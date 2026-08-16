package com.osrscopilot.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ToolRegistryTest
{
	private final ToolRegistry registry = new ToolRegistry(null);

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
				registry.buildTools(cap, java.util.Map.of(), java.util.Map.of(), offerOwnedSearch).size());
			assertTrue(registry.buildTools(cap, java.util.Map.of(), java.util.Map.of(), offerOwnedSearch)
				.keySet().containsAll(specNames));
		}
	}
}
