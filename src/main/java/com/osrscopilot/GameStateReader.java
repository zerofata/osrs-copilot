package com.osrscopilot;

import com.osrscopilot.pipeline.GameCapture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/**
 * Reads the game client into a GameCapture snapshot. Everything here runs
 * on the client thread (Quest.getState runs scripts); the pipeline then
 * works entirely off-thread against the immutable capture.
 */
class GameStateReader
{
	// Mirror net.runelite.api.gameval.InventoryID; raw ints to stay compatible
	// across API versions.
	static final int INV_ID = 93;
	static final int WORN_ID = 94;
	static final int BANK_ID = 95;

	// VarPlayer.SLAYER_TASK_SIZE, and the config the built-in Slayer plugin
	// writes its task to. Literals keep this off that plugin's classes.
	private static final int SLAYER_TASK_SIZE = 394;
	private static final String SLAYER_GROUP = "slayer";
	private static final String SLAYER_TASK_NAME = "taskName";
	private static final String SLAYER_TASK_LOCATION = "taskLocation";

	private final Client client;
	private final ConfigManager configManager;
	private final CopilotConfig config;
	private final BankStore bankStore;
	private final EventRecorder events;

	GameStateReader(Client client, ConfigManager configManager, CopilotConfig config,
		BankStore bankStore, EventRecorder events)
	{
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.bankStore = bankStore;
		this.events = events;
	}

	GameCapture buildCapture()
	{
		GameCapture cap = new GameCapture();
		Player p = client.getLocalPlayer();
		if (p != null)
		{
			cap.playerName = p.getName();
			cap.combatLevel = p.getCombatLevel();
			WorldPoint wp = p.getWorldLocation();
			if (wp != null)
			{
				cap.location = Map.of("x", wp.getX(), "y", wp.getY(),
					"plane", wp.getPlane(), "regionId", wp.getRegionID());
			}
		}
		cap.accountType = client.getVarbitValue(VarbitID.IRONMAN);

		Map<String, Integer> skills = new LinkedHashMap<>();
		Map<String, Integer> skillXp = new LinkedHashMap<>();
		Map<String, Integer> boosts = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			String name = skill.getName();
			int real = client.getRealSkillLevel(skill);
			int boosted = client.getBoostedSkillLevel(skill);
			skills.put(name, real);
			skillXp.put(name, client.getSkillExperience(skill));
			if (boosted != real)
			{
				boosts.put(name, boosted - real);
			}
		}
		cap.skills = skills;
		cap.skillXp = skillXp;
		cap.boostsOrDrains = boosts;

		Map<String, String> questStates = new LinkedHashMap<>();
		for (Quest q : Quest.values())
		{
			QuestState state = q.getState(client);
			questStates.put(q.getName(), state.name());
		}
		cap.questStates = questStates;
		cap.diaries = diaryCompletion();
		cap.slayerTask = slayerTask();
		cap.unlocks = unlocks();

