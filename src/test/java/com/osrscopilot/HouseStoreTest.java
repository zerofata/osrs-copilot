package com.osrscopilot;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HouseStoreTest
{
	// describe(): whitelist calibrated against a real house dump.

	@Test
	public void portalChamberPortalsKeepTheirDestinationName()
	{
		assertEquals("Kourend Portal",
			HouseStore.describe("Kourend Portal", new String[]{"Enter"}));
	}

	@Test
	public void dualDestinationPortalListsBothDestinations()
	{
		assertEquals("Varrock Portal (Varrock or Grand Exchange)",
			HouseStore.describe("Varrock Portal",
				new String[]{"Varrock", "Grand Exchange", "Toggle", null, "Remove"}));
	}

	@Test
	public void houseExitPortalIsNotAFacility()
	{
		assertNull(HouseStore.describe("Portal", new String[]{"Enter"}));
	}

	@Test
	public void poolNeedsADrinkOp()
	{
		assertEquals("Pool of Rejuvenation",
			HouseStore.describe("Pool of Rejuvenation", new String[]{"Drink"}));
		assertNull(HouseStore.describe("Large fountain", new String[]{null, null}));
	}

	@Test
	public void altarsPrayerAndSpellbook()
	{
		assertEquals("Altar", HouseStore.describe("Altar", new String[]{"Pray"}));
		assertEquals("Occult altar",
			HouseStore.describe("Occult altar", new String[]{"Venerate"}));
	}

	@Test
	public void teleportFacilitiesByName()
	{
		assertEquals("Portal Nexus", HouseStore.describe("Portal Nexus", null));
		assertEquals("Ornate jewellery box",
			HouseStore.describe("Ornate jewellery box", new String[]{"Teleport Menu"}));
		assertEquals("Digsite Pendant",
			HouseStore.describe("Digsite Pendant", new String[]{"Teleport menu"}));
		assertEquals("Fairy ring", HouseStore.describe("Fairy ring", new String[]{"Use"}));
	}

	@Test
	public void furnitureIsIgnored()
	{
		assertNull(HouseStore.describe("Oak chair", new String[]{"Sit-on"}));
		assertNull(HouseStore.describe("Rug", null));
	}

	// parseNexusMenu(): Teleport Menu texts calibrated in-game.

	@Test
	public void nexusMenuEntriesParseAfterTheSeparator()
	{
		assertEquals(List.of("Arceuus Library"), HouseStore.parseNexusMenu(List.of(
			"Portal Nexus", "Teleport Mode", "Scry Mode",
			"<col=ffffff>1</col> :  Arceuus Library")));
	}

	@Test
	public void otherMenusAreRejectedByTitle()
	{
		assertNull(HouseStore.parseNexusMenu(List.of()));
		assertNull(HouseStore.parseNexusMenu(List.of(
			"Jewellery Box", "<col=ffffff>1</col> :  Duel Arena.")));
	}

	@Test
	public void nexusConfigurationScreenYieldsNoDestinations()
	{
		// The Configuration screen shares the title but lists its
		// catalogue without " : " separators.
		assertEquals(List.of(), HouseStore.parseNexusMenu(List.of(
			"Portal Nexus", "Available 0/41", "Varrock", "Lumbridge")));
	}

	// Persistence.

	@Test
	public void roundTripFoldsNexusDestinationsIntoTheirEntry() throws Exception
	{
		File dir = Files.createTempDirectory("house-store-test").toFile();
		Gson gson = new Gson();

		HouseStore store = new HouseStore(dir, gson, Runnable::run);
		store.sync(42L);
		assertNull(store.forCapture());
		store.updateFacilities(List.of("Altar", "Kourend Portal", "Portal Nexus"));
		store.updateNexus(List.of("Arceuus Library", "Varrock"));

		HouseStore reloaded = new HouseStore(dir, gson, Runnable::run);
		reloaded.sync(42L);
		assertEquals(
			List.of("Altar", "Kourend Portal", "Portal Nexus: Arceuus Library, Varrock"),
			reloaded.forCapture());
	}

	@Test
	public void otherAccountsDoNotInheritTheHouse() throws Exception
	{
		File dir = Files.createTempDirectory("house-store-test").toFile();
		HouseStore store = new HouseStore(dir, new Gson(), Runnable::run);
		store.sync(42L);
		store.updateFacilities(List.of("Altar"));
		store.sync(7L);
		assertNull(store.forCapture());
	}
}
