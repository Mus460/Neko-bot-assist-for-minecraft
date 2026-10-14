package dev.butterbot.client;

import dev.butterbot.ButterBot;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A slim list of the player's bots in the top-left corner, one line per bot: a health strip, a small
 * icon for the job, the name, what it is doing and how far away it is ("Full" when its bag is
 * nearly full). Toggled with a key (default ]); the choice is remembered.
 */
public final class BotHud implements HudElement {

	private static final Path SETTINGS = FabricLoader.getInstance().getConfigDir().resolve("butterbot").resolve("hud.txt");
	private static final long STALE_MILLIS = 5000L;
	private static final int WIDTH = 136;
	private static final int ROW_HEIGHT = 11;
	private static final int MAX_ROWS = 6;
	private static final int NAME_WIDTH = 44;
	private static final float FULL_BAG = 0.9F;

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
		int x = 3;
		int y = 3;
		int rows = Math.min(MAX_ROWS, bots.size());
		boolean more = bots.size() > MAX_ROWS;
		g.fill(x - 2, y - 2, x + WIDTH + 2, y + rows * ROW_HEIGHT + (more ? ROW_HEIGHT : 0), 0x60000000);
		for (int i = 0; i < rows; i++) {
			Payloads.HudBot bot = bots.get(i);
			int rowY = y + i * ROW_HEIGHT;

			// Health as a thin strip on the left, filling up from the bottom.
			float health = bot.maxHealth() <= 0.0F ? 0.0F : Mth.clamp(bot.health() / bot.maxHealth(), 0.0F, 1.0F);
			int filled = Math.max(1, Math.round(8 * health));
			g.fill(x, rowY, x + 2, rowY + 8, 0xFF2A2A2A);
			g.fill(x, rowY + 8 - filled, x + 2, rowY + 8, healthColor(health));

			ItemStack icon = iconOf(bot.icon());
			if (!icon.isEmpty()) {
				g.pose().pushMatrix();
				g.pose().translate(x + 4, rowY);
				g.pose().scale(0.5F);
				g.item(icon, 0, 0);
				g.pose().popMatrix();
			}

			int textX = x + 14;
			String name = font.plainSubstrByWidth(bot.name(), NAME_WIDTH);
			g.text(font, name, textX, rowY, 0xFFFFFFFF, true);

			boolean full = bot.capacity() > 0 && bot.items() >= bot.capacity() * FULL_BAG;
			String where = bot.distance() < 0 ? Component.translatable("butterbot.hud.other_dimension").getString() : bot.distance() + "m";
			String right = full ? Component.translatable("butterbot.hud.full").getString() + " " + where : where;
			int rightX = x + WIDTH - font.width(right);
			g.text(font, right, rightX, rowY, full ? 0xFFFF7A6A : 0xFFA0A0A0, true);

			int statusX = textX + font.width(name) + 4;
			int statusWidth = rightX - 4 - statusX;
			if (statusWidth > 8) {
				g.text(font, font.plainSubstrByWidth(bot.status().getString(), statusWidth), statusX, rowY, 0xFFCFCFCF, true);
			}
		}
		if (more) {
			g.text(font, "+" + (bots.size() - MAX_ROWS), x + 14, y + rows * ROW_HEIGHT, 0xFFA0A0A0, true);
		}
	}

	private static ItemStack iconOf(String id) {
		Identifier identifier = id.isEmpty() ? null : Identifier.tryParse(id);
		return identifier == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
	}

	private static int healthColor(float fraction) {
		if (fraction > 0.6F) {
			return 0xFF5BD35B;
		}
		return fraction > 0.3F ? 0xFFE8C23A : 0xFFE0483A;
	}
}
