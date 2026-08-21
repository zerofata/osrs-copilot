package com.osrscopilot.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * The deterministic front half of the pipeline: entity resolution, needs
 * classification, facility intents, diary tiers, bank mode. No LLM, no
 * network beyond the resolver's vocabulary -- which is why the whole thing
 * is regression-testable for free via the eval battery's route-only mode.
 *
 * Everything rule-shaped lives here: the needs vocabulary, the need rules,
 * the facility rules, and the anaphora/locational patterns. Routes are
 * profiles over one pipeline spine, never separate code paths -- a wrong
 * route costs extra or missing prefetch, not different behavior.
 */
@Slf4j
class Router
{
	static final int BANK_INLINE_LIMIT = 200;

	/** Facility questions ("where can I pray/bank/smelt") map a small closed
	 * vocabulary of intents to the wiki's canonical listing pages. The page
	 * CONTENT is live from the wiki, so game updates (new altars, moved
	 * banks) flow through with no code change; only a brand-new facility
	 * type would need a line here. Gated on locational phrasing. */
	static final Pattern LOCATIONAL =
		Pattern.compile("\\b(where|nearest|closest|closer|nearby|near me|how far)\\b");

	/** Words that point back at the conversation instead of standing alone.
	 * Their presence means the previous turn's subject is still live and its
	 * entities should carry into this turn's retrieval. Corrections ("i
	 * mean for bowfa") point back by nature: they restate the previous
	 * question, so its subject must survive into this one. */
	private static final Pattern ANAPHORIC = Pattern.compile(
		"\\b(it|its|that|those|them|these|this|ones?|same|again|instead|"
		+ "i meant?|what about|how about|and if|what if)\\b"
		// Locative "there" points back at the previous subject's place ("is
		// a tbow good there"). Existential "there" refers to nothing ("is
		// there a way to...") and is recognized by its frozen frames: a
		// be-verb or modal before it, or be/article/any/no after it.
		+ "|(?<!\\b(?:is|are|was|were|will|would|can|could|should|do|does|did) )"
		+ "\\bthere\\b(?!'s)(?! (?:is|are|was|were|be|a|an|any\\w*|no)\\b)");

	/** Diary tiers are a closed vocabulary; the match only applies when a
	 * resolved page is a diary, so "hard" in other questions is inert. */
	private static final Pattern DIARY_TIER =
		Pattern.compile("\\b(easy|medium|hard|elite)\\b", Pattern.CASE_INSENSITIVE);

	/** Run planning ("herb run", "tree route") names an activity, not an
	 * entity the resolver can find; the wiki's guide page carries the
	 * routes, patch lists, and teleports such an answer is built from. */
	private static final Pattern FARMING_RUN = Pattern.compile(
		"\\b(farm(ing)?|herb|tree|fruit tree|allotment|flower) (runs?|routes?)\\b");

	static final Object[][] FACILITY_RULES = {
		{Pattern.compile("\\b(pray(er)?|altar)\\b"), "Altar"},
		{Pattern.compile("\\bbank\\b"), "Bank"},
		{Pattern.compile("\\b(furnace|smelt)\\b"), "Furnace"},
		{Pattern.compile("\\b(anvil|smith)\\b"), "Anvil"},
		{Pattern.compile("\\b(cook|range|stove)\\b"), "Cooking range"},
		{Pattern.compile("\\bfairy ring\\b"), "Fairy ring"},
		{Pattern.compile("\\bspirit tree\\b"), "Spirit tree"},
		{Pattern.compile("\\b(farm(ing)? patch|allotment)\\b"), "Farming patch"},
		{Pattern.compile("\\bwater(fill| source)?\\b"), "Water source"},
		{Pattern.compile("\\bsaw ?mill\\b"), "Sawmill"},
	};

