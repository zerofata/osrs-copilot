package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles everything the model reads: the system prompt, the user message
 * (player state + retrieved facts), and the bounded conversation history.
 * The one place where game state is rendered into text.
 */
class PromptBuilder
{
	private static final int HISTORY_MAX_EXCHANGES = 6;
	private static final int HISTORY_MAX_CHARS = 8000;

	/** The system prompt; only the closing style rule varies by mode.
	 * Exactly two byte-stable variants exist, so provider prompt caching
	 * gets an identical prefix per mode. */
	static String systemPrompt(boolean simple)
	{
		return SYNTH_SYSTEM + (simple
			? "- Verify with tools as thoroughly as ever -- every rule above still "
			+ "applies. Only the final answer changes: write it as short plain "
			+ "conversational sentences, like a chat message. No markdown syntax "
			+ "may appear in it: no *, **, #, |, backticks, bullet points, or "
			+ "numbered lists -- state emphasis and structure in words instead. "
			+ "Give the recommendation and the verified facts that justify it, "
			+ "without alternatives or caveats unless asked."
			: "- Be concrete and concise; recommend rather than exhaustively enumerate, "
			+ "but use steps or lists when the question calls for them.");
	}

	private static final String SYNTH_SYSTEM =
		"You are an OSRS copilot running inside RuneLite. Answer the player's question "
		+ "using their live game state and the retrieved facts provided. Principles:\n"
		+ "- PLAYER STATE is what the client observes. A field or fact block marked "
		+ "\"complete\" is exhaustive (absence there means not owned -- never search or "
		+ "guess for what it already rules out). Everything else not shown is UNKNOWN, "
		+ "not absent: never infer missing items, progress, or experience from absence -- say "
		+ "what you can't see and give conditional advice ('if you have X, otherwise Y') where "
		+ "it matters.\n"
		+ "- Respect the player's account type (an IRONMAN cannot use the Grand Exchange).\n"
		+ "- Retrieved facts were fetched from the OSRS Wiki just now and are authoritative. "
		+ "Prefer them over memory, and don't assert game facts from memory that they don't "
		+ "support. Use tools if something essential is missing.\n"
		+ "- Personalize: check requirements against the player's actual levels, quest progress, "
		+ "kill counts (boss_kc_and_activity_scores; a missing boss means few kills, not "
		+ "exactly zero), and owned items.\n"
		+ "- PRICES: never state a coin price, price range, or cost claim ('nearly free', "
		+ "'expensive') from memory -- prices move constantly and your training-time prices are "
		+ "often wrong by 10-100x. Every price you mention must come from a GE price fact or a "
		+ "ge_price call in this conversation (batch all candidates into ONE call). If an item "
		+ "isn't priced by then, name it without a number.\n"
		+ "- Gear and upgrade recommendations must come from the retrieved equipment tables and "
		+ "facts, which include items released after your training data; your remembered meta is "
		+ "incomplete. Recommend the alternatives the tables list at the player's budget and "
		+ "stats, and check ownership of those alternatives before declaring a purchase needed. "
		+ "If you add an option from memory, say that's what it is.\n"
		+ "- slayer_task is the player's current Slayer assignment. On-task-only effects "
		+ "(Slayer helmet/black mask bonuses, task-only areas) apply ONLY against that exact "
		+ "creature; the player is off-task for everything else.\n"
		+ "- Quest progress shown in the facts is authoritative. For any quest not shown, verify "
		+ "with the quest_status tool before advising quest-gated content.\n"
		+ "- If the question is ambiguous, state the interpretation you chose in one short sentence.\n"
		+ "- If the facts don't answer the question, say so honestly rather than guessing.\n"
		+ "- When comparing or ranking options, ground the verdict in retrieved numbers "
		+ "(defensive stats, requirements, mechanics) or a retrieved recommendation. If nothing "
		+ "retrieved settles it, present the trade-offs and say the data doesn't settle it -- "
		+ "never manufacture a confident winner.\n"
		+ "- In an ongoing conversation, PLAYER STATE and RETRIEVED FACTS accompany the latest "
		+ "question and reflect the current moment; earlier answers may describe older state.\n";

	private final Gson gson;
	private final WikiApi wiki;
	private final Hiscores hiscores;

	PromptBuilder(Gson gson, WikiApi wiki, Hiscores hiscores)
	{
		this.gson = gson;
		this.wiki = wiki;
		this.hiscores = hiscores;
	}

