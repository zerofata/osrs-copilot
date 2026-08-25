package com.osrscopilot.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Live wiki content: search, title resolution, page text (plaintext extract
 * with wikitext fallback), sections by heading, and render-time query
 * templates. Owns the short-TTL LRU over wiki GETs, which all live queries
 * flow through.
 */
@Slf4j
class WikiContent
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";

	/** Extract-to-page-size ratio below which the plaintext extract has lost
	 * the page's substance to table stripping; see isHusk. */
	private static final double HUSK_RATIO = 0.3;

	private static final int PAGE_CHAR_LIMIT = 7000;
	private static final int WIKITEXT_CHAR_LIMIT = 12000;

	/** TTL for the wiki-GET cache. Not applied to the prices API (prices
	 * move) or snapshots (own 7-day disk cache). */
	private static final long CONTENT_CACHE_TTL_MS = 24 * 60 * 60 * 1000;
	private static final int CONTENT_CACHE_MAX_ENTRIES = 256;

	/** Standard wiki appendix sections; excluded from the TOC line and
	 * dropped first when over budget. */
	private static final Set<String> NOISE_SECTIONS = Set.of(
		"changes", "references", "trivia", "gallery", "see also", "external links",
		"official worlds", "music", "developers");

	private final Http http;

	/** URL -> {fetchedAtMs, response}, LRU-evicted. Guarded by itself. */
	private final Map<String, Object[]> contentCache =
		new LinkedHashMap<String, Object[]>(64, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest)
			{
				return size() > CONTENT_CACHE_MAX_ENTRIES;
			}
		};

	WikiContent(Http http)
	{
		this.http = http;
	}

	/** All wiki GETs go through here; see CONTENT_CACHE_TTL_MS. Responses
	 * are treated as read-only by every caller, so sharing them is safe. */
	private JsonObject cachedGet(String url) throws IOException
	{
		synchronized (contentCache)
		{
			Object[] hit = contentCache.get(url);
			if (hit != null && System.currentTimeMillis() - (long) hit[0] < CONTENT_CACHE_TTL_MS)
			{
				return (JsonObject) hit[1];
			}
		}
		JsonObject fresh = http.getJson(url);
		synchronized (contentCache)
		{
			contentCache.put(url, new Object[]{System.currentTimeMillis(), fresh});
		}
		return fresh;
	}

	/** Runs a bucket query. API-level errors throw rather than reading as
	 * empty: empty means the subject doesn't exist, and conflating the
	 * two hides upstream schema drift. */
	JsonObject bucket(String query) throws IOException
	{
		JsonObject r = cachedGet(WIKI_API + "?action=bucket&format=json&query=" + Http.enc(query));
		if (r != null && r.has("error"))
		{
			String message = r.get("error").isJsonPrimitive()
				? r.get("error").getAsString() : r.get("error").toString();
			log.warn("bucket query rejected: {} (query: {})", message, query);
			throw new IOException("bucket query rejected: " + message);
		}
		return r;
	}

	JsonObject wikiQuery(String params) throws IOException
	{
		return cachedGet(WIKI_API + "?action=query&format=json&" + params);
	}

	/** Search the wiki: [{title, snippet, headings}]. Headings let the
	 * follow-up be a targeted section fetch. */
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
				String title = h.get("title").getAsString();
				entry.put("title", title);
				entry.put("snippet", h.get("snippet").getAsString()
					.replace("<span class=\"searchmatch\">", "").replace("</span>", ""));
				if (out.size() < 3)
				{
					List<String> headings = topSections(title);
					if (!headings.isEmpty())
					{
						entry.put("headings", headings);
					}
				}
				out.add(entry);
			}
			return out;
		}
		catch (Exception e)
		{
			return List.of(Map.of("error", "search failed: " + e.getMessage()));
		}
	}

	/** Top-level headings, appendix noise excluded. Empty on failure:
	 * headings only decorate. */
	List<String> topSections(String title)
	{
		try
		{
			List<String> out = new ArrayList<>();
			for (JsonElement e : pageSections(title))
			{
				JsonObject s = e.getAsJsonObject();
				String line = s.get("line").getAsString();
				if (s.get("toclevel").getAsInt() != 1
					|| NOISE_SECTIONS.contains(line.toLowerCase(Locale.ROOT)))
				{
					continue;
				}
				out.add(line);
				if (out.size() >= 12)
				{
					break;
				}
			}
			return out;
		}
		catch (Exception e)
		{
			log.debug("sections fetch failed for {}", title, e);
			return List.of();
		}
	}

	/** Resolves each name to the wiki page it lands on, or null when no
	 * such page exists. Batched 50 per request (API limit). */
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
			hops.putAll(EntityResolver.fromToMap(query, "normalized"));
			hops.putAll(EntityResolver.fromToMap(query, "redirects"));
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

	/** Page content, truncated: plaintext extract, falling back to raw
	 * wikitext when the extract lost the page's substance. Null when the
	 * page doesn't exist. */
	String page(String title)
	{
		return page(title, 0);
	}

	/** Same, with a caller-chosen char budget (0 = defaults). */
	String page(String title, int charLimit)
	{
		try
		{
		// prop=info returns the wikitext size, so the extract can be
		// measured against its source in one request.
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
				return withQuerySections(title, text,
					charLimit > 0 ? charLimit : PAGE_CHAR_LIMIT);
			}
		}
		catch (Exception e)
		{
			log.debug("page fetch failed for {}", title, e);
		}
		return null;
	}

	/** Whether a plaintext extract lost the page's substance to table
	 * stripping. Table-gutted pages measure below the 0.3 threshold;
	 * prose pages sit at 0.39+. */
	private static boolean isHusk(String extract, int pageBytes)
	{
		return pageBytes > 0 && (double) extract.length() / pageBytes < HUSK_RATIO;
	}

	/** The wiki's own document structure for a page
	 * (action=parse&prop=sections), shared by the TOC and section fetches. */
	private JsonArray pageSections(String title) throws IOException
	{
		JsonObject r = cachedGet(WIKI_API + "?action=parse&prop=sections&format=json"
			+ "&redirects=1&page=" + Http.enc(title));
		return r.getAsJsonObject("parse").getAsJsonArray("sections");
	}

	/** Fetch one section of a page by heading; null when no section
	 * matches. */
	String sectionByHeading(String title, Pattern headingPattern, int charLimit)
	{
		try
		{
			for (JsonElement e : pageSections(title))
			{
				JsonObject s = e.getAsJsonObject();
				if (!headingPattern.matcher(s.get("line").getAsString()).find())
				{
					continue;
				}
				JsonObject sec = cachedGet(WIKI_API + "?action=parse&prop=wikitext&format=json"
					+ "&redirects=1&page=" + Http.enc(title) + "&section=" + s.get("index").getAsString());
				String text = sec.getAsJsonObject("parse").getAsJsonObject("wikitext")
					.get("*").getAsString();
				return truncate(scrubWikitext(text), charLimit);
			}
		}
		catch (Exception e)
		{
			log.debug("section fetch failed for {}", title, e);
		}
		return null;
	}

	/** Large tables often live on transcluded subpages ({{/Locations}});
	 * the raw wikitext only has the stub, so follow one level down. */
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
			return withQuerySections(title, scrubWikitext(text), charLimit);
		}
		catch (Exception e)
		{
			log.debug("wikitext fetch failed for {}", title, e);
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Render-time query sections
	// ------------------------------------------------------------------

	/** Sections whose whole body is a render-time query template are
	 * invisible to both extraction paths. "Used in recommended equipment"
	 * exists nowhere else, so it is rendered explicitly. */
	private static final String USED_IN_REC_EQUIP = "Used in recommended equipment";
	private static final Pattern REC_EQUIP_HUSK = Pattern.compile(
		"(\\{\\{Used in recommended equipment[^}]*\\}\\}|==+ *Used in recommended equipment *==+)");
	private static final int RENDERED_SECTION_CHAR_LIMIT = 2500;

	/** Top-level headings ("== X ==") in extract or wikitext form. */
	private static final Pattern TOP_HEADING =
		Pattern.compile("(?m)^== *([^=\\n]+?) *== *$");

	// ------------------------------------------------------------------
	// Wikitext scrubbing and section-aware budgeting
	// ------------------------------------------------------------------

	private static final Pattern GALLERY_BLOCK =
		Pattern.compile("(?is)<gallery[^>]*>.*?</gallery>");
	/** Cell prefix attributes that carry no content ("data-sort-value=..."). */
	private static final Pattern SORT_VALUE_ATTR =
		Pattern.compile("data-sort-value=\"[^\"\\n]*\" *\\| *");

	/** Removes wikitext with no semantic content: image/file links,
	 * gallery blocks, citation templates, sort-value cell attributes. */
	static String scrubWikitext(String text)
	{
		text = GALLERY_BLOCK.matcher(text).replaceAll("");
		text = stripBalanced(text, "[[file:", "[[", "]]");
		text = stripBalanced(text, "[[image:", "[[", "]]");
		text = stripBalanced(text, "{{cite", "{{", "}}");
		text = SORT_VALUE_ATTR.matcher(text).replaceAll("");
		return text;
	}

	/**
	 * Removes every occurrence of a balanced construct that starts with
	 * startToken (matched case-insensitively), tracking nesting: captions
	 * contain [[links]] and citations nest templates. An unclosed
	 * construct is left untouched.
	 */
	private static String stripBalanced(String text, String startToken, String open, String close)
	{
		String lower = text.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder(text.length());
		int pos = 0;
		while (true)
		{
			int start = lower.indexOf(startToken, pos);
			if (start < 0)
			{
				out.append(text, pos, text.length());
				return out.toString();
			}
			int depth = 1;
			int i = start + open.length();
			while (i < text.length() && depth > 0)
			{
				if (lower.startsWith(open, i))
				{
					depth++;
					i += open.length();
				}
				else if (lower.startsWith(close, i))
				{
					depth--;
					i += close.length();
				}
				else
				{
					i++;
				}
			}
			out.append(text, pos, start);
			pos = depth == 0 ? i : start + open.length();
			if (depth != 0)
			{
				out.append(text, start, pos);
			}
		}
	}

	/** Spends an over-budget page's char limit on whole sections: the lead
	 * always ships, noise sections drop first, the rest are admitted whole
	 * in page order, and a section bigger than half the budget is weighed
	 * last. Pages with no usable sections fall back to a position cut. */
	static String budgetBySections(String text, int limit)
	{
		if (text.length() <= limit)
		{
			return text;
		}
		List<Integer> starts = new ArrayList<>();
		List<String> headings = new ArrayList<>();
		Matcher m = TOP_HEADING.matcher(text);
		while (m.find())
		{
			starts.add(m.start());
			headings.add(m.group(1).trim().toLowerCase(Locale.ROOT));
		}
		if (starts.isEmpty())
		{
			return truncate(text, limit);
		}
		String lead = text.substring(0, starts.get(0));
		if (lead.length() >= limit)
		{
			return truncate(text, limit);
		}
		List<String> ordered = new ArrayList<>();
		List<String> deferred = new ArrayList<>();
		for (int i = 0; i < starts.size(); i++)
		{
			if (NOISE_SECTIONS.contains(headings.get(i)))
			{
				continue;
			}
			int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
			String section = text.substring(starts.get(i), end);
			// Admitted only into whatever budget the rest left over.
			(section.length() > limit / 2 ? deferred : ordered).add(section);
		}
		ordered.addAll(deferred);
		StringBuilder out = new StringBuilder(lead);
		int admitted = 0;
		for (String section : ordered)
		{
			if (out.length() + section.length() <= limit)
			{
				out.append(section);
				admitted++;
			}
		}
		// No section fit: the substance is one giant section, and a
		// position cut beats a bare lead.
		return admitted == 0 && !ordered.isEmpty() ? truncate(text, limit) : out.toString();
	}

	/** The page's TOC from the full pre-truncation text, so a reader of a
	 * truncated page still sees every section that exists. */
	static String tocLine(String text)
	{
		List<String> toc = new ArrayList<>();
		Matcher m = TOP_HEADING.matcher(text);
		while (m.find() && toc.size() < 15)
		{
			String heading = m.group(1).trim();
			if (!NOISE_SECTIONS.contains(heading.toLowerCase(Locale.ROOT))
				&& !toc.contains(heading))
			{
				toc.add(heading);
			}
		}
		return toc.size() >= 2 ? "[Sections: " + String.join("; ", toc) + "]\n\n" : "";
	}

	/** Truncate, then append any render-time query section rendered for
	 * real: the section sits at the tail of long pages where the budget
	 * would drop it. The in-place husk is removed. */
	private String withQuerySections(String title, String text, int charLimit)
	{
		boolean present = text.contains(USED_IN_REC_EQUIP);
		String out = tocLine(text) + budgetBySections(text, charLimit);
		if (!present)
		{
			return out;
		}
		String rendered = renderTemplate("{{" + USED_IN_REC_EQUIP + "|" + title + "}}");
		if (rendered == null || rendered.length() < 20)
		{
			return out;
		}
		out = REC_EQUIP_HUSK.matcher(out).replaceAll("");
		return out + "\n\n== " + USED_IN_REC_EQUIP + " ==\n"
			+ "(rank 1 = listed best-in-slot on that strategy page)\n"
			+ truncate(rendered, RENDERED_SECTION_CHAR_LIMIT);
	}

	/** Render one template invocation by itself and flatten the HTML. */
	private String renderTemplate(String wikitextCall)
	{
		try
		{
			JsonObject r = cachedGet(WIKI_API + "?action=parse&format=json&prop=text"
				+ "&contentmodel=wikitext&text=" + Http.enc(wikitextCall));
			return htmlToText(r.getAsJsonObject("parse").getAsJsonObject("text")
				.get("*").getAsString());
		}
		catch (Exception e)
		{
			log.debug("template render failed for {}", wikitextCall, e);
			return null;
		}
	}

	/** Row-preserving HTML flattening: block closers become line breaks,
	 * tags go, the entities that appear in game names are unescaped. */
	private static String htmlToText(String html)
	{
		String text = html
			.replaceAll("(?i)</(tr|li|p|h[1-6]|caption)>", "\n")
			.replaceAll("<[^>]+>", " ")
			.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
			.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
		StringBuilder out = new StringBuilder();
		for (String line : text.split("\n"))
		{
			String collapsed = line.replaceAll("\\s+", " ").trim();
			if (!collapsed.isEmpty())
			{
				out.append(collapsed).append('\n');
			}
		}
		return out.toString().trim();
	}

	private String rawWikitext(String title) throws IOException
	{
		JsonObject r = cachedGet(WIKI_API + "?action=parse&prop=wikitext&format=json"
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

	private static String truncate(String s, int limit)
	{
		return s.length() > limit ? s.substring(0, limit) : s;
	}
}
