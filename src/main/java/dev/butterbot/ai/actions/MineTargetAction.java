package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Walks to a scanned ore/log and breaks it, digging a way through if the path is blocked. */
public class MineTargetAction implements BotAction {

	private static final double REACH_DISTANCE_SQ = 4.0D * 4.0D;
	private static final int REPATH_INTERVAL_TICKS = 20;
	private static final int POSITION_CHECK_INTERVAL_TICKS = 20;
	private static final double STUCK_MOVEMENT_THRESHOLD_SQ = 0.16D;
	private static final int MAX_TICKS_PER_TARGET = 20 * 90;

	private int repathCooldown;
	private int positionCheckCooldown;
	private int targetTicks;
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
		repathCooldown = 0;
		positionCheckCooldown = POSITION_CHECK_INTERVAL_TICKS;
		targetTicks = 0;
		lastPosition = bot.position();
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos target = bot.getTargetBlock();
		if (target == null) {
			return;
		}

		BlockState targetState = bot.level().getBlockState(target);
		if (!bot.isTargetBlock(targetState)) {
			bot.getMiner().cancel();
			bot.setTargetBlock(null);
			bot.requestRescan();
			return;
		}

		if (++targetTicks > MAX_TICKS_PER_TARGET) {
			giveUpOnTarget(bot, target);
			return;
		}

		MiningController miner = bot.getMiner();
		double distanceSq = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target));

		if (distanceSq <= REACH_DISTANCE_SQ && bot.hasLineOfSight(target)) {
			bot.getNavigation().stop();
			bot.setBotState(BotState.MINING);
			if (!target.equals(miner.getDigPos()) && !miner.beginDig(target)) {
				giveUpOnTarget(bot, target);
				return;
			}
			if (miner.tickDig()) {
				bot.setTargetBlock(null);
				bot.requestRescan();
			}
			return;
		}

		bot.setBotState(BotState.MOVING_TO_TARGET);

		if (miner.isDigging()) {
			miner.tickDig();
			return;
		}

		if (--repathCooldown <= 0) {
			repathCooldown = REPATH_INTERVAL_TICKS;
			bot.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
		}

		if (--positionCheckCooldown <= 0) {
			positionCheckCooldown = POSITION_CHECK_INTERVAL_TICKS;
			boolean stuck = lastPosition != null && bot.position().distanceToSqr(lastPosition) < STUCK_MOVEMENT_THRESHOLD_SQ;
			lastPosition = bot.position();
			if (stuck && TunnelHelper.digStepTowards(bot, target) == TunnelHelper.StepResult.BLOCKED) {
				giveUpOnTarget(bot, target);
			}
		}
	}

	private void giveUpOnTarget(ButterBotEntity bot, BlockPos target) {
		bot.getMiner().cancel();
		bot.blacklistTarget(target);
		bot.setTargetBlock(null);
		bot.requestRescan();
		targetTicks = 0;
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTargetBlock() == null;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
