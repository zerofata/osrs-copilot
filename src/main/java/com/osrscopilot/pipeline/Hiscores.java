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
 *
 * Cached per player for the whole login session: hiscores only persist on
 * logout/world hop, so while logged in the cached value mirrors the source
 * exactly and a refetch can never return anything newer. The plugin
 * invalidates on login, which is the one moment fresh data can exist.
 */
@Slf4j
class Hiscores
{
	private static final String URL =
		"https://secure.runescape.com/m=hiscore_oldschool/index_lite.json?player=";

	private final Http http;
	private String cachedPlayer;
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
		if (player.equals(cachedPlayer))
		{
			return cachedScores;
		}
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
			cachedPlayer = player;
			cachedScores = found.isEmpty() ? null : found;
		}
		catch (Exception e)
		{
			// Not cached: a session-long cache must not pin a transient
			// failure; the next question retries.
			log.debug("hiscores lookup failed for {}", player, e);
			return null;
		}
		return cachedScores;
	}

	/** Called on login: the only moment the hiscores can have changed. */
	synchronized void invalidate()
	{
		cachedPlayer = null;
		cachedScores = null;
	}
}
