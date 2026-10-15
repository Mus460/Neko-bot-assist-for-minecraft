package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.ai.path.DigPath;
import dev.butterbot.ai.path.DigPlanner;
import dev.butterbot.ai.path.PathFollower;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Gets to a scanned ore/log and breaks it, the way a player would: when there is a walkable way the
 * bot simply walks it; otherwise it plans a whole route through the ground ({@link DigPlanner}) and
 * digs along it, so it never paces around looking for a path that doesn't exist. Within reach it
 * digs out whatever hides the block. A block it can't get to is skipped, and the owner is told why.
 */
public class MineTargetAction implements BotAction {

	/** Eye to block centre: about a player's reach. */
	private static final double REACH_SQ = 4.5D * 4.5D;
	/** How close (in blocks, along the grid) the end of a walking path must get to count as reaching. */
	private static final int PATH_REACH = 2;
	/** Without getting closer (or further along the route) for this long, the target is given up. */
	private static final int NO_PROGRESS_TICKS = 20 * 15;
	/** Ticks spent not digging on one target before it is given up anyway. */
	private static final int MAX_TICKS_PER_TARGET = 20 * 90;
	private static final int WALK_STUCK_TICKS = 40;
	/** A route can be planned again this often (the world changed, the bot was pushed...) before giving up. */
	private static final int MAX_REPLANS = 3;

	private enum Mode {WALK, FOLLOW}

	private enum Reason {NO_ROUTE, NO_PROGRESS, CANT_DIG}

