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
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
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
		cap.accountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);

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
		areas.put("Ardougne", new int[]{Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE});
		areas.put("Desert", new int[]{Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE});
		areas.put("Falador", new int[]{Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE});
		areas.put("Fremennik", new int[]{Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE});
		areas.put("Kandarin", new int[]{Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE});
		areas.put("Karamja", new int[]{Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE});
		areas.put("Kourend & Kebos", new int[]{Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE});
		areas.put("Lumbridge & Draynor", new int[]{Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE});
		areas.put("Morytania", new int[]{Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE});
		areas.put("Varrock", new int[]{Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE});
		areas.put("Western Provinces", new int[]{Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE});
		areas.put("Wilderness", new int[]{Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE});

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
