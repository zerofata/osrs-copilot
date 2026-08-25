package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrscopilot.area.Areas;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;
import okhttp3.OkHttpClient;

/**
 * Audits area resolution against the wiki. Every page with a location
 * infobox is fetched and its {{Map}} coordinates extracted from the
 * wikitext; each point is then resolved through {@link Areas}, the same
 * path the prompt uses. Reports places that resolve to nothing (gaps --
 * typically content newer than the vendored table, listed newest release
 * first) and places that resolve to a name sharing no word with the page
 * title (possible misattributions; most are benign, e.g. a shop resolving
 * to its city).
 *
 * Run: gradlew areaScan
 */
public final class GameAreaScan
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final Pattern RELEASE = Pattern.compile("\\|\\s*release\\s*=([^\\n]*)");
	private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
	private static final Pattern NUM_PAIR = Pattern.compile("^(\\d{3,5})\\s*,\\s*(\\d{3,5})$");

	private final Http http;
	private final Gson gson;

	private GameAreaScan(Http http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	private static final class Place
	{
		final String title;
		final int x;
		final int y;
		final int plane;
		final int releaseYear;

		Place(String title, int x, int y, int plane, int releaseYear)
		{
			this.title = title;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.releaseYear = releaseYear;
		}
	}

	public static void main(String[] args) throws Exception
	{
		Gson gson = new Gson();
		GameAreaScan scan = new GameAreaScan(new Http(new OkHttpClient(), gson), gson);

		List<String> titles = scan.locationPages();
		System.out.println("location-infobox pages: " + titles.size());

		List<Place> places = new ArrayList<>();
		int noCoords = 0;
		for (int i = 0; i < titles.size(); i += 50)
		{
			List<String> batch = titles.subList(i, Math.min(i + 50, titles.size()));
			for (String[] page : scan.wikitexts(batch))
			{
				Place p = parsePlace(page[0], page[1]);
				if (p != null)
				{
					places.add(p);
				}
				else
				{
					noCoords++;
				}
			}
			Thread.sleep(1000);
		}
		System.out.println("with map coordinates:   " + places.size()
			+ "   (no coordinates in wikitext: " + noCoords + ")");

		List<Place> unresolved = new ArrayList<>();
		List<String> mismatches = new ArrayList<>();
		for (Place p : places)
		{
			String name = Areas.resolve(new WorldPoint(p.x, p.y, p.plane));
			if (name == null)
			{
				unresolved.add(p);
			}
			else if (!sharesWord(p.title, name))
			{
				mismatches.add(String.format("%-42s (%d,%d,%d) -> %s",
					p.title, p.x, p.y, p.plane, name));
			}
		}

		System.out.printf("%nresolved: %d / %d (%.0f%%)%n",
			places.size() - unresolved.size(), places.size(),
			100.0 * (places.size() - unresolved.size()) / places.size());

		System.out.println("\n== UNRESOLVED (newest release first) "
			+ "-- gaps in the area table ==");
		unresolved.sort(Comparator.comparingInt((Place p) -> -p.releaseYear)
			.thenComparing(p -> p.title));
		for (Place p : unresolved)
		{
			System.out.printf("%s  %-42s (%d,%d,%d)%n",
				p.releaseYear > 0 ? String.valueOf(p.releaseYear) : "????",
				p.title, p.x, p.y, p.plane);
		}

		System.out.println("\n== NAME MISMATCHES -- resolved, but to an "
			+ "unrelated-sounding area (review manually) ==");
		mismatches.forEach(System.out::println);
	}

	/** All page titles carrying a location infobox, via the wiki's bucket API. */
	private List<String> locationPages() throws IOException
	{
		Set<String> titles = new TreeSet<>();
		for (int offset = 0; offset < 100_000; offset += 5000)
		{
			JsonObject r = http.getJson(WIKI_API + "?action=bucket&format=json&query="
				+ Http.enc("bucket('infobox_location').select('page_name')"
				+ ".limit(5000).offset(" + offset + ").run()"));
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
					titles.add(name.getAsString());
				}
			}
			if (rows.size() < 5000)
			{
				break;
			}
		}
		return new ArrayList<>(titles);
	}

	/** Fetches raw wikitext for up to 50 titles; returns {title, wikitext}. */
	private List<String[]> wikitexts(List<String> titles) throws IOException
	{
		JsonObject r = http.getJson(WIKI_API + "?action=query&prop=revisions"
			+ "&rvprop=content&rvslots=main&format=json&titles="
			+ Http.enc(String.join("|", titles)));
		List<String[]> out = new ArrayList<>();
		JsonObject pages = r.getAsJsonObject("query").getAsJsonObject("pages");
		for (String pageId : pages.keySet())
		{
			JsonObject p = pages.getAsJsonObject(pageId);
			if (p.has("revisions"))
			{
				String text = p.getAsJsonArray("revisions").get(0).getAsJsonObject()
					.getAsJsonObject("slots").getAsJsonObject("main").get("*").getAsString();
				out.add(new String[]{p.get("title").getAsString(), text});
			}
		}
		return out;
	}

	/** Extracts a representative world point from the page's first usable
	 * {{Map}} call: named x=/y= if present, else the centroid of positional
	 * "x,y" pairs (polygon and pin lists). */
	private static Place parsePlace(String title, String text)
	{
		int year = 0;
		Matcher rel = RELEASE.matcher(text);
		if (rel.find())
		{
			Matcher y = YEAR.matcher(rel.group(1));
			if (y.find())
			{
				year = Integer.parseInt(y.group());
			}
		}

		int from = 0;
		while (true)
		{
			int start = text.indexOf("{{Map", from);
			if (start < 0)
			{
				return null;
			}
			String body = balancedBody(text, start);
			from = start + 5;
			if (body == null)
			{
				continue;
			}
			Integer x = null;
			Integer y = null;
			int plane = 0;
			List<int[]> pairs = new ArrayList<>();
			for (String part : splitTopLevel(body))
			{
				String arg = part.trim();
				int eq = arg.indexOf('=');
				if (eq >= 0)
				{
					String key = arg.substring(0, eq).trim().toLowerCase(Locale.ROOT);
					String val = arg.substring(eq + 1).trim();
					try
					{
						if (key.equals("x"))
						{
							x = (int) Double.parseDouble(val);
						}
						else if (key.equals("y"))
						{
							y = (int) Double.parseDouble(val);
						}
						else if (key.equals("plane"))
						{
							plane = (int) Double.parseDouble(val);
						}
					}
					catch (NumberFormatException ignored)
					{
					}
				}
				else
				{
					Matcher m = NUM_PAIR.matcher(arg);
					if (m.matches())
					{
						pairs.add(new int[]{Integer.parseInt(m.group(1)),
							Integer.parseInt(m.group(2))});
					}
				}
			}
			if (x == null && !pairs.isEmpty())
			{
				long sx = 0;
				long sy = 0;
				for (int[] pr : pairs)
				{
					sx += pr[0];
					sy += pr[1];
				}
				x = (int) (sx / pairs.size());
				y = (int) (sy / pairs.size());
			}
			if (x != null && y != null)
			{
				return new Place(title, x, y, plane, year);
			}
		}
	}

	/** Template body between "{{Map" at start and its matching "}}", or
	 * null if braces never balance. */
	private static String balancedBody(String text, int start)
	{
		int depth = 0;
		for (int i = start; i < text.length() - 1; i++)
		{
			if (text.charAt(i) == '{' && text.charAt(i + 1) == '{')
			{
				depth++;
				i++;
			}
			else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}')
			{
				depth--;
				i++;
				if (depth == 0)
				{
					return text.substring(start + 5, i - 1);
				}
			}
		}
		return null;
	}

	/** Splits template arguments on '|' at brace depth zero. */
	private static List<String> splitTopLevel(String body)
	{
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int last = 0;
		for (int i = 0; i < body.length(); i++)
		{
			char c = body.charAt(i);
			if (c == '{' || c == '[')
			{
				depth++;
			}
			else if (c == '}' || c == ']')
			{
				depth--;
			}
			else if (c == '|' && depth == 0)
			{
				parts.add(body.substring(last, i));
				last = i + 1;
			}
		}
		parts.add(body.substring(last));
		return parts;
	}

	/** True if any normalized word appears in both names. */
	private static boolean sharesWord(String a, String b)
	{
		Set<String> wa = words(a);
		wa.retainAll(words(b));
		return !wa.isEmpty();
	}

	private static Set<String> words(String s)
	{
		Set<String> out = new HashSet<>();
		for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
		{
			if (w.length() > 2 && !w.equals("the") && !w.equals("and"))
			{
				out.add(w);
			}
		}
		return out;
	}
}
