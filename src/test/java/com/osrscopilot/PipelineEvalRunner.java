package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.osrscopilot.pipeline.CopilotPipeline;
import com.osrscopilot.pipeline.EntityResolver;
import com.osrscopilot.pipeline.GameCapture;
import com.osrscopilot.pipeline.Llm;
import com.osrscopilot.pipeline.StreamListener;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;

/**
 * Offline eval harness: runs the in-plugin pipeline against a recorded
 * snapshot without launching the game. This is how the pipeline is iterated
 * on -- same code path the plugin uses in-game.
 *
 * Usage:
 *   --env FILE        KEY=VALUE file with PARASAIL_BASE_URL / PARASAIL_API_KEY
 *   --snapshot FILE   snapshot JSON (::probe dump)
 *   --question TEXT   the question to ask
 *   --battery FILE    run every question in a file instead (one per line;
 *                     "&gt; " prefix = follow-up in the same conversation;
 *                     "EVENT {json}" attaches a recent game event to the next
 *                     question; "ASSERT {json}" checks the previous question's
 *                     route; "ANSWER text" seeds the previous question's
 *                     answer so routeOnly/promptOnly runs exercise
 *                     answer-derived subject inheritance (full runs use the
 *                     model's real answer and ignore the seed);
 *                     # comments and blank lines skipped)
 *   --routeOnly true  run only the deterministic router + assertions -- no
 *                     LLM calls, free, fast route regression check
 *   --model NAME      model name (default deepseek-ai/DeepSeek-V4-Flash)
 *   --maxTokens N     per-response completion cap (default 4096; lower it to
 *                     reproduce reasoning-burn truncation deterministically)
 *
 * ASSERT keys: items/monsters/quests/pages/skills/needs/facilities require
 * those entries in the route (case-insensitive); a "not_" prefix requires
 * their absence. Example: ASSERT {"needs":["transport"],"not_facilities":["Bank"]}
 *
 * Battery runs write a JSONL artifact to eval/runs/ so runs diff mechanically.
 * Exits nonzero when any assertion fails.
 */
