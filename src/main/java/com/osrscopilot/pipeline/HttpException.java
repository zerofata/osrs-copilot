package com.osrscopilot.pipeline;

import java.io.IOException;

/**
 * Non-2xx HTTP response, with the status code and the server's own error
 * message (parsed from an OpenAI-style {"error":{"message":...}} body when
 * present) available as fields instead of buried in a string.
 */
public class HttpException extends IOException
{
	public final int code;
	public final String serverMessage;

	HttpException(int code, String url, String serverMessage)
	{
		super("HTTP " + code + " from " + url
			+ (serverMessage.isEmpty() ? "" : (": " + serverMessage)));
		this.code = code;
		this.serverMessage = serverMessage;
	}
}
