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
}
