package dev.butterbot.network;

import dev.butterbot.ai.path.DigPath;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The debug key: tells the owner what each nearby bot is doing (task, target, route, last problem)
 * and for a while draws each bot's target block and dig route in particles that only the owner sees.
 * Meant for working out why a bot is stuck from a screenshot.
 */
public final class BotDebug {

	private static final int SHOW_SECONDS = 20;
	private static final int INTERVAL_TICKS = 5;
	private static final double RANGE = 96.0D;

	/** Players with the view on, and the game time it turns off. */
	private static final Map<UUID, Long> watching = new HashMap<>();
	private static int timer;

	/** Forgets every view (the game times belong to the world that is closing). */
	public static void clear() {
		watching.clear();
		timer = 0;
	}

	/** Turns the view on (listing every bot) or, when it is on, off again. */
	public static void toggle(ServerPlayer player, List<ButterBotEntity> bots) {
		long now = player.level().getGameTime();
		Long until = watching.get(player.getUUID());
		if (until != null && until > now) {
			watching.remove(player.getUUID());
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.debug.off")));
			return;
		}
		watching.put(player.getUUID(), now + SHOW_SECONDS * 20L);
		player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.debug.on", SHOW_SECONDS)));
		for (ButterBotEntity bot : bots) {
			player.sendSystemMessage(describe(bot));
		}
	}

	private static Component describe(ButterBotEntity bot) {
		MutableComponent line = Component.literal("- ").append(bot.getName().copy().withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" [" + bot.blockPosition().toShortString() + "] ").withStyle(ChatFormatting.DARK_GRAY))
				.append(bot.describeShortStatus());
		BlockPos target = bot.getRouteTarget();
		if (target != null) {
			line.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.translatable("butterbot.debug.target",
					bot.level().getBlockState(target).getBlock().getName(), target.toShortString()));
		}
		DigPath route = bot.getRoute();
		if (route != null) {
			line.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.translatable("butterbot.debug.route",
					Math.min(bot.getRouteProgress(), route.size()), route.size()));
		}
		Component problem = bot.getLastProblem();
		if (problem != null) {
			line.append(Component.literal("\n   ")).append(Component.translatable("butterbot.debug.problem", problem)
					.withStyle(ChatFormatting.RED));
		}
		return line;
	}

	public static void tick(MinecraftServer server) {
		if (watching.isEmpty() || ++timer < INTERVAL_TICKS) {
			return;
		}
		timer = 0;
		for (Iterator<Map.Entry<UUID, Long>> it = watching.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || player.level().getGameTime() > entry.getValue()) {
				it.remove();
				continue;
			}
			ServerLevel level = player.level();
			for (ButterBotEntity bot : level.getEntities(ModEntities.BUTTER_BOT,
					bot -> bot.isAlive() && bot.isOwnedBy(player) && bot.distanceToSqr(player) < RANGE * RANGE)) {
				draw(level, player, bot);
			}
		}
	}

	/** The target glows; the rest of the route is a line of white dots, with sparks on blocks still to dig. */
	private static void draw(ServerLevel level, ServerPlayer player, ButterBotEntity bot) {
		BlockPos target = bot.getRouteTarget();
		if (target != null) {
			level.sendParticles(player, ParticleTypes.GLOW, true, true,
					target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 6, 0.35D, 0.35D, 0.35D, 0.0D);
		}
		DigPath route = bot.getRoute();
		if (route == null) {
			return;
		}
		List<DigPath.Step> steps = route.steps();
		for (int i = Math.max(0, bot.getRouteProgress()); i < steps.size(); i++) {
			DigPath.Step step = steps.get(i);
			BlockPos feet = step.feet();
			level.sendParticles(player, ParticleTypes.END_ROD, true, true,
					feet.getX() + 0.5D, feet.getY() + 0.2D, feet.getZ() + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			for (BlockPos dig : step.dig()) {
				if (!level.getBlockState(dig).getCollisionShape(level, dig).isEmpty()) {
					level.sendParticles(player, ParticleTypes.ELECTRIC_SPARK, true, true,
							dig.getX() + 0.5D, dig.getY() + 0.5D, dig.getZ() + 0.5D, 1, 0.2D, 0.2D, 0.2D, 0.0D);
				}
			}
		}
	}

	private BotDebug() {
	}
}
