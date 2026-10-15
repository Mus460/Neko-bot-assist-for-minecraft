package dev.butterbot.ai.job;

import dev.butterbot.ai.Walker;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Gets a working bot somewhere, however far: it walks short distances, and for long ones (or another
 * dimension) it first loads the destination and then hops there.
 */
public final class JobTravel {

	public enum Result {MOVING, ARRIVED, FAILED}

	/** Farther than this the bot hops instead of walking, like everywhere else in the mod. */
	private static final double SHORTCUT_SQ = 64.0D * 64.0D;
	/** Generating new terrain can take a while on a slow computer. */
	private static final int LOAD_TIMEOUT_TICKS = 20 * 30;
	private static final int MAX_STUCK_TICKS = 120;

	/** Right after a hop the bot may land a few blocks off (a safe spot near the target): close enough. */
	private static final double AFTER_HOP_REACH = 4.5D;

	private final Walker walker = new Walker();
	private int waitTicks;
	private boolean hopped;

	public void reset(ButterBotEntity bot) {
		walker.reset(bot);
		waitTicks = 0;
		hopped = false;
	}

	/**
	 * One step towards {@code pos} in {@code level}. With {@code surface} the Y of {@code pos} is ignored
	 * and the ground there is used (for spots in terrain nobody has seen yet).
	 */
	public Result moveTo(ButterBotEntity bot, ServerLevel level, BlockPos pos, double reach, boolean surface) {
		boolean sameLevel = bot.level() == level;
		BlockPos target = surface && level.isLoaded(pos) ? ground(level, pos) : pos;
		double distanceSq = sameLevel ? bot.distanceToSqr(Vec3.atBottomCenterOf(target)) : Double.MAX_VALUE;

		double arriveAt = hopped ? Math.max(reach, AFTER_HOP_REACH) : reach;
		if (distanceSq <= arriveAt * arriveAt) {
			bot.getNavigation().stop();
			waitTicks = 0;
			hopped = false;
			return Result.ARRIVED;
		}

		if (!sameLevel || distanceSq > SHORTCUT_SQ || !level.isLoaded(pos)) {
			bot.getNavigation().stop();
			if (waitTicks % 20 == 0) {
				JobChunks.preload(level, pos);
			}
			if (!level.isLoaded(pos)) {
				return ++waitTicks > LOAD_TIMEOUT_TICKS ? Result.FAILED : Result.MOVING;
			}
			target = surface ? ground(level, pos) : pos;
			boolean done = sameLevel ? bot.teleportNear(target, false) : bot.followToLevel(level, target);
			waitTicks = 0;
			walker.reset(bot);
			hopped |= done;
			return done ? Result.MOVING : Result.FAILED;
		}

		if (!walker.walk(bot, Vec3.atBottomCenterOf(target), 1.1D, false, MAX_STUCK_TICKS)) {
			walker.reset(bot);
			boolean done = bot.teleportNear(target, false);
			hopped |= done;
			return done ? Result.MOVING : Result.FAILED;
		}
		return Result.MOVING;
	}

	/** The first spot above the ground (leaves and plants don't count as ground). */
	public static BlockPos ground(ServerLevel level, BlockPos pos) {
		return new BlockPos(pos.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()), pos.getZ());
	}
}
