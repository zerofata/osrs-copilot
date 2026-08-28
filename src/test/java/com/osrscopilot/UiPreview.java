package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.ItemDescriptor;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Renders the chat panel offscreen with a seeded conversation and saves
 * PNGs at sidebar and pop-out sizes, so UI changes can be reviewed without
 * launching the game client. Dev tool only.
 */
public final class UiPreview
{
	private UiPreview()
	{
	}

	public static void main(String[] args) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			try
			{
				for (String name : new String[]{"game-native", "modern", "parchment"})
				{
					Theme.setActive(Theme.byName(name));
					render(name, 242, 620, true);
					render(name, 560, 720, true);
					render(name + "-welcome", 242, 620, false);
					render(name + "-welcome-custom", 242, 620, false);
				}
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		System.exit(0);
	}

	private static void render(String tag, int width, int height, boolean seedConversation)
		throws Exception
	{
		CopilotPanel panel = new CopilotPanel(13);
		panel.setAskHandler(q -> {
		});
		if (seedConversation)
		{
			seed(panel);
			// The welcome render keeps Simple off, so both states appear
			// across the preview set.
			panel.setSimpleMode(true);
		}
		else if (tag.endsWith("-custom"))
		{
			// The setup form expanded, in its second state: Custom endpoint
			// with the base URL row revealed.
			panel.setSetupState(new CopilotPanel.SetupValues(LlmProvider.CUSTOM, "",
				"llama3", "http://localhost:8000/v1", 0.2, 8192, 4));
			panel.setSetupOpen(true);
		}
		// Streamed content renders on a coalescing timer that can't fire
		// while this thread owns the EDT; flush the pending render directly.
		java.lang.reflect.Method render = CopilotPanel.class.getDeclaredMethod("render");
		render.setAccessible(true);
		render.invoke(panel);

		JFrame frame = new JFrame();
		frame.setUndecorated(true);
		frame.add(panel);
		frame.setSize(width, height);
		frame.addNotify();
		frame.validate();

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		panel.setSize(width, height);
		panel.validate();
		panel.printAll(g);
		g.dispose();
		frame.dispose();

		File out = new File("eval/ui-" + tag + "-" + width + "x" + height + ".png");
		ImageIO.write(img, "png", out);
		System.out.println("wrote " + out.getAbsolutePath());
	}

	private static void seed(CopilotPanel panel) throws Exception
	{
		GameCapture cap = new GameCapture();
		cap.questStates = Map.of(
			"While Guthix Sleeps", "FINISHED",
			"A Kingdom Divided", "NOT_STARTED");
		cap.inventory = List.of(Map.of("name", "Prayer potion(4)", "quantity", 3));
		cap.equipment = List.of();
		cap.bank = List.of(
			Map.of("name", "Emberlight", "quantity", 1),
			Map.of("name", "Bandos chestplate", "quantity", 1),
			Map.of("name", "Neitiznot faceguard", "quantity", 1));
		EntityResolver.Resolution ents = new EntityResolver.Resolution();
		ents.monsters.add("Tormented Demon");
		// Includes the owned items: only catalogued names decorate.
		List<ItemDescriptor> catalogue = List.of(
			new ItemDescriptor("Bow of Faerdhinen", "Bow of Faerdhinen", null, true, null, null),
			new ItemDescriptor("Dragon dart", "Dragon dart", null, true, null, null),
			new ItemDescriptor("Prayer potion(4)", "Prayer potion", null, true, null, null),
			new ItemDescriptor("Emberlight", "Emberlight", null, false, null, null),
			new ItemDescriptor("Bandos chestplate", "Bandos chestplate", null, true, null, null),
			new ItemDescriptor("Neitiznot faceguard", "Neitiznot faceguard", null, false, null, null));

		ask(panel, "what should i bring for tormented demons");
		String answer = "You have **While Guthix Sleeps** done, so you can fight them. "
			+ "A Kingdom Divided is not started, so demonbane spells are out.\n\n"
			+ "### Melee (primary)\n"
			+ "| Slot | Item |\n|---|---|\n"
			+ "| Weapon | Emberlight |\n"
			+ "| Body | Bandos chestplate |\n"
			+ "| Head | Neitiznot faceguard |\n\n"
			+ "- Bring prayer potions and a ranged switch\n"
			+ "- Bow of Faerdhinen only helps with the crystal set\n"
			+ "- At 93 Ranged your accuracy is fine either way\n";
		// No client here, so no sprite rendering: the store serves whatever
		// a previous dev-client session left on disk, else no icons.
		IconStore icons = new IconStore(new java.io.File("eval/icon-cache"),
			null, null, null);
		String decorated = AnswerDecorator.build(cap, ents, List.of(), catalogue, icons)
			.decorate(MarkdownHtml.toHtml(answer));
		panel.showAnswerDone(answer, decorated, 12.4, true,
			"facts: Monster info: Tormented Demon; Strategy: Tormented Demon; "
				+ "Recommended equipment: Tormented Demon | tokens 16719 in / 2999 out");

		ask(panel, "what about dragon darts in the blowpipe");
		panel.appendDelta("Let me check the numbers on that");
		panel.discardPartial();
		panel.showWorking("Looking up: search_owned_items, ge_price");
		panel.appendDelta("Dragon darts push the blowpipe's DPS up during the "
			+ "shield-down phase, and");
	}

	private static void ask(CopilotPanel panel, String question) throws Exception
	{
		Field inputField = CopilotPanel.class.getDeclaredField("input");
		inputField.setAccessible(true);
		((JTextField) inputField.get(panel)).setText(question);
		Field sendField = CopilotPanel.class.getDeclaredField("send");
		sendField.setAccessible(true);
		((JButton) sendField.get(panel)).doClick();
	}
}
