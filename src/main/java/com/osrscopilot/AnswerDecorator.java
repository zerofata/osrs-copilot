package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entity-aware answer styling: quest names colored by the player's actual
 * progress, item names by where the player holds them (carried, banked, not
 * owned), monsters and pages from the answer's own retrieval linked plainly.
 * Every name links to its OSRS Wiki page.
 *
 * Purely deterministic: names are matched against vocabularies the client or
 * wiki provides (quest list with live states, captured containers, the GE
 * item catalogue, wiki-verified route entities). The model plays no part, so
 * the styling can never assert something the game state doesn't back -- and
 * new content appears here the moment the live vocabularies carry it.
 */
final class AnswerDecorator
{
	private static final String WIKI_BASE = "https://oldschool.runescape.wiki/w/";

	/** Names shorter than this are too likely to collide with prose. */
	private static final int MIN_NAME_LENGTH = 4;

	private static final class Rule
	{
		final String lowerName;
		final String title;
		final String color;
		/** Wiki image filename rendered before the name; null = no icon.
		 * State color already says finished/in-progress/missing, so no
		 * glyphs. Stored as a filename, not a URL: the vocabulary holds
		 * thousands of rules (the whole GE catalogue) and only names that
		 * actually appear in an answer may cost a fetch. */
		final String iconFile;
		final int iconW;
		final int iconH;
		/** Skill names are everyday words ("attack the demon", "a ranged
		 * switch"); only their capitalized form denotes the skill. */
		final boolean capitalizedOnly;
		/** Ownership annotation ("×5,006 banked") rendered after the FIRST
		 * mention of the name; null = none. Colors alone ask the reader to
		 * know the legend -- the badge states it: when the model says "mine
		 * mithril ore", the ore the player already owns says so inline. */
		final String badge;

		Rule(String lowerName, String title, String color, String iconFile,
			int iconW, int iconH, boolean capitalizedOnly, String badge)
		{
			this.lowerName = lowerName;
			this.title = title;
			this.color = color;
			this.iconFile = iconFile;
			this.iconW = iconW;
			this.iconH = iconH;
			this.capitalizedOnly = capitalizedOnly;
			this.badge = badge;
		}
	}

	private final List<Rule> rules;
	private final IconCache icons;

	private AnswerDecorator(List<Rule> rules, IconCache icons)
	{
		// Longest name first, so "Diamond bolts" wins over "Diamond".
		rules.sort((a, b) -> b.lowerName.length() - a.lowerName.length());
		this.rules = rules;
		this.icons = icons;
	}

	/**
	 * Assemble the name vocabularies in precedence order: a name known
	 * several ways keeps its richest meaning (quest state over item over
	 * plain page link).
	 */
	/** The in-game quest point icon, shared by every quest rule. */
	private static final String QUEST_ICON_FILE = "Quest_point_icon.png";

	/** Inventory sprites are 36x31-ish; near-square icons stay square. */
	private static final int ICON_SQUARE = 14;
	private static final int ITEM_ICON_W = 16;

	/** All 24 skills; every "<Skill>_icon.png" file exists on the wiki. */
	private static final List<String> SKILLS = List.of(
		"Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic",
		"Runecraft", "Hitpoints", "Crafting", "Mining", "Smithing", "Fishing",
		"Cooking", "Firemaking", "Woodcutting", "Agility", "Herblore",
		"Thieving", "Fletching", "Slayer", "Farming", "Construction", "Hunter",
		"Sailing");

