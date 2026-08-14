package com.osrscopilot.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Deterministic entity resolution for player questions.
 *
 * The game is a closed world: ~4,700 tradeable items, ~3,200 monsters,
 * ~200 quests, 24 skills. Resolution order:
 * 1. N-gram lookup against local vocabularies (longest match wins).
 * 2. Unresolved spans batched through the wiki redirect API -- the wiki
 *    community has curated 20 years of slang as redirects ("addy bars" ->
 *    Adamantite bar, "kbd" -> King Black Dragon).
 *
 * No LLM involved: never hallucinates, latency is one HTTP call at most.
 */
@Slf4j
public class EntityResolver
{
	private static final List<String> SKILLS = Arrays.asList(
		"attack", "strength", "defence", "ranged", "prayer", "magic", "runecraft",
		"construction", "hitpoints", "agility", "herblore", "thieving", "crafting",
		"fletching", "slayer", "hunter", "mining", "smithing", "fishing", "cooking",
		"firemaking", "woodcutting", "farming", "sailing");

	private static final Map<String, String> SKILL_ALIASES = Map.of(
		"defense", "defence", "rc", "runecraft", "wc", "woodcutting", "fm", "firemaking",
		"hp", "hitpoints", "con", "construction", "range", "ranged", "ranging", "ranged",
		"mage", "magic");