		cap.inventory = itemList(client.getItemContainer(INV_ID));
		cap.equipment = itemList(client.getItemContainer(WORN_ID));
		if (config.sendBank())
		{
			ItemContainer bank = client.getItemContainer(BANK_ID);
			cap.bank = bank != null ? itemList(bank) : bankStore.contents();
			if (cap.bank != null)
			{
				cap.bankCapturedAtMs = bank != null
					? System.currentTimeMillis() : bankStore.capturedAtMs();
			}
		}
		if (config.sendRecentEvents())
		{
			cap.recentEvents = events.recent();
		}
		return cap;
	}

	/**
	 * Per-tier achievement diary completion via varbits. Per-task varbits
	 * exist but need a curated ID map; tiers cover most question needs.
	 */
	private Map<String, Object> diaryCompletion()
	{
		Map<String, int[]> areas = new LinkedHashMap<>();
		areas.put("Ardougne", new int[]{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE});
		areas.put("Desert", new int[]{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE});
		areas.put("Falador", new int[]{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE});
		areas.put("Fremennik", new int[]{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE});
		areas.put("Kandarin", new int[]{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE});
		// Karamja predates the diary varbit convention: its easy/medium/hard
		// completion lives in ATJUN_*_DONE; only elite follows the pattern.
		areas.put("Karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE});
		areas.put("Kourend & Kebos", new int[]{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE});
		areas.put("Lumbridge & Draynor", new int[]{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE});
		areas.put("Morytania", new int[]{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE});
		areas.put("Varrock", new int[]{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE});
		areas.put("Western Provinces", new int[]{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE});
		areas.put("Wilderness", new int[]{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE});

		String[] tiers = {"easy", "medium", "hard", "elite"};
		Map<String, Object> out = new LinkedHashMap<>();
		for (Map.Entry<String, int[]> area : areas.entrySet())
		{
			List<String> done = new ArrayList<>();
			for (int i = 0; i < 4; i++)
			{
				if (client.getVarbitValue(area.getValue()[i]) == 1)
				{
					done.add(tiers[i]);
				}
			}
			out.put(area.getKey(), done);
		}
		return out;
	}

	/**
	 * Spellbook and prayer-scroll unlocks, straight from varbits. The model
	 * kept verifying these through the item search tool (which can only say
	 * "0 owned" for a consumed scroll -- actively misleading); stating them
	 * here answers the question before it is asked. Piety/Chivalry are
	 * deliberately absent: their gate (Knight Waves) has no varbit we can
	 * read with confidence, and a false unlock claim is worse than the
	 * model checking the King's Ransom quest state.
	 */
	private Map<String, Object> unlocks()
	{
		Map<String, Object> out = new LinkedHashMap<>();
		String[] books = {"standard", "ancient magicks", "lunar", "arceuus"};
		int book = client.getVarbitValue(VarbitID.SPELLBOOK);
		out.put("active_spellbook", book >= 0 && book < books.length
			? books[book] : "unknown");
		out.put("rigour_unlocked",
			client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) == 1);
		out.put("augury_unlocked",
			client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) == 1);
		out.put("preserve_unlocked",
			client.getVarbitValue(VarbitID.PRAYER_PRESERVE_UNLOCKED) == 1);
		return out;
	}

	/**
	 * The active Slayer task. The remaining count is a varp, so it is always
	 * exact. The creature has no name mapping in the client API -- only a
	 * numeric id -- so the name comes from the built-in Slayer plugin, which
	 * records it per account. With that plugin off we report the count alone
	 * rather than guessing a creature.
	 */
	private Map<String, Object> slayerTask()
	{
		// The count is the source of truth for "is there a task": the stored
		// creature name outlives the task it was recorded for.
		int remaining = client.getVarpValue(SLAYER_TASK_SIZE);
		if (remaining <= 0)
		{
			return null;
		}
		Map<String, Object> task = new LinkedHashMap<>();
		String creature = configManager.getRSProfileConfiguration(SLAYER_GROUP, SLAYER_TASK_NAME);
		if (creature != null && !creature.isEmpty())
		{
			task.put("creature", creature);
		}
		task.put("remaining", remaining);
		String location = configManager.getRSProfileConfiguration(SLAYER_GROUP, SLAYER_TASK_LOCATION);
		if (location != null && !location.isEmpty())
		{
			task.put("location", location);
		}
		return task;
	}

	List<Map<String, Object>> itemList(ItemContainer container)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		if (container == null)
		{
			return items;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() == -1)
			{
				continue;
			}
			items.add(Map.of(
				"id", item.getId(),
				"name", itemName(item.getId()),
				"quantity", item.getQuantity()));
		}
		return items;
	}

	String itemName(int id)
	{
		try
		{
			return client.getItemDefinition(id).getName();
		}
		catch (Exception e)
		{
			return "unknown";
		}
	}
}
