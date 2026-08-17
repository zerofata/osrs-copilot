package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Deterministic prefetch: every resolved entity gets a core fact bundle
 * unconditionally; needs only add extras (drop tables, prices, strategy
 * pages). Facts are assembled here and only here, so what the model sees
 * per route is inspectable in one place.
 */
@Slf4j
class Prefetcher
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

	/** Location and boss pages carry their travel options in a dedicated
	 * section; matched by heading, so this works for any destination. */
	private static final Pattern TRANSPORT_HEADING =
		Pattern.compile("(?i)transport|getting there|travel|access");

	private static final Pattern LOCATIONS_HEADING =
		Pattern.compile("(?i)^locations?$|list of|where to find");

	/** First page link of a location table row's destination, e.g.
	 * "|location = [[Asgarnian Ice Dungeon]] ({{Fairycode|AIQ}})". */
	private static final Pattern LOCATION_ROW_DEST =
		Pattern.compile("(?m)^\\|\\s*location\\s*=\\s*\\[\\[([^\\]|]+)");

	/** Strategy pages keep recommended gear in an equipment/setup section. */
	private static final Pattern EQUIPMENT_HEADING =
		Pattern.compile("(?i)\\b(equipment|gear|setups?|loadout)\\b");

	private final WikiApi wiki;
	private final Gson gson;

	Prefetcher(WikiApi wiki, Gson gson)
	{
		this.wiki = wiki;
		this.gson = gson;
	}

	List<String> prefetch(CopilotPipeline.Route route, GameCapture cap,
		Map<String, long[]> owned, Map<String, String> ownedNames, boolean bankInlined)
	{
		EntityResolver.Resolution ents = route.entities;
		List<String> facts = new ArrayList<>();
		Set<String> needs = new TreeSet<>(route.needs);

		// Core bundle: monster info always; extras per needs.
		for (String monster : limit(ents.monsters, 3))
		{
			addFact(facts, "Monster info: " + monster, wiki.monsterInfo(monster));
			if (needs.contains(Router.NEED_DROP_TABLE) || needs.contains(Router.NEED_PRICES))
			{
				addFact(facts, "Drop table: " + monster, wiki.monsterDrops(monster));
			}
			if (needs.contains(Router.NEED_STRATEGY) || needs.contains(Router.NEED_MECHANICS))
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
			if (needs.contains(Router.NEED_TRANSPORT))
			{
				addTravelFact(facts, monster);
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
				addFact(facts, "Ownership: " + item, Ownership.check(owned, ownedNames, item));
			}
			if (needs.contains(Router.NEED_ITEM_SOURCES) || needs.contains(Router.NEED_DROP_TABLE))
			{
				addFact(facts, "How to obtain: " + item, wiki.itemDropSources(item));
			}
			if (needs.contains(Router.NEED_PRICES))
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
			if (route.diaryTier != null && Router.isDiaryPage(page))
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
			if (needs.contains(Router.NEED_TRANSPORT))
			{
				addTravelFact(facts, page);
			}
		}

		// Core bundle for skills: the skill's own page, but only when skills
		// are the question's whole subject. Combat skills especially are
		// mentioned incidentally next to a real subject ("gear for tormented
		// demons with my attack level") -- there the other entity's bundle
		// answers and the player's levels already sit in PLAYER STATE, so a
		// generic skill article would only spend the budget. When the skill
		// is all there is ("how do I get started with sailing"), its page IS
		// the subject matter and the prompt would otherwise carry no facts.
		if (!ents.skills.isEmpty() && ents.items.isEmpty() && ents.monsters.isEmpty()
			&& ents.quests.isEmpty() && ents.pages.isEmpty())
		{
			for (String skill : limit(ents.skills, 2))
			{
				addFact(facts, "Skill: " + skill, wiki.page(skill, FACT_PAGE_BUDGET));
			}
		}

		// "<Skill> training" is a universal wiki convention -- a page or a
		// redirect to the canonical guide for every skill -- so one rule
		// covers all of them and new skills arrive without a code change.
		if (needs.contains(Router.NEED_TRAINING))
		{
			for (String skill : limit(ents.skills, 2))
			{
				addFact(facts, "Training guide: " + skill, wiki.page(skill + " training"));
			}
		}

		if (needs.contains(Router.NEED_XP_MATH) && cap.skillXp != null)
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

	/**
	 * Travel facts follow two wiki conventions. Destinations with a journey
	 * worth documenting (bosses, dungeons, cities) carry a dedicated travel
	 * section -- Getting there / Transportation / Access. Ordinary monster
	 * pages have no such section; where the creature is found lives under a
	 * Locations heading instead. Try the direct answer first, then fall back,
	 * so a transport question never ships without its destination.
	 */
	private void addTravelFact(List<String> facts, String page)
	{
		String section = wiki.sectionByHeading(page, TRANSPORT_HEADING, FACT_PAGE_BUDGET);
		if (section != null)
		{
			addFact(facts, "Getting there: " + page, section);
			return;
		}
		String locations = wiki.sectionByHeading(page, LOCATIONS_HEADING, FACT_PAGE_BUDGET);
		addFact(facts, "Locations: " + page, locations);
		// The locations table says WHERE, not HOW TO GET THERE. With one
		// destination that gap is pure retrieval -- its page's travel
		// section is the answer, so fetch it. With several, which one is
		// best is a judgment call (task, gear, diary unlocks), and travel
		// detail for arbitrary rows would only bias the model toward them;
		// it picks from the table and can wiki_page its choice.
		String destination = soleDestination(locations);
		if (destination != null && !destination.equalsIgnoreCase(page))
		{
			addFact(facts, "Getting there: " + destination,
				wiki.sectionByHeading(destination, TRANSPORT_HEADING, FACT_PAGE_BUDGET));
		}
	}

	/** The single destination a locations table points at, or null when it
	 * has none or several. */
	static String soleDestination(String locationsWikitext)
	{
		if (locationsWikitext == null)
		{
			return null;
		}
		Set<String> destinations = new LinkedHashSet<>();
		Matcher m = LOCATION_ROW_DEST.matcher(locationsWikitext);
		while (m.find())
		{
			destinations.add(m.group(1).trim());
		}
		return destinations.size() == 1 ? destinations.iterator().next() : null;
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
	void addFacilitiesFromFacts(String question, CopilotPipeline.Route route, List<String> facts)
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
		if (!route.needs.contains(Router.NEED_ITEM_SOURCES) && !Router.LOCATIONAL.matcher(ql).find())
		{
			return;
		}
		String hay = String.join("\n", facts).toLowerCase(Locale.ROOT);
		List<String> pages = new ArrayList<>();
		for (Object[] rule : Router.FACILITY_RULES)
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
	void prefetchFacilities(List<String> facilityPages, List<String> alreadyFetched,
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

	/**
	 * Ownership slice for a summarized bank: for every item the retrieved
	 * facts mention, state POSITIVELY whether the player owns it or lacks
	 * it, so gear, quest, and training answers start with the ownership
	 * they need instead of discovering it one tool call at a time (a
	 * diligent model once made 55 single-item searches for one gear
	 * question). Format-blind by construction: it matches item names
	 * against fact text, never parsing the page -- so it behaves the same
	 * for every route and every page layout, and can't be broken by a wiki
	 * reformat.
	 *
	 * Both lists exist because models don't infer from absence -- our own
	 * system prompt forbids it ("everything not shown is UNKNOWN"). An
	 * owned-only block whose header said "not listed = not owned" was
	 * ignored in practice: the model re-verified 38 fact-mentioned items
	 * through the search tool in one observed run, an entire redundant
	 * LLM round-trip. Naming the lacked items removes the inference.
	 */
	boolean addOwnershipFromFacts(List<String> facts, Map<String, long[]> owned,
		Map<String, String> names)
	{
		List<String[]> vocabulary = null;
		try
		{
			vocabulary = wiki.knownItemNames();
		}
		catch (Exception e)
		{
			log.debug("item vocabulary unavailable; ownership fact stays partial", e);
		}
		Ownership.Slice slice = Ownership.slice(
			String.join("\n", facts).toLowerCase(Locale.ROOT),
			owned, names, vocabulary);
		if (slice == null)
		{
			return false;
		}
		// The completeness claim is only made when it is true: a cut list
		// or an unavailable vocabulary falls back to honest framing that
		// still steers verification into ONE batched call, not a sweep.
		// The return value reports which framing was used -- a complete
		// block means the search tool has nothing left to answer and the
		// caller withholds it entirely.
		addFact(facts, slice.complete
			? "Ownership of every item these facts mention (complete both ways: "
				+ "owned means owned, absent from OWNED means not owned)"
			: "Ownership (lists cut for length; for items in neither list, decide "
				+ "what actually matters to the answer and verify just those in ONE "
				+ "batched search_owned_items call)",
			slice.text);
		return slice.complete;
	}

	/** Attach the player's progress for every quest the question or facts
	 * mention, so quest gates arrive pre-verified. */
	static Map<String, String> relevantQuestStates(String question, List<String> facts,
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

	private static <T> List<T> limit(List<T> list, int n)
	{
		return list.subList(0, Math.min(n, list.size()));
	}
}
