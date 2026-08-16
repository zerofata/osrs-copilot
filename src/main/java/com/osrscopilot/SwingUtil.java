package com.osrscopilot;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.event.HyperlinkListener;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/** Shared Swing helpers for the copilot's custom-painted UI. */
final class SwingUtil
{
	private SwingUtil()
	{
	}

	/** The OS's font smoothing settings (subpixel AA on Windows). Custom
	 * painting starts from a bare Graphics2D with no text hints at all, so
	 * every text-drawing component here must opt in or small text renders
	 * with hard pixel stairsteps. */
	private static final Object DESKTOP_FONT_HINTS =
		java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");

	static void smooth(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g;
		if (DESKTOP_FONT_HINTS instanceof java.util.Map)
		{
			g2.addRenderingHints((java.util.Map<?, ?>) DESKTOP_FONT_HINTS);
		}
		else
		{
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		}
	}

	/** JLabel that honors the desktop's text antialiasing. */
	static JLabel smoothLabel(String text)
	{
		return new JLabel(text)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				smooth(g);
				super.paintComponent(g);
			}
		};
	}

	/** HTML pane tuned for card bodies: transparent, non-editable, entity
	 * links colored but not underlined (color carries the state; underlining
	 * every known name turns dense answers into noise). */
	static JEditorPane newHtmlPane(HyperlinkListener links, int fontPx)
	{
		JEditorPane pane = new JEditorPane()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				smooth(g);
				super.paintComponent(g);
			}
		};
		HTMLEditorKit kit = new HTMLEditorKit();
		StyleSheet sheet = new StyleSheet();
		sheet.addStyleSheet(kit.getStyleSheet());
		sheet.addRule("a { text-decoration: none; }");
		kit.setStyleSheet(sheet);
		pane.setEditorKit(kit);
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		pane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontPx));
		pane.addHyperlinkListener(links);
		return pane;
	}

	static void styleScrollBar(JScrollBar bar, Theme theme)
	{
		bar.setPreferredSize(new Dimension(8, 0));
		bar.setUI(new BasicScrollBarUI()
		{
			@Override
			protected void configureScrollBarColors()
			{
				thumbColor = theme.scrollThumb;
				trackColor = theme.surface;
			}

			@Override
			protected JButton createDecreaseButton(int orientation)
			{
				return zeroButton();
			}

			@Override
			protected JButton createIncreaseButton(int orientation)
			{
				return zeroButton();
			}

			@Override
			protected void paintThumb(Graphics g, javax.swing.JComponent c, java.awt.Rectangle r)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(thumbColor);
				g2.fillRoundRect(r.x + 1, r.y, r.width - 2, r.height, 6, 6);
				g2.dispose();
			}

			private JButton zeroButton()
			{
				JButton b = new JButton();
				b.setPreferredSize(new Dimension(0, 0));
				return b;
			}
		});
	}

	static String escapeHtml(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
