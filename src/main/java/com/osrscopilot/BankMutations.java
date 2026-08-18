package com.osrscopilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;

/**
 * Applies the bank changes that happen while the bank is closed, so the
 * stored snapshot stays accurate between authoritative captures. Two paths
 * are tracked: deposit boxes (inventory/equipment decreases while the
 * deposit box interface is open are deposits) and the Grand Exchange's
 * collect-to-bank (uncollected buy fills and cancelled-sell remainders are
 * credited when banked). Client thread only.
 *
 * Deltas are credit-only and deliberately conservative: an ambiguous
 * collect clears the tracking instead of guessing, because a missed credit
 * self-heals at the next bank open while an invented one is a wrong
 * ownership answer until then. Untracked paths (POH butlers,
 * direct-to-bank rewards, play on other devices) self-heal the same way.
 */
@Slf4j
class BankMutations
{
	private static final String COLLECT_TO_BANK = "Collect to bank";

	private final BankStore bankStore;
	private final ItemManager itemManager;
	private final GeSlots geSlots = new GeSlots();

	/** Inventory/equipment as last seen while the deposit box is open;
	 * null while it is closed. */
	private Map<Integer, Integer> inventorySeen;
	private Map<Integer, Integer> equipmentSeen;

	BankMutations(BankStore bankStore, ItemManager itemManager)
	{
		this.bankStore = bankStore;
		this.itemManager = itemManager;
	}

	// --- deposit box ---

	void depositBoxOpened(ItemContainer inventory, ItemContainer equipment)
	{
		inventorySeen = multiset(inventory);
		equipmentSeen = multiset(equipment);
	}

	void depositBoxClosed()
	{
		inventorySeen = null;
		equipmentSeen = null;
	}

	/** Inventory or equipment changed. While the deposit box is open, any
	 * decrease against the last-seen state is a deposit. */
	void containerChanged(int containerId, ItemContainer container)
	{
		if (inventorySeen == null)
		{
			return;
		}
		Map<Integer, Integer> now = multiset(container);
		Map<Integer, Integer> before;
		if (containerId == InventoryID.INV)
		{
			before = inventorySeen;
			inventorySeen = now;
		}
		else if (containerId == InventoryID.WORN)
		{
			before = equipmentSeen;
			equipmentSeen = now;
		}
		else
		{
			return;
		}
		for (Map.Entry<Integer, Integer> gone : decreases(before, now).entrySet())
		{
			credit(gone.getKey(), gone.getValue());
		}
	}

	// --- grand exchange ---

	void offerChanged(int slot, GrandExchangeOfferState state, int itemId,
		int quantitySold, int totalQuantity)
	{
		geSlots.offerChanged(slot, state, itemId, quantitySold, totalQuantity);
	}

	/** Every menu click passes through here. "Collect to bank" credits the
	 * tracked collectables; any other collect variant (to inventory,
	 * per-slot, collection box) or a bank visit clears them instead of
	 * guessing where the items landed. */
	void menuClicked(String option)
	{
		if (option == null)
		{
			return;
		}
		if (COLLECT_TO_BANK.equalsIgnoreCase(option))
		{
			for (int[] pair : geSlots.drain())
			{
				credit(pair[0], pair[1]);
			}
		}
		else if (option.regionMatches(true, 0, "Collect", 0, 7) || "Bank".equals(option))
		{
			geSlots.clear();
		}
	}

	// --- shared ---

	/** Noted deposits must land on the unnoted bank stack, hence the
	 * canonicalize. Runs on the client thread (item composition lookup). */
	private void credit(int itemId, int quantity)
	{
		try
		{
			int canonical = itemManager.canonicalize(itemId);
			bankStore.credit(canonical,
				itemManager.getItemComposition(canonical).getName(), quantity);
		}
		catch (Exception e)
		{
			log.debug("bank credit failed for item {}", itemId, e);
		}
	}

