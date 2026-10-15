package dev.butterbot.ai.job;

import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Predicate;

/** Job chests: opening them (both halves of a double chest), counting what is inside and filling them. */
public final class JobStorage {

	/**
	 * The storage at {@code pos}: a chest (the whole double chest), barrel, shulker box or Bot Base.
	 * Loot chests nobody has opened yet don't count. Null if there is none or it isn't loaded.
	 */
	@Nullable
	public static Container open(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return null;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity instanceof BotBaseBlockEntity base) {
			return base;
		}
		if (blockEntity instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		if (blockEntity instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		if (blockEntity instanceof BarrelBlockEntity || blockEntity instanceof ShulkerBoxBlockEntity) {
			return (Container) blockEntity;
		}
		return null;
	}

	public static boolean isStorage(ServerLevel level, BlockPos pos) {
		return open(level, pos) != null;
	}

	/** True if {@code a} and {@code b} are the same storage: the same block, or the two halves of one double chest. */
	public static boolean sameStorage(ServerLevel level, BlockPos a, BlockPos b) {
		if (a.equals(b)) {
			return true;
		}
		BlockState state = level.isLoaded(a) ? level.getBlockState(a) : null;
		return state != null && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
				&& ChestBlock.getConnectedBlockPos(a, state).equals(b);
	}

	public static int count(Container container, Item item) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Puts {@code stack} into {@code container}; returns what didn't fit. */
	public static ItemStack insert(Container container, ItemStack stack) {
		if (container instanceof BotBaseBlockEntity base) {
			return base.insert(stack);
		}
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack existing = container.getItem(i);
			if (!existing.isEmpty() && existing.getCount() < existing.getMaxStackSize()
					&& ItemStack.isSameItemSameComponents(existing, stack)) {
				int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
				existing.grow(move);
				stack.shrink(move);
			}
		}
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
				container.setItem(i, stack.copy());
				stack.setCount(0);
			}
		}
		container.setChanged();
		return stack;
	}

	/**
	 * Moves the backpack items matching {@code which} into {@code container}. Returns how many were
	 * moved; {@code leftOver[0]} is set when some did not fit.
	 */
	public static int deposit(ButterBotEntity bot, Container container, Predicate<ItemStack> which, boolean[] leftOver) {
		SimpleContainer backpack = bot.getInventory();
		int moved = 0;
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			if (stack.isEmpty() || !which.test(stack)) {
				continue;
			}
			int before = stack.getCount();
			ItemStack rest = insert(container, stack.copy());
			moved += before - rest.getCount();
			if (!rest.isEmpty()) {
				leftOver[0] = true;
			}
			backpack.setItem(i, rest);
		}
		return moved;
	}

	/** Moves up to {@code max} items matching {@code which} from {@code container} into the backpack. Returns how many. */
	public static int take(ButterBotEntity bot, Container container, Predicate<ItemStack> which, int max) {
		SimpleContainer backpack = bot.getInventory();
		int moved = 0;
		for (int i = 0; i < container.getContainerSize() && moved < max; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !which.test(stack)) {
				continue;
			}
			int want = Math.min(max - moved, stack.getCount());
			ItemStack rest = backpack.addItem(stack.copyWithCount(want));
			int took = want - rest.getCount();
			if (took <= 0) {
				// The backpack is full.
				break;
			}
			stack.shrink(took);
			container.setItem(i, stack);
			moved += took;
		}
		if (moved > 0) {
			container.setChanged();
		}
		return moved;
	}

	/** Things the bot keeps for itself when it empties its backpack: torches, food and some blocks for plugging lava. */
	public static boolean isSupply(ItemStack stack) {
		return stack.is(Items.TORCH) || ButterBotEntity.isSafeFood(stack) || ButterBotEntity.isFiller(stack);
	}

	/** Lid sound (and animation, for chests) while the bot puts things in. */
	public static void playOpenClose(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
		level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.05F);
	}

	/** Counts each of {@code items} in the container at {@code pos}. False if it can't be opened. */
	public static boolean countInto(ServerLevel level, BlockPos pos, Iterable<Item> items, Map<Item, Integer> into) {
		Container container = open(level, pos);
		if (container == null) {
			return false;
		}
		for (Item item : items) {
			into.put(item, count(container, item));
		}
		return true;
	}

	/** The storage nearest to {@code center} within {@code radius} blocks (empty ones too). */
	@Nullable
	public static BlockPos nearest(ServerLevel level, BlockPos center, int radius) {
		ChunkPos centerChunk = ChunkPos.containing(center);
		int chunkRadius = (radius >> 4) + 1;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
			for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunk.x() + cx, centerChunk.z() + cz);
				if (chunk == null) {
					continue;
				}
				for (BlockPos pos : chunk.getBlockEntities().keySet()) {
					if (Math.abs(pos.getY() - center.getY()) > 10) {
						continue;
					}
					double distance = pos.distSqr(center);
					if (distance > (double) radius * radius || distance >= bestDistance || open(level, pos) == null) {
						continue;
					}
					best = pos.immutable();
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private JobStorage() {
	}
}
