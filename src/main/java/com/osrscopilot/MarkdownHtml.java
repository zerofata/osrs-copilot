package com.osrscopilot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal markdown-to-HTML converter for the subset LLMs actually emit:
 * headers, bold/italic, inline code, bullet/numbered lists, tables, rules.
 * Dependency-free on purpose (Plugin Hub scrutinizes third-party libraries),
 * and targets the HTML 3.2 subset that Swing's JEditorPane can render.
 *
 * Tolerant by design: partial markdown (mid-stream) renders as slightly
 * unstyled text, never breaks.
 */
final class MarkdownHtml
{
	private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
	private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
	private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[\\s|:\\-]+$");
	private static final Pattern HRULE = Pattern.compile("^\\s*[-_*]{3,}\\s*$");

	private MarkdownHtml()
	{
	}

	static String toHtml(String markdown)
	{
		StringBuilder out = new StringBuilder();
		String openList = null;
		boolean inTable = false;
		boolean tableHeaderDone = false;

		for (String raw : markdown.split("\n", -1))
		{
			String line = SwingUtil.escapeHtml(raw);

			Matcher table = TABLE_ROW.matcher(line);
			if (table.matches())
			{
				if (TABLE_SEPARATOR.matcher(line).matches())
				{
					continue;
				}
				openList = closeList(out, openList);
				Theme theme = Theme.active();
				if (!inTable)
				{
					// cellspacing over a colored table bg draws a hairline grid,
					// the only bordering Swing's HTML 3.2 renderer honors.
					out.append("<table width='100%' cellpadding='4' cellspacing='1' bgcolor='")
						.append(theme.tableEdgeHex).append("'>");
					inTable = true;
					tableHeaderDone = false;
				}
				out.append("<tr>");
				String trimmed = line.trim();
				String inner = trimmed.substring(1, trimmed.length() - 1);
				for (String cell : inner.split("\\|", -1))
				{
					out.append("<td bgcolor='")
						.append(tableHeaderDone ? theme.tableCellHex : theme.tableHeaderHex)
						.append("'>")
						.append(tableHeaderDone ? inline(cell.trim()) : "<b>" + inline(cell.trim()) + "</b>")
						.append("</td>");
				}
				out.append("</tr>");
				tableHeaderDone = true;
				continue;
			}
			inTable = closeTable(out, inTable);

			Matcher header = HEADER.matcher(line);
			if (header.matches())
			{
				openList = closeList(out, openList);
				// Two visible header sizes: sections (#, ##) and
				// subsections (### and deeper). More levels than that just
				// adds visual noise at panel width.
				String size = header.group(1).length() <= 2 ? "4" : "3";
				out.append("<br><font size='").append(size).append("'><b>")
					.append(inline(header.group(2))).append("</b></font><br>");
				continue;
			}
			if (HRULE.matcher(line).matches())
			{
				openList = closeList(out, openList);
				out.append("<hr>");
				continue;
			}

			Matcher bullet = BULLET.matcher(line);
			Matcher numbered = NUMBERED.matcher(line);
			if (bullet.matches() || numbered.matches())
			{
				String tag = bullet.matches() ? "ul" : "ol";
				String item = bullet.matches() ? bullet.group(1) : numbered.group(1);
				if (!tag.equals(openList))
				{
					openList = closeList(out, openList);
					out.append('<').append(tag).append('>');
					openList = tag;
				}
				out.append("<li>").append(inline(item)).append("</li>");
				continue;
			}

			openList = closeList(out, openList);
			if (line.trim().isEmpty())
			{
				out.append("<br>");
			}
			else
			{
				out.append(inline(line)).append("<br>");
			}
		}
		closeList(out, openList);
		closeTable(out, inTable);
		return out.toString();
	}

	private static String closeList(StringBuilder out, String openList)
	{
		if (openList != null)
		{
			out.append("</").append(openList).append('>');
		}
		return null;
	}

	private static boolean closeTable(StringBuilder out, boolean inTable)
	{
		if (inTable)
		{
			out.append("</table>");
		}
		return false;
	}

	private static String inline(String s)
	{
		s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
		s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
		s = s.replaceAll("(?<![\\w*])\\*([^*]+)\\*(?![\\w*])", "<i>$1</i>");
		// Links render as underlined text; the game client is no place to browse.
		s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "<u>$1</u>");
		return s;
	}
}
