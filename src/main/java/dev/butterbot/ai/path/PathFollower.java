package dev.butterbot.ai.path;

import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Walks a {@link DigPath} one step at a time: digs out what is in the way, puts down a block when
 * the step needs one, then steps into the next block and waits until the bot has landed there.
 * When the world no longer matches the plan (a block it may not dig, the floor gone, the bot pushed
 * off the route, a step taking far too long) it stops with {@link Status#FAILED} so the caller can
 * plan again.
 */
public final class PathFollower {

	public enum Status {RUNNING, DONE, FAILED}

	/** Ticks of trying one step (digging not counted) before the plan is given up. */
	private static final int STEP_TIMEOUT = 60;

	@Nullable
	private DigPath path;
	@Nullable
	private BlockPos allowedTarget;
	private int index;
	private int stepTicks;
	/** Blocks put down for this route (bridges and pillars). */
	private int placed;

	public void start(DigPath path, @Nullable BlockPos target) {
		this.path = path;
		this.allowedTarget = target;
		this.index = 0;
		this.stepTicks = 0;
		this.placed = 0;
	}

	public void clear() {
		path = null;
		allowedTarget = null;
		index = 0;
		stepTicks = 0;
		placed = 0;
	}

	/** Blocks put down since the route started. */
	public int placed() {
		return placed;
	}

	public boolean isActive() {
		return path != null;
	}

	@Nullable
	public DigPath path() {
		return path;
	}

	/** How many steps are done (goes up as the bot gets along). */
	public int progress() {
		return index;
	}

	public Status tick(ButterBotEntity bot) {
		DigPath current = path;
		if (current == null) {
			return Status.FAILED;
		}
		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			miner.tickDig();
			return Status.RUNNING;
		}
		if (!bot.onGround() && !bot.isInWater() && !bot.isInLava()) {
			// Dropping or jumping: wait until it lands. Off a ledge it would drift past the
			// column it should land in, so once over that column it stops moving sideways.
			if (index < current.size()) {
				DigPath.Step falling = current.steps().get(index);
				BlockPos at = bot.blockPosition();
				if (falling.move() == DigPath.Move.DROP && at.getX() == falling.feet().getX() && at.getZ() == falling.feet().getZ()) {
					Vec3 motion = bot.getDeltaMovement();
					bot.setDeltaMovement(0.0D, motion.y, 0.0D);
				}
			}
			return Status.RUNNING;
		}
		while (index < current.size() && isAt(bot, current.steps().get(index).feet())) {
			index++;
			stepTicks = 0;
		}
		if (index >= current.size()) {
			return Status.DONE;
		}
		DigPath.Step step = current.steps().get(index);
		BlockPos from = index == 0 ? current.start() : current.steps().get(index - 1).feet();
		if (!isNear(bot, from) && !isNear(bot, step.feet())) {
			// Pushed, fell somewhere else, or the ground moved: plan again from here. (Landing a
			// block past a drop is fine: it walks back into the step.)
			return Status.FAILED;
		}
		if (++stepTicks > STEP_TIMEOUT) {
			return Status.FAILED;
		}

		Level level = bot.level();
		for (BlockPos cell : step.dig()) {
			BlockState state = level.getBlockState(cell);
			if (TunnelHelper.isGateway(state)) {
				if (TunnelHelper.isClosedGateway(state)) {
					// Open it like a player would (it is closed again once the bot is through).
					if (!bot.openGate(cell)) {
						return Status.FAILED;
					}
					stepTicks = 0;
					return Status.RUNNING;
				}
				continue;
			}
			if (state.getCollisionShape(level, cell).isEmpty() && !TunnelHelper.isTrap(state)) {
				continue;
			}
			boolean allowed = cell.equals(allowedTarget) ? bot.canDig(cell, state) : TunnelHelper.canClear(bot, cell, state);
			if (!allowed || !bot.makeDigSafe(cell)) {
				return Status.FAILED;
			}
			bot.getNavigation().stop();
			if (!miner.beginDig(cell)) {
				return Status.FAILED;
			}
			stepTicks = 0;
			return Status.RUNNING;
		}

		switch (step.move()) {
			case PILLAR -> {
				// The block goes where the feet are: a torch (the bot lights its tunnels) or a
				// flower there is picked up first, or the bot would climb two blocks at once.
				BlockPos base = feetOf(bot);
				BlockState baseState = level.getBlockState(base);
				if (!baseState.canBeReplaced()) {
					if (!baseState.getCollisionShape(level, base).isEmpty() || !TunnelHelper.isPickable(baseState) || !miner.beginDig(base)) {
						return Status.FAILED;
					}
					stepTicks = 0;
					return Status.RUNNING;
				}
				TunnelHelper.StepResult result = TunnelHelper.pillarUp(bot);
				if (result == TunnelHelper.StepResult.BUILDING) {
					placed++;
				}
				return result == TunnelHelper.StepResult.BLOCKED ? Status.FAILED : Status.RUNNING;
			}
			case BRIDGE -> {
				BlockPos place = step.place();
				if (place != null && level.getBlockState(place).getCollisionShape(level, place).isEmpty()) {
					if (!bot.placeFillerAt(place)) {
						return Status.FAILED;
					}
					placed++;
					return Status.RUNNING;
				}
			}
			case WALK, STEP_UP -> {
				BlockPos floor = step.feet().below();
				if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) {
					// The ground the plan counted on is gone.
					return Status.FAILED;
				}
			}
			default -> {
			}
		}
		TunnelHelper.stepInto(bot, step.feet());
		return Status.RUNNING;
	}

	/**
	 * The block the bot's feet are in. On a floor lower than a full block (soul sand, mud, farmland,
	 * a path, a bottom slab) the bot stands inside that block's cell, so the cell above it counts.
	 */
	public static BlockPos feetOf(ButterBotEntity bot) {
		BlockPos pos = bot.blockPosition();
		Level level = bot.level();
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
		if (!shape.isEmpty()) {
			// Carpets and thin snow are stood in, not on.
			double top = shape.max(Direction.Axis.Y);
			if (top >= 0.5D && bot.getY() - pos.getY() >= top - 1.0E-3D) {
				return pos.above();
			}
		}
		return pos;
	}

	/** Standing in {@code feet} (on the ground there, or swimming in it). */
	private static boolean isAt(ButterBotEntity bot, BlockPos feet) {
		return feetOf(bot).equals(feet);
	}

	/** Still about where the last step ended (moving into the next one counts). */
	private static boolean isNear(ButterBotEntity bot, BlockPos from) {
		BlockPos at = feetOf(bot);
		return Math.abs(at.getX() - from.getX()) <= 1 && Math.abs(at.getZ() - from.getZ()) <= 1
				&& Math.abs(at.getY() - from.getY()) <= 1;
	}
}