	private static Map<Integer, Integer> multiset(ItemContainer container)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		if (container == null)
		{
			return counts;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() != -1)
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return counts;
	}

	/** Per item id, how much less of it `after` holds than `before`. */
	static Map<Integer, Integer> decreases(Map<Integer, Integer> before,
		Map<Integer, Integer> after)
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> was : before.entrySet())
		{
			int drop = was.getValue() - after.getOrDefault(was.getKey(), 0);
			if (drop > 0)
			{
				out.put(was.getKey(), drop);
			}
		}
		return out;
	}

	/**
	 * Prediction-vs-actual summary for the drift audit at an authoritative
	 * bank capture, or null when the prediction matched (or there was
	 * nothing predicted).
	 */
	static String drift(List<Map<String, Object>> predicted, List<Map<String, Object>> actual)
	{
		if (predicted == null || actual == null)
		{
			return null;
		}
		Map<Integer, Long> want = quantitiesById(predicted);
		Map<Integer, Long> got = quantitiesById(actual);
		List<String> lines = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : got.entrySet())
		{
			long off = e.getValue() - want.getOrDefault(e.getKey(), 0L);
			if (off != 0)
			{
				lines.add("item " + e.getKey() + " " + (off > 0 ? "+" : "") + off);
			}
		}
		for (Map.Entry<Integer, Long> e : want.entrySet())
		{
			if (!got.containsKey(e.getKey()))
			{
				lines.add("item " + e.getKey() + " -" + e.getValue());
			}
		}
		if (lines.isEmpty())
		{
			return null;
		}
		String head = String.join(", ", lines.subList(0, Math.min(10, lines.size())));
		return lines.size() + " stacks off prediction: " + head
			+ (lines.size() > 10 ? ", ..." : "");
	}

	private static Map<Integer, Long> quantitiesById(List<Map<String, Object>> items)
	{
		Map<Integer, Long> out = new HashMap<>();
		for (Map<String, Object> item : items)
		{
			Object id = item.get("id");
			Object qty = item.get("quantity");
			if (id instanceof Number && qty instanceof Number)
			{
				out.merge(((Number) id).intValue(), ((Number) qty).longValue(), Long::sum);
			}
		}
		return out;
	}

	/**
	 * The GE bookkeeping: per slot, the offer's item and how many of it are
	 * sitting uncollected. Buy fills accrue from quantitySold deltas; a
	 * cancelled sell's remainder becomes collectable as items. Sold-portion
	 * proceeds and refunds are coins, which are not tracked. Pure state
	 * machine, no client types.
	 */
	static class GeSlots
	{
		private static final int SLOTS = 8;

		private final int[] item = new int[SLOTS];
		private final int[] uncollected = new int[SLOTS];
		private final int[] sold = new int[SLOTS];

		void offerChanged(int slot, GrandExchangeOfferState state, int itemId,
			int quantitySold, int totalQuantity)
		{
			if (slot < 0 || slot >= SLOTS)
			{
				return;
			}
			switch (state)
			{
				case EMPTY:
					item[slot] = 0;
					uncollected[slot] = 0;
					sold[slot] = 0;
					return;
				case BUYING:
				case BOUGHT:
				case CANCELLED_BUY:
					if (itemId != item[slot] || quantitySold < sold[slot])
					{
						// a new offer reusing the slot
						item[slot] = itemId;
						uncollected[slot] = 0;
						sold[slot] = 0;
					}
					uncollected[slot] += quantitySold - sold[slot];
					sold[slot] = quantitySold;
					return;
				case SELLING:
				case SOLD:
					// the sold portion collects as coins, not items
					item[slot] = itemId;
					sold[slot] = quantitySold;
					uncollected[slot] = 0;
					return;
				case CANCELLED_SELL:
					item[slot] = itemId;
					sold[slot] = quantitySold;
					uncollected[slot] = Math.max(0, totalQuantity - quantitySold);
					return;
				default:
			}
		}

		/** The uncollected {itemId, quantity} pairs, cleared by this call. */
		List<int[]> drain()
		{
			List<int[]> out = new ArrayList<>();
			for (int slot = 0; slot < SLOTS; slot++)
			{
				if (item[slot] > 0 && uncollected[slot] > 0)
				{
					out.add(new int[]{item[slot], uncollected[slot]});
				}
				uncollected[slot] = 0;
			}
			return out;
		}

		void clear()
		{
			for (int slot = 0; slot < SLOTS; slot++)
			{
				uncollected[slot] = 0;
			}
		}
	}
}
