package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * OSRS Wiki + Grand Exchange API access, with disk-cached vocabularies.
 * Direct port of the Python tools.py: tools do the fiddly work (name
 * normalization, API syntax) so the model gets simple names and tidy results.
 */
@Slf4j
public class WikiApi
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final String PRICES_API = "https://prices.runescape.wiki/api";
	private static final String WORDLIST_URL =
		"https://raw.githubusercontent.com/first20hours/google-10000-english/master/google-10000-english.txt";

	/** Extract-to-page-size ratio below which the plaintext extract has lost
	 * the page's substance to table stripping; see isHusk. */
	private static final double HUSK_RATIO = 0.3;

	private static final int PAGE_CHAR_LIMIT = 7000;
	private static final int WIKITEXT_CHAR_LIMIT = 12000;

	/** Vocab caches refresh after this age, so game/wiki changes flow in
	 * without a code update. */
	private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

	private final Http http;
	private final Gson gson;
	private final File cacheDir;

	private List<Map<String, Object>> geMapping;
	private Set<String> monsterNames;
	private Set<String> englishWords;
	private List<NamedPoint> locationIndex;

	/** A named place on the world map, from the wiki's live map data. */
	public static class NamedPoint
	{
		public String name;
		public int x;
		public int y;
		public int plane;
	}

	public WikiApi(Http http, Gson gson, File cacheDir)
	{
		this.http = http;
		this.gson = gson;
		this.cacheDir = cacheDir;
	}

	// ------------------------------------------------------------------
	// Cached vocabularies
	// ------------------------------------------------------------------

	synchronized List<Map<String, Object>> geMapping() throws IOException
	{
		if (geMapping == null)
		{
			String json = cachedFetch("ge_mapping.json",
				() -> http.getText(PRICES_API + "/v2/osrs/mapping"));
			geMapping = gson.fromJson(json,
				new TypeToken<List<Map<String, Object>>>() { }.getType());
		}
		return geMapping;
	}

	synchronized Set<String> monsterNames() throws IOException
	{
		if (monsterNames == null)
		{
			// Prefer page_name over the infobox name: it is the exact wiki
			// title, so strategy subpages and dropsline page_name queries
			// match without casing surprises.
			String json = cachedFetch("monsters_v2.json", () -> {
				JsonObject r = bucket("bucket('infobox_monster').select('name','page_name').limit(5000).run()");
				Set<String> names = new TreeSet<>();
				for (JsonElement row : r.getAsJsonArray("bucket"))
				{
					JsonObject o = row.getAsJsonObject();
					JsonElement page = o.get("page_name");
					JsonElement name = o.get("name");
					if (page != null && !page.isJsonNull())
					{
						names.add(page.getAsString());
					}
					else if (name != null && !name.isJsonNull())
					{
						names.add(name.getAsString());
					}
				}
				return gson.toJson(names);
			});
			monsterNames = gson.fromJson(json, new TypeToken<Set<String>>() { }.getType());
		}
		return monsterNames;
	}

	/** 10k most common English words; used by the resolver to spot slang
	 * (OSRS abbreviations are almost never dictionary words). Empty set on
	 * fetch failure -- the resolver degrades gracefully. */
	synchronized Set<String> englishWords()
	{
		if (englishWords == null)
		{
			try
			{
				String text = cachedFetch("english_10k.txt", () -> http.getText(WORDLIST_URL));
				englishWords = new HashSet<>();
				for (String w : text.split("\n"))
				{
					if (!w.trim().isEmpty())
					{
						englishWords.add(w.trim());
					}
				}
			}
			catch (IOException e)
			{
				log.warn("Wordlist unavailable", e);
				englishWords = new HashSet<>();
			}
		}
		return englishWords;
	}

	private String cachedFetch(String filename, Fetcher fetcher) throws IOException
	{
		File f = new File(cacheDir, filename);
		boolean fresh = f.exists()
			&& System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS;
		if (fresh)
		{
			return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
		}
		try
		{
			String content = fetcher.fetch();
			cacheDir.mkdirs();
			Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
			return content;
		}
		catch (IOException e)
		{
			// Refresh failed but a stale copy exists: stale beats broken.
			if (f.exists())
			{
				log.warn("cache refresh failed for {}, using stale copy", filename, e);
				return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			}
			throw e;
		}
	}

	private interface Fetcher
	{
		String fetch() throws IOException;
	}

	/**
	 * Named places with world coordinates, built by joining two live wiki
	 * datasets: pages with a location infobox (what counts as a place) and
	 * the map bucket (where each page's map is centered). Entirely
	 * wiki-maintained -- new areas appear on cache refresh, no code changes.
	 */
	synchronized List<NamedPoint> locationIndex() throws IOException
	{
		if (locationIndex == null)
		{
			String json = cachedFetch("locations.json", () -> {
				Set<String> places = new HashSet<>();
				JsonObject r = bucket("bucket('infobox_location').select('page_name').limit(5000).run()");
				for (JsonElement row : r.getAsJsonArray("bucket"))
				{
					JsonElement name = row.getAsJsonObject().get("page_name");
					if (name != null && !name.isJsonNull())
					{
						places.add(name.getAsString());
					}
				}

				List<NamedPoint> points = new ArrayList<>();
				Set<String> seen = new HashSet<>();
				for (int offset = 0; offset < 100_000; offset += 5000)
				{
					JsonObject page = bucket("bucket('map').select('page_name','options')"
						+ ".limit(5000).offset(" + offset + ").run()");
					JsonArray rows = page.getAsJsonArray("bucket");
					if (rows == null || rows.size() == 0)
					{
						break;
					}
					for (JsonElement e : rows)
					{
						JsonObject row = e.getAsJsonObject();
						if (!row.has("page_name") || row.get("page_name").isJsonNull()
							|| !row.has("options") || row.get("options").isJsonNull())
						{
							continue;
						}
						String name = row.get("page_name").getAsString();
						if (!places.contains(name) || !seen.add(name))
						{
							continue;
						}
						try
						{
							JsonObject opts = gson.fromJson(row.get("options").getAsString(), JsonObject.class);
							// mapID 0 = the main game world surface map.
							if (opts.has("mapID") && opts.get("mapID").getAsInt() != 0)
							{
								seen.remove(name);
								continue;
							}
							NamedPoint p = new NamedPoint();
							p.name = name;
							p.x = (int) opts.get("x").getAsDouble();
							p.y = (int) opts.get("y").getAsDouble();
							p.plane = opts.has("plane") ? opts.get("plane").getAsInt() : 0;
							points.add(p);
						}
						catch (Exception ignored)
						{
							seen.remove(name);
						}
					}
					if (rows.size() < 5000)
					{
						break;
					}
				}
				return gson.toJson(points);
			});
			locationIndex = gson.fromJson(json, new TypeToken<List<NamedPoint>>() { }.getType());
		}
		return locationIndex;
	}

	/** Nearest named places to a world point, closest first. Empty on
	 * index failure -- callers fall back to raw coordinates. */
	List<NamedPoint> nearestPlaces(int x, int y, int count)
	{
		try
		{
			List<NamedPoint> index = locationIndex();
			List<NamedPoint> sorted = new ArrayList<>(index);
			sorted.sort((a, b) -> Long.compare(distSq(a, x, y), distSq(b, x, y)));
			return sorted.subList(0, Math.min(count, sorted.size()));
		}
		catch (Exception e)
		{
			log.warn("location index unavailable", e);
			return new ArrayList<>();
		}
	}

	static long distSq(NamedPoint p, int x, int y)
	{
		long dx = p.x - x;
		long dy = p.y - y;
		return dx * dx + dy * dy;
	}

	// ------------------------------------------------------------------
	// Wiki queries
	// ------------------------------------------------------------------

	JsonObject bucket(String query) throws IOException
	{
		return http.getJson(WIKI_API + "?action=bucket&format=json&query=" + Http.enc(query));
	}

	JsonObject wikiQuery(String params) throws IOException
	{
		return http.getJson(WIKI_API + "?action=query&format=json&" + params);
	}

	/** Search the wiki. Returns [{title, snippet}]. */
	List<Map<String, Object>> search(String query)
	{
		try
		{
			JsonObject r = wikiQuery("list=search&srlimit=5&srsearch=" + Http.enc(query));
			List<Map<String, Object>> out = new ArrayList<>();
			for (JsonElement hit : r.getAsJsonObject("query").getAsJsonArray("search"))
			{
				JsonObject h = hit.getAsJsonObject();
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("title", h.get("title").getAsString());
				entry.put("snippet", h.get("snippet").getAsString()
					.replace("<span class=\"searchmatch\">", "").replace("</span>", ""));
				out.add(entry);
			}
			return out;
		}
		catch (Exception e)
		{
			return List.of(Map.of("error", "search failed: " + e.getMessage()));
		}
	}

	/**
	 * Resolves each name to the wiki page it lands on, or null when the wiki
	 * has no such page. The wiki is the closed vocabulary of what exists in
	 * this game, and the page a name lands on says how the name relates to
	 * it: an unchanged title is the thing itself, a near-identical title is a
	 * spelling or plural variant, and a wholly different title means the name
	 * is not this game's name for anything (RS3's "Anachronia" resolves to
	 * "Fossil Island"). Batched 50 per request (API limit).
	 */
	Map<String, String> resolveTitles(Collection<String> names) throws IOException
	{
		Map<String, String> resolved = new LinkedHashMap<>();
		List<String> batch = new ArrayList<>(new LinkedHashSet<>(names));
		for (int i = 0; i < batch.size(); i += 50)
		{
			List<String> slice = batch.subList(i, Math.min(i + 50, batch.size()));
			JsonObject query = wikiQuery("redirects=1&titles="
				+ Http.enc(String.join("|", slice))).getAsJsonObject("query");
			if (query == null)
			{
				continue;
			}
			Map<String, String> hops = new LinkedHashMap<>();
			addHops(hops, query, "normalized");
			addHops(hops, query, "redirects");
			Set<String> missing = new LinkedHashSet<>();
			if (query.has("pages"))
			{
				for (Map.Entry<String, JsonElement> e : query.getAsJsonObject("pages").entrySet())
				{
					JsonObject page = e.getValue().getAsJsonObject();
					if (page.has("missing") || page.has("invalid"))
					{
						missing.add(page.get("title").getAsString());
					}
				}
			}
			for (String name : slice)
			{
				String title = name;
				// Normalization and redirects are reported as hops; follow the
				// chain, guarding against redirect loops.
				for (int hop = 0; hop < 5 && hops.containsKey(title); hop++)
				{
					title = hops.get(title);
				}
				resolved.put(name, missing.contains(title) ? null : title);
			}
		}
		return resolved;
	}

	private static void addHops(Map<String, String> hops, JsonObject query, String field)
	{
		if (!query.has(field))
		{
			return;
		}
		for (JsonElement e : query.getAsJsonArray(field))
		{
			JsonObject hop = e.getAsJsonObject();
			hops.put(hop.get("from").getAsString(), hop.get("to").getAsString());
		}
	}

	/**
	 * Page content, truncated. Plaintext extract normally; falls back to raw
	 * wikitext when the extract lost the page's substance. Returns null when
	 * the page doesn't exist -- the one not-found convention for all content
	 * methods here; only the LLM tool boundary turns that into an error value.
	 */
	String page(String title)
	{
		return page(title, 0);
	}

	/** Same, with a caller-chosen char budget (0 = defaults). */
	String page(String title, int charLimit)
	{
		try
		{
			// prop=info gives the page's wikitext size, so the extract can be
			// measured against what it was made from in the same request.
			JsonObject r = wikiQuery("prop=extracts%7Cinfo&explaintext=1&redirects=1"
				+ "&titles=" + Http.enc(title));
			JsonObject pages = r.getAsJsonObject("query").getAsJsonObject("pages");
			for (Map.Entry<String, JsonElement> entry : pages.entrySet())
			{
				JsonObject p = entry.getValue().getAsJsonObject();
				if (!p.has("extract"))
				{
					continue;
				}
				String text = p.get("extract").getAsString();
				int pageBytes = p.has("length") ? p.get("length").getAsInt() : 0;
				if (text.isEmpty() && pageBytes == 0)
				{
					continue;
				}
				// An empty extract from a non-empty page is the extreme husk:
				// table-only pages plaintext-extract to nothing.
				if (text.isEmpty() || isHusk(text, pageBytes))
				{
					log.debug("husk extract for {} ({}B of {}B page), using wikitext",
						title, text.length(), pageBytes);
					return wikitext(title, WIKITEXT_CHAR_LIMIT);
				}
				return truncate(text, charLimit > 0 ? charLimit : PAGE_CHAR_LIMIT);
			}
		}
		catch (Exception e)
		{
			log.debug("page fetch failed for {}", title, e);
		}
		return null;
	}

	/**
	 * Whether a plaintext extract lost the page's substance to table
	 * stripping. Table-only pages come back as a shell of section headings
	 * with nothing under them, which is worse than no page at all: it reads
	 * as "the wiki has nothing here" and invites the model to fill the gap
	 * from memory. Measured, not guessed -- on a sample spanning items,
	 * monsters, locations, skills and diaries, genuinely table-gutted pages
	 * (Anvil 0.05, Bones 0.12, Varrock Diary 0.17, Adamantite bar 0.22) sit
	 * well below prose pages (0.39-0.71).
	 */
	private static boolean isHusk(String extract, int pageBytes)
	{
		return pageBytes > 0 && (double) extract.length() / pageBytes < HUSK_RATIO;
	}

	/**
	 * Fetch one section of a page by heading, using the wiki's own document
	 * structure (action=parse&prop=sections). Returns null when the page has
	 * no matching section -- callers treat that as "nothing to add".
	 */
	String sectionByHeading(String title, Pattern headingPattern, int charLimit)
	{
		try
		{
			JsonObject r = http.getJson(WIKI_API + "?action=parse&prop=sections&format=json"
				+ "&redirects=1&page=" + Http.enc(title));
			for (JsonElement e : r.getAsJsonObject("parse").getAsJsonArray("sections"))
			{
				JsonObject s = e.getAsJsonObject();
				if (!headingPattern.matcher(s.get("line").getAsString()).find())
				{
					continue;
				}
				JsonObject sec = http.getJson(WIKI_API + "?action=parse&prop=wikitext&format=json"
					+ "&redirects=1&page=" + Http.enc(title) + "&section=" + s.get("index").getAsString());
				String text = sec.getAsJsonObject("parse").getAsJsonObject("wikitext")
					.get("*").getAsString();
				return truncate(text, charLimit);
			}
		}
		catch (Exception e)
		{
			log.debug("section fetch failed for {}", title, e);
		}
		return null;
	}

	/** Wiki pages often keep large tables on subpages transcluded as
	 * {{/Locations}} etc.; the raw wikitext only has the stub. Follow the
	 * wiki's own structure one level down. */
	private static final Pattern SUBPAGE_TRANSCLUSION =
		Pattern.compile("\\{\\{/([^}|{]+)(\\|[^}]*)?\\}\\}");

	/** Raw wikitext (preserves tables and templates), with directly
	 * transcluded subpages inlined. Null when the page doesn't exist. */
	String wikitext(String title, int charLimit)
	{
		try
		{
			String text = rawWikitext(title);
			text = inlineSubpages(title, text, charLimit);
			return truncate(text, charLimit);
		}
		catch (Exception e)
		{
			log.debug("wikitext fetch failed for {}", title, e);
			return null;
		}
	}

	private String rawWikitext(String title) throws IOException
	{
		JsonObject r = http.getJson(WIKI_API + "?action=parse&prop=wikitext&format=json"
			+ "&redirects=1&page=" + Http.enc(title));
		return r.getAsJsonObject("parse").getAsJsonObject("wikitext")
			.get("*").getAsString();
	}

	private String inlineSubpages(String title, String text, int charLimit)
	{
		Matcher m = SUBPAGE_TRANSCLUSION.matcher(text);
		StringBuffer sb = new StringBuffer();
		while (m.find() && sb.length() < charLimit)
		{
			String replacement;
			try
			{
				replacement = rawWikitext(title + "/" + m.group(1).trim());
			}
			catch (Exception e)
			{
				replacement = m.group(0);
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// Structured game data
	// ------------------------------------------------------------------

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
			String target = resolveTitles(List.of(name)).get(name);
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
			String shortestPartial = null;
			for (Map<String, Object> it : geMapping())
			{
				String candidate = (String) it.get("name");
				if (candidate.toLowerCase(Locale.ROOT).contains(lower)
					&& (shortestPartial == null || candidate.length() < shortestPartial.length()))
				{
					shortestPartial = candidate;
				}
			}
			if (shortestPartial != null)
			{
				return shortestPartial;
			}
			List<Map<String, Object>> hits = search(name);
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
		for (Map<String, Object> it : geMapping())
		{
			String candidate = (String) it.get("name");
			if (candidate.equalsIgnoreCase(name))
			{
				return candidate;
			}
		}
		return null;
	}

	/** Shortest "Name (qualifier)" entry in the GE mapping, or null. */
	private String qualifiedVariant(String name) throws IOException
	{
		String prefix = name.toLowerCase(Locale.ROOT) + " (";
		String best = null;
		for (Map<String, Object> it : geMapping())
		{
			String candidate = (String) it.get("name");
			if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)
				&& (best == null || candidate.length() < best.length()))
			{
				best = candidate;
			}
		}
		return best;
	}

	/** Monsters/activities that drop an item, with quantity and rarity. */
	Map<String, Object> itemDropSources(String itemName)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		try
		{
			String canonical = resolveItemName(itemName);
			JsonObject r = bucket("bucket('dropsline').select('page_name','drop_json')"
				+ ".where('item_name','" + canonical.replace("'", "\\'") + "').limit(50).run()");
			List<Map<String, Object>> out = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (JsonElement row : r.getAsJsonArray("bucket"))
			{
				JsonObject o = row.getAsJsonObject();
				if (!o.has("drop_json"))
				{
					continue;
				}
				JsonObject dj = gson.fromJson(o.get("drop_json").getAsString(), JsonObject.class);
				String source = dj.has("Dropped from") ? dj.get("Dropped from").getAsString()
					: (o.has("page_name") ? o.get("page_name").getAsString() : "?");
				String qty = dj.has("Drop Quantity") ? dj.get("Drop Quantity").getAsString() : "?";
				String rarity = dj.has("Rarity") ? dj.get("Rarity").getAsString() : "?";
				if (!seen.add(source + "|" + qty + "|" + rarity))
				{
					continue;
				}
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("source", source);
				entry.put("quantity", qty);
				entry.put("rarity", rarity);
				out.add(entry);
				if (out.size() >= 30)
				{
					break;
				}
			}
			result.put("item", canonical);
			result.put("sources", out);
			if (!canonical.equalsIgnoreCase(itemName))
			{
				result.put("note", "'" + itemName + "' resolved to in-game item '" + canonical + "'");
			}
			if (out.isEmpty())
			{
				result.put("note", "No drop sources found for '" + canonical + "'. It may not be "
					+ "dropped by monsters; check wiki_page for other ways to obtain it.");
			}
		}
		catch (Exception e)
		{
			result.put("error", "lookup failed: " + e.getMessage());
		}
		return result;
	}

	/** Full drop table of a monster. */
	Map<String, Object> monsterDrops(String name)
	{
		try
		{
			JsonObject r = bucket("bucket('dropsline').select('item_name','drop_json')"
				+ ".where('page_name','" + name.replace("'", "\\'") + "').limit(80).run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				// Monster page names are exact; try to resolve via search once.
				List<Map<String, Object>> hits = search(name);
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
				JsonObject o = row.getAsJsonObject();
				if (!o.has("drop_json"))
				{
					continue;
				}
				JsonObject dj = gson.fromJson(o.get("drop_json").getAsString(), JsonObject.class);
				String item = o.has("item_name") ? o.get("item_name").getAsString() : "?";
				String qty = dj.has("Drop Quantity") ? dj.get("Drop Quantity").getAsString() : "?";
				String rarity = dj.has("Rarity") ? dj.get("Rarity").getAsString() : "?";
				if (!seen.add(item + "|" + qty + "|" + rarity))
				{
					continue;
				}
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("item", item);
				entry.put("quantity", qty);
				entry.put("rarity", rarity);
				out.add(entry);
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
			JsonObject r = bucket("bucket('infobox_monster')"
				+ ".select('name','combat_level','hitpoints','max_hit',"
				+ "'slayer_level','slayer_category','attribute',"
				+ "'attack_level','strength_level','defence_level','ranged_level','magic_level',"
				+ "'stab_defence_bonus','slash_defence_bonus','crush_defence_bonus',"
				+ "'magic_defence_bonus','light_range_defence_bonus',"
				+ "'standard_range_defence_bonus','heavy_range_defence_bonus',"
				+ "'attack_style','attack_speed','size',"
				+ "'venom_immune','cannon_immune','burn_immune','freeze_resistance',"
				+ "'elemental_weakness','elemental_weakness_percent')"
				+ ".where('name','" + name.replace("'", "\\'") + "').limit(3).run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				return Map.of("error", "No monster named '" + name + "' found. Check spelling via wiki_search.");
			}
			Map<String, Object> info = gson.fromJson(rows.get(0),
				new TypeToken<Map<String, Object>>() { }.getType());
			info.values().removeIf(Objects::isNull);
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
			JsonObject r = bucket("bucket('infobox_bonuses')"
				+ ".select('page_name',"
				+ "'stab_attack_bonus','slash_attack_bonus','crush_attack_bonus',"
				+ "'magic_attack_bonus','range_attack_bonus',"
				+ "'stab_defence_bonus','slash_defence_bonus','crush_defence_bonus',"
				+ "'magic_defence_bonus','range_defence_bonus',"
				+ "'strength_bonus','ranged_strength_bonus','magic_damage_bonus','prayer_bonus')"
				+ ".where('page_name','" + name.replace("'", "\\'") + "').limit(3).run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				return Map.of("error", "No equipment stats for '" + name
					+ "'. It may not be wearable, or the name may differ; check via wiki_search.");
			}
			Map<String, Object> info = gson.fromJson(rows.get(0),
				new TypeToken<Map<String, Object>>() { }.getType());
			info.values().removeIf(Objects::isNull);
			info.replaceAll((k, v) -> stripMarkup(v));
			return info;
		}
		catch (Exception e)
		{
			return Map.of("error", "lookup failed: " + e.getMessage());
		}
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
			for (Map<String, Object> it : geMapping())
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

	private static String truncate(String s, int limit)
	{
		return s.length() > limit ? s.substring(0, limit) : s;
	}
}
