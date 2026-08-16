package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * OSRS Copilot pipeline: resolve -> classify -> prefetch -> synthesize.
 * Runs entirely inside the plugin against a GameCapture; the only external
 * dependencies are the OSRS Wiki/GE APIs and the user's own LLM endpoint.
 *
 * Everything non-fuzzy stays out of the LLM:
 * - Entity resolution is deterministic (closed vocab + wiki redirects).
 * - Needs classification is rule-based.
 * - Every resolved entity gets a core fact bundle unconditionally; needs only
 *   add extras (drop tables, prices, strategy pages).
 * - Ownership lives in the state block; quest gates arrive pre-verified.
 */
@Slf4j
public class CopilotPipeline
{
	/** Prompt budget per secondary page fact (items, quests, strategy
	 * fallbacks). Passed to WikiApi as the fetch budget -- truncation happens
	 * once, at the content source, never re-applied here. */
	private static final int FACT_PAGE_BUDGET = 4500;
	/** Facility listing tables need more room: "closest X" is only answerable
	 * if the player's region made it into the excerpt. */
	private static final int FACILITY_CHAR_LIMIT = 9000;
	/** Equipment sections hold one gear table per combat style; multi-style
	 * bosses (the ones gear questions are hardest for) need them all.
	 * Sized to fit a three-style tabber (Tormented Demons: ~11k chars). */
	private static final int EQUIPMENT_CHAR_LIMIT = 12000;
	private static final int BANK_INLINE_LIMIT = 200;
	private static final int HISTORY_MAX_EXCHANGES = 6;
	private static final int HISTORY_MAX_CHARS = 8000;

	/** Location and boss pages carry their travel options in a dedicated
	 * section; matched by heading, so this works for any destination. */
	private static final Pattern TRANSPORT_HEADING =
		Pattern.compile("(?i)transport|getting there|travel|access");

	private static final Pattern LOCATIONS_HEADING =
		Pattern.compile("(?i)^locations?$|list of|where to find");

	/** Strategy pages keep recommended gear in an equipment/setup section. */
	private static final Pattern EQUIPMENT_HEADING =
		Pattern.compile("(?i)\\b(equipment|gear|setups?|loadout)\\b");

	/** Facility questions ("where can I pray/bank/smelt") map a small closed
	 * vocabulary of intents to the wiki's canonical listing pages. The page
	 * CONTENT is live from the wiki, so game updates (new altars, moved
	 * banks) flow through with no code change; only a brand-new facility
	 * type would need a line here. Gated on locational phrasing. */
	private static final Pattern LOCATIONAL =
		Pattern.compile("\\b(where|nearest|closest|closer|nearby|near me|how far)\\b");

	/** Words that point back at the conversation instead of standing alone.
	 * Their presence means the previous turn's subject is still live and its
	 * entities should carry into this turn's retrieval. */
	private static final Pattern ANAPHORIC = Pattern.compile(
		"\\b(it|its|that|those|them|these|this|ones?|same|again|instead|"
		+ "what about|how about|and if|what if)\\b");
	/** Diary tiers are a closed vocabulary; the match only applies when a
	 * resolved page is a diary, so "hard" in other questions is inert. */
	private static final Pattern DIARY_TIER =
		Pattern.compile("\\b(easy|medium|hard|elite)\\b", Pattern.CASE_INSENSITIVE);

