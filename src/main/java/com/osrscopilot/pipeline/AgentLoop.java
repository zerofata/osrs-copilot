package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Tool-calling loop: the model may call tools for a bounded number of turns,
 * then is forced to answer from what it has gathered. */
@Slf4j
class AgentLoop
{
	/**
	 * Upper bound on tool calls executed from a single model message. The
	 * endpoint is user-configured and untrusted: without this cap, one
	 * broken or hostile model message could fan out into hundreds of
	 * sequential wiki requests. The batched tools keep legitimate turns
	 * well under it; excess calls get an error result (the protocol
	 * requires a tool response per call id) telling the model to answer.
	 */
	private static final int MAX_TOOL_CALLS_PER_TURN = 8;

	interface Tool
	{
		Object call(JsonObject args) throws Exception;
	}

	static class Result
	{
		String answer = "";
		final List<String> toolLog = new ArrayList<>();
		int turns;
	}

	/** Runs on a prepared message list (system + any conversation history +
	 * the current user turn), mutating it as tool calls resolve. */
	static Result run(Llm llm, Gson gson, JsonArray messages,
		JsonArray toolSpecs, Map<String, Tool> tools, int maxTurns,
		StreamListener listener) throws IOException
	{
		Result result = new Result();

		for (int turn = 0; turn < maxTurns; turn++)
		{
			result.turns = turn + 1;
			// The warning must precede the final turn: a cutoff discovered
			// after the fact leaks tool markup instead of prose.
			if (turn == maxTurns - 1 && turn > 0)
			{
				messages.add(message("user", "Research budget nearly exhausted: any tool "
					+ "calls in your next message are the LAST that will be executed. "
					+ "Prefer answering now from what you have gathered."));
			}
			JsonObject msg = llm.chat(messages, toolSpecs, listener);
			JsonArray toolCalls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
				? msg.getAsJsonArray("tool_calls") : null;

			if (toolCalls == null || toolCalls.size() == 0)
			{
				result.answer = ensureAnswer(llm, messages, msg, listener, result);
				return result;
			}

			// Any text streamed this turn was preamble to tool calls, not
			// the answer -- take it back off the display.
			if (listener != null)
			{
				listener.onTurnDiscarded();
				listener.onStatus("Looking up: " + toolCallNames(toolCalls));
			}

			JsonObject assistant = message("assistant", contentOf(msg));
			assistant.add("tool_calls", toolCalls);
			messages.add(assistant);

			for (int i = 0; i < toolCalls.size(); i++)
			{
				JsonObject call = toolCalls.get(i).getAsJsonObject();
				JsonObject fn = call.getAsJsonObject("function");
				String name = fn.get("name").getAsString();
				JsonObject args;
				try
				{
					args = gson.fromJson(fn.get("arguments").getAsString(), JsonObject.class);
				}
				catch (Exception e)
				{
					args = new JsonObject();
				}
				if (args == null)
				{
					args = new JsonObject();
				}

				Object output;
				Tool tool = tools.get(name);
				if (i >= MAX_TOOL_CALLS_PER_TURN)
				{
					output = Map.of("error", "tool call budget for this turn exceeded; "
						+ "answer from what you have");
				}
				else
				{
					try
					{
						output = tool != null ? tool.call(args)
							: Map.of("error", "unknown tool: " + name);
					}
					catch (Exception e)
					{
						output = Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
					}
				}
				String outputJson = output instanceof String ? (String) output : gson.toJson(output);
				result.toolLog.add(name + "(" + gson.toJson(args) + ")");
				log.debug("tool {} -> {}B", name, outputJson.length());

				JsonObject toolMsg = message("tool", outputJson);
				toolMsg.addProperty("tool_call_id",
					call.has("id") ? call.get("id").getAsString() : String.valueOf(i));
				messages.add(toolMsg);
			}
		}

		// Turn budget exhausted: force a final answer without tools.
		messages.add(message("user",
			"Stop researching. Give your final answer now using the facts you have gathered."));
		result.answer = ensureAnswer(llm, messages,
			llm.chat(messages, null, listener), listener, result);
		return result;
	}

	/**
	 * An empty or tool-markup "answer" is a failure, not a result. Two
	 * causes need different correctives: a reasoning model that burned the
	 * whole completion budget thinking (finish_reason=length) needs room
	 * and brevity, while a model leaking tool-call markup as text needs
	 * telling that tools are gone. Retries once with the matching
	 * corrective, then throws; the exception carries the tool trace and
	 * truncation diagnosis, and the panel's error path offers resubmission.
	 */
	private static String ensureAnswer(Llm llm, JsonArray messages, JsonObject msg,
		StreamListener listener, Result result) throws IOException
	{
		String answer = contentOf(msg);
		if (!looksLikeToolMarkup(answer))
		{
			return answer;
		}
		if (listener != null)
		{
			listener.onTurnDiscarded();
		}
		boolean truncated = truncatedByLength(msg);
		messages.add(message("user", truncated
			? "Your previous response hit the token limit before any answer text "
				+ "appeared. Answer now in plain prose. Be brief: a compact answer "
				+ "beats a complete one that gets cut off again."
			: "Tools are no longer available. Do NOT emit tool-call syntax. "
				+ "Write your final answer as plain prose now."));
		JsonObject retryMsg = llm.chat(messages, null, listener);
		String retry = contentOf(retryMsg);
		if (looksLikeToolMarkup(retry))
		{
			throw new EmptyAnswerException(result.turns, toolNames(result.toolLog),
				truncated || truncatedByLength(retryMsg));
		}
		return retry;
	}

	private static boolean truncatedByLength(JsonObject msg)
	{
		return msg.has("finish_reason") && !msg.get("finish_reason").isJsonNull()
			&& "length".equals(msg.get("finish_reason").getAsString());
	}

	/** Bare tool names from the log's "name({args})" entries. */
	private static String toolNames(List<String> toolLog)
	{
		List<String> names = new ArrayList<>();
		for (String entry : toolLog)
		{
			int paren = entry.indexOf('(');
			names.add(paren > 0 ? entry.substring(0, paren) : entry);
		}
		return String.join(", ", names);
	}

	private static String toolCallNames(JsonArray toolCalls)
	{
		List<String> names = new ArrayList<>();
		for (int i = 0; i < toolCalls.size(); i++)
		{
			names.add(toolCalls.get(i).getAsJsonObject()
				.getAsJsonObject("function").get("name").getAsString());
		}
		return String.join(", ", names);
	}

	static JsonObject message(String role, String content)
	{
		JsonObject msg = new JsonObject();
		msg.addProperty("role", role);
		msg.addProperty("content", content == null ? "" : content);
		return msg;
	}

	private static String contentOf(JsonObject msg)
	{
		return msg.has("content") && !msg.get("content").isJsonNull()
			? msg.get("content").getAsString() : "";
	}

	private static boolean looksLikeToolMarkup(String text)
	{
		String stripped = text == null ? "" : text.trim();
		return stripped.isEmpty() || stripped.contains("tool_calls")
			|| stripped.contains("invoke name=") || stripped.startsWith("<");
	}
}
