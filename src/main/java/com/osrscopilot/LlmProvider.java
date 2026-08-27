package com.osrscopilot;

/** Config-facing endpoint choice. OpenRouter carries its fixed base URL;
 * Custom uses the user-entered one. */
public enum LlmProvider
{
	OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
	CUSTOM("Custom endpoint", null);

	private final String label;
	/** Fixed base URL, or null when the user supplies it. */
	public final String baseUrl;

	LlmProvider(String label, String baseUrl)
	{
		this.label = label;
		this.baseUrl = baseUrl;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
