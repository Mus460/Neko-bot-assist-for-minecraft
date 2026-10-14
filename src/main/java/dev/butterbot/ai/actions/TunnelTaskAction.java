package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Digs a straight tunnel slice by slice, placing torches and plugging any lava it meets. */
public class TunnelTaskAction implements BotAction {

	public static final int TORCH_INTERVAL = 6;
	private static final double START_REACH_SQ = 3.0D * 3.0D;
	private static final int MAX_STUCK_TICKS = 300;

	private final Walker walker = new Walker();

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.TUNNEL && bot.getTunnelDirection() != null && bot.getTunnelOrigin() != null
				&& !bot.isReturnRequested();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		walker.reset(bot);
	}

	@Override
	public void tick(ButterBotEntity bot) {
		MiningController miner = bot.getMiner();
		if (miner.isDigging()) {
			miner.tickDig();
			return;
		}

		Direction direction = bot.getTunnelDirection();
		BlockPos origin = bot.getTunnelOrigin();
		if (direction == null || origin == null) {
			return;
		}

		int progress = bot.getTunnelProgress();
		if (progress >= bot.getTunnelLength()) {
			bot.tellOwner(Component.translatable("butterbot.msg.tunnel_done", bot.getTunnelLength()));
			if (bot.hasLoot()) {
				bot.setReturnRequested(true);
			} else {
				bot.playTaskCompleteSound();
				bot.clearTask();
			}
			return;
		}

		Level level = bot.level();
		if (progress == 0 && Walker.horizontalDistSq(bot, origin) > START_REACH_SQ) {
			bot.setBotState(BotState.MOVING_TO_TARGET);
			if (!walker.walk(bot, Vec3.atBottomCenterOf(origin), 1.0D, true, MAX_STUCK_TICKS)) {
				bot.tellOwner(Component.translatable("butterbot.msg.tunnel_unreachable", origin.toShortString()));
				bot.clearTask();
			}
			return;
		}

		bot.setBotState(BotState.MINING);
		BlockPos sliceBase = origin.relative(direction, progress);

		for (int w = 0; w < bot.getTunnelWidth(); w++) {
			BlockPos column = sliceBase.relative(direction.getClockWise(), w);
			for (int h = bot.getTunnelHeight() - 1; h >= 0; h--) {
				BlockPos cell = column.above(h);
				BlockState cellState = level.getBlockState(cell);
				if (cellState.isAir() || cellState.is(Blocks.TORCH) || cellState.is(Blocks.WALL_TORCH)) {
					continue;
				}
				if (cellState.getFluidState().is(FluidTags.LAVA)) {
					// Lava inside the tunnel: plug it first, then dig the plug out like any other block.
					if (!bot.plugWithFiller(cell)) {
						bot.warnLava();
						bot.tellOwner(Component.translatable("butterbot.msg.tunnel_lava", cell.toShortString(), progress,
								bot.getTunnelLength()));
						bot.clearTask();
					}
					return;
				}
				if (!bot.canDig(cell, cellState)) {
					continue;
				}
				if (!bot.makeDigSafe(cell)) {
					bot.warnLava();
					bot.tellOwner(Component.translatable("butterbot.msg.tunnel_lava", cell.toShortString(), progress,
							bot.getTunnelLength()));
					bot.clearTask();
					return;
				}
				bot.getNavigation().stop();
				miner.beginDig(cell);
				return;
			}
		}

		BlockPos walkTarget = sliceBase.relative(direction.getClockWise(), (bot.getTunnelWidth() - 1) / 2);
		if (Walker.horizontalDistSq(bot, walkTarget) <= 1.0D && Math.abs(bot.getY() - walkTarget.getY()) <= 1.5D) {
			if (bot.placesTunnelTorches() && (progress + 1) % TORCH_INTERVAL == 0) {
				placeTorch(level, sliceBase);
			}
			bot.setTunnelProgress(progress + 1);
			walker.reset(bot);
			return;
		}

		if (!walker.walk(bot, Vec3.atBottomCenterOf(walkTarget), 1.0D, false, MAX_STUCK_TICKS)) {
			bot.tellOwner(Component.translatable("butterbot.msg.tunnel_stuck", progress, bot.getTunnelLength()));
			bot.clearTask();
		}
	}

	private static void placeTorch(Level level, BlockPos floorCell) {
		BlockPos below = floorCell.below();
		if (level.getBlockState(floorCell).isAir() && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
			level.setBlock(floorCell, Blocks.TORCH.defaultBlockState(), 3);
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.TUNNEL || bot.isReturnRequested();
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
