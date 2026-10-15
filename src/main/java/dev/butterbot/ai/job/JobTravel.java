package dev.butterbot.ai.job;

import dev.butterbot.ai.Travel;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Gets a working bot somewhere, however far, on foot: it keeps the area around itself loaded while it
 * walks (see {@link JobChunks}), works its way around obstacles with the route planner, and reaches
 * another dimension through a portal it came through. Only as a last resort (stuck for a long while
 * even with digging and building, or no portal known) does it hop there.
 */
public final class JobTravel {

	public enum Result {MOVING, ARRIVED, FAILED, NO_WAY}

	/** Not getting anywhere for this long (even with digging and building) counts as stuck. */
	private static final int MAX_STUCK_TICKS = 20 * 30;
	/** Generating the far side of a last-resort hop can take a while on a slow computer. */
	private static final int LOAD_TIMEOUT_TICKS = 20 * 30;

	private final Walker walker = new Walker();
	private int waitTicks;

	public void reset(ButterBotEntity bot) {
		walker.reset(bot);
		waitTicks = 0;
	}

	/**
	 * One step towards {@code pos} in {@code level}. With {@code surface} the Y of {@code pos} is ignored
	 * and the ground there is used (for spots in terrain nobody has seen yet). NO_WAY: the spot is in
	 * another dimension and the bot knows no portal there.
	 */
	public Result moveTo(ButterBotEntity bot, ServerLevel level, BlockPos pos, double reach, boolean surface) {
		if (bot.level() != level) {
			if (Travel.towards(bot, walker, level.dimension(), null)) {
				return Result.MOVING;
			}
			// No portal it knows leads there: the job can't wait forever, so it hops over as a last resort.
			bot.getNavigation().stop();
			if (waitTicks % 20 == 0) {
				JobChunks.preload(level, pos);
			}
			if (!level.isLoaded(pos)) {
				return ++waitTicks > LOAD_TIMEOUT_TICKS ? Result.NO_WAY : Result.MOVING;
			}
			waitTicks = 0;
			return bot.lastResortToLevel(level, surface ? ground(level, pos) : pos) ? Result.MOVING : Result.NO_WAY;
		}
		boolean loaded = level.isLoaded(pos);
		BlockPos target = surface && loaded ? ground(level, pos) : pos;
		if (loaded && bot.distanceToSqr(Vec3.atBottomCenterOf(target)) <= reach * reach) {
			bot.getNavigation().stop();
			return Result.ARRIVED;
		}
		// Not loaded yet (far away): head that way at the bot's own height; it loads as the bot gets near.
		Vec3 goal = loaded ? Vec3.atBottomCenterOf(target) : new Vec3(pos.getX() + 0.5D, bot.getY(), pos.getZ() + 0.5D);
		if (!walker.walk(bot, goal, 1.1D, true, MAX_STUCK_TICKS)) {
			walker.reset(bot);
			// Stuck even with the route planner digging and building: hop there as a last resort.
			if (loaded && bot.lastResortTeleport(target)) {
				return Result.MOVING;
			}
			bot.sayStuck();
			return Result.FAILED;
		}
		return Result.MOVING;
	}

	/** The first spot above the ground (leaves and plants don't count as ground). */
	public static BlockPos ground(ServerLevel level, BlockPos pos) {
		return new BlockPos(pos.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()), pos.getZ());
	}
}