public class PipelineEvalRunner
{
	public static void main(String[] args) throws Exception
	{
		PrintStream out = new PrintStream(System.out, true, "UTF-8");
		Map<String, String> opts = parseArgs(args);
		Gson gson = new Gson();

		Map<String, String> env = new HashMap<>(System.getenv());
		if (opts.containsKey("env"))
		{
			for (String line : Files.readAllLines(new File(opts.get("env")).toPath(), StandardCharsets.UTF_8))
			{
				int eq = line.indexOf('=');
				if (eq > 0 && !line.trim().startsWith("#"))
				{
					env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
				}
			}
		}

		String baseUrl = env.getOrDefault("PARASAIL_BASE_URL", env.getOrDefault("OPENAI_BASE_URL", ""));
		String apiKey = env.getOrDefault("PARASAIL_API_KEY", env.getOrDefault("OPENAI_API_KEY", ""));
		String model = opts.getOrDefault("model", "deepseek-ai/DeepSeek-V4-Flash");
		int maxTokens = Integer.parseInt(opts.getOrDefault("maxTokens", "4096"));
		Llm.Settings settings = new Llm.Settings(baseUrl, apiKey, model, 0.2, maxTokens);
		if (!settings.isConfigured())
		{
			out.println("No endpoint configured: set PARASAIL_BASE_URL/PARASAIL_API_KEY or pass --env FILE");
			System.exit(1);
		}

		GameCapture cap = loadSnapshot(new File(opts.get("snapshot")), gson);
		out.printf("snapshot: %s (%s, cb %d, bank %s)%n", opts.get("snapshot"),
			cap.accountTypeName(), cap.combatLevel,
			cap.bank == null ? "none" : cap.bank.size() + " items");

		File cacheDir = new File(new File(System.getProperty("user.home"), ".runelite"),
			"osrs-copilot/cache");
		CopilotPipeline pipeline = new CopilotPipeline(new OkHttpClient(), gson, cacheDir);

		// Streaming listener mirrors what the plugin panel does, so this run
		// verifies the SSE path end-to-end.
		StreamListener listener = new StreamListener()
		{
			@Override
			public void onDelta(String text)
			{
				out.print(text);
				out.flush();
			}

			@Override
			public void onTurnDiscarded()
			{
				out.println("\n[partial turn discarded]");
			}

			@Override
			public void onStatus(String status)
			{
				out.println("[" + status + "]");
			}
		};

		// Battery file, or --question with optional --question2 follow-up.
		List<BatteryItem> items = new java.util.ArrayList<>();
		if (opts.containsKey("battery"))
		{
			items = parseBattery(new File(opts.get("battery")), gson);
		}
		else
		{
			items.add(new BatteryItem(opts.get("question"), false, null));
			if (opts.get("question2") != null)
			{
				items.add(new BatteryItem(opts.get("question2"), true, null));
			}
		}

		boolean routeOnly = "true".equals(opts.get("routeOnly"));
		boolean promptOnly = "true".equals(opts.get("promptOnly"));
		Map<String, String> promptDump = new LinkedHashMap<>();
		List<String> summary = new java.util.ArrayList<>();
		List<String> failures = new java.util.ArrayList<>();
		List<String> suspects = new java.util.ArrayList<>();
		List<String> errors = new java.util.ArrayList<>();
		List<CopilotPipeline.Exchange> history = new java.util.ArrayList<>();
		List<Map<String, Object>> baseEvents = cap.recentEvents;

		// Records are appended as they complete: a battery is minutes of API
		// calls, and a flaky endpoint must not cost the whole run's evidence.
		java.io.PrintWriter artifact = null;
		File artifactFile = null;
		if (opts.containsKey("battery"))
		{
			File runsDir = new File(new File(opts.get("battery")).getAbsoluteFile()
				.getParentFile(), "runs");
			runsDir.mkdirs();
			String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
			artifactFile = new File(runsDir, "run-" + stamp
				+ (routeOnly ? "-routes" : promptOnly ? "-prompts" : "") + ".jsonl");
			artifact = new java.io.PrintWriter(artifactFile, "UTF-8");
		}

		for (BatteryItem item : items)
		{
			if (!item.followUp)
			{
				history.clear();
			}
			cap.recentEvents = item.events != null ? item.events : baseEvents;

			out.println("\n########################################################");
			out.println("question: " + (item.followUp ? "(follow-up) " : "") + item.question
				+ (item.events != null ? "   [with simulated event]" : ""));

			// Follow-ups inherit the previous turn's entities, as in-game.
			EntityResolver.Resolution prev = history.isEmpty() ? null
				: history.get(history.size() - 1).entities;

			CopilotPipeline.Route route;
			CopilotPipeline.Result result = null;
			try
			{
				if (routeOnly)
				{
					route = pipeline.route(item.question, cap, prev);
					history.add(new CopilotPipeline.Exchange(item.question,
						item.answer != null ? item.answer : "",
						seededSubject(pipeline, route, item, cap)));
				}
				else if (promptOnly)
				{
					// Retrieval without synthesis: shows exactly what the model
					// would be given, at zero LLM cost.
					CopilotPipeline.Prepared prepared = pipeline.prepare(item.question, cap, prev);
					route = prepared.route;
					history.add(new CopilotPipeline.Exchange(item.question,
						item.answer != null ? item.answer : "",
						seededSubject(pipeline, route, item, cap)));
					out.println("\n=== BUILT PROMPT ===\n" + prepared.prompt + "\n=== END PROMPT ===");
					promptDump.put(item.question, prepared.prompt);
				}
				else
				{
					out.println("\n=== ANSWER (streaming) ===");
					// Matches CopilotConfig.maxToolTurns() so evals exercise
					// the same budget the plugin ships with.
					result = pipeline.answer(item.question, history, cap, settings, 4, listener);
					route = result.route;
					history.add(new CopilotPipeline.Exchange(item.question, result.answer,
						result.subject));
					out.println();
				}
			}
			catch (Exception e)
			{
				out.println("\nERROR: " + e);
				errors.add(trim(item.question, 40) + " :: " + e);
				JsonObject failed = new JsonObject();
				failed.addProperty("question", item.question);
				failed.addProperty("error", String.valueOf(e));
				writeRecord(artifact, gson, failed);
				continue;
			}

			out.printf("%nroute: items=%s monsters=%s quests=%s pages=%s skills=%s needs=%s facilities=%s diaryTier=%s bank=%s%n",
				route.entities.items, route.entities.monsters, route.entities.quests,
				route.entities.pages, route.entities.skills, route.needs,
				route.facilityPages, route.diaryTier, route.bankMode);

			List<String> assertFailures = checkAssertions(item.asserts, route);
			for (String f : assertFailures)
			{
				out.println("ASSERT FAIL: " + f);
				failures.add(trim(item.question, 40) + " :: " + f);
			}
			if (item.asserts != null && assertFailures.isEmpty())
			{
				out.println("asserts: PASS");
			}

			JsonObject rec = new JsonObject();
			rec.addProperty("question", item.question);
			rec.addProperty("followUp", item.followUp);
			rec.add("route", gson.toJsonTree(route));
			rec.addProperty("assertFailures", String.join("; ", assertFailures));
			if (promptDump.containsKey(item.question))
			{
				rec.addProperty("prompt", promptDump.get(item.question));
			}
			if (result != null)
			{
				Llm.Usage u = result.usage;
				out.printf("facts=%d contextChars=%d toolCalls=%s time=%.1fs%n",
					result.factBlocks, result.contextChars, result.toolLog, result.millis / 1000.0);
				out.printf("tokens: in=%d out=%d cached=%d llmCalls=%d%n",
					u.promptTokens, u.completionTokens, u.cachedPromptTokens, u.calls);
				if ("true".equals(opts.get("showPrompt")))
				{
					out.println("\n=== BUILT PROMPT ===\n" + result.prompt + "\n=== END PROMPT ===");
				}
				if (!result.suspectNames.isEmpty())
				{
					out.println("UNGROUNDED NAMES: " + result.suspectNames);
					suspects.add(trim(item.question, 40) + " :: " + result.suspectNames);
				}
				rec.addProperty("answer", result.answer);
				rec.add("suspectNames", gson.toJsonTree(result.suspectNames));
				rec.add("toolCalls", gson.toJsonTree(result.toolLog));
				rec.addProperty("seconds", result.millis / 1000.0);
				rec.addProperty("tokensIn", u.promptTokens);
				rec.addProperty("tokensOut", u.completionTokens);
				rec.addProperty("tokensCached", u.cachedPromptTokens);
				summary.add(String.format("%-52s %6.1fs  in=%-6d out=%-5d cached=%-6d calls=%d tools=%d",
					trim(item.question, 50), result.millis / 1000.0, u.promptTokens,
					u.completionTokens, u.cachedPromptTokens, u.calls, result.toolLog.size()));
			}
			writeRecord(artifact, gson, rec);
		}

		if (artifact != null)
		{
			artifact.close();
			out.println("\nrun artifact: " + artifactFile);
		}

		if (!summary.isEmpty())
		{
			out.println("\n=== SUMMARY ===");
			summary.forEach(out::println);
		}
		out.println("\n=== UNGROUNDED NAMES: " + (suspects.isEmpty() ? "NONE ==="
			: suspects.size() + " ANSWERS ==="));
		suspects.forEach(out::println);
		if (!errors.isEmpty())
		{
			out.println("\n=== ERRORS: " + errors.size() + " ===");
			errors.forEach(out::println);
		}
		out.println("\n=== ASSERTIONS: " + (failures.isEmpty() ? "ALL PASS ==="
			: failures.size() + " FAILED ==="));
		failures.forEach(out::println);
		// OkHttp keeps non-daemon pool threads alive; don't let them hang the JVM.
		System.exit(failures.isEmpty() ? 0 : 1);
	}

