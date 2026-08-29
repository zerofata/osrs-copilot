package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.ItemDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnswerDecoratorTest
{
	private static AnswerDecorator bankDecorator()
	{
		EntityResolver.Resolution entities = new EntityResolver.Resolution();
		entities.pages.add("Bank");
		return AnswerDecorator.build(new GameCapture(), entities, List.of(), List.of(), null);
	}

	@Test
	public void fragmentOfLargerProperNounIsNotDecorated()
	{
		String html = bankDecorator().decorate(
			"Your closest bank is the Bank of Gielinor in Yanille.");
		// The standalone lowercase mention still links...
		assertTrue(html.contains("closest <a"));
		// ...but "Bank" inside the invented proper noun must stay bare.
		assertTrue(html.contains("Bank of Gielinor"));
		assertFalse(html.contains(">Bank</font>"));
	}

	@Test
	public void fragmentGuardStopsAtPhraseEnd()
	{
		// "Bank" followed by lowercase prose is a whole name, not a fragment.
		String html = bankDecorator().decorate("The Bank is north of the anvil.");
		assertTrue(html.contains(">Bank</font>"));
	}

	@Test
	public void connectorWithoutCapitalDoesNotSuppress()
	{
		String html = bankDecorator().decorate("Use the bank of the city centre.");
		assertTrue(html.contains(">bank</font>"));
	}

	private static AnswerDecorator demonDecorator(boolean withMonsterVocab)
	{
		EntityResolver.Resolution entities = new EntityResolver.Resolution();
		entities.pages.add("Demon");
		List<String> monsters = withMonsterVocab ? List.of("Greater demon") : List.of();
		return AnswerDecorator.build(new GameCapture(), entities, monsters, List.of(), null);
	}

	@Test
	public void tailOfLargerProperNounIsNotDecorated()
	{
		// "demons" is the tail of "Greater demons"; the Demon page would
		// mislabel it, and no rule knows the longer name.
		String html = demonDecorator(false)
			.decorate("Kill the Greater demons in the Catacombs.");
		assertFalse(html.contains("<a"));
	}

	@Test
	public void sentenceOpeningCapitalDoesNotSuppress()
	{
		// "The" is capitalized only because it opens the sentence.
		String html = demonDecorator(false).decorate("The demons there are aggressive.");
		assertTrue(html.contains(">demons</font>"));
	}

	@Test
	public void monsterVocabularyClaimsWholePhrase()
	{
		// The vocabulary knows "Greater demon" even though the route never
		// resolved it; longest-first beats the "Demon" page fragment.
		String html = demonDecorator(true).decorate("Kill the Greater demons there.");
		assertTrue(html.contains(">Greater demons</font>"));
	}

	@Test
	public void bareUntradeableYieldsToALongerNameInTheSameAnswer()
	{
		// "the totem" beside "Dark totem pieces" means the dark totem; the
		// Legends' Quest "Totem" item must not claim it.
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Totem", "Totem", 1857, false, null, null),
			new ItemDescriptor("Dark totem", "Dark totem", 19685, false, null, null));
		String html = AnswerDecorator.build(new GameCapture(),
			new EntityResolver.Resolution(), List.of(), items, null)
			.decorate("Assemble the Dark totem pieces, then use the totem on the altar.");
		assertTrue(html.contains(">Dark totem</font>"));
		assertFalse(html.contains(">totem</font>"));
	}

	@Test
	public void tradeableBareWordDecoratesBesideItsLongerCousin()
	{
		// Cooked shark is literally the item "Shark"; a cooking answer
		// mentioning "Raw shark" must not suppress it.
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Shark", "Shark", 385, true, null, null),
			new ItemDescriptor("Raw shark", "Raw shark", 383, true, null, null));
		String html = AnswerDecorator.build(new GameCapture(),
			new EntityResolver.Resolution(), List.of(), items, null)
			.decorate("Cook raw sharks on the range until you have 100 sharks.");
		assertTrue(html.contains(">raw sharks</font>"));
		assertTrue(html.contains(">sharks</font>"));
	}

	@Test
	public void bareUntradeableWithNoLongerCousinStillDecorates()
	{
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Crowbar", "Crowbar", 31807, false, null, null));
		String html = AnswerDecorator.build(new GameCapture(),
			new EntityResolver.Resolution(), List.of(), items, null)
			.decorate("The quest rewards the crowbar you need.");
		assertTrue(html.contains(">crowbar</font>"));
	}

	@Test
	public void ownedNameOutsideTheCatalogueDoesNotDecorate()
	{
		// The snapshot excludes "Coins" as an unsafe bare word; owning
		// coins must not turn every gp amount into an item reference.
		GameCapture cap = new GameCapture();
		cap.bank = List.of(Map.of("name", "Coins", "quantity", 2_304_921, "id", 995));
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Shark", "Shark", 385, true, null, null));
		String html = AnswerDecorator.build(cap, new EntityResolver.Resolution(),
			List.of(), items, null)
			.decorate("The upgrade costs 100,000 coins at the shop.");
		assertFalse(html.contains("<a"));
	}

	@Test
	public void ownedVersionedItemPassesTheBaseNamePolicy()
	{
		GameCapture cap = new GameCapture();
		cap.inventory = List.of(Map.of("name", "Prayer potion(4)", "quantity", 3));
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Prayer potion(4)", "Prayer potion", 2434, true, null, null));
		String html = AnswerDecorator.build(cap, new EntityResolver.Resolution(),
			List.of(), items, null)
			.decorate("Bring a Prayer potion for the fight.");
		assertTrue(html.contains(">Prayer potion</font>"));
		assertTrue(html.contains("carried"));
	}

	@Test
	public void versionedNameLinksToItsCanonicalPage()
	{
		List<ItemDescriptor> items = List.of(
			new ItemDescriptor("Fire cape (l)", "Fire cape", null, false, null, null));
		String html = AnswerDecorator.build(new GameCapture(),
			new EntityResolver.Resolution(), List.of(), items, null)
			.decorate("Bring your Fire cape (l) along.");
		assertTrue(html.contains("/w/Fire_cape'"));
		assertTrue(html.contains(">Fire cape (l)</font>"));
	}
}
