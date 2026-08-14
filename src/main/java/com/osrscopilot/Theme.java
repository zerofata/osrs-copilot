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
	final String name;

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

	private Theme(String name)
	{
		this.name = name;
	}

	private static volatile Theme active = gameNative();

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
				return modern();
			case "parchment":
				return parchment();
			default:
				return gameNative();
		}
	}

	static String hex(Color c)
	{
		return String.format("#%06x", c.getRGB() & 0xffffff);
	}

	/**
	 * Dark surfaces (native to the RuneLite client), but every accent drawn
	 * from the game: RuneScape fonts for chrome, the interface orange-gold
	 * as the only accent, questions as chat lines, answers as journal cards,
	 * entity colors matched to the in-game quest list.
	 */
	static Theme gameNative()
	{
		Theme t = new Theme("game-native");
		t.chromeFont = FontManager.getRunescapeBoldFont();
		t.statusFont = FontManager.getRunescapeSmallFont();
		t.accent = new Color(0xff981f);
		t.surface = new Color(0x1e1b16);
		t.chromeBg = new Color(0x26221b);
		t.buttonBg = new Color(0x3a3226);
		t.buttonHover = new Color(0x4a4030);
		t.buttonFg = new Color(0xff981f);
		t.primaryBg = new Color(0xff981f);
		t.primaryHover = new Color(0xffb04a);
		t.primaryFg = new Color(0x211a10);
		t.inputBg = new Color(0x1e1b16);
		t.inputEdge = new Color(0x4a3f2a);
		t.inputFocusEdge = new Color(0xb8781c);
		t.inputText = new Color(0xe6d9b8);
		t.statusFg = new Color(0xb8a888);
		t.scrollThumb = new Color(0x4a4030);
		t.arc = 8;
		t.userAsChatLine = true;
		t.userCardBg = null;
		t.userCardEdge = null;
		t.botCardBg = new Color(0x262019);
		t.botCardEdge = new Color(0x4a3b24);
		t.bevelLight = null;
		t.userLabelFg = new Color(0xffffff);
		t.botLabelFg = new Color(0xff981f);
		t.userBodyHex = "#ff981f";
		t.botBodyHex = "#e8ddc4";
		t.userPrefixHex = "#ffffff";
		t.noteHex = "#a89878";
		t.metaHex = "#8a7c60";
		t.caretHex = "#ff981f";
		t.welcomeTextHex = "#a89878";
		t.tableEdgeHex = "#4a3b24";
		t.tableHeaderHex = "#332a1d";
		t.tableCellHex = "#241f17";
		// The in-game quest list's own colors, softened for a dark surface.
		t.questDoneHex = "#2ee62e";
		t.questProgressHex = "#f0e130";
		t.questNotStartedHex = "#ff4040";
		t.itemCarriedHex = "#8ee88e";
		t.itemBankedHex = "#4ed6d6";
		t.itemUnownedHex = "#e05b5b";
		t.plainLinkHex = "#c8b681";
		return t;
	}

	/**
	 * Monochrome chrome, neutral surfaces, no brand accent at all: the only
	 * color on screen is semantic (quest states, item ownership), so meaning
	 * is the sole thing that glows.
	 */
	static Theme modern()
	{
		Theme t = new Theme("modern");
		t.chromeFont = null;
		t.statusFont = null;
		t.accent = new Color(0xe0e0e0);
		t.surface = new Color(0x212121);
		t.chromeBg = new Color(0x282828);
		t.buttonBg = new Color(0x2a2a2a);
		t.buttonHover = new Color(0x343434);
		t.buttonFg = new Color(0xbbbbbb);
		t.primaryBg = new Color(0xe8e8e8);
		t.primaryHover = new Color(0xffffff);
		t.primaryFg = new Color(0x1e1e1e);
		t.inputBg = new Color(0x212121);
		t.inputEdge = new Color(0x3a3a3a);
		t.inputFocusEdge = new Color(0x5a5a5a);
		t.inputText = new Color(0xdddddd);
		t.statusFg = new Color(0x9a9a9a);
		t.scrollThumb = new Color(0x424242);
		t.arc = 12;
		t.userAsChatLine = false;
		t.userCardBg = new Color(0x2f2f2f);
		t.userCardEdge = new Color(0x3c3c3c);
		t.botCardBg = new Color(0x2a2a2a);
		t.botCardEdge = new Color(0x363636);
		t.bevelLight = null;
		t.userLabelFg = new Color(0x9a9a9a);
		t.botLabelFg = new Color(0x9a9a9a);
		t.userBodyHex = "#dddddd";
		t.botBodyHex = "#dddddd";
		t.userPrefixHex = "#ffffff";
		t.noteHex = "#9a9a9a";
		t.metaHex = "#828282";
		t.caretHex = "#cfcfcf";
		t.welcomeTextHex = "#828282";
		t.tableEdgeHex = "#3a3a3a";
		t.tableHeaderHex = "#303030";
		t.tableCellHex = "#252525";
		t.questDoneHex = "#6cc24a";
		t.questProgressHex = "#d4aa46";
		t.questNotStartedHex = "#cc5555";
		t.itemCarriedHex = "#7ec850";
		t.itemBankedHex = "#6fa8dc";
		t.itemUnownedHex = "#b07070";
		t.plainLinkHex = "#b0a6e8";
		return t;
	}

	/**
	 * Full skeuomorphism: parchment message cards with stone-bevel edges and
	 * quest-journal dark text, chat-blue questions, squared corners --
	 * closest to the in-game interface look, floating on a dark-brown field.
	 */
	static Theme parchment()
	{
		Theme t = new Theme("parchment");
		t.chromeFont = FontManager.getRunescapeBoldFont();
		t.statusFont = FontManager.getRunescapeSmallFont();
		t.accent = new Color(0xffd870);
		t.surface = new Color(0x2e2820);
		t.chromeBg = new Color(0x241f18);
		t.buttonBg = new Color(0x4e4433);
		t.buttonHover = new Color(0x5e5340);
		t.buttonFg = new Color(0xf0d9a0);
		t.primaryBg = new Color(0x6a5a3a);
		t.primaryHover = new Color(0x7a6a48);
		t.primaryFg = new Color(0xffd870);
		t.inputBg = new Color(0xded5b6);
		t.inputEdge = new Color(0x5a4a33);
		t.inputFocusEdge = new Color(0x8a6d2e);
		t.inputText = new Color(0x3a2f1b);
		t.statusFg = new Color(0xc8b681);
		t.scrollThumb = new Color(0x5a4a33);
		t.arc = 4;
		t.userAsChatLine = false;
		t.userCardBg = new Color(0xd8cdad);
		t.userCardEdge = new Color(0x5a4a33);
		t.botCardBg = new Color(0xe2d8ba);
		t.botCardEdge = new Color(0x5a4a33);
		t.bevelLight = new Color(0xf4ecd4);
		// Dialogue-box speaker red for the answerer, public-chat blue for you.
		t.userLabelFg = new Color(0x16388c);
		t.botLabelFg = new Color(0x7a1f1f);
		t.userBodyHex = "#16388c";
		t.botBodyHex = "#2e2415";
		t.userPrefixHex = "#16388c";
		t.noteHex = "#6a5c40";
		t.metaHex = "#7a6c50";
		t.caretHex = "#7a1f1f";
		t.welcomeTextHex = "#c8b681";
		t.tableEdgeHex = "#8a7a5c";
		t.tableHeaderHex = "#c9bc96";
		t.tableCellHex = "#d8cdad";
		// Quest-journal semantics, darkened to read on parchment.
		t.questDoneHex = "#1e7d1e";
		t.questProgressHex = "#8a6d00";
		t.questNotStartedHex = "#a02121";
		t.itemCarriedHex = "#2a7d2a";
		t.itemBankedHex = "#1e5b8a";
		t.itemUnownedHex = "#a04040";
		t.plainLinkHex = "#6a4fa0";
		return t;
	}
}
