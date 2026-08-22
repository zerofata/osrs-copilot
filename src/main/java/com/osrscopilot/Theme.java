package com.osrscopilot;

import java.awt.Color;
import java.awt.Font;
import net.runelite.client.ui.FontManager;

/**
 * One visual identity for the whole presentation stack. The chat panel, the
 * markdown renderer, and the entity decorator all read the active theme, so
 * chrome, answer typography, and entity state colors always speak the same
 * visual language -- the design flaw a theme object exists to prevent is
 * modern chrome wrapped around game-styled content.
 *
 * The pipeline never sees this class; presentation is swappable wholesale.
 */
final class Theme
{
	// -- Chrome -----------------------------------------------------------
	/** Font for wordmark, speaker labels, and buttons; null = default sans.
	 * RuneLite ships the actual RuneScape fonts, the single cheapest
	 * authenticity lever available. */
	Font chromeFont;
	Font statusFont;
	Color accent;
	/** Transcript background (behind the cards). */
	Color surface;
	/** Chrome background (behind the header buttons and input row). */
	Color chromeBg;
	Color buttonBg;
	Color buttonHover;
	Color buttonFg;
	Color primaryBg;
	Color primaryHover;
	Color primaryFg;
	Color inputBg;
	Color inputEdge;
	Color inputFocusEdge;
	Color inputText;
	Color statusFg;
	Color scrollThumb;
	/** Corner radius; the game's own interface is squared, modern UIs round. */
	int arc;

	// -- Message cards ------------------------------------------------------
	/** Game-native mode: your questions render as chat lines (something you
	 * said), not cards; only answers get the journal treatment. */
	boolean userAsChatLine;
	Color userCardBg;
	Color userCardEdge;
	Color botCardBg;
	Color botCardEdge;
	/** Inner highlight line for the stone-bevel look; null = flat hairline. */
	Color bevelLight;
	Color userLabelFg;
	Color botLabelFg;
	String userBodyHex;
	String botBodyHex;
	/** "You:" prefix color in chat-line mode. */
	String userPrefixHex;
	String noteHex;
	String metaHex;
	String caretHex;
	String welcomeTextHex;

	// -- Markdown (answer body) --------------------------------------------
	String tableEdgeHex;
	String tableHeaderHex;
	String tableCellHex;

	// -- Entity decorator ----------------------------------------------------
	String questDoneHex;
	String questProgressHex;
	String questNotStartedHex;
	String itemCarriedHex;
	String itemBankedHex;
	String itemUnownedHex;
	String plainLinkHex;

	private Theme()
	{
	}

	private static volatile Theme active = build(0);

	static Theme active()
	{
		return active;
	}

	static void setActive(Theme theme)
	{
		active = theme;
	}

	static Theme byName(String name)
	{
		switch (name)
		{
			case "modern":
				return build(1);
			case "parchment":
				return build(2);
			default:
				return build(0);
		}
	}

	static String hex(Color c)
	{
		return String.format("#%06x", c.getRGB() & 0xffffff);
	}

