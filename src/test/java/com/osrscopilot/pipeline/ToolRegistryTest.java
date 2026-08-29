package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ToolRegistryTest
{
	private final ToolRegistry registry = new ToolRegistry(null, new Gson());

	private static List<String> names(JsonArray specs)
	{
		List<String> names = new ArrayList<>();
		for (JsonElement e : specs)
		{
			names.add(e.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		}
		return names;
	}

	@Test
	public void everySpecIsAWellFormedFunctionDeclaration()
	{
		for (JsonElement e : registry.buildToolSpecs(true))
		{
			JsonObject spec = e.getAsJsonObject();
			assertEquals("function", spec.get("type").getAsString());
			JsonObject fn = spec.getAsJsonObject("function");
			assertTrue(fn.has("name") && fn.has("description"));
			JsonObject params = fn.getAsJsonObject("parameters");
			assertEquals("object", params.get("type").getAsString());
			// Every declared parameter is required, and vice versa.
			String required = params.getAsJsonArray("required").get(0).getAsString();
			assertTrue(params.getAsJsonObject("properties").has(required));
		}
	}

	@Test
	public void ownedItemSearchOnlyOfferedWhenOwnershipIsNotAlreadyInContext()
	{
		// Offered only when neither the inlined bank nor a complete
		// ownership fact already answers it: a tool over visible data
		// invites redundant lookups.
		assertTrue(names(registry.buildToolSpecs(true)).contains("search_owned_items"));
		assertFalse(names(registry.buildToolSpecs(false)).contains("search_owned_items"));
	}

	@Test
	public void specsAndImplementationsStayInLockstep()
	{
		GameCapture cap = new GameCapture();
		for (boolean offerOwnedSearch : new boolean[]{true, false})
		{
			List<String> specNames = names(registry.buildToolSpecs(offerOwnedSearch));
			assertEquals(specNames.size(),
				registry.buildTools(cap, Map.of(), Map.of(), offerOwnedSearch, true).size());
			assertTrue(registry.buildTools(cap, Map.of(), Map.of(), offerOwnedSearch, true)
				.keySet().containsAll(specNames));
		}
	}

	// ---- xp_to_level -----------------------------------------------------

	private static Object xpCall(Map<String, Integer> skillXp, String skill, Object target)
		throws Exception
	{
		GameCapture cap = new GameCapture();
		cap.skillXp = skillXp;
		JsonObject args = new JsonObject();
		args.addProperty("skill", skill);
		if (target instanceof Number)
		{
			args.addProperty("target_level", (Number) target);
		}
		return new ToolRegistry(null, new Gson())
			.buildTools(cap, Map.of(), Map.of(), false, false)
			.get("xp_to_level").call(args);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void xpToLevelComputesTheExactGapFromTheOfficialTable() throws Exception
	{
		// The real failure this guards: at 787,792 Herblore XP (level 70)
		// asking for 72, the model doubled the next-level gap and said ~54k.
		// The official table says level 72 = 899,257 total.
		Map<String, Object> out = (Map<String, Object>)
			xpCall(Map.of("Herblore", 787_792), "herblore", 72);
		assertEquals("Herblore", out.get("skill"));
		assertEquals(70, out.get("current_level"));
		assertEquals(111_465, out.get("xp_needed"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void xpToLevelReportsAnAlreadyReachedTargetInsteadOfANegativeGap() throws Exception
	{
		Map<String, Object> out = (Map<String, Object>)
			xpCall(Map.of("Herblore", 787_792), "Herblore", 60);
		assertEquals(0, out.get("xp_needed"));
		assertTrue(out.containsKey("note"));
	}

	@Test
	public void xpToLevelRejectsUnknownSkillsMissingTargetsAndAbsentCaptureData()
		throws Exception
	{
		assertTrue(((Map<?, ?>) xpCall(Map.of("Herblore", 1), "Herblaw", 72))
			.containsKey("error"));
		assertTrue(((Map<?, ?>) xpCall(Map.of("Herblore", 1), "Herblore", null))
			.containsKey("error"));
		assertTrue(((Map<?, ?>) xpCall(Map.of("Herblore", 1), "Herblore", 100))
			.containsKey("error"));
		assertTrue(((Map<?, ?>) xpCall(null, "Herblore", 72)).containsKey("error"));
	}

	// ---- owned-item search ----------------------------------------------

	private static Object searchOwned(WikiApi wiki, Map<String, Long> ownedItems,
		String... queries) throws Exception
	{
		Map<String, long[]> owned = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Long> e : ownedItems.entrySet())
		{
			owned.put(e.getKey().toLowerCase(java.util.Locale.ROOT), new long[]{e.getValue()});
			names.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getKey());
		}
		JsonArray list = new JsonArray();
		for (String q : queries)
		{
			list.add(q);
		}
		JsonObject args = new JsonObject();
		args.add("queries", list);
		return new ToolRegistry(wiki, new Gson())
			.buildTools(new GameCapture(), owned, names, true, false)
			.get("search_owned_items").call(args);
	}

	@Test
	public void inventedNameIsFlaggedInsteadOfReadingAsNotOwned() throws Exception
	{
		WikiApi wiki = stubWiki("", Map.of(), "Cannon base", "Cannon stand");
		Map<?, ?> result = (Map<?, ?>) searchOwned(wiki,
			Map.of("Cannon base", 1L), "Reinforced cannon frame");
		assertTrue(String.valueOf(result.get("Reinforced cannon frame"))
			.contains("not a valid OSRS item name"));
	}

	@Test
	public void validNameWithNoCopiesStaysAVerifiedZero() throws Exception
	{
		WikiApi wiki = stubWiki("", Map.of(), "Cannon base", "Saradomin brew(4)");
		Map<?, ?> result = (Map<?, ?>) searchOwned(wiki, Map.of(),
			"Cannon base", "Saradomin brew");
		assertEquals("no match in bank/inventory/equipment", result.get("Cannon base"));
		// Base names of versioned items are how prose refers to them.
		assertEquals("no match in bank/inventory/equipment", result.get("Saradomin brew"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void collectiveTermFindsOwnedVariants() throws Exception
	{
		WikiApi wiki = stubWiki("", Map.of(), "Imbued Zamorak cape", "Cannon base");
		Map<?, ?> result = (Map<?, ?>) searchOwned(wiki,
			Map.of("Imbued Zamorak cape", 1L, "Cannon base", 1L),
			"Imbued god cape", "Dwarf multicannon");
		List<Map<String, Object>> capes =
			(List<Map<String, Object>>) result.get("Imbued god cape");
		assertEquals("Imbued Zamorak cape", capes.get(0).get("item"));
		List<Map<String, Object>> cannon =
			(List<Map<String, Object>>) result.get("Dwarf multicannon");
		assertEquals("Cannon base", cannon.get(0).get("item"));
	}

	@Test
	public void unownedCollectiveIsAVerifiedZeroNotAnInvalidName() throws Exception
	{
		// Every variant was searched, so the miss is real -- unlike an
		// invented name, which stays flagged.
		WikiApi wiki = stubWiki("", Map.of(), "Cannon base");
		Map<?, ?> result = (Map<?, ?>) searchOwned(wiki, Map.of(), "God cape");
		assertEquals("no match in bank/inventory/equipment", result.get("God cape"));
	}

	@Test
	public void substringHitsBypassValidation() throws Exception
	{
		WikiApi wiki = stubWiki("", Map.of(), "Cannon base");
		Map<?, ?> result = (Map<?, ?>) searchOwned(wiki,
			Map.of("Cannon base", 1L, "Cannon stand", 1L), "cannon");
		assertTrue(result.get("cannon") instanceof List);
		assertEquals(2, ((List<?>) result.get("cannon")).size());
	}

	// ---- ownership annotation on tool results ---------------------------

	/** WikiApi serving fixed content and a fixed item vocabulary. */
	private static WikiApi stubWiki(String pageText, Map<String, Object> drops,
		String... vocabulary)
	{
		return new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			String page(String title)
			{
				return pageText;
			}

			@Override
			Map<String, Object> monsterDrops(String name)
			{
				return drops;
			}

			@Override
			public List<ItemDescriptor> itemCatalog()
			{
				List<ItemDescriptor> out = new ArrayList<>();
				for (String n : vocabulary)
				{
					out.add(new ItemDescriptor(n, n, null, false, null, null));
				}
				return out;
			}
		};
	}

	private static Object call(WikiApi wiki, String tool, String argKey, String argValue,
		Map<String, Long> ownedItems, boolean annotate) throws Exception
	{
		Map<String, long[]> owned = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Long> e : ownedItems.entrySet())
		{
			owned.put(e.getKey().toLowerCase(java.util.Locale.ROOT), new long[]{e.getValue()});
			names.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getKey());
		}
		JsonObject args = new JsonObject();
		args.addProperty(argKey, argValue);
		return new ToolRegistry(wiki, new Gson())
			.buildTools(new GameCapture(), owned, names, false, annotate)
			.get(tool).call(args);
	}

	@Test
	public void toolResultsCarryTheOwnershipSliceForItemsTheyMention() throws Exception
	{
		// A page fetched mid-loop names items the prefetched facts never
		// mentioned. The result itself must state ownership, or the model
		// can only hedge ("if you have one...").
		WikiApi wiki = stubWiki(
			"The fastest route is the Giantsoul amulet teleport. "
				+ "Alternatively use a Twisted bow for the boss.",
			null, "Giantsoul amulet", "Twisted bow");
		Object out = call(wiki, "wiki_page", "title", "Asgarnian Ice Dungeon",
			Map.of("Giantsoul amulet", 1L), true);
		String text = (String) out;
		assertTrue("original content is preserved",
			text.startsWith("The fastest route"));
		assertTrue(text.contains("OWNED: Giantsoul amulet"));
		assertTrue("mentioned unowned items are named, not implied",
			text.contains("NOT OWNED") && text.contains("Twisted bow"));
		assertTrue(text.contains("complete both ways"));
	}

	@Test
	public void jsonToolResultsAreAnnotatedThroughTheirSerializedForm() throws Exception
	{
		WikiApi wiki = stubWiki(null,
			Map.of("drops", List.of("Draconic visage", "Skeletal visage")),
			"Draconic visage", "Skeletal visage");
		Object out = call(wiki, "monster_drops", "name", "Vorkath",
			Map.of("Draconic visage", 1L), true);
		String text = (String) out;
		assertTrue("the JSON payload survives for the model to read",
			text.contains("\"drops\""));
		assertTrue(text.contains("OWNED: Draconic visage"));
		assertTrue(text.contains("NOT OWNED") && text.contains("Skeletal visage"));
	}

	@Test
	public void inlinedBankDisablesTheAnnotation() throws Exception
	{
		// With the bank inlined in the prompt the model already sees
		// everything; annotating would only repeat it.
		WikiApi wiki = stubWiki("Bring a Giantsoul amulet.", null, "Giantsoul amulet");
		Object out = call(wiki, "wiki_page", "title", "Anything",
			Map.of("Giantsoul amulet", 1L), false);
		assertEquals("Bring a Giantsoul amulet.", out);
	}

	@Test
	public void errorResultsAndItemFreeResultsPassThroughUntouched() throws Exception
	{
		WikiApi wiki = stubWiki(null, null, "Giantsoul amulet");
		Object error = call(wiki, "wiki_page", "title", "No such page",
			Map.of("Giantsoul amulet", 1L), true);
		assertTrue("a lookup failure stays a structured error", error instanceof Map);

		WikiApi prose = stubWiki("The quest begins in Lumbridge.", null, "Giantsoul amulet");
		Object plain = call(prose, "wiki_page", "title", "Some quest",
			Map.of("Giantsoul amulet", 1L), true);
		assertEquals("The quest begins in Lumbridge.", plain);
	}
}
