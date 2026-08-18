package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The player's ownership index: bank + inventory + equipment flattened into
 * case-folded name -> quantity, with the original display names kept
 * alongside. Pure functions of a GameCapture -- no I/O, no state -- shared
 * by the prefetcher (ownership facts), the tool registry
 * (search_owned_items), and the answer decorator (ownership badges). The
 * name-matching primitives live here alone so the ownership the model is
 * told and the ownership the UI displays can never drift apart.
 */
public final class Ownership
{
	private static final Pattern TRAILING_QUALIFIER = Pattern.compile("\\s*\\([^)]*\\)$");

	private Ownership()
	{
	}

	/** Item display name with any trailing "(4)"/"(i)" qualifier removed.
	 * Dose, charge, and version qualifiers exist in item names but never
	 * in prose, so matching and display both use the base name. */
	public static String baseName(String name)
	{
		return TRAILING_QUALIFIER.matcher(name).replaceAll("").trim();
	}

	static Map<String, long[]> buildIndex(GameCapture cap)
	{
		Map<String, long[]> owned = new LinkedHashMap<>();
		for (List<Map<String, Object>> container :
			Arrays.asList(cap.bank, cap.inventory, cap.equipment))
		{
			if (container == null)
			{
				continue;
			}
			for (Map<String, Object> item : container)
			{
				String name = String.valueOf(item.get("name"));
				long qty = item.get("quantity") instanceof Number
					? ((Number) item.get("quantity")).longValue() : 1;
				owned.merge(name.toLowerCase(Locale.ROOT), new long[]{qty},
					(a, b) -> new long[]{a[0] + b[0]});
			}
		}
		return owned;
	}

	static Map<String, String> buildNames(GameCapture cap)
	{
		Map<String, String> names = new LinkedHashMap<>();
		for (List<Map<String, Object>> container :
			Arrays.asList(cap.bank, cap.inventory, cap.equipment))
		{
			if (container == null)
			{
				continue;
			}
			for (Map<String, Object> item : container)
			{
				String name = String.valueOf(item.get("name"));
				names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
			}
		}
		return names;
	}

	static Object check(Map<String, long[]> owned, Map<String, String> names, String itemName)
	{
		String key = itemName.toLowerCase(Locale.ROOT);
		if (owned.containsKey(key))
		{
			return Map.of("item", names.get(key), "owned", owned.get(key)[0]);
		}
		List<Map<String, Object>> partial = new ArrayList<>();
		for (Map.Entry<String, long[]> e : owned.entrySet())
		{
			if ((e.getKey().contains(key) || key.contains(e.getKey())) && partial.size() < 5)
			{
				Map<String, Object> hit = new LinkedHashMap<>();
				hit.put("item", names.get(e.getKey()));
				hit.put("owned", e.getValue()[0]);
				partial.add(hit);
			}
		}
		if (!partial.isEmpty())
		{
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("item", itemName);
			out.put("exact_match", false);
			out.put("similar_owned", partial);
			return out;
		}
		return Map.of("item", itemName, "owned", 0);
	}

	/**
	 * Both ownership lists for every catalogued item a text mentions:
	 * OWNED with quantities, NOT OWNED by name. Positive statements both
	 * ways, because models do not infer from absence -- our own system
	 * prompt forbids it. Shared by the prefetcher (over prefetched facts)
	 * and the tool registry (over live tool results), so the model gets
	 * the same grounding for content it discovers mid-loop as for content
	 * we fetched up front.
	 */
	static final class Slice
	{
		final String text;
		/** True when the lists may claim completeness: the vocabulary was
		 * available and nothing was cut for length. */
		final boolean complete;

		private Slice(String text, boolean complete)
		{
			this.text = text;
			this.complete = complete;
		}
	}

	/** Cap on the owned list; past this the block is noise. */
	private static final int OWNED_LIMIT = 120;
	/** Cap on the not-owned enumeration, sized ABOVE what real equipment
	 * pages mention (a three-style tabber names ~100-150 items): a
	 * truncated list forfeits the completeness claim, and the model then
	 * rationally re-verifies items one search at a time -- the exact
	 * behavior this block exists to prevent. ~4 tokens per entry, so even
	 * a full list costs under a thousand tokens against the multi-
	 * thousand-token sweep it replaces. */
	private static final int LACKED_LIMIT = 200;

