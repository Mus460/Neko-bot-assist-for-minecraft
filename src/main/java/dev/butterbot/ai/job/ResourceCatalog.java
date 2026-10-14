package dev.butterbot.ai.job;

import dev.butterbot.entity.BotJob;
import dev.butterbot.entity.BotResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The items a miner or gatherer can be asked for, with where to find them: which blocks give the
 * item, how deep to dig (miners) and which biomes are worth travelling to (mostly for gatherers).
 */
public final class ResourceCatalog {

	/** An extra check that needs the block's surroundings (for example "not the bottom sugar cane"). */
	@FunctionalInterface
	public interface Placement {
		boolean test(BlockGetter level, BlockPos pos, BlockState state);
	}

	/**
	 * @param behavior  how the existing mining code treats it (underground staircase, tree felling, roaming...)
	 * @param optimalY  the Y level miners dig down to, or null for things found on the surface
	 */
	public record Entry(Item item, BotJob job, BotResource behavior, @Nullable Integer optimalY,
						Predicate<BlockState> blocks, @Nullable Placement placement, Predicate<Holder<Biome>> biomes) {

		public boolean matchesState(BlockState state) {
			return blocks.test(state);
		}

		public boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
			return blocks.test(state) && (placement == null || placement.test(level, pos, state));
		}

		public boolean underground() {
			return optimalY != null;
		}

		public int y() {
			return optimalY == null ? 0 : optimalY;
		}

