package dev.butterbot.ai.path;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A planned route: the steps a bot takes from where it stands, each ending with its feet in one
 * block. A step may first need blocks dug out of the way or a block placed to stand on.
 *
 * @param reachesGoal false for the best partial route when the goal itself was out of reach
 */
public record DigPath(BlockPos start, List<Step> steps, boolean reachesGoal) {

	public enum Move {
		/** Walk to the next block at the same height. */
		WALK,
		/** Step up one block (onto a block, or after digging room). */
		STEP_UP,
		/** Dig a stair step and go one block down. */
		STEP_DOWN,
		/** Walk off an edge and drop up to a few blocks. */
		DROP,
		/** Put a block in a gap and walk over it. */
		BRIDGE,
		/** Dig the block under the feet and drop into it. */
		DIG_DOWN,
		/** Jump and put a block under the feet. */
		PILLAR,
		/** Swim along the top of deep water. */
		SWIM
	}

	/**
	 * @param feet  where the bot's feet are after this step
	 * @param dig   blocks to clear first, in order (any that are already open are skipped)
	 * @param place a block to put down first (a bridge), or null
	 */
	public record Step(BlockPos feet, Move move, List<BlockPos> dig, @Nullable BlockPos place) {
	}

	public boolean isEmpty() {
		return steps.isEmpty();
	}

	public int size() {
		return steps.size();
	}
}
