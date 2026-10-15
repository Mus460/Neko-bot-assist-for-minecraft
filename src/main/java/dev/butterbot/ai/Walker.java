package dev.butterbot.ai;

import dev.butterbot.ai.path.DigPath;
import dev.butterbot.ai.path.DigPlanner;
import dev.butterbot.ai.path.PathFollower;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Shared "walk to a point" logic: periodic re-pathing, waypoints for far targets, stuck detection
 * and, optionally, digging or building a way through when the path is blocked.
 */
public final class Walker {

	private static final int REPATH_INTERVAL = 10;
	/** Pathfinding only looks this far, so farther targets are walked to via waypoints. */
	private static final double WAYPOINT_DISTANCE = 24.0D;
	private static final double FAR_TARGET = 40.0D;
	private static final int UNSTICK_AFTER_TICKS = 30;
	private static final int UNSTICK_INTERVAL = 10;
	/** A walk never builds more than this in one go (no endless bridges across the ocean). */
	private static final int MAX_BLOCKS_PLACED = 64;
	/** Walking this far without building starts a fresh building budget (long trips meet many obstacles). */
	private static final double FREE_WALK_SQ = 32.0D * 32.0D;
	/** Dig routes tried per walk before falling back to poking at the obstacle step by step. */
	private static final int MAX_PLANS = 3;

	private int repathCooldown;
	private int stuckTicks;
	private int blocksPlaced;
	/** {@link #blocksPlaced} (with the route under way) when the bot last built something, and where. */
	private int budgetMark;
	@Nullable
	private Vec3 lastBuildPos;
	/** Ticks left to finish a step that a dig-out move started (instead of re-pathing mid-step). */
	private int stepTicks;
	@Nullable
	private Vec3 lastPosition;
	/** A dig route around whatever the bot got stuck on. */
	private final PathFollower follower = new PathFollower();
	private int failedPlans;

	public void reset(ButterBotEntity bot) {
		repathCooldown = 0;
		stuckTicks = 0;
		blocksPlaced = 0;
		budgetMark = 0;
		lastBuildPos = null;
		stepTicks = 0;
		failedPlans = 0;
		follower.clear();
		lastPosition = bot.position();
	}

	public int stuckTicks() {
		return stuckTicks;
	}

	/**
	 * Keeps walking towards {@code target}. With {@code digWhenStuck} the bot cuts through natural
	 * ground, builds stairs, pillars and bridges when it can't go on. Returns {@code false} once the
	 * bot has been stuck for longer than {@code maxStuckTicks}.
	 */
	public boolean walk(ButterBotEntity bot, Vec3 target, double speed, boolean digWhenStuck, int maxStuckTicks) {
		refreshBudget(bot);
		if (follower.isActive()) {
			PathFollower.Status status = follower.tick(bot);
			if (status == PathFollower.Status.RUNNING) {
				if (blocksPlaced + follower.placed() >= MAX_BLOCKS_PLACED) {
					blocksPlaced += follower.placed();
					follower.clear();
					return false;
				}
				stuckTicks = 0;
				lastPosition = bot.position();
				return true;
			}
			// Around the obstacle (or the route stopped fitting): back to normal walking.
			blocksPlaced += follower.placed();
			follower.clear();
			if (status == PathFollower.Status.FAILED) {
				failedPlans++;
			}
			stuckTicks = 0;
			repathCooldown = 0;
			lastPosition = bot.position();
			return true;
		}
		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			miner.tickDig();
			stuckTicks = 0;
			lastPosition = bot.position();
			return true;
		}

		if (stepTicks > 0) {
			// Finishing the step out of a tight spot: keep stepping, the pathfinder would undo it.
			stepTicks--;
			TunnelHelper.StepResult step = TunnelHelper.digStepTowards(bot, BlockPos.containing(target));
			if (step == TunnelHelper.StepResult.BUILDING) {
				blocksPlaced++;
			}
			if (step != TunnelHelper.StepResult.MOVING) {
				stepTicks = 0;
			}
			repathCooldown = 0;
			lastPosition = bot.position();
			return true;
		}

		if (--repathCooldown <= 0) {
			repathCooldown = REPATH_INTERVAL;
			Vec3 goal = waypoint(bot, target);
			bot.getNavigation().moveTo(goal.x, goal.y, goal.z, speed);
		}

		// Pathfinding treats anything within about one block as "arrived", which leaves the bot
		// just short of tight targets such as the next tunnel slice. Step the last bit directly.
		double dx = target.x - bot.getX();
		double dz = target.z - bot.getZ();
		if (dx * dx + dz * dz < 2.25D && bot.getNavigation().isDone()) {
			bot.getMoveControl().setWantedPosition(target.x, target.y, target.z, speed);
		}

