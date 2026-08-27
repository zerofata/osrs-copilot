package com.osrscopilot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("osrscopilot")
public interface CopilotConfig extends Config
{
	@ConfigSection(
		name = "LLM endpoint",
		description = "Any OpenAI-compatible chat completions API.",
		position = 0
	)
	String endpointSection = "endpoint";

	@ConfigSection(
		name = "Sampling",
		description = "Model sampling settings",
		position = 1
	)
	String samplingSection = "sampling";

	@ConfigSection(
		name = "Context & privacy",
		description = "What game state is sent with your questions",
		position = 2
	)
	String contextSection = "context";

	@ConfigSection(
		name = "Appearance",
		description = "How the chat panel looks",
		position = 3
	)
	String appearanceSection = "appearance";

	@ConfigSection(
		name = "Developer",
		description = "Diagnostics; leave off for normal use",
		position = 4,
		closedByDefault = true
	)
	String devSection = "developer";

	// ------------------------------------------------------------------
	// Endpoint
	// ------------------------------------------------------------------

	// The third-party-server disclosure is the Plugin Hub install warning;
	// nothing is sent to an LLM until the user configures an endpoint here.
	@ConfigItem(
		keyName = "provider",
		name = "Provider",
		description = "OpenRouter, or any custom OpenAI-compatible endpoint",
		section = endpointSection,
		position = 0
	)
	default LlmProvider provider()
	{
		return LlmProvider.OPENROUTER;
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Bearer token for the endpoint (leave empty if not required)",
		section = endpointSection,
		position = 1,
		secret = true
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "model",
		name = "Model",
		description = "Model name as the endpoint expects it, e.g. deepseek/deepseek-chat on OpenRouter",
		section = endpointSection,
		position = 2
	)
	default String model()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "Custom base URL",
		description = "OpenAI-compatible base URL, used only when Provider is Custom endpoint, "
			+ "e.g. http://localhost:11434/v1",
		section = endpointSection,
		position = 3
	)
	default String apiBaseUrl()
	{
		return "";
	}

	// ------------------------------------------------------------------
	// Sampling
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "temperature",
		name = "Temperature",
		description = "Sampling temperature (0.0-2.0). Lower = more factual",
		section = samplingSection,
		position = 0
	)
	default double temperature()
	{
		return 0.2;
	}

	@ConfigItem(
		keyName = "maxTokens",
		name = "Max tokens",
		description = "Per-response token cap sent to the endpoint. Reasoning models "
			+ "spend their thinking from this budget too; if answers come back "
			+ "empty with a token-limit error, raise this.",
		section = samplingSection,
		position = 1
	)
	@Range(min = 256, max = 32768)
	default int maxTokens()
	{
		return 8192;
	}

	@ConfigItem(
		keyName = "maxToolTurns",
		name = "Max tool turns",
		description = "How many rounds of tool calls the model may make before being forced to answer",
		section = samplingSection,
		position = 2
	)
	// The heaviest questions (budget + ownership) take three research turns.
	@Range(min = 1, max = 8)
	default int maxToolTurns()
	{
		return 4;
	}

	// ------------------------------------------------------------------
	// Context & privacy
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "sendBank",
		name = "Send bank contents",
		description = "Include your bank in questions so answers know what you own",
		section = contextSection,
		position = 0
	)
	default boolean sendBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sendRecentEvents",
		name = "Send recent events",
		description = "Include recent drops, kills, and game messages as question context",
		section = contextSection,
		position = 1
	)
	default boolean sendRecentEvents()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Appearance
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "theme",
		name = "Theme",
		description = "Visual style of the chat panel: game-native dark, modern neutral, or parchment",
		section = appearanceSection,
		position = 0
	)
	default PanelTheme theme()
	{
		return PanelTheme.GAME_NATIVE;
	}

	@ConfigItem(
		keyName = "fontSize",
		name = "Text size",
		description = "Base size of chat message text, in pixels",
		section = appearanceSection,
		position = 1
	)
	@Range(min = 11, max = 24)
	default int fontSize()
	{
		return 13;
	}

	@ConfigItem(
		keyName = "simpleMode",
		name = "Simple answers",
		description = "Short plain-text replies without headings, tables, or lists. "
			+ "Also togglable from the chat panel.",
		section = appearanceSection,
		position = 2
	)
	default boolean simpleMode()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Developer
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "logEvents",
		name = "Log events to disk",
		description = "Append gameplay events to a JSONL log (diagnostics)",
		section = devSection,
		position = 0
	)
	default boolean logEvents()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logMenuClicks",
		name = "Log menu clicks",
		description = "Include every menu click in the event log (noisy)",
		section = devSection,
		position = 1
	)
	default boolean logMenuClicks()
	{
		return false;
	}
}
