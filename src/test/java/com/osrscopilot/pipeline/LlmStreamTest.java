package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import okio.Buffer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the SSE stream parser against provider behaviors we rely on,
 * OpenRouter's in particular: keep-alive comment lines, usage in the
 * final chunk, fragmented tool calls, and mid-stream error chunks
 * delivered under an already-committed 200 status.
 */
public class LlmStreamTest
{
	private static class Recorder implements StreamListener
	{
		final StringBuilder deltas = new StringBuilder();
		boolean discarded;

		@Override
		public void onDelta(String text)
		{
			deltas.append(text);
		}

		@Override
		public void onTurnDiscarded()
		{
			discarded = true;
		}

		@Override
		public void onStatus(String status)
		{
		}
	}

	private final Recorder listener = new Recorder();
	private final Llm llm = new Llm(null, new Gson(),
		new Llm.Settings("https://openrouter.ai/api/v1", "k", "m", 0.7, 512));

	private JsonObject parse(String... sseLines) throws IOException
	{
		Buffer source = new Buffer();
		for (String line : sseLines)
		{
			source.writeUtf8(line).writeUtf8("\n");
		}
		return llm.readSseStream(source, listener);
	}

	@Test
	public void reassemblesContentDeltas() throws IOException
	{
		JsonObject msg = parse(
			"data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
			"data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
			"data: [DONE]");
		assertEquals("Hello", msg.get("content").getAsString());
		assertEquals("stop", msg.get("finish_reason").getAsString());
		assertEquals("Hello", listener.deltas.toString());
	}

	/** OpenRouter interleaves ": OPENROUTER PROCESSING" keep-alive
	 * comments; per the SSE spec they must be ignored, not parsed. */
	@Test
	public void ignoresSseCommentsAndBlankLines() throws IOException
	{
		JsonObject msg = parse(
			": OPENROUTER PROCESSING",
			"",
			"data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
			": OPENROUTER PROCESSING",
			"data: [DONE]");
		assertEquals("ok", msg.get("content").getAsString());
	}

	/** Usage arrives in the final chunk, which may have no choices. */
	@Test
	public void recordsUsageFromFinalChunk() throws IOException
	{
		parse(
			"data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
			"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":7,"
				+ "\"prompt_tokens_details\":{\"cached_tokens\":60}}}",
			"data: [DONE]");
		assertEquals(100, llm.usage().promptTokens);
		assertEquals(7, llm.usage().completionTokens);
		assertEquals(60, llm.usage().cachedPromptTokens);
	}

	@Test
	public void reassemblesFragmentedToolCall() throws IOException
	{
		JsonObject msg = parse(
			"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
				+ "\"function\":{\"name\":\"wiki_page\",\"arguments\":\"{\\\"title\\\":\"}}]}}]}",
			"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"arguments\":\"\\\"Zulrah\\\"}\"}}]}}]}",
			"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
			"data: [DONE]");
		JsonObject call = msg.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
		assertEquals("c1", call.get("id").getAsString());
		JsonObject fn = call.getAsJsonObject("function");
		assertEquals("wiki_page", fn.get("name").getAsString());
		assertEquals("{\"title\":\"Zulrah\"}", fn.get("arguments").getAsString());
	}

	/** A mid-stream failure arrives as a chunk with a top-level error
	 * object under HTTP 200. It must surface as an exception carrying the
	 * server's message, not as a truncated or empty answer, and the
	 * partial text must be pulled off the display. */
	@Test
	public void midStreamErrorThrowsWithServerMessage()
	{
		try
		{
			parse(
				"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
				"data: {\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\","
					+ "\"metadata\":{\"error_type\":\"rate_limit_exceeded\"}},"
					+ "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},"
					+ "\"finish_reason\":\"error\"}]}");
			fail("expected HttpException");
		}
		catch (HttpException e)
		{
			assertEquals(429, e.code);
			assertEquals("Rate limit exceeded", e.serverMessage);
		}
		catch (IOException e)
		{
			fail("expected HttpException, got " + e);
		}
		assertTrue("partial text must be discarded", listener.discarded);
	}

	/** Internal failures carry a string code ("server_error") instead of
	 * an HTTP status; those map to code 0. */
	@Test
	public void midStreamErrorWithStringCode()
	{
		try
		{
			parse("data: {\"error\":{\"code\":\"server_error\","
				+ "\"message\":\"Provider disconnected unexpectedly\"},"
				+ "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},"
				+ "\"finish_reason\":\"error\"}]}");
			fail("expected HttpException");
		}
		catch (HttpException e)
		{
			assertEquals(0, e.code);
			assertEquals("Provider disconnected unexpectedly", e.serverMessage);
		}
		catch (IOException e)
		{
			fail("expected HttpException, got " + e);
		}
	}

	/** A clean stream must never signal a discard. */
	@Test
	public void cleanStreamDoesNotDiscard() throws IOException
	{
		parse(
			"data: {\"choices\":[{\"delta\":{\"content\":\"fine\"},\"finish_reason\":\"stop\"}]}",
			"data: [DONE]");
		assertFalse(listener.discarded);
	}

	/** Endpoints that end the stream without [DONE] (connection close)
	 * still yield whatever content arrived. */
	@Test
	public void streamWithoutDoneMarkerStillYieldsContent() throws IOException
	{
		JsonObject msg = parse(
			"data: {\"choices\":[{\"delta\":{\"content\":\"abrupt\"}}]}");
		assertEquals("abrupt", msg.get("content").getAsString());
	}
}