	String buildUserMessage(String question, GameCapture cap, List<String> facts,
		boolean bankInlined, boolean ownershipComplete, boolean offerOwnedSearch)
	{
		Map<String, Object> state = playerState(cap, bankInlined,
			ownershipComplete, offerOwnedSearch);

		StringBuilder sb = new StringBuilder();
		sb.append("QUESTION: ").append(question).append("\n\n");
		sb.append("PLAYER STATE:\n").append(gson.toJson(state));
		if (cap.recentEvents != null && !cap.recentEvents.isEmpty())
		{
			sb.append("\n\nRECENT SESSION EVENTS (newest last):\n")
				.append(gson.toJson(cap.recentEvents));
		}
		if (!facts.isEmpty())
		{
			sb.append("\n\nRETRIEVED FACTS (fetched from OSRS Wiki / GE just now):\n\n")
				.append(String.join("\n", facts));
		}
		return sb.toString();
	}

	/** The PLAYER STATE block. */
	private Map<String, Object> playerState(GameCapture cap, boolean bankInlined,
		boolean ownershipComplete, boolean offerOwnedSearch)
	{
		Map<String, Object> state = new LinkedHashMap<>();
		// Without today's date the model assumes its training-time year.
		// Lives here so the system prompt stays byte-identical for caching.
		state.put("date", java.time.LocalDate.now().toString());
		state.put("player", cap.playerName);
		state.put("account_type", cap.accountTypeName());
		state.put("combat_level", cap.combatLevel);
		state.put("location", groundedLocation(cap));
		state.put("skills", cap.skills);
		// Kill counts are server-side state the client can't see; without
		// them the model assumes a beginner.
		Map<String, Long> scores = hiscores.rankedActivities(cap.playerName);
		if (scores != null)
		{
			state.put("boss_kc_and_activity_scores", scores);
		}
		if (cap.boostsOrDrains != null && !cap.boostsOrDrains.isEmpty())
		{
			state.put("boosts_or_drains", cap.boostsOrDrains);
		}
		if (cap.questStates != null)
		{
			long finished = cap.questStates.values().stream()
				.filter("FINISHED"::equals).count();
			state.put("quests_finished", finished);
			List<String> inProgress = new ArrayList<>();
			cap.questStates.forEach((q, s) -> {
				if ("IN_PROGRESS".equals(s))
				{
					inProgress.add(q);
				}
			});
			state.put("quests_in_progress", inProgress);
		}
		if (cap.slayerTask != null)
		{
			state.put("slayer_task", cap.slayerTask);
		}
		if (cap.unlocks != null)
		{
			state.put("unlocks", cap.unlocks);
		}
		state.put("inventory", itemStrings(cap.inventory));
		state.put("equipment", itemNamesOnly(cap.equipment));
		state.put("bank", bankState(cap, bankInlined, ownershipComplete, offerOwnedSearch));
		addDiaryState(state, cap);
		return state;
	}

	/** Data plus status, no embedded instructions; the system prompt
	 * defines the semantics of "complete"/"unknown" once. */
	private Map<String, Object> bankState(GameCapture cap, boolean bankInlined,
		boolean ownershipComplete, boolean offerOwnedSearch)
	{
		Map<String, Object> bank = new LinkedHashMap<>();
		if (cap.bank == null)
		{
			bank.put("status", "unknown");
		}
		else
		{
			bank.put("status", bankInlined ? "complete" : "summarized");
			if (bankInlined)
			{
				bank.put("items", itemStrings(cap.bank));
			}
			else
			{
				bank.put("item_count", cap.bank.size());
			// The access note must match the tools actually offered;
			// referencing a withheld tool sends the model chasing it.
				String factsAreDefinitive =
					"the Ownership fact in RETRIEVED FACTS is the definitive bank "
						+ "answer for every item the facts mention, and each tool "
						+ "result carries the same ownership note for the items IT "
						+ "mentions -- state ownership plainly from these, never "
						+ "conditionally ('if you have one')";
				bank.put("access", !offerOwnedSearch
					? factsAreDefinitive
					: ownershipComplete
						? factsAreDefinitive + "; for items the facts do NOT "
							+ "mention, use ONE search_owned_items call with all "
							+ "queries batched"
						: "ownership of items the facts mention is in RETRIEVED FACTS, "
							+ "and each tool result carries an ownership note for the "
							+ "items IT mentions; for anything still uncovered use ONE "
							+ "search_owned_items call with all queries batched");
			}
		}
		return bank;
	}

