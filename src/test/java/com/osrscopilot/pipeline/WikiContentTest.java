package com.osrscopilot.pipeline;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WikiContentTest
{
	@Test
	public void tocListsTopLevelSectionsInOrder()
	{
		String page = "Lead prose.\n"
			+ "== Basic overview ==\nprose\n"
			+ "=== Starting items ===\nsub-section prose\n"
			+ "== Loot table ==\nrows\n"
			+ "== Combat Achievements ==\ntasks\n";
		assertEquals("[Sections: Basic overview; Loot table; Combat Achievements]\n\n",
			WikiContent.tocLine(page));
	}

	@Test
	public void tocExcludesAppendixNoise()
	{
		String page = "Lead.\n"
			+ "== Strategy ==\nprose\n"
			+ "== Rewards ==\nrows\n"
			+ "== Changes ==\nchangelog\n"
			+ "== References ==\ncitations\n"
			+ "== Trivia ==\nfacts\n";
		assertEquals("[Sections: Strategy; Rewards]\n\n", WikiContent.tocLine(page));
	}

	@Test
	public void singleSectionPagesGetNoToc()
	{
		assertEquals("", WikiContent.tocLine("Lead.\n== Only section ==\nprose\n"));
		assertEquals("", WikiContent.tocLine("Short item page with no sections at all."));
	}

	@Test
	public void wikitextHeadingsWithoutSpacesAlsoMatch()
	{
		String page = "Lead.\n==Loot table==\nrows\n==Money making==\nprose\n";
		assertTrue(WikiContent.tocLine(page).contains("Loot table; Money making"));
		assertFalse(WikiContent.tocLine(page).contains("=="));
	}

	// --- scrubWikitext: markup with zero semantic content ---

	@Test
	public void fileLinksWithNestedCaptionLinksAreRemovedWhole()
	{
		String text = "Before. [[File:Tombs of Amascut.png|thumb|350px|The "
			+ "[[invocation]] interface where [[Raid level|levels]] show.]] After.";
		assertEquals("Before.  After.", WikiContent.scrubWikitext(text));
	}

	@Test
	public void galleryBlocksAndCitationTemplatesAreRemoved()
	{
		String text = "Intro.\n<gallery mode=\"packed\" heights=\"180\">\n"
			+ "File:Fighting Akkha.png|[[Akkha]], Guardian of Het.\n</gallery>\n"
			+ "Levels are random.{{CiteTwitter|author=Mod Ash|url=https://x.com/1|"
			+ "quote=Looks random {{sic}} to me.}} More prose.";
		String scrubbed = WikiContent.scrubWikitext(text);
		assertEquals("Intro.\n\nLevels are random. More prose.", scrubbed);
	}

	@Test
	public void sortValueAttributesGoContentStays()
	{
		String row = "| data-sort-value=\"Try Again\" | Try Again\n|+5\n";
		assertEquals("| Try Again\n|+5\n", WikiContent.scrubWikitext(row));
	}

	@Test
	public void unclosedConstructsAreLeftUntouched()
	{
		String broken = "Prose [[File:cut off mid page";
		assertEquals(broken, WikiContent.scrubWikitext(broken));
	}

	@Test
	public void ordinaryLinksAndTemplatesSurviveScrubbing()
	{
		String text = "The [[Bandos chestplate]] needs {{SCP|Defence|65}} and "
			+ "[[Bow of Faerdhinen|bowfa]] works too.";
		assertEquals(text, WikiContent.scrubWikitext(text));
	}

	// --- budgetBySections: whole sections, never a mid-table cut ---

	private static String repeat(String s, int n)
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			b.append(s);
		}
		return b.toString();
	}

	@Test
	public void underBudgetPagesPassThroughUntouched()
	{
		String page = "Lead.\n== A ==\nshort\n== Trivia ==\nkept when under budget\n";
		assertEquals(page, WikiContent.budgetBySections(page, 10_000));
	}

	@Test
	public void overBudgetDropsWholeSectionsNeverCutsMidSection()
	{
		String page = "Lead.\n"
			+ "== Requirements ==\n" + repeat("req ", 100)
			+ "\n== Rewards ==\n" + repeat("gp ", 100)
			+ "\n== Lore ==\n" + repeat("myth ", 200);
		String out = WikiContent.budgetBySections(page, 950);
		assertTrue(out.startsWith("Lead."));
		assertTrue(out.contains("== Requirements =="));
		assertTrue("a section that fits after a skipped one is still admitted",
			out.contains("== Rewards =="));
		assertFalse(out.contains("== Lore =="));
		assertFalse("no partial section content may leak", out.contains("myth"));
	}

	@Test
	public void noiseSectionsAreDroppedFirstWhenOverBudget()
	{
		String page = "Lead.\n"
			+ "== Official worlds ==\n" + repeat("world ", 50)
			+ "\n== Rewards ==\n" + repeat("gp ", 50);
		String out = WikiContent.budgetBySections(page, page.length() - 10);
		assertFalse(out.contains("Official worlds"));
		assertTrue(out.contains("== Rewards =="));
	}

	@Test
	public void giantSectionIsWeighedLastSoTheRestOfThePageSurvives()
	{
		// The ToA shape: a huge early table (invocations) followed by the
		// sections the question needs. Position-cut truncation shipped the
		// table and starved the tail; section budgeting must do the reverse.
		String page = "Lead.\n"
			+ "== Invocations ==\n" + repeat("invocation row ", 500)
			+ "\n== Money making ==\n" + repeat("profit ", 60)
			+ "\n== Rewards ==\n" + repeat("unique ", 60);
		String out = WikiContent.budgetBySections(page, 2000);
		assertTrue(out.contains("== Money making =="));
		assertTrue(out.contains("== Rewards =="));
		assertFalse(out.contains("== Invocations =="));
	}

	@Test
	public void giantSectionStillAdmittedWhenRoomRemains()
	{
		String page = "Lead.\n"
			+ "== Strategy ==\n" + repeat("step ", 400)
			+ "\n== Trivia ==\nnoise stuff\n"
			+ "== Notes ==\nshort\n";
		// Dropping the noise section frees enough budget that the deferred
		// giant is admitted after the small section.
		String out = WikiContent.budgetBySections(page, page.length() - 10);
		assertTrue(out.contains("== Notes =="));
		assertTrue(out.contains("== Strategy =="));
		assertFalse(out.contains("== Trivia =="));
	}

	@Test
	public void headinglessAndGiantOnlyPagesFallBackToPositionCut()
	{
		String headingless = repeat("one long table row ", 400);
		assertEquals(1000, WikiContent.budgetBySections(headingless, 1000).length());
		// A page whose whole substance is one giant section: packing whole
		// sections would ship a bare lead, so position-cut wins.
		String giantOnly = "Tiny lead.\n== Locations ==\n" + repeat("row ", 2000);
		String out = WikiContent.budgetBySections(giantOnly, 1000);
		assertEquals(1000, out.length());
		assertTrue(out.contains("== Locations =="));
	}
}