		public boolean likes(Holder<Biome> biome) {
			return biomes.test(biome);
		}
	}

	private static final Predicate<Holder<Biome>> LAND = ResourceCatalog::isLand;
	private static final Placement ABOVE_ITS_OWN_KIND = (level, pos, state) -> level.getBlockState(pos.below()).is(state.getBlock());
	private static final Placement NATURAL_TREE = ResourceCatalog::isNaturalTree;
	private static final Placement OPEN_GROUND = (level, pos, state) -> level.getBlockState(pos.above()).canBeReplaced();

	private static final List<Entry> ENTRIES = new ArrayList<>();

	static {
		// Miners: ores and stone, dug far below the surface.
		mine(Items.COAL, BotResource.COAL, 45, s -> s.is(BlockItemTags.COAL_ORES.block()), LAND);
		mine(Items.RAW_IRON, BotResource.IRON, 15, s -> s.is(BlockItemTags.IRON_ORES.block()), LAND);
		mine(Items.RAW_COPPER, BotResource.COPPER, 48, s -> s.is(BlockItemTags.COPPER_ORES.block()), LAND);
		mine(Items.RAW_GOLD, BotResource.GOLD, -16, s -> s.is(BlockItemTags.GOLD_ORES.block()) && !s.is(Blocks.NETHER_GOLD_ORE), LAND);
		mine(Items.REDSTONE, BotResource.REDSTONE, -58, s -> s.is(BlockItemTags.REDSTONE_ORES.block()), LAND);
		mine(Items.LAPIS_LAZULI, BotResource.LAPIS, 0, s -> s.is(BlockItemTags.LAPIS_ORES.block()), LAND);
		mine(Items.DIAMOND, BotResource.DIAMOND, -58, s -> s.is(BlockItemTags.DIAMOND_ORES.block()), LAND);
		mine(Items.EMERALD, BotResource.EMERALD, 100, s -> s.is(BlockItemTags.EMERALD_ORES.block()),
				tags(BiomeTags.IS_MOUNTAIN));
		mine(Items.COBBLESTONE, BotResource.STONE, 40, s -> s.is(Blocks.STONE), LAND);
		mine(Items.COBBLED_DEEPSLATE, BotResource.STONE, -20, s -> s.is(Blocks.DEEPSLATE), LAND);
		mine(Items.ANDESITE, BotResource.STONE, 40, s -> s.is(Blocks.ANDESITE), LAND);
		mine(Items.DIORITE, BotResource.STONE, 40, s -> s.is(Blocks.DIORITE), LAND);
		mine(Items.GRANITE, BotResource.STONE, 40, s -> s.is(Blocks.GRANITE), LAND);
		mine(Items.TUFF, BotResource.STONE, -20, s -> s.is(Blocks.TUFF), LAND);
		mine(Items.GRAVEL, BotResource.STONE, 20, s -> s.is(Blocks.GRAVEL), LAND);

		// Gatherers: wood and other things found on the surface, in the biomes they grow in.
		tree(Items.OAK_LOG, Blocks.OAK_LOG, or(tags(BiomeTags.IS_FOREST),
				keys(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SWAMP, Biomes.WOODED_BADLANDS, Biomes.MEADOW)));
		tree(Items.SPRUCE_LOG, Blocks.SPRUCE_LOG, or(tags(BiomeTags.IS_TAIGA), keys(Biomes.GROVE, Biomes.WINDSWEPT_FOREST)));
		tree(Items.BIRCH_LOG, Blocks.BIRCH_LOG, keys(Biomes.BIRCH_FOREST, Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.FOREST));
		tree(Items.JUNGLE_LOG, Blocks.JUNGLE_LOG, tags(BiomeTags.IS_JUNGLE));
		tree(Items.ACACIA_LOG, Blocks.ACACIA_LOG, tags(BiomeTags.IS_SAVANNA));
		tree(Items.DARK_OAK_LOG, Blocks.DARK_OAK_LOG, keys(Biomes.DARK_FOREST));
		tree(Items.MANGROVE_LOG, Blocks.MANGROVE_LOG, keys(Biomes.MANGROVE_SWAMP));
		tree(Items.CHERRY_LOG, Blocks.CHERRY_LOG, keys(Biomes.CHERRY_GROVE));
		tree(Items.PALE_OAK_LOG, Blocks.PALE_OAK_LOG, keys(Biomes.PALE_GARDEN));
		gather(Items.SAND, s -> s.is(Blocks.SAND), null, or(keys(Biomes.DESERT), tags(BiomeTags.IS_BEACH)));
		gather(Items.RED_SAND, s -> s.is(Blocks.RED_SAND), null, tags(BiomeTags.IS_BADLANDS));
		gather(Items.DIRT, s -> s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK), OPEN_GROUND,
				keys(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW, Biomes.FOREST));
		gather(Items.SUGAR_CANE, s -> s.is(Blocks.SUGAR_CANE), ABOVE_ITS_OWN_KIND,
				keys(Biomes.SWAMP, Biomes.DESERT, Biomes.BEACH, Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE));
		gather(Items.CACTUS, s -> s.is(Blocks.CACTUS), ABOVE_ITS_OWN_KIND, or(keys(Biomes.DESERT), tags(BiomeTags.IS_BADLANDS)));
		gather(Items.PUMPKIN, s -> s.is(Blocks.PUMPKIN), null,
				or(keys(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW), tags(BiomeTags.IS_TAIGA)));
		gather(Items.MELON_SLICE, s -> s.is(Blocks.MELON), null, tags(BiomeTags.IS_JUNGLE));
		gather(Items.BAMBOO, s -> s.is(Blocks.BAMBOO), ABOVE_ITS_OWN_KIND, keys(Biomes.BAMBOO_JUNGLE, Biomes.JUNGLE));
		gather(Items.SNOWBALL, s -> s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW), null,
				keys(Biomes.SNOWY_PLAINS, Biomes.SNOWY_TAIGA, Biomes.SNOWY_SLOPES, Biomes.GROVE, Biomes.ICE_SPIKES,
						Biomes.SNOWY_BEACH, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS));
		gather(Items.WHEAT_SEEDS, s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS), null,
				keys(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW, Biomes.SAVANNA));
	}

	private static void mine(Item item, BotResource behavior, int y, Predicate<BlockState> blocks, Predicate<Holder<Biome>> biomes) {
		ENTRIES.add(new Entry(item, BotJob.MINER, behavior, y, blocks, null, biomes));
	}

	private static void tree(Item item, Block log, Predicate<Holder<Biome>> biomes) {
		ENTRIES.add(new Entry(item, BotJob.GATHERER, BotResource.WOOD, null, s -> s.is(log), NATURAL_TREE, biomes));
	}

	private static void gather(Item item, Predicate<BlockState> blocks, @Nullable Placement placement, Predicate<Holder<Biome>> biomes) {
		ENTRIES.add(new Entry(item, BotJob.GATHERER, BotResource.SAND, null, blocks, placement, biomes));
	}

	/** Everything {@code job} can be asked for, in a fixed order (for the item picker). */
	public static List<Entry> forJob(BotJob job) {
		List<Entry> list = new ArrayList<>();
		for (Entry entry : ENTRIES) {
			if (entry.job() == job) {
				list.add(entry);
			}
		}
		return list;
	}

	@Nullable
	public static Entry get(Item item) {
		for (Entry entry : ENTRIES) {
			if (entry.item() == item) {
				return entry;
			}
		}
		return null;
	}

	public static boolean canGet(BotJob job, Item item) {
		Entry entry = get(item);
		return entry != null && entry.job() == job;
	}

	/** Not sea or river: somewhere a bot can stand and dig. */
	public static boolean isLand(Holder<Biome> biome) {
		return !biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_DEEP_OCEAN) && !biome.is(BiomeTags.IS_RIVER);
	}

	/** A log that belongs to a real tree (natural leaves at the top), so houses made of logs are left alone. */
	public static boolean isNaturalTree(BlockGetter level, BlockPos pos, BlockState state) {
		BlockPos.MutableBlockPos cursor = pos.mutable();
		int climbed = 0;
		while (climbed < 40 && level.getBlockState(cursor.above()).is(state.getBlock())) {
			cursor.move(0, 1, 0);
			climbed++;
		}
		BlockPos top = cursor.immutable();
		for (BlockPos near : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
			BlockState nearState = level.getBlockState(near);
			if (nearState.is(BlockTags.LEAVES) && nearState.hasProperty(LeavesBlock.PERSISTENT)
					&& !nearState.getValue(LeavesBlock.PERSISTENT)) {
				return true;
			}
		}
		return false;
	}

	@SafeVarargs
	private static Predicate<Holder<Biome>> tags(TagKey<Biome>... tags) {
		return biome -> {
			for (TagKey<Biome> tag : tags) {
				if (biome.is(tag)) {
					return true;
				}
			}
			return false;
		};
	}

	@SafeVarargs
	private static Predicate<Holder<Biome>> keys(ResourceKey<Biome>... keys) {
		return biome -> {
			for (ResourceKey<Biome> key : keys) {
				if (biome.is(key)) {
					return true;
				}
			}
			return false;
		};
	}

	private static Predicate<Holder<Biome>> or(Predicate<Holder<Biome>> first, Predicate<Holder<Biome>> second) {
		return first.or(second);
	}

	private ResourceCatalog() {
	}
}
