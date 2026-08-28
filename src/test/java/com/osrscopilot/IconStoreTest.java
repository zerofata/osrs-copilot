package com.osrscopilot;

import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.ItemDescriptor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The store renders from the live client, which tests cannot host; what is
 * testable is the contract around it: ID-keyed disk serving, fail-soft
 * misses, and the decorator resolving icons by item ID instead of guessed
 * filename.
 */
public class IconStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final int WHIP = 4151;

	private IconStore storeWith(String... pngNames) throws Exception
	{
		File dir = tmp.newFolder();
		for (String name : pngNames)
		{
			ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB),
				"png", new File(dir, name));
		}
		// Null managers: outside the client only disk content is served.
		return new IconStore(dir, null, null, null);
	}

	@Test
	public void servesDiskIconsByIdAndFailsSoftWithoutClient() throws Exception
	{
		IconStore store = storeWith("item-" + WHIP + ".png", "skill-attack.png", "quest.png");
		assertTrue(store.itemIconUrl(WHIP).endsWith("item-" + WHIP + ".png"));
		assertTrue(store.skillIconUrl("Attack").endsWith("skill-attack.png"));
		assertTrue(store.questIconUrl().endsWith("quest.png"));
		// Unrendered sprite, no client to render it: null, never an error.
		assertNull(store.itemIconUrl(9999));
		assertNull(store.skillIconUrl("Sailing"));
	}

	@Test
	public void decoratorResolvesOwnedItemIconsByCapturedId() throws Exception
	{
		IconStore store = storeWith("item-" + WHIP + ".png");
		GameCapture cap = new GameCapture();
		cap.equipment = List.of(Map.of("id", WHIP, "name", "Abyssal whip", "quantity", 1));

		String html = AnswerDecorator
			.build(cap, new EntityResolver.Resolution(), List.of(), List.of(), store)
			.decorate("Bring your Abyssal whip.");
		assertTrue("owned item icon must come from its captured ID",
			html.contains("item-" + WHIP + ".png"));
	}

	@Test
	public void decoratorResolvesUnownedItemIconsFromTheCatalogue() throws Exception
	{
		IconStore store = storeWith("item-" + WHIP + ".png");
		String html = AnswerDecorator
			.build(new GameCapture(), new EntityResolver.Resolution(), List.of(),
				List.of(new ItemDescriptor("Abyssal whip", "Abyssal whip", WHIP, true, null, null)), store)
			.decorate("Save up for an Abyssal whip.");
		assertTrue("unowned item icon must come from the catalogue ID",
			html.contains("item-" + WHIP + ".png"));

		String noId = AnswerDecorator
			.build(new GameCapture(), new EntityResolver.Resolution(), List.of(),
				List.of(new ItemDescriptor("Abyssal whip", "Abyssal whip", null, true, null, null)), store)
			.decorate("Save up for an Abyssal whip.");
		assertFalse("no ID means no icon, never a guess", noId.contains("<img"));
	}

	@Test
	public void capturedIdBeatsTheCatalogueId() throws Exception
	{
		// The player's copy may be a variant; its captured ID wins over
		// the catalogue's canonical one.
		IconStore store = storeWith("item-" + WHIP + ".png", "item-9999.png");
		GameCapture cap = new GameCapture();
		cap.equipment = List.of(Map.of("id", WHIP, "name", "Abyssal whip", "quantity", 1));
		String html = AnswerDecorator
			.build(cap, new EntityResolver.Resolution(), List.of(),
				List.of(new ItemDescriptor("Abyssal whip", "Abyssal whip", 9999, true, null, null)), store)
			.decorate("Bring your Abyssal whip.");
		assertTrue(html.contains("item-" + WHIP + ".png"));
		assertFalse(html.contains("item-9999.png"));
	}

	@Test
	public void untradeableIconComesFromItsCatalogueId() throws Exception
	{
		// Crowbar is not on the GE; only the infobox catalogue carries its ID.
		IconStore store = storeWith("item-31807.png");
		String html = AnswerDecorator
			.build(new GameCapture(), new EntityResolver.Resolution(), List.of(),
				List.of(new ItemDescriptor("Crowbar", "Crowbar", 31807, false, null, null)), store)
			.decorate("The quest rewards a Crowbar.");
		assertTrue(html.contains("item-31807.png"));
	}
}
