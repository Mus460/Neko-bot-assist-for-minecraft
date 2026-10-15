package dev.butterbot.network;

import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModEntities;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

	/** Players who got a non-empty list last time, so they get one empty update when their bots are gone. */
	private static final Set<UUID> hadBots = new HashSet<>();
	private static int timer;

	public static void tick(MinecraftServer server) {
		if (++timer < INTERVAL_TICKS) {
			return;
		}
		timer = 0;

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
					break;
				}
				int distance = bot.level() == player.level() ? (int) Math.round(Math.sqrt(bot.distanceToSqr(player))) : -1;
				Item icon = bot.hudIcon();
				lines.add(new Payloads.HudBot(bot.getName().getString(), bot.getHealth(), bot.getMaxHealth(), bot.describeHudStatus(),
						icon == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(icon).toString(), distance, bot.countInventoryItems(),
						bot.getInventory().getContainerSize() * 64));
			}
			ServerPlayNetworking.send(player, new Payloads.HudUpdate(lines));
		}
	}

	private HudSync() {
	}
}
