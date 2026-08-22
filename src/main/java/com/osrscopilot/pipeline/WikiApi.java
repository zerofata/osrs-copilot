package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OSRS Wiki + Grand Exchange API access: the facade over three
 * single-purpose collaborators, so callers keep one dependency.
 *
 * - {@link VocabSnapshots}: bulk vocabularies from published snapshots
 *   (items, monsters, locations, GE mapping, wordlist), 7-day disk cache.
 * - {@link WikiContent}: live page content, search, title resolution, and
 *   the TTL'd LRU over wiki GETs.
 * - {@link WikiLookups}: structured game data from the wiki's buckets and
 *   the prices API (drops, combat profiles, equipment stats, quests, GE).
 */
public class WikiApi
{
	private final VocabSnapshots vocab;
	private final WikiContent content;
	private final WikiLookups lookups;

	/** A named place on the world map, from the wiki's live map data. */
	public static class NamedPoint
	{
		public String name;
		public int x;
		public int y;
		public int plane;
		/** True for dungeons: their marker on the surface map is the
		 * entrance, not the place itself -- a surface player standing on
		 * it is NEXT TO the dungeon, never in it. */
		public boolean entrance;
	}

	public WikiApi(Http http, Gson gson, File cacheDir)
	{
		this.vocab = new VocabSnapshots(http, gson, cacheDir);
		this.content = new WikiContent(http);
		this.lookups = new WikiLookups(http, gson, content, vocab);
	}

	static long distSq(NamedPoint p, int x, int y)
	{
		long dx = p.x - x;
		long dy = p.y - y;
		return dx * dx + dy * dy;
	}

	// ---- vocabularies (VocabSnapshots) --------------------------------

	List<Map<String, Object>> geMapping() throws IOException
	{
		return vocab.geMapping();
	}

	Set<String> monsterNames() throws IOException
	{
		return vocab.monsterNames();
	}

	Set<String> strategiesPages() throws IOException
	{
		return vocab.strategiesPages();
	}

	Set<String> slayerTaskPages() throws IOException
	{
		return vocab.slayerTaskPages();
	}

	public List<String[]> allItemNames() throws IOException
	{
		return vocab.allItemNames();
	}

	public List<String[]> knownItemNames() throws IOException
	{
		return vocab.knownItemNames();
	}

	public Map<String, Integer> itemIdsByName() throws IOException
	{
		return vocab.itemIdsByName();
	}

	Set<String> englishWords()
	{
		return vocab.englishWords();
	}

	Set<String> commonEnglishWords()
	{
		return vocab.commonEnglishWords();
	}

	List<NamedPoint> locationIndex() throws IOException
	{
		return vocab.locationIndex();
	}

	List<NamedPoint> nearestPlaces(int x, int y, int count)
	{
		return vocab.nearestPlaces(x, y, count);
	}

	// ---- live content (WikiContent) -----------------------------------

	JsonObject wikiQuery(String params) throws IOException
	{
		return content.wikiQuery(params);
	}

	List<Map<String, Object>> search(String query)
	{
		return content.search(query);
	}

	Map<String, String> resolveTitles(Collection<String> names) throws IOException
	{
		return content.resolveTitles(names);
	}

	String page(String title)
	{
		return content.page(title);
	}

	String page(String title, int charLimit)
	{
		return content.page(title, charLimit);
	}

	String sectionByHeading(String title, Pattern headingPattern, int charLimit)
	{
		return content.sectionByHeading(title, headingPattern, charLimit);
	}

	List<String> topSections(String title)
	{
		return content.topSections(title);
	}

	String wikitext(String title, int charLimit)
	{
		return content.wikitext(title, charLimit);
	}

	// ---- structured lookups (WikiLookups) ------------------------------

	Map<String, Object> itemSources(String itemName)
	{
		return lookups.itemSources(itemName);
	}

	Map<String, Object> monsterDrops(String name)
	{
		return lookups.monsterDrops(name);
	}

	Map<String, Object> monsterInfo(String name)
	{
		return lookups.monsterInfo(name);
	}

	Map<String, Object> itemStats(String name)
	{
		return lookups.itemStats(name);
	}

	Map<String, Object> questInfo(String name)
	{
		return lookups.questInfo(name);
	}

	Map<String, Object> gePrice(String itemName)
	{
		return lookups.gePrice(itemName);
	}
}
