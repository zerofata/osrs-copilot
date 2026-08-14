package com.osrscopilot;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarkdownHtmlTest
{
	@Test
	public void inlineStyles()
	{
		String html = MarkdownHtml.toHtml("Use **Protect from Melee** with *Piety* and `::probe`.");
		assertTrue(html.contains("<b>Protect from Melee</b>"));
		assertTrue(html.contains("<i>Piety</i>"));
		assertTrue(html.contains("<code>::probe</code>"));
	}

	@Test
	public void headersAndLists()
	{
		String html = MarkdownHtml.toHtml("### Gear Setup\n- Abyssal whip\n- Anti-dragon shield\n\n1. First\n2. Second");
		assertTrue(html.contains("<b>Gear Setup</b>"));
		assertTrue(html.contains("<ul><li>Abyssal whip</li><li>Anti-dragon shield</li></ul>"));
		assertTrue(html.contains("<ol><li>First</li><li>Second</li></ol>"));
	}

	@Test
	public void tablesRenderWithHeaderRow()
	{
		String html = MarkdownHtml.toHtml("| Slot | Item |\n|---|---|\n| Weapon | Whip |");
		assertTrue(html.contains("<table"));
		// Header cells are bold on a distinct background; body cells plain.
		assertTrue(html.contains("<b>Slot</b></td>"));
		assertTrue(html.contains(">Whip</td>"));
		assertFalse(html.contains("---"));
	}

	@Test
	public void bulletAsteriskDoesNotItalicize()
	{
		String html = MarkdownHtml.toHtml("* one\n* two");
		assertTrue(html.contains("<ul><li>one</li><li>two</li></ul>"));
		assertFalse(html.contains("<i>"));
	}

	@Test
	public void htmlIsEscaped()
	{
		String html = MarkdownHtml.toHtml("damage <10 & \"safe\"");
		assertTrue(html.contains("&lt;10 &amp;"));
	}

	@Test
	public void partialMarkdownNeverBreaks()
	{
		// Mid-stream fragments: unclosed bold, dangling table pipe.
		assertEquals(false, MarkdownHtml.toHtml("**unclosed bold").isEmpty());
		assertEquals(false, MarkdownHtml.toHtml("| half | a row").isEmpty());
	}
}
