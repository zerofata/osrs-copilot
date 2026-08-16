package com.osrscopilot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Real game icons for decorated answers, rendered from the client's own
 * caches -- item sprites via {@link ItemManager}, skill icons via
 * {@link SkillIconManager}, the quest point icon via {@link SpriteManager}.
 * Nothing is fetched over the network: the game client already holds every
 * sprite, keyed by item ID rather than guessed filename, so an icon can
 * neither 404 nor mismatch its item.
 *
 * Icons persist as PNGs because the Swing HTML renderer references images
 * by URL; each renders once and is a file URL thereafter, including offline.
 *
 * Fail-soft by contract: any miss returns null and the caller renders
 * without an icon. A decoration layer must never break an answer.
 */
@Slf4j
final class IconStore
{
	/** How long a decorate-time item render may wait for the client to
	 * draw the sprite. Local cache work, normally single-digit millis;
	 * the bound exists so a wedged client thread cannot stall an answer. */
	private static final long ITEM_RENDER_TIMEOUT_MS = 2000;

	private final File dir;
	private final ItemManager itemManager;
	/** Resolved file URLs (and misses, as null-markers via absence) for
	 * this session, so repeat mentions cost a map hit. */
	private final Map<String, String> resolved = new ConcurrentHashMap<>();

	/**
	 * Managers may be null outside the client (UI previews, tests): the
	 * store then serves only icons already on disk.
	 */
	IconStore(File dir, ItemManager itemManager, SpriteManager spriteManager,
		SkillIconManager skillIconManager)
	{
		this.dir = dir;
		this.itemManager = itemManager;
		dir.mkdirs();
		// The wiki-era cache guessed filenames and remembered its 404s;
		// neither concept survives the move to ID-keyed client sprites.
		new File(dir, "misses.txt").delete();

		if (skillIconManager != null)
		{
			for (Skill skill : Skill.values())
			{
				if (skill == Skill.OVERALL)
				{
					continue;
				}
				writeOnce(skillFile(skill), () -> skillIconManager.getSkillImage(skill, true));
			}
		}
		if (spriteManager != null)
		{
			// Sprite readiness depends on client startup; async with a
			// write-once callback instead of assuming the cache is loaded.
			spriteManager.getSpriteAsync(SpriteID.QUESTS_PAGE_ICON_BLUE_QUESTS, 0,
				img -> writeOnce(questFile(), () -> img));
		}
	}

	/** File URL for an item's inventory sprite, rendering it from the game
	 * cache on first use. Null if unavailable; never throws. */
	String itemIconUrl(int itemId)
	{
		File f = new File(dir, "item-" + itemId + ".png");
		String key = f.getName();
		String cached = resolved.get(key);
		if (cached != null)
		{
			return cached;
		}
		if (!f.exists() && !renderItem(itemId, f))
		{
			return null;
		}
		String url = f.toURI().toString();
		resolved.put(key, url);
		return url;
	}

	/** File URL for a skill's icon; written at construction. */
	String skillIconUrl(String skillName)
	{
		return existingUrl(skillFile(skillName));
	}

	/** File URL for the quest point icon; written at construction. */
	String questIconUrl()
	{
		return existingUrl(questFile());
	}

	private String existingUrl(File f)
	{
		String cached = resolved.get(f.getName());
		if (cached != null)
		{
			return cached;
		}
		if (!f.exists())
		{
			return null;
		}
		String url = f.toURI().toString();
		resolved.put(f.getName(), url);
		return url;
	}

	/**
	 * Render one item sprite to disk. AsyncBufferedImage paints on the
	 * client thread; decoration runs on the pipeline worker, so a bounded
	 * wait here is safe and keeps the one-shot decoration pass complete
	 * (an icon missing at decorate time would be missing from that answer
	 * forever -- the decorated HTML is cached verbatim).
	 */
	private boolean renderItem(int itemId, File f)
	{
		if (itemManager == null)
		{
			return false;
		}
		try
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			CountDownLatch drawn = new CountDownLatch(1);
			img.onLoaded(drawn::countDown);
			if (!drawn.await(ITEM_RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
			{
				log.debug("item sprite {} not drawn within {}ms", itemId, ITEM_RENDER_TIMEOUT_MS);
				return false;
			}
			// Copy: AsyncBufferedImage repaints itself on its own schedule.
			BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
			copy.getGraphics().drawImage(img, 0, 0, null);
			return writePng(copy, f);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception e)
		{
			log.debug("item sprite {} render failed", itemId, e);
			return false;
		}
	}

	private interface ImageSource
	{
		BufferedImage get();
	}

	private void writeOnce(File f, ImageSource source)
	{
		if (f.exists())
		{
			return;
		}
		try
		{
			BufferedImage img = source.get();
			if (img != null)
			{
				writePng(img, f);
			}
		}
		catch (Exception e)
		{
			log.debug("icon write failed for {}", f.getName(), e);
		}
	}

	private boolean writePng(BufferedImage img, File f)
	{
		try
		{
			return ImageIO.write(img, "png", f);
		}
		catch (Exception e)
		{
			log.debug("icon write failed for {}", f.getName(), e);
			return false;
		}
	}

	private File skillFile(Skill skill)
	{
		return skillFile(skill.getName());
	}

	private File skillFile(String skillName)
	{
		return new File(dir, "skill-" + skillName.toLowerCase(Locale.ROOT) + ".png");
	}

	private File questFile()
	{
		return new File(dir, "quest.png");
	}
}
