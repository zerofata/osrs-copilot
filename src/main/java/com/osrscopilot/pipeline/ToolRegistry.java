package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The tools offered to the synth model: OpenAI function-calling specs and
 * implementations side by side so they cannot drift apart.
 */
class ToolRegistry
{
	/** Budget for a single-section fetch (wikitext, tables intact). One
	 * deliberately chosen section of one page may run long. */
	private static final int SECTION_CHAR_LIMIT = 12000;

	private final WikiApi wiki;
	private final Gson gson;

	ToolRegistry(WikiApi wiki, Gson gson)
	{
		this.wiki = wiki;
		this.gson = gson;
	}

	JsonArray buildToolSpecs(boolean offerOwnedSearch)
	{
		JsonArray specs = new JsonArray();
		specs.add(toolSpec("wiki_search",
			"Search the OSRS Wiki for pages. Returns page titles and short snippets ONLY, "
				+ "not page content -- follow up with wiki_page to read a page.",
			"query"));
		// Section fetches return wikitext so tables (loot, rewards) survive.
		JsonObject pageSpec = toolSpec("wiki_page",
			"Get the text of an OSRS Wiki page (item, monster, quest, guide). Long pages are "
				+ "truncated and full-page text OMITS tables; the [Sections: ...] line lists "
				+ "every section that exists. Pass one as \"section\" to fetch just that "
				+ "section with its tables intact.",
			"title");
		pageSpec.getAsJsonObject("function").getAsJsonObject("parameters")
			.getAsJsonObject("properties").add("section", singleType("string"));
		specs.add(pageSpec);
		specs.add(toolSpec("item_sources",
			"How to obtain an item: monster drops and activity/reward-chest sources with drop "
				+ "rates, creation recipes (materials, skill levels, facility), shops that stock "
				+ "it, and whether it trades on the GE. Quest rewards are not covered; use "
				+ "wiki_page for those.",
			"item_name"));
		specs.add(toolSpec("monster_drops",
			"Full drop table of a monster with quantities and drop rarities.", "name"));
		specs.add(toolSpec("monster_info",
			"Get a monster's full combat profile: levels, defensive bonuses per style, "
				+ "attributes, attack style/speed, and immunities.", "name"));
		specs.add(toolSpec("item_stats",
			"Get a wearable item's combat bonuses: attack and defence by style, "
				+ "strength, ranged strength, magic damage, and prayer.", "item_name"));
		specs.add(toolSpec("quest_info",
			"Get a quest's requirements (skill levels and prerequisite quests), "
				+ "items required, and start point.", "quest_name"));
		// Batched: pricing a shortlist costs one round trip, not one each.
		specs.add(toolSpec("ge_price",
			"Current Grand Exchange price, buy limit, and high-alch value. Takes a LIST "
				+ "of item names and returns the price of each -- price all candidate "
				+ "items in one call, never one call per item.",
			"item_names", arrayOf("string")));
		specs.add(toolSpec("quest_status",
			"Check whether the player has finished, started, or not started a specific quest.",
			"quest_name"));
		JsonObject xpSpec = toolSpec("xp_to_level",
			"Gives the total xp required to reach requested level in skill. Use it for every "
				+ "xp-to-a-level figure rather than estimating; the XP math fact only "
				+ "precomputes the next level.",
			"skill");
		JsonObject xpParams = xpSpec.getAsJsonObject("function").getAsJsonObject("parameters");
		xpParams.getAsJsonObject("properties").add("target_level", singleType("integer"));
		xpParams.getAsJsonArray("required").add("target_level");
		specs.add(xpSpec);
		// Withheld when ownership is already fully in context: a tool over
		// visible data invites redundant lookups.
		if (offerOwnedSearch)
		{
			specs.add(toolSpec("search_owned_items",
				"Search the player's bank, inventory, and equipment. Takes a LIST of item-name "
					+ "queries and returns the matches for each -- check all candidate items "
					+ "in one call, never one call per item.",
				"queries", arrayOf("string")));
		}
		return specs;
	}

	private static JsonObject toolSpec(String name, String description, String param)
	{
		return toolSpec(name, description, param, singleType("string"));
	}

