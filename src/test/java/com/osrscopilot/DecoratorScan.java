package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Offline check of answer decoration: builds a synthetic capture and a
 * sample answer, prints the decorated HTML. Verifies quest-state coloring,
 * item container coloring, plural matching, longest-match precedence, and
 * that markup is never decorated into.
 */
public final class DecoratorScan
{
	private DecoratorScan()
	{
	}

	public static void main(String[] args) throws Exception
	{
		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8.name());

		GameCapture cap = new GameCapture();
		cap.questStates = Map.of(
			"While Guthix Sleeps", "FINISHED",
			"A Kingdom Divided", "NOT_STARTED",
			"The Giant Dwarf", "IN_PROGRESS");
		cap.inventory = List.of(Map.of("name", "Prayer potion(4)", "quantity", 3));
		cap.equipment = List.of(Map.of("name", "Abyssal whip", "quantity", 1));
		cap.bank = List.of(
			Map.of("name", "Emberlight", "quantity", 1),
			Map.of("name", "Ruby dragon bolts (e)", "quantity", 200));

		EntityResolver.Resolution entities = new EntityResolver.Resolution();
		entities.monsters.add("Tormented Demon");

		List<String[]> tradeable = List.of(
			new String[]{"Bow of Faerdhinen", "Bow of Faerdhinen"},
			new String[]{"Diamond", "Diamond"},
			new String[]{"Diamond bolts", "Diamond bolts"},
			new String[]{"Shark", "Shark"},
			new String[]{"Emberlight", "Emberlight"});

		String answer = "You need **While Guthix Sleeps** done (it is) and A Kingdom Divided "
			+ "unlocks Dark Demonbane. The Giant Dwarf is half done.\n\n"
			+ "- Bring your Emberlight and prayer potions\n"
			+ "- Ruby dragon bolts work; diamond bolts are backup\n"
			+ "- Bow of Faerdhinen only if you get crystal armour\n"
			+ "- Bring sharks to eat while fighting the tormented demons\n";

		String html = AnswerDecorator.build(cap, entities, tradeable, null)
			.decorate(MarkdownHtml.toHtml(answer));
		out.println(html);
	}
}
