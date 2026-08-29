package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import okhttp3.OkHttpClient;

/**
 * Compiles the bulk vocabularies the plugin needs and writes them as
 * gzipped snapshots for publishing to the vocab-data branch. The only code
 * that runs the wiki's expensive bulk queries; it runs weekly in CI, never
 * on a user's machine. Every dataset has a minimum-size threshold: a wiki
 * API change that guts a dataset fails the job and leaves the previous
 * snapshot published.
 */
public final class VocabSnapshotTool
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final String PRICES_API = "https://prices.runescape.wiki/api";
	private static final String WORDLIST_URL =
		"https://raw.githubusercontent.com/first20hours/google-10000-english/master/google-10000-english.txt";

	private final Http http;
	private final Gson gson;
	/** Milliseconds slept before each wiki request. Zero for local test
	 * runs; CI passes ~20s so the weekly gather spreads over ~15-20
	 * minutes instead of bursting fifty requests at the wiki. */
	private final long paceMs;

	private VocabSnapshotTool(Http http, Gson gson, long paceMs)
	{
		this.http = http;
		this.gson = gson;
		this.paceMs = paceMs;
	}

	public static void main(String[] args) throws Exception
	{
		File outDir = new File(args.length > 0 ? args[0] : "build/vocab");
		outDir.mkdirs();
		long paceMs = (args.length > 1 ? Long.parseLong(args[1]) : 0) * 1000;
		Gson gson = new Gson();
		VocabSnapshotTool tool = new VocabSnapshotTool(new Http(new OkHttpClient(), gson), gson, paceMs);
		if (paceMs > 0)
		{
			System.out.println("pacing: " + (paceMs / 1000) + "s before each request");
		}

		// ~85% of the live counts measured 2026-08. These datasets only
		// grow with game updates; a dip below means the query broke.
		Map<String, JsonObject> geMapping = tool.geMapping();
		Set<String> monsters = tool.monsterNameSet();
		tool.write(outDir, "monsters_v2.json",
			new Sized(gson.toJson(monsters), monsters.size()), 1400, "monster names");
		tool.write(outDir, "strategies.json", tool.strategiesIndex(monsters), 105, "strategy subpages");
		// 178 live titles measured 2026-08-21, redirect aliases included.
		tool.write(outDir, "slayer_tasks.json", tool.slayerTaskIndex(), 150, "slayer task subpages");
		Sized wordlist = tool.wordlist();
		tool.write(outDir, "english_10k.txt", wordlist, 9000, "wordlist lines");
		// ~85% of the 11,006 canonical rows measured 2026-08-28.
		tool.write(outDir, "items_v2.json",
			tool.itemIndex(wordSet(wordlist.content), geMapping),
			9300, "item descriptors");

		// For humans inspecting the branch; clients never read this.
		java.nio.file.Files.write(new File(outDir, "stamp.txt").toPath(),
			(java.time.Instant.now() + "\n").getBytes(StandardCharsets.UTF_8));
		System.out.println("snapshots written to " + outDir.getAbsolutePath());
	}

	/** Gzips content to outDir after checking the record-count threshold. */
	private void write(File outDir, String filename, Sized data, int minCount, String what)
		throws IOException
	{
		if (data.count < minCount)
		{
			throw new IllegalStateException("SANITY FAIL: " + filename + " has " + data.count
				+ " " + what + ", expected >= " + minCount + " -- refusing to publish");
		}
		File f = new File(outDir, filename + ".gz");
		try (GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(f)))
		{
			out.write(data.content.getBytes(StandardCharsets.UTF_8));
		}
		System.out.printf("%-22s %6d %-20s %8d bytes gz%n", filename, data.count, what, f.length());
	}

	private static final class Sized
	{
		final String content;
		final int count;

		Sized(String content, int count)
		{
			this.content = content;
			this.count = count;
		}
	}

	/** GE catalogue entries by lowercased name. Not published on its own:
	 * its fields (tradeability, buy limit, high alch, ID backfill) ride on
	 * the item descriptors. */
	private Map<String, JsonObject> geMapping() throws IOException
	{
		String json = getText(PRICES_API + "/v2/osrs/mapping");
		Map<String, JsonObject> byName = new HashMap<>();
		for (JsonElement e : gson.fromJson(json, JsonArray.class))
		{
			JsonObject entry = e.getAsJsonObject();
			JsonElement name = entry.get("name");
			if (name != null && !name.isJsonNull())
			{
				byName.putIfAbsent(name.getAsString().toLowerCase(Locale.ROOT), entry);
			}
		}
		if (byName.size() < 4000)
		{
			throw new IllegalStateException("SANITY FAIL: GE mapping has " + byName.size()
				+ " entries, expected >= 4000 -- refusing to publish");
		}
		return byName;
	}

	private Sized wordlist() throws IOException
	{
		String text = getText(WORDLIST_URL);
		return new Sized(text, text.split("\n").length);
	}

	/** page_name is the exact wiki title, so downstream queries match
	 * without casing surprises. Paginated defensively: versioned monsters
	 * multiply rows and nothing warns when a dataset outgrows one page. */
	private Set<String> monsterNameSet() throws IOException
	{
		Set<String> names = new TreeSet<>();
		for (int offset = 0; offset < 100_000; offset += 5000)
		{
			JsonObject r = bucket("bucket('infobox_monster').select('name','page_name')"
				+ ".limit(5000).offset(" + offset + ").run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				break;
			}
			for (JsonElement row : rows)
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
			if (rows.size() < 5000)
			{
				break;
			}
		}
		return names;
	}

	/** Every /Strategies guide subpage that exists. A title search finds
	 * every real page with the suffix but skips redirect titles (which
	 * still fetch fine), so monster-derived titles are additionally batch
	 * existence-checked, 50 per request. */
	private Sized strategiesIndex(Set<String> monsterNames) throws IOException
	{
		Set<String> exists = new TreeSet<>();
		Integer offset = 0;
		while (offset != null)
		{
			JsonObject r = getJson(WIKI_API + "?action=query&list=search&format=json"
				+ "&srlimit=50&sroffset=" + offset
				+ "&srsearch=" + Http.enc("intitle:\"Strategies\""));
			for (JsonElement e : r.getAsJsonObject("query").getAsJsonArray("search"))
			{
				String title = e.getAsJsonObject().get("title").getAsString();
				if (title.endsWith("/Strategies"))
				{
					exists.add(title);
				}
			}
			offset = r.has("continue")
				? r.getAsJsonObject("continue").get("sroffset").getAsInt() : null;
		}
		List<String> batch = new ArrayList<>(50);
		for (String name : monsterNames)
		{
			batch.add(name + "/Strategies");
			if (batch.size() == 50)
			{
				addExisting(batch, exists);
				batch.clear();
			}
		}
		if (!batch.isEmpty())
		{
			addExisting(batch, exists);
		}
		return new Sized(gson.toJson(exists), exists.size());
	}

	/** Every "Slayer task/..." guide subpage, redirect aliases included:
	 * the aliases are matching surface for mapping a creature name to its
	 * task guide. */
	private Sized slayerTaskIndex() throws IOException
	{
		Set<String> titles = new TreeSet<>();
		String cont = null;
		do
		{
			JsonObject r = getJson(WIKI_API + "?action=query&list=allpages&format=json"
				+ "&apprefix=" + Http.enc("Slayer task/") + "&aplimit=500"
				+ (cont != null ? "&apcontinue=" + Http.enc(cont) : ""));
			for (JsonElement e : r.getAsJsonObject("query").getAsJsonArray("allpages"))
			{
				String title = e.getAsJsonObject().get("title").getAsString();
				// The bare "Slayer task/" root is in the listing too.
				if (title.length() > "Slayer task/".length())
				{
					titles.add(title);
				}
			}
			cont = r.has("continue")
				? r.getAsJsonObject("continue").get("apcontinue").getAsString() : null;
		} while (cont != null);
		return new Sized(gson.toJson(titles), titles.size());
	}

	/** Adds the titles in the batch that exist on the wiki to out. */
	private void addExisting(List<String> titles, Set<String> out) throws IOException
	{
		JsonObject r = getJson(WIKI_API + "?action=query&format=json&titles="
			+ Http.enc(String.join("|", titles)));
		JsonObject pages = r.getAsJsonObject("query").getAsJsonObject("pages");
		for (String pageId : pages.keySet())
		{
			JsonObject p = pages.getAsJsonObject(pageId);
			if (!p.has("missing"))
			{
				out.add(p.get("title").getAsString());
			}
		}
	}

	/** ~85% of the 10,872 id-bearing rows measured 2026-08-28. Guards the
	 * wiki dropping or reshaping item_id, like the Module:Map incident. */
	private static final int MIN_ITEM_IDS = 9200;

	/** Every item as a canonical {@link ItemDescriptor} from the item
	 * infoboxes, fully canonicalized here so clients just parse. */
	private Sized itemIndex(Set<String> englishWords, Map<String, JsonObject> geByName)
		throws IOException
	{
		List<JsonObject> rows = new ArrayList<>();
		for (int offset = 0; offset < 100_000; offset += 5000)
		{
			JsonObject page = bucket("bucket('infobox_item')"
				+ ".select('page_name','item_name','item_id','removal_date')"
				+ ".limit(5000).offset(" + offset + ").run()");
			JsonArray batch = page.getAsJsonArray("bucket");
			if (batch == null || batch.size() == 0)
			{
				break;
			}
			for (JsonElement e : batch)
			{
				rows.add(e.getAsJsonObject());
			}
			if (batch.size() < 5000)
			{
				break;
			}
		}
		List<ItemDescriptor> items = canonicalItems(rows, englishWords, geByName);
		long withId = items.stream().filter(it -> it.id != null).count();
		if (withId < MIN_ITEM_IDS)
		{
			throw new IllegalStateException("SANITY FAIL: items_v2.json has " + withId
				+ " id-bearing descriptors, expected >= " + MIN_ITEM_IDS
				+ " -- refusing to publish");
		}
		Set<String> lowerNames = new HashSet<>();
		for (ItemDescriptor it : items)
		{
			lowerNames.add(it.name.toLowerCase(Locale.ROOT));
		}
		for (String variant : ItemCollectives.variantTerms())
		{
			if (lowerNames.stream().noneMatch(n -> n.contains(variant)))
			{
				throw new IllegalStateException("SANITY FAIL: collective variant '"
					+ variant + "' matches no catalogued item -- table is stale");
			}
		}
		return new Sized(gson.toJson(items), items.size());
	}

	/** Canonicalization: drop removed content, fake pages, and bare
	 * dictionary-word names (kept when tradeable: prose "bread" means the
	 * item); resolve duplicate display names to their real page; take the
	 * first decimal game ID, else the GE one (new tradeables get GE IDs
	 * before wiki infobox IDs); attach GE buy limit and high alch.
	 * Deterministic for identical input rows. Package-private for tests. */
	static List<ItemDescriptor> canonicalItems(List<JsonObject> rows,
		Set<String> englishWords, Map<String, JsonObject> geByName)
	{
		Map<String, List<JsonObject>> byName = new HashMap<>();
		for (JsonObject row : rows)
		{
			JsonElement name = row.get("item_name");
			JsonElement removed = row.get("removal_date");
			if (name == null || name.isJsonNull()
				|| (removed != null && !removed.isJsonNull()))
			{
				continue;
			}
			if (fakeItemPage(pageOf(row)))
			{
				continue;
			}
			String lower = name.getAsString().toLowerCase(Locale.ROOT);
			if (name.getAsString().indexOf(' ') < 0 && englishWords.contains(lower)
				&& !geByName.containsKey(lower))
			{
				continue;
			}
			byName.computeIfAbsent(lower, k -> new ArrayList<>()).add(row);
		}
		List<ItemDescriptor> out = new ArrayList<>(byName.size());
		for (Map.Entry<String, List<JsonObject>> group : byName.entrySet())
		{
			JsonObject row = group.getValue().stream()
				.min(Comparator.comparingInt(VocabSnapshotTool::pageRank)
					.thenComparing(VocabSnapshotTool::pageOf)
					.thenComparing(r -> r.get("item_name").getAsString()))
				.get();
			String name = row.get("item_name").getAsString();
			JsonObject ge = geByName.get(group.getKey());
			Integer id = gameId(row);
			if (id == null && ge != null)
			{
				id = intField(ge, "id");
			}
			out.add(new ItemDescriptor(name, pageOf(row), id, ge != null,
				ge != null ? intField(ge, "limit") : null,
				ge != null ? intField(ge, "highalch") : null));
		}
		out.sort(Comparator.comparing(it -> it.name, String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private static Integer intField(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
			? e.getAsInt() : null;
	}

	/** Prefer the page named exactly like the item, then any real article
	 * over disambiguated ones ("Fire cape" over "Fire cape (Last Man
	 * Standing)"). */
	private static int pageRank(JsonObject row)
	{
		String page = pageOf(row);
		return page.equals(row.get("item_name").getAsString()) ? 0
			: page.indexOf('(') < 0 ? 1 : 2;
	}

	private static String pageOf(JsonObject row)
	{
		JsonElement pg = row.get("page_name");
		return pg != null && !pg.isJsonNull()
			? pg.getAsString() : row.get("item_name").getAsString();
	}

	/** First decimal ID in the row's item_id array; the bucket also holds
	 * prefixed non-game IDs ("beta...") that never render as sprites. */
	private static Integer gameId(JsonObject row)
	{
		JsonElement ids = row.get("item_id");
		if (ids == null || !ids.isJsonArray())
		{
			return null;
		}
		for (JsonElement e : ids.getAsJsonArray())
		{
			try
			{
				return Integer.parseInt(e.getAsString());
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return null;
	}

	/** Non-obtainable infobox variants that share a real item's name. */
	private static boolean fakeItemPage(String page)
	{
		return page.contains("(unobtainable item")
			|| page.contains("(interface item")
			|| page.contains("(animation item")
			|| page.contains("(RuneScape 2 Beta");
	}

	private static Set<String> wordSet(String text)
	{
		Set<String> words = new HashSet<>();
		for (String line : text.split("\n"))
		{
			if (!line.trim().isEmpty())
			{
				words.add(line.trim());
			}
		}
		return words;
	}

	private JsonObject bucket(String query) throws IOException
	{
		return getJson(WIKI_API + "?action=bucket&format=json&query=" + Http.enc(query));
	}

	private JsonObject getJson(String url) throws IOException
	{
		pace();
		return http.getJson(url);
	}

	private String getText(String url) throws IOException
	{
		pace();
		return http.getText(url);
	}

	private void pace() throws IOException
	{
		if (paceMs <= 0)
		{
			return;
		}
		try
		{
			Thread.sleep(paceMs);
		}
		catch (InterruptedException e)
		{
			throw new IOException("interrupted while pacing", e);
		}
	}
}
