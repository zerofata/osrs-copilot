package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Collective terms guides use for item families whose real names the term
 * cannot substring-match ("god cape" for the three per-god Mage Arena
 * capes), mapped to the variant words that do. Curated like the area
 * supplement: grown as families surface. The weekly snapshot job fails
 * when a variant no longer matches any catalogued item.
 */
final class ItemCollectives
{
	private static final List<String> GOD_DHIDES = List.of(
		"saradomin d'hide", "zamorak d'hide", "guthix d'hide",
		"armadyl d'hide", "bandos d'hide", "ancient d'hide");

	private static final Map<String, List<String>> COLLECTIVES = Map.of(
		"god cape", List.of("saradomin cape", "zamorak cape", "guthix cape"),
		"god book", List.of("holy book", "unholy book", "book of balance",
			"book of war", "book of law", "book of darkness"),
		"blessed d'hide", GOD_DHIDES,
		"blessed dragonhide", GOD_DHIDES,
		"god d'hide", GOD_DHIDES,
		"dwarf multicannon", List.of("cannon base", "cannon stand",
			"cannon barrels", "cannon furnace"),
		"dwarf cannon", List.of("cannon base", "cannon stand",
			"cannon barrels", "cannon furnace"));

	/** The lowercased query, or every variant of it when it contains a
	 * collective term. Expansion substitutes within the query, so
	 * qualifiers survive: "imbued god cape" becomes "imbued saradomin
	 * cape" and friends. */
	static List<String> expand(String lowerQuery)
	{
		for (Map.Entry<String, List<String>> e : COLLECTIVES.entrySet())
		{
			if (lowerQuery.contains(e.getKey()))
			{
				List<String> out = new ArrayList<>();
				for (String variant : e.getValue())
				{
					out.add(lowerQuery.replace(e.getKey(), variant));
				}
				return out;
			}
		}
		return List.of(lowerQuery);
	}

	/** Every variant term, for the snapshot job's staleness check. */
	static Collection<String> variantTerms()
	{
		List<String> out = new ArrayList<>();
		COLLECTIVES.values().forEach(out::addAll);
		return out;
	}

	private ItemCollectives()
	{
	}
}
