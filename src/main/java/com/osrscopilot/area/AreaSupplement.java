package com.osrscopilot.area;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Names for map regions the vendored {@link GameArea} table lacks: content
 * released after the table's last update, and the Wilderness, which the
 * table never covered. Region IDs were derived from each wiki location
 * page's map coordinates by GameAreaScan; where several places share a
 * region, the settlement or best-known landmark won. Consulted only when
 * GameArea resolves nothing, so entries can never shadow the vendored data.
 */
final class AreaSupplement
{
	private AreaSupplement()
	{
	}

	/** Surface Wilderness bounds. Named POI regions inside them are in
	 * REGION_NAMES and match first. */
	private static final int WILDY_X_MIN = 2944;
	private static final int WILDY_X_MAX = 3391;
	private static final int WILDY_Y_MIN = 3520;
	private static final int WILDY_Y_MAX = 3970;

	static String name(WorldPoint point)
	{
		String named = REGION_NAMES.get(point.getRegionID());
		if (named != null)
		{
			return named;
		}
		if (point.getX() >= WILDY_X_MIN && point.getX() <= WILDY_X_MAX
			&& point.getY() >= WILDY_Y_MIN && point.getY() <= WILDY_Y_MAX)
		{
			int level = Math.min(56, (point.getY() - WILDY_Y_MIN) / 8 + 1);
			return "the Wilderness (level " + level + ")";
		}
		return null;
	}

	private static final Map<Integer, String> REGION_NAMES = new HashMap<>();

	private static void put(int region, String name)
	{
		REGION_NAMES.put(region, name);
	}

