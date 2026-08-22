package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Structured game-data lookups over the wiki's buckets and the prices API:
 * drop tables, monster combat profiles, equipment bonuses, quest
 * requirements, GE prices. Returns error maps rather than nulls because the
 * LLM tool boundary wants the message.
 */
@Slf4j
class WikiLookups
{
	private static final String PRICES_API = "https://prices.runescape.wiki/api";

	private final Http http;
	private final Gson gson;
	private final WikiContent content;
	private final VocabSnapshots vocab;

	WikiLookups(Http http, Gson gson, WikiContent content, VocabSnapshots vocab)
	{
		this.http = http;
		this.gson = gson;
		this.content = content;
		this.vocab = vocab;
	}

	/** Best-effort canonical item name via the GE mapping, else wiki search. */
	String resolveItemName(String name)
	{
		try
		{
			String exact = mappingMatch(name);
			if (exact != null)
			{
				return exact;
			}
			// The wiki's curated redirects ("bowfa" -> Bow of Faerdhinen) are
			// the same mechanism the entity resolver trusts -- use them before
			// substring matching, which is a guess. GE names carry charge
			// qualifiers players never type ("Toxic blowpipe (empty)"), so a
			// redirect target also matches its qualified variant.
			String target = content.resolveTitles(List.of(name)).get(name);
			if (target != null)
			{
				String viaRedirect = mappingMatch(target);
				if (viaRedirect != null)
				{
					return viaRedirect;
				}
				String qualified = qualifiedVariant(target);
				return qualified != null ? qualified : target;
			}
			String lower = name.toLowerCase(Locale.ROOT);
			String shortestPartial =
				scanMapping(c -> c.toLowerCase(Locale.ROOT).contains(lower));
			if (shortestPartial != null)
			{
				return shortestPartial;
			}
			List<Map<String, Object>> hits = content.search(name);
			if (!hits.isEmpty() && hits.get(0).containsKey("title"))
			{
				return (String) hits.get(0).get("title");
			}
		}
		catch (Exception e)
		{
			log.debug("resolveItemName failed for {}", name, e);
		}
		return name;
	}

	/** Case-insensitive exact match against the GE mapping, or null. */
	private String mappingMatch(String name) throws IOException
	{
		return scanMapping(c -> c.equalsIgnoreCase(name));
	}

	/** Shortest "Name (qualifier)" entry in the GE mapping, or null. */
	private String qualifiedVariant(String name) throws IOException
	{
		String prefix = name.toLowerCase(Locale.ROOT) + " (";
		return scanMapping(c -> c.toLowerCase(Locale.ROOT).startsWith(prefix));
	}