	/** LLM-free modes have no real answer; an ANSWER-seeded one stands in so
	 * the follow-up inheritance path (question + answer subject) still runs. */
	private static EntityResolver.Resolution seededSubject(CopilotPipeline pipeline,
		CopilotPipeline.Route route, BatteryItem item, GameCapture cap)
	{
		return item.answer != null
			? pipeline.subjectOf(route.entities, item.answer, cap) : route.entities;
	}

	private static void writeRecord(java.io.PrintWriter artifact, Gson gson, JsonObject rec)
	{
		if (artifact != null)
		{
			artifact.println(gson.toJson(rec));
			artifact.flush();
		}
	}

	/** Checks route expectations: each key lists entries that must be present
	 * in the corresponding route list ("not_" prefix: must be absent). */
	private static List<String> checkAssertions(JsonObject asserts, CopilotPipeline.Route route)
	{
		List<String> failures = new java.util.ArrayList<>();
		if (asserts == null)
		{
			return failures;
		}
		Map<String, List<String>> routeLists = new HashMap<>();
		routeLists.put("items", route.entities.items);
		routeLists.put("monsters", route.entities.monsters);
		routeLists.put("quests", route.entities.quests);
		routeLists.put("pages", route.entities.pages);
		routeLists.put("skills", route.entities.skills);
		routeLists.put("needs", route.needs);
		routeLists.put("facilities", route.facilityPages);
		routeLists.put("diary_tier",
			route.diaryTier == null ? List.of() : List.of(route.diaryTier));

		for (String key : asserts.keySet())
		{
			boolean negated = key.startsWith("not_");
			String listName = negated ? key.substring(4) : key;
			List<String> actual = routeLists.get(listName);
			if (actual == null)
			{
				failures.add("unknown assert key: " + key);
				continue;
			}
			for (JsonElement e : asserts.getAsJsonArray(key))
			{
				String expected = e.getAsString();
				boolean present = actual.stream().anyMatch(a -> a.equalsIgnoreCase(expected));
				if (present == negated)
				{
					failures.add(listName + (negated ? " must not contain '" : " must contain '")
						+ expected + "' (actual: " + actual + ")");
				}
			}
		}
		return failures;
	}