	private final PathFollower follower = new PathFollower();
	private Mode mode = Mode.WALK;
	@Nullable
	private BlockPos planned;
	private int targetTicks;
	private double bestDistance;
	private int sinceProgress;
	private int walkStuckTicks;
	private int replans;
	private int lastProgress;
	@Nullable
	private Vec3 lastPosition;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 60;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.MINE && bot.getTargetBlock() != null && !bot.isReturnRequested();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		planned = null;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos target = bot.getTargetBlock();
		if (target == null) {
			return;
		}
		Level level = bot.level();
		if (!bot.isTargetBlock(level.getBlockState(target))) {
			// Already gone (taken with the vein, by the owner, by a creeper...): find the next one.
			finishTarget(bot);
			return;
		}
		if (!target.equals(planned)) {
			newTarget(bot, target);
		}

		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			boolean diggingTarget = target.equals(miner.getDigPos());
			if (miner.tickDig() && diggingTarget) {
				finishTarget(bot);
			}
			return;
		}

		double distance = Math.sqrt(bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)));
		if (distance < bestDistance - 1.0D) {
			bestDistance = distance;
			sinceProgress = 0;
		} else {
			sinceProgress++;
		}
		if (mode == Mode.FOLLOW && follower.progress() != lastProgress) {
			// A route may go around something first: getting along it counts as progress too.
			lastProgress = follower.progress();
			sinceProgress = 0;
		}
		if (++targetTicks > MAX_TICKS_PER_TARGET || sinceProgress > NO_PROGRESS_TICKS) {
			giveUp(bot, target, Reason.NO_PROGRESS);
			return;
		}

		if (distance * distance <= REACH_SQ) {
			BlockPos blocker = blockerOf(bot, target);
			if (blocker == null) {
				bot.getNavigation().stop();
				bot.setBotState(BotState.MINING);
				if (!miner.beginDig(target)) {
					giveUp(bot, target, Reason.CANT_DIG);
					return;
				}
				if (miner.tickDig()) {
					finishTarget(bot);
				}
				return;
			}
			BlockState blockerState = level.getBlockState(blocker);
			if (TunnelHelper.canClear(bot, blocker, blockerState) && bot.makeDigSafe(blocker)) {
				// Something sits between the bot and the block: dig it out like a player would.
				bot.getNavigation().stop();
				bot.setBotState(BotState.MINING);
				miner.beginDig(blocker);
				return;
			}
		}

		bot.setBotState(BotState.MOVING_TO_TARGET);
		if (mode == Mode.WALK) {
			walk(bot, target);
			return;
		}
		PathFollower.Status status = follower.tick(bot);
		if (status == PathFollower.Status.RUNNING) {
			return;
		}
		DigPath route = follower.path();
		if (status == PathFollower.Status.DONE && route != null && route.reachesGoal() && distance * distance <= REACH_SQ) {
			// Arrived next to the block, yet the reach check above found nothing it may dig to get at it.
			giveUp(bot, target, Reason.CANT_DIG);
			return;
		}
		// The end of a partial route (it got closer, so that doesn't count against it), or the plan
		// no longer fits the world: plan the next bit from here.
		boolean partDone = status == PathFollower.Status.DONE && route != null && !route.reachesGoal();
		if (!partDone && ++replans > MAX_REPLANS || !plan(bot, target)) {
			giveUp(bot, target, Reason.NO_ROUTE);
		}
	}

	/** Decides how to get to a new target: walk when the pathfinder finds a way, otherwise plan a dig route. */
	private void newTarget(ButterBotEntity bot, BlockPos target) {
		planned = target;
		targetTicks = 0;
		sinceProgress = 0;
		walkStuckTicks = 0;
		replans = 0;
		lastPosition = bot.position();
		bestDistance = Math.sqrt(bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)));
		bot.getMiner().cancel();
		follower.clear();
		mode = Mode.WALK;
		bot.setRoute(target, follower);
		if (bestDistance * bestDistance <= REACH_SQ) {
			return;
		}
		Path path = bot.getNavigation().createPath(target, PATH_REACH);
		if (path != null && path.canReach()) {
			bot.getNavigation().moveTo(path, 1.0D);
		} else {
			plan(bot, target);
		}
	}

	/** Plans a dig route to {@code target} and starts on it. False if there is none. */
	private boolean plan(ButterBotEntity bot, BlockPos target) {
		bot.getNavigation().stop();
		DigPath path = DigPlanner.plan(bot, DigPlanner.mine(target), target);
		if (path == null || path.isEmpty()) {
			follower.clear();
			return false;
		}
		follower.start(path, target);
		mode = Mode.FOLLOW;
		lastProgress = 0;
		return true;
	}

	private void walk(ButterBotEntity bot, BlockPos target) {
		Vec3 position = bot.position();
		if (lastPosition != null && position.distanceToSqr(lastPosition) < 0.0025D) {
			walkStuckTicks++;
		} else {
			walkStuckTicks = 0;
		}
		lastPosition = position;
		if (bot.getNavigation().isDone() || walkStuckTicks > WALK_STUCK_TICKS) {
			// The walk is over (or went nowhere) and the block is still out of reach: dig the rest of the way.
			walkStuckTicks = 0;
			if (!plan(bot, target)) {
				giveUp(bot, target, Reason.NO_ROUTE);
			}
		}
	}

	/**
	 * Null when the bot can see the target (its middle or any open face), otherwise the block in the
	 * way. Grass, torches and other things without a hitbox don't block.
	 */
	@Nullable
	private static BlockPos blockerOf(ButterBotEntity bot, BlockPos target) {
		Level level = bot.level();
		Vec3 eye = bot.getEyePosition();
		Vec3 middle = Vec3.atCenterOf(target);
		BlockHitResult first = level.clip(new ClipContext(eye, middle, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
		if (first.getType() == HitResult.Type.MISS || first.getBlockPos().equals(target)) {
			return null;
		}
		for (Direction face : Direction.values()) {
			BlockPos side = target.relative(face);
			if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
				continue;
			}
			Vec3 point = middle.add(face.getStepX() * 0.45D, face.getStepY() * 0.45D, face.getStepZ() * 0.45D);
			BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
			if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) {
				return null;
			}
		}
		return first.getBlockPos();
	}

	private void finishTarget(ButterBotEntity bot) {
		bot.getMiner().cancel();
		bot.setTargetBlock(null);
		bot.requestRescan();
		bot.setRoute(null, null);
		follower.clear();
		planned = null;
	}

	/** Skips a block it can't get to (and the rest of its vein, which is just as hard to reach) and says why. */
	private void giveUp(ButterBotEntity bot, BlockPos target, Reason reason) {
		bot.getNavigation().stop();
		Component block = bot.level().getBlockState(target).getBlock().getName();
		bot.reportProblem(Component.translatable("butterbot.problem." + reason.name().toLowerCase(java.util.Locale.ROOT),
				block, target.toShortString()));
		bot.blacklistTarget(target);
		for (BlockPos near : BlockPos.betweenClosed(target.offset(-2, -2, -2), target.offset(2, 2, 2))) {
			if (bot.isTargetBlock(bot.level().getBlockState(near))) {
				bot.blacklistTarget(near.immutable());
			}
		}
		finishTarget(bot);
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTargetBlock() == null;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
		bot.setRoute(null, null);
		follower.clear();
		planned = null;
	}
}