	static AnswerDecorator build(GameCapture cap, EntityResolver.Resolution entities,
		List<String> tradeableNames, IconCache icons)
	{
		Theme theme = Theme.active();
		Map<String, Rule> byName = new LinkedHashMap<>();
		if (cap != null && cap.questStates != null)
		{
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				String color = "FINISHED".equals(e.getValue()) ? theme.questDoneHex
					: "IN_PROGRESS".equals(e.getValue()) ? theme.questProgressHex
					: theme.questNotStartedHex;
				add(byName, e.getKey(), e.getKey(), color,
					QUEST_ICON_FILE, ICON_SQUARE, ICON_SQUARE, false);
			}
		}
		if (cap != null)
		{
			addOwnedItems(byName, cap, theme);
		}
		for (String skill : SKILLS)
		{
			add(byName, skill, skill, theme.plainLinkHex,
				skill + "_icon.png", ICON_SQUARE, ICON_SQUARE, true);
		}
		if (entities != null)
		{
			for (List<String> kind : List.of(entities.monsters, entities.pages, entities.quests))
			{
				for (String name : kind)
				{
					add(byName, name, name, theme.plainLinkHex, null, 0, 0, false);
				}
			}
		}
		if (tradeableNames != null)
		{
			for (String name : tradeableNames)
			{
				add(byName, name, name, theme.itemUnownedHex,
					name + ".png", ITEM_ICON_W, ICON_SQUARE, false);
			}
		}
		return new AnswerDecorator(new ArrayList<>(byName.values()), icons);
	}

	/** Aggregate every owned copy of an item across containers (dose and
	 * charge variants collapse to one base name), then emit a single rule
	 * whose badge states quantity and place: "×5,006 banked", "equipped",
	 * "×20 carried, ×5,006 banked". */
	private static void addOwnedItems(Map<String, Rule> byName, GameCapture cap, Theme theme)
	{
		Map<String, long[]> counts = new LinkedHashMap<>(); // {carried, equipped, banked}
		Map<String, String> iconName = new LinkedHashMap<>();
		accumulate(counts, iconName, cap.inventory, 0);
		accumulate(counts, iconName, cap.equipment, 1);
		accumulate(counts, iconName, cap.bank, 2);
		for (Map.Entry<String, long[]> e : counts.entrySet())
		{
			long[] c = e.getValue();
			boolean carried = c[0] > 0 || c[1] > 0;
			List<String> parts = new ArrayList<>();
			if (c[1] > 0)
			{
				parts.add(c[1] > 1 ? String.format("×%,d equipped", c[1]) : "equipped");
			}
			if (c[0] > 0)
			{
				parts.add(String.format("×%,d carried", c[0]));
			}
			if (c[2] > 0)
			{
				parts.add(String.format("×%,d banked", c[2]));
			}
			add(byName, e.getKey(), e.getKey(),
				carried ? theme.itemCarriedHex : theme.itemBankedHex,
				iconName.get(e.getKey()) + ".png", ITEM_ICON_W, ICON_SQUARE, false,
				String.join(", ", parts));
		}
	}

	private static void accumulate(Map<String, long[]> counts, Map<String, String> iconName,
		List<Map<String, Object>> container, int slot)
	{
		if (container == null)
		{
			return;
		}
		for (Map<String, Object> item : container)
		{
			String name = String.valueOf(item.get("name"));
			// Charge/dose qualifiers exist in item names but never in prose:
			// "Prayer potion(4)" must match an answer saying "prayer potion".
			// The icon file keeps the exact name -- that is what the wiki's
			// sprite files are named after.
			String base = name.replaceAll("\\s*\\([^)]*\\)$", "").trim();
			Object q = item.get("quantity");
			long qty = q instanceof Number ? ((Number) q).longValue() : 1;
			counts.computeIfAbsent(base, k -> new long[3])[slot] += qty;
			iconName.putIfAbsent(base, name);
		}
	}

	private static void add(Map<String, Rule> byName, String name, String title,
		String color, String iconFile, int iconW, int iconH, boolean capitalizedOnly)
	{
		add(byName, name, title, color, iconFile, iconW, iconH, capitalizedOnly, null);
	}

	private static void add(Map<String, Rule> byName, String name, String title,
		String color, String iconFile, int iconW, int iconH, boolean capitalizedOnly,
		String badge)
	{
		if (name == null || name.length() < MIN_NAME_LENGTH)
		{
			return;
		}
		byName.putIfAbsent(name.toLowerCase(Locale.ROOT),
			new Rule(name.toLowerCase(Locale.ROOT), title, color,
				iconFile, iconW, iconH, capitalizedOnly, badge));
	}

	/**
	 * Rewrite known names in rendered answer HTML into colored wiki links.
	 * Operates on text between tags only; matches are whole-word,
	 * case-insensitive, and tolerate a plural "s".
	 */
	String decorate(String html)
	{
		String lower = html.toLowerCase(Locale.ROOT);
		boolean[] offLimits = tagRanges(html);
		List<int[]> chosen = new ArrayList<>();
		for (int r = 0; r < rules.size(); r++)
		{
			String needle = rules.get(r).lowerName;
			int from = 0;
			int i;
			while ((i = lower.indexOf(needle, from)) >= 0)
			{
				from = i + 1;
				int end = i + needle.length();
				if (end < lower.length() && lower.charAt(end) == 's')
				{
					end++;
				}
				if (!wordBounded(lower, i, end) || anyMarked(offLimits, i, end))
				{
					continue;
				}
				if (rules.get(r).capitalizedOnly && !Character.isUpperCase(html.charAt(i)))
				{
					continue;
				}
				for (int k = i; k < end; k++)
				{
					offLimits[k] = true;
				}
				chosen.add(new int[]{i, end, r});
			}
		}
		if (chosen.isEmpty())
		{
			return html;
		}
		chosen.sort((a, b) -> a[0] - b[0]);
		StringBuilder out = new StringBuilder(html.length() + chosen.size() * 64);
		String badgeHex = Theme.active().metaHex;
		java.util.Set<Integer> badged = new java.util.HashSet<>();
		int pos = 0;
		for (int[] m : chosen)
		{
			Rule rule = rules.get(m[2]);
			out.append(html, pos, m[0])
				.append("<a href='").append(wikiUrl(rule.title)).append("'>");
			// Icons resolve lazily, here, for matched names only: the rule
			// vocabulary is thousands strong, an answer mentions a handful.
			String iconUrl = rule.iconFile != null && icons != null
				? icons.fileUrl(rule.iconFile) : null;
			if (iconUrl != null)
			{
				// border=0: linked images otherwise get the ancient
				// browser-style link border box.
				out.append("<img src='").append(iconUrl)
					.append("' width='").append(rule.iconW)
					.append("' height='").append(rule.iconH)
					.append("' border='0'>&nbsp;");
			}
			out.append("<font color='").append(rule.color).append("'>")
				.append(html, m[0], m[1])
				.append("</font></a>");
			// Ownership badge on the first mention only: repeated names in
			// one answer would otherwise repeat the same annotation.
			if (rule.badge != null && badged.add(m[2]))
			{
				out.append(" <font size='2' color='").append(badgeHex)
					.append("'>[").append(rule.badge).append("]</font>");
			}
			pos = m[1];
		}
		out.append(html, pos, html.length());
		return out.toString();
	}

	/** True in every position that sits inside an HTML tag. */
	private static boolean[] tagRanges(String html)
	{
		boolean[] blocked = new boolean[html.length()];
		boolean in = false;
		for (int i = 0; i < html.length(); i++)
		{
			char c = html.charAt(i);
			if (c == '<')
			{
				in = true;
			}
			blocked[i] = in;
			if (c == '>')
			{
				in = false;
			}
		}
		return blocked;
	}

	private static boolean wordBounded(String s, int start, int end)
	{
		boolean startOk = start == 0 || !Character.isLetterOrDigit(s.charAt(start - 1));
		boolean endOk = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
		return startOk && endOk;
	}

	private static boolean anyMarked(boolean[] marked, int start, int end)
	{
		for (int i = start; i < end; i++)
		{
			if (marked[i])
			{
				return true;
			}
		}
		return false;
	}

	private static String wikiUrl(String title)
	{
		try
		{
			return WIKI_BASE + URLEncoder.encode(title, "UTF-8").replace("+", "_");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
