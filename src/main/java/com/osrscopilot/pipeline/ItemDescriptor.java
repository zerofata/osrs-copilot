package com.osrscopilot.pipeline;

/**
 * One catalogued item: display name (per version), canonical wiki page,
 * and the game item ID when the wiki knows one. The weekly snapshot job
 * writes these as items_v2.json; clients parse the same type back.
 */
public final class ItemDescriptor
{
	public final String name;
	public final String page;
	/** Inventory-sprite item ID; null when neither the wiki nor the GE
	 * catalogue lists one. */
	public final Integer id;
	public final boolean tradeable;
	/** GE buy limit and high-alch value; null when untradeable or the GE
	 * catalogue omits them. */
	public final Integer limit;
	public final Integer highAlch;

	public ItemDescriptor(String name, String page, Integer id, boolean tradeable,
		Integer limit, Integer highAlch)
	{
		this.name = name;
		this.page = page;
		this.id = id;
		this.tradeable = tradeable;
		this.limit = limit;
		this.highAlch = highAlch;
	}
}
