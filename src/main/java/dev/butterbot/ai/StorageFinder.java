package dev.butterbot.ai;

import dev.butterbot.block.BotBaseBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Finds the player's storage: the Bot Base plus chests, barrels and shulker boxes around it (and
 * around the player). Loot chests nobody has opened yet are left alone.
 */
public final class StorageFinder {

	public static final int RADIUS = 16;
	private static final int VERTICAL = 8;

	/** A container holding {@code count} matching items. */
	public record Found(BlockPos pos, Container container, int count) {
	}

	public static List<Found> find(ServerLevel level, Collection<BlockPos> centers, Predicate<ItemStack> match) {
		Map<BlockPos, Found> found = new LinkedHashMap<>();
		for (BlockPos center : centers) {
			ChunkPos centerChunk = ChunkPos.containing(center);
			int chunkRadius = (RADIUS >> 4) + 1;
			for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
				for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunk.x() + cx, centerChunk.z() + cz);
					if (chunk == null) {
						continue;
					}
					for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
						BlockPos pos = entry.getKey();
						if (found.containsKey(pos) || Math.abs(pos.getX() - center.getX()) > RADIUS
								|| Math.abs(pos.getZ() - center.getZ()) > RADIUS || Math.abs(pos.getY() - center.getY()) > VERTICAL) {
							continue;
						}
						Container container = asStorage(entry.getValue());
						if (container == null) {
							continue;
						}
						int count = count(container, match);
						if (count > 0) {
							found.put(pos.immutable(), new Found(pos.immutable(), container, count));
						}
					}
				}
			}
		}
		return new ArrayList<>(found.values());
	}

	@Nullable
	public static Container containerAt(ServerLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity == null ? null : asStorage(blockEntity);
	}

	@Nullable
	private static Container asStorage(BlockEntity blockEntity) {
		if (blockEntity instanceof BotBaseBlockEntity base) {
			return base;
		}
		if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity
				|| blockEntity instanceof ShulkerBoxBlockEntity) {
			RandomizableContainerBlockEntity container = (RandomizableContainerBlockEntity) blockEntity;
			return container.getLootTable() == null ? container : null;
		}
		return null;
	}

	public static int count(Container container, Predicate<ItemStack> match) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty() && match.test(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Moves up to {@code amount} matching items out of {@code container} into {@code into}. Returns how many moved. */
	public static int take(Container container, Predicate<ItemStack> match, int amount, List<ItemStack> into) {
		int taken = 0;
		for (int i = 0; i < container.getContainerSize() && taken < amount; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !match.test(stack)) {
				continue;
			}
			ItemStack part = stack.split(Math.min(amount - taken, stack.getCount()));
			container.setItem(i, stack);
			taken += part.getCount();
			into.add(part);
		}
		if (taken > 0) {
			container.setChanged();
		}
		return taken;
	}

	private StorageFinder() {
	}
}
