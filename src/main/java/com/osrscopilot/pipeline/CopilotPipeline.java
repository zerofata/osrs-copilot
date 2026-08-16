package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
		/**
		 * The turn's inheritable subject -- question entities plus what the
		 * answer's opening introduced (see subjectOf). Store THIS in the
		 * Exchange, not route.entities, so follow-ups can point at things
		 * the answer brought up.
		 */
		public EntityResolver.Resolution subject;
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
	private final Router router;
	private final Prefetcher prefetcher;
	private final ToolRegistry toolRegistry;
	private final PromptBuilder promptBuilder;

	public CopilotPipeline(OkHttpClient httpClient, Gson gson, File cacheDir)
	{
		this.http = new Http(httpClient, gson);
		this.gson = gson;
		this.wiki = new WikiApi(http, gson, cacheDir);
		this.resolver = new EntityResolver(wiki);
		this.hiscores = new Hiscores(http);
		this.router = new Router(resolver);
		this.prefetcher = new Prefetcher(wiki, gson);
		this.toolRegistry = new ToolRegistry(wiki);
		this.promptBuilder = new PromptBuilder(gson, wiki, hiscores);
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
		return router.route(question, cap, previous);
	}

	/** The subject usually opens the answer ("Here's how to make your
	 * Scorching bow: ..."); scanning further mostly picks up supporting
	 * cast (teleports, side quests, alternatives). */
	private static final int SUBJECT_SCAN_CHARS = 600;
	/** Per kind, at most this many answer-introduced names join the
	 * subject. Question entities always come first, and prefetch budgets
	 * (3-4 per kind) mean anything past a few inherited names is dead
	 * weight anyway. */
	private static final int SUBJECT_ADDITIONS = 3;

	/**
	 * The inheritable subject of a completed turn. A question can hand the
	 * subject to its answer: "take me through making the bow" resolves the
	 * Tormented synapse, the answer replies in terms of the Scorching bow,
	 * and "what arrows can I use with this bow" then points at the bow --
	 * which the question's entities alone can't provide. So the subject is
	 * the question's entities plus the leading names the answer introduced.
	 * Best-effort: inheritance is an enrichment, never worth failing an
	 * already-delivered answer over.
	 */
	public EntityResolver.Resolution subjectOf(EntityResolver.Resolution question,
		String answer, GameCapture cap)
	{
		EntityResolver.Resolution subject = new EntityResolver.Resolution();
		subject.items.addAll(question.items);
		subject.monsters.addAll(question.monsters);
		subject.quests.addAll(question.quests);
		subject.skills.addAll(question.skills);
		subject.pages.addAll(question.pages);
		try
		{
			String opening = answer.length() > SUBJECT_SCAN_CHARS
				? answer.substring(0, SUBJECT_SCAN_CHARS) : answer;
			EntityResolver.Resolution named = resolver.resolve(opening,
				cap.questStates != null ? cap.questStates.keySet() : Set.of(), true);
			appendCapped(named.items, subject.items);
			appendCapped(named.monsters, subject.monsters);
			appendCapped(named.quests, subject.quests);
			appendCapped(named.pages, subject.pages);
		}
		catch (Exception e)
		{
			log.debug("subject resolution from answer failed", e);
		}
		return subject;
	}

	private static void appendCapped(List<String> from, List<String> into)
	{
		int added = 0;
		for (String name : from)
		{
			if (added == SUBJECT_ADDITIONS)
			{
				return;
			}
			if (!into.contains(name))
			{
				into.add(name);
				added++;
			}
		}
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

	/** Lowercase tradeable name to item ID, for rendering item sprites from
	 * the client's game cache. Best-effort: empty when unavailable. */
	public Map<String, Integer> knownItemIds()
	{
		try
		{
			return wiki.itemIdsByName();
		}
		catch (Exception e)
		{
			log.debug("item ids unavailable for decoration", e);
			return Map.of();
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
		p.ownedIndex = Ownership.buildIndex(cap);
		p.ownedNames = Ownership.buildNames(cap);
		p.bankInlined = !"summarized".equals(p.route.bankMode);

		List<String> facts = prefetcher.prefetch(p.route, cap,
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
		prefetcher.prefetchFacilities(p.route.facilityPages, fetchedPages, facts);
		prefetcher.addFacilitiesFromFacts(question, p.route, facts);
		Map<String, String> questFacts = Prefetcher.relevantQuestStates(question, facts,
			p.route.entities, cap.questStates);
		if (questFacts != null)
		{
			facts.add("### Quest progress (authoritative, from the game client)\n"
				+ gson.toJson(questFacts));
		}
		if (!p.bankInlined)
		{
			prefetcher.addOwnershipFromFacts(facts, p.ownedIndex, p.ownedNames);
		}

		p.factBlocks = facts.size();
		p.factTitles = new ArrayList<>();
		for (String fact : facts)
		{
			int nl = fact.indexOf('\n');
			p.factTitles.add((nl > 0 ? fact.substring(0, nl) : fact)
				.replaceFirst("^#+\\s*", ""));
		}
		p.prompt = promptBuilder.buildUserMessage(question, cap, facts, p.bankInlined);
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
		messages.add(AgentLoop.message("system", PromptBuilder.SYNTH_SYSTEM));
		for (Exchange ex : PromptBuilder.boundedHistory(history))
		{
			messages.add(AgentLoop.message("user", ex.question));
			messages.add(AgentLoop.message("assistant", ex.answer));
		}
		messages.add(AgentLoop.message("user", userMessage));

		JsonArray toolSpecs = toolRegistry.buildToolSpecs(bankInlined);
		Map<String, AgentLoop.Tool> tools = toolRegistry.buildTools(cap, prepared.ownedIndex,
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
		result.subject = subjectOf(route.entities, agent.answer, cap);
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

}
