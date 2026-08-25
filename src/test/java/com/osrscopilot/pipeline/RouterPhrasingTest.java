package com.osrscopilot.pipeline;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Table-driven phrasing corpus over Router.classifyNeeds. Every phrasing a
 * real player used that missed becomes a one-line row here. No network, no
 * resolver -- entity presence is a flag.
 */
public class RouterPhrasingTest
{
	/** question / monsterResolved / itemResolved / needs that MUST appear. */
	private static final Object[][] POSITIVE = {
		// --- Strategy + mechanics: framed fight questions (no entity needed) ---
		{"how to kill vorkath", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do i kill vorkath", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do you kill zulrah", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do we kill the corp beast", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how can i beat jad", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how can you beat the hunllef", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how should i fight kril", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how should we fight bandos", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how would i defeat the whisperer", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do i defeat the phantom muspah", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do you defeat sotetseg", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how to solo bandos", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how do i solo the kalphite queen", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"best way to kill hydra", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"best way to fight the leviathan", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"whats the strategy for zulrah", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"is there a safespot for the kbd", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how to fight vardorvis", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how can we defeat duke sucellus", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"how should i beat the fight caves", false, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},

		// --- Strategy + mechanics: entity-conditioned (monster + fight verb, no frame) ---
		{"can i kill vorkath without a shield", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"is it possible to beat muspah with melee", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"killing zulrah with a blowpipe only", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"can you solo the corp beast", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"is vorkath worth fighting at 80 range", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"i keep dying fighting the hunllef", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"should i fight jad with range or mage", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"anyone defeated the whisperer on a pure", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"i fought kril and got smoked", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},
		{"can i defeat vardorvis at 70 attack", true, false, need(Router.NEED_STRATEGY, Router.NEED_MECHANICS)},

		// --- Strategy via gear phrasing ---
		{"what gear for zulrah", false, false, need(Router.NEED_STRATEGY)},
		{"best setup for cox", false, false, need(Router.NEED_STRATEGY)},
		{"what equipment do i need for the gauntlet", false, false, need(Router.NEED_STRATEGY)},
		{"whats a good loadout for barrows", false, false, need(Router.NEED_STRATEGY)},
		{"what should i wear for fire giants", false, false, need(Router.NEED_STRATEGY)},
		{"what do i bring to the inferno", false, false, need(Router.NEED_STRATEGY)},

		// --- Item sources: framed obtain questions ---
		{"where do i get a dragon scimitar", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"where can i get addy bars", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"wheres the best place to get pure essence", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"where do you find ranarr seeds", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"where can i buy a rune axe", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how do i get a fire cape", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how do i obtain a dwh", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how can i make super combats", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how do you craft nature runes", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how to farm snapdragons", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how should i get my first bond", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"how would you get a trident", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"where do i get cannonballs", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"wheres a good spot to get yew logs", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"quickest way to get a rune pouch", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"fastest way to obtain a slayer helm", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"easiest way to make prayer potions", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"best place to get sharks", false, false, need(Router.NEED_ITEM_SOURCES)},
		{"fastest method to get marks of grace", false, false, need(Router.NEED_ITEM_SOURCES)},

		// --- Item sources: entity-conditioned (item + obtain verb, no frame) ---
		{"i want to get a bowfa", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"thinking about buying a tbow", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"can these be crafted", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"is a dragon axe obtainable from wintertodt", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"got my rune crossbow, was it farmable instead", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"can prayer potions be made from scratch", false, true, need(Router.NEED_ITEM_SOURCES)},
		{"i found a clue scroll, can i find another", false, true, need(Router.NEED_ITEM_SOURCES)},

		// --- Transport ---
		{"how do i get to zulrah", false, false, need(Router.NEED_TRANSPORT)},
		{"how to travel to the fishing guild", false, false, need(Router.NEED_TRANSPORT)},
		{"how can i get to sote", false, false, need(Router.NEED_TRANSPORT)},
		{"how do you get to the myths guild", false, false, need(Router.NEED_TRANSPORT)},
		{"how should i travel to varlamore", false, false, need(Router.NEED_TRANSPORT)},
		{"fastest way to prifddinas", false, false, need(Router.NEED_TRANSPORT)},
		{"quickest way to the wilderness altar", false, false, need(Router.NEED_TRANSPORT)},
		{"route to mount karuulm", false, false, need(Router.NEED_TRANSPORT)},

		// --- Prices ---
		{"how much is a tbow", false, false, need(Router.NEED_PRICES)},
		{"whats the price of a shadow", false, false, need(Router.NEED_PRICES)},
		{"what does a bandos chestplate cost", false, false, need(Router.NEED_PRICES)},
		{"is a dwh worth its value", false, false, need(Router.NEED_PRICES)},
		{"should i alch my rune platebodies", false, false, need(Router.NEED_PRICES)},
		{"should i sell my bowfa", false, false, need(Router.NEED_PRICES)},

		// --- XP math and training ---
		{"what level do i need for barrows gloves", false, false, need(Router.NEED_XP_MATH)},
		{"how much xp to 99 fishing", false, false, need(Router.NEED_XP_MATH)},
		{"experience needed for 85 mining", false, false, need(Router.NEED_XP_MATH)},
		{"best way to train slayer", false, false, need(Router.NEED_TRAINING)},
		{"training magic on a budget", false, false, need(Router.NEED_TRAINING)},
		{"levelling herblore cheap", false, false, need(Router.NEED_TRAINING)},
		{"leveling agility fast", false, false, need(Router.NEED_TRAINING)},

		// --- Drop table ---
		{"what does vorkath drop", false, false, need(Router.NEED_DROP_TABLE)},
		{"zulrah loot table", false, false, need(Router.NEED_DROP_TABLE)},
		{"is the visage dropped by anything else", false, false, need(Router.NEED_DROP_TABLE)},
		{"is vorkath worth killing", false, false,
			need(Router.NEED_DROP_TABLE, Router.NEED_PRICES, Router.NEED_STRATEGY)},

		// --- Mechanics keywords ---
		{"is vorkath afkable, whats his weakness", false, false, need(Router.NEED_MECHANICS)},
		{"what attack style does kraken use", false, false, need(Router.NEED_MECHANICS)},
		{"where does the hellhound spawn", false, false, need(Router.NEED_MECHANICS)},
	};

