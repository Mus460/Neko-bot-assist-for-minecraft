package dev.butterbot.ai;

import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Breaks one block at a time with a visible crack animation. It takes as long as it would take a
 * player holding the tool the bot shows (iron, later diamond and netherite), and higher-level bots
 * are faster still.
 */
public final class MiningController {

	private static final int MIN_TICKS = 4;
	private static final int MAX_TICKS = 200;

	private final ButterBotEntity bot;
	@Nullable
	private BlockPos digPos;
	private int miningTicks;
	private int requiredTicks;

	public MiningController(ButterBotEntity bot) {
		this.bot = bot;
	}

	@Nullable
	public BlockPos getDigPos() {
		return digPos;
	}

	public boolean isDigging() {
		return digPos != null;
	}

	public boolean beginDig(BlockPos pos) {
		Level level = bot.level();
		BlockState state = level.getBlockState(pos);
		if (!bot.canDig(pos, state)) {
			return false;
		}
		cancel();
		digPos = pos.immutable();
		miningTicks = 0;
		requiredTicks = Mth.clamp(Mth.ceil(digTicks(level, pos, state)), MIN_TICKS, MAX_TICKS);
		return true;
	}

	/** Vanilla's breaking time for the bot's tool: hardness x 30 / tool speed ticks (x 100 with the wrong tool). */
	private float digTicks(Level level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		ItemStack tool = bot.diggingTool(state);
		float toolSpeed = Math.max(1.0F, tool.getDestroySpeed(state));
		boolean proper = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return hardness * (proper ? 30.0F : 100.0F) / toolSpeed / BotLevels.digSpeed(bot.getBotLevel());
	}

	/** Advances the current dig. Returns true on the tick the block breaks. */
	public boolean tickDig() {
		if (digPos == null) {
			return false;
		}
		Level level = bot.level();
		BlockState state = level.getBlockState(digPos);
		if (!bot.canDig(digPos, state)) {
			cancel();
			return false;
		}

		bot.getLookControl().setLookAt(Vec3.atCenterOf(digPos));
		miningTicks++;

		int stage = Math.min(9, miningTicks * 10 / requiredTicks);
		level.destroyBlockProgress(bot.getId(), digPos, stage);

		if (miningTicks < requiredTicks) {
			return false;
		}

		level.destroyBlockProgress(bot.getId(), digPos, -1);
		BlockPos broken = digPos;
		digPos = null;
		miningTicks = 0;
		bot.breakBlock(broken);
		return true;
	}

	public void cancel() {
		if (digPos != null) {
			bot.level().destroyBlockProgress(bot.getId(), digPos, -1);
			digPos = null;
		}
		miningTicks = 0;
	}
}
