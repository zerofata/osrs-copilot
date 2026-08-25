package com.osrscopilot.pipeline;

import java.io.IOException;

/** Non-2xx HTTP response, with the status code and the server's error
 * message as fields. */
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
