package com.osrscopilot;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * Recent gameplay events, in two forms with two audiences: a bounded
 * in-memory buffer that accompanies questions as context ("what's this
 * drop?"), and an opt-in JSONL disk log for development diagnostics.
 * Both are filtered at the call sites to event types that are the player's
 * own data -- never other players' chat.
 */
@Slf4j
class EventRecorder
{
	private static final int EVENT_BUFFER_SIZE = 100;
	private static final int EVENTS_SENT_WITH_QUESTION = 30;
	/** How long dev event logs are kept before pruning. */
	private static final long EVENT_LOG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000;

	private final Client client;
	private final Gson gson;
	private final CopilotConfig config;
	private final File dataDir;

	// Client thread only.
	private final Deque<Map<String, Object>> recentEvents = new ArrayDeque<>();
	private BufferedWriter eventLog;

	EventRecorder(Client client, Gson gson, CopilotConfig config, File dataDir)
	{
		this.client = client;
		this.gson = gson;
		this.config = config;
		this.dataDir = dataDir;
	}

	/** Runs on the client thread. Only event types useful as question context. */
	void buffer(String type, Map<String, Object> data)
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

	/** Runs on the client thread: the newest buffered events, timestamps
	 * rewritten as minutes-ago for the model. */
	List<Map<String, Object>> recent()
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

	void openLog() throws IOException
	{
		// One file per session, never read back: prune old ones or they
		// accumulate forever.
		File[] old = dataDir.listFiles((d, name) ->
			name.startsWith("events-") && name.endsWith(".jsonl"));
		if (old != null)
		{
			long cutoff = System.currentTimeMillis() - EVENT_LOG_RETENTION_MS;
			for (File f : old)
			{
				if (f.lastModified() < cutoff)
				{
					f.delete();
				}
			}
		}
		File logFile = new File(dataDir, "events-" + System.currentTimeMillis() + ".jsonl");
		eventLog = Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	void closeLog() throws IOException
	{
		if (eventLog != null)
		{
			eventLog.close();
			eventLog = null;
		}
	}

	void log(String type, Map<String, Object> data)
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

	boolean logOpen()
	{
		return eventLog != null;
	}
}
