package com.osrscopilot.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The canonicalization pass the weekly snapshot job runs over raw
 * infobox_item rows. */
public class VocabSnapshotToolTest
{
	private static final Set<String> ENGLISH = Set.of("key", "bread", "cake");
	private static final Map<String, JsonObject> GE = Map.of(
		"bread", geEntry(9999, 6000, 12),
		"fire cape", geEntry(6570, null, null));

	private static JsonObject geEntry(int id, Integer limit, Integer highalch)
	{
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		if (limit != null)
		{
			o.addProperty("limit", limit);
		}
		if (highalch != null)
		{
			o.addProperty("highalch", highalch);
		}
		return o;
	}

	private static JsonObject row(String name, String page, String... ids)
	{
		JsonObject o = new JsonObject();
		o.addProperty("item_name", name);
		o.addProperty("page_name", page);
		if (ids.length > 0)
		{
			JsonArray arr = new JsonArray();
			for (String id : ids)
			{
				arr.add(id);
			}
			o.add("item_id", arr);
		}
		return o;
	}

	private static ItemDescriptor only(String name, List<ItemDescriptor> items)
	{
		ItemDescriptor found = null;
		for (ItemDescriptor it : items)
		{
			if (it.name.equals(name))
			{
				assertNull("duplicate descriptor for " + name, found);
				found = it;
			}
		}
		return found;
	}

	@Test
	public void parsesDecimalGameIds()
	{
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(
			List.of(row("Crowbar", "Crowbar", "31807")), ENGLISH, GE);
		assertEquals(Integer.valueOf(31807), only("Crowbar", items).id);
	}

	@Test
	public void skipsNonGameIdsAndMissingIds()
	{
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(List.of(
			row("Ancient staff", "Ancient staff", "beta4675", "4675"),
			row("Quest scroll", "Quest scroll")), ENGLISH, GE);
		assertEquals(Integer.valueOf(4675), only("Ancient staff", items).id);
		assertNull(only("Quest scroll", items).id);
	}

	@Test
	public void duplicateNamesResolveToTheRealPage()
	{
		// The animation variant is a fake page; the LMS variant loses to
		// the exact-name page. One descriptor each, deterministically.
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(List.of(
			row("Fire cape", "Fire cape (animation item)", "10566"),
			row("Fire cape", "Fire cape", "6570"),
			row("Saradomin brew(4)", "Saradomin brew (Last Man Standing)", "23575"),
			row("Saradomin brew(4)", "Saradomin brew", "6685")), ENGLISH, GE);
		assertEquals(2, items.size());
		assertEquals(Integer.valueOf(6570), only("Fire cape", items).id);
		ItemDescriptor brew = only("Saradomin brew(4)", items);
		assertEquals("Saradomin brew", brew.page);
		assertEquals(Integer.valueOf(6685), brew.id);
	}

	@Test
	public void removedContentIsExcluded()
	{
		JsonObject removed = row("Half full wine jug", "Half full wine jug", "1989");
		removed.addProperty("removal_date", "2004-01-29");
		assertNull(only("Half full wine jug",
			VocabSnapshotTool.canonicalItems(List.of(removed), ENGLISH, GE)));
	}

	@Test
	public void bareDictionaryWordsStayOnlyWhenTradeable()
	{
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(List.of(
			row("Key", "Key (item)", "409"),
			row("Bread", "Bread", "2309")), ENGLISH, GE);
		assertNull("untradeable dictionary word must not claim prose", only("Key", items));
		assertEquals(Integer.valueOf(2309), only("Bread", items).id);
	}

	@Test
	public void geFieldsRideOnTradeableDescriptors()
	{
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(List.of(
			row("Bread", "Bread", "2309"),
			row("Crowbar", "Crowbar", "31807")), ENGLISH, GE);
		ItemDescriptor bread = only("Bread", items);
		assertTrue(bread.tradeable);
		assertEquals("wiki id beats the GE id", Integer.valueOf(2309), bread.id);
		assertEquals(Integer.valueOf(6000), bread.limit);
		assertEquals(Integer.valueOf(12), bread.highAlch);
		ItemDescriptor crowbar = only("Crowbar", items);
		assertFalse(crowbar.tradeable);
		assertNull(crowbar.limit);
		assertNull(crowbar.highAlch);
	}

	@Test
	public void geIdBackfillsARowTheWikiHasNotAssignedYet()
	{
		List<ItemDescriptor> items = VocabSnapshotTool.canonicalItems(
			List.of(row("Fire cape", "Fire cape")), ENGLISH, GE);
		ItemDescriptor cape = only("Fire cape", items);
		assertEquals(Integer.valueOf(6570), cape.id);
		assertNull("absent GE fields stay null", cape.limit);
	}

	@Test
	public void outputIsDeterministicRegardlessOfInputOrder()
	{
		List<JsonObject> rows = new ArrayList<>(List.of(
			row("Fire cape", "Fire cape (animation item)", "10566"),
			row("Fire cape", "Fire cape", "6570"),
			row("Crowbar", "Crowbar", "31807"),
			row("Abyssal whip", "Abyssal whip", "4151")));
		List<ItemDescriptor> a = VocabSnapshotTool.canonicalItems(rows, ENGLISH, GE);
		Collections.reverse(rows);
		List<ItemDescriptor> b = VocabSnapshotTool.canonicalItems(rows, ENGLISH, GE);
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++)
		{
			assertEquals(a.get(i).name, b.get(i).name);
			assertEquals(a.get(i).page, b.get(i).page);
			assertEquals(a.get(i).id, b.get(i).id);
		}
	}
}