	static
	{
		put(4651, "Laguna Aurorae");
		put(4751, "Kurask Lair");
		put(4764, "Headless Beast's Lair");
		put(4911, "South-west Tlati Rainforest mine");
		put(4912, "Tal Teklan");
		put(4913, "Tal Teok");
		put(5012, "Dragon Nest");
		put(5165, "Darkmoon Ravine");
		put(5168, "Tlati Rainforest");
		put(5170, "Tempestus");
		put(5172, "Stalker Den");
		put(5173, "Custodia Mountains mine");
		put(5266, "Tonali Cavern");
		put(5267, "Crypt of Tonali");
		put(5268, "Ruins of Mokhaiotl");
		put(5420, "Mistrock");
		put(5421, "Aldarin");
		put(5423, "Kastori");
		put(5426, "Gloomthorn Trail");
		put(5427, "Auburn Valley");
		put(5677, "Villa Lucens");
		put(5683, "Great Auburn Redwood");
		put(5684, "Auburnvale");
		put(5786, "Giants' Den");
		put(5787, "Shayzien Prison");
		put(5938, "Quetzacalli Gorge");
		put(5939, "The Darkfrost");
		put(5949, "The White Cliffs of Lova");
		put(5975, "Yama's Lair");
		put(6036, "Wolf Den");
		put(6187, "Shimmering Atoll");
		put(6194, "The Proudspire");
		put(6195, "Shipwreck Cove");
		put(6223, "Lithkren Vault");
		put(6450, "Tower of Ascension");
		put(6451, "Salvager Overlook");
		put(6475, "Misthalin Manor");
		put(6706, "Twilight Temple");
		put(6707, "East Salvager Overlook mine");
		put(6953, "The Crown Jewel");
		put(6991, "Dream World");
		put(6997, "Clan Hall");
		put(7205, "Isle of Serpents");
		put(7244, "Balloon Crash Site");
		put(7470, "Vatrachos Island");
		put(7475, "Port Roberts");
		put(7477, "Chinchompa Island");
		put(7494, "Dragonkin Castle");
		put(7723, "Deepfin Point");
		put(7728, "Minotaurs' Rest");
		put(7743, "Brittle Isle");
		put(7777, "Scrubfoot's cave");
		put(7779, "Scar essence mine");
		put(7828, "Here be minotaurs");
		put(8232, "Wintumber Island");
		put(8234, "Shipyard");
		put(8241, "Lledrith Island");
		put(8249, "Buccaneers' Haven");
		put(8276, "Airship Platform");
		put(8349, "Buccaneers' Laboratory");
		put(8503, "Drumstick Isle");
		put(8520, "Iban's Temple");
		put(8528, "Braindeath Island");
		put(8609, "Lunar Isle mine");
		put(8740, "Sunbleak Island");
		put(8758, "Ynysdail");
		put(8840, "Sunbleak Cave");
		put(8855, "Iorwerth Camp cave");
		put(9114, "Ynysdail Cavern");
		put(9123, "Ungael laboratory");
		put(9251, "Rainbow's End");
		put(9259, "Tear of the Soul");
		put(9288, "Varrock crypt");
		put(9522, "Galarpos Mountains");
		put(9626, "Underground Military Glider Hangar");
		put(9770, "Anglers' Retreat");
		put(9814, "Dorgesh-Kaan-Keldagrim train system");
		put(10018, "Ardeaglais");
		put(10023, "Isle of Bones");
		put(10105, "Virer Hunting Ground");
		put(10131, "Skavid caves");
		put(10133, "Tree Gnome Village Dungeon");
		put(10275, "Wyrmscraig");
		put(10301, "Here be penguins");
		put(10360, "Vampyrium Mine");
		put(10361, "Apsul Hunting Ground");
		put(10362, "Sangvesti");
		put(10374, "Wyrmscraig Cavern");
		put(10533, "Charred Island");
		put(10634, "Charred Dungeon");
		put(10828, "Thammaron's throne room");
		put(10874, "Sotfa Forest");
		put(11047, "Red Rock");
		put(11070, "The Graveyard");
		put(11165, "Kendal's Lair");
		put(11167, "Trollweiss Dungeon");
		put(11300, "Last Light");
		put(11324, "Zemouregal's Fort");
		put(11408, "Karamjan Temple");
		put(11410, "Shilo Village mine");
		put(11564, "Rock Island Prison");
		put(11579, "Lucien's camp");
		put(11580, "The North");
		put(11583, "Grimstone");
		put(11645, "Maggot King's lair");
		put(11677, "Troll kitchen");
		put(11683, "Grimstone Dungeon");
		put(11811, "The Onyx Crest");
		put(11816, "Remote Island");
		put(11833, "Dareeyak");
		put(11834, "The Forgotten Cemetery");
		put(11835, "Chaos Temple");
		put(11895, "Sugadinti's Hideout");
		put(12073, "Dognose Island");
		put(12078, "The Pandemonium");
		put(12088, "Dark Warriors' Fortress");
		put(12089, "Bandit Camp (Wilderness)");
		put(12090, "Wilderness God Wars Dungeon");
		put(12092, "Lava Maze runite mine");
		put(12093, "Pirates' Hideout");
		put(12107, "Abyss");
		put(12117, "Crash Island Dungeon");
		put(12178, "Pandemonium Cave");
		put(12187, "Old School Museum");
		put(12192, "Lava Maze Dungeon");
		put(12327, "Abalone Cliffs");
		put(12343, "South Wilderness mine");
		put(12346, "Bandit Camp mine");
		put(12348, "Lava Maze");
		put(12349, "Mage Arena bank");
		put(12426, "Gryphons (dungeon)");
		put(12433, "Enakhra's Temple");
		put(12581, "The Summer Shore");
		put(12583, "Northern Conch mine");
		put(12600, "Ferox Enclave");
		put(12601, "Graveyard of Shadows");
		put(12602, "Ruins (east)");
		put(12603, "Wilderness Hunter area");
		put(12605, "Resource Area");
		put(12682, "Shellbane Gryphon Cave");
		put(12836, "Cape Conch mine");
		put(12838, "The Great Conch");
		put(12842, "Menaphos");
		put(12856, "Chaos Temple (Wilderness)");
		put(12858, "Bone Yard");
		put(12859, "Lava Dragon Isle");
		put(12861, "Scorpion Pit");
		put(12951, "Water mill cellar");
		put(12995, "Grand Library");
		put(13092, "Cape Conch");
		put(13113, "Bone Yard Hunter area");
		put(13116, "Demonic Ruins");
		put(13117, "Rogues' Castle");
		put(13194, "Coral Nurseries");
		put(13346, "The Little Pearl");
		put(13368, "Pyre Cove");
		put(13373, "Blighted Volcano");
		put(13403, "1v1 Arena");
		put(13631, "Daimon's Crater");
		put(13712, "Scabaras Dungeon");
		put(14148, "Yu'biusk");
		put(14151, "Zemouregal's Base");
		put(14745, "Morytania Spider Cave");
		put(14915, "Goblin Temple");
		put(15199, "Church of Ayaster crypt");
		put(15248, "Underwater Tunnel");
		put(15455, "Church of Ayaster");
		put(16453, "Ancient Guthixian Temple");
		put(16457, "Black Knight Catacombs");
		put(16461, "Movario's base");
	}
}
