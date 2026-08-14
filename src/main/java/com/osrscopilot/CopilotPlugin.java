package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import com.osrscopilot.pipeline.CopilotPipeline;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.HttpException;
import com.osrscopilot.pipeline.Llm;
import com.osrscopilot.pipeline.StreamListener;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

/**
 * OSRS Copilot: ask questions in the side panel, answered by an LLM with your
 * live game state and facts fetched from the OSRS Wiki / Grand Exchange.
 *
 * Fully self-contained: the whole pipeline (entity resolution, prefetch,
 * tool-calling synthesis) runs inside the plugin. The only things the user
 * configures are their LLM endpoint, key, model, and sampling settings.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Copilot",
	description = "AI copilot: ask questions in the side panel, answered with your live game state",
	tags = {"ai", "copilot", "assistant"}
)
public class CopilotPlugin extends Plugin
{
	// Mirror net.runelite.api.gameval.InventoryID; raw ints to stay compatible
	// across API versions.
	private static final int INV_ID = 93;
	private static final int WORN_ID = 94;
	private static final int BANK_ID = 95;

	private static final File DATA_DIR = new File(RuneLite.RUNELITE_DIR, "osrs-copilot");
	private static final File CACHE_DIR = new File(DATA_DIR, "cache");
	private static final File BANK_FILE = new File(DATA_DIR, "bank-latest.json");

	// VarPlayer.SLAYER_TASK_SIZE, and the config the built-in Slayer plugin
	// writes its task to. Literals keep this off that plugin's classes.
	private static final int SLAYER_TASK_SIZE = 394;
	private static final String SLAYER_GROUP = "slayer";
	private static final String SLAYER_TASK_NAME = "taskName";
	private static final String SLAYER_TASK_LOCATION = "taskLocation";

	private static final int EVENT_BUFFER_SIZE = 100;
	private static final int EVENTS_SENT_WITH_QUESTION = 30;

	@Inject
	private Client client;

	@Inject
	private CopilotConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ConfigManager configManager;

	private CopilotPipeline pipeline;
	private CopilotPanel panel;
	private NavigationButton navButton;
	private IconCache iconCache;
	/** Toolbar icon: starts as a drawn placeholder, upgraded to the Wise
	 * Old Man chathead once the icon cache has it. EDT-confined. */
	private BufferedImage navIcon;
	private BufferedWriter eventLog;

	// Question flow must hop threads: Swing EDT (submit) -> client thread
	// (capture; Quest.getState runs scripts, so never from a script callback)
	// -> executor (pipeline; network + LLM) -> Swing EDT (render).
	private volatile String pendingQuestion;
	private String pendingSnapshotLabel;
	private List<Map<String, Object>> lastBankContents;
	private long bankCapturedAtMs;

	// Recent gameplay events kept for question context (client thread only).
	private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();

	// Conversation session: completed exchanges only (errors never enter).
	// Touched from the EDT and the executor, hence the synchronization.
	private final List<CopilotPipeline.Exchange> conversation = new ArrayList<>();

	@Override
	protected void startUp() throws Exception
	{
		DATA_DIR.mkdirs();
		loadPersistedBank();

		pipeline = new CopilotPipeline(okHttpClient, gson, CACHE_DIR);
		executor.execute(pipeline::warmCaches);
		iconCache = new IconCache(new File(CACHE_DIR, "icons"), okHttpClient);

		Theme.setActive(Theme.byName(config.theme().key));
		navIcon = makeIcon();
		panel = createPanel();
		navButton = createNavButton(panel);
		clientToolbar.addNavigation(navButton);
		executor.execute(this::upgradeNavIcon);

		if (config.logEvents())
		{
			openEventLog();
		}
		log.info("OSRS Copilot started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			SwingUtilities.invokeLater(panel::closePopOut);
		}
		if (eventLog != null)
		{
			eventLog.close();
			eventLog = null;
		}
	}

	@Provides
	CopilotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CopilotConfig.class);
	}

	private CopilotPanel createPanel()
	{
		CopilotPanel p = new CopilotPanel(config.fontSize());
		p.setAskHandler(this::askQuestion);
		p.setClearHandler(() -> {
			synchronized (conversation)
			{
				conversation.clear();
			}
		});
		return p;
	}

	private NavigationButton createNavButton(CopilotPanel forPanel)
	{
		return NavigationButton.builder()
			.tooltip("OSRS Copilot")
			.icon(navIcon)
			.priority(7)
			.panel(forPanel)
			.build();
	}

	/** Runs on the executor: fetch the Wise Old Man chathead (the game's
	 * guide archetype) and swap it in as the toolbar icon. The drawn
	 * placeholder stays if the fetch fails -- purely cosmetic, never fatal. */
	private void upgradeNavIcon()
	{
		File f = iconCache.file("Wise_Old_Man_chathead.png");
		if (f == null)
		{
			return;
		}
		try
		{
			BufferedImage img = javax.imageio.ImageIO.read(f);
			if (img == null)
			{
				return;
			}
			double scale = Math.min(24.0 / img.getWidth(), 24.0 / img.getHeight());
			int w = (int) Math.round(img.getWidth() * scale);
			int h = (int) Math.round(img.getHeight() * scale);
			BufferedImage scaled = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scaled.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, (24 - w) / 2, (24 - h) / 2, w, h, null);
			g.dispose();
			SwingUtilities.invokeLater(() -> {
				if (panel == null || navButton == null)
				{
					return;
				}
				navIcon = scaled;
				clientToolbar.removeNavigation(navButton);
				navButton = createNavButton(panel);
				clientToolbar.addNavigation(navButton);
			});
		}
		catch (Exception e)
		{
			log.debug("nav icon upgrade failed", e);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("osrscopilot".equals(event.getGroup())
			&& ("theme".equals(event.getKey()) || "fontSize".equals(event.getKey())))
		{
			// Both rebuild the panel and replay the conversation into it.
			SwingUtilities.invokeLater(this::applyTheme);
		}
	}

	/**
	 * A theme is baked into every component at construction, so switching
	 * means building a fresh panel. The conversation record replays into it
	 * (plain markdown; entity decoration belongs to the turn that made it),
	 * so changing themes never costs the player their chat.
	 */
	private void applyTheme()
	{
		if (panel == null)
		{
			return;
		}
		Theme.setActive(Theme.byName(config.theme().key));
		panel.closePopOut();
		clientToolbar.removeNavigation(navButton);
		panel = createPanel();
		synchronized (conversation)
		{
			for (CopilotPipeline.Exchange exchange : conversation)
			{
				panel.seedExchange(exchange.question, exchange.answer);
			}
		}
		navButton = createNavButton(panel);
		clientToolbar.addNavigation(navButton);
	}

	// ------------------------------------------------------------------
	// Ask flow
	// ------------------------------------------------------------------

	/** Called from the Swing EDT when the player submits a question. */
	private void askQuestion(String question)
	{
		// Plugin Hub contract: features that talk to a third-party server are
		// opt-in. Nothing leaves the client until the user flips this on.
		if (!config.enableCopilot())
		{
			panel.showError("Enable the copilot in the plugin settings first "
				+ "(wrench icon -> OSRS Copilot -> Enable copilot). Your questions and "
				+ "game state are sent only to the LLM endpoint you configure there.");
			return;
		}
		Llm.Settings settings = llmSettings();
		if (!settings.isConfigured())
		{
			panel.showError("Set the API base URL and model in the plugin settings first "
				+ "(wrench icon -> OSRS Copilot).");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showError("Log in first - the copilot needs your game state.");
			return;
		}
		pendingQuestion = question;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (pendingSnapshotLabel != null)
		{
			String label = pendingSnapshotLabel;
			pendingSnapshotLabel = null;
			File f = dumpSnapshot(label);
			String msg = f != null
				? "Copilot: snapshot written to " + f.getName()
				: "Copilot: snapshot FAILED, see client log";
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
		}

		if (pendingQuestion != null)
		{
			String question = pendingQuestion;
			pendingQuestion = null;
			GameCapture capture = buildCapture();
			Llm.Settings settings = llmSettings();
			int maxTurns = config.maxToolTurns();
			executor.execute(() -> runPipeline(question, capture, settings, maxTurns));
		}
	}

	/** Runs on the executor: wiki prefetch + LLM calls, streamed to the panel. */
	private void runPipeline(String question, GameCapture capture, Llm.Settings settings, int maxTurns)
	{
		StreamListener listener = new StreamListener()
		{
			@Override
			public void onDelta(String text)
			{
				SwingUtilities.invokeLater(() -> panel.appendDelta(text));
			}

			@Override
			public void onTurnDiscarded()
			{
				SwingUtilities.invokeLater(panel::discardPartial);
			}

			@Override
			public void onStatus(String status)
			{
				SwingUtilities.invokeLater(() -> panel.showWorking(status));
			}
		};

		List<CopilotPipeline.Exchange> history;
		synchronized (conversation)
		{
			history = new ArrayList<>(conversation);
		}

		try
		{
			CopilotPipeline.Result result =
				pipeline.answer(question, history, capture, settings, maxTurns, listener);
			log.debug("answered in {}ms, {} fact blocks, {} tool calls, {} context chars",
				result.millis, result.factBlocks, result.toolLog.size(), result.contextChars);
			synchronized (conversation)
			{
				conversation.add(new CopilotPipeline.Exchange(question, result.answer,
					result.route != null ? result.route.entities : null));
			}
			String meta = answerMeta(result);
			// Entity decoration (wiki links, quest/item state colors) runs
			// here on the worker thread: it may fetch the GE catalogue.
			String decorated = AnswerDecorator
				.build(capture, result.route != null ? result.route.entities : null,
					pipeline.tradeableItemNames(), iconCache)
				.decorate(MarkdownHtml.toHtml(result.answer));
			SwingUtilities.invokeLater(() ->
				panel.showAnswerDone(result.answer, decorated, result.millis / 1000.0,
					capture.bank != null, meta));
		}
		catch (Exception e)
		{
			log.warn("Copilot pipeline failed", e);
			SwingUtilities.invokeLater(() -> panel.showError(friendlyError(e)));
		}
	}

	/** One dim line under each answer disclosing what the model was given:
	 * retrieved facts, tools it called, and token cost. Keeps the pipeline
	 * inspectable in-game instead of only in offline eval runs. */
	private static String answerMeta(CopilotPipeline.Result result)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(result.factTitles.isEmpty() ? "no facts retrieved"
			: "facts: " + String.join("; ", result.factTitles));
		if (result.toolLog != null && !result.toolLog.isEmpty())
		{
			sb.append(" | tools: ").append(String.join(", ", result.toolLog));
		}
		if (result.usage != null)
		{
			sb.append(" | tokens ").append(result.usage.promptTokens).append(" in / ")
				.append(result.usage.completionTokens).append(" out");
		}
		if (!result.suspectNames.isEmpty())
		{
			sb.append(" | unverified names: ").append(String.join(", ", result.suspectNames));
		}
		return sb.toString();
	}

	private Llm.Settings llmSettings()
	{
		return new Llm.Settings(config.apiBaseUrl(), config.apiKey(), config.model(),
			config.temperature(), config.maxTokens());
	}

	/** Maps failures by kind -- HTTP status class or transport error -- with
	 * the server's own message as the detail. No per-endpoint cases. */
	private static String friendlyError(Exception e)
	{
		if (e instanceof HttpException)
		{
			HttpException he = (HttpException) e;
			String detail = he.serverMessage.isEmpty() ? he.getMessage() : he.serverMessage;
			if (he.code == 401 || he.code == 403)
			{
				return "Rejected (" + he.code + ") - check the API key in settings. " + detail;
			}
			if (he.code == 404)
			{
				return "Not found (404) - check the API base URL and model name. " + detail;
			}
			if (he.code == 429 || he.code >= 500)
			{
				return "Endpoint unavailable (" + he.code + ") - resubmit in a moment. " + detail;
			}
			return "HTTP " + he.code + ": " + detail;
		}
		if (e instanceof IOException)
		{
			return "Network error: " + e.getMessage();
		}
		return "Unexpected error: " + e;
	}

	// ------------------------------------------------------------------
	// Capture (client thread)
	// ------------------------------------------------------------------

	private GameCapture buildCapture()
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
			cap.bank = bank != null ? itemList(bank) : lastBankContents;
			if (cap.bank != null)
			{
				cap.bankCapturedAtMs = bank != null
					? System.currentTimeMillis() : bankCapturedAtMs;
			}
		}
		if (config.sendRecentEvents())
		{
			cap.recentEvents = buildRecentEvents();
		}
		return cap;
	}

	/** Runs on the client thread. */
	private List<Map<String, Object>> buildRecentEvents()
	{
		List<Map<String, Object>> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		int skip = Math.max(0, recentEvents.size() - EVENTS_SENT_WITH_QUESTION);
		int i = 0;
		for (Map<String, Object> e : recentEvents)
		{
			if (i++ < skip)
			{
				continue;
			}
			Map<String, Object> entry = new LinkedHashMap<>(e);
			Object ts = entry.remove("tsMs");
			if (ts instanceof Long)
			{
				entry.put("minutes_ago", Math.round((now - (Long) ts) / 6000.0) / 10.0);
			}
			out.add(entry);
		}
		return out;
	}

	/** Runs on the client thread. Only event types useful as question context. */
	private void bufferEvent(String type, Map<String, Object> data)
	{
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("tsMs", System.currentTimeMillis());
		entry.put("type", type);
		entry.putAll(data);
		recentEvents.addLast(entry);
		while (recentEvents.size() > EVENT_BUFFER_SIZE)
		{
			recentEvents.removeFirst();
		}
	}

	// ------------------------------------------------------------------
	// Event capture
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		logEvent("gameState", Map.of("state", event.getGameState().name()));
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		// Dev aid: ::probe dumps a full snapshot usable by the offline eval
		// runner. Deferred to the next tick (scripts are not reentrant).
		if ("probe".equalsIgnoreCase(event.getCommand()))
		{
			pendingSnapshotLabel = "manual";
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		logEvent("stat", Map.of(
			"skill", event.getSkill().name(),
			"xp", event.getXp(),
			"level", event.getLevel(),
			"boosted", event.getBoostedLevel()));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == BANK_ID)
		{
			// The bank container is not readable on demand after the bank
			// closes (getItemContainer returns null), so cache it here and
			// persist to disk so ownership survives client restarts.
			lastBankContents = itemList(event.getItemContainer());
			bankCapturedAtMs = System.currentTimeMillis();
			persistBank();
		}
		if (eventLog != null)
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("containerId", id);
			data.put("itemCount", event.getItemContainer().count());
			logEvent("itemContainer", data);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Game messages (drops, task completions, level-ups) are useful
		// question context; player chat is not.
		if (event.getType() == ChatMessageType.GAMEMESSAGE
			|| event.getType() == ChatMessageType.SPAM)
		{
			bufferEvent("chat", Map.of("message", event.getMessage()));
		}
		logEvent("chat", Map.of(
			"chatType", event.getType().name(),
			"message", event.getMessage()));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == null)
		{
			return;
		}
		Map<String, Object> data = Map.of(
			"actor", String.valueOf(event.getActor().getName()),
			"isLocalPlayer", event.getActor() == client.getLocalPlayer());
		logEvent("death", data);
		bufferEvent("death", data);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		for (ItemStack stack : event.getItems())
		{
			items.add(Map.of(
				"id", stack.getId(),
				"name", itemName(stack.getId()),
				"quantity", stack.getQuantity()));
		}
		Map<String, Object> data = Map.of(
			"npc", String.valueOf(event.getNpc().getName()),
			"items", items);
		logEvent("npcLoot", data);
		bufferEvent("npcLoot", data);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.logMenuClicks())
		{
			return;
		}
		logEvent("click", Map.of(
			"option", String.valueOf(event.getMenuOption()),
			"target", String.valueOf(event.getMenuTarget())));
	}

	// ------------------------------------------------------------------
	// Snapshot dump (dev aid for the offline eval runner)
	// ------------------------------------------------------------------

	private File dumpSnapshot(String label)
	{
		try
		{
			GameCapture cap = buildCapture();
			File out = new File(DATA_DIR, "snapshot-" + label + "-" + System.currentTimeMillis() + ".json");
			try (BufferedWriter w = new BufferedWriter(new FileWriter(out)))
			{
				w.write(gson.newBuilder().setPrettyPrinting().create().toJson(cap));
			}
			log.info("Snapshot written: {}", out.getAbsolutePath());
			return out;
		}
		catch (Exception e)
		{
			log.error("Snapshot failed", e);
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

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

	private void persistBank()
	{
		if (lastBankContents == null)
		{
			return;
		}
		try (BufferedWriter w = new BufferedWriter(new FileWriter(BANK_FILE)))
		{
			w.write(gson.toJson(lastBankContents));
		}
		catch (IOException e)
		{
			log.warn("Bank persist failed", e);
		}
	}

	private void loadPersistedBank()
	{
		if (!BANK_FILE.exists())
		{
			return;
		}
		try (FileReader r = new FileReader(BANK_FILE))
		{
			lastBankContents = gson.fromJson(r,
				new TypeToken<List<Map<String, Object>>>() { }.getType());
			// The file's mtime is when the bank was last persisted.
			bankCapturedAtMs = BANK_FILE.lastModified();
			log.info("Loaded persisted bank ({} items)",
				lastBankContents != null ? lastBankContents.size() : 0);
		}
		catch (Exception e)
		{
			log.warn("Bank load failed", e);
		}
	}

	private static BufferedImage makeIcon()
	{
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(30, 90, 160));
		g.fillRoundRect(1, 1, 22, 22, 8, 8);
		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		g.drawString("AI", 5, 16);
		g.dispose();
		return img;
	}

	private List<Map<String, Object>> itemList(ItemContainer container)
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

	private String itemName(int id)
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

	private void openEventLog() throws IOException
	{
		File logFile = new File(DATA_DIR, "events-" + System.currentTimeMillis() + ".jsonl");
		eventLog = new BufferedWriter(new FileWriter(logFile, true));
	}

	private void logEvent(String type, Map<String, Object> data)
	{
		if (eventLog == null || !config.logEvents())
		{
			return;
		}
		try
		{
			Map<String, Object> line = new LinkedHashMap<>();
			line.put("ts", Instant.now().toString());
			line.put("tick", client.getTickCount());
			line.put("type", type);
			line.putAll(data);
			eventLog.write(gson.toJson(line));
			eventLog.newLine();
			// Flush per write so a client crash cannot lose captured events.
			eventLog.flush();
		}
		catch (IOException e)
		{
			log.warn("Event log write failed", e);
		}
	}
}