	/**
	 * All three palettes in one table, one field per row, so a value change
	 * or a new field can never silently miss a theme. Columns:
	 *
	 * [0] game-native -- dark surfaces (native to the RuneLite client), but
	 *     every accent drawn from the game: RuneScape fonts for chrome, the
	 *     interface orange-gold as the only accent, questions as chat lines,
	 *     answers as journal cards, entity colors matched to the in-game
	 *     quest list (softened for a dark surface).
	 * [1] modern -- monochrome chrome, neutral surfaces, no brand accent at
	 *     all: the only color on screen is semantic (quest states, item
	 *     ownership), so meaning is the sole thing that glows.
	 * [2] parchment -- full skeuomorphism: parchment message cards with
	 *     stone-bevel edges and quest-journal dark text, chat-blue questions
	 *     and dialogue-box speaker red for answers, squared corners --
	 *     closest to the in-game interface look, on a dark-brown field.
	 */
	private static Theme build(int i)
	{
		Font bold = FontManager.getRunescapeBoldFont();
		Font small = FontManager.getRunescapeSmallFont();
		Theme t = new Theme();
		t.chromeFont = pick(i, bold, null, bold);
		t.statusFont = pick(i, small, null, small);
		t.accent = pick(i, c(0xff981f), c(0xe0e0e0), c(0xffd870));
		t.surface = pick(i, c(0x1e1b16), c(0x212121), c(0x2e2820));
		t.chromeBg = pick(i, c(0x26221b), c(0x282828), c(0x241f18));
		t.buttonBg = pick(i, c(0x3a3226), c(0x2a2a2a), c(0x4e4433));
		t.buttonHover = pick(i, c(0x4a4030), c(0x343434), c(0x5e5340));
		t.buttonFg = pick(i, c(0xff981f), c(0xbbbbbb), c(0xf0d9a0));
		t.primaryBg = pick(i, c(0xff981f), c(0xe8e8e8), c(0x6a5a3a));
		t.primaryHover = pick(i, c(0xffb04a), c(0xffffff), c(0x7a6a48));
		t.primaryFg = pick(i, c(0x211a10), c(0x1e1e1e), c(0xffd870));
		t.inputBg = pick(i, c(0x1e1b16), c(0x212121), c(0xded5b6));
		t.inputEdge = pick(i, c(0x4a3f2a), c(0x3a3a3a), c(0x5a4a33));
		t.inputFocusEdge = pick(i, c(0xb8781c), c(0x5a5a5a), c(0x8a6d2e));
		t.inputText = pick(i, c(0xe6d9b8), c(0xdddddd), c(0x3a2f1b));
		t.statusFg = pick(i, c(0xb8a888), c(0x9a9a9a), c(0xc8b681));
		t.scrollThumb = pick(i, c(0x4a4030), c(0x424242), c(0x5a4a33));
		t.arc = pick(i, 8, 12, 4);
		t.userAsChatLine = pick(i, true, false, false);
		t.userCardBg = pick(i, null, c(0x2f2f2f), c(0xd8cdad));
		t.userCardEdge = pick(i, null, c(0x3c3c3c), c(0x5a4a33));
		t.botCardBg = pick(i, c(0x262019), c(0x2a2a2a), c(0xe2d8ba));
		t.botCardEdge = pick(i, c(0x4a3b24), c(0x363636), c(0x5a4a33));
		t.bevelLight = pick(i, null, null, c(0xf4ecd4));
		t.userLabelFg = pick(i, c(0xffffff), c(0x9a9a9a), c(0x16388c));
		t.botLabelFg = pick(i, c(0xff981f), c(0x9a9a9a), c(0x7a1f1f));
		t.userBodyHex = pick(i, "#ff981f", "#dddddd", "#16388c");
		t.botBodyHex = pick(i, "#e8ddc4", "#dddddd", "#2e2415");
		t.userPrefixHex = pick(i, "#ffffff", "#ffffff", "#16388c");
		t.noteHex = pick(i, "#a89878", "#9a9a9a", "#6a5c40");
		t.metaHex = pick(i, "#8a7c60", "#828282", "#7a6c50");
		t.caretHex = pick(i, "#ff981f", "#cfcfcf", "#7a1f1f");
		t.welcomeTextHex = pick(i, "#a89878", "#828282", "#c8b681");
		t.tableEdgeHex = pick(i, "#4a3b24", "#3a3a3a", "#8a7a5c");
		t.tableHeaderHex = pick(i, "#332a1d", "#303030", "#c9bc96");
		t.tableCellHex = pick(i, "#241f17", "#252525", "#d8cdad");
		t.questDoneHex = pick(i, "#2ee62e", "#6cc24a", "#1e7d1e");
		t.questProgressHex = pick(i, "#f0e130", "#d4aa46", "#8a6d00");
		t.questNotStartedHex = pick(i, "#ff4040", "#cc5555", "#a02121");
		t.itemCarriedHex = pick(i, "#8ee88e", "#7ec850", "#2a7d2a");
		t.itemBankedHex = pick(i, "#4ed6d6", "#6fa8dc", "#1e5b8a");
		t.itemUnownedHex = pick(i, "#e05b5b", "#b07070", "#a04040");
		t.plainLinkHex = pick(i, "#c8b681", "#b0a6e8", "#6a4fa0");
		return t;
	}

	private static <T> T pick(int i, T gameNative, T modern, T parchment)
	{
		return i == 1 ? modern : i == 2 ? parchment : gameNative;
	}

	private static Color c(int rgb)
	{
		return new Color(rgb);
	}
}
