package dev.butterbot.ai;

import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Breaks one block at a time with a visible crack animation; higher-level bots dig faster. */
public final class MiningController {

	private static final float TICKS_PER_HARDNESS = 10.0F;

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
		float hardness = state.getDestroySpeed(level, pos);
		int ticks = (int) (hardness * TICKS_PER_HARDNESS / BotLevels.digSpeed(bot.getBotLevel()));
		requiredTicks = Mth.clamp(ticks, 4, 200);
		return true;
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