	/** Grammar/meta words used to judge whether a multi-word span is "mostly
	 * real words" and worth a redirect attempt. Single-token candidacy is
	 * decided by dictionary membership, not this list. */
	private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
		("a an the i my me you your it its this that these those is are was be been "
		+ "do does did can could should would will whats what's what how where when why which who "
		+ "best good better fast fastest quick quickest way ways get got make making need needs "
		+ "build building built nearest closest "
		+ "worth for with without and or not no yes of in on at to from into vs versus about "
		+ "more less most many much have has had if while during active right now current level "
		+ "levels xp exp experience quest quests boss monster gear setup strategy guide tips "
		+ "kill killing fight fighting drop drops dropped use using item items stuff thing things "
		+ "money gp gold profit hour hr afk safe easy hard next "
		+ "player players people normally usually give giving steps step finish finished "
		+ "complete completed start started solo group grouping").split(" ")));

	/** Function words only -- no domain nouns. STOPWORDS deliberately holds
	 * game words like "quest" and "gear" that do appear in page titles ("Cat
	 * quest" redirects to Gertrude's Cat), so it can't judge span edges. */
	private static final Set<String> GRAMMAR = new HashSet<>(Arrays.asList(
		("a an the this that these those my your our their its his her "
		+ "i me you it we they "
		+ "is are was were be been am do does did can could should would will might must "
		+ "if while during until when where what whats what's how why which who whose "
		+ "and or nor but not no yes vs versus "
		+ "of in on at to from into onto with without for by about than then as "
		// "nearest"/"closest" belong here, not just STOPWORDS: the wiki
		// redirects "Nearest bank" to its "Closest..." navigation page, so
		// the two-word span leaks through content counting. No real entity
		// name begins or ends on a locational superlative.
		+ "between above below over under across behind among upon after before near "
		+ "nearest closest there here also just really please any some").split(" ")));

	public static class Resolution
	{
		public final List<String> items = new ArrayList<>();
		public final List<String> monsters = new ArrayList<>();
		public final List<String> quests = new ArrayList<>();
		public final List<String> skills = new ArrayList<>();
		public final List<String> pages = new ArrayList<>();

		public boolean anyEntity()
		{
			return !items.isEmpty() || !monsters.isEmpty() || !quests.isEmpty() || !pages.isEmpty();
		}

		private List<String> byKind(String kind)
		{
			switch (kind)
			{
				case "items": return items;
				case "monsters": return monsters;
				case "quests": return quests;
				case "skills": return skills;
				default: return pages;
			}
		}
	}

	private final WikiApi wiki;
	private Map<String, String> itemVocab;
	private Map<String, String> monsterVocab;

	public EntityResolver(WikiApi wiki)
	{
		this.wiki = wiki;
	}

	/** First call fetches/loads vocabularies (then cached in memory + disk). */
	private synchronized void ensureVocabs() throws IOException
	{
		if (itemVocab == null)
		{
			// GE catalogue plus untradeables, junk-filtered at the source;
			// values are the canonical page, so versioned names ("Fire cape
			// (l)") retrieve their shared page.
			Map<String, String> items = new HashMap<>();
			for (String[] it : wiki.knownItemNames())
			{
				items.putIfAbsent(norm(it[0]), it[1]);
			}
			Map<String, String> monsters = new HashMap<>();
			for (String name : wiki.monsterNames())
			{
				monsters.put(norm(name), name);
			}
			itemVocab = items;
			monsterVocab = monsters;
		}
	}

	/** Resolve extra text (a name from game state, not from the player) and
	 * merge what it finds into an existing resolution. */
	public void resolveInto(String text, Collection<String> questNames, Resolution into)
		throws IOException
	{
		Resolution extra = resolve(text, questNames);
		for (String kind : new String[]{"items", "monsters", "quests", "skills", "pages"})
		{
			List<String> target = into.byKind(kind);
			for (String name : extra.byKind(kind))
			{
				if (!target.contains(name))
				{
					target.add(name);
				}
			}
		}
	}

	public Resolution resolve(String question, Collection<String> questNames) throws IOException
	{
		return resolve(question, questNames, false);
	}

	/**
	 * @param hasContext true when the conversation already has a resolved
	 * subject. The desperation widening (trying bare dictionary words as
	 * redirects) exists to find SOMETHING in a context-free question; on a
	 * follow-up it only manufactures junk -- "master list of all resources"
	 * once resolved pages Master, List, and Resources, crowding the real
	 * subject out of the prefetch budget.
	 */
	public Resolution resolve(String question, Collection<String> questNames, boolean hasContext)
		throws IOException
	{
		ensureVocabs();
		Map<String, String> questVocab = new HashMap<>();
		for (String q : questNames)
		{
			questVocab.put(norm(q), q);
		}

		String[] tokens = norm(question).split(" +");
		Resolution result = new Resolution();
		boolean[] used = new boolean[tokens.length];
		List<int[]> unresolved = new ArrayList<>();  // {start, size}

		for (int size = 4; size >= 1; size--)
		{
			for (int i = 0; i + size <= tokens.length; i++)
			{
				if (anyUsed(used, i, size))
				{
					continue;
				}
				String gram = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
				if (size == 1 && (STOPWORDS.contains(gram) || gram.length() < 3))
				{
					continue;
				}
				String[] hit = lookup(gram, questVocab);
				if (hit != null)
				{
					List<String> list = result.byKind(hit[0]);
					if (!list.contains(hit[1]))
					{
						list.add(hit[1]);
					}
					Arrays.fill(used, i, i + size, true);
				}
				else if (size <= 3)
				{
					unresolved.add(new int[]{i, size});
				}
			}
		}

		boolean vocabHit = !result.items.isEmpty() || !result.monsters.isEmpty()
			|| !result.quests.isEmpty() || !result.skills.isEmpty();

		// Redirect pass: candidate spans that don't overlap a confirmed match.
		// Short spans are the likeliest slang, so prefer them.
		unresolved.sort((a, b) -> Integer.compare(a[1], b[1]));
		Map<String, int[]> chosen = new LinkedHashMap<>();
		Set<String> english = wiki.englishWords();
		for (int[] span : unresolved)
		{
			if (anyUsed(used, span[0], span[1]))
			{
				continue;
			}
			String[] gramTokens = Arrays.copyOfRange(tokens, span[0], span[0] + span[1]);
			if (spansFunctionWord(gramTokens))
			{
				continue;
			}
			String gram = String.join(" ", gramTokens);
			// Inherited conversation context counts as "already resolved":
			// it satisfies the same need desperation exists to fill.
			if (!chosen.containsKey(gram)
				&& isRedirectCandidate(span[1], gramTokens, gram, english, vocabHit || hasContext))
			{
				chosen.put(gram, span);
			}
		}

		// Longest mention wins, as in the vocabulary pass: a hit from a longer
		// span suppresses hits from spans it overlaps ("crystal armor" beats
		// "armor"), and prefetch sees the most specific pages first.
		List<Map.Entry<String, String[]>> hits =
			new ArrayList<>(redirectBatch(new ArrayList<>(chosen.keySet()), questVocab).entrySet());
		hits.sort((a, b) -> Integer.compare(chosen.get(b.getKey())[1], chosen.get(a.getKey())[1]));
		for (Map.Entry<String, String[]> hit : hits)
		{
			int[] span = chosen.get(hit.getKey());
			if (anyUsed(used, span[0], span[1]))
			{
				continue;
			}
			Arrays.fill(used, span[0], span[0] + span[1], true);
			List<String> list = result.byKind(hit.getValue()[0]);
			if (!list.contains(hit.getValue()[1]))
			{
				list.add(hit.getValue()[1]);
			}
		}

		// Drop generic page hits contained inside a stronger match
		// ("Bar" vs "Adamantite bar", "King" vs "Dagannoth Kings").
		List<String> allNames = new ArrayList<>();
		for (String kind : new String[]{"items", "monsters", "quests", "pages"})
		{
			for (String n : result.byKind(kind))
			{
				allNames.add(n.toLowerCase(Locale.ROOT));
			}
		}
		result.pages.removeIf(p -> {
			String pl = p.toLowerCase(Locale.ROOT);
			return allNames.stream().anyMatch(other -> !pl.equals(other) && other.contains(pl));
		});
		return result;
	}

	/**
	 * A mention of a thing never begins or ends on a function word. Without
	 * this the window straddles conjunctions and the wiki resolves the result:
	 * "blowpipe or" (from "blowpipe or bowfa") is a real redirect to a blowpipe
	 * ornament kit, since "(or)" is how the game names those. Nothing is lost
	 * by rejecting the span -- its content core is its own candidate span.
	 */
	private static boolean spansFunctionWord(String[] gramTokens)
	{
		return GRAMMAR.contains(gramTokens[0]) || GRAMMAR.contains(gramTokens[gramTokens.length - 1]);
	}

	/**
	 * Non-dictionary tokens are almost certainly slang ("kbd", "tbow", "gwd").
	 * Dictionary tokens ("fury", "whip") are only tried when nothing else in
	 * the question resolved -- desperation widens the net without letting
	 * common words crowd out real entities.
	 */
	private static boolean isRedirectCandidate(int size, String[] gramTokens, String gram,
		Set<String> english, boolean vocabHit)
	{
		if (size == 1)
		{
			if (!english.isEmpty() && english.contains(gram))
			{
				return !vocabHit && !STOPWORDS.contains(gram);
			}
			return true;
		}
		int content = 0;
		for (String t : gramTokens)
		{
			if (!STOPWORDS.contains(t) && t.length() >= 3)
			{
				content++;
			}
		}
		return content * 2 >= gramTokens.length;
	}

	/** Check one normalized n-gram against all vocabularies. Returns
	 * {kind, canonicalName} or null. */
	private String[] lookup(String gram, Map<String, String> questVocab)
	{
		for (String candidate : gram.endsWith("s")
			? new String[]{gram, gram.substring(0, gram.length() - 1)}
			: new String[]{gram})
		{
			if (questVocab.containsKey(candidate))
			{
				return new String[]{"quests", questVocab.get(candidate)};
			}
			if (monsterVocab.containsKey(candidate))
			{
				return new String[]{"monsters", monsterVocab.get(candidate)};
			}
			// Skills outrank items: the item index contains interface
			// pseudo-items named exactly after skills ("Smithing (interface
			// item)"), and a bare skill name always means the skill.
			if (SKILL_ALIASES.containsKey(candidate))
			{
				return new String[]{"skills", title(SKILL_ALIASES.get(candidate))};
			}
			if (SKILLS.contains(candidate))
			{
				return new String[]{"skills", title(candidate)};
			}
			if (itemVocab.containsKey(candidate))
			{
				return new String[]{"items", itemVocab.get(candidate)};
			}
		}
		return null;
	}

	/** Resolve unmatched n-grams via wiki redirects in one batched call. */
	private Map<String, String[]> redirectBatch(List<String> grams, Map<String, String> questVocab)
	{
		Map<String, String[]> out = new LinkedHashMap<>();
		if (grams.isEmpty())
		{
			return out;
		}
		List<String> capped = grams.subList(0, Math.min(25, grams.size()));
		StringBuilder titles = new StringBuilder();
		for (String g : capped)
		{
			if (titles.length() > 0)
			{
				titles.append('|');
			}
			titles.append(capitalize(g));
		}
		JsonObject r;
		try
		{
			r = wiki.wikiQuery("redirects=1&titles=" + Http.enc(titles.toString()));
		}
		catch (Exception e)
		{
			log.debug("redirect batch failed", e);
			return out;
		}
		JsonObject q = r.getAsJsonObject("query");
		Map<String, String> normalized = fromToMap(q, "normalized");
		Map<String, String> redirects = fromToMap(q, "redirects");
		Set<String> existing = new HashSet<>();
		if (q.has("pages"))
		{
			for (Map.Entry<String, JsonElement> p : q.getAsJsonObject("pages").entrySet())
			{
				JsonObject page = p.getValue().getAsJsonObject();
				if (!page.has("missing"))
				{
					existing.add(page.get("title").getAsString());
				}
			}
		}

		for (String gram : capped)
		{
			String titleStr = capitalize(gram);
			titleStr = normalized.getOrDefault(titleStr, titleStr);
			String target = redirects.get(titleStr);
			if (target == null && existing.contains(titleStr))
			{
				target = titleStr;
			}
			if (target == null)
			{
				continue;
			}
			int frag = target.indexOf('#');
			if (frag >= 0)
			{
				target = target.substring(0, frag);
			}
			// Wiki naming convention for navigation-only pages: no prose to
			// retrieve, so they are never worth a fact block.
			if (target.endsWith("(disambiguation)"))
			{
				continue;
			}
			String[] known = lookup(norm(target), questVocab);
			out.put(gram, known != null ? known : new String[]{"pages", target});
		}
		return out;
	}

	private static Map<String, String> fromToMap(JsonObject q, String key)
	{
		Map<String, String> map = new HashMap<>();
		if (q.has(key))
		{
			for (JsonElement e : q.getAsJsonArray(key))
			{
				JsonObject o = e.getAsJsonObject();
				map.put(o.get("from").getAsString(), o.get("to").getAsString());
			}
		}
		return map;
	}

	private static boolean anyUsed(boolean[] used, int start, int size)
	{
		for (int i = start; i < start + size; i++)
		{
			if (used[i])
			{
				return true;
			}
		}
		return false;
	}

	static String norm(String s)
	{
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ")
			.replaceAll(" +", " ").trim();
	}

	private static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String title(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
