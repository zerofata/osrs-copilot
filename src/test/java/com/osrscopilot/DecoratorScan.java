package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.ItemDescriptor;
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

		// Includes the owned items: only catalogued names decorate.
		List<ItemDescriptor> catalogue = List.of(
			new ItemDescriptor("Bow of Faerdhinen", "Bow of Faerdhinen", null, true, null, null),
			new ItemDescriptor("Diamond", "Diamond", null, true, null, null),
			new ItemDescriptor("Diamond bolts", "Diamond bolts", null, true, null, null),
			new ItemDescriptor("Shark", "Shark", null, true, null, null),
			new ItemDescriptor("Emberlight", "Emberlight", null, false, null, null),
			new ItemDescriptor("Prayer potion(4)", "Prayer potion", null, true, null, null),
			new ItemDescriptor("Abyssal whip", "Abyssal whip", null, true, null, null),
			new ItemDescriptor("Ruby dragon bolts (e)", "Ruby dragon bolts (e)", null, true, null, null));

		String answer = "You need **While Guthix Sleeps** done (it is) and A Kingdom Divided "
			+ "unlocks Dark Demonbane. The Giant Dwarf is half done.\n\n"
			+ "- Bring your Emberlight and prayer potions\n"
			+ "- Ruby dragon bolts work; diamond bolts are backup\n"
			+ "- Bow of Faerdhinen only if you get crystal armour\n"
			+ "- Bring sharks to eat while fighting the tormented demons\n";

		String html = AnswerDecorator.build(cap, entities, List.of(), catalogue, null)
			.decorate(MarkdownHtml.toHtml(answer));
		out.println(html);
	}
}
