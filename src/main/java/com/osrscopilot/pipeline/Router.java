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
 * classification, facility intents, diary tiers, bank mode. No LLM, so
 * routing is regression-testable without a model.
 */
@Slf4j
class Router
{
	/** Bank size at or below which the full bank inlines into the prompt;
	 * larger banks are summarized. */
	static final int BANK_INLINE_LIMIT = 200;

	/** Locational phrasing gate for the facility rules. */
	static final Pattern LOCATIONAL =
		Pattern.compile("\\b(where|nearest|closest|closer|nearby|near me|how far)\\b");

	/** Words that point back at the conversation; their presence carries
	 * the previous turn's entities into this turn's retrieval. */
	private static final Pattern ANAPHORIC = Pattern.compile(
		"\\b(it|its|that|those|them|these|this|ones?|same|again|instead|"
		+ "i meant?|what about|how about|and if|what if)\\b"
		// Locative "there" points back ("is a tbow good there");
		// existential "there" ("is there a way...") is excluded by its
		// surrounding frames.
		+ "|(?<!\\b(?:is|are|was|were|will|would|can|could|should|do|does|did) )"
		+ "\\bthere\\b(?!'s)(?! (?:is|are|was|were|be|a|an|any\\w*|no)\\b)");

	/** Only applied when a resolved page is a diary; "hard" elsewhere is
	 * inert. */
	private static final Pattern DIARY_TIER =
		Pattern.compile("\\b(easy|medium|hard|elite)\\b", Pattern.CASE_INSENSITIVE);

	/** "herb run"/"tree route" names an activity the resolver can't find;
	 * routes to the wiki's Farming runs guide. */
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

	// Need names shared with the prefetcher; a misspelled need silently
	// no-ops.
	static final String NEED_PRICES = "prices";
	static final String NEED_DROP_TABLE = "drop_table";
	static final String NEED_STRATEGY = "strategy";
	static final String NEED_MECHANICS = "mechanics";
	static final String NEED_ITEM_SOURCES = "item_sources";
	static final String NEED_XP_MATH = "xp_math";
	static final String NEED_TRAINING = "training";
	static final String NEED_TRANSPORT = "transport";
	static final String NEED_SLAYER_TASK = "slayer_task";

	private static final Pattern SLAYER_MENTION = Pattern.compile("\\bslayer\\b");

	// Shared phrasing frames; a pronoun variant added here fixes every rule.
	/** "how to / how do i / how can you / how should we ..." */
	static final String HOW = "how (to|do (i|you|we)|can (i|you|we)|should (i|we)|would (i|you|we))";
	/** "where" including the contraction-less "wheres". */
	static final String WHERE = "where('?s)?";
	/** Fight verbs, inflection-tolerant (kills, killed, fighting, fought...). */
	static final String FIGHT_VERB = "(kill|beat|fight|fought|defeat|solo)(s|t?ed|ing)?";
	/** Obtain verbs, inflection-tolerant (gets, getting, made, crafting...). */
	static final String OBTAIN_VERB = "(get(s|ting)?|got|buy(s|ing)?|bought|find(s|ing)?|found"
		+ "|mak(e|es|ing)|made|craft(s|ed|ing)?|obtain(s|ed|ing|able)?|farm(s|ed|ing|able)?)";

	/** Applied only when a monster resolved: a monster plus a fight verb is
	 * fight intent, no frame required. */
	private static final Pattern FIGHT_VERB_ANYWHERE =
		Pattern.compile("\\b" + FIGHT_VERB + "\\b");

	/** Applied only when an item resolved. */
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
		{Pattern.compile("\\b(gear|setup|equipment|loadout|what.*(wear|bring|take|pack))\\b"), new String[]{NEED_STRATEGY}},
		// Safe on non-combat subjects: strategy extras only fetch when a
		// /Strategies page exists.
		{Pattern.compile("\\b(prep|prepping|prepare|preparing) for\\b"), new String[]{NEED_STRATEGY}},
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
			previous != null && previous.anyEntity()
				? EntityResolver.Source.FOLLOW_UP : EntityResolver.Source.QUESTION);
		// "my task" names an entity the player never types; resolve the
		// task creature from game state.
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
		// Inherit the previous subject only when the question points back
		// at it (anaphor, or nothing newly resolved). Inherited entities
		// merge after the question's own.
		if (previous != null && (!r.entities.anyEntity()
			|| ANAPHORIC.matcher(question.toLowerCase(Locale.ROOT)).find()))
		{
			mergeMissing(previous.items, r.entities.items);
			mergeMissing(previous.monsters, r.entities.monsters);
			mergeMissing(previous.quests, r.entities.quests);
			mergeMissing(previous.pages, r.entities.pages);
		}
		r.needs = classifyNeeds(question,
			!r.entities.monsters.isEmpty(), !r.entities.items.isEmpty());
		// Not in classifyNeeds: the task-reference half needs game state.
		if (!r.entities.monsters.isEmpty() && (taskReferenced
			|| SLAYER_MENTION.matcher(question.toLowerCase(Locale.ROOT)).find()))
		{
			r.needs.add(NEED_SLAYER_TASK);
		}
		// Transport is meaningless without a resolved destination ("best
		// way to train smithing" must not route as travel).
		if (r.entities.pages.isEmpty() && r.entities.monsters.isEmpty())
		{
			r.needs.remove(NEED_TRANSPORT);
		}
		r.facilityPages = facilityPages(question);
		// A whole diary page blows the fact budget; a named tier routes to
		// just that tier's section.
		if (r.entities.pages.stream().anyMatch(Router::isDiaryPage))
		{
			Matcher tier = DIARY_TIER.matcher(question);
			if (tier.find())
			{
				r.diaryTier = tier.group(1).toLowerCase(Locale.ROOT);
				// Otherwise the tier word also resolves as a junk page
				// ("Medium").
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
			addMissing(into, name);
		}
	}

	/** Pure needs classification: no network, no game state beyond the
	 * flags. Table-tested by RouterPhrasingTest. */
	static List<String> classifyNeeds(String question,
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
					addMissing(needs, n);
				}
			}
		}
		// A resolved entity makes intent near-certain without a question
		// frame ("can i kill vorkath without a shield").
		if (monsterResolved && FIGHT_VERB_ANYWHERE.matcher(ql).find())
		{
			addMissing(needs, NEED_STRATEGY);
			addMissing(needs, NEED_MECHANICS);
		}
		if (itemResolved && OBTAIN_VERB_ANYWHERE.matcher(ql).find())
		{
			addMissing(needs, NEED_ITEM_SOURCES);
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

	/** True when the player refers to their Slayer task without naming the
	 * creature and the client knows the task. A resolved monster wins; a
	 * denied task ("off task") must not fire. */
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