	private static class BatteryItem
	{
		final String question;
		final boolean followUp;
		final List<Map<String, Object>> events;
		JsonObject asserts;
		String answer;

		BatteryItem(String question, boolean followUp, List<Map<String, Object>> events)
		{
			this.question = question;
			this.followUp = followUp;
			this.events = events;
		}
	}

	private static List<BatteryItem> parseBattery(File file, Gson gson) throws Exception
	{
		List<BatteryItem> items = new java.util.ArrayList<>();
		List<Map<String, Object>> pendingEvents = null;
		for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
		{
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			if (line.startsWith("EVENT "))
			{
				Map<String, Object> event = gson.fromJson(line.substring(6),
					new TypeToken<Map<String, Object>>() { }.getType());
				pendingEvents = List.of(event);
				continue;
			}
			if (line.startsWith("ASSERT "))
			{
				if (items.isEmpty())
				{
					throw new IllegalArgumentException("ASSERT before any question");
				}
				items.get(items.size() - 1).asserts = gson.fromJson(line.substring(7), JsonObject.class);
				continue;
			}
			if (line.startsWith("ANSWER "))
			{
				if (items.isEmpty())
				{
					throw new IllegalArgumentException("ANSWER before any question");
				}
				items.get(items.size() - 1).answer = line.substring(7);
				continue;
			}
			boolean followUp = line.startsWith("> ");
			items.add(new BatteryItem(followUp ? line.substring(2) : line, followUp, pendingEvents));
			pendingEvents = null;
		}
		return items;
	}

	private static String trim(String s, int max)
	{
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}

	private static Map<String, String> parseArgs(String[] args)
	{
		Map<String, String> opts = new HashMap<>();
		for (int i = 0; i + 1 < args.length; i += 2)
		{
			opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
		}
		if (!opts.containsKey("snapshot") || (!opts.containsKey("question") && !opts.containsKey("battery")))
		{
			throw new IllegalArgumentException("--snapshot and (--question or --battery) are required");
		}
		return opts;
	}

	/** Loads a GameCapture dump as written by ::probe. */
	static GameCapture loadSnapshot(File file, Gson gson) throws Exception
	{
		String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		return gson.fromJson(json, GameCapture.class);
	}
}
