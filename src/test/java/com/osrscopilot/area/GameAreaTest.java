package com.osrscopilot.area;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the vendored area table's behavior at known world points. The
 * coordinates are hand-verified against the wiki's maps; a future re-sync
 * of the table that breaks any of these is a data regression, not drift.
 */
public class GameAreaTest
{
	private static GameArea at(int x, int y, int plane)
	{
		return GameArea.fromPoint(new WorldPoint(x, y, plane));
	}

	@Test
	public void surfaceCities()
	{
		assertEquals(GameArea.LUMBRIDGE, at(3222, 3218, 0));
		assertEquals(GameArea.VARROCK, at(3212, 3428, 0));
		assertEquals(GameArea.FALADOR, at(2965, 3380, 0));
		assertEquals(GameArea.GRAND_EXCHANGE, at(3164, 3487, 0));
		// Varlamore (2024+): newest expansion present in the table.
		assertEquals(GameArea.CIVITAS_ILLA_FORTIS, at(1750, 3100, 0));
	}

	@Test
	public void undergroundAreas()
	{
		assertEquals(GameArea.TAVERLEY_DUNGEON, at(2885, 9798, 0));
		assertEquals(GameArea.LUMBRIDGE_SWAMP_CAVES, at(3170, 9560, 0));
		assertEquals(GameArea.VARROCK_SEWERS, at(3237, 9858, 0));
	}

	/** Boss rooms are sub-region rectangles overlaid on the dungeon's
	 * full regions; fromPoint must prefer the rectangle. */
	@Test
	public void subRegionBeatsFullRegion()
	{
		// General Graardor's room is a rectangle inside GWD region 11347.
		assertEquals(GameArea.GENERAL_GRAARDOR, at(2870, 5360, 0));
		// Same region, outside the rectangle: the surrounding dungeon.
		assertEquals(GameArea.GOD_WARS_DUNGEON, at(2820, 5330, 0));
	}

	/** The Warriors' Guild building is carved out of Burthorpe's region
	 * with different footprints per plane. */
	@Test
	public void planeDependentFootprints()
	{
		assertEquals(GameArea.WARRIORS_GUILD, at(2850, 3545, 0));
		assertEquals(GameArea.BURTHORPE, at(2850, 3533, 0));
		// Upstairs footprint extends further south than the ground floor.
		assertEquals(GameArea.WARRIORS_GUILD, at(2850, 3533, 1));
	}

	/** The three MTA rooms share one region, split by plane. */
	@Test
	public void planeSplitRooms()
	{
		assertEquals(GameArea.MTA_ENCHANTING_CHAMBER, at(3350, 9630, 0));
		assertEquals(GameArea.MTA_CREATURE_GRAVEYARD, at(3350, 9630, 1));
		assertEquals(GameArea.MTA_ALCHEMISTS_PLAYGROUND, at(3350, 9630, 2));
		// NMZ shares its region with plane 0 content; only planes >= 1 match.
		assertEquals(GameArea.NIGHTMARE_ZONE, at(2270, 4680, 1));
	}

	@Test
	public void rectangleBoundariesAreInclusive()
	{
		// Graardor room corners: (2863, 5351) to (2876, 5369).
		assertEquals(GameArea.GENERAL_GRAARDOR, at(2863, 5351, 0));
		assertEquals(GameArea.GENERAL_GRAARDOR, at(2876, 5369, 0));
		assertEquals(GameArea.GOD_WARS_DUNGEON, at(2862, 5351, 0));
		assertEquals(GameArea.GOD_WARS_DUNGEON, at(2876, 5370, 0));
	}

	@Test
	public void unmappedPointResolvesToNull()
	{
		assertNull(at(1000, 1000, 0));
	}

	/** The supplement covers content the vendored table lacks; it must
	 * only ever fill gaps, never shadow the table. */
	@Test
	public void supplementFillsGaps()
	{
		// Post-table content: Varlamore part 2 (2024) and Sailing (2025).
		assertEquals("Aldarin", Areas.resolve(new WorldPoint(1391, 2935, 0)));
		assertEquals("Port Roberts", Areas.resolve(new WorldPoint(1888, 3298, 0)));
		// Never in the table: Ferox Enclave and the surface Wilderness.
		assertEquals("Ferox Enclave", Areas.resolve(new WorldPoint(3137, 3623, 0)));
		assertEquals("the Wilderness (level 34)", Areas.resolve(new WorldPoint(3100, 3788, 0)));
		// Named wilderness POIs beat the generic wilderness rule.
		assertEquals("Resource Area", Areas.resolve(new WorldPoint(3185, 3934, 0)));
		// The vendored table still wins where it has data.
		assertEquals("Lumbridge", Areas.resolve(new WorldPoint(3222, 3218, 0)));
		assertEquals("Kraken (boss area)", Areas.resolve(new WorldPoint(2280, 10022, 0)));
		assertNull(Areas.resolve(new WorldPoint(1000, 1000, 0)));
	}

	/** Two areas claiming the same full region would make fromPoint's
	 * answer depend on enum declaration order. Sub-region overlays on a
	 * full region are fine; duplicate full-region claims are data bugs. */
	@Test
	public void noDuplicateFullRegionClaims()
	{
		Map<Integer, GameArea> claimed = new HashMap<>();
		StringJoiner dupes = new StringJoiner("; ");
		for (GameArea area : GameArea.values())
		{
			for (int region : area.getFullRegions())
			{
				GameArea prior = claimed.put(region, area);
				if (prior != null)
				{
					dupes.add("region " + region + ": " + prior + " and " + area);
				}
			}
		}
		assertTrue("duplicate full-region claims: " + dupes, dupes.length() == 0);
	}
}
