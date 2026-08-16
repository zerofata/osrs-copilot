package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player's ownership index: bank + inventory + equipment flattened into
 * case-folded name -> quantity, with the original display names kept
 * alongside. Pure functions of a GameCapture -- no I/O, no state -- shared
 * by the prefetcher (ownership facts) and the tool registry
 * (search_owned_items).
 */
final class Ownership
{
	private Ownership()
	{
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

	/** Case-folded whole-word containment, tolerating a plural "s" on the
	 * fact side. Plain substring would let "bow" claim "blowpipe". */
	static boolean mentionsWord(String haystack, String needle)
	{
		int from = 0;
		int i;
		while ((i = haystack.indexOf(needle, from)) >= 0)
		{
			int end = i + needle.length();
			if (end < haystack.length() && haystack.charAt(end) == 's')
			{
				end++;
			}
			boolean startOk = i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1));
			boolean endOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
			if (startOk && endOk)
			{
				return true;
			}
			from = i + 1;
		}
		return false;
	}
}