	private static void addDiaryState(Map<String, Object> state, GameCapture cap)
	{
		if (cap.diaries != null && !cap.diaries.isEmpty())
		{
			// Only areas with completed tiers are listed; the note
			// carries the semantics for the rest.
			Map<String, Object> completed = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : cap.diaries.entrySet())
			{
				if (e.getValue() instanceof List && !((List<?>) e.getValue()).isEmpty())
				{
					completed.put(e.getKey(), e.getValue());
				}
			}
			Map<String, Object> diaries = new LinkedHashMap<>();
			diaries.put("status", "authoritative, from the game client");
			diaries.put("completed_tiers", completed);
			diaries.put("note", "any area or tier not listed here is NOT complete. "
				+ "Per-task progress inside an incomplete tier is not visible: present its "
				+ "tasks as a checklist, never claim which individual tasks are done");
			state.put("achievement_diaries", diaries);
		}
	}

	/** Resolve raw coordinates to named places; the coordinates stay in
	 * as supplementary data. */
	private Map<String, Object> groundedLocation(GameCapture cap)
	{
		if (cap.location == null)
		{
			return null;
		}
		// JSON round-trips turn ints into doubles ("y":3220.0); tidy them.
		Map<String, Object> out = new LinkedHashMap<>();
		cap.location.forEach((k, v) ->
			out.put(k, v instanceof Number ? ((Number) v).longValue() : v));
		Object xo = cap.location.get("x");
		Object yo = cap.location.get("y");
		if (xo instanceof Number && yo instanceof Number)
		{
			int x = ((Number) xo).intValue();
			int y = ((Number) yo).intValue();
		// The primary place must be one the player can BE in: a dungeon's
		// surface marker is its entrance, not the dungeon.
			WikiApi.NamedPoint place = null;
			WikiApi.NamedPoint second = null;
			WikiApi.NamedPoint entrance = null;
			for (WikiApi.NamedPoint p : wiki.nearestPlaces(x, y, 4))
			{
				if (Math.round(Math.sqrt(WikiApi.distSq(p, x, y))) > 300)
				{
					break;
				}
				if (p.entrance)
				{
					entrance = entrance == null ? p : entrance;
				}
				else if (place == null)
				{
					place = p;
				}
				else if (second == null)
				{
					second = p;
				}
			}
			if (place != null)
			{
				long dist = Math.round(Math.sqrt(WikiApi.distSq(place, x, y)));
				out.put("place", place.name + (dist <= 40 ? "" : " (~" + dist + " tiles away)"));
				if (second != null)
				{
					out.put("also_near", second.name);
				}
			}
			if (entrance != null)
			{
				long dist = Math.round(Math.sqrt(WikiApi.distSq(entrance, x, y)));
				out.put("nearby_entrance", entrance.name
					+ " (surface entrance ~" + dist + " tiles away; the player is NOT inside)");
			}
			if (place == null && entrance == null && y >= 6400)
			{
				// Above y=6400 is dungeons and instances; the index
				// covers only the surface map.
				out.put("place", "underground or instanced area (off the surface map)");
			}
			// No named place within range; say nothing rather than guess.
		}
		return out;
	}

	/** Newest-first budget: keep the most recent exchanges within both the
	 * exchange and character caps, preserving chronological order. */
	static List<CopilotPipeline.Exchange> boundedHistory(List<CopilotPipeline.Exchange> history)
	{
		List<CopilotPipeline.Exchange> kept = new ArrayList<>();
		if (history == null)
		{
			return kept;
		}
		int chars = 0;
		for (int i = history.size() - 1; i >= 0 && kept.size() < HISTORY_MAX_EXCHANGES; i--)
		{
			CopilotPipeline.Exchange ex = history.get(i);
			chars += ex.question.length() + ex.answer.length();
			if (chars > HISTORY_MAX_CHARS && !kept.isEmpty())
			{
				break;
			}
			kept.add(0, ex);
		}
		return kept;
	}

	private static List<String> itemStrings(List<Map<String, Object>> items)
	{
		List<String> out = new ArrayList<>();
		if (items != null)
		{
			for (Map<String, Object> item : items)
			{
				long qty = item.get("quantity") instanceof Number
					? ((Number) item.get("quantity")).longValue() : 1;
				out.add(item.get("name") + (qty > 1 ? " x" + qty : ""));
			}
		}
		return out;
	}

	private static List<String> itemNamesOnly(List<Map<String, Object>> items)
	{
		List<String> out = new ArrayList<>();
		if (items != null)
		{
			for (Map<String, Object> item : items)
			{
				out.add(String.valueOf(item.get("name")));
			}
		}
		return out;
	}
}
