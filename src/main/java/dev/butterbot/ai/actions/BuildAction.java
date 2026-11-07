package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Blueprint;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Builds a design the AI made ({@link Blueprint}): walks around clearing and placing it block by block. It replaces
 * empty spots and natural terrain (stone, dirt, sand, gravel, leaves, snow...) so it can level a hill or dig a
 * basement; logs, planks and anything else built are left alone, so nothing of the owner's is ever broken. Blocks come from the
 * backpack when it has them; what is missing it makes itself, like the torches it lights areas with.
 */
public class BuildAction implements BotAction {

	private static final double REACH_SQ = 5.5D * 5.5D;
	private static final int TICKS_PER_BLOCK = 2;
	private static final int MAX_WAITS = 20;

	private final Walker walker = new Walker();
	private int cooldown;
	private int waits;
	private int walkTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.BUILD && !bot.getBuildPlan().isEmpty();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		walker.reset(bot);
		waits = 0;
		walkTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel level)) {
			return;
		}
		List<Blueprint.Placement> plan = bot.getBuildPlan();
		if (plan.isEmpty()) {
			finish(bot);
			return;
		}
		if (--cooldown > 0) {
			return;
		}
		Blueprint.Placement next = plan.getFirst();
		BlockPos pos = next.pos();
		if (!level.isLoaded(pos) || !canPlace(level, pos, next.state())) {
			plan.removeFirst();
			return;
		}
		double distanceSq = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
		// Unreachable spots (inside a wall it just built, up on a roof) are placed from where it got to.
		if (distanceSq > REACH_SQ && walkTicks < 100) {
			walkTicks++;
			if (!walker.walk(bot, Vec3.atBottomCenterOf(pos), 1.15D, false, 100)) {
				walkTicks = 100;
			}
			return;
		}
		if (!next.state().getCollisionShape(level, pos).isEmpty()
				&& !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos)).isEmpty() && waits < MAX_WAITS) {
			// Someone is standing there: try the rest first.
			waits++;
			plan.removeFirst();
			plan.addLast(next);
			return;
		}
		plan.removeFirst();
		waits = 0;
		walkTicks = 0;
		walker.reset(bot);
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		place(bot, level, pos, next.state());
		cooldown = TICKS_PER_BLOCK;
	}

	private static void place(ButterBotEntity bot, ServerLevel level, BlockPos pos, BlockState state) {
		clear(bot, level, pos);
		if (state.isAir()) {
			return;
		}
		take(bot, state);
		// Fences, panes, walls and stairs join up with what is already around them.
		state = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, pos);
		level.setBlock(pos, state, 3);
		// Two-block things (doors, tall flowers) get their top half, beds their head.
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
				&& canPlace(level, pos.above(), state)) {
			clear(bot, level, pos.above());
			level.setBlock(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
			Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
			BlockPos head = pos.relative(facing);
			if (canPlace(level, head, state)) {
				clear(bot, level, head);
				level.setBlock(head, state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), 3);
			}
		}
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
		Item item = state.getBlock().asItem();
		bot.showTool(new ItemStack(item == Items.AIR ? Items.BRICKS : item), 6);
	}

	/**
	 * Empties a spot. Natural terrain just goes (carving a mountain would otherwise flood the place with items);
	 * anything built is broken and drops itself and what it held, so nothing of the owner's is lost.
	 */
	private static void clear(ButterBotEntity bot, ServerLevel level, BlockPos pos) {
		BlockState existing = level.getBlockState(pos);
		if (existing.isAir()) {
			return;
		}
		if (isNatural(existing)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		} else {
			level.destroyBlock(pos, true, bot);
		}
	}

	/** Uses the block from the backpack if it has one; otherwise makes it itself. */
	private static void take(ButterBotEntity bot, BlockState state) {
		Item item = state.getBlock().asItem();
		if (item != Items.AIR && bot.takeItem(item, 1) > 0) {
			bot.countBuildBlock(true);
		} else {
			bot.countBuildBlock(false);
		}
	}

	/** Anywhere the owner asked for, except where the same block already is or the block can't be broken (bedrock). */
	private static boolean canPlace(ServerLevel level, BlockPos pos, BlockState state) {
		BlockState existing = level.getBlockState(pos);
		return !existing.equals(state) && existing.getDestroySpeed(level, pos) >= 0.0F;
	}

	/** Terrain the world made, as opposed to something built. */
	public static boolean isNatural(BlockState state) {
		return state.isAir() || state.canBeReplaced() || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
				|| state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE)
				|| state.is(BlockTags.ORES) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.FARMLAND)
				|| state.is(Blocks.DIRT_PATH) || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.CALCITE) || state.is(Blocks.MOSS_BLOCK)
				|| state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.OBSIDIAN);
	}

	/** How many built (not natural) blocks the plan would break, for the owner to agree to first. */
	public static int countBreaks(ServerLevel level, List<Blueprint.Placement> plan) {
		int breaks = 0;
		for (Blueprint.Placement placement : plan) {
			BlockState existing = level.getBlockState(placement.pos());
			if (!isNatural(existing) && !existing.equals(placement.state())) {
				breaks++;
			}
		}
		return breaks;
	}

	private static void finish(ButterBotEntity bot) {
		bot.tellOwner(Component.translatable("butterbot.build.done", bot.getBuildName(), bot.getBuildFromBag() + bot.getBuildMade(),
				bot.getBuildFromBag()));
		bot.gainWorkXp(Math.max(1, (bot.getBuildFromBag() + bot.getBuildMade()) / 4));
		bot.playTaskCompleteSound();
		bot.clearTask();
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.BUILD;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
