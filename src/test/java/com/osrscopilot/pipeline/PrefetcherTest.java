package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ownership fact block states, positively, which fact-mentioned items
 * the player owns and which they lack. The model answers gear questions
 * from it instead of re-verifying through the search tool, so these tests
 * pin the matching that makes its completeness claim true.
 */
public class PrefetcherTest
{
	@Test
	public void taskPageMatchingBridgesCategoryPlurals()
	{
		java.util.Set<String> index = new java.util.TreeSet<>(List.of(
			"Slayer task/Greater demons", "Slayer task/Ankou",
			"Slayer task/Aberrant spectres", "Slayer task/TzHaar"));
		// Singular monster to plural category, unchanged names, and the
		// capture's own plural creature string all resolve.
		assertEquals("Slayer task/Greater demons",
			Prefetcher.matchTaskPage("Greater demon", index));
		assertEquals("Slayer task/Ankou", Prefetcher.matchTaskPage("Ankou", index));
		assertEquals("Slayer task/TzHaar", Prefetcher.matchTaskPage("TzHaar", index));
		assertEquals("Slayer task/Aberrant spectres",
			Prefetcher.matchTaskPage("Aberrant spectres", index));
		assertEquals(null, Prefetcher.matchTaskPage("Zulrah", index));
	}

