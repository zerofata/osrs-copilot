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
 * Deterministic entity resolution: n-gram lookup against local vocabularies
 * (longest match wins), then unresolved spans batched through the wiki
 * redirect API, which resolves community slang ("addy bars" -> Adamantite
 * bar, "kbd" -> King Black Dragon).
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

	/** Question vocabulary that is never a subject ("whats", "worth",
	 * "gear"). Common English is rejected by the frequency band instead;
	 * two-letter words ("go", "up") must be listed here because the
	 * wordlist can't judge that length and both have wiki redirects. */
	private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
		("a an the i my me you your it its this that these those is are was be been "
		+ "do does did can could should would will whats what's what how where when why which who "
		+ "best good better fast fastest quick quickest way ways get got make making need needs "
		+ "build building built nearest closest go up "
		+ "worth for with without and or not no yes of in on at to from into vs versus about "
		+ "more less most many much have has had if while during active right now current level "
		+ "levels xp exp experience quest quests boss monster gear setup loadout strategy guide tips "
		+ "kill killing fight fighting drop drops dropped use using item items stuff thing things "
		+ "money gp gold profit hour hr afk safe easy hard next "
		+ "player players people normally usually give giving steps step finish finished "
		+ "complete completed start started solo group grouping").split(" ")));

	/** Function words only; judges span edges. STOPWORDS can't: it holds
	 * game words ("quest", "gear") that appear in page titles. */
	private static final Set<String> GRAMMAR = new HashSet<>(Arrays.asList(
		("a an the this that these those my your our their its his her "
		+ "i me you it we they "
		+ "is are was were be been am do does did can could should would will might must "
		+ "if while during until when where what whats what's how why which who whose "
		+ "and or nor but not no yes vs versus "
		+ "of in on at to from into onto with without for by about than then as "
		// Some contractions have wiki redirects ("Im" -> Ironman Mode).
		+ "im ive id ill youre ur hes shes theyre weve youve "
		+ "dont doesnt didnt isnt arent wasnt werent cant wont couldnt "
		+ "wouldnt shouldnt havent hasnt hadnt "
		+ "ah hm hmm eh oh ok okay um uh huh hey yo "
		// "Nearest bank" is a real wiki redirect; keeping the superlatives
		// here stops such spans at the edge check.
		+ "between above below over under across behind among upon after before near "
		+ "nearest closest there here also just really please any some").split(" ")));

	/** The register of the text; controls how eagerly the redirect pass
	 * guesses on single dictionary words. */
	public enum Source
	{
		/** Context-free player question: bare dictionary words are tried
		 * when nothing else resolved. */
		QUESTION,
		/** The conversation already has a subject: dictionary words are
		 * not tried. */
		FOLLOW_UP,
		/** Model answer prose: no single-token guesses; multi-word proper
		 * names still resolve. */
		ANSWER
	}

	public static class Resolution
	{
		public final List<String> items = new ArrayList<>();
		public final List<String> monsters = new ArrayList<>();
		public final List<String> quests = new ArrayList<>();
		public final List<String> skills = new ArrayList<>();
		public final List<String> pages = new ArrayList<>();

		public boolean anyEntity()
		{
			return !items.isEmpty() || !monsters.isEmpty() || !quests.isEmpty()
				|| !skills.isEmpty() || !pages.isEmpty();
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
			// Values are the canonical page: versioned names ("Fire cape
			// (l)") map to their shared page.
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
		// Game-state names are trusted; resolve at full question register.
		Resolution extra = resolve(text, questNames, Source.QUESTION);
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

	public Resolution resolve(String question, Collection<String> questNames, Source source)
		throws IOException
	{
		ensureVocabs();
		Map<String, String> questVocab = questVocab(questNames);

		String[] tokens = norm(question).split(" +");
		Resolution result = new Resolution();
		boolean[] used = new boolean[tokens.length];
		// {start, size} spans the vocabulary pass could not claim.
		List<int[]> unresolved = vocabularyPass(tokens, used, questVocab, result);
		redirectPass(tokens, used, unresolved, questVocab, result, source);
		dropShadowedPages(result);
		return result;
	}

	private static Map<String, String> questVocab(Collection<String> questNames)
	{
		Map<String, String> questVocab = new HashMap<>();
		for (String q : questNames)
		{
			questVocab.put(norm(q), q);
		}
		// Alias bare series names ("Dragon Slayer") to the numbered opener;
		// putIfAbsent keeps a real quest with that exact name first.
		for (String q : questNames)
		{
			String n = norm(q);
			if (n.endsWith(" i"))
			{
				questVocab.putIfAbsent(n.substring(0, n.length() - 2), q);
			}
		}
		return questVocab;
	}

	/** N-gram scan against the local vocabularies, longest span first.
	 * Marks claimed tokens in {@code used} and returns the spans left for
	 * the redirect pass. */
	private List<int[]> vocabularyPass(String[] tokens, boolean[] used,
		Map<String, String> questVocab, Resolution result)
	{
		List<int[]> unresolved = new ArrayList<>();
		for (int size = 4; size >= 1; size--)
		{
			for (int i = 0; i + size <= tokens.length; i++)
			{
				if (anyUsed(used, i, size))
				{
					continue;
				}
				String gram = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
				// Single letters are noise; two-letter slang ("hp", "cg")
				// is real.
				if (size == 1 && (STOPWORDS.contains(gram) || gram.length() < 2))
				{
					continue;
				}
				String[] hit = lookup(gram, questVocab);
				if (hit != null)
				{
					// A negated mention is consumed (no sub-span may retry
					// it) but resolves to nothing.
					if (!negated(tokens, i))
					{
						List<String> list = result.byKind(hit[0]);
						if (!list.contains(hit[1]))
						{
							list.add(hit[1]);
						}
					}
					Arrays.fill(used, i, i + size, true);
				}
				else if (size <= 3)
				{
					unresolved.add(new int[]{i, size});
				}
			}
		}
		return unresolved;
	}

	/** Wiki redirect pass over the spans the vocabularies left behind:
	 * candidate selection is register-aware (see isRedirectCandidate),
	 * acceptance longest-span-first as in the vocabulary pass. */
	private void redirectPass(String[] tokens, boolean[] used, List<int[]> unresolved,
		Map<String, String> questVocab, Resolution result, Source source) throws IOException
	{
		boolean vocabHit = !result.items.isEmpty() || !result.monsters.isEmpty()
			|| !result.quests.isEmpty() || !result.skills.isEmpty();

		// Shortest spans first: they are the likeliest slang.
		unresolved.sort((a, b) -> Integer.compare(a[1], b[1]));
		Map<String, int[]> chosen = new LinkedHashMap<>();
		Set<String> english = wiki.englishWords();
		Set<String> common = wiki.commonEnglishWords();
		for (int[] span : unresolved)
		{
			if (anyUsed(used, span[0], span[1]))
			{
				continue;
			}
			String[] gramTokens = Arrays.copyOfRange(tokens, span[0], span[0] + span[1]);
			if (spansFunctionWord(gramTokens) || negated(tokens, span[0]))
			{
				continue;
			}
			String gram = String.join(" ", gramTokens);
			if (!chosen.containsKey(gram)
				&& isRedirectCandidate(span[1], gramTokens, gram, english, common,
					vocabHit || source != Source.QUESTION, source))
			{
				chosen.put(gram, span);
			}
		}

		// Accept longest span first: "crystal armor" suppresses "armor".
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
			// An English word must land on a typed entity ("fury" ->
			// Amulet of fury yes, "bow" -> the Bow page no). Two-letter
			// shorthand and facility nouns are exempt.
			String gram = hit.getKey();
			if (span[1] == 1 && gram.length() > 2 && english.contains(gram)
				&& !facilityNoun(gram) && "pages".equals(hit.getValue()[0]))
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
	}

	/** Drop generic page hits contained inside a stronger match
	 * ("Bar" vs "Adamantite bar", "King" vs "Dagannoth Kings"), and
	 * glossary pages when a real subject resolved. */
	private static void dropShadowedPages(Resolution result)
	{
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
		// A glossary page ("kc" -> Kill count) is only the subject when
		// nothing else resolved.
		if (hasSubjectBesides(result, GLOSSARY_PAGES))
		{
			result.pages.removeIf(GLOSSARY_PAGES::contains);
		}
	}

	private static final Set<String> GLOSSARY_PAGES =
		new HashSet<>(Arrays.asList("Kill count", "Drop rate"));

	private static boolean hasSubjectBesides(Resolution result, Set<String> ignoredPages)
	{
		if (!result.items.isEmpty() || !result.monsters.isEmpty()
			|| !result.quests.isEmpty() || !result.skills.isEmpty())
		{
			return true;
		}
		return result.pages.stream().anyMatch(p -> !ignoredPages.contains(p));
	}

	/** Exclusion cues. "don't/haven't" are deliberately absent: "I don't
	 * have the armor" still needs the armor's facts. */
	private static final Set<String> NEGATIONS = new HashSet<>(Arrays.asList(
		"not", "without", "excluding", "except", "besides", "minus", "ignoring"));

	/** True when the word appears at least once outside a negation
	 * ("on task" yes; "not on task" no). */
	static boolean mentionsAffirmatively(String question, String word)
	{
		String[] tokens = norm(question).split(" +");
		for (int i = 0; i < tokens.length; i++)
		{
			if (tokens[i].equals(word) && !negated(tokens, i))
			{
				return true;
			}
		}
		return false;
	}

	/** True when the span sits under a negation cue with only function
	 * words between; a content word breaks the chain ("don't HAVE the
	 * armor" still resolves the armor). */
	private static boolean negated(String[] tokens, int start)
	{
		for (int back = 1; back <= 3 && start - back >= 0; back++)
		{
			String t = tokens[start - back];
			if (NEGATIONS.contains(t))
			{
				return true;
			}
			if (!GRAMMAR.contains(t))
			{
				return false;
			}
		}
		return false;
	}

	/** A mention never begins or ends on a function word: "blowpipe or"
	 * is a real redirect (the game names ornament kits "(or)"). */
	private static boolean spansFunctionWord(String[] gramTokens)
	{
		return GRAMMAR.contains(gramTokens[0]) || GRAMMAR.contains(gramTokens[gramTokens.length - 1]);
	}

	/** Common nouns whose game-facility sense dominates in a game question;
	 * exempt from the common-band block and the typed-entity requirement. */
	private static final Set<String> FACILITY_NOUNS =
		new HashSet<>(Arrays.asList("bank", "house"));

	private static boolean facilityNoun(String gram)
	{
		return FACILITY_NOUNS.contains(gram)
			|| (gram.endsWith("s") && FACILITY_NOUNS.contains(gram.substring(0, gram.length() - 1)));
	}

	/** Absence from English is evidence of slang, so the redirect is
	 * trusted ("kbd", "toa"); English words need proof. Common-band words
	 * never qualify, rare-band words only when nothing else resolved. */
	private static boolean isRedirectCandidate(int size, String[] gramTokens, String gram,
		Set<String> english, Set<String> common, boolean subjectKnown, Source source)
	{
		if (size == 1)
		{
			if (source == Source.ANSWER)
			{
				return false;
			}
			// A bare number is a level, not a subject ("99" redirects to
			// Skill mastery).
			if (gram.chars().allMatch(Character::isDigit))
			{
				return false;
			}
			// The wordlist can't judge two-letter words; GRAMMAR and
			// STOPWORDS have already filtered the junk ones.
			if (gram.length() == 2)
			{
				return true;
			}
			if (common.contains(gram) && !facilityNoun(gram))
			{
				return false;
			}
			if (!english.isEmpty() && english.contains(gram))
			{
				return !subjectKnown;
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
			// Skills outrank items: the item index has interface
			// pseudo-items named exactly after skills.
			if (SKILL_ALIASES.containsKey(candidate))
			{
				return new String[]{"skills", capitalize(SKILL_ALIASES.get(candidate))};
			}
			if (SKILLS.contains(candidate))
			{
				return new String[]{"skills", capitalize(candidate)};
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
			if (target.endsWith("(disambiguation)"))
			{
				continue;
			}
			String[] known = lookup(norm(target), questVocab);
			out.put(gram, known != null ? known : new String[]{"pages", target});
		}
		return out;
	}

	/** Parses a MediaWiki "normalized"/"redirects" hop array into a
	 * from -> to map. */
	static Map<String, String> fromToMap(JsonObject q, String key)
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
}
