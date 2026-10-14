package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fells a whole tree from one broken log, clears its leaves (keeping saplings, sticks and apples)
 * and plants a sapling where the trunk stood. Only real trees are felled: the logs must touch
 * natural leaves, so log houses and other builds are left alone.
 */
public final class TreeFeller {

	private static final int MAX_LOGS = 160;
	private static final int MAX_LEAVES = 400;
	private static final int HORIZONTAL_REACH = 7;
	private static final int LEAF_REACH = 4;

	/** Fells the rest of the tree whose log at {@code origin} was just broken. Returns the logs taken. */
	public static int fell(ButterBotEntity bot, ServerLevel level, BlockPos origin, BlockState logState) {
		Block logBlock = logState.getBlock();
		List<BlockPos> logs = findLogs(level, origin, logBlock);
		Set<BlockPos> trunk = new HashSet<>(logs);
		trunk.add(origin);
		if (!touchesNaturalLeaves(level, trunk)) {
			return 0;
		}

		List<BlockPos> roots = new ArrayList<>();
		if (isRoot(level, origin)) {
			roots.add(origin);
		}
		for (BlockPos log : logs) {
			if (bot.isJobZoneBlocked(log)) {
				continue;
			}
			if (isRoot(level, log)) {
				roots.add(log);
			}
			bot.harvestBlock(level, log, level.getBlockState(log), true);
			bot.gainWorkXp(1);
		}
		clearLeaves(bot, level, trunk);
		replant(bot, level, roots, logBlock);
		return logs.size();
	}

	/** True if {@code pos} is the bottom log of a natural tree standing on soil. */
	public static boolean isTreeBase(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)) {
			return false;
		}
		Set<BlockPos> trunk = new HashSet<>(findLogs(level, pos, state.getBlock()));
		trunk.add(pos);
		return trunk.size() >= 3 && touchesNaturalLeaves(level, trunk);
	}

	private static List<BlockPos> findLogs(ServerLevel level, BlockPos origin, Block logBlock) {
		List<BlockPos> logs = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(origin);
		seen.add(origin);
		while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
			BlockPos current = queue.poll();
			for (BlockPos cursor : BlockPos.betweenClosed(current.offset(-1, -1, -1), current.offset(1, 1, 1))) {
				BlockPos next = cursor.immutable();
				if (!seen.add(next)) {
					continue;
				}
				if (Math.abs(next.getX() - origin.getX()) > HORIZONTAL_REACH || Math.abs(next.getZ() - origin.getZ()) > HORIZONTAL_REACH
						|| next.getY() < origin.getY() - 12 || next.getY() > origin.getY() + 32 || !level.isLoaded(next)) {
					continue;
				}
				if (level.getBlockState(next).is(logBlock)) {
					logs.add(next);
					queue.add(next);
				}
			}
		}
		return logs;
	}

	private static boolean isNaturalLeaf(BlockState state) {
		return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
	}

	private static boolean touchesNaturalLeaves(ServerLevel level, Set<BlockPos> logs) {
		for (BlockPos log : logs) {
			for (BlockPos cursor : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				if (isNaturalLeaf(level.getBlockState(cursor))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isRoot(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos.below()).is(BlockTags.DIRT);
	}

	private static void clearLeaves(ButterBotEntity bot, ServerLevel level, Set<BlockPos> logs) {
		Set<BlockPos> seen = new HashSet<>(logs);
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(logs);
		ArrayDeque<Integer> depth = new ArrayDeque<>();
		for (int i = 0; i < logs.size(); i++) {
			depth.add(0);
		}
		int cleared = 0;
		while (!queue.isEmpty() && cleared < MAX_LEAVES) {
			BlockPos current = queue.poll();
			int currentDepth = depth.poll();
			if (currentDepth >= LEAF_REACH) {
				continue;
			}
			for (BlockPos cursor : BlockPos.betweenClosed(current.offset(-1, -1, -1), current.offset(1, 1, 1))) {
				BlockPos next = cursor.immutable();
				if (!seen.add(next) || !level.isLoaded(next)) {
					continue;
				}
				BlockState state = level.getBlockState(next);
				if (!isNaturalLeaf(state)) {
					continue;
				}
				bot.harvestBlock(level, next, state, true);
				cleared++;
				queue.add(next);
				depth.add(currentDepth + 1);
			}
		}
	}

	private static void replant(ButterBotEntity bot, ServerLevel level, List<BlockPos> roots, Block logBlock) {
		if (roots.isEmpty()) {
			return;
		}
		Item preferred = saplingFor(logBlock);
		for (BlockPos root : roots) {
			if (!level.getBlockState(root).isAir()) {
				continue;
			}
			if (preferred != null && bot.countItem(preferred) > 0 && bot.placeBlockFromInventory(root, preferred)) {
				continue;
			}
			for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
				ItemStack stack = bot.getInventory().getItem(i);
				if (stack.is(ItemTags.SAPLINGS) && bot.placeBlockFromInventory(root, stack.getItem())) {
					break;
				}
			}
		}
	}

	/** oak_log -> oak_sapling, mangrove_log -> mangrove_propagule, and so on. */
	private static Item saplingFor(Block logBlock) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(logBlock);
		String path = id.getPath().replace("stripped_", "");
		String sapling = path.startsWith("mangrove") ? "mangrove_propagule" : path.replace("_log", "_sapling").replace("_stem", "_fungus");
		Identifier saplingId = Identifier.tryBuild(id.getNamespace(), sapling);
		return saplingId == null ? null : BuiltInRegistries.ITEM.getOptional(saplingId).orElse(null);
	}

	private TreeFeller() {
	}
}
