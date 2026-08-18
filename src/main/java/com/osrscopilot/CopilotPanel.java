package com.osrscopilot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * Sidebar chat panel. Pure view: collects questions, renders answers from a
 * {@link TranscriptModel}. All methods must be called on the Swing EDT.
 *
 * The conversation is a stack of real Swing message cards (custom-painted)
 * rather than one big HTML document: Swing's HTML renderer is stuck in HTML
 * 3.2, so surfaces, corners, hover states, and the scrollbar are painted
 * with Graphics2D, and HTML is used only for text flow inside a card.
 *
 * Every color, font, and card behavior comes from the active {@link Theme};
 * this class holds structure and interaction only.
 */
class CopilotPanel extends PluginPanel
{
	private static final int RENDER_THROTTLE_MS = 100;

	private final Theme theme = Theme.active();

	private final TranscriptModel model = new TranscriptModel();
	/** The card currently displaying each block; rebuilt on structural
	 * changes, patched in place while streaming. */
	private final Map<TranscriptModel.Block, MessageCard> cards = new HashMap<>();

	private final JPanel messageList = new ScrollableStack();
	private final JScrollPane scroll;
	private final JTextField input;
	private final JButton send;
	private final JButton clear;
	private final JButton popOut;
	private final JLabel status = SwingUtil.smoothLabel(" ");
	private final Timer renderTimer;
	private final Timer pulseTimer;
	private int pulse;

