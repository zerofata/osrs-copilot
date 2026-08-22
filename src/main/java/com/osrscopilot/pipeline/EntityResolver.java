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

	/** Question-meta vocabulary: the words players use to ASK about the game
	 * rather than to name things in it -- question grammar ("whats",
	 * "best"), quantity talk ("worth", "profit"), and gaming-register nouns
	 * ("gear", "setup", "loadout" -- absent from English wordlists but
	 * still never a subject). Used to skip single tokens in the vocabulary
	 * pass and to judge whether a multi-word span is "mostly real words".
	 * Ordinary common English needs no entry here: the frequency band in
	 * isRedirectCandidate rejects it structurally. The exception is
	 * two-letter words ("go", "up"), which the frequency band cannot judge
	 * -- at that length the wordlist holds junk and real slang alike, so
	 * curation decides and hostile redirects (the Games Room board game,
	 * Underground Pass) are blocked here. */
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
		// Apostrophe-less contractions and interjections: typed constantly,
		// and some have real wiki redirects ("Im" -> Ironman Mode, "Ah" ->
		// Alchemical Hydra) that would hijack the redirect pass.
		+ "im ive id ill youre ur hes shes theyre weve youve "
		+ "dont doesnt didnt isnt arent wasnt werent cant wont couldnt "
		+ "wouldnt shouldnt havent hasnt hadnt "
		+ "ah hm hmm eh oh ok okay um uh huh hey yo "
		// "nearest"/"closest" belong here, not just STOPWORDS: the wiki
		// redirects "Nearest bank" to its "Closest..." navigation page, so
		// the two-word span leaks through content counting. No real entity
		// name begins or ends on a locational superlative.
		+ "between above below over under across behind among upon after before near "
		+ "nearest closest there here also just really please any some").split(" ")));

	/**
	 * What kind of text is being resolved. The vocabulary pass is safe on
	 * anything; this only decides how eagerly the redirect pass may guess,
	 * because guessing costs differ by register: a player's question is
	 * slang-dense and worth wide guesses, model answer prose is ordinary
	 * English where wide guesses manufacture junk ("Go" -> the Burthorpe
	 * Games Room board game).
	 */
	public enum Source
	{
		/** A context-free player question: nothing resolved means nothing
		 * to talk about, so even bare dictionary words are worth trying
		 * ("fury", "whip"). */
		QUESTION,
		/** A follow-up in a conversation that already has a subject: the
		 * need desperation exists to fill is already met, so dictionary
		 * words are not tried. */
		FOLLOW_UP,
		/** Model-written answer prose (subject inheritance): single words
		 * are prose, not slang -- no single-token redirect guesses at all.
		 * Real answer-introduced subjects are multi-word proper names
		 * ("Meiyerditch Labs"), which still resolve. */
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
		// QUESTION register: game-state text is a bare trusted name
		// ("Greater demons") and gets the full resolution a question would.
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

	/**
	 * @param source the text's register (see {@link Source}). The
	 * desperation widening (trying bare dictionary words as redirects)
	 * exists to find SOMETHING in a context-free question; anywhere else it
	 * only manufactures junk -- "master list of all resources" once
	 * resolved pages Master, List, and Resources, crowding the real
	 * subject out of the prefetch budget.
	 */
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
		// Players name series quests bare ("Dragon Slayer", "Monkey Madness")
		// but the client's quest list only has numbered entries. Alias the
		// bare name to the series opener; a real quest with that exact name
		// keeps priority via putIfAbsent.
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
				// Single letters are noise; two-letter slang is real and
				// resolves through redirects ("cg", "kc") or skill aliases
				// ("hp", "rc"). GRAMMAR and the digit guard keep chat
				// shorthand and level numbers out of the redirect pass.
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

		// Candidate spans that don't overlap a confirmed match.
		// Short spans are the likeliest slang, so prefer them.
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
			// Inherited conversation context counts as "already resolved":
			// it satisfies the same need desperation exists to fill.
			if (!chosen.containsKey(gram)
				&& isRedirectCandidate(span[1], gramTokens, gram, english, common,
					vocabHit || source != Source.QUESTION, source))
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
			// An English word must prove itself by landing on a typed
			// entity: "fury" -> Amulet of fury resolves, but "bow" -> Bow
			// and "inventory" -> Inventory are the word's ordinary sense
			// wearing a page title, junk by construction. Slang keeps
			// generic pages ("toa" -> Tombs of Amascut), two-letter
			// shorthand is exempt with the wordlist unable to judge it
			// ("ge" -> Grand Exchange), and facility nouns are exempt
			// because their pages ARE generic ("house" -> Player-owned
			// house).
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
		// Glossary pages define a term ("kc" redirects to Kill count). The
		// definition is the subject only when nothing else resolved; next
		// to a real subject ("kc for corrupted gauntlet") it is noise in a
		// page slot the subject may need.
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

	/** Exclusion cues. "not" covers "not on X" / "not X"; the rest are the
	 * prepositions of exclusion. Deliberately NOT "don't/haven't": "I don't
	 * have the armor" is a statement about the armor, and the armor's facts
	 * are exactly what the answer needs. */
	private static final Set<String> NEGATIONS = new HashSet<>(Arrays.asList(
		"not", "without", "excluding", "except", "besides", "minus", "ignoring"));

	/** True when the question mentions the word at least once NOT under a
	 * negation cue ("on task" yes; "not on task" no). Lets keyword rules
	 * outside the vocabulary pass share the resolver's negation semantics. */
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

	/**
	 * True when the span sits under a negation ("not on consumables",
	 * "except for the amulet"): the player is excluding the thing, so it
	 * must not drive prefetch. Only function words may intervene between the
	 * cue and the mention; a content word ("don't HAVE the armor") breaks
	 * the chain, so absence-talk still resolves its entity.
	 */
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

	/** Common nouns whose game-facility sense dominates in a game question:
	 * "my bank", "closer to a bank", "difference between houses" are about
	 * the facilities, not the English words. Exempt from the common-band
	 * block and from the typed-entity requirement (their pages are generic),
	 * but still desperation-only like any dictionary word. Closed set --
	 * each entry earns its place with a battery case. */
	private static final Set<String> FACILITY_NOUNS =
		new HashSet<>(Arrays.asList("bank", "house"));

	private static boolean facilityNoun(String gram)
	{
		return FACILITY_NOUNS.contains(gram)
			|| (gram.endsWith("s") && FACILITY_NOUNS.contains(gram.substring(0, gram.length() - 1)));
	}

	/**
	 * Single-token candidacy follows one principle: absence from English is
	 * evidence of slang, so the redirect is trusted ("kbd", "tbow", "toa");
	 * membership in English demands proof. Common-band words ("up", "want",
	 * "game") never qualify -- their ordinary sense always dominates, and
	 * the wiki redirects a shocking number of them to real entities ("Want"
	 * -> Wanted!, "Up" -> Underground Pass). Rare-band words ("fury",
	 * "bow") are tried only in desperation, and their hit must additionally
	 * land on a typed entity (see the acceptance loop in resolve()).
	 */
	private static boolean isRedirectCandidate(int size, String[] gramTokens, String gram,
		Set<String> english, Set<String> common, boolean subjectKnown, Source source)
	{
		if (size == 1)
		{
			// Answer prose has no single-word slang, only single English
			// words. Real answer-introduced subjects are multi-word names,
			// which the branch below still tries.
			if (source == Source.ANSWER)
			{
				return false;
			}
			// A bare number is a level or quantity, never a subject; the
			// wiki redirects "99" to Skill mastery, which would pollute
			// every level-target question.
			if (gram.chars().allMatch(Character::isDigit))
			{
				return false;
			}
			// At two letters the wordlist can't judge anything: it holds
			// junk entries like "cg" and "ge" that would shelve real
			// player shorthand, AND common words like "ca" that ARE real
			// shorthand here (Combat Achievements). Curation owns this
			// length -- function grammar lives in GRAMMAR, question-meta
			// ("go", "up") in STOPWORDS, both filtered before this point.
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
			// Skills outrank items: the item index contains interface
			// pseudo-items named exactly after skills ("Smithing (interface
			// item)"), and a bare skill name always means the skill.
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

	/** Parses a MediaWiki "normalized"/"redirects" hop array into a
	 * from -> to map. Shared with WikiContent's redirect resolution. */
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
