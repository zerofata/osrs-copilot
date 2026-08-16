package com.osrscopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TranscriptModelTest
{
	@Test
	public void streamingCreatesOneAnswerBlockAndAppends()
	{
		TranscriptModel model = new TranscriptModel();
		model.beginQuestion("what is a whip");

		assertTrue("first delta creates the block", model.appendDelta("The abyssal "));
		assertFalse("later deltas reuse it", model.appendDelta("whip is..."));
		assertEquals(2, model.blocks().size());
		assertEquals("The abyssal whip is...", model.answerBlock().text.toString());
		assertTrue(model.isStreaming(model.answerBlock()));
	}

	@Test
	public void discardedPartialBecomesWorkingNoteAndTextClears()
	{
		TranscriptModel model = new TranscriptModel();
		model.beginQuestion("q");
		model.appendDelta("Let me look that up");
		assertTrue(model.discardPartial());

		TranscriptModel.Block block = model.answerBlock();
		assertEquals(0, block.text.length());
		assertEquals(java.util.List.of("Let me look that up"), block.working);
	}

	@Test
	public void completingTheAnswerCanonicalizesTheBlock()
	{
		TranscriptModel model = new TranscriptModel();
		model.beginQuestion("q");
		model.appendDelta("preamble");
		model.discardPartial();
		model.appendDelta("The ans");

		TranscriptModel.Block done = model.completeAnswer("The answer.", "<b>The answer.</b>", "tokens 5");
		assertNull("streaming is over", model.answerBlock());
		assertFalse(model.isStreaming(done));
		assertEquals("The answer.", done.text.toString());
		assertEquals("<b>The answer.</b>", done.decoratedHtml);
		assertEquals("tokens 5", done.meta);
		assertTrue("working notes served their purpose", done.working.isEmpty());
	}

	@Test
	public void errorRollsBackToBeforeTheQuestionAndReturnsIt()
	{
		TranscriptModel model = new TranscriptModel();
		model.seedExchange("old q", "old a", null, null);
		model.beginQuestion("new question");
		model.appendDelta("partial answer that failed");

		String question = model.rollback();
		assertEquals("new question", question);
		assertEquals("failures never pollute the conversation", 2, model.blocks().size());
		assertNull(model.answerBlock());
	}

	@Test
	public void seededExchangesAreNotRolledBack()
	{
		TranscriptModel model = new TranscriptModel();
		model.seedExchange("q1", "a1", "<b>a1</b>", "meta");
		model.rollback();
		assertEquals(2, model.blocks().size());
		assertEquals("a1", model.blocks().get(1).text.toString());
	}
}
