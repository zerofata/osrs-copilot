package com.osrscopilot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Real game icons for decorated answers, sourced from the OSRS Wiki's image
 * files. Each icon downloads once (a few hundred bytes), persists to disk,
 * and is referenced by file URL from the HTML renderer -- so answers never
 * block on the network for an icon after first use, and offline sessions
 * keep every icon already seen.
 *
 * Fail-soft by contract: any miss returns null and the caller renders
 * without an icon. A decoration layer must never break an answer.
 */
@Slf4j
final class IconCache
{
	private static final String IMAGE_BASE = "https://oldschool.runescape.wiki/images/";
	/** Marks a filename that failed to fetch, so it is not retried this
	 * session (ConcurrentHashMap forbids null values). */
	private static final String MISS = "";

	private final File dir;
	private final OkHttpClient client;
	private final Map<String, String> resolved = new ConcurrentHashMap<>();

	IconCache(File dir, OkHttpClient client)
	{
		this.dir = dir;
		this.client = client;
		dir.mkdirs();
	}

	/** File URL for a wiki image (e.g. "Quest_point_icon.png"), downloading
	 * on first use. Null if unavailable; never throws. */
	String fileUrl(String wikiFile)
	{
		String cached = resolved.get(wikiFile);
		if (cached != null)
		{
			return cached.equals(MISS) ? null : cached;
		}
		String url = fetch(wikiFile);
		resolved.put(wikiFile, url == null ? MISS : url);
		return url;
	}

	/** The cached image as a local file, for Swing consumers that need an
	 * Image rather than an HTML URL. Null if unavailable; never throws. */
	File file(String wikiFile)
	{
		String url = fileUrl(wikiFile);
		if (url == null)
		{
			return null;
		}
		return new File(java.net.URI.create(url));
	}

	private String fetch(String wikiFile)
	{
		File f = new File(dir, wikiFile.replaceAll("[^A-Za-z0-9._-]", "_"));
		if (!f.exists())
		{
			Request request = new Request.Builder()
				.url(IMAGE_BASE + wikiFile.replace(" ", "_"))
				.header("User-Agent",
					"osrs-copilot RuneLite plugin (https://github.com/zerofata/osrs-copilot)")
				.build();
			try (Response response = client.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					log.debug("icon fetch failed for {}: HTTP {}", wikiFile, response.code());
					return null;
				}
				try (OutputStream out = new FileOutputStream(f))
				{
					out.write(response.body().bytes());
				}
			}
			catch (Exception e)
			{
				log.debug("icon fetch failed for {}", wikiFile, e);
				return null;
			}
		}
		return f.toURI().toString();
	}
}