	/** WikiApi whose item vocabulary is a fixed list; nothing fetches. */
	private static WikiApi vocabOf(String... names)
	{
		return new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			public List<ItemDescriptor> itemCatalog()
			{
				List<ItemDescriptor> out = new ArrayList<>();
				for (String n : names)
				{
					out.add(new ItemDescriptor(n, n, null, false, null, null));
				}
				return out;
			}
		};
	}

	private static List<String> ownershipFacts(WikiApi wiki, List<String> pageFacts,
		Map<String, Long> ownedItems) throws Exception
	{
		Map<String, long[]> owned = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : ownedItems.entrySet())
		{
			// Fixture quantities sit in the bank, the common case for the
			// summarized-bank mode these facts exist in.
			owned.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
				new long[]{0, 0, e.getValue()});
			names.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getKey());
		}
		List<String> facts = new ArrayList<>(pageFacts);
		new Prefetcher(wiki, new Gson()).addOwnershipFromFacts(facts, owned, names);
		return facts.subList(pageFacts.size(), facts.size());
	}

	@Test
	public void ownershipFactHeadingCarriesTheLocation() throws Exception
	{
		// The wiki stub yields no page/stats facts, so the ownership fact
		// is the only one the item produces; its heading must place the
		// item, the payload staying quantity-only.
		WikiApi wiki = new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			String page(String title, int charLimit)
			{
				return null;
			}

			@Override
			Map<String, Object> itemStats(String name)
			{
				return null;
			}
		};
		CopilotPipeline.Route route = new CopilotPipeline.Route();
		route.entities = new EntityResolver.Resolution();
		route.entities.items.add("Rune platebody");
		route.needs = List.of();
		route.facilityPages = List.of();
		Map<String, long[]> owned = new LinkedHashMap<>();
		owned.put("rune platebody", new long[]{0, 0, 1});
		List<String> facts = new Prefetcher(wiki, new Gson()).prefetch(route,
			new GameCapture(), owned, Map.of("rune platebody", "Rune platebody"), false);
		assertEquals(1, facts.size());
		assertTrue(facts.get(0).startsWith("### Ownership: Rune platebody (banked)\n"));
		assertTrue(facts.get(0).contains("\"owned\":1"));
	}

	@Test
	public void ownedAndLackedAreBothStatedPositively() throws Exception
	{
		List<String> added = ownershipFacts(
			vocabOf("Abyssal whip", "Twisted bow", "Dragon dagger"),
			List.of("### Recommended equipment\nWeapon: Abyssal whip or Twisted bow"),
			Map.of("Abyssal whip", 1L));
		assertEquals(1, added.size());
		String block = added.get(0);
		assertTrue("owned entries carry their location",
			block.contains("OWNED: Abyssal whip (banked)"));
		assertTrue("fact-mentioned unowned items must be named, not implied",
			block.contains("NOT OWNED") && block.contains("Twisted bow"));
		assertFalse("unmentioned items stay out of both lists",
			block.contains("Dragon dagger"));
		assertTrue("untruncated block claims completeness",
			block.contains("(complete)"));
	}

	@Test
	public void variantNamedGearMatchesTheCanonicalWikiName() throws Exception
	{
		// The wiki says "Slayer helmet (i)"; the player owns the black
		// variant. It must land in OWNED, not NOT OWNED -- a false
		// non-ownership claim is worse than no claim.
		List<String> added = ownershipFacts(
			vocabOf("Slayer helmet (i)"),
			List.of("### Recommended equipment\nHead: Slayer helmet (i)"),
			Map.of("Black slayer helmet (i)", 1L));
		assertEquals(1, added.size());
		assertTrue(added.get(0).contains("OWNED: Black slayer helmet"));
		assertFalse("an owned variant may never be declared not-owned",
			added.get(0).contains("NOT OWNED (verified absent at capture): Slayer helmet"));
	}

	@Test
	public void singleSharedWordIsNotAMention() throws Exception
	{
		// "helmet" alone appearing in prose must not drag in every owned
		// helmet: only multi-word remainders count as an item reference.
		List<String> added = ownershipFacts(
			vocabOf(),
			List.of("### Strategy\nAny helmet with decent defence works here."),
			Map.of("Black slayer helmet (i)", 1L));
		assertTrue(added.isEmpty());
	}

	@Test
	public void truncatedLackedListDropsTheCompletenessClaim() throws Exception
	{
		StringBuilder page = new StringBuilder("### Drop table\n");
		List<String> vocab = new ArrayList<>();
		for (int i = 0; i < 250; i++)
		{
			String name = "Ancient relic " + (char) ('a' + i / 26) + (char) ('a' + i % 26);
			page.append(name).append("\n");
			vocab.add(name);
		}
		List<String> added = ownershipFacts(
			vocabOf(vocab.toArray(new String[0])),
			List.of(page.toString()), Map.of("Abyssal whip", 1L));
		assertEquals(1, added.size());
		assertFalse("a cut list may not claim completeness",
			added.get(0).contains("(complete)"));
	}

	@Test
	public void singleDestinationLocationTableYieldsItsPage()
	{
		// Skeletal Wyvern's table: one row, destination link followed by a
		// fairy code template whose [[...]]-free syntax must not confuse
		// the parse.
		String table = "==Locations==\n{{LocTableHead}}\n{{LocLine\n"
			+ "|name = Skeletal Wyvern\n"
			+ "|location = [[Asgarnian Ice Dungeon]] ({{Fairycode|AIQ}})\n"
			+ "|levels = 140\n}}\n{{LocTableBottom}}";
		assertEquals("Asgarnian Ice Dungeon", Prefetcher.soleDestination(table));
	}

	@Test
	public void multipleDestinationsYieldNothingToHopTo()
	{
		// Greater demon spawns everywhere; picking a row would be a
		// judgment call, so the hop must not trigger.
		String table = "==Locations==\n"
			+ "{{LocLine\n|name = Greater demon\n|location = [[Wilderness]] near [[Demonic Ruins]]\n}}\n"
			+ "{{LocLine\n|name = Greater demon\n|location = [[Brimhaven Dungeon]] upper level\n}}\n"
			+ "{{LocLine\n|name = Greater demon\n|location = [[Catacombs of Kourend]]\n}}";
		assertEquals(null, Prefetcher.soleDestination(table));
	}

	@Test
	public void repeatedRowsAtOneDestinationStillCountAsOne()
	{
		// Different levels of the same dungeon are one destination: the
		// link target, not the row count, decides.
		String table = "{{LocLine\n|location = [[Brimhaven Dungeon]] upper level\n}}\n"
			+ "{{LocLine\n|location = [[Brimhaven Dungeon]] lower level\n}}";
		assertEquals("Brimhaven Dungeon", Prefetcher.soleDestination(table));
	}

	@Test
	public void pipedLinksResolveToThePageNotTheLabel()
	{
		String table = "{{LocLine\n|location = [[Royal Titans|Branda and Eldric]]\n}}";
		assertEquals("Royal Titans", Prefetcher.soleDestination(table));
	}

	@Test
	public void sectionsWithoutLocationRowsYieldNothing()
	{
		assertEquals(null, Prefetcher.soleDestination(null));
		assertEquals(null, Prefetcher.soleDestination(
			"==Locations==\nFound throughout [[Gielinor]] in various dungeons."));
	}

	/** WikiApi that serves a canned article for any page and records titles. */
	private static WikiApi pagesOf(List<String> fetched)
	{
		return new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			String page(String title, int charLimit)
			{
				fetched.add(title);
				return "Article: " + title;
			}
		};
	}

	private static List<String> prefetchSkillsRoute(List<String> fetched,
		List<String> skills, List<String> items)
	{
		CopilotPipeline.Route route = new CopilotPipeline.Route();
		route.entities = new EntityResolver.Resolution();
		route.entities.skills.addAll(skills);
		route.entities.items.addAll(items);
		route.needs = List.of();
		route.facilityPages = List.of();
		return new Prefetcher(pagesOf(fetched), new Gson())
			.prefetch(route, new GameCapture(), Map.of(), Map.of(), true);
	}

	@Test
	public void aSkillAloneFetchesItsOwnPage()
	{
		List<String> fetched = new ArrayList<>();
		List<String> facts = prefetchSkillsRoute(fetched, List.of("Sailing"), List.of());
		assertTrue(fetched.contains("Sailing"));
		assertTrue(facts.stream().anyMatch(f -> f.startsWith("### Skill: Sailing")));
	}

	@Test
	public void aSkillBesideAnotherSubjectFetchesNothingExtra()
	{
		// "what attack level for the dragon scimitar": the item bundle
		// answers; the generic Attack article would only spend the budget.
		List<String> fetched = new ArrayList<>();
		prefetchSkillsRoute(fetched, List.of("Attack"), List.of("Dragon scimitar"));
		assertFalse(fetched.contains("Attack"));
	}

	@Test
	public void doseAndChargeVariantsStillMatchProse() throws Exception
	{
		List<String> added = ownershipFacts(
			vocabOf("Prayer potion(4)", "Saradomin brew(4)"),
			List.of("### Strategy\nBring prayer potions and saradomin brews."),
			Map.of("Prayer potion(4)", 104L, "Saradomin brew(4)", 124L));
		assertEquals(1, added.size());
		assertTrue(added.get(0).contains("Prayer potion x104"));
		assertTrue(added.get(0).contains("Saradomin brew x124"));
		assertFalse("owned potions may not be declared lacking",
			added.get(0).contains("NOT OWNED (verified absent at capture): Prayer potion"));
	}

	// --- strategies index: skip dead subpage fetches, advertise live ones ---

	/** WikiApi with a canned strategies index (null = index unavailable);
	 * records every page title requested. */
	private static WikiApi strategiesWiki(List<String> fetched, java.util.Set<String> index)
	{
		return new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			java.util.Set<String> strategiesPages() throws java.io.IOException
			{
				if (index == null)
				{
					throw new java.io.IOException("snapshot unavailable");
				}
				return index;
			}

			@Override
			Map<String, Object> monsterInfo(String name)
			{
				Map<String, Object> info = new LinkedHashMap<>();
				info.put("name", name);
				return info;
			}

			@Override
			String page(String title)
			{
				fetched.add(title);
				if (title.endsWith("/Strategies"))
				{
					return index != null && index.contains(title) ? "Guide: " + title : null;
				}
				return "Article: " + title;
			}

			@Override
			String page(String title, int charLimit)
			{
				fetched.add(title);
				return "Article: " + title;
			}

			@Override
			String sectionByHeading(String title, java.util.regex.Pattern heading, int charLimit)
			{
				return null;
			}
		};
	}

	private static List<String> prefetchMonsterRoute(WikiApi wiki, String monster,
		String... needs)
	{
		CopilotPipeline.Route route = new CopilotPipeline.Route();
		route.entities = new EntityResolver.Resolution();
		route.entities.monsters.add(monster);
		route.needs = List.of(needs);
		route.facilityPages = List.of();
		return new Prefetcher(wiki, new Gson())
			.prefetch(route, new GameCapture(), Map.of(), Map.of(), true);
	}

	@Test
	public void indexedDeadStrategySubpageIsNeverFetched()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, java.util.Set.of("Vorkath/Strategies"));
		List<String> facts = prefetchMonsterRoute(wiki, "Bloodveld", Router.NEED_STRATEGY);
		assertFalse("the index says Bloodveld has no guide; a live 404 is the old behavior",
			fetched.contains("Bloodveld/Strategies"));
		assertTrue("the main-page strategy fallback must still arrive",
			facts.stream().anyMatch(f -> f.startsWith("### Page: Bloodveld")));
	}

	@Test
	public void indexedLiveStrategySubpageStillFetches()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, java.util.Set.of("Vorkath/Strategies"));
		List<String> facts = prefetchMonsterRoute(wiki, "Vorkath", Router.NEED_STRATEGY);
		assertTrue(fetched.contains("Vorkath/Strategies"));
		assertTrue(facts.stream().anyMatch(f -> f.startsWith("### Strategy: Vorkath")));
	}

	@Test
	public void unavailableIndexKeepsBlindFetchBehavior()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, null);
		prefetchMonsterRoute(wiki, "Bloodveld", Router.NEED_STRATEGY);
		assertTrue("index unknown must mean try-fetch, never assume absent",
			fetched.contains("Bloodveld/Strategies"));
	}

	@Test
	public void guidePageIsAdvertisedWhenStrategyIsNotNeeded()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, java.util.Set.of("Vorkath/Strategies"));
		List<String> facts = prefetchMonsterRoute(wiki, "Vorkath");
		String info = facts.stream()
			.filter(f -> f.startsWith("### Monster info: Vorkath")).findFirst().orElse("");
		assertTrue("the model must learn the guide exists at zero request cost",
			info.contains("strategy_guide_page") && info.contains("Vorkath/Strategies"));
		assertFalse("advertising must not fetch anything",
			fetched.contains("Vorkath/Strategies"));
	}

	@Test
	public void noGuideAdvertisedWhenNoneExists()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, java.util.Set.of("Vorkath/Strategies"));
		List<String> facts = prefetchMonsterRoute(wiki, "Bloodveld");
		assertFalse(facts.stream().anyMatch(f -> f.contains("strategy_guide_page")));
	}

	// --- strategies for page-shaped entities (raids, minigames, activities) ---

	private static List<String> prefetchPageRoute(WikiApi wiki, String page, String... needs)
	{
		CopilotPipeline.Route route = new CopilotPipeline.Route();
		route.entities = new EntityResolver.Resolution();
		route.entities.pages.add(page);
		route.needs = List.of(needs);
		route.facilityPages = List.of();
		return new Prefetcher(wiki, new Gson())
			.prefetch(route, new GameCapture(), Map.of(), Map.of(), true);
	}

	@Test
	public void indexedRaidGuideIsPrefetchedOnStrategyRoutes()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched,
			java.util.Set.of("Tombs of Amascut/Strategies"));
		List<String> facts = prefetchPageRoute(wiki, "Tombs of Amascut", Router.NEED_STRATEGY);
		assertTrue(fetched.contains("Tombs of Amascut/Strategies"));
		assertTrue(facts.stream().anyMatch(f -> f.startsWith("### Strategy: Tombs of Amascut")));
	}

	@Test
	public void pagesWithoutGuidesNeverBlindFetchEvenOnStrategyRoutes()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched,
			java.util.Set.of("Tombs of Amascut/Strategies"));
		prefetchPageRoute(wiki, "Varrock Diary", Router.NEED_STRATEGY);
		assertFalse(fetched.contains("Varrock Diary/Strategies"));
	}

	@Test
	public void unavailableIndexMeansNoPageGuideFetchAtAll()
	{
		// Pages were never blind-fetched before the index existed, so an
		// unavailable index must not start now.
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched, null);
		prefetchPageRoute(wiki, "Tombs of Amascut", Router.NEED_STRATEGY);
		assertFalse(fetched.contains("Tombs of Amascut/Strategies"));
	}

	@Test
	public void pageGuideIsAdvertisedWhenStrategyIsNotNeeded()
	{
		List<String> fetched = new ArrayList<>();
		WikiApi wiki = strategiesWiki(fetched,
			java.util.Set.of("Tombs of Amascut/Strategies"));
		List<String> facts = prefetchPageRoute(wiki, "Tombs of Amascut");
		String pageFact = facts.stream()
			.filter(f -> f.startsWith("### Page: Tombs of Amascut")).findFirst().orElse("");
		assertTrue(pageFact.contains("[Strategy guide page: Tombs of Amascut/Strategies]"));
		assertFalse(fetched.contains("Tombs of Amascut/Strategies"));
	}

	// --- sourcePage: which wiki page a fact title credits ---

	@Test
	public void wikiBackedFactTitlesNameTheirSourcePage()
	{
		assertEquals("Vorkath", Prefetcher.sourcePage("Monster info: Vorkath"));
		assertEquals("Vorkath/Strategies", Prefetcher.sourcePage("Strategy: Vorkath"));
		assertEquals("Smithing training", Prefetcher.sourcePage("Training guide: Smithing training"));
		assertEquals("Ironman Guide/Sailing",
			Prefetcher.sourcePage("Training guide: Ironman Guide/Sailing"));
		assertEquals("Varrock Diary", Prefetcher.sourcePage("Diary tasks (medium): Varrock Diary"));
		assertEquals("Adamantite bar", Prefetcher.sourcePage("How to obtain: Adamantite bar"));
	}

	// --- trainingGuide: account-type guide selection ---

	@Test
	public void trainingGuideMatchesAccountType()
	{
		assertEquals("Sailing training", Prefetcher.trainingGuide("Sailing", "NORMAL"));
		assertEquals("Ironman Guide/Sailing", Prefetcher.trainingGuide("Sailing", "IRONMAN"));
		assertEquals("Ironman Guide/Slayer",
			Prefetcher.trainingGuide("Slayer", "HARDCORE_GROUP_IRONMAN"));
		assertEquals("Ultimate Ironman Guide/Herblore",
			Prefetcher.trainingGuide("Herblore", "ULTIMATE_IRONMAN"));
	}

	@Test
	public void ironmanGuidesConsolidateAttackAndStrengthUnderMelee()
	{
		assertEquals("Ironman Guide/Melee", Prefetcher.trainingGuide("Attack", "IRONMAN"));
		assertEquals("Ultimate Ironman Guide/Melee",
			Prefetcher.trainingGuide("Strength", "ULTIMATE_IRONMAN"));
		assertEquals("Attack training", Prefetcher.trainingGuide("Attack", "NORMAL"));
	}

	@Test
	public void gameStateAndApiFactsCreditNoPage()
	{
		assertEquals(null, Prefetcher.sourcePage("Ownership: Scorching bow"));
		assertEquals(null, Prefetcher.sourcePage("GE price: Old school bond"));
		assertEquals(null, Prefetcher.sourcePage("XP math: Prayer"));
		assertEquals(null, Prefetcher.sourcePage(
			"Ownership of every item these facts mention (complete)"));
		assertEquals(null, Prefetcher.sourcePage(
			"Quest progress (authoritative, from the game client)"));
	}

	@Test
	public void unknownFactLabelsGoUnlinkedRatherThanMislinked()
	{
		assertEquals(null, Prefetcher.sourcePage("Some future fact: Thing"));
	}

	@Test
	public void gameStateHeadingsDisplayShortNames()
	{
		assertEquals("Quest progress", Prefetcher.displayTitle(
			"Quest progress (authoritative, from the game client)"));
		assertEquals("Ownership", Prefetcher.displayTitle(
			"Ownership of every item these facts mention (complete)"));
		assertEquals("Ownership", Prefetcher.displayTitle(
			"Ownership (lists cut for length; for items in neither list, decide "
				+ "what actually matters to the answer and verify just those in ONE "
				+ "batched search_owned_items call)"));
	}

	@Test
	public void wikiFactTitlesDisplayUnchanged()
	{
		assertEquals("Ownership: Scorching bow",
			Prefetcher.displayTitle("Ownership: Scorching bow"));
		assertEquals("Monster info: Vorkath",
			Prefetcher.displayTitle("Monster info: Vorkath"));
	}
}
