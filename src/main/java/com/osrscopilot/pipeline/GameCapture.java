package com.osrscopilot.pipeline;

import java.util.List;
import java.util.Map;

/**
 * Immutable-by-convention snapshot of everything the pipeline needs, captured
 * on the client thread at ask time. The pipeline then runs entirely off the
 * client thread against this object -- it never touches the Client API.
 */
public class GameCapture
{
	public String playerName;
	public int combatLevel;
	public Map<String, Object> location;
	public int accountType;
	public Map<String, Integer> skills;
	public Map<String, Integer> skillXp;
	public Map<String, Integer> boostsOrDrains;
	public Map<String, String> questStates;
	public Map<String, Object> diaries;
	/** {creature, remaining, location}; creature is absent if unnameable. */
	public Map<String, Object> slayerTask;
	/** Item lists as {name, quantity} maps. bank may be null (never seen). */
	public List<Map<String, Object>> inventory;
	public List<Map<String, Object>> equipment;
	public List<Map<String, Object>> bank;
	/** When the bank contents were last captured (live or persisted). */
	public Long bankCapturedAtMs;
	public List<Map<String, Object>> recentEvents;

	public static final Map<Integer, String> ACCOUNT_TYPES = Map.of(
		0, "NORMAL", 1, "IRONMAN", 2, "ULTIMATE_IRONMAN", 3, "HARDCORE_IRONMAN",
		4, "GROUP_IRONMAN", 5, "HARDCORE_GROUP_IRONMAN", 6, "UNRANKED_GROUP_IRONMAN");

	public String accountTypeName()
	{
		return ACCOUNT_TYPES.getOrDefault(accountType, "NORMAL");
	}
}
