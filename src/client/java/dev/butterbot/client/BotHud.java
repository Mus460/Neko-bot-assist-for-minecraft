package dev.butterbot.client;

import dev.butterbot.ButterBot;
import dev.butterbot.entity.BotTier;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A slim list of the player's bots in the top-left corner, one line per bot: a health strip, a small
 * icon for the job, the level and name, what it is doing and how far away it is ("Full" when its bag
 * is nearly full), with a thin line under it for how far along its work is. Trouble shows at a glance:
 * a blinking amber "!" when a bot is stuck or had to skip something, a red pulse while it is hurt or
 * low on health. Toggled with a key (default ]); the choice is remembered.
 */
public final class BotHud implements HudElement {

	private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("butterbot").resolve("hud.txt");
	private static final long STALE_MILLIS = 5000L;
	private static final int WIDTH = 150;
	private static final int ROW_HEIGHT = 11;
	private static final int MAX_ROWS = 10;
	private static final int NAME_WIDTH = 44;
	private static final float FULL_BAG = 0.9F;
	/** Amber for "something is wrong". */
	private static final int WARNING = 0xFFFFB020;
	/** The green of the experience bars in the panel. */
	private static final int PROGRESS = 0xFF80FF20;
	/** How quickly the health strip and the work line slide to a new value (per second). */
	private static final float EASE_RATE = 8.0F;
	/** How long the white "just lost" part of the health strip takes to fade. */
	private static final float LOST_FADE_MILLIS = 600.0F;
	/** The health strip is drawn in quarter pixels, so it slides smoothly. */
	private static final int STRIP_STEPS = 32;

	private static List<Payloads.HudBot> bots = List.of();
	private static long receivedAt;
	private static boolean visible = loadVisible();
	/** What each line showed last frame, so a change slides and fades instead of jumping. */
	private static final Map<UUID, RowAnim> anims = new HashMap<>();
	private static long lastFrame;

	private static final class RowAnim {
		/** Health fraction as drawn, and as last sent (-1 before the first frame). */
		float shownHealth;
		float health = -1.0F;
		/** Top of the white part that shows what was just lost, and when it was lost. */
		float lostTop;
		long lostAt = Long.MIN_VALUE / 2;
		float progress = -1.0F;
		int flags;
	}

