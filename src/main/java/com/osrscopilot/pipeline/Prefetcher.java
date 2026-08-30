package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.io.IOException;
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
 * Deterministic prefetch: every resolved entity gets a core fact bundle;
 * needs add extras (drop tables, prices, strategy pages).
 */
@Slf4j
class Prefetcher
{
	/** Char budget per secondary page fact; truncation happens once, at
	 * the content source. */
	private static final int FACT_PAGE_BUDGET = 4500;
	/** Facility listing tables need room for the player's region to make
	 * the excerpt. */
	private static final int FACILITY_CHAR_LIMIT = 9000;
	/** Fits one gear table per combat style; a three-style tabber runs
	 * ~11k chars. */
	private static final int EQUIPMENT_CHAR_LIMIT = 12000;

	/** Travel-section headings on location and boss pages. */
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

		monsterFacts(facts, needs, ents.monsters);
		itemFacts(facts, needs, ents.items, owned, ownedNames, bankInlined);
		questFacts(facts, ents.quests);
		pageFacts(facts, needs, route);
		skillFacts(facts, needs, ents, cap);
		houseFact(facts, needs, cap);
		return facts;
	}

	/** The player's POH facilities, so travel and prep answers can weigh
	 * a house teleport. Captured on house visits; absent until one. */
	private void houseFact(List<String> facts, Set<String> needs, GameCapture cap)
	{
		if (cap.house != null && !cap.house.isEmpty()
			&& (needs.contains(Router.NEED_TRANSPORT) || needs.contains(Router.NEED_STRATEGY)))
		{
			addFact(facts, "User's player owned house (POH) facilities",
				String.join("; ", cap.house));
		}
	}

	/** Core bundle: monster info always; extras per needs. */
	private void monsterFacts(List<String> facts, Set<String> needs, List<String> monsters)
	{
		for (String monster : limit(monsters, 3))
		{
			boolean strategyNeeded = needs.contains(Router.NEED_STRATEGY)
				|| needs.contains(Router.NEED_MECHANICS);
			boolean taskNeeded = needs.contains(Router.NEED_SLAYER_TASK);
			String taskGuide = slayerTaskPage(monster);
			Map<String, Object> info = wiki.monsterInfo(monster);
			// Advertise an unfetched guide subpage so the model can
			// wiki_page it.
			if (!strategyNeeded && info != null && !info.containsKey("error")
				&& Boolean.TRUE.equals(hasStrategiesPage(monster)))
			{
				info.put("strategy_guide_page", monster + "/Strategies");
			}
			if (!taskNeeded && taskGuide != null && info != null && !info.containsKey("error"))
			{
				info.put("slayer_task_guide_page", taskGuide);
			}
			addFact(facts, "Monster info: " + monster, info);
			if (taskNeeded && taskGuide != null)
			{
				// The label carries the page's category tail ("Greater
				// demons") so sourcePage can rebuild the fetched title.
				addFact(facts, "Slayer task guide: "
					+ taskGuide.substring("Slayer task/".length()), wiki.page(taskGuide));
			}
			if (needs.contains(Router.NEED_DROP_TABLE) || needs.contains(Router.NEED_PRICES))
			{
				addFact(facts, "Drop table: " + monster, wiki.monsterDrops(monster));
			}
			if (strategyNeeded)
			{
				String strategyPage = monster + "/Strategies";
				// The index skips guaranteed-404 strategy fetches; when it
				// is unavailable (null), fall back to the blind fetch.
				String text = Boolean.FALSE.equals(hasStrategiesPage(monster))
					? null : wiki.page(strategyPage);
				String label = "Strategy: " + monster;
				if (text == null)
				{
					strategyPage = monster;
					text = wiki.page(monster, FACT_PAGE_BUDGET);
					label = "Page: " + monster;
				}
				addFact(facts, label, text);
				// Equipment tables are stripped from plaintext extracts
				// without tripping the husk detector; fetch as wikitext.
				addFact(facts, "Recommended equipment: " + monster,
					wiki.sectionByHeading(strategyPage, EQUIPMENT_HEADING, EQUIPMENT_CHAR_LIMIT));
			}
			if (needs.contains(Router.NEED_TRANSPORT))
			{
				addTravelFact(facts, monster);
			}
		}
	}

	/** Core bundle: item page always (recipes/requirements live there).
	 * Ownership fact only when the bank couldn't be inlined into the state. */
	private void itemFacts(List<String> facts, Set<String> needs, List<String> items,
		Map<String, long[]> owned, Map<String, String> ownedNames, boolean bankInlined)
	{
		for (String item : limit(items, 4))
		{
			addFact(facts, "Item page: " + item, wiki.page(item, FACT_PAGE_BUDGET));
			// Combat bonuses live in the infobox, which no text fetch ever
			// carries. Non-equipment returns a not-found that addFact skips.
			addFact(facts, "Equipment stats: " + item, wiki.itemStats(item));
			if (!bankInlined)
			{
				// An exact hit puts the location in the heading ("Ownership:
				// Ring of wealth (banked)"); the payload stays quantity-only.
				long[] counts = owned.get(item.toLowerCase(Locale.ROOT));
				String where = counts != null && Ownership.total(counts) > 0
					? " (" + Ownership.whereLabel(counts) + ")" : "";
				addFact(facts, "Ownership: " + item + where,
					Ownership.check(owned, ownedNames, item));
			}
			if (needs.contains(Router.NEED_ITEM_SOURCES) || needs.contains(Router.NEED_DROP_TABLE))
			{
				addFact(facts, "How to obtain: " + item, wiki.itemSources(item));
			}
			if (needs.contains(Router.NEED_PRICES))
			{
				addFact(facts, "GE price: " + item, wiki.gePrice(item));
			}
		}
	}

	/** Core bundle: quest page always. */
	private void questFacts(List<String> facts, List<String> quests)
	{
		for (String quest : limit(quests, 2))
		{
			addFact(facts, "Quest page: " + quest, wiki.page(quest, FACT_PAGE_BUDGET));
			// Requirements live in the {{Quest details}} template, which no
			// text fetch carries.
			addFact(facts, "Quest requirements: " + quest, wiki.questInfo(quest));
		}
	}

	/** Other resolved pages (locations, guides, diaries...). Three:
	 * comparison questions name up to three subjects. */
	private void pageFacts(List<String> facts, Set<String> needs, CopilotPipeline.Route route)
	{
		for (String page : limit(route.entities.pages, 3))
		{
			// When a facility rule claims the same page, its targeted
			// Locations fetch wins over the generic article.
			if (route.facilityPages.stream().anyMatch(p -> p.equalsIgnoreCase(page)))
			{
				continue;
			}
			// A named diary tier replaces the whole-page fetch.
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
			// Raids and minigames resolve as pages but can have /Strategies
			// guides. Pages are never blind-fetched: positive index hit only.
			boolean guideExists = Boolean.TRUE.equals(hasStrategiesPage(page));
			boolean pageStrategy = (needs.contains(Router.NEED_STRATEGY)
				|| needs.contains(Router.NEED_MECHANICS)) && guideExists;
			String pageText = wiki.page(page);
			if (pageText != null && !pageStrategy && guideExists)
			{
				// Advertise the guide so the model can wiki_page it.
				pageText = "[Strategy guide page: " + page + "/Strategies]\n" + pageText;
			}
			addFact(facts, "Page: " + page, pageText);
			if (pageStrategy)
			{
				String strategyPage = page + "/Strategies";
				addFact(facts, "Strategy: " + page, wiki.page(strategyPage));
				addFact(facts, "Recommended equipment: " + page,
					wiki.sectionByHeading(strategyPage, EQUIPMENT_HEADING, EQUIPMENT_CHAR_LIMIT));
			}
			// Untradeable equipment (Arclight, Emberlight, barrows gloves...)
			// resolves as a page, not an item -- it still has an infobox.
			addFact(facts, "Equipment stats: " + page, wiki.itemStats(page));
			if (needs.contains(Router.NEED_TRANSPORT))
			{
				addTravelFact(facts, page);
			}
		}
	}

	/** The skill's own page, only when skills are the question's whole
	 * subject (levels already sit in PLAYER STATE). Training guides and
	 * XP math ride along per needs. */
	private void skillFacts(List<String> facts, Set<String> needs,
		EntityResolver.Resolution ents, GameCapture cap)
	{
		if (!ents.skills.isEmpty() && ents.items.isEmpty() && ents.monsters.isEmpty()
			&& ents.quests.isEmpty() && ents.pages.isEmpty())
		{
			for (String skill : limit(ents.skills, 2))
			{
				addFact(facts, "Skill: " + skill, wiki.page(skill, FACT_PAGE_BUDGET));
			}
		}

		// The label carries the page title: the footer links it for
		// attribution and the model sees which audience the guide targets.
		if (needs.contains(Router.NEED_TRAINING))
		{
			for (String skill : limit(ents.skills, 2))
			{
				String guide = trainingGuide(skill, cap.accountTypeName());
				addFact(facts, "Training guide: " + guide, wiki.page(guide));
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
	}

	/** The training guide page for the player's account type. "<Skill>
	 * training" is a wiki convention (page or redirect for every skill);
	 * the ironman guide families have a chapter per skill, with Attack
	 * and Strength consolidated under Melee. */
	static String trainingGuide(String skill, String accountType)
	{
		if ("NORMAL".equals(accountType))
		{
			return skill + " training";
		}
		String chapter = "Attack".equals(skill) || "Strength".equals(skill) ? "Melee" : skill;
		return ("ULTIMATE_IRONMAN".equals(accountType)
			? "Ultimate Ironman Guide/" : "Ironman Guide/") + chapter;
	}

	/** Whether {monster}/Strategies exists per the snapshot index; null
	 * when the index is unavailable. Callers must treat null as unknown,
	 * never as absent. */
	private Boolean hasStrategiesPage(String monster)
	{
		try
		{
			return wiki.strategiesPages().contains(monster + "/Strategies");
		}
		catch (Exception e)
		{
			log.debug("strategies index unavailable; keeping blind-fetch behavior", e);
			return null;
		}
	}

	/** The "Slayer task/..." guide page for a creature, or null when none
	 * exists or the index is unavailable. Task pages are named by plural
	 * category ("Slayer task/Greater demons"); a plural probe bridges the
	 * gap from singular monster names. */
	private String slayerTaskPage(String monster)
	{
		try
		{
			return matchTaskPage(monster, wiki.slayerTaskPages());
		}
		catch (Exception e)
		{
			log.debug("slayer task index unavailable; skipping task guide", e);
			return null;
		}
	}

	static String matchTaskPage(String monster, Set<String> index)
	{
		String n = EntityResolver.norm(monster);
		for (String title : index)
		{
			String tail = EntityResolver.norm(title.substring("Slayer task/".length()));
			if (tail.equals(n) || tail.equals(n + "s") || tail.equals(n + "es")
				|| (n.endsWith("s") && tail.equals(n.substring(0, n.length() - 1))))
			{
				return title;
			}
		}
		return null;
	}

	/** Bosses, dungeons, and cities carry a dedicated travel section;
	 * ordinary monster pages only have a Locations heading. Try the
	 * travel section first, then fall back. */
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
		// With exactly one destination, fetch its travel section too; with
		// several, the model picks from the table and wiki_pages its choice.
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

	/** Grounds facilities the facts lean on but the question never names:
	 * "quickest way to get planks" retrieves the Plank page, which says
	 * "sawmill" throughout but never where one is. */
	void addFacilitiesFromFacts(String question, CopilotPipeline.Route route, List<String> facts)
	{
		// Skip when a facility rule already matched: its table is in the
		// facts, and facility tables mention every other amenity.
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
			// Banks are mentioned incidentally on half the wiki; only a
			// question naming banks fetches that page.
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
			// The resolver often resolved the same page; don't fetch twice.
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

	/** Ownership slice for a summarized bank: for every item the facts
	 * mention, state positively whether the player owns or lacks it (the
	 * system prompt forbids inferring from absence). Matches names
	 * against fact text, so a wiki reformat can't break it. */
	boolean addOwnershipFromFacts(List<String> facts, Map<String, long[]> owned,
		Map<String, String> names) throws IOException
	{
		Ownership.Slice slice = Ownership.slice(
			String.join("\n", facts).toLowerCase(Locale.ROOT),
			owned, names, wiki.itemCatalog());
		if (slice == null)
		{
			return false;
		}
		// On a complete block the caller withholds the search tool.
		addFact(facts, slice.complete
			? "Ownership of every item these facts mention (complete both ways: "
				+ "owned means owned, absent from OWNED means not owned)"
			: "Ownership (lists cut for length; for items in neither list, decide "
				+ "what actually matters to the answer and verify just those in ONE "
				+ "batched search_owned_items call)",
			slice.text);
		return slice.complete;
	}

	/** The player's progress for every quest the question or facts
	 * mention. */
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

	/** The wiki page a fact was retrieved from (null for game-state
	 * facts); the answer footer links it for CC BY-NC-SA attribution.
	 * Unknown labels return null: unlinked beats mislinked. */
	static String sourcePage(String factTitle)
	{
		int sep = factTitle.indexOf(": ");
		if (sep < 0)
		{
			return null;
		}
		String label = factTitle.substring(0, sep);
		String name = factTitle.substring(sep + 2);
		if (label.startsWith("Diary tasks"))
		{
			return name;
		}
		switch (label)
		{
			case "Monster info":
			case "Drop table":
			case "Page":
			case "Item page":
			case "Equipment stats":
			case "How to obtain":
			case "Quest page":
			case "Quest requirements":
			case "Skill":
			case "Getting there":
			case "Locations":
			case "Facility locations":
			// Fetched from /Strategies when it exists, else the main page;
			// both are already their own fact, so the page stays linked.
			case "Recommended equipment":
			// The label already names the guide page.
			case "Training guide":
				return name;
			case "Strategy":
				return name + "/Strategies";
			case "Slayer task guide":
				return "Slayer task/" + name;
			default:
				return null;
		}
	}

	/** Strips the model-facing usage instructions that game-state fact
	 * titles carry before display in the footer. */
	static String displayTitle(String factTitle)
	{
		if (factTitle.startsWith("Quest progress"))
		{
			return "Quest progress";
		}
		if (factTitle.startsWith("Ownership") && !factTitle.startsWith("Ownership: "))
		{
			return "Ownership";
		}
		return factTitle;
	}

	/** Skips failed lookups: content methods return null, structured
	 * lookups return error maps; neither is a fact. */
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
