package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;

/**
 * The player's last-seen bank, kept per account: the bank container is not
 * readable after it closes, so the last open is cached in memory and
 * persisted to disk. The account hash keys everything: a second account on
 * the same machine must never inherit the first one's bank.
 *
 * Mutators run on the client thread; disk writes go to the executor. The
 * synchronized blocks make the writer observe contents and accountHash as
 * a consistent pair.
 */
@Slf4j
class BankStore
{
	private final File dataDir;
	private final Gson gson;
	private final Executor ioExecutor;

	private List<Map<String, Object>> contents;
	/** Which account the in-memory/persisted bank belongs to.
	 * -1 = no account seen yet this session. */
	private long accountHash = -1;

	BankStore(File dataDir, Gson gson, Executor ioExecutor)
	{
		this.dataDir = dataDir;
		this.gson = gson;
		this.ioExecutor = ioExecutor;
	}

	/** Runs on the client thread. On the first tick of a session and on
	 * any account switch, drop the previous account's bank and load this
	 * one's persisted copy. */
	void sync(long hash)
	{
		if (hash == -1 || hash == accountHash)
		{
			return;
		}
		synchronized (this)
		{
			accountHash = hash;
			contents = null;
		}
		load(hash);
	}

	/** A fresh bank capture from an open bank container: cache and persist. */
	void update(List<Map<String, Object>> items)
	{
		synchronized (this)
		{
			contents = items;
		}
		persist();
	}

	/** Applies an out-of-band deposit (deposit box, GE collect-to-bank).
	 * Copy-on-write: the pipeline reads the contents list off-thread, so
	 * the reference is swapped, never mutated. No-op without a snapshot. */
	void credit(int itemId, String name, int quantity)
	{
		if (contents == null || accountHash == -1 || quantity <= 0)
		{
			return;
		}
		List<Map<String, Object>> next = new ArrayList<>(contents.size() + 1);
		boolean merged = false;
		for (Map<String, Object> entry : contents)
		{
			Object id = entry.get("id");
			if (!merged && id instanceof Number && ((Number) id).intValue() == itemId)
			{
				Object have = entry.get("quantity");
				Map<String, Object> updated = new LinkedHashMap<>(entry);
				updated.put("quantity",
					(have instanceof Number ? ((Number) have).longValue() : 0L) + quantity);
				next.add(updated);
				merged = true;
			}
			else
			{
				next.add(entry);
			}
		}
		if (!merged)
		{
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", itemId);
			entry.put("name", name);
			entry.put("quantity", quantity);
			next.add(entry);
		}
		synchronized (this)
		{
			contents = next;
		}
		persist();
	}

	List<Map<String, Object>> contents()
	{
		return contents;
	}

	private File bankFile(long accountHash)
	{
		return new File(dataDir, "bank-" + Long.toUnsignedString(accountHash) + ".json");
	}

	private void persist()
	{
		if (contents == null || accountHash == -1)
		{
			return;
		}
		ioExecutor.execute(() ->
		{
			List<Map<String, Object>> snapshot;
			long hash;
			synchronized (this)
			{
				snapshot = contents;
				hash = accountHash;
			}
			if (snapshot == null || hash == -1)
			{
				return;
			}
			try
			{
				Files.write(bankFile(hash).toPath(),
					gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("Bank persist failed", e);
			}
		});
	}

	private void load(long accountHash)
	{
		File f = bankFile(accountHash);
		if (!f.exists())
		{
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
		{
			List<Map<String, Object>> loaded = gson.fromJson(r,
				new TypeToken<List<Map<String, Object>>>() { }.getType());
			synchronized (this)
			{
				contents = loaded;
			}
			log.info("Loaded persisted bank ({} items)",
				loaded != null ? loaded.size() : 0);
		}
		catch (Exception e)
		{
			log.warn("Bank load failed", e);
		}
	}
}
