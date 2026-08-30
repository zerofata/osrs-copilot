package com.osrscopilot;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;

/**
 * The player's POH facilities, kept per account: the house only exists as
 * scene objects while the player stands inside it, so each visit's scan is
 * persisted for use everywhere else. Same threading contract as BankStore:
 * mutators run on the client thread, disk writes go to the executor.
 */
@Slf4j
class HouseStore
{
	/** Wall-mounted teleport items; everything else mounted is decor. */
	private static final Set<String> MOUNTED_TELEPORTS = Set.of(
		"amulet of glory", "digsite pendant", "xeric's talisman", "mythical cape");

	private final File dataDir;
	private final Gson gson;
	private final Executor ioExecutor;

	private Data data;
	private long accountHash = -1;

	private static final class Data
	{
		List<String> facilities;
		List<String> nexus;
		long capturedAt;
	}

	HouseStore(File dataDir, Gson gson, Executor ioExecutor)
	{
		this.dataDir = dataDir;
		this.gson = gson;
		this.ioExecutor = ioExecutor;
	}

	/** Runs on the client thread. On the first tick of a session and on
	 * any account switch, drop the previous account's house and load this
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
			data = null;
		}
		load(hash);
	}

	/** A fresh facility scan from inside the house: replace and persist.
	 * The nexus destination list survives -- it comes from its own menu. */
	void updateFacilities(List<String> facilities)
	{
		synchronized (this)
		{
			Data next = new Data();
			next.facilities = facilities;
			next.nexus = data != null ? data.nexus : null;
			next.capturedAt = System.currentTimeMillis();
			data = next;
		}
		persist();
	}

	/** Destinations parsed from the nexus Teleport Menu. */
	void updateNexus(List<String> destinations)
	{
		synchronized (this)
		{
			Data next = new Data();
			next.facilities = data != null ? data.facilities : null;
			next.nexus = destinations;
			next.capturedAt = data != null ? data.capturedAt : System.currentTimeMillis();
			data = next;
		}
		persist();
	}

	/** The facility list for a capture, nexus destinations folded into its
	 * entry; null when the player's house has never been scanned. */
	List<String> forCapture()
	{
		Data d = data;
		if (d == null || d.facilities == null || d.facilities.isEmpty())
		{
			return null;
		}
		List<String> out = new ArrayList<>(d.facilities.size());
		for (String facility : d.facilities)
		{
			out.add("Portal Nexus".equals(facility) && d.nexus != null && !d.nexus.isEmpty()
				? "Portal Nexus: " + String.join(", ", d.nexus)
				: facility);
		}
		return out;
	}

	/**
	 * The facility a scene object represents, or null for furniture. Names
	 * and ops are impostor-resolved, so they reflect what is actually
	 * built. Calibrated against a real house dump 2026-08-30.
	 */
	static String describe(String name, String[] ops)
	{
		List<String> opList = new ArrayList<>();
		if (ops != null)
		{
			for (String op : ops)
			{
				if (op != null)
				{
					opList.add(op);
				}
			}
		}
		if ("Portal Nexus".equals(name))
		{
			return name;
		}
		// Portal Chamber portals are named by destination ("Kourend
		// Portal"); the bare house exit is just "Portal". Diary-upgraded
		// duals list both destinations as ops before a Toggle.
		if (name.endsWith(" Portal"))
		{
			int toggle = opList.indexOf("Toggle");
			return toggle >= 2
				? name + " (" + opList.get(0) + " or " + opList.get(1) + ")"
				: name;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (opList.contains("Drink") && lower.contains("pool"))
		{
			return name;
		}
		if (opList.contains("Pray") && lower.contains("altar"))
		{
			return name;
		}
		// Spellbook altars (Occult, Ancient, Lunar, Dark).
		if (opList.contains("Venerate"))
		{
			return name;
		}
		if (lower.contains("jewellery box") || lower.contains("fairy ring")
			|| lower.contains("spirit tree") || "obelisk".equals(lower)
			|| MOUNTED_TELEPORTS.contains(lower))
		{
			return name;
		}
		return null;
	}

	/**
	 * Destinations from the nexus Teleport Menu's texts, or null when the
	 * texts are some other interface. Entries render as
	 * "&lt;col=ffffff&gt;1&lt;/col&gt; :  Arceuus Library"; the
	 * Configuration screen shares the title but lists its catalogue
	 * without the " : " separator, so it parses to nothing.
	 */
	static List<String> parseNexusMenu(List<String> texts)
	{
		if (texts.isEmpty() || !"Portal Nexus".equals(texts.get(0)))
		{
			return null;
		}
		List<String> out = new ArrayList<>();
		for (String text : texts)
		{
			String plain = text.replaceAll("<[^>]*>", "");
			int sep = plain.indexOf(" : ");
			if (sep > 0)
			{
				out.add(plain.substring(sep + 3).trim());
			}
		}
		return out;
	}

	private File houseFile(long accountHash)
	{
		return new File(dataDir, "house-" + Long.toUnsignedString(accountHash) + ".json");
	}

	private void persist()
	{
		ioExecutor.execute(() ->
		{
			Data snapshot;
			long hash;
			synchronized (this)
			{
				snapshot = data;
				hash = accountHash;
			}
			if (snapshot == null || hash == -1)
			{
				return;
			}
			try
			{
				Files.write(houseFile(hash).toPath(),
					gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				log.warn("House persist failed", e);
			}
		});
	}

	private void load(long accountHash)
	{
		File f = houseFile(accountHash);
		if (!f.exists())
		{
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
		{
			Data loaded = gson.fromJson(r, Data.class);
			synchronized (this)
			{
				data = loaded;
			}
		}
		catch (Exception e)
		{
			log.warn("House load failed", e);
		}
	}
}