	// The needs vocabulary: retrieval extras a question can switch on.
	// Declared once so the rules below and their consumers in the prefetcher
	// can't drift apart on a typo (a misspelled need silently no-ops).
	static final String NEED_PRICES = "prices";
	static final String NEED_DROP_TABLE = "drop_table";
	static final String NEED_STRATEGY = "strategy";
	static final String NEED_MECHANICS = "mechanics";
	static final String NEED_ITEM_SOURCES = "item_sources";
	static final String NEED_XP_MATH = "xp_math";
	static final String NEED_TRAINING = "training";
	static final String NEED_TRANSPORT = "transport";
	static final String NEED_RECENT_EVENTS = "recent_events";
	static final String NEED_SLAYER_TASK = "slayer_task";

	/** Any slayer mention: with a monster subject, the "Slayer task/..."
	 * guide page (task-only variants, location comparison) is on point. */
	private static final Pattern SLAYER_MENTION = Pattern.compile("\\bslayer\\b");

	// Shared phrasing frames and verb lexicons. Every rule that hand-rolled
	// its own pronoun frame ("how to kill" but not "how do i defeat") was a
	// brittleness bug waiting for a phrasing it hadn't met; declaring the
	// frames once means a newly discovered variant fixes every rule at once.
	/** "how to / how do i / how can you / how should we ..." */
	static final String HOW = "how (to|do (i|you|we)|can (i|you|we)|should (i|we)|would (i|you|we))";
	/** "where" including the contraction-less "wheres". */
	static final String WHERE = "where('?s)?";
	/** Fight verbs, inflection-tolerant (kills, killed, fighting, fought...). */
	static final String FIGHT_VERB = "(kill|beat|fight|fought|defeat|solo)(s|t?ed|ing)?";
	/** Obtain verbs, inflection-tolerant (gets, getting, made, crafting...). */
	static final String OBTAIN_VERB = "(get(s|ting)?|got|buy(s|ing)?|bought|find(s|ing)?|found"
		+ "|mak(e|es|ing)|made|craft(s|ed|ing)?|obtain(s|ed|ing|able)?|farm(s|ed|ing|able)?)";

	/** Fires only when a monster resolved: a named monster plus a fight verb
	 * anywhere is near-certain fight intent, no frame required ("can i kill
	 * vorkath without a shield"). */
	private static final Pattern FIGHT_VERB_ANYWHERE =
		Pattern.compile("\\b" + FIGHT_VERB + "\\b");

	/** Fires only when an item resolved: the composite acquisition fact is
	 * small, so a false positive costs little. */
	private static final Pattern OBTAIN_VERB_ANYWHERE =
		Pattern.compile("\\b" + OBTAIN_VERB + "\\b");

	/** Needs are EXTRAS on top of each entity's core bundle, never the only
	 * path to essential facts. */
	private static final Object[][] NEED_RULES = {
		{Pattern.compile("\\b(price|cost|how much|value|alch|sell)\\b"), new String[]{NEED_PRICES}},
		{Pattern.compile("\\bworth (doing|it|killing|farming)\\b"), new String[]{NEED_DROP_TABLE, NEED_PRICES, NEED_STRATEGY}},
		{Pattern.compile("\\b(" + WHERE + "|" + HOW + ")\\b.*\\b" + OBTAIN_VERB + "\\b"),
			new String[]{NEED_ITEM_SOURCES}},
		{Pattern.compile("\\b(quickest|fastest|easiest|best) (way|place|method)\\b.*\\b" + OBTAIN_VERB + "\\b"),
			new String[]{NEED_ITEM_SOURCES}},
		{Pattern.compile("\\b(gear|setup|equipment|loadout|what.*(wear|bring))\\b"), new String[]{NEED_STRATEGY}},
		{Pattern.compile("\\b(strategy|safespot|(" + HOW + "|best way to) " + FIGHT_VERB + ")\\b"),
			new String[]{NEED_STRATEGY, NEED_MECHANICS}},
		{Pattern.compile("\\b(afk|aggro|mechanic|spawn|attack style|weakness)\\b"), new String[]{NEED_MECHANICS}},
		{Pattern.compile("\\b(level|xp|experience)\\b"), new String[]{NEED_XP_MATH}},
		{Pattern.compile("\\btrain(ing)?\\b|\\blevell?ing\\b"), new String[]{NEED_TRAINING}},
		{Pattern.compile("\\b(drop|dropped|loot)\\b"), new String[]{NEED_DROP_TABLE}},
		{Pattern.compile("\\b(fastest|quickest|best) way\\b|\\b" + HOW + " (get|travel) to\\b|\\broute to\\b"),
			new String[]{NEED_TRANSPORT}},
	};

