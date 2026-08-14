package com.osrscopilot.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Official OSRS hiscores: the player's own boss kill counts and activity
 * scores. Kill counts live server-side and are invisible to the client, yet
 * they are the ground truth for the player's experience -- without them the
 * model fills the gap with "assume beginner", which is worse than useless.
 * Fetched deterministically by player name, briefly cached.
 */
@Slf4j
class Hiscores
{
	private static final String URL =
		"https://secure.runescape.com/m=hiscore_oldschool/index_lite.json?player=";
	private static final long TTL_MS = 10 * 60 * 1000;

	private final Http http;
	private String cachedPlayer;
	private long cachedAtMs;
	private Map<String, Long> cachedScores;

	Hiscores(Http http)
	{
		this.http = http;
	}

	/**
	 * Ranked activities (boss name -> kill count, minigame -> score) for the
	 * player. Null when the lookup fails or nothing is ranked -- callers omit
	 * the field rather than inventing a value.
	 */
	synchronized Map<String, Long> rankedActivities(String player)
	{
		if (player == null || player.isEmpty())
		{
			return null;
		}
		// Client names may use non-breaking spaces.
		player = player.replace('\u00A0', ' ');
		if (player.equals(cachedPlayer) && System.currentTimeMillis() - cachedAtMs < TTL_MS)
		{
			return cachedScores;
		}
		Map<String, Long> scores = null;
		try
		{
			JsonObject r = http.getJson(URL + Http.enc(player));
			Map<String, Long> found = new LinkedHashMap<>();
			for (JsonElement e : r.getAsJsonArray("activities"))
			{
				JsonObject a = e.getAsJsonObject();
				long score = a.get("score").getAsLong();
				if (score > 0)
				{
					found.put(a.get("name").getAsString(), score);
				}
			}
			scores = found.isEmpty() ? null : found;
		}
		catch (Exception e)
		{
			log.debug("hiscores lookup failed for {}", player, e);
		}
		cachedPlayer = player;
		cachedAtMs = System.currentTimeMillis();
		cachedScores = scores;
		return cachedScores;
	}
}
