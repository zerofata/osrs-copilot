package com.osrscopilot;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BankStoreTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();

	private static final List<Map<String, Object>> ITEMS = List.of(
		Map.of("id", 11212.0, "name", "Dragon arrow", "quantity", 4663.0));

	@Test
	public void persistedBankSurvivesRestart()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);

		BankStore reopened = new BankStore(tmp.getRoot(), gson);
		reopened.sync(42L);
		assertEquals(1, reopened.contents().size());
		assertEquals("Dragon arrow", reopened.contents().get(0).get("name"));
	}

	@Test
	public void accountSwitchNeverInheritsAnotherAccountsBank()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);

		store.sync(99L);
		assertNull("fresh account must start with no bank", store.contents());

		// Switching back restores the first account's persisted bank.
		store.sync(42L);
		assertEquals(1, store.contents().size());
	}

	@Test
	public void noAccountSeenMeansNothingPersisted()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.update(ITEMS);
		// No account hash yet: nothing may be written to disk.
		assertEquals(0, tmp.getRoot().listFiles((d, n) -> n.startsWith("bank-")).length);
	}

	@Test
	public void creditMergesIntoTheExistingStack()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);

		store.credit(11212, "Dragon arrow", 150);

		assertEquals(1, store.contents().size());
		assertEquals(4813L,
			((Number) store.contents().get(0).get("quantity")).longValue());
	}

	@Test
	public void creditAppendsAnItemNotYetBanked()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);

		store.credit(560, "Death rune", 200);

		assertEquals(2, store.contents().size());
		Map<String, Object> added = store.contents().get(1);
		assertEquals("Death rune", added.get("name"));
		assertEquals(200L, ((Number) added.get("quantity")).longValue());
	}

	@Test
	public void creditPersistsAcrossRestart()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);
		store.credit(11212, "Dragon arrow", 37);

		BankStore reopened = new BankStore(tmp.getRoot(), gson);
		reopened.sync(42L);
		assertEquals(4700L,
			((Number) reopened.contents().get(0).get("quantity")).longValue());
	}

	@Test
	public void creditWithoutASnapshotIsRefused()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		// No capture ever seen: there is nothing sound to patch.
		store.credit(11212, "Dragon arrow", 5);
		assertNull(store.contents());
		assertEquals(0, tmp.getRoot().listFiles((d, n) -> n.startsWith("bank-")).length);
	}

	@Test
	public void creditDoesNotMutateTheCapturedList()
	{
		BankStore store = new BankStore(tmp.getRoot(), gson);
		store.sync(42L);
		store.update(ITEMS);
		List<Map<String, Object>> captured = store.contents();

		// A pipeline run may be reading the captured list off-thread;
		// credits must swap the reference, never mutate in place.
		store.credit(560, "Death rune", 200);

		assertEquals(1, captured.size());
		assertEquals(4663L,
			((Number) captured.get(0).get("quantity")).longValue());
	}
}
