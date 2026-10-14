package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
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
	/** A single walk never builds more than this (no endless bridges across the ocean). */
	private static final int MAX_BLOCKS_PLACED = 64;

	private int repathCooldown;
	private int stuckTicks;
	private int blocksPlaced;
	@Nullable
	private Vec3 lastPosition;

	public void reset(ButterBotEntity bot) {
		repathCooldown = 0;
		stuckTicks = 0;
		blocksPlaced = 0;
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
		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			miner.tickDig();
			stuckTicks = 0;
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
			if (TunnelHelper.digStepTowards(bot, BlockPos.containing(target)) == TunnelHelper.StepResult.BUILDING) {
				blocksPlaced++;
			}
		}

		return stuckTicks <= maxStuckTicks;
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
