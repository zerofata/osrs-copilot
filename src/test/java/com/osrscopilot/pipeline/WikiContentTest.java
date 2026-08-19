package com.osrscopilot.pipeline;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WikiContentTest
{
	@Test
	public void shortTextPassesThroughUntouched()
	{
		assertEquals("all of it", WikiContent.truncateNoted("all of it", 100));
	}

	@Test
	public void truncationNoteNamesDroppedSections()
	{
		String page = "Intro prose that fills the budget.\n"
			+ "== Kept section ==\nmore prose\n"
			+ "== Loot table ==\ntable rows\n"
			+ "=== Unique Rewards ===\nmore rows\n"
			+ "== Combat Achievements ==\ntasks";
		String out = WikiContent.truncateNoted(page, page.indexOf("== Loot table =="));
		assertTrue(out.contains("Sections not shown: Loot table; Unique Rewards; Combat Achievements"));
		assertTrue(out.contains("\"section\""));
		assertFalse(out.contains("table rows"));
	}

	@Test
	public void headinglessTailStillGetsAMarker()
	{
		String out = WikiContent.truncateNoted("prose ".repeat(50), 30);
		assertTrue(out.endsWith("[Page truncated at budget.]"));
	}
}
