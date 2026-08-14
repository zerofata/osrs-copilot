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
			JsonObject msg = llm.chat(messages, toolSpecs, listener);
			JsonArray toolCalls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
				? msg.getAsJsonArray("tool_calls") : null;

			if (toolCalls == null || toolCalls.size() == 0)
			{
				result.answer = contentOf(msg);
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
				try
				{
					output = tool != null ? tool.call(args)
						: Map.of("error", "unknown tool: " + name);
				}
				catch (Exception e)
				{
					output = Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
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
		result.answer = contentOf(llm.chat(messages, null, listener));

		// Models trained on native tool tokens sometimes leak tool-call markup
		// as text when forced to answer without tools. Retry once.
		if (looksLikeToolMarkup(result.answer))
		{
			if (listener != null)
			{
				listener.onTurnDiscarded();
			}
			messages.add(message("user", "Tools are no longer available. Do NOT emit "
				+ "tool-call syntax. Write your final answer as plain prose now."));
			result.answer = contentOf(llm.chat(messages, null, listener));
		}
		return result;
	}

	private static String toolCallNames(JsonArray toolCalls)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < toolCalls.size(); i++)
		{
			JsonObject fn = toolCalls.get(i).getAsJsonObject().getAsJsonObject("function");
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(fn.get("name").getAsString());
		}
		return sb.toString();
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
