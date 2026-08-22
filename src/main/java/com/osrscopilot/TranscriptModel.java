package com.osrscopilot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The conversation transcript and its streaming state machine, free of any
 * Swing: which blocks exist, which one is receiving streamed text, what
 * rolls back on error. The panel renders from this and never mutates it
 * directly, so partial-answer and rollback behavior is unit-testable
 * without a display.
 */
class TranscriptModel
{
	/** One message in the transcript. */
	static final class Block
	{
		final String who;
		final StringBuilder text = new StringBuilder();
		/** Dim disclosure line under an answer: what the model was given. */
		String meta;
		/** Interim activity (tool-call preamble, "Looking up ..." lines),
		 * shown dim above the text so the model's work stays visible
		 * instead of vanishing; cleared when the final answer arrives. */
		final List<String> working = new ArrayList<>();
		/** Finished answers arrive pre-rendered and entity-decorated (wiki
		 * links, quest/item state colors); streaming text renders live from
		 * markdown until then. */
		String decoratedHtml;

		Block(String who)
		{
			this.who = who;
		}

		boolean isUser()
		{
			return "You".equals(who);
		}
	}

	private final List<Block> blocks = new ArrayList<>();
	// The copilot block currently receiving streamed text, if any.
	private Block answerBlock;
	// On error the block list rolls back to this size and the question
	// returns to the input box, so failures never pollute the conversation.
	private int restoreCount;
	private String pendingQuestion;

	List<Block> blocks()
	{
		return Collections.unmodifiableList(blocks);
	}

	boolean isEmpty()
	{
		return blocks.isEmpty();
	}

	/** Whether this block is the one currently streaming. */
	boolean isStreaming(Block block)
	{
		return block == answerBlock;
	}

	Block answerBlock()
	{
		return answerBlock;
	}

	/** The player submitted a question: record the rollback point and add
	 * the user block. */
	void beginQuestion(String question)
	{
		restoreCount = blocks.size();
		pendingQuestion = question;
		Block block = new Block("You");
		block.text.append(question);
		blocks.add(block);
	}

	/** Replay one completed exchange (panel rebuilds on theme/font change). */
	void seedExchange(String question, String answer, String decoratedHtml, String meta)
	{
		Block q = new Block("You");
		q.text.append(question);
		blocks.add(q);
		Block a = new Block("Copilot");
		a.text.append(answer);
		a.decoratedHtml = decoratedHtml;
		a.meta = meta;
		blocks.add(a);
		restoreCount = blocks.size();
	}

	/** A fragment of the streamed answer arrived. Returns true when this
	 * created the answer block (a structural change; the view must rebuild). */
	boolean appendDelta(String text)
	{
		boolean created = ensureAnswerBlock();
		answerBlock.text.append(text);
		return created;
	}

	/** Streamed text turned out not to be the answer (tool-call preamble).
	 * Keep it as a dim working note -- text that appears and then vanishes
	 * reads as a glitch -- but move it out of the answer text so the real
	 * answer streams in clean below it. Returns true if anything changed. */
	boolean discardPartial()
	{
		if (answerBlock == null)
		{
			return false;
		}
		String partial = answerBlock.text.toString().trim();
		if (!partial.isEmpty())
		{
			answerBlock.working.add(partial);
		}
		answerBlock.text.setLength(0);
		return true;
	}

	/** A pipeline progress note ("Looking up: ..."). Returns true when this
	 * created the answer block. */
	boolean addWorkingNote(String note)
	{
		boolean created = ensureAnswerBlock();
		answerBlock.working.add(note);
		return created;
	}

	/**
	 * Canonicalize the finished turn: the block ends up exactly the final
	 * answer; interim working notes have served their purpose (the meta
	 * line keeps the factual record of what ran). Returns the finished
	 * block for the view to re-render.
	 */
	Block completeAnswer(String answer, String decoratedHtml, String meta)
	{
		ensureAnswerBlock();
		Block block = answerBlock;
		answerBlock = null;
		block.working.clear();
		block.text.setLength(0);
		block.text.append(answer);
		block.decoratedHtml = decoratedHtml;
		block.meta = meta;
		return block;
	}

	/** Roll the conversation back to before the failed question. Returns
	 * the question text so the view can put it back in the input box. */
	String rollback()
	{
		while (blocks.size() > restoreCount)
		{
			blocks.remove(blocks.size() - 1);
		}
		answerBlock = null;
		return pendingQuestion;
	}

	void clear()
	{
		blocks.clear();
		answerBlock = null;
		restoreCount = 0;
		// A stale pending question would otherwise resurface in the input
		// box if the first post-clear question fails and rolls back.
		pendingQuestion = null;
	}

	/** Returns true when the answer block had to be created. */
	private boolean ensureAnswerBlock()
	{
		if (answerBlock != null)
		{
			return false;
		}
		answerBlock = new Block("Copilot");
		blocks.add(answerBlock);
		return true;
	}
}
