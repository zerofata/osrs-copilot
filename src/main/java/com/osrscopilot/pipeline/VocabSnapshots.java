package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Bulk vocabularies served from published snapshots: a weekly CI job
 * (VocabSnapshotTool) runs the wiki's bulk queries once; clients download
 * the gzipped result with a 7-day disk cache and stale-beats-broken
 * fallback.
 */
@Slf4j
class VocabSnapshots
{
	/** Deliberately no fallback to building from the wiki: a broken
	 * snapshot pipeline must not shift bulk-query load onto the wiki. */
	private static final String SNAPSHOT_BASE =
		"https://raw.githubusercontent.com/zerofata/osrs-copilot/vocab-data/";

	/** Matches the weekly publish cadence. */
	private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

	private final Http http;
	private final Gson gson;
	private final File cacheDir;

	private Set<String> monsterNames;
	private Set<String> englishWords;
	private Set<String> commonEnglishWords;
	private List<ItemDescriptor> itemCatalog;
	private Set<String> strategiesPages;
	private Set<String> slayerTaskPages;

	VocabSnapshots(Http http, Gson gson, File cacheDir)
	{
		this.http = http;
		this.gson = gson;
		this.cacheDir = cacheDir;
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

	/** Exact titles of every {Monster}/Strategies subpage. Callers must
	 * treat IOException as "index unknown" and keep blind-fetch behavior,
	 * never as "page absent". */
	synchronized Set<String> strategiesPages() throws IOException
	{
		if (strategiesPages == null)
		{
			strategiesPages = gson.fromJson(snapshot("strategies.json"),
				new TypeToken<Set<String>>() { }.getType());
		}
		return strategiesPages;
	}

	/** Exact titles of every "Slayer task/..." guide subpage, redirect
	 * aliases included. IOException means "index unknown", never "page
	 * absent". */
	synchronized Set<String> slayerTaskPages() throws IOException
	{
		if (slayerTaskPages == null)
		{
			slayerTaskPages = gson.fromJson(snapshot("slayer_tasks.json"),
				new TypeToken<Set<String>>() { }.getType());
		}
		return slayerTaskPages;
	}

	/** The one item catalogue the resolver, ownership pass, and UI
	 * decorator share. The snapshot job canonicalizes it: removed content,
	 * fake pages, and bare dictionary-word untradeables are already gone,
	 * duplicate display names resolved, game IDs attached where known. */
	synchronized List<ItemDescriptor> itemCatalog() throws IOException
	{
		if (itemCatalog == null)
		{
			itemCatalog = gson.fromJson(snapshot("items_v2.json"),
				new TypeToken<List<ItemDescriptor>>() { }.getType());
		}
		return itemCatalog;
	}

	/** 10k most common English words, frequency-ordered. Empty on fetch
	 * failure. */
	synchronized Set<String> englishWords()
	{
		loadWordlist();
		return englishWords;
	}

	/** The high-frequency band of the wordlist; hostile redirects come
	 * from here ("up", "want"), genuine game words sit below it. */
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

	/** Hostile redirect words top out near rank 305; "staff" (511), the
	 * one game word inside the band, resolves through the vocabulary
	 * pass instead. */
	private static final int COMMON_ENGLISH_BAND = 1000;

	/** Fresh disk copy, else download, else stale disk copy (stale beats
	 * broken). */
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
