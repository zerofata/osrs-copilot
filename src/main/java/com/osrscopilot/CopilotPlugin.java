package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	/**
	 * Our own worker for the pipeline. RuneLite's injected executor is a
	 * SINGLE shared thread that also runs every plugin's scheduled tasks,
	 * config saves, and notifications -- parking it on a multi-minute LLM
	 * stream stalls the whole client's background work. One private thread
	 * keeps today's one-question-at-a-time ordering and dies with the plugin.
	 */
	private ExecutorService pipelineExecutor;

	private CopilotPipeline pipeline;
	private CopilotPanel panel;
	private NavigationButton navButton;
	private IconStore iconStore;
	/** Toolbar icon: the plugin's wizard-hat mark, bundled as a resource. */
	private BufferedImage navIcon;

	// Question flow must hop threads: Swing EDT (submit) -> client thread
	// (capture; Quest.getState runs scripts, so never from a script callback)
	// -> executor (pipeline; network + LLM) -> Swing EDT (render).
	private volatile String pendingQuestion;
	private String pendingSnapshotLabel;
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
		// Nothing may leave the client before the user opts in -- not even
		// static vocabulary downloads from our own GitHub. Warming runs when
		// the copilot is enabled, here or from onConfigChanged.
		if (config.enableCopilot())
		{
			pipelineExecutor.execute(pipeline::warmCaches);
		}
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
		if ("enableCopilot".equals(event.getKey()) && config.enableCopilot())
		{
			pipelineExecutor.execute(pipeline::warmCaches);
		}
	}

	/**
	 * A theme is baked into every component at construction, so switching
	 * means building a fresh panel. The conversation replays into it exactly
	 * as rendered (decoration, meta line and all), so a rebuild never costs
	 * the player their chat or its styling. After a theme switch old
	 * messages keep the entity colors of the theme they were answered
	 * under -- an acceptable trade against re-running decoration, which
	 * would need the game capture of every past turn.
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
		// Plugin Hub contract: features that talk to a third-party server are
		// opt-in. Nothing leaves the client until the user flips this on.
		if (!config.enableCopilot())
		{
			panel.showError("Enable the copilot in the plugin settings first "
				+ "(wrench icon -> OSRS Copilot -> Enable copilot).");
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

		if (pendingQuestion != null)
		{
			String question = pendingQuestion;
			pendingQuestion = null;
			GameCapture capture = reader.buildCapture();
			Llm.Settings settings = llmSettings();
			int maxTurns = config.maxToolTurns();
			pipelineExecutor.execute(() -> runPipeline(question, capture, settings, maxTurns));
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
				pipeline.answer(question, history, capture, settings, maxTurns, listener);
			log.debug("answered in {}ms, {} fact blocks, {} tool calls, {} context chars",
				result.millis, result.factBlocks, result.toolLog.size(), result.contextChars);
			String meta = answerMeta(result, gson);
			// Entity decoration (wiki links, quest/item state colors) runs
			// here on the worker thread: it may fetch the GE catalogue. A
			// failure costs only the styling, never the answer.
			String decorated = null;
			try
			{
				decorated = AnswerDecorator
					.build(capture, result.route != null ? result.route.entities : null,
						pipeline.knownItemNames(), pipeline.knownItemIds(), iconStore)
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
	 * given: retrieved facts, tools it called, and token cost. Keeps the
	 * pipeline inspectable in-game instead of only in offline eval runs.
	 * Every wiki page the answer drew on -- prefetched fact or tool fetch --
	 * links its edit history: the page's contributors hold the copyright
	 * (CC BY-NC-SA), and a history link is the accepted way to credit them.
	 * Underlined explicitly: the shared stylesheet suppresses underlines
	 * for the answer body's entity links. */
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
		return new Llm.Settings(config.apiBaseUrl(), config.apiKey(), config.model(),
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
			// Authoritative capture. The drift audit (how far the tracked
			// prediction strayed while the bank was closed) runs only on the
			// visit's first capture: later captures fire per withdrawal and
			// deposit, and diffing those merely echoes each interaction.
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
		// Game messages (drops, task completions, level-ups) are useful
		// question context; player chat is not. The disk log applies the
		// SAME filter: other players' public and private chat is their
		// data, and must never land in a file -- diagnostics or not.
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

}
