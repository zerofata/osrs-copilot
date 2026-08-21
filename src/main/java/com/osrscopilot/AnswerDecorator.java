package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.Ownership;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entity-aware answer styling: quest names colored by the player's actual
 * progress, item names by where the player holds them (carried, banked, not
 * owned), monsters and pages from the answer's own retrieval linked plainly.
 * Every name links to its OSRS Wiki page.
 *
 * Purely deterministic: names are matched against vocabularies the client or
 * wiki provides (quest list with live states, captured containers, the GE
 * item catalogue, wiki-verified route entities). The model plays no part.
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
		/** Pre-resolved icon file URL (skill and quest icons, written to
		 * disk at startup); null = none or item-sprite icon. */
		final String iconUrl;
		/** Item ID whose inventory sprite decorates the name; -1 = none.
		 * An ID rather than an image: sprites render only for names that
		 * appear in an answer, never for the whole vocabulary. */
		final int iconItemId;
		final int iconW;
		final int iconH;
		/** Skill names are everyday words ("attack the demon", "a ranged
		 * switch"); only their capitalized form denotes the skill. */
		final boolean capitalizedOnly;
		/** Ownership annotation ("×5,006 banked") rendered after the first
		 * mention of the name only; null = none. */
		final String badge;

		Rule(String lowerName, String title, String color, String iconUrl,
			int iconItemId, int iconW, int iconH, boolean capitalizedOnly, String badge)
		{
			this.lowerName = lowerName;
			this.title = title;
			this.color = color;
			this.iconUrl = iconUrl;
			this.iconItemId = iconItemId;
			this.iconW = iconW;
			this.iconH = iconH;
			this.capitalizedOnly = capitalizedOnly;
			this.badge = badge;
		}
	}

	private final List<Rule> rules;
	private final IconStore icons;

	private AnswerDecorator(List<Rule> rules, IconStore icons)
	{
		// Longest name first, so "Diamond bolts" wins over "Diamond".
		rules.sort((a, b) -> b.lowerName.length() - a.lowerName.length());
		this.rules = rules;
		this.icons = icons;
	}

	/** Inventory sprites are 36x31-ish; near-square icons stay square. */
	private static final int ICON_SQUARE = 14;
	private static final int ITEM_ICON_W = 16;

	/** All 24 skills. Their icons come from RuneLite's own SkillIconManager,
	 * so the set is exactly what the client knows. */
	private static final List<String> SKILLS = List.of(
		"Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic",
		"Runecraft", "Hitpoints", "Crafting", "Mining", "Smithing", "Fishing",
		"Cooking", "Firemaking", "Woodcutting", "Agility", "Herblore",
		"Thieving", "Fletching", "Slayer", "Farming", "Construction", "Hunter",
		"Sailing");

	/**
	 * Assemble the name vocabularies in precedence order: a name known
	 * several ways keeps its richest meaning (quest state over item over
	 * plain page link). monsterNames is the full plain-monster vocabulary,
	 * so answers that introduce a monster the route never resolved still
	 * link it. itemIds maps lowercase tradeable names to item IDs so
	 * unowned items can carry their sprite; owned items bring their IDs
	 * from the capture itself.
	 */
	static AnswerDecorator build(GameCapture cap, EntityResolver.Resolution entities,
		List<String> monsterNames, List<String[]> itemNames,
		Map<String, Integer> itemIds, IconStore icons)
	{
		Theme theme = Theme.active();
		Map<String, Rule> byName = new LinkedHashMap<>();
		if (cap != null && cap.questStates != null)
		{
			String questIcon = icons != null ? icons.questIconUrl() : null;
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				String color = "FINISHED".equals(e.getValue()) ? theme.questDoneHex
					: "IN_PROGRESS".equals(e.getValue()) ? theme.questProgressHex
					: theme.questNotStartedHex;
				add(byName, e.getKey(), e.getKey(), color,
					questIcon, -1, ICON_SQUARE, ICON_SQUARE, false, null);
			}
		}
		if (cap != null)
		{
			addOwnedItems(byName, cap, theme);
		}
		for (String skill : SKILLS)
		{
			add(byName, skill, skill, theme.plainLinkHex,
				icons != null ? icons.skillIconUrl(skill) : null,
				-1, ICON_SQUARE, ICON_SQUARE, true, null);
		}
		if (entities != null)
		{
			for (List<String> kind : List.of(entities.monsters, entities.pages, entities.quests))
			{
				for (String name : kind)
				{
					add(byName, name, name, theme.plainLinkHex, null, -1, 0, 0, false, null);
				}
			}
		}
		if (monsterNames != null)
		{
			for (String name : monsterNames)
			{
				add(byName, name, name, theme.plainLinkHex, null, -1, 0, 0, false, null);
			}
		}
		if (itemNames != null)
		{
			// {name, page}: versioned names ("Fire cape (l)") match by name
			// but link to their shared page. Untradeables without a mapped
			// ID render iconless rather than with a guessed image.
			for (String[] it : itemNames)
			{
				Integer id = itemIds != null
					? itemIds.get(it[0].toLowerCase(Locale.ROOT)) : null;
				add(byName, it[0], it[1], theme.itemUnownedHex,
					null, id != null ? id : -1, ITEM_ICON_W, ICON_SQUARE, false, null);
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
		Map<String, Integer> iconId = new LinkedHashMap<>();
		accumulate(counts, iconId, cap.inventory, 0);
		accumulate(counts, iconId, cap.equipment, 1);
		accumulate(counts, iconId, cap.bank, 2);
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
			Integer id = iconId.get(e.getKey());
			add(byName, e.getKey(), e.getKey(),
				carried ? theme.itemCarriedHex : theme.itemBankedHex,
				null, id != null ? id : -1, ITEM_ICON_W, ICON_SQUARE, false,
				String.join(", ", parts));
		}
	}

	private static void accumulate(Map<String, long[]> counts, Map<String, Integer> iconId,
		List<Map<String, Object>> container, int slot)
	{
		if (container == null)
		{
			return;
		}
		for (Map<String, Object> item : container)
		{
			String name = String.valueOf(item.get("name"));
			// The icon keeps the exact owned variant's ID.
			String base = Ownership.baseName(name);
			Object q = item.get("quantity");
			long qty = q instanceof Number ? ((Number) q).longValue() : 1;
			counts.computeIfAbsent(base, k -> new long[3])[slot] += qty;
			Object id = item.get("id");
			if (id instanceof Number)
			{
				iconId.putIfAbsent(base, ((Number) id).intValue());
			}
		}
	}

	private static void add(Map<String, Rule> byName, String name, String title,
		String color, String iconUrl, int iconItemId, int iconW, int iconH,
		boolean capitalizedOnly, String badge)
	{
		if (name == null || name.length() < MIN_NAME_LENGTH)
		{
			return;
		}
		byName.putIfAbsent(name.toLowerCase(Locale.ROOT),
			new Rule(name.toLowerCase(Locale.ROOT), title, color,
				iconUrl, iconItemId, iconW, iconH, capitalizedOnly, badge));
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
				int end = Ownership.wordMatchEnd(lower, i, needle.length());
				if (end < 0 || anyMarked(offLimits, i, end))
				{
					continue;
				}
				if (rules.get(r).capitalizedOnly && !Character.isUpperCase(html.charAt(i)))
				{
					continue;
				}
				if (fragmentOfLargerName(html, end) || tailOfLargerName(html, i))
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
			// Item sprites render here, lazily, for matched names only.
			String iconUrl = rule.iconUrl != null ? rule.iconUrl
				: rule.iconItemId >= 0 && icons != null
				? icons.itemIconUrl(rule.iconItemId) : null;
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
			// Ownership badge on the first mention only.
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

	/**
	 * Continuation of a title-case phrase: whitespace, optional lowercase
	 * connectors, then a capitalized word. Anything else (punctuation, tags,
	 * ordinary lowercase prose) ends the phrase.
	 */
	private static final Pattern PROPER_NOUN_CONTINUES =
		Pattern.compile("\\s+(?:(?:of|the|de)\\s+)*\\p{Lu}");

	/**
	 * True when the matched name is only a fragment of a longer proper noun
	 * ("Bank" in "Bank of Gielinor", "Varrock" in "Varrock Teleport"). The
	 * rule's page describes the whole name's referent, not the fragment's,
	 * so linking the fragment would mislabel it. The longer name, if it is a
	 * real page, is still caught by the unverified-name check.
	 */
	private static boolean fragmentOfLargerName(String html, int end)
	{
		Matcher m = PROPER_NOUN_CONTINUES.matcher(html);
		m.region(end, html.length());
		return m.lookingAt();
	}

	/**
	 * A capitalized word, optional lowercase connectors, then whitespace,
	 * ending exactly where the match starts.
	 */
	private static final Pattern PROPER_NOUN_PRECEDES =
		Pattern.compile("(?<!\\p{L})\\p{Lu}[\\p{L}'-]*(?:\\s+(?:of|the|de))*\\s+\\z");

	/**
	 * Mirror of {@link #fragmentOfLargerName}: the match is the tail of a
	 * longer title-case phrase ("demons" in "Greater demons", "Slayer" in
	 * "Dragon Slayer II"). The rule's page describes the fragment's own
	 * referent, not the longer name's, so linking would mislabel it. When
	 * the longer phrase is itself a known name, its rule has already
	 * claimed the whole span (rules match longest-first) and overlap
	 * rejection stops this check from ever seeing the fragment.
	 *
	 * Unlike the forward guard, a preceding capital is only evidence when
	 * it sits mid-sentence: sentence-opening words are capitalized no
	 * matter what they are ("The demons", "Use an Abyssal whip"), so a
	 * capital straight after start-of-text, punctuation, or a tag proves
	 * nothing and must not suppress. The residual risk -- a proper phrase
	 * that itself opens a sentence -- is accepted: suppressing there would
	 * cost far more real links than it saves mislabels.
	 */
	private static boolean tailOfLargerName(String html, int start)
	{
		Matcher m = PROPER_NOUN_PRECEDES.matcher(html);
		// The preceding word sits within a few dozen chars; a bounded
		// region keeps the scan O(1) per candidate match.
		m.region(Math.max(0, start - 48), start);
		m.useTransparentBounds(true);
		if (!m.find())
		{
			return false;
		}
		int i = m.start();
		while (i > 0 && Character.isWhitespace(html.charAt(i - 1)))
		{
			i--;
		}
		if (i == 0)
		{
			return false;
		}
		char before = html.charAt(i - 1);
		return Character.isLetterOrDigit(before) || before == ',';
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

	static String wikiUrl(String title)
	{
		return WIKI_BASE + URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "_");
	}
}
