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
 * OSRS Wiki + Grand Exchange API access: facade over {@link VocabSnapshots}
 * (bulk vocabularies), {@link WikiContent} (live content), and
 * {@link WikiLookups} (structured data).
 */
public class WikiApi
{
	private final VocabSnapshots vocab;
	private final WikiContent content;
	private final WikiLookups lookups;

	public WikiApi(Http http, Gson gson, File cacheDir)
	{
		this.vocab = new VocabSnapshots(http, gson, cacheDir);
		this.content = new WikiContent(http);
		this.lookups = new WikiLookups(http, gson, content, vocab);
	}

	// ---- vocabularies (VocabSnapshots) --------------------------------

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

	public List<ItemDescriptor> itemCatalog() throws IOException
	{
		return vocab.itemCatalog();
	}

	Set<String> englishWords()
	{
		return vocab.englishWords();
	}

	Set<String> commonEnglishWords()
	{
		return vocab.commonEnglishWords();
	}

	// ---- live content (WikiContent) -----------------------------------

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
