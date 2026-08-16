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
	/** WikiApi whose item vocabulary is a fixed list; nothing fetches. */
	private static WikiApi vocabOf(String... names)
	{
		return new WikiApi(null, new Gson(), new File("build/tmp"))
		{
			@Override
			public List<String[]> knownItemNames()
			{
				List<String[]> out = new ArrayList<>();
				for (String n : names)
				{
					out.add(new String[]{n, n});
				}
				return out;
			}
		};
	}

	private static List<String> ownershipFacts(WikiApi wiki, List<String> pageFacts,
		Map<String, Long> ownedItems)
	{
		Map<String, long[]> owned = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : ownedItems.entrySet())
		{
			owned.put(e.getKey().toLowerCase(java.util.Locale.ROOT), new long[]{e.getValue()});
			names.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getKey());
		}
		List<String> facts = new ArrayList<>(pageFacts);
		new Prefetcher(wiki, new Gson()).addOwnershipFromFacts(facts, owned, names);
		return facts.subList(pageFacts.size(), facts.size());
	}

	@Test
	public void ownedAndLackedAreBothStatedPositively()
	{
		List<String> added = ownershipFacts(
			vocabOf("Abyssal whip", "Twisted bow", "Dragon dagger"),
			List.of("### Recommended equipment\nWeapon: Abyssal whip or Twisted bow"),
			Map.of("Abyssal whip", 1L));
		assertEquals(1, added.size());
		String block = added.get(0);
		assertTrue(block.contains("OWNED: Abyssal whip"));
		assertTrue("fact-mentioned unowned items must be named, not implied",
			block.contains("NOT OWNED") && block.contains("Twisted bow"));
		assertFalse("unmentioned items stay out of both lists",
			block.contains("Dragon dagger"));
		assertTrue("untruncated block claims completeness",
			block.contains("complete both ways"));
	}

	@Test
	public void variantNamedGearMatchesTheCanonicalWikiName()
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
	public void singleSharedWordIsNotAMention()
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
	public void unavailableVocabularyDropsTheCompletenessClaim()
	{
		// wiki=null: knownItemNames() fails, so the lacked list is unknown
		// and the block may not claim completeness.
		List<String> added = ownershipFacts(
			null,
			List.of("### Recommended equipment\nWeapon: Abyssal whip"),
			Map.of("Abyssal whip", 1L));
		assertEquals(1, added.size());
		assertFalse("without the vocabulary the completeness claim would be false",
			added.get(0).contains("complete both ways"));
	}

	@Test
	public void truncatedLackedListDropsTheCompletenessClaim()
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
			added.get(0).contains("complete both ways"));
	}

	@Test
	public void doseAndChargeVariantsStillMatchProse()
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
}