	/** question / monsterResolved / itemResolved / need that must NOT appear. */
	private static final Object[][] NEGATIVE = {
		// No entity resolved: a bare fight verb must not fire the
		// entity-conditioned rule ("killing time", "beat the sepulchre").
		{"im just killing time before the update", false, false, Router.NEED_STRATEGY},
		{"can i beat the clock in the sepulchre", false, false, Router.NEED_STRATEGY},
		// No item resolved: bare obtain verbs stay inert.
		{"i got 99 strength yesterday", false, false, Router.NEED_ITEM_SOURCES},
		{"what should i do to have fun", false, false, Router.NEED_ITEM_SOURCES},
		// Monster resolved but no fight verb: asking about the creature is
		// not fight prep.
		{"where does vorkath live", true, false, Router.NEED_STRATEGY},
		{"on average how many kc needed for corrupted gauntlet", true, false, Router.NEED_STRATEGY},
		// Item resolved but no obtain verb.
		{"what does a bowfa look like", false, true, Router.NEED_ITEM_SOURCES},
	};

	@Test
	public void phrasingCorpusClassifies()
	{
		StringBuilder misses = new StringBuilder();
		for (Object[] row : POSITIVE)
		{
			List<String> got = Router.classifyNeeds((String) row[0], false,
				(Boolean) row[1], (Boolean) row[2]);
			for (String want : (String[]) row[3])
			{
				if (!got.contains(want))
				{
					misses.append(String.format("MISS  %-55s wanted %s, got %s%n",
						"\"" + row[0] + "\"", want, got));
				}
			}
		}
		assertTrue(misses.toString(), misses.length() == 0);
	}

	@Test
	public void phrasingCorpusRejects()
	{
		StringBuilder hits = new StringBuilder();
		for (Object[] row : NEGATIVE)
		{
			List<String> got = Router.classifyNeeds((String) row[0], false,
				(Boolean) row[1], (Boolean) row[2]);
			if (got.contains((String) row[3]))
			{
				hits.append(String.format("FALSE POSITIVE  %-55s must not have %s, got %s%n",
					"\"" + row[0] + "\"", row[3], got));
			}
		}
		assertFalse(hits.toString(), hits.length() > 0);
	}

	private static String[] need(String... needs)
	{
		return needs;
	}
}
