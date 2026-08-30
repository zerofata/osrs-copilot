package com.osrscopilot.pipeline;

import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertArrayEquals;
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
	public void indexTracksQuantitiesPerLocation()
	{
		Map<String, long[]> owned = Ownership.buildIndex(capture());
		assertArrayEquals(new long[]{100, 0, 4663}, owned.get("dragon arrow"));
		assertArrayEquals(new long[]{0, 1, 0}, owned.get("scorching bow"));
		assertArrayEquals(new long[]{0, 0, 1}, owned.get("rune platebody"));
		assertEquals(4763, Ownership.total(owned.get("dragon arrow")));
	}

	@Test
	public void whereLabelCountsOnlyWhenLocationsMix()
	{
		assertEquals("banked", Ownership.whereLabel(new long[]{0, 0, 4663}));
		assertEquals("carried", Ownership.whereLabel(new long[]{3, 0, 0}));
		assertEquals("equipped", Ownership.whereLabel(new long[]{0, 1, 0}));
		assertEquals("100 carried, 4663 banked",
			Ownership.whereLabel(new long[]{100, 0, 4663}));
		assertEquals("2 carried, 1 equipped, 5 banked",
			Ownership.whereLabel(new long[]{2, 1, 5}));
	}

	@Test
	public void sliceLabelsEachOwnedEntryWithItsLocation()
	{
		GameCapture cap = capture();
		Ownership.Slice slice = Ownership.slice(
			"bring dragon arrows and a scorching bow; a twisted bow also works",
			Ownership.buildIndex(cap), Ownership.buildNames(cap),
			List.of(new ItemDescriptor("Dragon arrow", "Dragon arrow", null, false, null, null),
				new ItemDescriptor("Scorching bow", "Scorching bow", null, false, null, null),
				new ItemDescriptor("Twisted bow", "Twisted bow", null, false, null, null)));
		assertTrue(slice.text.contains("Dragon arrow x4763 (100 carried, 4663 banked)"));
		assertTrue(slice.text.contains("Scorching bow (equipped)"));
		assertTrue("locations never leak into the not-owned list",
			slice.text.contains("NOT OWNED (verified absent at capture): Twisted bow"));
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

	@Test
	public void baseNameStripsTrailingQualifiers()
	{
		assertEquals("Prayer potion", Ownership.baseName("Prayer potion(4)"));
		assertEquals("Slayer helmet", Ownership.baseName("Slayer helmet (i)"));
		assertEquals("Rune platebody", Ownership.baseName("Rune platebody"));
	}

	@Test
	public void wordMatchEndExtendsPluralsAndRejectsMidWordSpans()
	{
		assertEquals(13, Ownership.wordMatchEnd("dragon arrows", 7, 5));
		assertEquals(3, Ownership.wordMatchEnd("bow", 0, 3));
		assertEquals(-1, Ownership.wordMatchEnd("longbows here", 4, 3));
	}
}
