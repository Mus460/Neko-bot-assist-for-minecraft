package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Getting a bot somewhere else without ever teleporting it: other dimensions are reached on foot
 * through a portal, like a player would. The bot remembers the portals it came through, so it can
 * always go back the way it came.
 */
public final class Travel {

	private static final double INSIDE_SQ = 1.5D * 1.5D;
	private static final int MAX_STUCK_TICKS = 20 * 30;

	/** The Nether or End portal block nearest to {@code pos} (within a couple of blocks), or null. */
	@Nullable
	public static BlockPos portalNear(Level level, BlockPos pos) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 2, 2))) {
			if (!level.isLoaded(near) || !isPortal(level.getBlockState(near))) {
				continue;
			}
			double distance = near.distSqr(pos);
			if (distance < bestDistance) {
				best = near.immutable();
				bestDistance = distance;
			}
		}
		return best;
	}

	public static boolean isPortal(BlockState state) {
		return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL);
	}

	/**
	 * One step of walking into a portal to {@code dimension}: the one at {@code preferred} if there is
	 * one there (where the owner went through), otherwise one the bot came through from that
	 * dimension. False when it knows no way there (the caller waits and says so).
	 */
	public static boolean towards(ButterBotEntity bot, Walker walker, ResourceKey<Level> dimension, @Nullable BlockPos preferred) {
		Level level = bot.level();
		BlockPos portal = preferred == null || !level.isLoaded(preferred) ? null : portalNear(level, preferred);
		if (portal == null) {
			portal = bot.portalTowards(dimension);
			if (portal != null && level.isLoaded(portal) && portalNear(level, portal) == null) {
				// The portal has been broken since.
				bot.forgetPortal(dimension);
				portal = null;
			}
		}
		if (portal == null || !bot.wantPortal(dimension)) {
			// No way it knows, or it has tried this one for too long: the caller falls back.
			return false;
		}
		Vec3 inside = Vec3.atBottomCenterOf(portal);
		if (bot.position().distanceToSqr(inside) > INSIDE_SQ) {
			if (!walker.walk(bot, inside, 1.1D, true, MAX_STUCK_TICKS)) {
				walker.reset(bot);
				bot.sayStuck();
			}
		} else {
			// Right in front of it: step in (the game does the rest).
			bot.getNavigation().stop();
			bot.getMoveControl().setWantedPosition(inside.x, inside.y, inside.z, 1.0D);
		}
		return true;
	}

	/** "the Nether", "the Overworld"... for messages. */
	public static Component dimensionName(ResourceKey<Level> dimension) {
		String path = dimension.identifier().getPath();
		return Component.translatableWithFallback("butterbot.dim." + path, path);
	}

	private Travel() {
	}
}