	private static JsonObject toolSpec(String name, String description, String param,
		JsonObject paramSchema)
	{
		JsonObject prop = new JsonObject();
		prop.add(param, paramSchema);
		JsonObject params = new JsonObject();
		params.addProperty("type", "object");
		params.add("properties", prop);
		JsonArray required = new JsonArray();
		required.add(param);
		params.add("required", required);
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("description", description);
		fn.add("parameters", params);
		JsonObject spec = new JsonObject();
		spec.addProperty("type", "function");
		spec.add("function", fn);
		return spec;
	}

	private static JsonObject arrayOf(String type)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "array");
		o.add("items", singleType(type));
		return o;
	}

	private static JsonObject singleType(String type)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	Map<String, AgentLoop.Tool> buildTools(GameCapture cap, Map<String, long[]> owned,
		Map<String, String> ownedNames, boolean offerOwnedSearch,
		boolean annotateOwnership)
	{
		Map<String, AgentLoop.Tool> tools = new LinkedHashMap<>();
		// Content-returning tools surface item names the prompt-time
		// ownership fact never saw; each result carries its own slice.
		tools.put("wiki_search", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.search(str(args, "query"))));
		tools.put("wiki_page", annotated(owned, ownedNames, annotateOwnership, args -> {
			String title = str(args, "title");
			String section = str(args, "section");
			if (!section.isEmpty())
			{
				String text = wiki.sectionByHeading(title, Pattern.compile(
					Pattern.quote(section), Pattern.CASE_INSENSITIVE), SECTION_CHAR_LIMIT);
				if (text != null)
				{
					return text;
				}
				List<String> available = wiki.topSections(title);
				return Map.of("error", "No section '" + section + "' on '" + title + "'"
					+ (available.isEmpty() ? "."
						: ". Sections: " + String.join("; ", available)));
			}
			String text = wiki.page(title);
			return text != null ? text
				: Map.of("error", "No page found for '" + title + "'. Try wiki_search first.");
		}));
		tools.put("item_sources", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.itemSources(str(args, "item_name"))));
		tools.put("monster_drops", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.monsterDrops(str(args, "name"))));
		tools.put("monster_info", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.monsterInfo(str(args, "name"))));
		tools.put("item_stats", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.itemStats(str(args, "item_name"))));
		tools.put("quest_info", annotated(owned, ownedNames, annotateOwnership,
			args -> wiki.questInfo(str(args, "quest_name"))));
		tools.put("ge_price", annotated(owned, ownedNames, annotateOwnership, args -> {
			List<String> names = strList(args, "item_names", "item_name");
			if (names.isEmpty())
			{
				return Map.of("error", "provide 'item_names': a list of items to price");
			}
			Map<String, Object> result = new LinkedHashMap<>();
			for (String name : names)
			{
				result.put(name, wiki.gePrice(name));
			}
			return result;
		}));
		tools.put("quest_status", args -> {
			if (cap.questStates == null || cap.questStates.isEmpty())
			{
				return Map.of("error", "quest states not available this session");
			}
			String ql = str(args, "quest_name").toLowerCase(Locale.ROOT);
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				if (e.getKey().toLowerCase(Locale.ROOT).equals(ql))
				{
					return Map.of("quest", e.getKey(), "status", e.getValue());
				}
			}
			Map<String, String> partial = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				if (e.getKey().toLowerCase(Locale.ROOT).contains(ql))
				{
					partial.put(e.getKey(), e.getValue());
				}
			}
			return partial.isEmpty()
				? Map.of("error", "no quest matching '" + str(args, "quest_name") + "'")
				: partial;
		});
		tools.put("xp_to_level", args -> {
			if (cap.skillXp == null || cap.skillXp.isEmpty())
			{
				return Map.of("error", "skill XP not available this session");
			}
			String skill = str(args, "skill");
			String sl = skill.toLowerCase(Locale.ROOT);
			Map.Entry<String, Integer> match = null;
			for (Map.Entry<String, Integer> e : cap.skillXp.entrySet())
			{
				if (e.getKey().toLowerCase(Locale.ROOT).equals(sl))
				{
					match = e;
					break;
				}
			}
			if (match == null)
			{
				return Map.of("error", "no skill named '" + skill + "'");
			}
			int target;
			try
			{
				target = args.get("target_level").getAsInt();
			}
			catch (Exception e)
			{
				return Map.of("error", "provide 'target_level': the level to reach (2-99)");
			}
			if (target < 2 || target > 99)
			{
				return Map.of("error", "target_level must be 2-99");
			}
			int xp = match.getValue();
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("skill", match.getKey());
			out.put("current_xp", xp);
			out.put("current_level", XpTable.toNextLevel(xp)[0]);
			out.put("target_level", target);
			int needed = XpTable.xpForLevel(target) - xp;
			out.put("xp_needed", Math.max(0, needed));
			if (needed <= 0)
			{
				out.put("note", "target level already reached");
			}
			return out;
		});
		if (offerOwnedSearch)
		{
			// The annotated wrap adds the ownership slice footer, whose
			// entries carry the carried/equipped/banked locations for the
			// items the hits name; the hits themselves stay totals.
			tools.put("search_owned_items", annotated(owned, ownedNames, annotateOwnership,
				args -> {
				List<String> queries = strList(args, "queries", "query");
				if (queries.isEmpty())
				{
					return Map.of("error", "provide 'queries': a list of item names to look for");
				}
				Map<String, Object> result = new LinkedHashMap<>();
				Set<String> known = validItemNames();
				for (String query : queries)
				{
					String ql = query.toLowerCase(Locale.ROOT);
					List<String> needles = ItemCollectives.expand(ql);
					List<Map<String, Object>> hits = new ArrayList<>();
					for (String needle : needles)
					{
						for (Map.Entry<String, long[]> e : owned.entrySet())
						{
							if (e.getKey().contains(needle) && hits.size() < 20)
							{
								Map<String, Object> hit = new LinkedHashMap<>();
								hit.put("item", ownedNames.get(e.getKey()));
								hit.put("quantity", Ownership.total(e.getValue()));
								hits.add(hit);
							}
						}
					}
					if (!hits.isEmpty())
					{
						result.put(query, hits);
						continue;
					}
					// An expanded query searched every variant: a true zero.
					// Otherwise a miss on an invented name must not read as
					// one ("Dwarf multicannon" is a page, not an item).
					result.put(query, needles.size() > 1 || known.contains(ql)
						? "no match in bank/inventory/equipment"
						: "not a valid OSRS item name -- search queries must use "
							+ "exact in-game item names");
				}
				return result;
			}));
		}
		return tools;
	}

	/** Lowercased catalogue names plus their bases ("saradomin brew" for
	 * "Saradomin brew(4)", since prose and queries drop the qualifier). */
	private Set<String> validItemNames() throws IOException
	{
		Set<String> names = new HashSet<>();
		for (ItemDescriptor it : wiki.itemCatalog())
		{
			String lower = it.name.toLowerCase(Locale.ROOT);
			names.add(lower);
			names.add(Ownership.baseName(lower));
		}
		return names;
	}

	/** Appends the ownership slice for every catalogued item a tool result
	 * mentions. Disabled when the bank is inlined; error results pass
	 * through untouched. */
	private AgentLoop.Tool annotated(Map<String, long[]> owned,
		Map<String, String> ownedNames, boolean enabled, AgentLoop.Tool inner)
	{
		if (!enabled)
		{
			return inner;
		}
		return args -> {
			Object out = inner.call(args);
			if (out instanceof Map && ((Map<?, ?>) out).containsKey("error"))
			{
				return out;
			}
			String text = out instanceof String ? (String) out : gson.toJson(out);
			Ownership.Slice slice = Ownership.slice(text.toLowerCase(Locale.ROOT),
				owned, ownedNames, wiki.itemCatalog());
			if (slice == null)
			{
				return out;
			}
			return text + "\n\n[Player ownership of items this result mentions"
				+ (slice.complete
					? " (complete both ways: absent from OWNED means not owned)"
					: " (lists cut for length)")
				+ "]\n" + slice.text;
		};
	}

	private static String str(JsonObject args, String key)
	{
		return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
	}

	/** List argument for a batched tool; tolerates a bare string under
	 * the list key or its singular cousin. */
	private static List<String> strList(JsonObject args, String listKey, String singleKey)
	{
		List<String> values = new ArrayList<>();
		if (args.has(listKey) && args.get(listKey).isJsonArray())
		{
			for (JsonElement v : args.getAsJsonArray(listKey))
			{
				values.add(v.getAsString());
			}
		}
		else
		{
			String single = args.has(singleKey) ? str(args, singleKey) : str(args, listKey);
			if (!single.isEmpty())
			{
				values.add(single);
			}
		}
		return values;
	}
}
