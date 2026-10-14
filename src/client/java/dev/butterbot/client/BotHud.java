package dev.butterbot.client;

import dev.butterbot.ButterBot;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Small list of the player's bots in the top-left corner: name, level, health bar, what each bot
 * is doing and how far away it is. Toggled with a key (default ]); the choice is remembered.
 */
public final class BotHud implements HudElement {

	private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("butterbot").resolve("hud.txt");
	private static final long STALE_MILLIS = 5000L;
	private static final int WIDTH = 150;
	private static final int ROW_HEIGHT = 25;
	private static final int MAX_ROWS = 6;

	private static List<Payloads.HudBot> bots = List.of();
	private static long receivedAt;
	private static boolean visible = loadVisible();

	public static void update(Payloads.HudUpdate update) {
		bots = List.copyOf(update.bots());
		receivedAt = Util.getMillis();
	}

	public static void clear() {
		bots = List.of();
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
		Minecraft minecraft = Minecraft.getInstance();
		if (!visible || bots.isEmpty() || minecraft.player == null
				|| minecraft.getDebugOverlay().showDebugScreen() || Util.getMillis() - receivedAt > STALE_MILLIS) {
			return;
		}
		Font font = minecraft.font;
		int x = 4;
		int y = 4;
		int rows = Math.min(MAX_ROWS, bots.size());
		g.fill(x - 3, y - 3, x + WIDTH + 3, y + rows * ROW_HEIGHT - 1, 0x70000000);
		for (int i = 0; i < rows; i++) {
			Payloads.HudBot bot = bots.get(i);
			int rowY = y + i * ROW_HEIGHT;

			String name = font.plainSubstrByWidth(bot.name(), WIDTH - 70);
			g.text(font, name, x, rowY, 0xFFFFFFFF, true);
			g.text(font, "Lv" + bot.level(), x + font.width(name) + 4, rowY, 0xFFFFC94A, true);
			String where = bot.distance() < 0 ? Component.translatable("butterbot.hud.other_dimension").getString() : bot.distance() + "m";
			g.text(font, where, x + WIDTH - font.width(where), rowY, 0xFFB8B8B8, true);

			float health = bot.maxHealth() <= 0.0F ? 0.0F : Mth.clamp(bot.health() / bot.maxHealth(), 0.0F, 1.0F);
			int barY = rowY + 10;
			g.fill(x, barY, x + WIDTH, barY + 2, 0xFF2A2A2A);
			g.fill(x, barY, x + Math.round(WIDTH * health), barY + 2, healthColor(health));

			String status = bot.status().getString();
			String items = bot.items() + "/" + bot.capacity();
			int statusWidth = WIDTH - font.width(items) - 6;
			g.text(font, font.plainSubstrByWidth(status, statusWidth), x, rowY + 14, 0xFFD8D8D8, true);
			g.text(font, items, x + WIDTH - font.width(items), rowY + 14, bot.items() >= bot.capacity() ? 0xFFFF7A6A : 0xFF9AD27A, true);
		}
		if (bots.size() > MAX_ROWS) {
			g.text(font, "+" + (bots.size() - MAX_ROWS), x, y + rows * ROW_HEIGHT + 1, 0xFFB8B8B8, true);
		}
	}

	private static int healthColor(float fraction) {
		if (fraction > 0.6F) {
			return 0xFF5BD35B;
		}
		return fraction > 0.3F ? 0xFFE8C23A : 0xFFE0483A;
	}
}
