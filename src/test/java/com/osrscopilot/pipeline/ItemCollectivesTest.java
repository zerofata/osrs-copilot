package com.osrscopilot.pipeline;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ItemCollectivesTest
{
	@Test
	public void substitutionKeepsQualifiers()
	{
		List<String> out = ItemCollectives.expand("imbued god cape");
		assertTrue(out.contains("imbued saradomin cape"));
		assertTrue(out.contains("imbued zamorak cape"));
		assertTrue(out.contains("imbued guthix cape"));
		assertTrue("max variants are separate names, not substring-reachable",
			out.contains("imbued zamorak max cape"));
	}

	@Test
	public void wholeQueryCollectiveExpandsToItsParts()
	{
		List<String> out = ItemCollectives.expand("dwarf multicannon");
		assertEquals(List.of("cannon base", "cannon stand",
			"cannon barrels", "cannon furnace"), out);
	}

	@Test
	public void plainNamesPassThroughUntouched()
	{
		assertEquals(List.of("abyssal whip"), ItemCollectives.expand("abyssal whip"));
	}
}
