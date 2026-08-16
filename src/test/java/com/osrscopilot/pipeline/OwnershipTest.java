package com.osrscopilot.pipeline;

import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class OwnershipTest
{
	private static GameCapture capture()
	{
		GameCapture cap = new GameCapture();
		cap.bank = List.of(
			Map.of("name", "Dragon arrow", "quantity", 4663),
			Map.of("name", "Rune platebody", "quantity", 1));
		cap.inventory = List.of(
			Map.of("name", "Dragon arrow", "quantity", 100));
		cap.equipment = List.of(
			Map.of("name", "Scorching bow", "quantity", 1));
		return cap;
	}

	@Test
	public void indexSumsQuantitiesAcrossContainers()
	{
		Map<String, long[]> owned = Ownership.buildIndex(capture());
		assertEquals(4763, owned.get("dragon arrow")[0]);
		assertEquals(1, owned.get("scorching bow")[0]);
	}

	@Test
	public void checkFindsExactAndPartialMatches()
	{
		GameCapture cap = capture();
		Map<String, long[]> owned = Ownership.buildIndex(cap);
		Map<String, String> names = Ownership.buildNames(cap);

		Object exact = Ownership.check(owned, names, "Scorching bow");
		assertEquals(Map.of("item", "Scorching bow", "owned", 1L), exact);

		Object partial = Ownership.check(owned, names, "arrow");
		assertTrue(partial instanceof Map);
		assertEquals(false, ((Map<?, ?>) partial).get("exact_match"));

		Object absent = Ownership.check(owned, names, "Twisted bow");
		assertEquals(Map.of("item", "Twisted bow", "owned", 0), absent);
	}

	@Test
	public void mentionsWordMatchesWholeWordsOnly()
	{
		assertTrue(Ownership.mentionsWord("bring a dragon arrow to the fight", "dragon arrow"));
		assertTrue(Ownership.mentionsWord("dragon arrows are best", "dragon arrow"));
		assertFalse(Ownership.mentionsWord("use the blowpipe here", "bow"));
	}
}
