package com.osrscopilot.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Collective terms guides use for item families whose real names the term
 * cannot substring-match ("god cape" for the per-god Mage Arena capes),
 * mapped to the variant words that do. Curated like the area supplement;
 * the weekly snapshot job fails when a variant no longer matches any
 * catalogued item.
 */
final class ItemCollectives
{
	private static final List<String> GOD_DHIDES = List.of(
		"saradomin d'hide", "zamorak d'hide", "guthix d'hide",
		"armadyl d'hide", "bandos d'hide", "ancient d'hide");

	private static final List<String> CANNON_PARTS = List.of(
		"cannon base", "cannon stand", "cannon barrels", "cannon furnace");

	private static final List<String> BARROWS_SETS = List.of(
		"dharok's", "ahrim's", "karil's", "torag's", "verac's", "guthan's");

	private static final List<String> SKILL_CAPES = List.of(
		"attack cape", "strength cape", "defence cape", "hitpoints cape",
		"ranging cape", "prayer cape", "magic cape", "cooking cape",
		"woodcutting cape", "fletching cape", "fishing cape", "firemaking cape",
		"crafting cape", "smithing cape", "mining cape", "herblore cape",
		"agility cape", "thieving cape", "slayer cape", "farming cape",
		"runecraft cape", "hunter cape", "construct. cape", "sailing cape");

	private static final Map<String, List<String>> COLLECTIVES = Map.ofEntries(
		Map.entry("god cape", List.of(
			"saradomin cape", "zamorak cape", "guthix cape",
			"saradomin max cape", "zamorak max cape", "guthix max cape")),
		// Prefix members cover both "Imbued X cape" and "Imbued X max cape".
		Map.entry("imbued cape", List.of(
			"imbued saradomin", "imbued zamorak", "imbued guthix")),
		Map.entry("god book", List.of(
			"holy book", "unholy book", "book of balance",
			"book of war", "book of law", "book of darkness")),
		Map.entry("god blessing", List.of(
			"holy blessing", "unholy blessing", "peaceful blessing",
			"honourable blessing", "war blessing", "ancient blessing")),
		Map.entry("blessed d'hide", GOD_DHIDES),
		Map.entry("blessed dragonhide", GOD_DHIDES),
		Map.entry("god d'hide", GOD_DHIDES),
		Map.entry("dwarf multicannon", CANNON_PARTS),
		Map.entry("dwarf cannon", CANNON_PARTS),
		Map.entry("zenyte jewellery", List.of(
			"amulet of torture", "necklace of anguish",
			"tormented bracelet", "ring of suffering")),
		Map.entry("barrows armour", BARROWS_SETS),
		Map.entry("barrows equipment", BARROWS_SETS),
		// The identity member keeps "Mystic air staff" matching.
		Map.entry("air staff", List.of(
			"air staff", "staff of air", "air battlestaff")),
		Map.entry("water staff", List.of(
			"water staff", "staff of water", "water battlestaff")),
		Map.entry("earth staff", List.of(
			"earth staff", "staff of earth", "earth battlestaff")),
		Map.entry("fire staff", List.of(
			"fire staff", "staff of fire", "fire battlestaff")),
		Map.entry("antivenom", List.of("anti-venom")),
		Map.entry("team cape", List.of("team-")),
		Map.entry("quest cape", List.of("quest point cape")),
		Map.entry("skill cape", SKILL_CAPES),
		Map.entry("cape of accomplishment", SKILL_CAPES));

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
