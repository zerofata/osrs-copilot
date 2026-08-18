package com.osrscopilot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The player's last-seen bank, kept per account. The bank container is not
 * readable on demand after the bank closes, so the last open is cached in
 * memory and persisted to disk (bank-&lt;accountHash&gt;.json) so ownership
 * answers survive client restarts. The account hash keys everything: a
 * second account on the same machine must never inherit the first one's
 * bank, or every ownership answer is confidently wrong.
 */
@Slf4j
class BankStore
{
	private final File dataDir;
	private final Gson gson;

	private List<Map<String, Object>> contents;
	/** Which account the in-memory/persisted bank belongs to.
	 * -1 = no account seen yet this session. */
	private long accountHash = -1;

	BankStore(File dataDir, Gson gson)
	{
		this.dataDir = dataDir;
		this.gson = gson;
	}

	/** Runs on the client thread (game tick). Keeps the in-memory bank bound
	 * to the logged-in account: on the first tick of a session, and on any
	 * account switch, drop the previous account's bank and load this one's
	 * persisted copy. GameTick only fires logged in, so the hash is valid. */
	void sync(long hash)
	{
		if (hash == -1 || hash == accountHash)
		{
			return;
		}
		accountHash = hash;
		contents = null;
		load(hash);
	}

	/** A fresh bank capture from an open bank container: cache and persist. */
	void update(List<Map<String, Object>> items)
	{
		contents = items;
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
		try (BufferedWriter w = new BufferedWriter(new FileWriter(bankFile(accountHash))))
		{
			w.write(gson.toJson(contents));
		}
		catch (IOException e)
		{
			log.warn("Bank persist failed", e);
		}
	}

	private void load(long accountHash)
	{
		File f = bankFile(accountHash);
		if (!f.exists())
		{
			return;
		}
		try (FileReader r = new FileReader(f))
		{
			contents = gson.fromJson(r,
				new TypeToken<List<Map<String, Object>>>() { }.getType());
			log.info("Loaded persisted bank ({} items)",
				contents != null ? contents.size() : 0);
		}
		catch (Exception e)
		{
			log.warn("Bank load failed", e);
		}
	}
}
