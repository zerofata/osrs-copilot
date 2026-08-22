package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Bulk vocabularies (every item, monster, place...), served from published
 * snapshots. They are identical for every install, so no client may compute
 * them: a scheduled job in our repo (VocabSnapshotTool, run weekly by CI)
 * does the wiki's expensive bulk queries ONCE and publishes gzipped
 * snapshots; clients only ever download the published result from GitHub's
 * CDN, with a 7-day disk cache and stale-beats-broken fallback.
 */
@Slf4j
class VocabSnapshots
{
	/**
	 * There is deliberately NO fallback to building from the wiki: if our
	 * snapshot pipeline breaks, our resolver degrades and the failure is
	 * ours to notice -- the wiki never absorbs it.
	 */
	private static final String SNAPSHOT_BASE =
		"https://raw.githubusercontent.com/zerofata/osrs-copilot/vocab-data/";

	/** Snapshots re-download after this age, matching the weekly publish
	 * cadence, so game/wiki changes flow in without a code update. */
	private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

	private final Http http;
	private final Gson gson;
	private final File cacheDir;

	private List<Map<String, Object>> geMapping;
	private Set<String> monsterNames;
	private Set<String> englishWords;
	private Set<String> commonEnglishWords;
	private List<WikiApi.NamedPoint> locationIndex;
	private List<String[]> itemNameIndex;
	private Map<String, Integer> itemIdsByName;
	private Set<String> strategiesPages;
	private Set<String> slayerTaskPages;

	VocabSnapshots(Http http, Gson gson, File cacheDir)
	{
		this.http = http;
		this.gson = gson;
		this.cacheDir = cacheDir;
	}

	synchronized List<Map<String, Object>> geMapping() throws IOException
	{
		if (geMapping == null)
		{
			geMapping = gson.fromJson(snapshot("ge_mapping.json"),
				new TypeToken<List<Map<String, Object>>>() { }.getType());
		}
		return geMapping;
	}

	/** Lowercase tradeable name to item ID, from the GE catalogue. The UI
	 * uses IDs to render item sprites from the client's own game cache. */
	synchronized Map<String, Integer> itemIdsByName() throws IOException
	{
		if (itemIdsByName == null)
		{
			Map<String, Integer> out = new HashMap<>();
			for (Map<String, Object> it : geMapping())
			{
				String name = (String) it.get("name");
				Object id = it.get("id");
				if (name != null && id instanceof Number)
				{
					out.putIfAbsent(name.toLowerCase(Locale.ROOT), ((Number) id).intValue());
				}
			}
			itemIdsByName = out;
		}
		return itemIdsByName;
	}

	synchronized Set<String> monsterNames() throws IOException
	{
		if (monsterNames == null)
		{
			monsterNames = gson.fromJson(snapshot("monsters_v2.json"),
				new TypeToken<Set<String>>() { }.getType());
		}
		return monsterNames;
	}

	/**
	 * Exact wiki titles of every {Monster}/Strategies subpage that exists,
	 * from the weekly snapshot's batch existence check. Lets the prefetcher
	 * skip guaranteed-404 strategy fetches and advertise guide pages without
	 * any live traffic. Callers must treat IOException as "index unknown"
	 * and fall back to blind-fetch behavior, never as "page absent".
	 */
	synchronized Set<String> strategiesPages() throws IOException
	{
		if (strategiesPages == null)
		{
			strategiesPages = gson.fromJson(snapshot("strategies.json"),
				new TypeToken<Set<String>>() { }.getType());
		}
		return strategiesPages;
	}

	/**
	 * Exact wiki titles of every "Slayer task/..." guide subpage, redirect
	 * aliases included, from the weekly snapshot's prefix listing. Lets the
	 * prefetcher fetch a task guide only when one exists, with zero live
	 * traffic. Callers must treat IOException as "index unknown", never as
	 * "page absent".
	 */
	synchronized Set<String> slayerTaskPages() throws IOException
	{
		if (slayerTaskPages == null)
		{
			slayerTaskPages = gson.fromJson(snapshot("slayer_tasks.json"),
				new TypeToken<Set<String>>() { }.getType());
		}
		return slayerTaskPages;
	}

	/**
	 * Every item in the game as {item name (per version), canonical page},
	 * from the wiki's item infoboxes. The GE catalogue only covers
	 * tradeables; this closes the gap (fire capes, void, quest items) for
	 * the resolver and the UI decorator. Versioned names ("Fire cape (l)")
	 * each map to their shared page. Removed content is excluded.
	 */
	synchronized List<String[]> allItemNames() throws IOException
	{
		if (itemNameIndex == null)
		{
			itemNameIndex = gson.fromJson(snapshot("items.json"),
				new TypeToken<List<String[]>>() { }.getType());
		}
		return itemNameIndex;
	}