	private final EntityResolver resolver;

	Router(EntityResolver resolver)
	{
		this.resolver = resolver;
	}

	CopilotPipeline.Route route(String question, GameCapture cap,
		EntityResolver.Resolution previous) throws IOException
	{
		CopilotPipeline.Route r = new CopilotPipeline.Route();
		Set<String> questNames = cap.questStates != null ? cap.questStates.keySet() : Set.of();
		r.entities = resolver.resolve(question, questNames,
			previous != null && previous.anyEntity());
		// "my task" names an entity the player never types: resolve the task
		// creature from game state so it retrieves like any other monster.
		boolean taskReferenced = cap.slayerTask != null && cap.slayerTask.get("creature") != null
			&& referencesSlayerTask(question, r.entities);
		if (taskReferenced)
		{
			resolver.resolveInto(String.valueOf(cap.slayerTask.get("creature")),
				questNames, r.entities);
		}
		if (FARMING_RUN.matcher(question.toLowerCase(Locale.ROOT)).find()
			&& !r.entities.pages.contains("Farming runs"))
		{
			r.entities.pages.add("Farming runs");
		}
		// A follow-up inherits the conversation's subject when it points back
		// at it -- an anaphor in the text ("what ABOUT addy darts", "which
		// ONES are closer", "do i have the stats for IT") or nothing newly
		// resolved. A self-contained question ("quickest way to get planks")
		// starts clean even mid-conversation: its own entities are its
		// subject, and stale pages from three turns ago would pollute its
		// facts. Inherited entities are merged AFTER the question's own, so
		// prefetch limits favor what the player just said.
		if (previous != null && (!r.entities.anyEntity()
			|| ANAPHORIC.matcher(question.toLowerCase(Locale.ROOT)).find()))
		{
			mergeMissing(previous.items, r.entities.items);
			mergeMissing(previous.monsters, r.entities.monsters);
			mergeMissing(previous.quests, r.entities.quests);
			mergeMissing(previous.pages, r.entities.pages);
		}
		r.hasEvents = cap.recentEvents != null && !cap.recentEvents.isEmpty();
		r.needs = classifyNeeds(question, r.hasEvents,
			!r.entities.monsters.isEmpty(), !r.entities.items.isEmpty());
		// A slayer-flavored question about a monster (asked, inherited, or
		// injected from the player's assignment) wants the "Slayer task/..."
		// guide: which variants count, where each is best killed. Not part
		// of classifyNeeds: the task-reference half depends on game state.
		if (!r.entities.monsters.isEmpty() && (taskReferenced
			|| SLAYER_MENTION.matcher(question.toLowerCase(Locale.ROOT)).find()))
		{
			r.needs.add(NEED_SLAYER_TASK);
		}
		// Cross-check needs against entities: transport means "how do I get
		// THERE" and is meaningless without a resolved destination ("best way
		// to train smithing" must not route as travel).
		if (r.entities.pages.isEmpty() && r.entities.monsters.isEmpty())
		{
			r.needs.remove(NEED_TRANSPORT);
		}
		r.facilityPages = facilityPages(question);
		// A diary page holds all four tiers' task tables -- far past any
		// page budget, so a whole-page fetch truncates mid-Easy. Every
		// diary shares the wiki's Easy/Medium/Hard/Elite section structure
		// and tiers are a closed four-word vocabulary, so a named tier
		// routes to exactly its section.
		if (r.entities.pages.stream().anyMatch(Router::isDiaryPage))
		{
			Matcher tier = DIARY_TIER.matcher(question);
			if (tier.find())
			{
				r.diaryTier = tier.group(1).toLowerCase(Locale.ROOT);
				// The diary rule has claimed the tier word; without this it
				// also resolves as a junk standalone page ("Medium").
				r.entities.pages.removeIf(p -> p.equalsIgnoreCase(r.diaryTier));
			}
		}
		r.bankMode = cap.bank == null ? "unknown"
			: cap.bank.size() <= BANK_INLINE_LIMIT ? "complete" : "summarized";
		log.debug("route: items={} monsters={} quests={} pages={} needs={} facilities={} bank={}",
			r.entities.items, r.entities.monsters, r.entities.quests, r.entities.pages,
			r.needs, r.facilityPages, r.bankMode);
		return r;
	}

