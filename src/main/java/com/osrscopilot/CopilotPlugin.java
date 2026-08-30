package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import com.osrscopilot.area.Areas;
import com.osrscopilot.pipeline.CopilotPipeline;
import com.osrscopilot.pipeline.EmptyAnswerException;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.HttpException;
import com.osrscopilot.pipeline.Llm;
import com.osrscopilot.pipeline.StreamListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * OSRS Copilot: ask questions in the side panel, answered by an LLM with
 * your live game state and facts fetched from the OSRS Wiki / Grand
 * Exchange. The user configures only their LLM endpoint, key, model, and
 * sampling.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Copilot",
	description = "Bring your own LLM! Supports OpenRouter or any OpenAI-compatible endpoint.",
	tags = {"ai", "copilot", "assistant", "llm"}
)
public class CopilotPlugin extends Plugin
{
	private static final File DATA_DIR = new File(RuneLite.RUNELITE_DIR, "osrs-copilot");
	private static final File CACHE_DIR = new File(DATA_DIR, "cache");

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
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private SkillIconManager skillIconManager;

	/** RuneLite's shared background thread: right for small tasks like the
	 * bank persist writes, which must stay off the client thread. */
	@Inject
	private ScheduledExecutorService sharedExecutor;

	/** Dedicated pipeline worker: RuneLite's injected executor is one
	 * shared thread for every plugin, and a multi-minute LLM stream would
	 * stall it. A private single thread also serializes questions and
	 * dies with the plugin. */
	private ExecutorService pipelineExecutor;

	private CopilotPipeline pipeline;
	private CopilotPanel panel;
	private NavigationButton navButton;
	private IconStore iconStore;
	private BufferedImage navIcon;

	// Question flow must hop threads: Swing EDT (submit) -> client thread
	// (capture; Quest.getState runs scripts, so never from a script callback)
	// -> executor (pipeline; network + LLM) -> Swing EDT (render).
	private volatile String pendingQuestion;
	private String pendingSnapshotLabel;
	private int pendingWidgetDumpGroup = -1;
	private BankStore bankStore;
	private BankMutations bankMutations;
	/** Arms the drift audit for the next bank capture; set at startup and
	 * whenever the bank interface closes, cleared once the visit's first
	 * capture has been audited. */
	private boolean bankDriftAuditArmed = true;
	private EventRecorder events;
	private GameStateReader reader;

	/** One completed turn: the pipeline exchange (LLM history) plus what the
	 * turn rendered. Panel rebuilds (theme/font-size changes) replay the
	 * rendered form verbatim, so decoration and meta survive them. */
	private static final class Turn
	{
		final CopilotPipeline.Exchange exchange;
		final String decoratedHtml;
		final String meta;

		Turn(CopilotPipeline.Exchange exchange, String decoratedHtml, String meta)
		{
			this.exchange = exchange;
			this.decoratedHtml = decoratedHtml;
			this.meta = meta;
		}
	}

	// Conversation session: completed exchanges only (errors never enter).
	// Touched from the EDT and the executor, hence the synchronization.
	private final List<Turn> conversation = new ArrayList<>();