		if (lastPosition != null && bot.position().distanceToSqr(lastPosition) < 0.0025D) {
			stuckTicks++;
		} else {
			stuckTicks = Math.max(0, stuckTicks - 2);
		}
		lastPosition = bot.position();

		if (digWhenStuck && stuckTicks >= UNSTICK_AFTER_TICKS && stuckTicks % UNSTICK_INTERVAL == 0) {
			if (blocksPlaced >= MAX_BLOCKS_PLACED) {
				return false;
			}
			if (failedPlans < MAX_PLANS && planAround(bot, target, true)) {
				stuckTicks = 0;
				return true;
			}
			TunnelHelper.StepResult step = TunnelHelper.digStepTowards(bot, BlockPos.containing(target));
			if (step == TunnelHelper.StepResult.BUILDING) {
				blocksPlaced++;
			} else if (step == TunnelHelper.StepResult.MOVING) {
				stepTicks = 10;
			}
		}

		if (!digWhenStuck && stuckTicks >= UNSTICK_AFTER_TICKS && stuckTicks % UNSTICK_INTERVAL == 0
				&& failedPlans < MAX_PLANS && planAround(bot, target, false)) {
			// Not allowed to dig here: a walking route still finds the way round (through a gate, say).
			stuckTicks = 0;
			return true;
		}

		return stuckTicks <= maxStuckTicks;
	}

	/** {@link #walk} to a block that may not be loaded yet (then over the ground towards it). */
	public boolean walkTo(ButterBotEntity bot, BlockPos pos, double speed, boolean digWhenStuck, int maxStuckTicks) {
		return bot.level().isLoaded(pos) ? walk(bot, Vec3.atBottomCenterOf(pos), speed, digWhenStuck, maxStuckTicks)
				: walkFar(bot, pos.getX() + 0.5D, pos.getZ() + 0.5D, speed, digWhenStuck, maxStuckTicks);
	}

	/**
	 * Heads for a far spot that isn't loaded yet (its height unknown) over the ground: towards a point
	 * {@value #WAYPOINT_DISTANCE} blocks along the way, at the ground there once that is loaded (not
	 * at the bot's own height, which would bridge ravines and tunnel through mountains).
	 */
	public boolean walkFar(ButterBotEntity bot, double x, double z, double speed, boolean digWhenStuck, int maxStuckTicks) {
		Vec3 step = waypoint(bot, new Vec3(x, bot.getY(), z));
		Level level = bot.level();
		BlockPos column = BlockPos.containing(step);
		if (!level.dimensionType().hasCeiling() && level.isLoaded(column)) {
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
			if (ground > level.getMinY()) {
				// (Over the void in the End there is no ground: keep its own height.)
				step = new Vec3(step.x, ground, step.z);
			}
		}
		return walk(bot, step, speed, digWhenStuck, maxStuckTicks);
	}

	/** The building budget starts again once the bot has walked a good stretch without building. */
	private void refreshBudget(ButterBotEntity bot) {
		int placed = blocksPlaced + (follower.isActive() ? follower.placed() : 0);
		if (placed != budgetMark) {
			budgetMark = placed;
			lastBuildPos = bot.position();
		} else if (blocksPlaced > 0 && !follower.isActive() && lastBuildPos != null
				&& bot.position().distanceToSqr(lastBuildPos) > FREE_WALK_SQ) {
			blocksPlaced = 0;
			budgetMark = 0;
			lastBuildPos = null;
		}
	}

	/** Plans a route towards the target (or the next waypoint) and starts following it; {@code dig}: may dig and build. */
	private boolean planAround(ButterBotEntity bot, Vec3 target, boolean dig) {
		BlockPos goal = BlockPos.containing(waypoint(bot, target));
		DigPlanner.Goal reach = DigPlanner.reach(goal, 2);
		DigPath path = dig ? DigPlanner.plan(bot, reach, null) : DigPlanner.planWalking(bot, reach);
		if (path == null || path.isEmpty()) {
			failedPlans++;
			return false;
		}
		bot.getNavigation().stop();
		follower.start(path, null);
		return true;
	}

	/** The target itself when it is close, otherwise a point {@value #WAYPOINT_DISTANCE} blocks towards it. */
	private static Vec3 waypoint(ButterBotEntity bot, Vec3 target) {
		Vec3 offset = target.subtract(bot.position());
		double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
		if (horizontal <= FAR_TARGET) {
			return target;
		}
		double part = WAYPOINT_DISTANCE / horizontal;
		return bot.position().add(offset.x * part, offset.y * part, offset.z * part);
	}

	public static double horizontalDistSq(ButterBotEntity bot, BlockPos pos) {
		double dx = bot.getX() - (pos.getX() + 0.5D);
		double dz = bot.getZ() - (pos.getZ() + 0.5D);
		return dx * dx + dz * dz;
	}
}