	/** Append entries from previous that the current list doesn't already
	 * have, preserving current-question-first order. */
	private static void mergeMissing(List<String> previous, List<String> into)
	{
		for (String name : previous)
		{
			if (!into.contains(name))
			{
				into.add(name);
			}
		}
	}

	/**
	 * Pure needs classification: question text plus entity-kind flags in,
	 * needs out. No network, no game state beyond the flags -- which is what
	 * makes it table-testable across a phrasing corpus (RouterPhrasingTest)
	 * without standing up the resolver.
	 */
	static List<String> classifyNeeds(String question, boolean hasEvents,
		boolean monsterResolved, boolean itemResolved)
	{
		String ql = question.toLowerCase(Locale.ROOT);
		List<String> needs = new ArrayList<>();
		for (Object[] rule : NEED_RULES)
		{
			if (((Pattern) rule[0]).matcher(ql).find())
			{
				for (String n : (String[]) rule[1])
				{
					if (!needs.contains(n))
					{
						needs.add(n);
					}
				}
			}
		}
		// Entity-conditioned rules: a resolved entity makes the intent
		// near-certain even without a question frame ("can i kill vorkath
		// without a shield" has no "how to" but is unmistakably fight prep).
		if (monsterResolved && FIGHT_VERB_ANYWHERE.matcher(ql).find())
		{
			addMissing(needs, NEED_STRATEGY);
			addMissing(needs, NEED_MECHANICS);
		}
		if (itemResolved && OBTAIN_VERB_ANYWHERE.matcher(ql).find())
		{
			addMissing(needs, NEED_ITEM_SOURCES);
		}
		if (hasEvents && (Pattern.compile("\\b(this|that|just|my)\\b.*\\b(drop|loot|kill|got)\\b")
			.matcher(ql).find() || ql.contains("whats this") || ql.contains("what's this")))
		{
			needs.add(NEED_RECENT_EVENTS);
		}
		return needs;
	}

	private static void addMissing(List<String> needs, String need)
	{
		if (!needs.contains(need))
		{
			needs.add(need);
		}
	}

	/** Facility intents matched in the question (routing only, no fetching). */
	private static List<String> facilityPages(String question)
	{
		List<String> pages = new ArrayList<>();
		String ql = question.toLowerCase(Locale.ROOT);
		if (!LOCATIONAL.matcher(ql).find())
		{
			return pages;
		}
		for (Object[] rule : FACILITY_RULES)
		{
			if (((Pattern) rule[0]).matcher(ql).find())
			{
				pages.add((String) rule[1]);
			}
		}
		return pages;
	}

	/** Every achievement diary page ends in " Diary" ("Varrock Diary",
	 * "Lumbridge & Draynor Diary"); tier names redirect to these. */
	static boolean isDiaryPage(String page)
	{
		return page.endsWith(" Diary");
	}

	/**
	 * True when the player refers to their Slayer task instead of naming the
	 * creature, and the client knows what that task is. A monster resolved
	 * from the question wins -- they may be asking about something else.
	 * A denied task ("im not on task", "off task") must not fire: injecting
	 * the task creature would hijack the subject and, by counting as a
	 * resolved entity, block inheritance of the real one.
	 */
	private static boolean referencesSlayerTask(String question, EntityResolver.Resolution entities)
	{
		if (!entities.monsters.isEmpty())
		{
			return false;
		}
		String q = question.toLowerCase(Locale.ROOT);
		if (q.matches(".*\\boff[ -](task|assignment)\\b.*"))
		{
			return false;
		}
		return EntityResolver.mentionsAffirmatively(question, "task")
			|| EntityResolver.mentionsAffirmatively(question, "assignment");
	}
}
