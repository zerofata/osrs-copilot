package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * OSRS Copilot pipeline: resolve -> classify -> prefetch -> synthesize.
 * The only external dependencies are the OSRS Wiki/GE APIs and the user's
 * own LLM endpoint.
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

		public Exchange(String question, String answer, EntityResolver.Resolution entities)
		{
			this.question = question;
			this.answer = answer;
			this.entities = entities;
		}
	}

	/** The deterministic routing decision for one question, made before
	 * the model runs. */
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
		/** Names the answer introduced that the wiki says aren't this
		 * game's ("Anachronia -> Fossil Island"). */
		public List<String> suspectNames = List.of();
		/** The turn's inheritable subject (see subjectOf). Store this in
		 * the Exchange, not route.entities, so follow-ups can point at
		 * things the answer brought up. */
		public EntityResolver.Resolution subject;
	}

	/** Everything the model gets, produced without any LLM call so
	 * retrieval can be inspected and regression-tested. */
	public static class Prepared
	{
		public Route route;
		public String prompt;
		public int factBlocks;
		public List<String> factTitles = List.of();
		private Map<String, long[]> ownedIndex;
		private Map<String, String> ownedNames;
		private boolean bankInlined;
		/** Whether search_owned_items is offered; decided in prepare(). */
		private boolean offerOwnedSearch;
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
		this.toolRegistry = new ToolRegistry(wiki, gson);
		this.promptBuilder = new PromptBuilder(gson, hiscores);
	}

	/** Invalidates the hiscores cache; see {@link Hiscores}. */
	public void onLogin()
	{
		hiscores.invalidate();
	}

	/** Warm vocabulary caches (call off-thread, e.g. at plugin start). */
	public void warmCaches()
	{
		try
		{
			wiki.geMapping();
			wiki.monsterNames();
			wiki.englishWords();
		}
		catch (IOException e)
		{
			log.warn("cache warm failed (will retry on first question)", e);
		}
	}

	/** The deterministic front half; callable on its own for route
	 * regression testing. */
	public Route route(String question, GameCapture cap, EntityResolver.Resolution previous)
		throws IOException
	{
		return router.route(question, cap, previous);
	}

	/** The subject usually opens the answer; deeper text is supporting
	 * cast. */
	private static final int SUBJECT_SCAN_CHARS = 600;
	/** The scan stops at the first list/table row: enumerated names are
	 * supporting cast. */
	private static final Pattern SUBJECT_SCAN_STOP =
		Pattern.compile("(?m)^\\s*(?:[-*|]|\\d+\\.)\\s");
	/** Cap on answer-introduced names joining the subject, per kind. */
	private static final int SUBJECT_ADDITIONS = 3;

	/** The turn's inheritable subject: the question's entities plus the
	 * leading names the answer introduced ("make the bow" resolves the
	 * Tormented synapse, the answer talks about the Scorching bow, "this
	 * bow" then means the bow). Best-effort. */
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
			Matcher stop = SUBJECT_SCAN_STOP.matcher(opening);
			if (stop.find())
			{
				opening = opening.substring(0, stop.start());
			}
			EntityResolver.Resolution named = resolver.resolve(opening,
				cap.questStates != null ? cap.questStates.keySet() : Set.of(),
				EntityResolver.Source.ANSWER);
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

	/** The wiki page a fact title was retrieved from (null for game-state
	 * and non-page facts), for the answer footer's source links. */
	public static String factSourcePage(String factTitle)
	{
		return Prefetcher.sourcePage(factTitle);
	}

	/** A fact title's user-facing form: game-state headings drop the
	 * model-facing instructions they carry. */
	public static String factDisplayTitle(String factTitle)
	{
		return Prefetcher.displayTitle(factTitle);
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

	/** Every plain monster name, for UI entity linking. Variant pages with
	 * parenthetical disambiguators are excluded: prose never writes "Black
	 * demon (The Grand Tree)". Empty when the snapshot is unavailable. */
	public List<String> knownMonsterNames()
	{
		try
		{
			List<String> out = new ArrayList<>();
			for (String name : wiki.monsterNames())
			{
				if (name.indexOf('(') < 0)
				{
					out.add(name);
				}
			}
			return out;
		}
		catch (Exception e)
		{
			log.debug("monster names unavailable for decoration", e);
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
		boolean ownershipComplete = false;
		if (!p.bankInlined)
		{
			ownershipComplete = prefetcher.addOwnershipFromFacts(facts, p.ownedIndex, p.ownedNames);
		}
		// Withhold the search tool only when the facts carry a gear list
		// with a complete ownership slice; without a gear list, "complete"
		// covers only incidental page items.
		boolean equipmentListed = facts.stream()
			.anyMatch(f -> f.startsWith("### Recommended equipment: "));
		p.offerOwnedSearch = !p.bankInlined && !(ownershipComplete && equipmentListed);

		p.factBlocks = facts.size();
		p.factTitles = new ArrayList<>();
		for (String fact : facts)
		{
			int nl = fact.indexOf('\n');
			p.factTitles.add((nl > 0 ? fact.substring(0, nl) : fact)
				.replaceFirst("^#+\\s*", ""));
		}
		p.prompt = promptBuilder.buildUserMessage(question, cap, facts,
			p.bankInlined, ownershipComplete, p.offerOwnedSearch);
		return p;
	}

	public Result answer(String question, List<Exchange> history, GameCapture cap,
		Llm.Settings llmSettings, int maxTurns, boolean simpleMode,
		StreamListener listener) throws IOException
	{
		long t0 = System.currentTimeMillis();
		Llm llm = new Llm(http, gson, llmSettings);

		Exchange last = history != null && !history.isEmpty()
			? history.get(history.size() - 1) : null;
		Prepared prepared = prepare(question, cap, last != null ? last.entities : null);
		Route route = prepared.route;
		String userMessage = prepared.prompt;

		// Prior turns carry only question + answer; state and facts
		// accompany the latest turn only.
		JsonArray messages = new JsonArray();
		messages.add(AgentLoop.message("system", PromptBuilder.systemPrompt(simpleMode)));
		for (Exchange ex : PromptBuilder.boundedHistory(history))
		{
			messages.add(AgentLoop.message("user", ex.question));
			messages.add(AgentLoop.message("assistant", ex.answer));
		}
		messages.add(AgentLoop.message("user", userMessage));

		JsonArray toolSpecs = toolRegistry.buildToolSpecs(prepared.offerOwnedSearch);
		Map<String, AgentLoop.Tool> tools = toolRegistry.buildTools(cap, prepared.ownedIndex,
			prepared.ownedNames, prepared.offerOwnedSearch, !prepared.bankInlined);

		AgentLoop.Result agent = AgentLoop.run(llm, gson, messages,
			toolSpecs, tools, maxTurns, listener);

		// The simple-mode prompt alone doesn't hold on formatting-happy
		// models; strip before the answer enters history.
		String answer = simpleMode ? stripEmphasisMarkdown(agent.answer) : agent.answer;

		Result result = new Result();
		result.answer = answer;
		result.route = route;
		result.factBlocks = prepared.factBlocks;
		result.factTitles = prepared.factTitles;
		result.contextChars = userMessage.length();
		result.toolLog = agent.toolLog;
		result.prompt = userMessage;
		result.usage = llm.usage();
		result.suspectNames = suspectNames(answer, userMessage);
		result.subject = subjectOf(route.entities, answer, cap);
		result.millis = System.currentTimeMillis() - t0;
		return result;
	}

	/** Removes emphasis markup (bold, italics, headers, inline code).
	 * Lists and tables are left alone: they can't be flattened to prose
	 * mechanically, and the renderer handles them. */
	static String stripEmphasisMarkdown(String answer)
	{
		return answer
			.replaceAll("(?m)^#{1,6}\\s+", "")
			.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
			.replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])", "$1")
			.replaceAll("`([^`]+)`", "$1");
	}

	/** Post-hoc grounding check: proper nouns the answer introduced that
	 * the wiki has no page for. A failure never costs the answer. */
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