	/** The one GE-mapping scan behind the matchers above: shortest entry
	 * satisfying the predicate, or null. Shortest because qualified and
	 * partial matches want the least-decorated variant. */
	private String scanMapping(java.util.function.Predicate<String> match) throws IOException
	{
		String best = null;
		for (Map<String, Object> it : vocab.geMapping())
		{
			String candidate = (String) it.get("name");
			if (match.test(candidate) && (best == null || candidate.length() < best.length()))
			{
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Every way to acquire an item that the wiki holds structured data for:
	 * drops (monsters, reward chests, activities), creation recipes, and
	 * shop stock, plus GE tradeability. One composite lookup because an
	 * acquisition question rarely knows in advance which route exists;
	 * routes with no data are omitted rather than erroring one by one.
	 * Quest and minigame rewards have no bucket and stay page-prose.
	 */
	Map<String, Object> itemSources(String itemName)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		try
		{
			// Buckets key on the item's PAGE name, which can differ from the
			// GE-mapping canonical: "bowfa" trades as "Bow of Faerdhinen
			// (inactive)" but its page (and its bucket rows) is "Bow of
			// Faerdhinen". Resolve the page first; the GE form matters only
			// for the tradeability report at the end.
			String page = content.resolveTitles(List.of(itemName)).get(itemName);
			String canonical = page != null ? page : resolveItemName(itemName);
			result.put("item", canonical);
			if (!canonical.equalsIgnoreCase(itemName))
			{
				result.put("resolved", "'" + itemName + "' resolved to '" + canonical + "'");
			}
			List<Map<String, Object>> drops = dropSources(canonical);
			if (!drops.isEmpty())
			{
				result.put("drops", drops);
			}
			List<Map<String, Object>> creation = recipes(canonical);
			if (!creation.isEmpty())
			{
				result.put("creation", creation);
			}
			List<Map<String, Object>> shops = shopStock(canonical);
			if (!shops.isEmpty())
			{
				result.put("shops", shops);
			}
			String geName = mappingMatch(canonical) != null
				? canonical : qualifiedVariant(canonical);
			result.put("tradeable_on_ge", geName != null);
			if (geName != null && !geName.equals(canonical))
			{
				result.put("ge_item", geName);
			}
			if (drops.isEmpty() && creation.isEmpty() && shops.isEmpty())
			{
				result.put("note", "No drop, creation, or shop sources found for '" + canonical
					+ "'. Quest and minigame rewards are not covered here; check wiki_page.");
			}
		}
		catch (Exception e)
		{
			result.put("error", "lookup failed: " + e.getMessage());
		}
		return result;
	}

	/** Dropsline rows for an item: monster and reward-chest sources. */
	private List<Map<String, Object>> dropSources(String canonical) throws IOException
	{
		JsonObject r = content.bucket("bucket('dropsline').select('page_name','drop_json')"
			+ ".where('item_name','" + esc(canonical) + "').limit(50).run()");
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement row : r.getAsJsonArray("bucket"))
		{
			Map<String, Object> entry = dropRow(row.getAsJsonObject(), "source", seen);
			if (entry != null)
			{
				out.add(entry);
				if (out.size() >= 30)
				{
					break;
				}
			}
		}
		return out;
	}

	/**
	 * One dropsline row as {identity, quantity, rarity}, or null when the
	 * row lacks drop_json or repeats an entry already seen. The identity
	 * depends on the direction of the lookup: "source" reads the dropping
	 * monster (item lookups list sources), anything else the item_name
	 * (monster lookups list items).
	 */
	private Map<String, Object> dropRow(JsonObject o, String idKey, Set<String> seen)
	{
		if (!o.has("drop_json"))
		{
			return null;
		}
		JsonObject dj = gson.fromJson(o.get("drop_json").getAsString(), JsonObject.class);
		String id = "source".equals(idKey)
			? (dj.has("Dropped from") ? dj.get("Dropped from").getAsString()
				: jstr(o, "page_name", "?"))
			: jstr(o, "item_name", "?");
		String qty = dj.has("Drop Quantity") ? dj.get("Drop Quantity").getAsString() : "?";
		String rarity = dj.has("Rarity") ? dj.get("Rarity").getAsString() : "?";
		if (!seen.add(id + "|" + qty + "|" + rarity))
		{
			return null;
		}
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put(idKey, id);
		entry.put("quantity", qty);
		entry.put("rarity", rarity);
		return entry;
	}

	/** Recipe-bucket rows on the item's own page: what it is made from.
	 * Rendered as compact strings ("100 x Crystal shard", "82 Smithing
	 * (boostable)") -- the model needs the requirements, not the wiki's
	 * image and cost bookkeeping. */
	private List<Map<String, Object>> recipes(String canonical) throws IOException
	{
		JsonObject r = content.bucket("bucket('recipe').select('production_json')"
			+ ".where('page_name','" + esc(canonical) + "').limit(5).run()");
		List<Map<String, Object>> out = new ArrayList<>();
		JsonArray rows = r.getAsJsonArray("bucket");
		if (rows == null)
		{
			return out;
		}
		for (JsonElement row : rows)
		{
			JsonObject o = row.getAsJsonObject();
			if (!o.has("production_json"))
			{
				continue;
			}
			JsonObject pj = gson.fromJson(o.get("production_json").getAsString(), JsonObject.class);
			Map<String, Object> rec = new LinkedHashMap<>();
			List<String> materials = new ArrayList<>();
			if (pj.has("materials") && pj.get("materials").isJsonArray())
			{
				for (JsonElement m : pj.getAsJsonArray("materials"))
				{
					JsonObject mo = m.getAsJsonObject();
					materials.add(jstr(mo, "quantity", "?") + " x " + jstr(mo, "name", "?"));
				}
			}
			List<String> skills = new ArrayList<>();
			if (pj.has("skills") && pj.get("skills").isJsonArray())
			{
				for (JsonElement s : pj.getAsJsonArray("skills"))
				{
					JsonObject so = s.getAsJsonObject();
					String skill = jstr(so, "level", "?") + " " + jstr(so, "name", "?");
					if ("Yes".equalsIgnoreCase(jstr(so, "boostable", "")))
					{
						skill += " (boostable)";
					}
					skills.add(skill);
				}
			}
			if (!materials.isEmpty())
			{
				rec.put("materials", materials);
			}
			if (!skills.isEmpty())
			{
				rec.put("skills", skills);
			}
			String facility = jstr(pj, "facilities", "");
			if (!facility.isEmpty())
			{
				rec.put("facility", facility);
			}
			if (!rec.isEmpty())
			{
				out.add(rec);
			}
		}
		return out;
	}

	/** Storeline-bucket rows: shops stocking the item, with price and stock. */
	private List<Map<String, Object>> shopStock(String canonical) throws IOException
	{
		JsonObject r = content.bucket("bucket('storeline')"
			+ ".select('sold_by','store_buy_price','store_currency','store_stock')"
			+ ".where('sold_item','" + esc(canonical) + "').limit(10).run()");
		List<Map<String, Object>> out = new ArrayList<>();
		JsonArray rows = r.getAsJsonArray("bucket");
		if (rows == null)
		{
			return out;
		}
		for (JsonElement row : rows)
		{
			JsonObject o = row.getAsJsonObject();
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("shop", jstr(o, "sold_by", "?"));
			String price = jstr(o, "store_buy_price", "");
			String currency = jstr(o, "store_currency", "");
			if (!price.isEmpty())
			{
				entry.put("price", (price + " " + currency).trim());
			}
			String stock = jstr(o, "store_stock", "");
			if (!stock.isEmpty())
			{
				entry.put("stock", stock);
			}
			out.add(entry);
		}
		return out;
	}

	private static String jstr(JsonObject o, String key, String fallback)
	{
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
			? o.get(key).getAsString() : fallback;
	}

	/** First row of a bucket result as a mutable map with null fields
	 * dropped, or null when the bucket matched nothing. The shared shape
	 * of every infobox lookup; per-field cleaning stays with the caller. */
	private Map<String, Object> firstRow(JsonObject r)
	{
		JsonArray rows = r.getAsJsonArray("bucket");
		if (rows == null || rows.size() == 0)
		{
			return null;
		}
		Map<String, Object> info = gson.fromJson(rows.get(0),
			new TypeToken<Map<String, Object>>() { }.getType());
		info.values().removeIf(Objects::isNull);
		return info;
	}

	/** Bucket query values sit in single quotes; escape accordingly. */
	private static String esc(String s)
	{
		return s.replace("'", "\\'");
	}

	/** Full drop table of a monster. */
	Map<String, Object> monsterDrops(String name)
	{
		try
		{
			JsonObject r = content.bucket("bucket('dropsline').select('item_name','drop_json')"
				+ ".where('page_name','" + esc(name) + "').limit(80).run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				// Monster page names are exact; try to resolve via search once.
				List<Map<String, Object>> hits = content.search(name);
				if (!hits.isEmpty() && hits.get(0).containsKey("title")
					&& !name.equals(hits.get(0).get("title")))
				{
					return monsterDrops((String) hits.get(0).get("title"));
				}
				return Map.of("error", "No drop data found for '" + name + "'.");
			}
			List<Map<String, Object>> out = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (JsonElement row : rows)
			{
				Map<String, Object> entry = dropRow(row.getAsJsonObject(), "item", seen);
				if (entry != null)
				{
					out.add(entry);
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("monster", name);
			result.put("drops", out);
			return result;
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
	}

	/**
	 * Combat profile of a monster. Deliberately complete: gear and style
	 * verdicts hinge on defensive stats, attributes (demon/dragon/undead
	 * drive demonbane and salve reasoning), and immunities. When these were
	 * omitted, models filled the gap from stale priors -- "low Defence" was
	 * once claimed for a Defence-150 monster the model had no numbers for.
	 */
	Map<String, Object> monsterInfo(String name)
	{
		try
		{
			JsonObject r = content.bucket("bucket('infobox_monster')"
				+ ".select('name','combat_level','hitpoints','max_hit',"
				+ "'slayer_level','slayer_category','attribute',"
				+ "'attack_level','strength_level','defence_level','ranged_level','magic_level',"
				+ "'stab_defence_bonus','slash_defence_bonus','crush_defence_bonus',"
				+ "'magic_defence_bonus','light_range_defence_bonus',"
				+ "'standard_range_defence_bonus','heavy_range_defence_bonus',"
				+ "'attack_style','attack_speed','size',"
				+ "'poison_resistance','venom_resistance','cannon_immune','burn_immune',"
				+ "'freeze_resistance','elemental_weakness','elemental_weakness_percent')"
				+ ".where('name','" + esc(name) + "').limit(3).run()");
			Map<String, Object> info = firstRow(r);
			if (info == null)
			{
				return Map.of("error", "No monster named '" + name + "' found. Check spelling via wiki_search.");
			}
			// The bucket carries "ERR" where the wiki's own template data is
			// broken (currently the resistance fields); junk, not a value.
			info.values().removeIf("ERR"::equals);
			info.replaceAll((k, v) -> stripMarkup(v));
			return info;
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
	}

	/**
	 * Equipment combat bonuses. The mirror of monsterInfo: infoboxes never
	 * survive plaintext extracts, so without this the model compares weapons
	 * it has no numbers for ("higher base stats -- exact numbers not in the
	 * retrieved data"). The bucket has no attack-speed or slot field; those
	 * live in page prose, which IS retrieved.
	 */
	Map<String, Object> itemStats(String name)
	{
		try
		{
			JsonObject r = content.bucket("bucket('infobox_bonuses')"
				+ ".select('page_name',"
				+ "'stab_attack_bonus','slash_attack_bonus','crush_attack_bonus',"
				+ "'magic_attack_bonus','range_attack_bonus',"
				+ "'stab_defence_bonus','slash_defence_bonus','crush_defence_bonus',"
				+ "'magic_defence_bonus','range_defence_bonus',"
				+ "'strength_bonus','ranged_strength_bonus','magic_damage_bonus','prayer_bonus')"
				+ ".where('page_name','" + esc(name) + "').limit(3).run()");
			Map<String, Object> info = firstRow(r);
			if (info == null)
			{
				return Map.of("error", "No equipment stats for '" + name
					+ "'. It may not be wearable, or the name may differ; check via wiki_search.");
			}
			info.replaceAll((k, v) -> stripMarkup(v));
			return info;
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
	}

	/**
	 * Quest requirements from the wiki's structured quest bucket. The
	 * {{Quest details}} template never survives plaintext extracts, so
	 * without this "can I do X" answers lack the skill levels and the
	 * prerequisite quest tree, and the model fills the gap from training
	 * data (where RS3 quests bleed in). As a bonus, prerequisite names
	 * appearing in this fact cause the pipeline to attach the player's
	 * live progress for each of them (relevantQuestStates scans facts).
	 */
	Map<String, Object> questInfo(String name)
	{
		try
		{
			JsonObject r = content.bucket("bucket('quest')"
				+ ".select('page_name','requirements','items_required','start_point')"
				+ ".where('page_name','" + esc(name) + "').limit(2).run()");
			Map<String, Object> info = firstRow(r);
			if (info == null)
			{
				return Map.of("error", "No quest named '" + name
					+ "' found. Check spelling via wiki_search.");
			}
			info.replaceAll((k, v) -> v instanceof String ? flattenWikitext((String) v) : v);
			return info;
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
	}

	/** Line-preserving cleanup for wikitext bucket fields: drops icon file
	 * links, unwraps [[page|label]] links, and strips HTML, but keeps the
	 * "*"/"**" bullet nesting that encodes the prerequisite tree. */
	private static String flattenWikitext(String wikitext)
	{
		StringBuilder sb = new StringBuilder();
		for (String line : wikitext.split("\n"))
		{
			String s = line
				.replaceAll("\\[\\[File:[^\\]]*\\]\\]", "")
				.replaceAll("\\[\\[[^|\\]]*\\|([^\\]]*)\\]\\]", "$1")
				.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1")
				.replaceAll("<[^>]+>", "")
				.replaceAll("[ \t]+", " ")
				.trim();
			if (!s.isEmpty() && !s.matches("\\**"))
			{
				sb.append(s).append('\n');
			}
		}
		return sb.toString().trim();
	}

	/** Bucket TEXT fields can carry raw HTML ("<div class=..>*31 (auto)");
	 * flatten to plain text so stat blocks stay readable. */
	@SuppressWarnings("unchecked")
	private static Object stripMarkup(Object value)
	{
		if (value instanceof String)
		{
			return ((String) value).replaceAll("<[^>]+>", " ")
				.replaceAll("[\\s*]+", " ").trim();
		}
		if (value instanceof List)
		{
			List<Object> out = new ArrayList<>();
			for (Object v : (List<Object>) value)
			{
				out.add(stripMarkup(v));
			}
			return out;
		}
		return value;
	}

	/** Current GE price, buy limit, and high-alch value. */
	Map<String, Object> gePrice(String itemName)
	{
		try
		{
			String canonical = resolveItemName(itemName);
			for (Map<String, Object> it : vocab.geMapping())
			{
				if (!canonical.equals(it.get("name")))
				{
					continue;
				}
				long id = ((Number) it.get("id")).longValue();
				JsonObject r = http.getJson(PRICES_API + "/v2/osrs/latest?id=" + id);
				JsonObject data = r.getAsJsonObject("data").getAsJsonObject(String.valueOf(id));
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("item", canonical);
				out.put("high", data != null && data.has("high") && !data.get("high").isJsonNull()
					? data.get("high").getAsLong() : null);
				out.put("low", data != null && data.has("low") && !data.get("low").isJsonNull()
					? data.get("low").getAsLong() : null);
				out.put("buy_limit", it.get("limit"));
				out.put("high_alch", it.get("highalch"));
				return out;
			}
			return Map.of("error", "'" + itemName + "' is not a tradeable item.");
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
	}
}