	/**
	 * The one item-name list both the resolver and the UI decorator use:
	 * the GE catalogue (authoritative for tradeables), extended with
	 * untradeable names from the item infobox index. Infobox entries whose
	 * name is a single common English word are excluded -- they are obscure
	 * quest junk and interface pseudo-items ("Diary (Witch's House)",
	 * "Prayer (interface item)", "Key", "Note") and claiming bare
	 * dictionary words breaks real references ("Varrock diary", "prayer
	 * level"). Same principle as the resolver's desperation rule.
	 *
	 * Entries whose canonical page the wiki marks as a non-world sprite
	 * (interface, unobtainable, animation, beta-only) are excluded
	 * outright: a player can never mean them, and their names are real
	 * speech ("Dart", "Torch", "Magic carpet") -- claiming a mention
	 * locally blocks the redirect pass from resolving it to the real thing
	 * ("addy darts" once resolved to Dart (unobtainable item) instead of
	 * the Addy darts redirect). Matched anywhere in the disambiguator, not
	 * just as a suffix: "Torch (animation item, Sea Slug)".
	 */
	synchronized List<String[]> knownItemNames() throws IOException
	{
		List<String[]> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Map<String, Object> it : geMapping())
		{
			String name = (String) it.get("name");
			if (name != null && seen.add(name.toLowerCase(Locale.ROOT)))
			{
				out.add(new String[]{name, name});
			}
		}
		try
		{
			Set<String> english = englishWords();
			for (String[] it : allItemNames())
			{
				String name = it[0];
				if (name.indexOf(' ') < 0 && english.contains(name.toLowerCase(Locale.ROOT)))
				{
					continue;
				}
				if (fakeItemPage(it[1]))
				{
					continue;
				}
				if (seen.add(name.toLowerCase(Locale.ROOT)))
				{
					out.add(it);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("item infobox index unavailable; untradeables resolve as pages only", e);
		}
		return out;
	}

	private static boolean fakeItemPage(String page)
	{
		return page.contains("(unobtainable item")
			|| page.contains("(interface item")
			|| page.contains("(animation item")
			|| page.contains("(RuneScape 2 Beta");
	}

	/** 10k most common English words; used by the resolver to spot slang
	 * (OSRS abbreviations are almost never dictionary words). Empty set on
	 * fetch failure -- the resolver degrades gracefully. */
	synchronized Set<String> englishWords()
	{
		loadWordlist();
		return englishWords;
	}

	/** The high-frequency band of the wordlist (it is frequency-ordered,
	 * most common first). Every hostile redirect observed in live sessions
	 * came from this band ("up" is rank 54, "want" 254, "game" 305), while
	 * genuine game-flavoured English sits far below it ("bow" 6335, "cave"
	 * 7512) or is absent entirely ("whip", "fury"). */
	synchronized Set<String> commonEnglishWords()
	{
		loadWordlist();
		return commonEnglishWords;
	}

	private void loadWordlist()
	{
		if (englishWords != null)
		{
			return;
		}
		englishWords = new HashSet<>();
		commonEnglishWords = new HashSet<>();
		try
		{
			for (String w : snapshot("english_10k.txt").split("\n"))
			{
				if (!w.trim().isEmpty())
				{
					englishWords.add(w.trim());
					if (englishWords.size() <= COMMON_ENGLISH_BAND)
					{
						commonEnglishWords.add(w.trim());
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Wordlist unavailable", e);
		}
	}

	/** Rank cutoff for the common band: observed hostiles top out at rank
	 * 305, so 1,000 gives 3x margin. The only game-adjacent word inside the
	 * band is "staff" (511), which is a real item and resolves through the
	 * vocabulary pass instead. */
	private static final int COMMON_ENGLISH_BAND = 1000;

	/**
	 * Named places with world coordinates, joined from the wiki's location
	 * infoboxes and map bucket by the snapshot job. Entirely wiki-maintained
	 * -- new areas appear on the next published snapshot, no code changes.
	 */
	synchronized List<WikiApi.NamedPoint> locationIndex() throws IOException
	{
		if (locationIndex == null)
		{
			locationIndex = gson.fromJson(snapshot("locations-v2.json"),
				new TypeToken<List<WikiApi.NamedPoint>>() { }.getType());
		}
		return locationIndex;
	}

	/** Nearest named places to a world point, closest first. Empty on
	 * index failure -- callers fall back to raw coordinates. */
	List<WikiApi.NamedPoint> nearestPlaces(int x, int y, int count)
	{
		try
		{
			List<WikiApi.NamedPoint> index = locationIndex();
			List<WikiApi.NamedPoint> sorted = new ArrayList<>(index);
			sorted.sort((a, b) -> Long.compare(
				WikiApi.distSq(a, x, y), WikiApi.distSq(b, x, y)));
			return sorted.subList(0, Math.min(count, sorted.size()));
		}
		catch (Exception e)
		{
			log.warn("location index unavailable", e);
			return new ArrayList<>();
		}
	}

	/**
	 * A vocabulary snapshot: fresh disk copy, else download from our
	 * published vocab-data branch, else stale disk copy (stale beats
	 * broken). Building the dataset from the wiki is deliberately not in
	 * this chain -- see SNAPSHOT_BASE.
	 */
	private String snapshot(String filename) throws IOException
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
			String content = gunzip(http.getBytes(SNAPSHOT_BASE + filename + ".gz"));
			cacheDir.mkdirs();
			Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
			return content;
		}
		catch (IOException e)
		{
			// Refresh failed but a stale copy exists: stale beats broken.
			if (f.exists())
			{
				log.warn("snapshot refresh failed for {}, using stale copy", filename, e);
				return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			}
			throw e;
		}
	}

	private static String gunzip(byte[] compressed) throws IOException
	{
		try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
			new java.io.ByteArrayInputStream(compressed)))
		{
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
			{
				out.write(buf, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
