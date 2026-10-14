package dev.butterbot.network;

import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side validation for every control packet: the player must own the bot and either stand
 * at a Bot Base (bot within 200 blocks of it) or be within 200 blocks of the bot itself.
 */
public final class BotAccess {

	public static final double BASE_USE_DISTANCE_SQ = 8.0D * 8.0D;
	public static final double BASE_RANGE = 200.0D;
	public static final double DIRECT_RANGE = 200.0D;

	public static boolean isValidBase(ServerPlayer player, Optional<BlockPos> base) {
		if (base.isEmpty()) {
			return false;
		}
		BlockPos pos = base.get();
		ServerLevel level = player.level();
		return level.isLoaded(pos)
				&& level.getBlockState(pos).is(ModBlocks.BOT_BASE)
				&& player.distanceToSqr(Vec3.atCenterOf(pos)) <= BASE_USE_DISTANCE_SQ;
	}

	@Nullable
	public static ButterBotEntity resolve(ServerPlayer player, UUID botId, Optional<BlockPos> base) {
		Entity entity = player.level().getEntity(botId);
		if (!(entity instanceof ButterBotEntity bot) || !bot.isAlive() || !bot.isOwnedBy(player)) {
			return null;
		}
		if (isValidBase(player, base) && bot.distanceToSqr(Vec3.atCenterOf(base.get())) <= BASE_RANGE * BASE_RANGE) {
			return bot;
		}
		if (player.distanceToSqr(bot) <= DIRECT_RANGE * DIRECT_RANGE) {
			return bot;
		}
		return null;
	}

	/** The player's bots near the base (if valid) and near the player, nearest first. */
	public static List<ButterBotEntity> listBots(ServerPlayer player, Optional<BlockPos> base) {
		ServerLevel level = player.level();
		Set<ButterBotEntity> found = new LinkedHashSet<>();
		Vec3 sortCenter = player.position();
		if (isValidBase(player, base)) {
			sortCenter = Vec3.atCenterOf(base.get());
			found.addAll(level.getEntitiesOfClass(ButterBotEntity.class, new AABB(base.get()).inflate(BASE_RANGE),
					bot -> bot.isAlive() && bot.isOwnedBy(player)));
		}
		found.addAll(level.getEntitiesOfClass(ButterBotEntity.class, player.getBoundingBox().inflate(DIRECT_RANGE),
				bot -> bot.isAlive() && bot.isOwnedBy(player)));
		List<ButterBotEntity> list = new ArrayList<>(found);
		Vec3 center = sortCenter;
		list.sort(Comparator.comparingDouble(bot -> bot.distanceToSqr(center)));
		return list;
	}

	private BotAccess() {
	}
}