	private static final Object[][] FACILITY_RULES = {
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

	private static final String SYNTH_SYSTEM =
		"You are an OSRS copilot running inside RuneLite. Answer the player's question "
		+ "using their live game state and the retrieved facts provided. Principles:\n"
		+ "- PLAYER STATE is what the client observes. A field marked \"complete\" is "
		+ "exhaustive (absence there means not owned). Everything else not shown is UNKNOWN, "
		+ "not absent: never infer missing items, progress, or experience from absence -- say "
		+ "what you can't see and give conditional advice ('if you have X, otherwise Y') where "
		+ "it matters.\n"
		+ "- Respect the player's account type (an IRONMAN cannot use the Grand Exchange).\n"
		+ "- Retrieved facts were fetched from the OSRS Wiki just now and are authoritative. "
		+ "Prefer them over memory, and don't assert game facts from memory that they don't "
		+ "support. Use tools if something essential is missing.\n"
		+ "- Personalize: check requirements against the player's actual levels, quest progress, "
		+ "kill counts (boss_kc_and_activity_scores; a missing boss means few kills, not "
		+ "exactly zero), and owned items.\n"
		+ "- Quest progress shown in the facts is authoritative. For any quest not shown, verify "
		+ "with the quest_status tool before advising quest-gated content.\n"
		+ "- If the question is ambiguous, state the interpretation you chose in one short sentence.\n"
		+ "- If the facts don't answer the question, say so honestly rather than guessing.\n"
		+ "- When comparing or ranking options, ground the verdict in retrieved numbers "
		+ "(defensive stats, requirements, mechanics) or a retrieved recommendation. If nothing "
		+ "retrieved settles it, present the trade-offs and say the data doesn't settle it -- "
		+ "never manufacture a confident winner.\n"
		+ "- In an ongoing conversation, PLAYER STATE and RETRIEVED FACTS accompany the latest "
		+ "question and reflect the current moment; earlier answers may describe older state.\n"
		+ "- Be concrete and concise; recommend rather than exhaustively enumerate, but use "
		+ "steps or lists when the question calls for them.";

	// The needs vocabulary: retrieval extras a question can switch on.
	// Declared once so the rules below and their consumers in prefetch/route
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

	/** Needs are EXTRAS on top of each entity's core bundle, never the only
	 * path to essential facts. */
	private static final Object[][] NEED_RULES = {
		{Pattern.compile("\\b(price|cost|how much|value|alch|sell)\\b"), new String[]{NEED_PRICES}},
		{Pattern.compile("\\bworth (doing|it|killing|farming)\\b"), new String[]{NEED_DROP_TABLE, NEED_PRICES, NEED_STRATEGY}},
		{Pattern.compile("\\b(where|how) (do|can|to|i)\\b.*\\b(get|find|make|obtain|farm)\\b"), new String[]{NEED_ITEM_SOURCES}},
		{Pattern.compile("\\b(quickest|fastest|easiest|best) way\\b.*\\b(get|make|obtain)\\b"), new String[]{NEED_ITEM_SOURCES}},
		{Pattern.compile("\\b(gear|setup|equipment|loadout|what.*(wear|bring))\\b"), new String[]{NEED_STRATEGY}},
		{Pattern.compile("\\b(strategy|safespot|(how|best way) to (kill|beat|fight))\\b"), new String[]{NEED_STRATEGY, NEED_MECHANICS}},
		{Pattern.compile("\\b(afk|aggro|mechanic|spawn|attack style|weakness)\\b"), new String[]{NEED_MECHANICS}},
		{Pattern.compile("\\b(level|xp|experience)\\b"), new String[]{NEED_XP_MATH}},
		{Pattern.compile("\\btrain(ing)?\\b|\\blevell?ing\\b"), new String[]{NEED_TRAINING}},
		{Pattern.compile("\\b(drop|dropped|loot)\\b"), new String[]{NEED_DROP_TABLE}},
		{Pattern.compile("\\b(fastest|quickest|best) way\\b|\\bhow (do i|to|can i) (get|travel) to\\b|\\broute to\\b"),
			new String[]{NEED_TRANSPORT}},
	};

	/** One completed question/answer pair from earlier in the session. */
	public static class Exchange
	{
		public final String question;
		public final String answer;
		/** What that turn resolved to; lets follow-ups keep the subject. */
		public final EntityResolver.Resolution entities;

		public Exchange(String question, String answer)
		{
			this(question, answer, null);
		}

		public Exchange(String question, String answer, EntityResolver.Resolution entities)
		{
			this.question = question;
			this.answer = answer;
			this.entities = entities;
		}
	}

	/**
	 * The deterministic routing decision for one question: everything chosen
	 * before the model runs, as a single inspectable artifact. Routes are
	 * profiles over one pipeline spine, never separate code paths -- a wrong
	 * route costs extra or missing prefetch, not a different behavior.
	 */
	public static class Route
	{
		public EntityResolver.Resolution entities;
		public List<String> needs;
		public List<String> facilityPages;
		/** Named diary tier (easy/medium/hard/elite) when a diary page
		 * resolved; routes the prefetch to that tier's section only. */
		public String diaryTier;
		/** complete | summarized | unknown */
		public String bankMode;
		public boolean hasEvents;
	}

	public static class Result
	{
		public String answer;
		public Route route;
		public int factBlocks;
		/** Heading of every fact block retrieved, for user-facing disclosure. */
		public List<String> factTitles = List.of();
		public int contextChars;
		public List<String> toolLog;
		public long millis;
		/** The full user message sent to the model, for sanity inspection. */
		public String prompt;
		/** Token usage summed across every LLM call for this question. */
		public Llm.Usage usage;
		/**
		 * Names the answer introduced that the wiki says aren't this game's
		 * ("Anachronia -> Fossil Island"). Empty is the expected case.
		 */
		public List<String> suspectNames = List.of();
	}

	/**
	 * Everything the model gets, before it runs: the route plus the assembled
	 * user message. Produced without any LLM call, so retrieval can be
	 * inspected and regression-tested for free -- and it is the same object
	 * answer() synthesizes from, so what you inspect is what ships.
	 */
	public static class Prepared
	{
		public Route route;
		public String prompt;
		public int factBlocks;
		public List<String> factTitles = List.of();
		private Map<String, long[]> ownedIndex;
		private Map<String, String> ownedNames;
		private boolean bankInlined;
	}

	private final Http http;
	private final Gson gson;
	private final WikiApi wiki;
	private final EntityResolver resolver;
	private final Hiscores hiscores;

	public CopilotPipeline(OkHttpClient httpClient, Gson gson, File cacheDir)
	{
		this.http = new Http(httpClient, gson);
		this.gson = gson;
		this.wiki = new WikiApi(http, gson, cacheDir);
		this.resolver = new EntityResolver(wiki);
		this.hiscores = new Hiscores(http);
	}

	/** Warm vocabulary caches (call off-thread, e.g. at plugin start). */
	public void warmCaches()
	{
		try
		{
			wiki.geMapping();
			wiki.monsterNames();
			wiki.englishWords();
			wiki.locationIndex();
		}
		catch (IOException e)
		{
			log.warn("cache warm failed (will retry on first question)", e);
		}
	}

	/**
	 * The deterministic front half: entity resolution, needs classification,
	 * facility intents, capability profile. No LLM involved -- callable on
	 * its own for free route regression testing.
	 */
	public Route route(String question, GameCapture cap) throws IOException
	{
		return route(question, cap, null);
	}

	public Route route(String question, GameCapture cap, EntityResolver.Resolution previous)
		throws IOException
	{
		Route r = new Route();
		Set<String> questNames = cap.questStates != null ? cap.questStates.keySet() : Set.of();
		r.entities = resolver.resolve(question, questNames,
			previous != null && previous.anyEntity());
		// "my task" names an entity the player never types: resolve the task
		// creature from game state so it retrieves like any other monster.
		if (cap.slayerTask != null && cap.slayerTask.get("creature") != null
			&& referencesSlayerTask(question, r.entities))
		{
			resolver.resolveInto(String.valueOf(cap.slayerTask.get("creature")),
				questNames, r.entities);
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
			mergeMissing(previous.monsters, r.entities.monsters);
			mergeMissing(previous.quests, r.entities.quests);
			mergeMissing(previous.pages, r.entities.pages);
		}
		r.hasEvents = cap.recentEvents != null && !cap.recentEvents.isEmpty();
		r.needs = classifyNeeds(question, r.hasEvents);
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
		if (r.entities.pages.stream().anyMatch(CopilotPipeline::isDiaryPage))
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

	/** Every achievement diary page ends in " Diary" ("Varrock Diary",
	 * "Lumbridge & Draynor Diary"); tier names redirect to these. */
	private static boolean isDiaryPage(String page)
	{
		return page.endsWith(" Diary");
	}

	/**
	 * True when the player refers to their Slayer task instead of naming the
	 * creature, and the client knows what that task is. A monster resolved
	 * from the question wins -- they may be asking about something else.
	 */
	private static boolean referencesSlayerTask(String question, EntityResolver.Resolution entities)
	{
		if (!entities.monsters.isEmpty())
		{
			return false;
		}
		String q = question.toLowerCase(Locale.ROOT);
		return q.matches(".*\\b(task|assignment)\\b.*");
	}

	/** Every known item as {name, wiki page} (tradeable and untradeable
	 * alike), for UI entity linking. Best-effort: empty when the sources
	 * can't be fetched. */
	public List<String[]> knownItemNames()
	{
		try
		{
			return wiki.knownItemNames();
		}
		catch (Exception e)
		{
			log.debug("item names unavailable for decoration", e);
			return List.of();
		}
	}

	public Prepared prepare(String question, GameCapture cap) throws IOException
	{
		return prepare(question, cap, null);
	}

	public Prepared prepare(String question, GameCapture cap, EntityResolver.Resolution previous)
		throws IOException
	{
		Prepared p = new Prepared();
		p.route = route(question, cap, previous);
		p.ownedIndex = buildOwnedIndex(cap);
		p.ownedNames = buildOwnedNames(cap);
		p.bankInlined = !"summarized".equals(p.route.bankMode);

		List<String> facts = prefetch(p.route, cap,
			p.ownedIndex, p.ownedNames, p.bankInlined);
		// Dedupe against pages the entity loop actually fetched: it skips
		// pages the facility rule claimed, so those must not count here.
		List<String> fetchedPages = new ArrayList<>();
		for (String pg : p.route.entities.pages)
		{
			if (p.route.facilityPages.stream().noneMatch(f -> f.equalsIgnoreCase(pg)))
			{
				fetchedPages.add(pg);
			}
		}
		prefetchFacilities(p.route.facilityPages, fetchedPages, facts);
		addFacilitiesFromFacts(question, p.route, facts);
		Map<String, String> questFacts = relevantQuestStates(question, facts,
			p.route.entities, cap.questStates);
		if (questFacts != null)
		{
			facts.add("### Quest progress (authoritative, from the game client)\n"
				+ gson.toJson(questFacts));
		}
		if (!p.bankInlined)
		{
			addOwnershipFromFacts(facts, p.ownedIndex, p.ownedNames);
		}

		p.factBlocks = facts.size();
		p.factTitles = new ArrayList<>();
		for (String fact : facts)
		{
			int nl = fact.indexOf('\n');
			p.factTitles.add((nl > 0 ? fact.substring(0, nl) : fact)
				.replaceFirst("^#+\\s*", ""));
		}
		p.prompt = buildUserMessage(question, cap, facts, p.bankInlined);
		return p;
	}

	public Result answer(String question, List<Exchange> history, GameCapture cap,
		Llm.Settings llmSettings, int maxTurns, StreamListener listener) throws IOException
	{
		long t0 = System.currentTimeMillis();
		Llm llm = new Llm(http, gson, llmSettings);

		Exchange last = history != null && !history.isEmpty()
			? history.get(history.size() - 1) : null;
		Prepared prepared = prepare(question, cap, last != null ? last.entities : null);
		Route route = prepared.route;
		boolean bankInlined = prepared.bankInlined;
		String userMessage = prepared.prompt;

		// History enters as real chat messages: prior turns carry only the
		// question and the distilled answer; state and facts always accompany
		// the latest turn (retrieval context is transient, answers persist).
		JsonArray messages = new JsonArray();
		messages.add(AgentLoop.message("system", SYNTH_SYSTEM));
		for (Exchange ex : boundedHistory(history))
		{
			messages.add(AgentLoop.message("user", ex.question));
			messages.add(AgentLoop.message("assistant", ex.answer));
		}
		messages.add(AgentLoop.message("user", userMessage));

		JsonArray toolSpecs = buildToolSpecs(bankInlined);
		Map<String, AgentLoop.Tool> tools = buildTools(cap, prepared.ownedIndex,
			prepared.ownedNames, bankInlined);

		AgentLoop.Result agent = AgentLoop.run(llm, gson, messages,
			toolSpecs, tools, maxTurns, listener);

		Result result = new Result();
		result.answer = agent.answer;
		result.route = route;
		result.factBlocks = prepared.factBlocks;
		result.factTitles = prepared.factTitles;
		result.contextChars = userMessage.length();
		result.toolLog = agent.toolLog;
		result.prompt = userMessage;
		result.usage = llm.usage();
		result.suspectNames = suspectNames(agent.answer, userMessage);
		result.millis = System.currentTimeMillis() - t0;
		return result;
	}

	/**
	 * Post-hoc grounding check: proper nouns the answer introduced that the
	 * wiki has no matching page for. Retrieval and prompting reduce memory
	 * leakage but can't preclude it, so the last line of defence is checking
	 * the output against the game's own vocabulary. One batched request; a
	 * failure here must never cost the player their answer.
	 */
	private List<String> suspectNames(String answer, String context)
	{
		try
		{
			List<GroundingCheck.Finding> suspect =
				GroundingCheck.suspect(GroundingCheck.check(answer, context, wiki));
			List<String> names = new ArrayList<>();
			for (GroundingCheck.Finding f : suspect)
			{
				names.add(f.toString());
			}
			if (!names.isEmpty())
			{
				log.info("answer contains names absent from the wiki: {}", names);
			}
			return names;
		}
		catch (Exception e)
		{
			log.debug("grounding check failed", e);
			return List.of();
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

	/**
	 * Second pass, mirror of addOwnershipFromFacts: a sourcing answer often
	 * hinges on a facility the question never names. "Quickest way to get
	 * planks" retrieves the Plank page, which says "sawmill" throughout but
	 * never says WHERE one is -- a vacuum the model once filled with an
	 * invented Taverley sawmill. When a sourcing/locational question's facts
	 * lean on a known facility (repeated mentions, not incidental), ground
	 * its locations from the live wiki instead of the model's memory.
	 */
	private void addFacilitiesFromFacts(String question, Route route, List<String> facts)
	{
		// Discovery is for questions that DON'T name a facility. When one
		// matched, its table is already in the facts -- and facility tables
		// list nearby amenities, so scanning them "discovers" every other
		// facility ("nearest bank" once pulled Furnace and Cooking range).
		if (!route.facilityPages.isEmpty())
		{
			return;
		}
		String ql = question.toLowerCase(Locale.ROOT);
		if (!route.needs.contains(NEED_ITEM_SOURCES) && !LOCATIONAL.matcher(ql).find())
		{
			return;
		}
		String hay = String.join("\n", facts).toLowerCase(Locale.ROOT);
		List<String> pages = new ArrayList<>();
		for (Object[] rule : FACILITY_RULES)
		{
			String page = (String) rule[1];
			// Banks are mentioned incidentally on half the wiki ("bank
			// nearby", "withdraw from the bank") and every player knows
			// where one is; only a question naming banks fetches that page.
			if ("Bank".equals(page) || pages.size() >= 2)
			{
				continue;
			}
			Matcher m = ((Pattern) rule[0]).matcher(hay);
			int hits = 0;
			while (hits < 3 && m.find())
			{
				hits++;
			}
			if (hits >= 3)
			{
				pages.add(page);
			}
		}
		List<String> alreadyFetched = new ArrayList<>(route.entities.pages);
		alreadyFetched.addAll(route.facilityPages);
		prefetchFacilities(pages, alreadyFetched, facts);
	}

	/** For facility questions, attach the wiki's canonical listing page
	 * (its Locations section when present, else the page wikitext). */
	private void prefetchFacilities(List<String> facilityPages, List<String> alreadyFetched,
		List<String> facts)
	{
		for (String page : facilityPages)
		{
			// The resolver often lands on the same page the facility rule
			// names ("nearest anvil" -> page Anvil + facility Anvil); fetching
			// it twice only doubles the tokens.
			if (alreadyFetched.stream().anyMatch(p -> p.equalsIgnoreCase(page)))
			{
				continue;
			}
			String text = wiki.sectionByHeading(page, LOCATIONS_HEADING, FACILITY_CHAR_LIMIT);
			if (text == null)
			{
				text = wiki.wikitext(page, FACILITY_CHAR_LIMIT);
			}
			addFact(facts, "Facility locations: " + page, text);
		}
	}

	/** Newest-first budget: keep the most recent exchanges within both the
	 * exchange and character caps, preserving chronological order. */
	private static List<Exchange> boundedHistory(List<Exchange> history)
	{
		List<Exchange> kept = new ArrayList<>();
		if (history == null)
		{
			return kept;
		}
		int chars = 0;
		for (int i = history.size() - 1; i >= 0 && kept.size() < HISTORY_MAX_EXCHANGES; i--)
		{
			Exchange ex = history.get(i);
			chars += ex.question.length() + ex.answer.length();
			if (chars > HISTORY_MAX_CHARS && !kept.isEmpty())
			{
				break;
			}
			kept.add(0, ex);
		}
		return kept;
	}

	// ------------------------------------------------------------------
	// Needs classification (rule-based)
	// ------------------------------------------------------------------

	private static List<String> classifyNeeds(String question, boolean hasEvents)
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
		if (hasEvents && (Pattern.compile("\\b(this|that|just|my)\\b.*\\b(drop|loot|kill|got)\\b")
			.matcher(ql).find() || ql.contains("whats this") || ql.contains("what's this")))
		{
			needs.add(NEED_RECENT_EVENTS);
		}
		return needs;
	}

	// ------------------------------------------------------------------
	// Ownership index (bank + inventory + equipment)
	// ------------------------------------------------------------------

	private static Map<String, long[]> buildOwnedIndex(GameCapture cap)
	{
		Map<String, long[]> owned = new LinkedHashMap<>();
		for (List<Map<String, Object>> container :
			Arrays.asList(cap.bank, cap.inventory, cap.equipment))
		{
			if (container == null)
			{
				continue;
			}
			for (Map<String, Object> item : container)
			{
				String name = String.valueOf(item.get("name"));
				long qty = item.get("quantity") instanceof Number
					? ((Number) item.get("quantity")).longValue() : 1;
				owned.merge(name.toLowerCase(Locale.ROOT), new long[]{qty},
					(a, b) -> new long[]{a[0] + b[0]});
			}
		}
		return owned;
	}

	private static Map<String, String> buildOwnedNames(GameCapture cap)
	{
		Map<String, String> names = new LinkedHashMap<>();
		for (List<Map<String, Object>> container :
			Arrays.asList(cap.bank, cap.inventory, cap.equipment))
		{
			if (container == null)
			{
				continue;
			}
			for (Map<String, Object> item : container)
			{
				String name = String.valueOf(item.get("name"));
				names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
			}
		}
		return names;
	}

	/** Cap on the ownership slice below; past this the block is noise, and
	 * the batched search tool covers the rest. */
	private static final int OWNERSHIP_FACT_LIMIT = 120;

	/**
	 * Ownership slice for a summarized bank: whichever owned items the
	 * retrieved facts mention are declared up front, so gear, quest, and
	 * training answers start with the ownership they need instead of
	 * discovering it one tool call at a time (a diligent model once made 55
	 * single-item searches for one gear question). Format-blind by
	 * construction: it matches the client's own item names against fact
	 * text, never parsing the page -- so it behaves the same for every
	 * route and every page layout, and can't be broken by a wiki reformat.
	 */
	private void addOwnershipFromFacts(List<String> facts, Map<String, long[]> owned,
		Map<String, String> names)
	{
		String haystack = String.join("\n", facts).toLowerCase(Locale.ROOT);
		Map<String, Long> mentioned = new LinkedHashMap<>();
		for (Map.Entry<String, long[]> e : owned.entrySet())
		{
			// Charge/dose qualifiers exist in bank names but never in prose:
			// "Prayer potion(4)" must match a page saying "prayer potions".
			String base = e.getKey().replaceAll("\\s*\\([^)]*\\)$", "").trim();
			if (base.length() < 3 || !mentionsWord(haystack, base))
			{
				continue;
			}
			String display = names.get(e.getKey()).replaceAll("\\s*\\([^)]*\\)$", "").trim();
			mentioned.merge(display, e.getValue()[0], Long::sum);
		}
		if (mentioned.isEmpty())
		{
			return;
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Map.Entry<String, Long> e : mentioned.entrySet())
		{
			if (n >= OWNERSHIP_FACT_LIMIT)
			{
				sb.append(", ...");
				break;
			}
			sb.append(n++ > 0 ? ", " : "").append(e.getKey());
			if (e.getValue() > 1)
			{
				sb.append(" x").append(e.getValue());
			}
		}
		addFact(facts, "You own (of the items mentioned in these facts; "
			+ "anything not listed was absent at capture)", sb.toString());
	}

	/** Case-folded whole-word containment, tolerating a plural "s" on the
	 * fact side. Plain substring would let "bow" claim "blowpipe". */
	private static boolean mentionsWord(String haystack, String needle)
	{
		int from = 0;
		int i;
		while ((i = haystack.indexOf(needle, from)) >= 0)
		{
			int end = i + needle.length();
			if (end < haystack.length() && haystack.charAt(end) == 's')
			{
				end++;
			}
			boolean startOk = i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1));
			boolean endOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
			if (startOk && endOk)
			{
				return true;
			}
			from = i + 1;
		}
		return false;
	}

	private Object checkOwnership(Map<String, long[]> owned, Map<String, String> names, String itemName)
	{
		String key = itemName.toLowerCase(Locale.ROOT);
		if (owned.containsKey(key))
		{
			return Map.of("item", names.get(key), "owned", owned.get(key)[0]);
		}
		List<Map<String, Object>> partial = new ArrayList<>();
		for (Map.Entry<String, long[]> e : owned.entrySet())
		{
			if ((e.getKey().contains(key) || key.contains(e.getKey())) && partial.size() < 5)
			{
				Map<String, Object> hit = new LinkedHashMap<>();
				hit.put("item", names.get(e.getKey()));
				hit.put("owned", e.getValue()[0]);
				partial.add(hit);
			}
		}
		if (!partial.isEmpty())
		{
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("item", itemName);
			out.put("exact_match", false);
			out.put("similar_owned", partial);
			return out;
		}
		return Map.of("item", itemName, "owned", 0);
	}

	// ------------------------------------------------------------------
	// Deterministic prefetch
	// ------------------------------------------------------------------

	private List<String> prefetch(Route route,
		GameCapture cap, Map<String, long[]> owned, Map<String, String> ownedNames, boolean bankInlined)
	{
		EntityResolver.Resolution ents = route.entities;
		List<String> facts = new ArrayList<>();
		Set<String> needs = new TreeSet<>(route.needs);

		// Core bundle: monster info always; extras per needs.
		for (String monster : limit(ents.monsters, 3))
		{
			addFact(facts, "Monster info: " + monster, wiki.monsterInfo(monster));
			if (needs.contains(NEED_DROP_TABLE) || needs.contains(NEED_PRICES))
			{
				addFact(facts, "Drop table: " + monster, wiki.monsterDrops(monster));
			}
			if (needs.contains(NEED_STRATEGY) || needs.contains(NEED_MECHANICS))
			{
				String strategyPage = monster + "/Strategies";
				String text = wiki.page(strategyPage);
				String label = "Strategy: " + monster;
				if (text == null)
				{
					strategyPage = monster;
					text = wiki.page(monster, FACT_PAGE_BUDGET);
					label = "Page: " + monster;
				}
				addFact(facts, label, text);
				// Recommended gear lives in equipment tables, which plaintext
				// extracts strip without tripping the husk detector (the rest
				// of the page is prose). Same mechanism as transport: fetch
				// the section by heading, as table-preserving wikitext.
				addFact(facts, "Recommended equipment: " + monster,
					wiki.sectionByHeading(strategyPage, EQUIPMENT_HEADING, EQUIPMENT_CHAR_LIMIT));
			}
			if (needs.contains(NEED_TRANSPORT))
			{
				String section = wiki.sectionByHeading(monster, TRANSPORT_HEADING, FACT_PAGE_BUDGET);
				addFact(facts, "Getting there: " + monster, section);
			}
		}

		// Core bundle: item page always (recipes/requirements live there).
		// Ownership fact only when the bank couldn't be inlined into the state.
		for (String item : limit(ents.items, 4))
		{
			addFact(facts, "Item page: " + item, wiki.page(item, FACT_PAGE_BUDGET));
			// Combat bonuses live in the infobox, which no text fetch ever
			// carries. Non-equipment returns a not-found that addFact skips.
			addFact(facts, "Equipment stats: " + item, wiki.itemStats(item));
			if (!bankInlined)
			{
				addFact(facts, "Ownership: " + item, checkOwnership(owned, ownedNames, item));
			}
			if (needs.contains(NEED_ITEM_SOURCES) || needs.contains(NEED_DROP_TABLE))
			{
				addFact(facts, "How to obtain: " + item, wiki.itemDropSources(item));
			}
			if (needs.contains(NEED_PRICES))
			{
				addFact(facts, "GE price: " + item, wiki.gePrice(item));
			}
		}

		// Core bundle: quest page always.
		for (String quest : limit(ents.quests, 2))
		{
			addFact(facts, "Quest page: " + quest, wiki.page(quest, FACT_PAGE_BUDGET));
			// Requirements live in the {{Quest details}} template, which no
			// text fetch carries. The prerequisite names in this fact also
			// pull the player's progress for each via relevantQuestStates.
			addFact(facts, "Quest requirements: " + quest, wiki.questInfo(quest));
		}

		// Other resolved pages (locations, guides, diaries...). wiki.page()
		// serves wikitext automatically for table-heavy page categories.
		// Three, matching monsters: comparison questions legitimately name
		// three subjects ("blowpipe vs demon bow vs bowfa") and dropping the
		// third silently guts the comparison.
		for (String page : limit(ents.pages, 3))
		{
			// When the resolver and a facility rule land on the same page
			// ("nearest bank" -> page Bank + facility Bank), the facility
			// fetch wins: it targets the Locations section, which is the
			// part a locational question needs; the generic article on top
			// would only double the tokens.
			if (route.facilityPages.stream().anyMatch(p -> p.equalsIgnoreCase(page)))
			{
				continue;
			}
			// A named diary tier replaces the whole-page fetch: the tier's
			// section (task table + rewards, as table-preserving wikitext)
			// is the answer; the other three tiers are pure noise.
			if (route.diaryTier != null && isDiaryPage(page))
			{
				String tierHeading = "^" + route.diaryTier + "$";
				String section = wiki.sectionByHeading(page,
					Pattern.compile(tierHeading, Pattern.CASE_INSENSITIVE), FACILITY_CHAR_LIMIT);
				if (section != null)
				{
					addFact(facts, "Diary tasks (" + route.diaryTier + "): " + page, section);
					continue;
				}
			}
			addFact(facts, "Page: " + page, wiki.page(page));
			// Untradeable equipment (Arclight, Emberlight, barrows gloves...)
			// resolves as a page, not an item -- it still has an infobox.
			addFact(facts, "Equipment stats: " + page, wiki.itemStats(page));
			if (needs.contains(NEED_TRANSPORT))
			{
				// Travel options live in a dedicated section that the
				// truncated extract usually cuts off; fetch it by heading.
				String section = wiki.sectionByHeading(page, TRANSPORT_HEADING, FACT_PAGE_BUDGET);
				addFact(facts, "Getting there: " + page, section);
			}
		}

		// "<Skill> training" is a universal wiki convention -- a page or a
		// redirect to the canonical guide for every skill -- so one rule
		// covers all of them and new skills arrive without a code change.
		if (needs.contains(NEED_TRAINING))
		{
			for (String skill : limit(ents.skills, 2))
			{
				addFact(facts, "Training guide: " + skill, wiki.page(skill + " training"));
			}
		}

		if (needs.contains(NEED_XP_MATH) && cap.skillXp != null)
		{
			for (String skill : limit(ents.skills, 2))
			{
				Integer xp = cap.skillXp.get(skill);
				if (xp != null)
				{
					int[] next = XpTable.toNextLevel(xp);
					Map<String, Object> math = new LinkedHashMap<>();
					math.put("current_xp", xp);
					math.put("current_level", next[0]);
					math.put("next_level", next[1]);
					math.put("xp_needed_for_next_level", next[2]);
					addFact(facts, "XP math: " + skill, math);
				}
			}
		}
		return facts;
	}

	/** A failed lookup is not a fact. Content methods signal failure with
	 * null; the structured lookups (prices, drops, monster info) return
	 * error maps because the LLM tool boundary wants the message -- prefetch
	 * wants neither, so both shapes are skipped here, uniformly. */
	private void addFact(List<String> facts, String label, Object payload)
	{
		if (payload == null || (payload instanceof Map && ((Map<?, ?>) payload).containsKey("error")))
		{
			log.debug("prefetch: {} -> nothing found, skipped", label);
			return;
		}
		String text = payload instanceof String ? (String) payload : gson.toJson(payload);
		facts.add("### " + label + "\n" + text);
		log.debug("prefetch: {} ({}B)", label, text.length());
	}

	/** Attach the player's progress for every quest the question or facts
	 * mention, so quest gates arrive pre-verified. */
	private static Map<String, String> relevantQuestStates(String question, List<String> facts,
		EntityResolver.Resolution ents, Map<String, String> questStates)
	{
		if (questStates == null || questStates.isEmpty())
		{
			return null;
		}
		String hay = (question + " " + String.join(" ", facts)).toLowerCase(Locale.ROOT);
		Map<String, String> rel = new LinkedHashMap<>();
		for (String q : ents.quests)
		{
			if (questStates.containsKey(q))
			{
				rel.put(q, questStates.get(q));
			}
		}
		for (Map.Entry<String, String> e : questStates.entrySet())
		{
			if (rel.size() >= 15)
			{
				break;
			}
			if (!rel.containsKey(e.getKey()) && e.getKey().length() >= 6
				&& hay.contains(e.getKey().toLowerCase(Locale.ROOT)))
			{
				rel.put(e.getKey(), e.getValue());
			}
		}
		return rel.isEmpty() ? null : rel;
	}

	// ------------------------------------------------------------------
	// Prompt assembly
	// ------------------------------------------------------------------

	private String buildUserMessage(String question, GameCapture cap, List<String> facts,
		boolean bankInlined)
	{
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("player", cap.playerName);
		state.put("account_type", cap.accountTypeName());
		state.put("combat_level", cap.combatLevel);
		state.put("location", groundedLocation(cap));
		state.put("skills", cap.skills);
		// Kill counts are server-side state the client can't see; without
		// them the model assumes "beginner", which is the failure mode.
		Map<String, Long> scores = hiscores.rankedActivities(cap.playerName);
		if (scores != null)
		{
			state.put("boss_kc_and_activity_scores", scores);
		}
		if (cap.boostsOrDrains != null && !cap.boostsOrDrains.isEmpty())
		{
			state.put("boosts_or_drains", cap.boostsOrDrains);
		}
		if (cap.questStates != null)
		{
			long finished = cap.questStates.values().stream()
				.filter("FINISHED"::equals).count();
			state.put("quests_finished", finished);
			List<String> inProgress = new ArrayList<>();
			cap.questStates.forEach((q, s) -> {
				if ("IN_PROGRESS".equals(s))
				{
					inProgress.add(q);
				}
			});
			state.put("quests_in_progress", inProgress);
		}
		if (cap.slayerTask != null)
		{
			state.put("slayer_task", cap.slayerTask);
		}
		state.put("inventory", itemStrings(cap.inventory));
		state.put("equipment", itemNamesOnly(cap.equipment));
		// Self-describing: data plus status/provenance, no embedded instructions.
		// The system prompt defines the semantics of "complete"/"unknown" once.
		Map<String, Object> bank = new LinkedHashMap<>();
		if (cap.bank == null)
		{
			bank.put("status", "unknown");
		}
		else
		{
			bank.put("status", bankInlined ? "complete" : "summarized");
			if (cap.bankCapturedAtMs != null)
			{
				bank.put("captured", ago(cap.bankCapturedAtMs));
			}
			if (bankInlined)
			{
				bank.put("items", itemStrings(cap.bank));
			}
			else
			{
				bank.put("item_count", cap.bank.size());
				bank.put("access", "ownership of items the facts mention is in RETRIEVED FACTS; "
					+ "for anything else use ONE search_owned_items call with all queries batched");
			}
		}
		state.put("bank", bank);
		if (cap.diaries != null && !cap.diaries.isEmpty())
		{
			// Self-describing, like the bank field: bare per-area lists left
			// the model guessing what "[]" meant. Only areas with completed
			// tiers are listed; the note carries the semantics for the rest.
			Map<String, Object> completed = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : cap.diaries.entrySet())
			{
				if (e.getValue() instanceof List && !((List<?>) e.getValue()).isEmpty())
				{
					completed.put(e.getKey(), e.getValue());
				}
			}
			Map<String, Object> diaries = new LinkedHashMap<>();
			diaries.put("status", "authoritative, from the game client");
			diaries.put("completed_tiers", completed);
			diaries.put("note", "any area or tier not listed here is NOT complete. "
				+ "Per-task progress inside an incomplete tier is not visible: present its "
				+ "tasks as a checklist, never claim which individual tasks are done");
			state.put("achievement_diaries", diaries);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("QUESTION: ").append(question).append("\n\n");
		sb.append("PLAYER STATE:\n").append(gson.toJson(state));
		if (cap.recentEvents != null && !cap.recentEvents.isEmpty())
		{
			sb.append("\n\nRECENT SESSION EVENTS (newest last):\n")
				.append(gson.toJson(cap.recentEvents));
		}
		if (!facts.isEmpty())
		{
			sb.append("\n\nRETRIEVED FACTS (fetched from OSRS Wiki / GE just now):\n\n")
				.append(String.join("\n", facts));
		}
		return sb.toString();
	}

	/**
	 * Raw coordinates mean nothing to a model; resolve them to named places
	 * from the wiki's live location index. Coordinates stay in as
	 * supplementary data (retrieved facts quote exact tiles).
	 */
	private Map<String, Object> groundedLocation(GameCapture cap)
	{
		if (cap.location == null)
		{
			return null;
		}
		// JSON round-trips turn ints into doubles ("y":3220.0); tidy them.
		Map<String, Object> out = new LinkedHashMap<>();
		cap.location.forEach((k, v) ->
			out.put(k, v instanceof Number ? ((Number) v).longValue() : v));
		Object xo = cap.location.get("x");
		Object yo = cap.location.get("y");
		if (xo instanceof Number && yo instanceof Number)
		{
			int x = ((Number) xo).intValue();
			int y = ((Number) yo).intValue();
			// The primary place must be one the player can BE in. A dungeon's
			// surface marker is its entrance, so a player standing on it is in
			// the surrounding area, not the dungeon -- "you are in the Yanille
			// Agility Dungeon" from a Yanille street corner came from here.
			WikiApi.NamedPoint place = null;
			WikiApi.NamedPoint second = null;
			WikiApi.NamedPoint entrance = null;
			for (WikiApi.NamedPoint p : wiki.nearestPlaces(x, y, 4))
			{
				if (Math.round(Math.sqrt(WikiApi.distSq(p, x, y))) > 300)
				{
					break;
				}
				if (p.entrance)
				{
					entrance = entrance == null ? p : entrance;
				}
				else if (place == null)
				{
					place = p;
				}
				else if (second == null)
				{
					second = p;
				}
			}
			if (place != null)
			{
				long dist = Math.round(Math.sqrt(WikiApi.distSq(place, x, y)));
				out.put("place", place.name + (dist <= 40 ? "" : " (~" + dist + " tiles away)"));
				if (second != null)
				{
					out.put("also_near", second.name);
				}
			}
			if (entrance != null)
			{
				long dist = Math.round(Math.sqrt(WikiApi.distSq(entrance, x, y)));
				out.put("nearby_entrance", entrance.name
					+ " (surface entrance ~" + dist + " tiles away; the player is NOT inside)");
			}
			if (place == null && entrance == null && y >= 6400)
			{
				// Deterministic fact: the coordinate plane above y=6400
				// holds dungeons and instances, not the surface world. The
				// index covers the surface map, so nothing matched here.
				out.put("place", "underground or instanced area (off the surface map)");
			}
			// Otherwise: no named place within range; say nothing rather
			// than something wrong.
		}
		return out;
	}

	private static String ago(long thenMs)
	{
		long mins = Math.max(0, (System.currentTimeMillis() - thenMs) / 60_000);
		if (mins < 60)
		{
			return mins + " minutes ago";
		}
		if (mins < 48 * 60)
		{
			return (mins / 60) + " hours ago";
		}
		return (mins / (24 * 60)) + " days ago";
	}

	private static List<String> itemStrings(List<Map<String, Object>> items)
	{
		List<String> out = new ArrayList<>();
		if (items != null)
		{
			for (Map<String, Object> item : items)
			{
				long qty = item.get("quantity") instanceof Number
					? ((Number) item.get("quantity")).longValue() : 1;
				out.add(item.get("name") + (qty > 1 ? " x" + qty : ""));
			}
		}
		return out;
	}

	private static List<String> itemNamesOnly(List<Map<String, Object>> items)
	{
		List<String> out = new ArrayList<>();
		if (items != null)
		{
			for (Map<String, Object> item : items)
			{
				out.add(String.valueOf(item.get("name")));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Tools offered to the synth model
	// ------------------------------------------------------------------

	private JsonArray buildToolSpecs(boolean bankInlined)
	{
		JsonArray specs = new JsonArray();
		specs.add(toolSpec("wiki_search",
			"Search the OSRS Wiki for pages. Returns page titles and short snippets ONLY, "
				+ "not page content -- follow up with wiki_page to read a page.",
			"query"));
		specs.add(toolSpec("wiki_page",
			"Get the text of an OSRS Wiki page (item, monster, quest, guide).", "title"));
		specs.add(toolSpec("item_drop_sources",
			"List monsters and activities that drop an item, with quantities and drop rarity.",
			"item_name"));
		specs.add(toolSpec("monster_drops",
			"Full drop table of a monster with quantities and drop rarities.", "name"));
		specs.add(toolSpec("monster_info",
			"Get a monster's full combat profile: levels, defensive bonuses per style, "
				+ "attributes, attack style/speed, and immunities.", "name"));
		specs.add(toolSpec("item_stats",
			"Get a wearable item's combat bonuses: attack and defence by style, "
				+ "strength, ranged strength, magic damage, and prayer.", "item_name"));
		specs.add(toolSpec("quest_info",
			"Get a quest's requirements (skill levels and prerequisite quests), "
				+ "items required, and start point.", "quest_name"));
		// Batched for the same reason as search_owned_items: budget questions
		// legitimately price a whole shortlist, and that should cost one
		// round trip, not one each.
		specs.add(toolSpec("ge_price",
			"Current Grand Exchange price, buy limit, and high-alch value. Takes a LIST "
				+ "of item names and returns the price of each -- price all candidate "
				+ "items in one call, never one call per item.",
			"item_names", arrayOf("string")));
		specs.add(toolSpec("quest_status",
			"Check whether the player has finished, started, or not started a specific quest.",
			"quest_name"));
		// Only offered when the full owned list is NOT already in context --
		// a tool over visible data invites redundant lookups. Batched: a
		// gear recommendation legitimately needs dozens of ownership checks,
		// and they should cost one round trip, not one each.
		if (!bankInlined)
		{
			specs.add(toolSpec("search_owned_items",
				"Search the player's bank, inventory, and equipment. Takes a LIST of item-name "
					+ "queries and returns the matches for each -- check all candidate items "
					+ "in one call, never one call per item.",
				"queries", arrayOf("string")));
		}
		return specs;
	}

	private static JsonObject toolSpec(String name, String description, String param)
	{
		return toolSpec(name, description, param, singleType("string"));
	}

	private static JsonObject toolSpec(String name, String description, String param,
		JsonObject paramSchema)
	{
		JsonObject prop = new JsonObject();
		prop.add(param, paramSchema);
		JsonObject params = new JsonObject();
		params.addProperty("type", "object");
		params.add("properties", prop);
		JsonArray required = new JsonArray();
		required.add(param);
		params.add("required", required);
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("description", description);
		fn.add("parameters", params);
		JsonObject spec = new JsonObject();
		spec.addProperty("type", "function");
		spec.add("function", fn);
		return spec;
	}

	private static JsonObject arrayOf(String type)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "array");
		o.add("items", singleType(type));
		return o;
	}

	private static JsonObject singleType(String type)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	private Map<String, AgentLoop.Tool> buildTools(GameCapture cap, Map<String, long[]> owned,
		Map<String, String> ownedNames, boolean bankInlined)
	{
		Map<String, AgentLoop.Tool> tools = new LinkedHashMap<>();
		tools.put("wiki_search", args -> wiki.search(str(args, "query")));
		tools.put("wiki_page", args -> {
			String title = str(args, "title");
			String text = wiki.page(title);
			return text != null ? text
				: Map.of("error", "No page found for '" + title + "'. Try wiki_search first.");
		});
		tools.put("item_drop_sources", args -> wiki.itemDropSources(str(args, "item_name")));
		tools.put("monster_drops", args -> wiki.monsterDrops(str(args, "name")));
		tools.put("monster_info", args -> wiki.monsterInfo(str(args, "name")));
		tools.put("item_stats", args -> wiki.itemStats(str(args, "item_name")));
		tools.put("quest_info", args -> wiki.questInfo(str(args, "quest_name")));
		tools.put("ge_price", args -> {
			List<String> names = strList(args, "item_names", "item_name");
			if (names.isEmpty())
			{
				return Map.of("error", "provide 'item_names': a list of items to price");
			}
			Map<String, Object> result = new LinkedHashMap<>();
			for (String name : names)
			{
				result.put(name, wiki.gePrice(name));
			}
			return result;
		});
		tools.put("quest_status", args -> {
			if (cap.questStates == null || cap.questStates.isEmpty())
			{
				return Map.of("error", "quest states not available this session");
			}
			String ql = str(args, "quest_name").toLowerCase(Locale.ROOT);
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				if (e.getKey().toLowerCase(Locale.ROOT).equals(ql))
				{
					return Map.of("quest", e.getKey(), "status", e.getValue());
				}
			}
			Map<String, String> partial = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : cap.questStates.entrySet())
			{
				if (e.getKey().toLowerCase(Locale.ROOT).contains(ql))
				{
					partial.put(e.getKey(), e.getValue());
				}
			}
			return partial.isEmpty()
				? Map.of("error", "no quest matching '" + str(args, "quest_name") + "'")
				: partial;
		});
		if (!bankInlined)
		{
			tools.put("search_owned_items", args -> {
				List<String> queries = strList(args, "queries", "query");
				if (queries.isEmpty())
				{
					return Map.of("error", "provide 'queries': a list of item names to look for");
				}
				Map<String, Object> result = new LinkedHashMap<>();
				for (String query : queries)
				{
					String ql = query.toLowerCase(Locale.ROOT);
					List<Map<String, Object>> hits = new ArrayList<>();
					for (Map.Entry<String, long[]> e : owned.entrySet())
					{
						if (e.getKey().contains(ql) && hits.size() < 20)
						{
							Map<String, Object> hit = new LinkedHashMap<>();
							hit.put("item", ownedNames.get(e.getKey()));
							hit.put("quantity", e.getValue()[0]);
							hits.add(hit);
						}
					}
					result.put(query, hits.isEmpty()
						? "no match in bank/inventory/equipment" : hits);
				}
				return result;
			});
		}
		return tools;
	}

	private static String str(JsonObject args, String key)
	{
		return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
	}

	/** List argument for a batched tool. The spec says a list; a model
	 * sending one bare string (under either the list key or its singular
	 * cousin) still gets an answer -- LLM output is a system boundary. */
	private static List<String> strList(JsonObject args, String listKey, String singleKey)
	{
		List<String> values = new ArrayList<>();
		if (args.has(listKey) && args.get(listKey).isJsonArray())
		{
			for (JsonElement v : args.getAsJsonArray(listKey))
			{
				values.add(v.getAsString());
			}
		}
		else
		{
			String single = args.has(singleKey) ? str(args, singleKey) : str(args, listKey);
			if (!single.isEmpty())
			{
				values.add(single);
			}
		}
		return values;
	}

	private static <T> List<T> limit(List<T> list, int n)
	{
		return list.subList(0, Math.min(n, list.size()));
	}
}