	/**
	 * @param haystackLower lowercased text to scan for item mentions
	 * @param vocabulary every known item as {name, page}, or null when
	 *        unavailable (the slice then omits NOT OWNED and reports
	 *        incomplete)
	 * @return the rendered lists, or null when the text mentions nothing
	 *         catalogued or owned
	 */
	static Slice slice(String haystackLower, Map<String, long[]> owned,
		Map<String, String> names, List<String[]> vocabulary)
	{
		Map<String, Long> ownedMentioned = new LinkedHashMap<>();
		List<String> ownedBases = new ArrayList<>();
		for (Map.Entry<String, long[]> e : owned.entrySet())
		{
			String base = baseName(e.getKey());
			ownedBases.add(base);
			if (base.length() < 3 || !mentionsItem(haystackLower, base))
			{
				continue;
			}
			String display = baseName(names.get(e.getKey()));
			ownedMentioned.merge(display, e.getValue()[0], Long::sum);
		}

		Set<String> lacked = vocabulary != null
			? lackedMentioned(haystackLower, ownedBases, vocabulary) : null;
		if (ownedMentioned.isEmpty() && (lacked == null || lacked.isEmpty()))
		{
			return null;
		}

		StringBuilder sb = new StringBuilder("OWNED: ");
		int n = 0;
		boolean truncated = false;
		for (Map.Entry<String, Long> e : ownedMentioned.entrySet())
		{
			if (n >= OWNED_LIMIT)
			{
				sb.append(", ...");
				truncated = true;
				break;
			}
			sb.append(n++ > 0 ? ", " : "").append(e.getKey());
			if (e.getValue() > 1)
			{
				sb.append(" x").append(e.getValue());
			}
		}
		if (n == 0)
		{
			sb.append("none of them");
		}
		if (lacked != null)
		{
			sb.append("\nNOT OWNED (verified absent at capture): ");
			n = 0;
			for (String name : lacked)
			{
				if (n >= LACKED_LIMIT)
				{
					sb.append(", ...");
					truncated = true;
					break;
				}
				sb.append(n++ > 0 ? ", " : "").append(name);
			}
			if (n == 0)
			{
				sb.append("nothing relevant");
			}
		}
		return new Slice(sb.toString(), lacked != null && !truncated);
	}

	/**
	 * Every catalogued item the text mentions that the player does NOT
	 * own, by name. Owned variants count as owned: a text saying "Slayer
	 * helmet (i)" is covered by the player's "Black slayer helmet (i)".
	 */
	private static Set<String> lackedMentioned(String haystackLower,
		List<String> ownedBases, List<String[]> vocabulary)
	{
		Set<String> lacked = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		for (String[] it : vocabulary)
		{
			String base = baseName(it[0]);
			String lower = base.toLowerCase(Locale.ROOT);
			if (lower.length() < 4 || !seen.add(lower)
				|| !mentionsWord(haystackLower, lower))
			{
				continue;
			}
			boolean ownedVariant = false;
			for (String ownedBase : ownedBases)
			{
				if (ownedBase.equals(lower) || mentionsWord(ownedBase, lower))
				{
					ownedVariant = true;
					break;
				}
			}
			if (!ownedVariant)
			{
				lacked.add(base);
			}
		}
		return lacked;
	}

	/**
	 * True when the text mentions the owned item's name -- directly, or
	 * under a shorter form of it. Wiki pages name canonical gear ("Slayer
	 * helmet (i)"); the player's copy is often a decorated variant ("Black
	 * slayer helmet (i)"), which plain containment can never find in the
	 * page's text. Stripping lead qualifier words one at a time catches
	 * those, and only multi-word remainders count: single words like
	 * "helmet" are prose, not an item reference.
	 */
	private static boolean mentionsItem(String haystackLower, String base)
	{
		String candidate = base;
		while (true)
		{
			if (mentionsWord(haystackLower, candidate))
			{
				return true;
			}
			int space = candidate.indexOf(' ');
			if (space < 0 || candidate.indexOf(' ', space + 1) < 0)
			{
				return false;
			}
			candidate = candidate.substring(space + 1);
		}
	}

	/** Case-folded whole-word containment. */
	static boolean mentionsWord(String haystack, String needle)
	{
		int from = 0;
		int i;
		while ((i = haystack.indexOf(needle, from)) >= 0)
		{
			if (wordMatchEnd(haystack, i, needle.length()) >= 0)
			{
				return true;
			}
			from = i + 1;
		}
		return false;
	}

	/** End index of a whole-word match at [start, start+length), extended
	 * over a plural "s"; -1 when the span is not word-bounded. Plain
	 * substring containment would let "bow" claim "blowpipe". */
	public static int wordMatchEnd(String s, int start, int length)
	{
		int end = start + length;
		if (end < s.length() && s.charAt(end) == 's')
		{
			end++;
		}
		boolean startOk = start == 0 || !Character.isLetterOrDigit(s.charAt(start - 1));
		boolean endOk = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
		return startOk && endOk ? end : -1;
	}
}