	private final HyperlinkListener linkOpener = e -> {
		if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED)
		{
			LinkBrowser.browse(e.getDescription());
		}
	};

	private Consumer<String> askHandler;
	private Runnable clearHandler;
	private boolean busy;
	private String statusBase = " ";

	// Pop-out support: the chat UI sits on `content`, which lives either in
	// this sidebar panel or in a free-floating resizable frame.
	private final JPanel content = new JPanel(new BorderLayout(0, 8));
	private final PopOutManager popOutManager;

	/** Base pixel size for message text; everything HTML scales from it. */
	private final int bodyFontPx;

	CopilotPanel(int bodyFontPx)
	{
		super(false);
		this.bodyFontPx = bodyFontPx;
		setLayout(new BorderLayout());
		setBackground(theme.chromeBg);

		input = new RoundedField(theme);
		send = new FlatButton("Ask", theme, true);
		clear = new FlatButton("New chat", theme, false);
		popOut = new FlatButton("Pop out", theme, false);
		popOutManager = new PopOutManager(this, content, popOut, input, theme);

		messageList.setBackground(theme.surface);
		messageList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		scroll = new JScrollPane(messageList);
		scroll.setPreferredSize(new Dimension(0, 420));
		scroll.setBorder(null);
		scroll.getViewport().setBackground(theme.surface);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		SwingUtil.styleScrollBar(scroll.getVerticalScrollBar(), theme);

		input.setToolTipText("Ask about gear, quests, drops, prices, training...");

		status.setFont(theme.statusFont != null ? theme.statusFont.deriveFont(16f)
			: status.getFont().deriveFont(Font.ITALIC, 12f));
		status.setForeground(theme.statusFg);
		status.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

		JPanel inputRow = new JPanel(new BorderLayout(6, 0));
		inputRow.setOpaque(false);
		inputRow.add(input, BorderLayout.CENTER);
		inputRow.add(send, BorderLayout.EAST);

		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		south.add(inputRow, BorderLayout.CENTER);
		south.add(status, BorderLayout.SOUTH);

		JLabel wordmark = SwingUtil.smoothLabel("COPILOT");
		wordmark.setForeground(theme.accent);
		wordmark.setFont(theme.chromeFont != null ? theme.chromeFont.deriveFont(16f)
			: wordmark.getFont().deriveFont(Font.BOLD, 12f));
		wordmark.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));

		JPanel northButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		northButtons.setOpaque(false);
		northButtons.add(popOut);
		northButtons.add(clear);

		JPanel north = new JPanel(new BorderLayout());
		north.setOpaque(false);
		clear.setToolTipText("Start a new conversation (clears context)");
		popOut.setToolTipText("Open the chat in its own resizable window");
		north.add(wordmark, BorderLayout.WEST);
		north.add(northButtons, BorderLayout.EAST);

		// The whole chat UI lives on one movable panel: RuneLite's sidebar
		// width is a fixed constant, so "make the chat bigger" is only
		// possible by carrying this panel into a resizable window. The
		// padding travels with it so nothing touches container edges,
		// docked or floating.
		content.setBackground(theme.chromeBg);
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		content.add(north, BorderLayout.NORTH);
		content.add(scroll, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		add(content, BorderLayout.CENTER);

		popOut.addActionListener(e -> popOutManager.toggle());

		renderTimer = new Timer(RENDER_THROTTLE_MS, e -> render());
		renderTimer.setRepeats(false);
		// A slow pulse animates the status ellipsis while the model works,
		// so waiting reads as activity rather than a hang.
		pulseTimer = new Timer(400, e -> {
			pulse++;
			refreshStatus();
		});

		ActionListener submit = e -> fireAsk();
		input.addActionListener(submit);
		send.addActionListener(submit);
		clear.addActionListener(e -> fireClear());

		rebuild();
	}

	/** Plugin shutdown: a floating chat window must not outlive the plugin. */
	void closePopOut()
	{
		popOutManager.close();
	}

	/** Replay one completed exchange into a fresh panel (theme and font-size
	 * changes rebuild the panel; the conversation must survive the swap).
	 * The answer re-renders exactly as the turn produced it: decorated HTML
	 * and meta line when available, markdown otherwise. */
	void seedExchange(String question, String answer, String decoratedHtml, String meta)
	{
		model.seedExchange(question, answer, decoratedHtml, meta);
		rebuild();
	}

	void setAskHandler(Consumer<String> handler)
	{
		askHandler = handler;
	}

	void setClearHandler(Runnable handler)
	{
		clearHandler = handler;
	}

	private void fireAsk()
	{
		String question = input.getText().trim();
		if (question.isEmpty() || busy || askHandler == null)
		{
			return;
		}
		model.beginQuestion(question);
		rebuild();
		input.setText("");
		setBusy(true);
		askHandler.accept(question);
	}

	private void fireClear()
	{
		if (busy)
		{
			return;
		}
		model.clear();
		rebuild();
		setStatusBase(" ");
		status.setToolTipText(null);
		status.setForeground(theme.statusFg);
		if (clearHandler != null)
		{
			clearHandler.run();
		}
	}

	/** A fragment of the streamed answer arrived. */
	void appendDelta(String text)
	{
		if (model.appendDelta(text))
		{
			rebuild();
		}
		scheduleRender();
	}

	/** Streamed text turned out not to be the answer (tool-call preamble);
	 * the model keeps it as a dim working note. */
	void discardPartial()
	{
		if (model.discardPartial())
		{
			scheduleRender();
		}
	}

	/** A pipeline progress note ("Looking up: ..."): shown dim inside the
	 * answer card, where the player is actually looking, and echoed to the
	 * status line. */
	void showWorking(String note)
	{
		if (model.addWorkingNote(note))
		{
			rebuild();
		}
		setStatusBase(note);
		scheduleRender();
	}

	void showAnswerDone(String answer, String decoratedHtml, double seconds,
		boolean bankSeen, String meta)
	{
		TranscriptModel.Block done = model.completeAnswer(answer, decoratedHtml, meta);
		if (cards.get(done) == null)
		{
			rebuild();
		}
		else
		{
			syncCard(done);
		}
		setBusy(false);
		if (bankSeen)
		{
			setStatusBase(String.format("answered in %.1fs", seconds));
			status.setToolTipText(null);
		}
		else
		{
			// Without bank contents the copilot can't tell "you don't own it"
			// from "I can't see it", so gear answers stay hypothetical.
			setStatusBase("answered - open your bank once for gear answers");
			status.setToolTipText("<html><body style='width:280px'>Bank contents are "
				+ "read when the bank is opened, then persist across sessions.</body></html>");
		}
	}

	/** Roll the conversation back to before this question and put the
	 * question text back in the input box so the player can resubmit. */
	void showError(String message)
	{
		String question = model.rollback();
		rebuild();
		if (question != null)
		{
			input.setText(question);
		}
		setBusy(false);
		setStatusBase("Error - press Enter to retry (hover for details)");
		status.setToolTipText("<html><body style='width:280px'>" + SwingUtil.escapeHtml(message)
			+ "</body></html>");
		status.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
	}

	private void setBusy(boolean b)
	{
		busy = b;
		input.setEnabled(!b);
		send.setEnabled(!b);
		clear.setEnabled(!b);
		if (b)
		{
			setStatusBase("Thinking");
			status.setForeground(theme.statusFg);
			status.setToolTipText(null);
			pulseTimer.start();
		}
		else
		{
			pulseTimer.stop();
			refreshStatus();
		}
	}

	private void setStatusBase(String text)
	{
		statusBase = text;
		refreshStatus();
	}

	private void refreshStatus()
	{
		if (busy && !statusBase.trim().isEmpty())
		{
			StringBuilder dots = new StringBuilder(statusBase);
			for (int i = 0; i <= pulse % 3; i++)
			{
				dots.append('.');
			}
			status.setText(dots.toString());
		}
		else
		{
			status.setText(statusBase);
		}
	}

	// ------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------

	/** Coalesces bursts of streaming deltas into ~10 renders/second. */
	private void scheduleRender()
	{
		if (!renderTimer.isRunning())
		{
			renderTimer.start();
		}
	}

	/** Streaming refresh: only the live block's card re-renders. */
	private void render()
	{
		if (model.answerBlock() != null)
		{
			syncCard(model.answerBlock());
		}
	}

	/** Rebuild the card stack from the model. Structural changes only
	 * (new turn, rollback, clear); streaming goes through syncCard. */
	private void rebuild()
	{
		messageList.removeAll();
		cards.clear();
		if (model.isEmpty())
		{
			messageList.add(welcomePane());
		}
		for (TranscriptModel.Block block : model.blocks())
		{
			MessageCard card = new MessageCard(theme, block.isUser(), linkOpener, bodyFontPx);
			cards.put(block, card);
			syncCard(block);
			messageList.add(card);
		}
		messageList.revalidate();
		messageList.repaint();
		scrollToBottom();
	}

	private void syncCard(TranscriptModel.Block block)
	{
		MessageCard card = cards.get(block);
		if (card == null)
		{
			return;
		}
		// Secondary text stays at HTML size 3 (size 2 maps to ~10px in
		// Swing's renderer -- unreadable); the dim color alone carries the
		// visual hierarchy.
		StringBuilder html = new StringBuilder();
		if (block.isUser() && theme.userAsChatLine)
		{
			// Game-native: a question is something you said, not a document.
			html.append("<font color='").append(theme.userPrefixHex)
				.append("'><b>You:</b></font> ");
		}
		for (String note : block.working)
		{
			html.append("<font size='3' color='").append(theme.noteHex).append("'><i>")
				.append(SwingUtil.escapeHtml(note)).append("</i></font><br>");
		}
		html.append(block.decoratedHtml != null ? block.decoratedHtml
			: MarkdownHtml.toHtml(block.text.toString()));
		if (model.isStreaming(block))
		{
			// Streaming: a caret keeps the block visibly alive.
			html.append("<font color='").append(theme.caretHex)
				.append("'><b>&#9612;</b></font>");
		}
		if (block.meta != null && !block.meta.isEmpty())
		{
			// Meta arrives as HTML from answerMeta (its only producer), which
			// escapes the dynamic parts itself: fact titles carry source links.
			html.append("<br><font size='3' color='").append(theme.metaHex).append("'>")
				.append(block.meta).append("</font>");
		}
		card.setBodyHtml(html.toString());
		scrollToBottom();
	}

	private void scrollToBottom()
	{
		SwingUtilities.invokeLater(() -> {
			JScrollBar bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	private JEditorPane welcomePane()
	{
		JEditorPane pane = SwingUtil.newHtmlPane(linkOpener, bodyFontPx);
		pane.setText("<html><body><br><br><center>"
			+ "<font size='4' color='" + Theme.hex(theme.accent) + "'><b>OSRS Copilot</b></font><br>"
			+ "<font color='" + theme.welcomeTextHex + "'>"
			+ "Ask about gear, quests, drops, prices, or training."
			+ "<br>Answers are grounded in the wiki and your own game state.</font>"
			+ "<br><br><font size='3' color='" + theme.welcomeTextHex + "'>"
			+ "\u201cwhat should i bring for vorkath\u201d<br>"
			+ "\u201cwhere do i get addy bars\u201d<br>"
			+ "\u201cis my slayer task worth doing\u201d</font>"
			+ "<br><br><font size='3' color='" + theme.welcomeTextHex + "'>"
			+ "Requires an OpenAI-compatible LLM endpoint, set in plugin settings."
			+ "<br><br>Game data from the <a href='https://oldschool.runescape.wiki/'>OSRS Wiki</a>"
			+ " (content by its contributors, CC BY-NC-SA)"
			+ "<br>and the wiki's <a href='https://prices.runescape.wiki/'>GE price API</a>."
			+ " Each answer links its sources."
			+ "</font></center></body></html>");
		return pane;
	}

	// ------------------------------------------------------------------
	// Custom-painted components
	// ------------------------------------------------------------------

	/** Vertical card stack that always matches the viewport width, so HTML
	 * bodies wrap to the panel instead of pushing content off the right
	 * edge (the sidebar is only ~240px wide). */
	private static final class ScrollableStack extends JPanel implements javax.swing.Scrollable
	{
		ScrollableStack()
		{
			super(new StackLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction)
		{
			return visible.height;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	/**
	 * Full-width vertical stack whose row heights are computed AFTER the row
	 * is given its real width. Stock layout managers ask for preferred size
	 * first and lay out second, which mis-heights HTML text that wraps
	 * (height depends on width) -- the classic Swing height-for-width gap.
	 */
	private static final class StackLayout implements java.awt.LayoutManager
	{
		private static final int GAP = 8;

		@Override
		public void addLayoutComponent(String name, java.awt.Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(java.awt.Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(java.awt.Container parent)
		{
			Insets in = parent.getInsets();
			int width = parent.getWidth();
			int inner = Math.max(width - in.left - in.right, 100);
			int h = in.top;
			for (java.awt.Component c : parent.getComponents())
			{
				if (c.isVisible())
				{
					h += heightForWidth(c, inner) + GAP;
				}
			}
			return new Dimension(Math.max(width, 100), h + in.bottom);
		}

		@Override
		public Dimension minimumLayoutSize(java.awt.Container parent)
		{
			return new Dimension(0, 0);
		}

		@Override
		public void layoutContainer(java.awt.Container parent)
		{
			Insets in = parent.getInsets();
			int inner = parent.getWidth() - in.left - in.right;
			int y = in.top;
			for (java.awt.Component c : parent.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				int h = heightForWidth(c, inner);
				c.setBounds(in.left, y, inner, h);
				y += h + GAP;
			}
			// The viewport sized this container from a preferred height that
			// was computed at the previous width; when a resize changes text
			// wrap, converge with one follow-up pass.
			int settled = y + in.bottom;
			if (settled != parent.getHeight())
			{
				java.awt.Container target = parent;
				SwingUtilities.invokeLater(target::revalidate);
			}
		}

		/** Setting the width first makes the HTML body recompute its wrap,
		 * so the preferred height that follows is the real one. */
		private static int heightForWidth(java.awt.Component c, int width)
		{
			c.setSize(width, Integer.MAX_VALUE);
			if (c instanceof java.awt.Container)
			{
				((java.awt.Container) c).doLayout();
			}
			return c.getPreferredSize().height;
		}
	}

	/**
	 * One conversation turn. Depending on the theme this is a painted card
	 * (surface fill, hairline or stone-bevel edge, speaker label) or a bare
	 * chat line (game-native user questions: no card, inline "You:" prefix).
	 */
	private static final class MessageCard extends JPanel
	{
		private final Color bg;
		private final Color edge;
		private final Color bevel;
		private final int arc;
		private final String bodyHex;
		private final JEditorPane body;

		MessageCard(Theme theme, boolean you, HyperlinkListener links, int fontPx)
		{
			super(new BorderLayout(0, 4));
			boolean chatLine = you && theme.userAsChatLine;
			this.bg = chatLine ? null : you ? theme.userCardBg : theme.botCardBg;
			this.edge = you ? theme.userCardEdge : theme.botCardEdge;
			this.bevel = theme.bevelLight;
			this.arc = theme.arc;
			this.bodyHex = you ? theme.userBodyHex : theme.botBodyHex;
			setOpaque(false);
			setBorder(chatLine
				? BorderFactory.createEmptyBorder(2, 4, 2, 4)
				: BorderFactory.createEmptyBorder(9, 12, 9, 11));

			if (!chatLine)
			{
				JLabel label = SwingUtil.smoothLabel(you ? "YOU" : "COPILOT");
				label.setForeground(you ? theme.userLabelFg : theme.botLabelFg);
				label.setFont(theme.chromeFont != null ? theme.chromeFont.deriveFont(16f)
					: label.getFont().deriveFont(Font.BOLD, 11f));
				add(label, BorderLayout.NORTH);
			}

			body = SwingUtil.newHtmlPane(links, fontPx);
			add(body, BorderLayout.CENTER);
		}

		void setBodyHtml(String inner)
		{
			body.setText("<html><body style='color:" + bodyHex + "'>" + inner + "</body></html>");
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (bg != null)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(bg);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
				if (bevel != null)
				{
					// Stone-bevel: a light inner line under the dark outer one.
					g2.setColor(bevel);
					g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
				}
				g2.setColor(edge);
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	/** Flat themed button with a hover state and hand cursor. */
	private static final class FlatButton extends JButton
	{
		private final Color bg;
		private final Color bgHover;
		private final Color bgDisabled;
		private final int arc;
		private boolean hover;

		FlatButton(String text, Theme theme, boolean primary)
		{
			super(text);
			this.bg = primary ? theme.primaryBg : theme.buttonBg;
			this.bgHover = primary ? theme.primaryHover : theme.buttonHover;
			// A bright primary fill under grayed-out text is illegible;
			// disabled buttons recede to the secondary surface.
			this.bgDisabled = theme.buttonBg;
			this.arc = theme.arc;
			setForeground(primary ? theme.primaryFg : theme.buttonFg);
			setFocusPainted(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
			if (theme.chromeFont != null)
			{
				setFont(theme.chromeFont.deriveFont(16f));
			}
			else if (primary)
			{
				setFont(getFont().deriveFont(Font.BOLD));
			}
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(!isEnabled() ? bgDisabled : hover ? bgHover : bg);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
			g2.dispose();
			SwingUtil.smooth(g);
			super.paintComponent(g);
		}
	}

	/** Themed text field; the border brightens on focus. */
	private static final class RoundedField extends JTextField
	{
		private final Theme theme;

		RoundedField(Theme theme)
		{
			this.theme = theme;
			setOpaque(false);
			setBackground(theme.inputBg);
			setForeground(theme.inputText);
			setCaretColor(theme.inputText);
			setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(theme.inputBg);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), theme.arc, theme.arc);
			g2.setColor(hasFocus() ? theme.inputFocusEdge : theme.inputEdge);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, theme.arc, theme.arc);
			g2.dispose();
			SwingUtil.smooth(g);
			super.paintComponent(g);
		}
	}
}
