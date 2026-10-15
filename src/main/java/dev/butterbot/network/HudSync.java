package dev.butterbot.network;

import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModEntities;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Once a second, sends every player a short summary of their bots for the on-screen list. */
public final class HudSync {

	private static final int INTERVAL_TICKS = 20;
	private static final int MAX_LINES = 10;
	/** Bots being shown come on top of the nearest ones, up to what the client reads. */
	private static final int MAX_WITH_SHOWN = 16;
	/** As long as the client's "Show" lasts. */
	private static final long SHOW_MILLIS = 30_000L;

	/** Bots someone pressed "Show" for, and until when ({@link Util#getMillis()}), so they get a marker however far away. */
	private static final Map<UUID, Long> shown = new HashMap<>();

	/** Players who got a non-empty list last time, so they get one empty update when their bots are gone. */
	private static final Set<UUID> hadBots = new HashSet<>();
	private static int timer;

	public static void markShown(ButterBotEntity bot) {
		shown.put(bot.getUUID(), Util.getMillis() + SHOW_MILLIS);
	}

	public static void tick(MinecraftServer server) {
		if (++timer < INTERVAL_TICKS) {
			return;
		}
		timer = 0;
		long now = Util.getMillis();
		shown.values().removeIf(end -> end <= now);

		Map<UUID, List<ButterBotEntity>> byOwner = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			for (ButterBotEntity bot : level.getEntities(ModEntities.BUTTER_BOT, bot -> bot.isAlive() && bot.getOwnerUuid() != null)) {
				byOwner.computeIfAbsent(bot.getOwnerUuid(), id -> new ArrayList<>()).add(bot);
			}
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			List<ButterBotEntity> bots = byOwner.getOrDefault(player.getUUID(), List.of());
			if (!ServerPlayNetworking.canSend(player, Payloads.HudUpdate.TYPE)
					|| (bots.isEmpty() && !hadBots.remove(player.getUUID()))) {
				continue;
			}
			if (!bots.isEmpty()) {
				hadBots.add(player.getUUID());
			}
			List<ButterBotEntity> sorted = new ArrayList<>(bots);
			// Bots in the player's dimension first, nearest first.
			sorted.sort(Comparator.comparingDouble(bot -> bot.level() == player.level() ? bot.distanceToSqr(player) : Double.MAX_VALUE));
			List<Payloads.HudBot> lines = new ArrayList<>();
			for (ButterBotEntity bot : sorted) {
				if (lines.size() >= MAX_LINES) {
					// Past the nearest ones, only bots being shown.
					if (shown.isEmpty() || lines.size() >= MAX_WITH_SHOWN) {
						break;
					}
					if (!shown.containsKey(bot.getUUID())) {
						continue;
					}
				}
				int distance = bot.level() == player.level() ? (int) Math.round(Math.sqrt(bot.distanceToSqr(player))) : -1;
				Item icon = bot.hudIcon();
				int flags = bot.hudFlags();
				lines.add(new Payloads.HudBot(bot.getName().getString(), bot.getHealth(), bot.getMaxHealth(), status(bot, flags),
						icon == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(icon).toString(), distance, bot.countInventoryItems(),
						bot.getInventory().getContainerSize() * 64, bot.getUUID(), bot.getBotLevel(), flags, bot.hudProgress(),
						(float) bot.getX(), (float) bot.getY(), (float) bot.getZ(), bot.getSkinId(), bot.getSkinVariant()));
			}
			ServerPlayNetworking.send(player, new Payloads.HudUpdate(lines));
		}
	}

	/**
	 * What the line says: "Stuck!" while the bot can't find its way, then fighting or a paused job, then a
	 * problem it had lately (only the start of the sentence fits), then its work.
	 */
	private static Component status(ButterBotEntity bot, int flags) {
		if ((flags & Payloads.HudBot.STUCK) != 0) {
			return Component.translatable("butterbot.hud.s.stuck");
		}
		Component problem = bot.getHudProblem();
		if (problem != null && (flags & (Payloads.HudBot.FIGHTING | Payloads.HudBot.PAUSED)) == 0) {
			return problem;
		}
		return bot.describeHudStatus();
	}

	private HudSync() {
	}
}
