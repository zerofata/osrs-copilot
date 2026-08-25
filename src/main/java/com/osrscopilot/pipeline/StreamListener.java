package com.osrscopilot.pipeline;

/**
 * Receives incremental output while the pipeline runs, so the UI can render
 * the answer as it generates. Called from the pipeline thread -- implementors
 * must hop to their own thread (e.g. the Swing EDT) as needed.
 */
public interface StreamListener
{
	/** A fragment of visible answer text arrived. */
	void onDelta(String text);

	/** The text streamed so far was not the final answer; discard it from
	 * the display. */
	void onTurnDiscarded();

	/** Short progress note ("Checking wiki_page...") for a status line. */
	void onStatus(String status);
}
