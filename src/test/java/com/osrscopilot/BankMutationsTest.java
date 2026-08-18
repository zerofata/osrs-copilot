package com.osrscopilot;

import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The out-of-band bank trackers must be conservative: only credit what the
 * arithmetic proves was deposited or collected, and clear rather than guess
 * on any ambiguous path. A missed credit self-heals at the next bank open;
 * an invented one is a wrong ownership answer until then.
 */
public class BankMutationsTest
{
	// --- deposit box container diffs ---

	@Test
	public void decreasesDetectDepositsOnly()
	{
		Map<Integer, Integer> before = Map.of(385, 5, 560, 200, 4151, 1);
		Map<Integer, Integer> after = Map.of(385, 2, 560, 250, 4151, 1);
		// Sharks went down (deposit), death runes went UP (withdrawal has no
		// deposit-box path, but an increase must never become a credit),
		// the whip is unchanged.
		assertEquals(Map.of(385, 3), BankMutations.decreases(before, after));
	}

	@Test
	public void itemFullyDepositedIsItsWholeStack()
	{
		Map<Integer, Integer> before = Map.of(11212, 4663);
		Map<Integer, Integer> after = Map.of();
		assertEquals(Map.of(11212, 4663), BankMutations.decreases(before, after));
	}

	@Test
	public void itemAppearingFromNowhereIsNotADeposit()
	{
		Map<Integer, Integer> before = Map.of();
		Map<Integer, Integer> after = Map.of(385, 5);
		assertTrue(BankMutations.decreases(before, after).isEmpty());
	}

	// --- GE slot bookkeeping ---

	@Test
	public void buyFillsAccrueAsUncollected()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(0, GrandExchangeOfferState.BUYING, 560, 100, 1000);
		slots.offerChanged(0, GrandExchangeOfferState.BUYING, 560, 350, 1000);
		slots.offerChanged(0, GrandExchangeOfferState.BOUGHT, 560, 1000, 1000);

		List<int[]> credits = slots.drain();
		assertEquals(1, credits.size());
		assertEquals(560, credits.get(0)[0]);
		assertEquals(1000, credits.get(0)[1]);
	}

	@Test
	public void drainClearsWhatItReturned()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(2, GrandExchangeOfferState.BUYING, 560, 100, 1000);
		slots.drain();
		assertTrue("second drain must not double-credit", slots.drain().isEmpty());
	}

	@Test
	public void newOfferReusingASlotStartsFresh()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(0, GrandExchangeOfferState.BOUGHT, 560, 500, 500);
		slots.offerChanged(0, GrandExchangeOfferState.EMPTY, 0, 0, 0);
		slots.offerChanged(0, GrandExchangeOfferState.BUYING, 385, 10, 100);

		List<int[]> credits = slots.drain();
		assertEquals(1, credits.size());
		assertEquals(385, credits.get(0)[0]);
		assertEquals(10, credits.get(0)[1]);
	}

	@Test
	public void sameItemNewOfferDetectedByQuantityResetDropsStaleAccrual()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(0, GrandExchangeOfferState.BOUGHT, 560, 500, 500);
		// No EMPTY seen between offers: the first offer's items were
		// collected while we weren't watching, so its accrual is stale.
		// The new offer's lower quantitySold is the tell; only its own
		// fills may credit.
		slots.offerChanged(0, GrandExchangeOfferState.BUYING, 560, 20, 300);

		List<int[]> credits = slots.drain();
		assertEquals(1, credits.size());
		assertEquals(20, credits.get(0)[1]);
	}

	@Test
	public void cancelledSellRemainderCollectsAsItems()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(1, GrandExchangeOfferState.SELLING, 4151, 3, 10);
		slots.offerChanged(1, GrandExchangeOfferState.CANCELLED_SELL, 4151, 3, 10);

		List<int[]> credits = slots.drain();
		assertEquals(1, credits.size());
		assertEquals(4151, credits.get(0)[0]);
		assertEquals(7, credits.get(0)[1]);
	}

	@Test
	public void sellProceedsAreCoinsNotItems()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(1, GrandExchangeOfferState.SELLING, 4151, 3, 10);
		slots.offerChanged(1, GrandExchangeOfferState.SOLD, 4151, 10, 10);
		assertTrue(slots.drain().isEmpty());
	}

	@Test
	public void clearDropsUncollectedWithoutCrediting()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(0, GrandExchangeOfferState.BOUGHT, 560, 500, 500);
		slots.clear();
		assertTrue(slots.drain().isEmpty());
	}

	@Test
	public void outOfRangeSlotIsIgnored()
	{
		BankMutations.GeSlots slots = new BankMutations.GeSlots();
		slots.offerChanged(-1, GrandExchangeOfferState.BOUGHT, 560, 5, 5);
		slots.offerChanged(8, GrandExchangeOfferState.BOUGHT, 560, 5, 5);
		assertTrue(slots.drain().isEmpty());
	}

	// --- drift audit ---

	@Test
	public void matchingPredictionReportsNoDrift()
	{
		List<Map<String, Object>> bank = List.of(
			Map.of("id", 11212.0, "name", "Dragon arrow", "quantity", 4663.0));
		assertNull(BankMutations.drift(bank, bank));
	}

	@Test
	public void nothingPredictedReportsNoDrift()
	{
		assertNull(BankMutations.drift(null,
			List.of(Map.of("id", 385, "name", "Shark", "quantity", 5))));
	}

	@Test
	public void driftNamesTheStacksThatAreOff()
	{
		List<Map<String, Object>> predicted = List.of(
			Map.of("id", 11212, "name", "Dragon arrow", "quantity", 4663),
			Map.of("id", 560, "name", "Death rune", "quantity", 200));
		List<Map<String, Object>> actual = List.of(
			Map.of("id", 11212, "name", "Dragon arrow", "quantity", 4700),
			Map.of("id", 385, "name", "Shark", "quantity", 5));

		String drift = BankMutations.drift(predicted, actual);
		assertTrue(drift.contains("item 11212 +37"));
		assertTrue("unpredicted stack must show", drift.contains("item 385 +5"));
		assertTrue("vanished stack must show", drift.contains("item 560 -200"));
		assertTrue(drift.startsWith("3 stacks off prediction"));
	}
}
