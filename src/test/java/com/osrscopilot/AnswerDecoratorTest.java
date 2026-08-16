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
		return AnswerDecorator.build(new GameCapture(), entities, List.of(), Map.of(), null);
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
}
