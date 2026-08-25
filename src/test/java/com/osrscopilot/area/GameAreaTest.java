package com.osrscopilot.area;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GameAreaTest
{
	@Test
	public void resolvesKnownPoints()
	{
		assertEquals(GameArea.LUMBRIDGE, GameArea.fromPoint(new WorldPoint(3222, 3218, 0)));
		assertEquals(GameArea.GRAND_EXCHANGE, GameArea.fromPoint(new WorldPoint(3164, 3487, 0)));
		assertEquals(GameArea.TAVERLEY_DUNGEON, GameArea.fromPoint(new WorldPoint(2885, 9798, 0)));
	}

	@Test
	public void respectsPlaneRestrictions()
	{
		// Nightmare Zone spans region 9033 on planes >= 1 only.
		assertEquals(GameArea.NIGHTMARE_ZONE, GameArea.fromPoint(new WorldPoint(2270, 4680, 1)));
	}

	@Test
	public void unmappedPointResolvesToNull()
	{
		assertNull(GameArea.fromPoint(new WorldPoint(1000, 1000, 0)));
	}
}