	@Override
	protected void startUp() throws Exception
	{
		DATA_DIR.mkdirs();
		bankStore = new BankStore(DATA_DIR, gson, sharedExecutor);
		bankMutations = new BankMutations(bankStore, itemManager);
		events = new EventRecorder(client, gson, config, DATA_DIR);
		reader = new GameStateReader(client, configManager, config, bankStore, events);

		pipelineExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "osrs-copilot-pipeline");
			t.setDaemon(true);
			return t;
		});
		pipeline = new CopilotPipeline(okHttpClient, gson, CACHE_DIR);
		// Warming only fetches static vocabulary from the plugin's GitHub;
		// nothing reaches an LLM until the user configures an endpoint.
		pipelineExecutor.execute(pipeline::warmCaches);
		iconStore = new IconStore(new File(CACHE_DIR, "icons"),
			itemManager, spriteManager, skillIconManager);

		Theme.setActive(Theme.byName(config.theme().key));
		navIcon = ImageUtil.loadImageResource(CopilotPlugin.class, "nav-icon.png");
		panel = createPanel();
		navButton = createNavButton(panel);
		clientToolbar.addNavigation(navButton);

		if (config.logEvents())
		{
			events.openLog();
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
		if (events != null)
		{
			events.closeLog();
		}
		if (pipelineExecutor != null)
		{
			// Drops queued work; an in-flight HTTP read runs out its
			// timeout on the dying daemon thread.
			pipelineExecutor.shutdownNow();
			pipelineExecutor = null;
		}
		// The plugin instance outlives disable, so everything sizable must
		// be released: the pipeline's vocabulary sets and page cache, the
		// panel's rendered conversation, and the capture state.
		pipeline = null;
		panel = null;
		iconStore = null;
		navIcon = null;
		bankStore = null;
		bankMutations = null;
		events = null;
		reader = null;
		pendingQuestion = null;
		pendingSnapshotLabel = null;
		pendingWidgetDumpGroup = -1;
		synchronized (conversation)
		{
			conversation.clear();
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
		// The config entry is the single source of truth for simple mode;
		// the chat toggle is just a shortcut to it.
		p.setSimpleMode(config.simpleMode());
		p.setSimpleModeHandler(on ->
			configManager.setConfiguration("osrscopilot", "simpleMode", on));
		// Same deal for the setup form: it reads and writes the config
		// entries the settings panel edits.
		p.setSetupState(setupValues());
		p.setSetupHandler(new CopilotPanel.SetupHandler()
		{
			@Override
			public void save(CopilotPanel.SetupValues v)
			{
				configManager.setConfiguration("osrscopilot", "provider", v.provider);
				configManager.setConfiguration("osrscopilot", "apiKey", v.apiKey);
				configManager.setConfiguration("osrscopilot", "model", v.model);
				if (v.provider == LlmProvider.CUSTOM)
				{
					configManager.setConfiguration("osrscopilot", "apiBaseUrl", v.customUrl);
				}
				configManager.setConfiguration("osrscopilot", "temperature", v.temperature);
				configManager.setConfiguration("osrscopilot", "maxTokens", v.maxTokens);
				configManager.setConfiguration("osrscopilot", "maxToolTurns", v.toolTurns);
			}

			@Override
			public void test(CopilotPanel.SetupValues v, java.util.function.Consumer<String> onDone)
			{
				String baseUrl = v.provider.baseUrl != null ? v.provider.baseUrl : v.customUrl;
				Llm.Settings settings = new Llm.Settings(baseUrl, v.apiKey, v.model,
					v.temperature, v.maxTokens);
				pipelineExecutor.execute(() -> {
					String error = null;
					try
					{
						pipeline.testEndpoint(settings);
					}
					catch (Exception e)
					{
						log.debug("endpoint test failed", e);
						error = friendlyError(e);
					}
					String err = error;
					SwingUtilities.invokeLater(() -> onDone.accept(err));
				});
			}
		});
		return p;
	}

	private CopilotPanel.SetupValues setupValues()
	{
		return new CopilotPanel.SetupValues(config.provider(), config.apiKey(),
			config.model(), config.apiBaseUrl(), config.temperature(),
			config.maxTokens(), config.maxToolTurns());
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

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"osrscopilot".equals(event.getGroup()))
		{
			return;
		}
		if ("theme".equals(event.getKey()) || "fontSize".equals(event.getKey()))
		{
			// Both rebuild the panel and replay the conversation into it.
			SwingUtilities.invokeLater(this::applyTheme);
		}
		if ("simpleMode".equals(event.getKey()) && panel != null)
		{
			// Changed from the RuneLite config panel: mirror it on the toggle.
			SwingUtilities.invokeLater(() -> panel.setSimpleMode(config.simpleMode()));
		}
		String key = event.getKey();
		if (("provider".equals(key) || "apiKey".equals(key) || "model".equals(key)
			|| "apiBaseUrl".equals(key) || "temperature".equals(key)
			|| "maxTokens".equals(key) || "maxToolTurns".equals(key)) && panel != null)
		{
			// Mirror config-panel edits into the setup form.
			SwingUtilities.invokeLater(() -> panel.setSetupState(setupValues()));
		}
	}

	/** A theme is baked into every component at construction, so switching
	 * builds a fresh panel and replays the conversation into it exactly as
	 * rendered. */
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
			for (Turn turn : conversation)
			{
				panel.seedExchange(turn.exchange.question, turn.exchange.answer,
					turn.decoratedHtml, turn.meta);
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
		Llm.Settings settings = llmSettings();
		if (!settings.isConfigured())
		{
			panel.showError("Set up your LLM first: click New chat and fill in the form, "
				+ "or use the plugin settings (wrench icon -> OSRS Copilot).");
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
		bankStore.sync(client.getAccountHash());

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
		if (pendingWidgetDumpGroup >= 0)
		{
			int group = pendingWidgetDumpGroup;
			pendingWidgetDumpGroup = -1;
			dumpWidgetGroup(group);
		}

		if (pendingQuestion != null)
		{
			String question = pendingQuestion;
			pendingQuestion = null;
			GameCapture capture = reader.buildCapture();
			Llm.Settings settings = llmSettings();
			int maxTurns = config.maxToolTurns();
			boolean simpleMode = config.simpleMode();
			pipelineExecutor.execute(() ->
				runPipeline(question, capture, settings, maxTurns, simpleMode));
		}
	}

	/** Runs on the executor: wiki prefetch + LLM calls, streamed to the panel. */
	private void runPipeline(String question, GameCapture capture, Llm.Settings settings,
		int maxTurns, boolean simpleMode)
	{
		// Locals, not fields: shutDown nulls the fields, and this method may
		// still be finishing an HTTP read on the dying daemon thread.
		CopilotPipeline pipeline = this.pipeline;
		CopilotPanel panel = this.panel;
		IconStore iconStore = this.iconStore;
		if (pipeline == null || panel == null)
		{
			return;
		}
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

		List<CopilotPipeline.Exchange> history = new ArrayList<>();
		synchronized (conversation)
		{
			for (Turn turn : conversation)
			{
				history.add(turn.exchange);
			}
		}

		try
		{
			CopilotPipeline.Result result =
				pipeline.answer(question, history, capture, settings, maxTurns,
					simpleMode, listener);
			log.debug("answered in {}ms, {} fact blocks, {} tool calls, {} context chars",
				result.millis, result.factBlocks, result.toolLog.size(), result.contextChars);
			String meta = answerMeta(result, gson);
			// Decoration runs on the worker (it may fetch the GE
			// catalogue); a failure costs only the styling.
			String decorated = null;
			try
			{
				decorated = AnswerDecorator
					.build(capture, result.route != null ? result.route.entities : null,
						pipeline.knownMonsterNames(), pipeline.knownItems(), iconStore)
					.decorate(MarkdownHtml.toHtml(result.answer));
			}
			catch (Exception e)
			{
				log.warn("answer decoration failed", e);
			}
			String decoratedHtml = decorated;
			synchronized (conversation)
			{
				conversation.add(new Turn(
					new CopilotPipeline.Exchange(question, result.answer, result.subject),
					decoratedHtml, meta));
			}
			SwingUtilities.invokeLater(() ->
				panel.showAnswerDone(result.answer, decoratedHtml, result.millis / 1000.0,
					capture.bank != null, meta));
		}
		catch (Exception e)
		{
			log.warn("Copilot pipeline failed", e);
			SwingUtilities.invokeLater(() -> panel.showError(friendlyError(e)));
		}
	}

	/** One dim HTML line under each answer disclosing what the model was
	 * given. Wiki pages link their edit history: the contributors hold the
	 * copyright (CC BY-NC-SA). Underlines are explicit because the shared
	 * stylesheet suppresses them for entity links. */
	static String answerMeta(CopilotPipeline.Result result, Gson gson)
	{
		StringBuilder sb = new StringBuilder();
		if (result.factTitles.isEmpty())
		{
			sb.append("no facts retrieved");
		}
		else
		{
			sb.append("facts: ");
			for (int i = 0; i < result.factTitles.size(); i++)
			{
				String title = result.factTitles.get(i);
				String page = CopilotPipeline.factSourcePage(title);
				String display = SwingUtil.escapeHtml(CopilotPipeline.factDisplayTitle(title));
				sb.append(i > 0 ? "; " : "")
					.append(page == null ? display : historyLink(page, display));
			}
		}
		if (result.toolLog != null && !result.toolLog.isEmpty())
		{
			sb.append(" | tools: ");
			for (int i = 0; i < result.toolLog.size(); i++)
			{
				sb.append(i > 0 ? ", " : "").append(toolHtml(gson, result.toolLog.get(i)));
			}
		}
		if (result.usage != null)
		{
			sb.append(" | tokens ").append(result.usage.promptTokens).append(" in / ")
				.append(result.usage.completionTokens).append(" out");
		}
		if (!result.suspectNames.isEmpty())
		{
			sb.append(" | unverified names: ")
				.append(SwingUtil.escapeHtml(String.join(", ", result.suspectNames)));
		}
		return sb.toString();
	}

	private static String historyLink(String page, String escapedText)
	{
		return "<a href='" + AnswerDecorator.wikiUrl(page) + "?action=history'><u>"
			+ escapedText + "</u></a>";
	}

	/** One tool-log entry ("name({json})") as footer HTML. Single-argument
	 * calls display just the value; page-backed tools (wiki_page,
	 * item_stats) link the page like a fact. Anything unexpected falls back
	 * to the raw entry. */
	private static String toolHtml(Gson gson, String entry)
	{
		int paren = entry.indexOf('(');
		if (paren > 0 && entry.endsWith(")"))
		{
			String name = entry.substring(0, paren);
			try
			{
				JsonObject args = gson.fromJson(
					entry.substring(paren + 1, entry.length() - 1), JsonObject.class);
				if (args.size() == 1)
				{
					JsonElement only = args.entrySet().iterator().next().getValue();
					if (only.isJsonPrimitive() && only.getAsJsonPrimitive().isString())
					{
						String value = only.getAsString();
						String display = SwingUtil.escapeHtml(name + "(" + value + ")");
						return "wiki_page".equals(name) || "item_stats".equals(name)
							? historyLink(value, display) : display;
					}
				}
			}
			catch (RuntimeException e)
			{
				log.debug("unparseable tool log entry: {}", entry);
			}
		}
		return SwingUtil.escapeHtml(entry);
	}

	private Llm.Settings llmSettings()
	{
		LlmProvider provider = config.provider();
		String baseUrl = provider.baseUrl != null ? provider.baseUrl : config.apiBaseUrl();
		return new Llm.Settings(baseUrl, config.apiKey(), config.model(),
			config.temperature(), config.maxTokens());
	}

	/** Maps failures by kind -- HTTP status class or transport error -- with
	 * the server's own message as the detail. No per-endpoint cases. */
	private static String friendlyError(Exception e)
	{
		if (e instanceof EmptyAnswerException)
		{
			return "The model didn't produce an answer: " + e.getMessage()
				+ ". Press Enter to resubmit.";
		}
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
			// Code 0: a mid-stream failure that carried no HTTP status.
			if (he.code == 0)
			{
				return "Model provider error - resubmit in a moment. " + detail;
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
	// Event capture
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		events.log("gameState", Map.of("state", event.getGameState().name()));
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			pipeline.onLogin();
		}
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
		// Dev aid: ::place prints what the prompt's location line will say
		// for the current position.
		if ("place".equalsIgnoreCase(event.getCommand()))
		{
			WorldPoint wp = GameStateReader.playerLocation(client);
			String msg;
			if (wp == null)
			{
				msg = "Copilot place: no player location";
			}
			else
			{
				String name = Areas.resolve(wp);
				if (name == null)
				{
					name = wp.getX() >= 6400
						? "(unresolved; prompt says: sailing at sea on a boat)"
						: wp.getY() >= 6400
						? "(unresolved; prompt says: underground area)"
						: "(unresolved; prompt omits place)";
				}
				msg = "Copilot place: (" + wp.getX() + ", " + wp.getY() + ", "
					+ wp.getPlane() + ") region " + wp.getRegionID() + " -> " + name;
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
		}
		// Dev aid: ::house dumps impostor-resolved scene objects and open
		// widget texts, for calibrating the POH facility whitelist.
		if ("house".equalsIgnoreCase(event.getCommand()))
		{
			File f = dumpHouseCalibration();
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", f != null
				? "Copilot: house dump written to " + f.getName()
				: "Copilot: house dump FAILED, see client log", null);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		events.log("stat", Map.of(
			"skill", event.getSkill().name(),
			"xp", event.getXp(),
			"level", event.getLevel(),
			"boosted", event.getBoostedLevel()));
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.BANK)
		{
			// The drift audit runs only on the visit's first capture:
			// later captures fire per withdrawal/deposit, and diffing
			// those merely echoes each interaction.
			List<Map<String, Object>> fresh = reader.itemList(event.getItemContainer());
			if (bankDriftAuditArmed)
			{
				bankDriftAuditArmed = false;
				String drift = BankMutations.drift(bankStore.contents(), fresh);
				if (drift != null)
				{
					log.debug("Bank snapshot drift at capture: {}", drift);
				}
			}
			bankStore.update(fresh);
		}
		bankMutations.containerChanged(id, event.getItemContainer());
		if (events.logOpen())
		{
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("containerId", id);
			data.put("itemCount", event.getItemContainer().count());
			events.log("itemContainer", data);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Game messages are useful question context; player chat is not.
		// The disk log applies the same filter: other players' chat must
		// never land in a file.
		if (event.getType() == ChatMessageType.GAMEMESSAGE
			|| event.getType() == ChatMessageType.SPAM)
		{
			events.buffer("chat", Map.of("message", event.getMessage()));
			events.log("chat", Map.of(
				"chatType", event.getType().name(),
				"message", event.getMessage()));
		}
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
		events.log("death", data);
		events.buffer("death", data);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		List<Map<String, Object>> items = new ArrayList<>();
		for (ItemStack stack : event.getItems())
		{
			items.add(Map.of(
				"id", stack.getId(),
				"name", reader.itemName(stack.getId()),
				"quantity", stack.getQuantity()));
		}
		Map<String, Object> data = Map.of(
			"npc", String.valueOf(event.getNpc().getName()),
			"items", items);
		events.log("npcLoot", data);
		events.buffer("npcLoot", data);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		bankMutations.menuClicked(event.getMenuOption());
		if (!config.logMenuClicks())
		{
			return;
		}
		events.log("click", Map.of(
			"option", String.valueOf(event.getMenuOption()),
			"target", String.valueOf(event.getMenuTarget())));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankMutations.depositBoxOpened(
				client.getItemContainer(InventoryID.INV),
				client.getItemContainer(InventoryID.WORN));
		}
		// Calibration aid: interfaces opened inside the POH dump their texts
		// a tick later (populated by then; some block ::house input).
		if (inPoh())
		{
			pendingWidgetDumpGroup = event.getGroupId();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankMutations.depositBoxClosed();
		}
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankDriftAuditArmed = true;
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		// The client fires an all-slots-EMPTY storm during login; those are
		// not real offer changes and must not wipe tracked collectables.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		GrandExchangeOffer offer = event.getOffer();
		bankMutations.offerChanged(event.getSlot(), offer.getState(),
			offer.getItemId(), offer.getQuantitySold(), offer.getTotalQuantity());
	}

	// ------------------------------------------------------------------
	// Snapshot dump (dev aid for the offline eval runner)
	// ------------------------------------------------------------------

	private File dumpSnapshot(String label)
	{
		try
		{
			GameCapture cap = reader.buildCapture();
			File out = new File(DATA_DIR, "snapshot-" + label + "-" + System.currentTimeMillis() + ".json");
			Files.write(out.toPath(), gson.newBuilder().setPrettyPrinting().create()
				.toJson(cap).getBytes(StandardCharsets.UTF_8));
			log.info("Snapshot written: {}", out.getAbsolutePath());
			return out;
		}
		catch (Exception e)
		{
			log.error("Snapshot failed", e);
			return null;
		}
	}

	/** One calibration file: every distinct scene object (impostor-resolved,
	 * so varbit-built house furniture reports what is actually built) and
	 * the texts of every open widget group. Run inside the POH, then again
	 * with the nexus Teleport Menu open to identify its group ID. */
	private File dumpHouseCalibration()
	{
		try
		{
			StringBuilder sb = new StringBuilder();
			WorldPoint wp = GameStateReader.playerLocation(client);
			sb.append("location: ").append(wp).append(" -> ")
				.append(wp != null ? Areas.resolve(wp) : null).append('\n');

			sb.append("\n-- scene objects (deduped by resolved id) --\n");
			Map<Integer, String> seen = new TreeMap<>();
			for (Tile[][] plane : client.getScene().getTiles())
			{
				for (Tile[] row : plane)
				{
					for (Tile tile : row)
					{
						if (tile == null)
						{
							continue;
						}
						describeObjects(seen, tile.getGameObjects());
						describeObjects(seen, tile.getWallObject(),
							tile.getDecorativeObject(), tile.getGroundObject());
					}
				}
			}
			seen.values().forEach(line -> sb.append(line).append('\n'));

			sb.append("\n-- open widget groups with text --\n");
			Map<Integer, List<String>> byGroup = new TreeMap<>();
			for (Widget root : client.getWidgetRoots())
			{
				collectWidgetTexts(root, byGroup, 0);
			}
			byGroup.forEach((group, texts) ->
				sb.append("group ").append(group).append(": ").append(texts).append('\n'));

			File out = new File(DATA_DIR, "house-calibration-" + System.currentTimeMillis() + ".txt");
			Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
			log.info("House calibration written: {}", out.getAbsolutePath());
			return out;
		}
		catch (Exception e)
		{
			log.error("House calibration failed", e);
			return null;
		}
	}

	private void describeObjects(Map<Integer, String> out, TileObject... objects)
	{
		for (TileObject obj : objects)
		{
			if (obj == null)
			{
				continue;
			}
			ObjectComposition comp = client.getObjectDefinition(obj.getId());
			if (comp != null && comp.getImpostorIds() != null)
			{
				comp = comp.getImpostor();
			}
			if (comp == null || "null".equals(comp.getName()))
			{
				continue;
			}
			out.putIfAbsent(comp.getId(), String.format("id=%d base=%d name=%s ops=%s",
				comp.getId(), obj.getId(), comp.getName(), Arrays.toString(comp.getActions())));
		}
	}

	private boolean inPoh()
	{
		WorldPoint wp = GameStateReader.playerLocation(client);
		return wp != null && "Player Owned House".equals(Areas.resolve(wp));
	}

	private void dumpWidgetGroup(int group)
	{
		try
		{
			Map<Integer, List<String>> byGroup = new TreeMap<>();
			for (Widget root : client.getWidgetRoots())
			{
				collectWidgetTexts(root, byGroup, 0);
			}
			List<String> texts = byGroup.get(group);
			if (texts == null)
			{
				return;
			}
			File out = new File(DATA_DIR, "house-widget-" + group + "-"
				+ System.currentTimeMillis() + ".txt");
			Files.write(out.toPath(), ("group " + group + ": " + texts)
				.getBytes(StandardCharsets.UTF_8));
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"Copilot: widget dump written to " + out.getName(), null);
		}
		catch (Exception e)
		{
			log.error("Widget dump failed", e);
		}
	}

	private void collectWidgetTexts(Widget widget, Map<Integer, List<String>> byGroup, int depth)
	{
		if (widget == null || depth > 12)
		{
			return;
		}
		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			List<String> texts = byGroup.computeIfAbsent(widget.getId() >>> 16,
				k -> new ArrayList<>());
			if (texts.size() < 60)
			{
				texts.add(text);
			}
		}
		for (Widget[] children : new Widget[][]{widget.getStaticChildren(),
			widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				collectWidgetTexts(child, byGroup, depth + 1);
			}
		}
	}

}
