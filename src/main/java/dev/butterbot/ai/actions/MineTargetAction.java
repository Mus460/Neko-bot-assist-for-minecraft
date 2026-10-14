package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Gets to a scanned ore/log and breaks it, the way a player would: when there is a walkable way the
 * bot walks it, otherwise it digs straight towards the block (stairs down, stairs up, a tunnel
 * across) instead of pacing around looking for a path that doesn't exist. Within reach it digs out
 * whatever hides the block. A target it can't get any closer to is given up quickly.
 */
public class MineTargetAction implements BotAction {

	/** Eye to block centre: about a player's reach. */
	private static final double REACH_SQ = 4.5D * 4.5D;
	/** How close (in blocks, along the grid) the end of a path must get for the walk to count as reaching. */
	private static final int PATH_REACH = 2;
	/** A partial path is only walked when it ends at least this much closer to the target. */
	private static final double PARTIAL_GAIN = 3.0D;
	/** Without getting at least a block closer for this long, the target is given up. */
	private static final int NO_PROGRESS_TICKS = 20 * 15;
	/** Ticks spent not digging on one target before it is given up anyway. */
	private static final int MAX_TICKS_PER_TARGET = 20 * 60;
	private static final int WALK_STUCK_TICKS = 40;
	private static final int MAX_BLOCKED_STEPS = 4;

	private enum Mode {WALK, DIG}

	private Mode mode = Mode.DIG;
	@Nullable
	private BlockPos planned;
	private boolean walkedPartial;
	private int targetTicks;
	private double bestDistance;
	private int sinceProgress;
	private int stepCooldown;
	private int blockedSteps;
	private int walkStuckTicks;
	@Nullable
	private Vec3 lastPosition;
	/** The block the bot is stepping into while digging its way; the next step is planned once it is there. */
	@Nullable
	private BlockPos pendingStep;
	private int pendingTicks;
	private final BlockPos.MutableBlockPos stepOut = new BlockPos.MutableBlockPos();

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
		if (++targetTicks > MAX_TICKS_PER_TARGET || sinceProgress > NO_PROGRESS_TICKS) {
			giveUp(bot, target);
			return;
		}

		if (distance * distance <= REACH_SQ) {
			BlockPos blocker = blockerOf(bot, target);
			if (blocker == null) {
				bot.getNavigation().stop();
				bot.setBotState(BotState.MINING);
				if (!miner.beginDig(target)) {
					giveUp(bot, target);
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
			walk(bot);
		} else {
			dig(bot, target);
		}
	}

	/** Decides how to get to a new target: walk when the pathfinder finds a way, otherwise dig there. */
	private void newTarget(ButterBotEntity bot, BlockPos target) {
		planned = target;
		walkedPartial = false;
		targetTicks = 0;
		sinceProgress = 0;
		stepCooldown = 0;
		blockedSteps = 0;
		walkStuckTicks = 0;
		pendingStep = null;
		lastPosition = bot.position();
		bestDistance = Math.sqrt(bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)));
		bot.getMiner().cancel();
		mode = Mode.DIG;
		if (bestDistance * bestDistance <= REACH_SQ) {
			return;
		}
		Path path = bot.getNavigation().createPath(target, PATH_REACH);
		if (path == null) {
			return;
		}
		if (path.canReach()) {
			mode = Mode.WALK;
		} else {
			// Part of the way is walkable: walk it once if it really gets closer, then dig the rest.
			Node end = path.getEndNode();
			double endDistance = end == null ? Double.MAX_VALUE : Math.sqrt(end.asBlockPos().distSqr(target));
			double fromHere = Math.sqrt(bot.blockPosition().distSqr(target));
			if (endDistance <= fromHere - PARTIAL_GAIN) {
				mode = Mode.WALK;
				walkedPartial = true;
			}
		}
		if (mode == Mode.WALK) {
			bot.getNavigation().moveTo(path, 1.0D);
		}
	}

	private void walk(ButterBotEntity bot) {
		Vec3 position = bot.position();
		if (lastPosition != null && position.distanceToSqr(lastPosition) < 0.0025D) {
			walkStuckTicks++;
		} else {
			walkStuckTicks = 0;
		}
		lastPosition = position;
		if (bot.getNavigation().isDone() || walkStuckTicks > WALK_STUCK_TICKS) {
			// The walk is over (or went nowhere) and the block is still out of reach: dig the rest of the way.
			bot.getNavigation().stop();
			mode = Mode.DIG;
			stepCooldown = 0;
		}
	}

	private void dig(ButterBotEntity bot, BlockPos target) {
		if (--stepCooldown > 0) {
			return;
		}
		if (!bot.onGround() && !bot.isInWater() && !bot.isInLava()) {
			// Still dropping into the hole just dug: land first, or the next step is planned from mid-air
			// and digs out the wrong blocks.
			return;
		}
		if (pendingStep != null) {
			if (TunnelHelper.isStandingIn(bot, pendingStep) || ++pendingTicks > 20) {
				pendingStep = null;
			} else {
				TunnelHelper.stepInto(bot, pendingStep);
				return;
			}
		}
		switch (TunnelHelper.digStepTowards(bot, target, stepOut)) {
			case DIGGING -> stepCooldown = 0;
			case BUILDING -> stepCooldown = 6;
			case MOVING -> {
				// Walk into the spot just dug and land there before planning the next step.
				pendingStep = stepOut.immutable();
				pendingTicks = 0;
			}
			case BLOCKED -> {
				if (++blockedSteps >= MAX_BLOCKED_STEPS) {
					giveUp(bot, target);
					return;
				}
				stepCooldown = 10;
				if (!walkedPartial) {
					// Bedrock, a build or a lava lake in the way: try walking around it once.
					walkedPartial = true;
					Path path = bot.getNavigation().createPath(target, PATH_REACH);
					if (path != null && path.getNodeCount() > 1) {
						bot.getNavigation().moveTo(path, 1.0D);
						mode = Mode.WALK;
						walkStuckTicks = 0;
					}
				}
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
		planned = null;
	}

	private void giveUp(ButterBotEntity bot, BlockPos target) {
		bot.getNavigation().stop();
		bot.blacklistTarget(target);
		// The rest of the same vein is just as hard to get to.
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
		planned = null;
	}
}
