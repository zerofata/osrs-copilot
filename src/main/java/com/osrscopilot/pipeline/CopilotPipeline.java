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
	private static final int HISTORY_MAX_EXCHANGES = 6;
	private static final int HISTORY_MAX_CHARS = 8000;

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

	public CopilotPipeline(OkHttpClient httpClient, Gson gson, File cacheDir)
	{
		this.http = new Http(httpClient, gson);
		this.gson = gson;
		this.wiki = new WikiApi(http, gson, cacheDir);
		this.resolver = new EntityResolver(wiki);
		this.hiscores = new Hiscores(http);
		this.router = new Router(resolver);
		this.prefetcher = new Prefetcher(wiki, gson);
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
