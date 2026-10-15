package dev.butterbot.ai;

import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Gets a stuck bot moving again, one step at a time: it cuts through cobwebs and natural terrain in
 * the way, builds stairs or a pillar to climb out of holes, pits and caves (also out of water and
 * lava), bridges gaps, and digs down when the target is below. Player builds such as planks, glass,
 * fences, bricks or doors are never dug through.
 */
public final class TunnelHelper {

	public enum StepResult {
		DIGGING,
		BUILDING,
		MOVING,
		BLOCKED
	}

	public static StepResult digStepTowards(ButterBotEntity bot, BlockPos target) {
		return digStepTowards(bot, target, null);
	}

	/** {@link #digStepTowards(ButterBotEntity, BlockPos)}; on {@link StepResult#MOVING} {@code stepOut} is set to the block stepped into. */
	public static StepResult digStepTowards(ButterBotEntity bot, BlockPos target, @Nullable BlockPos.MutableBlockPos stepOut) {
		Level level = bot.level();
		BlockPos feet = bot.blockPosition();
		int dx = target.getX() - feet.getX();
		int dy = target.getY() - feet.getY();
		int dz = target.getZ() - feet.getZ();

		// Cobwebs and powder snow hold the bot in place: cut them first.
		for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
			if (isTrap(level.getBlockState(cell))) {
				return dig(bot, cell);
			}
		}

		boolean closeBy = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
		if (dy < 0 && closeBy) {
			BlockPos below = feet.below();
			BlockState belowState = level.getBlockState(below);
			boolean shallowDrop = !level.getBlockState(feet.below(2)).isAir() || !level.getBlockState(feet.below(3)).isAir();
			if (!isPassable(level, below) && shallowDrop) {
				return canClear(bot, below, belowState) ? dig(bot, below) : StepResult.BLOCKED;
			}
		}
		if (dy >= 2 && closeBy) {
			return pillarUp(bot);
		}

		Direction direction = (dx == 0 && dz == 0) ? bot.getDirection() : Direction.getApproximateNearest(dx, 0, dz);
		BlockPos forward = feet.relative(direction);
		BlockPos[] cells = dy >= 1
				? new BlockPos[]{feet.above(2), forward.above(2), forward.above()}
				: dy <= -2
				? new BlockPos[]{forward.above(), forward, forward.below()}
				: new BlockPos[]{forward.above(), forward};

		for (BlockPos cell : cells) {
			BlockState cellState = level.getBlockState(cell);
			if (isPassable(level, cell) && !isTrap(cellState)) {
				continue;
			}
			if (canClear(bot, cell, cellState)) {
				return dig(bot, cell);
			}
			// Can't (or may not) dig through: when the target is above, climbing straight up may still work.
			return dy >= 1 ? pillarUp(bot) : StepResult.BLOCKED;
		}

