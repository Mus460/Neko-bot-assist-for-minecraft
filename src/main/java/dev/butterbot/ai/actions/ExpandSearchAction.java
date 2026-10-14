package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * When nothing is in scan range: underground resources make the bot dig a staircase down to the
 * resource's best Y level and then branch-mine; surface resources make it roam.
 */
public class ExpandSearchAction implements BotAction {

	private static final int UNDERGROUND_EFFORT_CAP = 500;
	private static final int SURFACE_EFFORT_CAP = 40;
	private static final int MOVE_INTERVAL_TICKS = 10;
	private static final int POSITION_CHECK_INTERVAL_TICKS = 30;
	private static final double STUCK_MOVEMENT_THRESHOLD_SQ = 0.16D;
	private static final int BRANCH_LENGTH = 24;
	/** Turning this often without getting anywhere means the bot is boxed in: it climbs out. */
	private static final int TURNS_BEFORE_CLIMBING = 8;

	private Direction tunnelDirection = Direction.NORTH;
	private int effort;
	private int branchSteps;
	private boolean gaveUp;
	private int moveCooldown;
	private int positionCheckCooldown;
	private int turnsInPlace;
	@Nullable
	private BlockPos lastFeet;
	@Nullable
	private Vec3 lastPosition;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 20;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.MINE
				&& bot.getResource() != null
				&& bot.getTargetBlock() == null
				&& !bot.isReturnRequested()
				&& !bot.isInventoryFull();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		tunnelDirection = bot.getDirection();
		effort = 0;
		branchSteps = 0;
		gaveUp = false;
		moveCooldown = 0;
		positionCheckCooldown = POSITION_CHECK_INTERVAL_TICKS;
		turnsInPlace = 0;
		lastFeet = bot.blockPosition();
		lastPosition = bot.position();
	}

	@Override
	public void tick(ButterBotEntity bot) {
		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			if (miner.tickDig()) {
				effort++;
				checkEffortCap(bot);
			}
			return;
		}

		BotResource resource = bot.getResource();
		if (resource == null || gaveUp) {
			return;
		}

		if (!bot.blockPosition().equals(lastFeet)) {
			lastFeet = bot.blockPosition();
			turnsInPlace = 0;
		}
		if (turnsInPlace >= TURNS_BEFORE_CLIMBING) {
			// Surrounded by things it can't dig (bedrock, obsidian, a build...): go up a level and try again.
			turnsInPlace = 0;
			effort += 10;
			TunnelHelper.pillarUp(bot);
			checkEffortCap(bot);
			return;
		}

		Level level = bot.level();
		if (resource.isUnderground()) {
			if (bot.getBlockY() > resource.optimalY() + 2 && bot.getBlockY() > level.getMinY() + 6) {
				descendStep(bot, level);
			} else {
				tunnelStep(bot, level);
			}
		} else {
			roamStep(bot);
		}
	}

	private void descendStep(ButterBotEntity bot, Level level) {
		BlockPos feet = bot.blockPosition();
		BlockPos forward = feet.relative(tunnelDirection);
		BlockPos[] cells = {forward.above(), forward, forward.below()};
		if (digFirstObstacle(bot, level, cells)) {
			return;
		}
		BlockPos landing = forward.below();
		if (level.getBlockState(landing.below()).isAir() && level.getBlockState(landing.below(2)).isAir()) {
			turn();
			return;
		}
		moveTo(bot, landing);
		trackStuck(bot);
	}

	private void tunnelStep(ButterBotEntity bot, Level level) {
		BlockPos feet = bot.blockPosition();
		BlockPos forward = feet.relative(tunnelDirection);
		BlockPos[] cells = {forward.above(), forward};
		if (digFirstObstacle(bot, level, cells)) {
			return;
		}
		if (level.getBlockState(forward.below()).isAir() && level.getBlockState(forward.below(2)).isAir()) {
			turn();
			return;
		}
		moveTo(bot, forward);
		if (bot.blockPosition().equals(forward) || Math.abs(bot.getX() - (forward.getX() + 0.5)) + Math.abs(bot.getZ() - (forward.getZ() + 0.5)) < 0.6) {
			if (++branchSteps >= BRANCH_LENGTH) {
				// Branch mining: turn every so often to cover new ground instead of one endless line.
				branchSteps = 0;
				tunnelDirection = bot.getRandom().nextBoolean() ? tunnelDirection.getClockWise() : tunnelDirection.getCounterClockWise();
			}
		}
		trackStuck(bot);
	}

	private void roamStep(ButterBotEntity bot) {
		if (!bot.getNavigation().isDone()) {
			trackStuck(bot);
			return;
		}
		Vec3 destination = DefaultRandomPos.getPos(bot, 20, 7);
		if (destination != null) {
			bot.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0D);
		}
		effort++;
		checkEffortCap(bot);
	}

	private boolean digFirstObstacle(ButterBotEntity bot, Level level, BlockPos[] cells) {
		for (BlockPos cell : cells) {
			BlockState cellState = level.getBlockState(cell);
			if (cellState.isAir() || cellState.getCollisionShape(level, cell).isEmpty()) {
				continue;
			}
			if (!bot.canDig(cell, cellState)) {
				turn();
				return true;
			}
			if (!bot.makeDigSafe(cell)) {
				bot.warnLava();
				turn();
				return true;
			}
			bot.getMiner().beginDig(cell);
			return true;
		}
		return false;
	}

	private void moveTo(ButterBotEntity bot, BlockPos pos) {
		if (--moveCooldown <= 0) {
			moveCooldown = MOVE_INTERVAL_TICKS;
			bot.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D);
		}
	}

	private void trackStuck(ButterBotEntity bot) {
		if (--positionCheckCooldown > 0) {
			return;
		}
		positionCheckCooldown = POSITION_CHECK_INTERVAL_TICKS;
		if (lastPosition != null && bot.position().distanceToSqr(lastPosition) < STUCK_MOVEMENT_THRESHOLD_SQ) {
			turn();
		}
		lastPosition = bot.position();
	}

	private void turn() {
		tunnelDirection = tunnelDirection.getClockWise();
		moveCooldown = 0;
		branchSteps = 0;
		turnsInPlace++;
	}

	private void checkEffortCap(ButterBotEntity bot) {
		BotResource resource = bot.getResource();
		if (resource == null || gaveUp) {
			return;
		}
		int cap = resource.isUnderground() ? UNDERGROUND_EFFORT_CAP : SURFACE_EFFORT_CAP;
		if (effort < cap) {
			return;
		}
		gaveUp = true;
		if (bot.hasLoot()) {
			bot.setReturnRequested(true);
			bot.tellOwner(Component.translatable("butterbot.msg.cant_find_more", resource.displayName()));
		} else {
			bot.tellOwner(Component.translatable("butterbot.msg.found_nothing", resource.displayName()));
			bot.clearTask();
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTargetBlock() != null || bot.getTask() != BotTask.MINE || bot.isReturnRequested();
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
