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
			public List<String[]> knownItemNames()
			{
				List<String[]> out = new ArrayList<>();
				for (String n : vocabulary)
				{
					out.add(new String[]{n, n});
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
