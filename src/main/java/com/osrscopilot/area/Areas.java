package com.osrscopilot.area;

import net.runelite.api.coords.WorldPoint;

/**
 * Resolves a world point to a display name: the vendored {@link GameArea}
 * table first, then {@link AreaSupplement} for content the table lacks.
 * Null when neither knows the point.
 */
public final class Areas
{
	private Areas()
	{
	}

	public static String resolve(WorldPoint point)
	{
		GameArea area = GameArea.fromPoint(point);
		if (area != null)
		{
			return area.getState() + qualifier(area.getGameAreaType());
		}
		return AreaSupplement.name(point);
	}

	/** Area names like "Kraken" or "Barrows" read as monsters or activities
	 * without a hint that they name the player's surroundings. */
	private static String qualifier(GameAreaType type)
	{
		switch (type)
		{
			case BOSSES:
				return " (boss area)";
			case MINIGAMES:
				return " (minigame area)";
			case RAIDS:
				return " (raid)";
			default:
				return "";
		}
	}
}