		if (dy >= 1) {
			// Climbing: put a step in front if there is nothing to stand on, then step up onto it.
			if (isPassable(level, forward)) {
				return bot.placeFillerAt(forward) ? StepResult.BUILDING : pillarUp(bot);
			}
			moveTo(bot, forward.above(), stepOut);
			return StepResult.MOVING;
		}
		if (dy > -2 && isPassable(level, forward.below()) && isPassable(level, forward.below(2))) {
			// A gap or a deep drop at our level: bridge it.
			return bot.placeFillerAt(forward.below()) ? StepResult.BUILDING : StepResult.BLOCKED;
		}
		moveTo(bot, forward, stepOut);
		return StepResult.MOVING;
	}

	/**
	 * Climbs one block straight up: jumps and puts a block under its feet, like a player pillaring.
	 * Works while standing on the ground or swimming in water or lava.
	 */
	public static StepResult pillarUp(ButterBotEntity bot) {
		if (bot.isPillaring()) {
			// Mid-jump: the block goes in as soon as the bot is high enough.
			return StepResult.MOVING;
		}
		Level level = bot.level();
		BlockPos feet = bot.blockPosition();
		BlockState feetState = level.getBlockState(feet);
		if (!feetState.canBeReplaced()) {
			VoxelShape shape = feetState.getCollisionShape(level, feet);
			if (shape.isEmpty()) {
				// A torch or flower at its feet: picked up first, the block goes there.
				return bot.getMiner().beginDig(feet) ? StepResult.DIGGING : StepResult.BLOCKED;
			}
			if (shape.max(Direction.Axis.Y) < 0.75D) {
				// On a slab, carpet or bed a jump doesn't get high enough to put a block on top of it.
				return canClear(bot, feet, feetState) ? dig(bot, feet) : StepResult.BLOCKED;
			}
		}
		BlockPos base = feetState.canBeReplaced() ? feet : feet.above();
		for (BlockPos cell : new BlockPos[]{base.above(), base.above(2)}) {
			BlockState cellState = level.getBlockState(cell);
			if (isPassable(level, cell) && !isTrap(cellState)) {
				continue;
			}
			return canClear(bot, cell, cellState) ? dig(bot, cell) : StepResult.BLOCKED;
		}
		if (!bot.onGround() && !bot.isInWater() && !bot.isInLava()) {
			return StepResult.MOVING;
		}
		if (!level.getBlockState(base).canBeReplaced()) {
			return StepResult.BLOCKED;
		}
		return bot.startPillarJump(base) ? StepResult.BUILDING : StepResult.MOVING;
	}

	/** A fence gate or wooden door, which the bot opens to walk through. */
	public static boolean isGateway(BlockState state) {
		return state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
				|| (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && state.is(BlockTags.WOODEN_DOORS));
	}

	/** A closed fence gate or wooden door. */
	public static boolean isClosedGateway(BlockState state) {
		return isGateway(state) && !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
	}

	/** Natural ground the bot may dig through when it needs a way out (never player builds). */
	public static boolean isNaturalTerrain(BlockState state) {
		if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
				|| state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.SNOW) || state.is(BlockTags.LEAVES)
				|| state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
				|| state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.MOSSY_COBBLESTONE)
				|| state.is(Blocks.END_STONE) || state.is(Blocks.CALCITE) || state.is(Blocks.DRIPSTONE_BLOCK)
				|| state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SOUL_SAND)
				|| state.is(Blocks.SOUL_SOIL) || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.NETHER_GOLD_ORE)
				|| state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || isTrap(state)
				|| state.is(BlockTags.NYLIUM) || state.is(Blocks.NETHER_WART_BLOCK) || state.is(Blocks.WARPED_WART_BLOCK)
				|| state.is(Blocks.GLOWSTONE) || state.is(Blocks.SHROOMLIGHT)) {
			return true;
		}
		for (BotResource resource : BotResource.values()) {
			if (resource.isUnderground() && resource != BotResource.STONE && resource.matchesBlock(state)) {
				return true;
			}
		}
		return false;
	}

	/** Blocks that trap whoever walks into them. */
	public static boolean isTrap(BlockState state) {
		return state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW);
	}

	private static boolean isPassable(Level level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	/** True if the bot may dig through this block to get somewhere (natural ground it is allowed to dig). */
	public static boolean canClear(ButterBotEntity bot, BlockPos pos, BlockState state) {
		return isNaturalTerrain(state) && bot.canDig(pos, state);
	}

	private static StepResult dig(ButterBotEntity bot, BlockPos pos) {
		if (!bot.makeDigSafe(pos)) {
			return StepResult.BLOCKED;
		}
		bot.getNavigation().stop();
		return bot.getMiner().beginDig(pos) ? StepResult.DIGGING : StepResult.BLOCKED;
	}

	/**
	 * True once the bot really stands in the column of {@code step}: on the floor there, not just
	 * over the edge (or dropped lower on the way down), so the next step is planned from the right spot.
	 */
	public static boolean isStandingIn(ButterBotEntity bot, BlockPos step) {
		BlockPos feet = bot.blockPosition();
		if (feet.getX() != step.getX() || feet.getZ() != step.getZ()) {
			return false;
		}
		if (bot.isInWater() || bot.isInLava()) {
			return true;
		}
		BlockPos floor = feet.below();
		return bot.onGround() && !bot.level().getBlockState(floor).getCollisionShape(bot.level(), floor).isEmpty();
	}

	/** Keeps walking straight into {@code pos} (call every tick until there). */
	public static void stepInto(ButterBotEntity bot, BlockPos pos) {
		moveTo(bot, pos, null);
	}

	/**
	 * Steps straight into a neighbouring block (one tick of walking; call again to keep going). The
	 * pathfinder counts a neighbouring block as already reached and wouldn't move the bot at all.
	 */
	private static void moveTo(ButterBotEntity bot, BlockPos pos, @Nullable BlockPos.MutableBlockPos stepOut) {
		if (stepOut != null) {
			stepOut.set(pos);
		}
		bot.getNavigation().stop();
		bot.getMoveControl().setWantedPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D);
	}

	private TunnelHelper() {
	}
}
