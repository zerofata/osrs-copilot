package com.osrscopilot.pipeline;

/** Official OSRS experience table math. */
final class XpTable
{
	private XpTable()
	{
	}

	static int xpForLevel(int level)
	{
		double points = 0;
		for (int lvl = 1; lvl < level; lvl++)
		{
			points += Math.floor(lvl + 300 * Math.pow(2, lvl / 7.0));
		}
		return (int) Math.floor(points / 4);
	}

	/** Returns {currentLevel, nextLevel (or -1 at 99), xpNeeded}. */
	static int[] toNextLevel(int currentXp)
	{
		int level = 1;
		for (int lvl = 2; lvl < 100; lvl++)
		{
			if (currentXp >= xpForLevel(lvl))
			{
				level = lvl;
			}
		}
		if (level >= 99)
		{
			return new int[]{99, -1, 0};
		}
		return new int[]{level, level + 1, xpForLevel(level + 1) - currentXp};
	}
}
