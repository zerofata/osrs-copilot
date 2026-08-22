package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Small JSON-over-HTTP helper shared by the wiki tools and the LLM client. */
class Http
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** The wiki's APIs (prices.runescape.wiki especially) require a
	 * descriptive User-Agent identifying the consumer, and 400 anything
	 * anonymous. The repo URL is the contact channel they ask for, so they
	 * can reach out instead of block. Sent on every request. */
	private static final String USER_AGENT =
		"osrs-copilot RuneLite plugin (https://github.com/zerofata/osrs-copilot)";

	private final OkHttpClient client;
	private final Gson gson;

	Http(OkHttpClient base, Gson gson)
	{
		// LLM calls can legitimately take minutes on reasoning models.
		this.client = base.newBuilder()
			.readTimeout(180, TimeUnit.SECONDS)
			.addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
				.header("User-Agent", USER_AGENT).build()))
			.build();
		this.gson = gson;
	}

	JsonObject getJson(String url) throws IOException
	{
		Request req = new Request.Builder().url(url).build();
		return execute(req);
	}

	/** POST returning the raw response for streaming consumption (SSE).
	 * Caller must close the response. Throws with the error body on non-2xx. */
	Response postStream(String url, JsonObject body, Map<String, String> headers) throws IOException
	{
		Request.Builder builder = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(body)));
		headers.forEach(builder::header);
		Response resp = client.newCall(builder.build()).execute();
		if (!resp.isSuccessful())
		{
			String errBody = resp.body() != null ? resp.body().string() : "";
			resp.close();
			throw error(resp.code(), url, errBody);
		}
		return resp;
	}

	private JsonObject execute(Request req) throws IOException
	{
		try (Response resp = client.newCall(req).execute())
		{
			String body = resp.body() != null ? resp.body().string() : "";
			if (!resp.isSuccessful())
			{
				throw error(resp.code(), req.url().toString(), body);
			}
			return gson.fromJson(body, JsonObject.class);
		}
	}

	/** Builds an HttpException, extracting the server's error message from an
	 * OpenAI-style JSON error body when present. */
	private HttpException error(int code, String url, String body)
	{
		String serverMessage = "";
		try
		{
			JsonObject parsed = gson.fromJson(body, JsonObject.class);
			if (parsed != null && parsed.has("error") && parsed.get("error").isJsonObject())
			{
				JsonObject err = parsed.getAsJsonObject("error");
				if (err.has("message") && !err.get("message").isJsonNull())
				{
					serverMessage = err.get("message").getAsString();
				}
			}
		}
		catch (Exception ignored)
		{
			// Not JSON; fall through to the raw body.
		}
		if (serverMessage.isEmpty() && body != null && !body.isEmpty())
		{
			serverMessage = body.substring(0, Math.min(300, body.length()));
		}
		return new HttpException(code, url, serverMessage);
	}

	String getText(String url) throws IOException
	{
		Request req = new Request.Builder().url(url).build();
		try (Response resp = client.newCall(req).execute())
		{
			if (!resp.isSuccessful() || resp.body() == null)
			{
				throw new IOException("HTTP " + resp.code() + " from " + req.url());
			}
			return resp.body().string();
		}
	}

	byte[] getBytes(String url) throws IOException
	{
		Request req = new Request.Builder().url(url).build();
		try (Response resp = client.newCall(req).execute())
		{
			if (!resp.isSuccessful() || resp.body() == null)
			{
				throw new IOException("HTTP " + resp.code() + " from " + req.url());
			}
			return resp.body().bytes();
		}
	}

	static String enc(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
