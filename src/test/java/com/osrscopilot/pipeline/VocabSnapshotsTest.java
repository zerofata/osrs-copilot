package com.osrscopilot.pipeline;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VocabSnapshotsTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** The client reads back exactly what the snapshot job serializes; a
	 * fresh disk copy means no network is touched. */
	@Test
	public void itemCatalogRoundTripsThroughTheSnapshotFormat() throws Exception
	{
		Gson gson = new Gson();
		File dir = tmp.newFolder();
		String json = gson.toJson(List.of(
			new ItemDescriptor("Shark", "Shark", 385, true, 20000, 102),
			new ItemDescriptor("Fire cape (l)", "Fire cape", null, false, null, null)));
		Files.write(new File(dir, "items_v2.json").toPath(),
			json.getBytes(StandardCharsets.UTF_8));

		VocabSnapshots vocab = new VocabSnapshots(
			new Http(new OkHttpClient(), gson), gson, dir);
		List<ItemDescriptor> items = vocab.itemCatalog();
		assertEquals(2, items.size());
		assertEquals("Shark", items.get(0).name);
		assertEquals(Integer.valueOf(385), items.get(0).id);
		assertEquals(Integer.valueOf(20000), items.get(0).limit);
		assertEquals(Integer.valueOf(102), items.get(0).highAlch);
		assertEquals("Fire cape", items.get(1).page);
		assertNull(items.get(1).id);
		assertNull(items.get(1).limit);
	}
}
