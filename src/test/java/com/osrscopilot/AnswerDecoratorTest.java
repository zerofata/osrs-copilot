package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
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
		return AnswerDecorator.build(new GameCapture(), entities, List.of(), List.of(), Map.of(), null);
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
		return AnswerDecorator.build(new GameCapture(), entities, monsters, List.of(), Map.of(), null);
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

	private static AnswerDecorator breadDecorator()
	{
		GameCapture cap = new GameCapture();
		cap.bank = List.of(Map.of("name", "Bread", "quantity", 9, "id", 2309));
		return AnswerDecorator.build(cap, new EntityResolver.Resolution(),
			List.of(), List.of(), Map.of(), null);
	}

	@Test
	public void idiomsAreNotDecorated()
	{
		String html = breadDecorator()
			.decorate("Port tasks are the bread and butter of early training.");
		assertFalse(html.contains("<a"));
	}

	@Test
	public void literalMentionStillDecorated()
	{
		String html = breadDecorator().decorate("Buy bread from the baker in Ardougne.");
		assertTrue(html.contains(">bread</font>"));
	}
}
