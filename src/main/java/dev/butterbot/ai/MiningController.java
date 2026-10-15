package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Breaks one block at a time with a visible crack animation. It takes as long as it would take a
 * player holding the tool the bot shows (iron, later diamond and netherite, or a better one the owner
 * gave it), and higher-level bots are faster still.
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

	/** How many ticks {@code bot} needs to break the block at {@code pos} (for planning routes). */
	public static int estimateTicks(ButterBotEntity bot, BlockPos pos, BlockState state) {
		return Mth.clamp(Mth.ceil(digTicks(bot, bot.level(), pos, state)), MIN_TICKS, MAX_TICKS);
	}

	private float digTicks(Level level, BlockPos pos, BlockState state) {
		return digTicks(bot, level, pos, state);
	}

	/**
	 * Vanilla's breaking time for the bot's tool (its own or a faster one it was given, Efficiency
	 * included): hardness x 30 / tool speed ticks (x 100 when no tool gets the drops).
	 */
	private static float digTicks(ButterBotEntity bot, Level level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		boolean proper = bot.canHarvest(state);
		return hardness * (proper ? 30.0F : 100.0F) / bot.toolSpeed(state) / bot.stats().digSpeed();
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
