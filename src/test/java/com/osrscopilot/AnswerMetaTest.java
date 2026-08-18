package com.osrscopilot;

import com.google.gson.Gson;
import com.osrscopilot.pipeline.CopilotPipeline;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The answer footer is the plugin's disclosure and attribution line: every
 * wiki page the answer drew on -- prefetched fact or tool fetch -- must link
 * its edit history (the contributors hold the copyright), game-state blocks
 * must display their short names without their model-facing instructions,
 * and nothing may leak unescaped HTML.
 */
public class AnswerMetaTest
{
	private static final Gson GSON = new Gson();

	private static String answerMeta(CopilotPipeline.Result result)
	{
		return CopilotPlugin.answerMeta(result, GSON);
	}

	private static CopilotPipeline.Result result(List<String> factTitles, List<String> toolLog)
	{
		CopilotPipeline.Result r = new CopilotPipeline.Result();
		r.factTitles = factTitles;
		r.toolLog = toolLog;
		return r;
	}

	@Test
	public void wikiFactsLinkTheirSourcePageHistory()
	{
		String meta = answerMeta(result(List.of("Monster info: Vorkath"), List.of()));
		assertTrue(meta.contains(
			"<a href='https://oldschool.runescape.wiki/w/Vorkath?action=history'>"));
		assertTrue("links must be visibly underlined", meta.contains("<u>Monster info: Vorkath</u>"));
	}

	@Test
	public void gameStateFactsDisplayShortAndUnlinked()
	{
		String meta = answerMeta(result(
			List.of("Quest progress (authoritative, from the game client)"), List.of()));
		assertTrue(meta.contains("Quest progress"));
		assertFalse(meta.contains("authoritative"));
		assertFalse(meta.contains("<a "));
	}

	@Test
	public void pageBackedToolCallsLinkAndSimplify()
	{
		String meta = answerMeta(result(List.of(),
			List.of("wiki_page({\"title\":\"Castaway\"})")));
		assertTrue(meta.contains(
			"<a href='https://oldschool.runescape.wiki/w/Castaway?action=history'>"));
		assertTrue(meta.contains("<u>wiki_page(Castaway)</u>"));
	}

	@Test
	public void searchCallsSimplifyWithoutLinking()
	{
		String meta = answerMeta(result(List.of(),
			List.of("wiki_search({\"query\":\"dog pet update 2025\"})")));
		assertTrue(meta.contains("wiki_search(dog pet update 2025)"));
		assertFalse(meta.contains("<a "));
		assertFalse("json syntax should not reach the display", meta.contains("&quot;query&quot;"));
	}

	@Test
	public void multiArgumentToolCallsFallBackToTheRawEntry()
	{
		String entry = "search_owned_items({\"queries\":[\"slayer helmet\",\"dragon boots\"]})";
		String meta = answerMeta(result(List.of(), List.of(entry)));
		assertTrue(meta.contains("search_owned_items"));
		assertTrue(meta.contains("slayer helmet"));
		assertFalse(meta.contains("<a "));
	}

	@Test
	public void emptyResultSaysSo()
	{
		assertEquals("no facts retrieved", answerMeta(result(List.of(), List.of())));
	}
}
