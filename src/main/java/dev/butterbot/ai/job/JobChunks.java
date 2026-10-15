package dev.butterbot.ai.job;

import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps working bots running when no player is around. Normally the world stops a few chunks away
 * from the player, which would freeze a miner 300 blocks from the base. Bots with a job, and bots on
 * their way somewhere, keep the chunks right around them loaded (like an ender pearl in flight does),
 * so a long walk never stops halfway.
 */
public final class JobChunks {

	private static final int INTERVAL_TICKS = 20;
	/** 3 keeps the bot's chunk and its 8 neighbours fully running, so it never freezes at a chunk border. */
	private static final int KEEP_RADIUS = 3;
	private static final int PRELOAD_RADIUS = 2;

	private static int timer;

	public static void tick(MinecraftServer server) {
		if (++timer < INTERVAL_TICKS) {
			return;
		}
		timer = 0;
		for (ServerLevel level : server.getAllLevels()) {
			// Paused jobs too: a bot paused far away must stay loaded, or it couldn't be found to resume it.
			// Bots walk everywhere now, however far: one on its way somewhere keeps going without the player near.
			for (ButterBotEntity bot : level.getEntities(ModEntities.BUTTER_BOT, bot -> bot.isAlive() && bot.keepsChunksLoaded())) {
				keepLoaded(level, bot.blockPosition());
			}
		}
	}

	public static void keepLoaded(ServerLevel level, BlockPos pos) {
		// The portal ticket is saved with the world and runs out on its own after 15 seconds.
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(pos), KEEP_RADIUS);
	}

	/** Starts loading (or generating) the area around {@code pos}; check {@code level.isLoaded(pos)} for when it is ready. */
	public static void preload(ServerLevel level, BlockPos pos) {
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(pos), PRELOAD_RADIUS);
	}

	private JobChunks() {
	}
}
