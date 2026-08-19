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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import okhttp3.OkHttpClient;

/**
 * Compiles the bulk vocabularies the plugin needs (every item, monster,
 * place, the GE catalogue, the English wordlist) and writes them as gzipped
 * snapshots for publishing to the repo's vocab-data branch.
 *
 * This is the ONLY code that runs the wiki's expensive bulk queries, and it
 * runs once a week in our CI (.github/workflows/vocab-snapshot.yml) -- never
 * on a user's machine. Fifty thousand installs re-deriving the same 30k-row
 * item index against the wiki's database-backed bucket API is the load
 * pattern API operators rightly resent; one job publishing to a CDN is not.
 *
 * Every dataset has a minimum-size threshold. A wiki API change that guts a
 * dataset fails the job loudly and leaves the previous snapshot published:
 * we would rather serve week-old vocab than an empty one.
 */
public final class VocabSnapshotTool
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final String PRICES_API = "https://prices.runescape.wiki/api";
	private static final String WORDLIST_URL =
		"https://raw.githubusercontent.com/first20hours/google-10000-english/master/google-10000-english.txt";

	private final Http http;
	private final Gson gson;

	private VocabSnapshotTool(Http http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	public static void main(String[] args) throws Exception
	{
		File outDir = new File(args.length > 0 ? args[0] : "build/vocab");
		outDir.mkdirs();
		Gson gson = new Gson();
		VocabSnapshotTool tool = new VocabSnapshotTool(new Http(new OkHttpClient(), gson), gson);

		// Thresholds are ~85% of the live counts measured 2026-08-16
		// (mapping 4652, monsters 1617, items 11432, locations 895,
		// wordlist 10000; strategy subpages 128 measured 2026-08-20). These
		// datasets only ever grow with game updates; a dip below means the
		// query broke, not the game shrank.
		tool.write(outDir, "ge_mapping.json", tool.geMapping(), 4000, "GE mapping entries");
		Set<String> monsters = tool.monsterNameSet();
		tool.write(outDir, "monsters_v2.json",
			new Sized(gson.toJson(monsters), monsters.size()), 1400, "monster names");
		tool.write(outDir, "strategies.json", tool.strategiesIndex(monsters), 105, "strategy subpages");
		tool.write(outDir, "items.json", tool.itemIndex(), 10000, "item index rows");
		tool.write(outDir, "locations-v2.json", tool.locationIndex(), 750, "location points");
		tool.write(outDir, "english_10k.txt", tool.wordlist(), 9000, "wordlist lines");

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

	private Sized geMapping() throws IOException
	{
		String json = http.getText(PRICES_API + "/v2/osrs/mapping");
		JsonArray parsed = gson.fromJson(json, JsonArray.class);
		return new Sized(json, parsed.size());
	}

	private Sized wordlist() throws IOException
	{
		String text = http.getText(WORDLIST_URL);
		return new Sized(text, text.split("\n").length);
	}

	/** Prefer page_name over the infobox name: it is the exact wiki title,
	 * so strategy subpages and dropsline page_name queries match without
	 * casing surprises. Paginated defensively: the bucket fits in one page
	 * today (~1.6k unique names), but versioned monsters multiply rows and
	 * nothing warns when a dataset outgrows a single request. */
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

	/**
	 * Every /Strategies guide subpage that exists, from two sweeps run
	 * weekly on the CI runner (never on clients). Strategies hang off far
	 * more than monsters -- raids, minigames, activities (Tombs of
	 * Amascut, Wintertodt, Inferno) -- so a title search finds every REAL
	 * page with the suffix (~3 paginated requests). Search skips redirect
	 * titles, which still fetch fine (Dust devil/Strategies redirects to
	 * its guide), so monster-derived titles are additionally batch
	 * existence-checked, 50 per request (~35 requests). Clients use the
	 * union to skip guaranteed-404 strategy fetches and to advertise
	 * existing guide pages at zero request cost.
	 */
	private Sized strategiesIndex(Set<String> monsterNames) throws IOException
	{
		Set<String> exists = new TreeSet<>();
		Integer offset = 0;
		while (offset != null)
		{
			JsonObject r = http.getJson(WIKI_API + "?action=query&list=search&format=json"
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

	/** Adds the titles in the batch that exist on the wiki to out. */
	private void addExisting(List<String> titles, Set<String> out) throws IOException
	{
		JsonObject r = http.getJson(WIKI_API + "?action=query&format=json&titles="
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

	/** Every item as {item name (per version), canonical page} from the item
	 * infoboxes; versioned names map to their shared page, removed content
	 * is excluded. See WikiApi.allItemNames for how clients use it. */
	private Sized itemIndex() throws IOException
	{
		List<String[]> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int offset = 0; offset < 100_000; offset += 5000)
		{
			JsonObject page = bucket("bucket('infobox_item')"
				+ ".select('page_name','item_name','removal_date')"
				+ ".limit(5000).offset(" + offset + ").run()");
			JsonArray rows = page.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				break;
			}
			for (JsonElement e : rows)
			{
				JsonObject row = e.getAsJsonObject();
				JsonElement name = row.get("item_name");
				JsonElement removed = row.get("removal_date");
				if (name == null || name.isJsonNull()
					|| (removed != null && !removed.isJsonNull()))
				{
					continue;
				}
				JsonElement pg = row.get("page_name");
				String pageName = pg != null && !pg.isJsonNull()
					? pg.getAsString() : name.getAsString();
				if (seen.add(name.getAsString()))
				{
					out.add(new String[]{name.getAsString(), pageName});
				}
			}
			if (rows.size() < 5000)
			{
				break;
			}
		}
		return new Sized(gson.toJson(out), out.size());
	}

	/** Named places with world coordinates: pages with a location infobox
	 * (what counts as a place) joined to the map bucket (where each page's
	 * map is centered), dungeons tagged as entrances. */
	private Sized locationIndex() throws IOException
	{
		Set<String> places = new HashSet<>();
		for (int offset = 0; offset < 100_000; offset += 5000)
		{
			JsonObject r = bucket("bucket('infobox_location').select('page_name')"
				+ ".limit(5000).offset(" + offset + ").run()");
			JsonArray rows = r.getAsJsonArray("bucket");
			if (rows == null || rows.size() == 0)
			{
				break;
			}
			for (JsonElement row : rows)
			{
				JsonElement name = row.getAsJsonObject().get("page_name");
				if (name != null && !name.isJsonNull())
				{
					places.add(name.getAsString());
				}
			}
			if (rows.size() < 5000)
			{
				break;
			}
		}
		Set<String> dungeons = categoryMembers("Dungeons");

		List<WikiApi.NamedPoint> points = new ArrayList<>();
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
					WikiApi.NamedPoint p = new WikiApi.NamedPoint();
					p.name = name;
					p.x = (int) opts.get("x").getAsDouble();
					p.y = (int) opts.get("y").getAsDouble();
					p.plane = opts.has("plane") ? opts.get("plane").getAsInt() : 0;
					p.entrance = dungeons.contains(name);
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
		return new Sized(gson.toJson(points), points.size());
	}

	/** All page titles in a wiki category, following pagination. */
	private Set<String> categoryMembers(String category) throws IOException
	{
		Set<String> titles = new HashSet<>();
		String cont = null;
		do
		{
			JsonObject r = http.getJson(WIKI_API + "?action=query&list=categorymembers&format=json"
				+ "&cmtitle=" + Http.enc("Category:" + category) + "&cmlimit=500"
				+ (cont != null ? "&cmcontinue=" + Http.enc(cont) : ""));
			for (JsonElement e : r.getAsJsonObject("query").getAsJsonArray("categorymembers"))
			{
				titles.add(e.getAsJsonObject().get("title").getAsString());
			}
			cont = r.has("continue")
				? r.getAsJsonObject("continue").get("cmcontinue").getAsString() : null;
		} while (cont != null);
		return titles;
	}

	private JsonObject bucket(String query) throws IOException
	{
		return http.getJson(WIKI_API + "?action=bucket&format=json&query=" + Http.enc(query));
	}
}
