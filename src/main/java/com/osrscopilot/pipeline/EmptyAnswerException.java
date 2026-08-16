package com.osrscopilot.pipeline;

import java.io.IOException;

/** The model finished without producing usable answer text -- empty content
 * or leaked tool-call markup -- even after a corrective retry. Surfaced as
 * its own type so the UI can offer a resubmit instead of a network error. */
public class EmptyAnswerException extends IOException
{
	public EmptyAnswerException()
	{
		super("the model returned no answer text");
	}
}
