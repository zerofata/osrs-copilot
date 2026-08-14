package com.osrscopilot;

/** Config-facing theme choice; each maps to a {@link Theme} palette. */
public enum PanelTheme
{
	GAME_NATIVE("game-native", "Game native"),
	MODERN("modern", "Modern"),
	PARCHMENT("parchment", "Parchment");

	final String key;
	private final String label;

	PanelTheme(String key, String label)
	{
		this.key = key;
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
