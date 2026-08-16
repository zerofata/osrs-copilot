package com.osrscopilot.pipeline;

import java.io.IOException;

/** The model finished without producing usable answer text -- empty content
 * or leaked tool-call markup -- even after a corrective retry. Surfaced as
 * its own type so the UI can offer a resubmit instead of a network error,
 * and carries the tool trace: an errored turn leaves nothing else to
 * diagnose with. */
public class EmptyAnswerException extends IOException
{
	public EmptyAnswerException(int turns, String toolNames, boolean truncatedByLength)
	{
		super("no answer text after " + turns + " tool turn" + (turns == 1 ? "" : "s")
			+ (toolNames.isEmpty() ? "" : " (" + toolNames + ")")
			+ (truncatedByLength
				? " -- the response hit the max-token limit before any answer text; "
					+ "raising Max tokens in the copilot settings gives the model room"
				: ""));
	}
}
