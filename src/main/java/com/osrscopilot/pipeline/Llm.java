package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okio.BufferedSource;

/** OpenAI-compatible chat-completions client. Endpoint, key, model, and
 * sampling all come from user settings -- nothing is hardcoded. */
@Slf4j
public class Llm
{
	/** User-configured connection + sampling settings, captured per request. */
	public static class Settings
	{
		public final String baseUrl;
		public final String apiKey;
		public final String model;
		public final double temperature;
		public final int maxTokens;

		public Settings(String baseUrl, String apiKey, String model, double temperature, int maxTokens)
		{
			// Tolerate trailing slashes and full /chat/completions URLs.
			String url = baseUrl == null ? "" : baseUrl.trim();
			while (url.endsWith("/"))
			{
				url = url.substring(0, url.length() - 1);
			}
			if (url.endsWith("/chat/completions"))
			{
				url = url.substring(0, url.length() - "/chat/completions".length());
			}
			this.baseUrl = url;
			this.apiKey = apiKey == null ? "" : apiKey.trim();
			this.model = model == null ? "" : model.trim();
			this.temperature = temperature;
			this.maxTokens = maxTokens;
		}

		public boolean isConfigured()
		{
			return !baseUrl.isEmpty() && !model.isEmpty();
		}
	}

	/** Token usage accumulated across every call this client instance makes
	 * (one instance per pipeline question, so totals are per-question). */
	public static class Usage
	{
		public int promptTokens;
		public int completionTokens;
		public int cachedPromptTokens;
		public int calls;
	}

	private final Http http;
	private final Gson gson;
	private final Settings settings;
	private final Usage usage = new Usage();

	Llm(Http http, Gson gson, Settings settings)
	{
		this.http = http;
		this.gson = gson;
		this.settings = settings;
	}

	Usage usage()
	{
		return usage;
	}

	private void recordUsage(JsonObject u)
	{
		if (u == null)
		{
			return;
		}
		usage.calls++;
		if (u.has("prompt_tokens") && !u.get("prompt_tokens").isJsonNull())
		{
			usage.promptTokens += u.get("prompt_tokens").getAsInt();
		}
		if (u.has("completion_tokens") && !u.get("completion_tokens").isJsonNull())
		{
			usage.completionTokens += u.get("completion_tokens").getAsInt();
		}
		// OpenAI-style cache reporting; absent on endpoints without it.
		if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject())
		{
			JsonObject d = u.getAsJsonObject("prompt_tokens_details");
			if (d.has("cached_tokens") && !d.get("cached_tokens").isJsonNull())
			{
				usage.cachedPromptTokens += d.get("cached_tokens").getAsInt();
			}
		}
	}

	/** One chat-completion call, always streamed (SSE); the returned
	 * message is the reassembled first choice. */
	JsonObject chat(JsonArray messages, JsonArray tools, StreamListener listener) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", settings.model);
		body.add("messages", messages);
		body.addProperty("temperature", settings.temperature);
		body.addProperty("max_tokens", settings.maxTokens);
		if (tools != null && tools.size() > 0)
		{
			body.add("tools", tools);
			body.addProperty("tool_choice", "auto");
		}
		Map<String, String> headers = settings.apiKey.isEmpty()
			? Map.of()
			: Map.of("Authorization", "Bearer " + settings.apiKey);
		String url = settings.baseUrl + "/chat/completions";

		body.addProperty("stream", true);
		JsonObject streamOptions = new JsonObject();
		streamOptions.addProperty("include_usage", true);
		body.add("stream_options", streamOptions);
		try (Response resp = http.postStream(url, body, headers))
		{
			return readSseStream(resp.body().source(), listener);
		}
	}

	/** Reassembles SSE delta chunks into a complete assistant message. */
	private JsonObject readSseStream(BufferedSource source, StreamListener listener) throws IOException
	{
		StringBuilder content = new StringBuilder();
		Map<Integer, JsonObject> toolCalls = new TreeMap<>();
		String finishReason = null;

		String line;
		while ((line = source.readUtf8Line()) != null)
		{
			if (!line.startsWith("data:"))
			{
				continue;
			}
			String data = line.substring(5).trim();
			if (data.equals("[DONE]"))
			{
				break;
			}
			JsonObject chunk = gson.fromJson(data, JsonObject.class);
			if (chunk.has("usage") && chunk.get("usage").isJsonObject())
			{
				recordUsage(chunk.getAsJsonObject("usage"));
			}
			JsonArray choices = chunk.getAsJsonArray("choices");
			if (choices == null || choices.size() == 0)
			{
				continue;
			}
			JsonObject choice = choices.get(0).getAsJsonObject();
			if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull())
			{
				finishReason = choice.get("finish_reason").getAsString();
			}
			JsonObject delta = choice.getAsJsonObject("delta");
			if (delta == null)
			{
				continue;
			}
			// reasoning_content deltas are intentionally not surfaced.
			if (delta.has("content") && !delta.get("content").isJsonNull())
			{
				String d = delta.get("content").getAsString();
				if (!d.isEmpty())
				{
					content.append(d);
					listener.onDelta(d);
				}
			}
			if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray())
			{
				for (JsonElement e : delta.getAsJsonArray("tool_calls"))
				{
					accumulateToolCallDelta(toolCalls, e.getAsJsonObject());
				}
			}
		}

		JsonObject msg = new JsonObject();
		msg.addProperty("role", "assistant");
		msg.addProperty("content", content.toString());
		if (finishReason != null)
		{
			msg.addProperty("finish_reason", finishReason);
		}
		if (!toolCalls.isEmpty())
		{
			JsonArray arr = new JsonArray();
			toolCalls.values().forEach(arr::add);
			msg.add("tool_calls", arr);
		}
		log.debug("llm call: finish={} contentChars={} toolCalls={}",
			finishReason, content.length(), toolCalls.size());
		return msg;
	}

	/** Tool calls stream as fragments keyed by index; id/name arrive once,
	 * arguments arrive as string pieces to concatenate. */
	private static void accumulateToolCallDelta(Map<Integer, JsonObject> toolCalls, JsonObject tc)
	{
		int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
		JsonObject acc = toolCalls.computeIfAbsent(idx, k -> {
			JsonObject o = new JsonObject();
			o.addProperty("type", "function");
			JsonObject f = new JsonObject();
			f.addProperty("name", "");
			f.addProperty("arguments", "");
			o.add("function", f);
			return o;
		});
		if (tc.has("id") && !tc.get("id").isJsonNull())
		{
			acc.addProperty("id", tc.get("id").getAsString());
		}
		JsonObject fn = tc.getAsJsonObject("function");
		if (fn != null)
		{
			JsonObject accFn = acc.getAsJsonObject("function");
			if (fn.has("name") && !fn.get("name").isJsonNull())
			{
				accFn.addProperty("name",
					accFn.get("name").getAsString() + fn.get("name").getAsString());
			}
			if (fn.has("arguments") && !fn.get("arguments").isJsonNull())
			{
				accFn.addProperty("arguments",
					accFn.get("arguments").getAsString() + fn.get("arguments").getAsString());
			}
		}
	}
}
