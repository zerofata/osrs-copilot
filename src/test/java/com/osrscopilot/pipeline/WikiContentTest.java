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
}
