package com.osrscopilot.pipeline;

import java.io.IOException;

/** The model finished without usable answer text even after a corrective
 * retry. Its own type so the UI can offer a resubmit; carries the tool
 * trace. */
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