	public static void update(Payloads.HudUpdate update) {
		bots = List.copyOf(update.bots());
		receivedAt = Util.getMillis();
		boolean newlyStuck = false;
		Set<UUID> present = new HashSet<>();
		for (Payloads.HudBot bot : bots) {
			present.add(bot.id());
			RowAnim anim = anims.computeIfAbsent(bot.id(), id -> new RowAnim());
			if (bot.has(Payloads.HudBot.STUCK) && (anim.flags & Payloads.HudBot.STUCK) == 0) {
				newlyStuck = true;
			}
			anim.flags = bot.flags();
		}
		anims.keySet().retainAll(present);
		Minecraft minecraft = Minecraft.getInstance();
		if (newlyStuck && visible && minecraft.player != null) {
			// One soft ding when a bot gets stuck, not on every update while it stays stuck.
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.5F));
		}
	}

	public static void clear() {
		bots = List.of();
		anims.clear();
	}

	/** Bots in this dimension (which the "all bots" orders reach) by the last update, or -1 if there is none lately. */
	public static int botsInReach() {
		if (Util.getMillis() - receivedAt > STALE_MILLIS) {
			return -1;
		}
		return (int) bots.stream().filter(bot -> bot.distance() >= 0).count();
	}

	public static void toggle() {
		visible = !visible;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendOverlayMessage(Component.translatable(visible ? "butterbot.hud.shown" : "butterbot.hud.hidden"));
		}
		try {
			Files.createDirectories(SETTINGS.getParent());
			Files.writeString(SETTINGS, visible ? "visible" : "hidden", StandardCharsets.UTF_8);
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not save the Butter Bot HUD setting", exception);
		}
	}

	private static boolean loadVisible() {
		try {
			return !Files.exists(SETTINGS) || !Files.readString(SETTINGS, StandardCharsets.UTF_8).trim().equals("hidden");
		} catch (IOException exception) {
			return true;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		long now = Util.getMillis();
		float seconds = lastFrame == 0L ? 0.0F : Mth.clamp((now - lastFrame) / 1000.0F, 0.0F, 0.25F);
		lastFrame = now;
		Minecraft minecraft = Minecraft.getInstance();
		if (!visible || bots.isEmpty() || minecraft.player == null
				|| minecraft.getDebugOverlay().showDebugScreen() || now - receivedAt > STALE_MILLIS) {
			return;
		}
		float ease = 1.0F - (float) Math.exp(-seconds * EASE_RATE);
		// Both pulses go from dim to bright and back about once a second.
		float blink = 0.5F + 0.5F * (float) Math.sin(now / 150.0D);
		float throb = 0.5F + 0.5F * (float) Math.sin(now / 200.0D);
		Font font = minecraft.font;
		int x = 3;
		int y = 3;
		int rows = Math.min(MAX_ROWS, bots.size());
		boolean more = bots.size() > MAX_ROWS;
		g.fill(x - 2, y - 2, x + WIDTH + 2, y + rows * ROW_HEIGHT + (more ? ROW_HEIGHT : 0), 0x60000000);
		for (int i = 0; i < rows; i++) {
			Payloads.HudBot bot = bots.get(i);
			RowAnim anim = anims.computeIfAbsent(bot.id(), id -> new RowAnim());
			int rowY = y + i * ROW_HEIGHT;

			// Hurt or low on health: the line pulses red.
			if (bot.has(Payloads.HudBot.HURT) || bot.has(Payloads.HudBot.LOW_HEALTH)) {
				int alpha = 0x30 + Math.round(0x30 * throb);
				g.fill(x - 2, rowY - 1, x + WIDTH + 2, rowY + ROW_HEIGHT - 1, alpha << 24 | 0xE02828);
			}

			drawHealth(g, bot, anim, x, rowY, now, ease);

			// Stuck or had to skip something: a blinking "!" where the job icon goes.
			boolean warn = bot.has(Payloads.HudBot.STUCK) || bot.has(Payloads.HudBot.PROBLEM);
			if (warn) {
				Component mark = Component.literal("!").withStyle(ChatFormatting.BOLD);
				g.text(font, mark, x + 8 - font.width(mark) / 2, rowY, ARGB.color(0.4F + 0.6F * blink, WARNING), true);
			} else {
				ItemStack icon = iconOf(bot.icon());
				if (!icon.isEmpty()) {
					g.pose().pushMatrix();
					g.pose().translate(x + 4, rowY);
					g.pose().scale(0.5F);
					g.item(icon, 0, 0);
					g.pose().popMatrix();
				}
			}

			// The level in its tier's colour, then the name.
			int textX = x + 14;
			String level = String.valueOf(bot.level());
			g.text(font, level, textX, rowY, BotTier.of(bot.level()).color(), true);
			int nameX = textX + font.width(level) + 2;
			String name = font.plainSubstrByWidth(bot.name(), NAME_WIDTH);
			g.text(font, name, nameX, rowY, 0xFFFFFFFF, true);

			boolean full = bot.capacity() > 0 && bot.items() >= bot.capacity() * FULL_BAG;
			String where = bot.distance() < 0 ? Component.translatable("butterbot.hud.other_dimension").getString() : bot.distance() + "m";
			String right = full ? Component.translatable("butterbot.hud.full").getString() + " " + where : where;
			int rightX = x + WIDTH - font.width(right);
			g.text(font, right, rightX, rowY, full ? 0xFFFF7A6A : 0xFFA0A0A0, true);

			int statusX = nameX + font.width(name) + 4;
			int statusWidth = rightX - 4 - statusX;
			if (statusWidth > 8) {
				// The server puts "Stuck!" or the problem in the status, except over fighting or a paused job.
				boolean warningText = bot.has(Payloads.HudBot.STUCK) || (bot.has(Payloads.HudBot.PROBLEM)
						&& !bot.has(Payloads.HudBot.FIGHTING) && !bot.has(Payloads.HudBot.PAUSED));
				g.text(font, font.plainSubstrByWidth(bot.status().getString(), statusWidth), statusX, rowY,
						warningText ? WARNING : 0xFFCFCFCF, true);
			}

			drawProgress(g, bot, anim, textX, x + WIDTH, rowY + 9, ease);
		}
		if (more) {
			g.text(font, "+" + (bots.size() - MAX_ROWS), x + 14, y + rows * ROW_HEIGHT, 0xFFA0A0A0, true);
		}
	}

	/**
	 * Health as a thin strip on the left, filling up from the bottom. It slides to a new value, and what
	 * was just lost shows white for a moment above it.
	 */
	private static void drawHealth(GuiGraphicsExtractor g, Payloads.HudBot bot, RowAnim anim, int x, int rowY, long now, float ease) {
		float health = bot.maxHealth() <= 0.0F ? 0.0F : Mth.clamp(bot.health() / bot.maxHealth(), 0.0F, 1.0F);
		if (anim.health < 0.0F) {
			anim.shownHealth = health;
		} else if (health < anim.health) {
			// Lost some: keep the top of a white part that is still fading, so two hits in a row read as one.
			anim.lostTop = lostFade(anim, now) > 0.0F ? Math.max(anim.lostTop, anim.shownHealth) : anim.shownHealth;
			anim.lostAt = now;
		}
		anim.health = health;
		anim.shownHealth += (health - anim.shownHealth) * ease;

		g.pose().pushMatrix();
		g.pose().translate(x, rowY);
		g.pose().scale(1.0F, 8.0F / STRIP_STEPS);
		g.fill(0, 0, 2, STRIP_STEPS, 0xFF2A2A2A);
		int shown = Math.max(STRIP_STEPS / 8, Math.round(STRIP_STEPS * anim.shownHealth));
		float lost = lostFade(anim, now);
		int lostTop = Math.round(STRIP_STEPS * anim.lostTop);
		if (lost > 0.0F && lostTop > shown) {
			g.fill(0, STRIP_STEPS - lostTop, 2, STRIP_STEPS - shown, ARGB.color(lost, 0xFFFFFF));
		}
		g.fill(0, STRIP_STEPS - shown, 2, STRIP_STEPS, healthColor(anim.shownHealth));
		g.pose().popMatrix();
	}

	/** How strongly the white "just lost" part still shows: 1 right after the hit, 0 once it has faded. */
	private static float lostFade(RowAnim anim, long now) {
		return Mth.clamp(1.0F - (now - anim.lostAt) / LOST_FADE_MILLIS, 0.0F, 1.0F);
	}

	/** A 1 px line under the text for how far along the work is (nothing when there is nothing to measure). */
	private static void drawProgress(GuiGraphicsExtractor g, Payloads.HudBot bot, RowAnim anim, int left, int right, int lineY, float ease) {
		if (bot.progress() < 0.0F) {
			anim.progress = -1.0F;
			return;
		}
		float progress = Mth.clamp(bot.progress(), 0.0F, 1.0F);
		anim.progress = anim.progress < 0.0F ? progress : anim.progress + (progress - anim.progress) * ease;
		g.fill(left, lineY, right, lineY + 1, 0x40FFFFFF);
		int filled = Math.round((right - left) * anim.progress);
		if (filled > 0) {
			g.fill(left, lineY, left + filled, lineY + 1, PROGRESS);
		}
	}

	private static ItemStack iconOf(String id) {
		Identifier identifier = id.isEmpty() ? null : Identifier.tryParse(id);
		return identifier == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
	}

	static int healthColor(float fraction) {
		if (fraction > 0.6F) {
			return 0xFF5BD35B;
		}
		return fraction > 0.3F ? 0xFFE8C23A : 0xFFE0483A;
	}
}
