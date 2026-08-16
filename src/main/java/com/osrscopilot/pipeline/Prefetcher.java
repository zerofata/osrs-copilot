package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashSet;
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

	/** Strategy pages keep recommended gear in an equipment/setup section. */
	private static final Pattern EQUIPMENT_HEADING =
		Pattern.compile("(?i)\\b(equipment|gear|setups?|loadout)\\b");

	/** Cap on the ownership slice; past this the block is noise, and the
	 * batched search tool covers the rest. */
	private static final int OWNERSHIP_FACT_LIMIT = 120;
	/** Cap on the not-owned enumeration, sized ABOVE what real equipment
	 * pages mention (a three-style tabber names ~100-150 items): a
	 * truncated list forfeits the completeness claim, and the model then
	 * rationally re-verifies items one search at a time -- the exact
	 * behavior this block exists to prevent. ~4 tokens per entry, so even
	 * a full list costs under a thousand tokens against the multi-
	 * thousand-token sweep it replaces. */
	private static final int LACKED_FACT_LIMIT = 200;

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
				// Travel options live in a dedicated section that the
				// truncated extract usually cuts off; fetch it by heading.
				String section = wiki.sectionByHeading(page, TRANSPORT_HEADING, FACT_PAGE_BUDGET);
				addFact(facts, "Getting there: " + page, section);
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
		String haystack = String.join("\n", facts).toLowerCase(Locale.ROOT);
		Map<String, Long> ownedMentioned = new LinkedHashMap<>();
		List<String> ownedBases = new ArrayList<>();
		for (Map.Entry<String, long[]> e : owned.entrySet())
		{
			// Charge/dose qualifiers exist in bank names but never in prose:
			// "Prayer potion(4)" must match a page saying "prayer potions".
			String base = e.getKey().replaceAll("\\s*\\([^)]*\\)$", "").trim();
			ownedBases.add(base);
			if (base.length() < 3 || !mentionedInFacts(haystack, base))
			{
				continue;
			}
			String display = names.get(e.getKey()).replaceAll("\\s*\\([^)]*\\)$", "").trim();
			ownedMentioned.merge(display, e.getValue()[0], Long::sum);
		}

		Set<String> lacked = lackedMentioned(haystack, ownedBases);
		if (ownedMentioned.isEmpty() && (lacked == null || lacked.isEmpty()))
		{
			return false;
		}

		StringBuilder sb = new StringBuilder("OWNED: ");
		int n = 0;
		boolean truncated = false;
		for (Map.Entry<String, Long> e : ownedMentioned.entrySet())
		{
			if (n >= OWNERSHIP_FACT_LIMIT)
			{
				sb.append(", ...");
				truncated = true;
				break;
			}
			sb.append(n++ > 0 ? ", " : "").append(e.getKey());
			if (e.getValue() > 1)
			{
				sb.append(" x").append(e.getValue());
			}
		}
		if (n == 0)
		{
			sb.append("none of them");
		}
		if (lacked != null)
		{
			sb.append("\nNOT OWNED (verified absent at capture): ");
			n = 0;
			for (String name : lacked)
			{
				if (n >= LACKED_FACT_LIMIT)
				{
					sb.append(", ...");
					truncated = true;
					break;
				}
				sb.append(n++ > 0 ? ", " : "").append(name);
			}
			if (n == 0)
			{
				sb.append("nothing relevant");
			}
		}

		// The completeness claim is only made when it is true: a cut list
		// or an unavailable vocabulary falls back to honest framing that
		// still steers verification into ONE batched call, not a sweep.
		// The return value reports which framing was used -- a complete
		// block means the search tool has nothing left to answer and the
		// caller withholds it entirely.
		boolean complete = lacked != null && !truncated;
		addFact(facts, complete
			? "Ownership of every item these facts mention (complete both ways: "
				+ "owned means owned, absent from OWNED means not owned)"
			: "Ownership (lists cut for length; for items in neither list, decide "
				+ "what actually matters to the answer and verify just those in ONE "
				+ "batched search_owned_items call)",
			sb.toString());
		return complete;
	}

	/**
	 * Every catalogued item the facts mention that the player does NOT
	 * own, by name. Null when the vocabulary is unavailable -- the caller
	 * then may not claim completeness. Owned variants count as owned: a
	 * fact saying "Slayer helmet (i)" is covered by the player's "Black
	 * slayer helmet (i)".
	 */
	private Set<String> lackedMentioned(String haystack, List<String> ownedBases)
	{
		List<String[]> vocabulary;
		try
		{
			vocabulary = wiki.knownItemNames();
		}
		catch (Exception e)
		{
			log.debug("item vocabulary unavailable; ownership fact stays partial", e);
			return null;
		}
		Set<String> lacked = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		for (String[] it : vocabulary)
		{
			String base = it[0].replaceAll("\\s*\\([^)]*\\)$", "").trim();
			String lower = base.toLowerCase(Locale.ROOT);
			if (lower.length() < 4 || !seen.add(lower)
				|| !Ownership.mentionsWord(haystack, lower))
			{
				continue;
			}
			boolean ownedVariant = false;
			for (String ownedBase : ownedBases)
			{
				if (ownedBase.equals(lower) || Ownership.mentionsWord(ownedBase, lower))
				{
					ownedVariant = true;
					break;
				}
			}
			if (!ownedVariant)
			{
				lacked.add(base);
			}
		}
		return lacked;
	}

	/**
	 * True when the facts mention the owned item's name -- directly, or
	 * under a shorter form of it. Wiki pages name canonical gear ("Slayer
	 * helmet (i)"); the player's copy is often a decorated variant ("Black
	 * slayer helmet (i)"), which plain containment can never find in the
	 * page's text. Stripping lead qualifier words one at a time catches
	 * those, and only multi-word remainders count: single words like
	 * "helmet" are prose, not an item reference.
	 */
	private static boolean mentionedInFacts(String haystack, String base)
	{
		String candidate = base;
		while (true)
		{
			if (Ownership.mentionsWord(haystack, candidate))
			{
				return true;
			}
			int space = candidate.indexOf(' ');
			if (space < 0 || candidate.indexOf(' ', space + 1) < 0)
			{
				return false;
			}
			candidate = candidate.substring(space + 1);
		}
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
