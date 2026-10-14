package dev.butterbot.ai;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VegetationBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tidies a group of containers: merges partial stacks, groups items by kind (ores, tools, food, wood,
 * farming, mob drops, redstone, natural blocks, other blocks, misc) and gives each kind its own
 * container where there is room. Nothing is ever deleted: what does not fit goes back to the inbox.
 */
public final class ItemSorter {

	public static final int ORES = 0;
	public static final int TOOLS = 1;
	public static final int FOOD = 2;
	public static final int WOOD = 3;
	public static final int FARMING = 4;
	public static final int MOB_DROPS = 5;
	public static final int REDSTONE = 6;
	public static final int NATURAL = 7;
	public static final int BLOCKS = 8;
	public static final int MISC = 9;

	public record Result(int stacks, List<ItemStack> overflow) {
	}

	/**
	 * Sorts the contents of {@code containers} (in the given order) plus {@code inbox}, whose items
	 * are moved into the containers. Returns how many stacks were placed and anything that did not fit
	 * anywhere (only possible if the containers changed size meanwhile).
	 */
	public static Result sort(List<Container> containers, Container inbox) {
		List<ItemStack> items = new ArrayList<>();
		for (Container container : containers) {
			drain(container, items);
		}
		if (inbox != null) {
			drain(inbox, items);
		}
		List<ItemStack> merged = merge(items);
		merged.sort(Comparator.comparingInt(ItemSorter::category)
				.thenComparing(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
				.thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));

		int totalSlots = 0;
		for (Container container : containers) {
			totalSlots += container.getContainerSize();
		}
		int slack = totalSlots - merged.size();
		int containerIndex = 0;
		int slot = 0;
		int previousCategory = -1;
		int placed = 0;
		List<ItemStack> rest = new ArrayList<>();
		for (ItemStack stack : merged) {
			int category = category(stack);
			if (previousCategory >= 0 && category != previousCategory && slot > 0 && containerIndex < containers.size()) {
				// A new kind starts in a fresh container when there is enough spare room for it.
				int remaining = containers.get(containerIndex).getContainerSize() - slot;
				if (remaining <= slack) {
					slack -= remaining;
					containerIndex++;
					slot = 0;
				}
			}
			previousCategory = category;
			if (containerIndex >= containers.size()) {
				rest.add(stack);
				continue;
			}
			Container container = containers.get(containerIndex);
			container.setItem(slot, stack);
			placed++;
			if (++slot >= container.getContainerSize()) {
				containerIndex++;
				slot = 0;
			}
		}

		List<ItemStack> overflow = new ArrayList<>();
		for (ItemStack stack : rest) {
			ItemStack leftover = inbox == null ? stack : addTo(inbox, stack);
			if (!leftover.isEmpty()) {
				overflow.add(leftover);
			}
		}
		for (Container container : containers) {
			container.setChanged();
		}
		if (inbox != null) {
			inbox.setChanged();
		}
		return new Result(placed, overflow);
	}

	private static void drain(Container container, List<ItemStack> into) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.removeItemNoUpdate(i);
			if (!stack.isEmpty()) {
				into.add(stack);
			}
		}
	}

	private static List<ItemStack> merge(List<ItemStack> items) {
		List<ItemStack> merged = new ArrayList<>();
		for (ItemStack stack : items) {
			for (ItemStack existing : merged) {
				if (stack.isEmpty()) {
					break;
				}
				if (existing.getCount() < existing.getMaxStackSize() && ItemStack.isSameItemSameComponents(existing, stack)) {
					int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
					existing.grow(move);
					stack.shrink(move);
				}
			}
			if (!stack.isEmpty()) {
				merged.add(stack);
			}
		}
		return merged;
	}

	private static ItemStack addTo(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack existing = container.getItem(i);
			if (existing.isEmpty()) {
				container.setItem(i, stack);
				return ItemStack.EMPTY;
			}
			if (existing.getCount() < existing.getMaxStackSize() && ItemStack.isSameItemSameComponents(existing, stack)) {
				int move = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
				existing.grow(move);
				stack.shrink(move);
			}
		}
		return stack;
	}

	/** The kind of an item, used for grouping. */
	public static int category(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (stack.has(DataComponents.MAX_DAMAGE) || stack.has(DataComponents.EQUIPPABLE) || stack.has(DataComponents.WEAPON)
				|| stack.has(DataComponents.TOOL) || stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW)) {
			return TOOLS;
		}
		if (path.startsWith("raw_") || path.endsWith("_ingot") || path.endsWith("_nugget") || path.endsWith("_ore")
				|| path.equals("diamond") || path.equals("emerald") || path.equals("lapis_lazuli") || path.equals("redstone")
				|| path.equals("coal") || path.equals("charcoal") || path.equals("quartz") || path.equals("amethyst_shard")
				|| path.equals("netherite_scrap") || path.equals("ancient_debris") || path.equals("flint")
				|| path.endsWith("_block") && (path.startsWith("iron") || path.startsWith("gold") || path.startsWith("diamond")
				|| path.startsWith("emerald") || path.startsWith("lapis") || path.startsWith("redstone") || path.startsWith("coal")
				|| path.startsWith("raw_") || path.startsWith("netherite") || path.startsWith("copper"))) {
			return ORES;
		}
		if (stack.has(DataComponents.FOOD)) {
			return FOOD;
		}
		if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.SAPLINGS) || stack.is(ItemTags.LEAVES)
				|| stack.is(ItemTags.WOODEN_SLABS) || stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.WOODEN_FENCES)
				|| stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOODEN_TRAPDOORS) || stack.is(Items.STICK)) {
			return WOOD;
		}
		if (path.endsWith("_seeds") || stack.is(Items.WHEAT) || stack.is(Items.SUGAR_CANE) || stack.is(Items.PUMPKIN)
				|| stack.is(Items.MELON) || stack.is(Items.CACTUS) || stack.is(Items.BAMBOO) || stack.is(Items.NETHER_WART)
				|| stack.is(Items.COCOA_BEANS) || stack.is(Items.BONE_MEAL) || stack.is(Items.EGG) || stack.is(Items.KELP)
				|| stack.is(Items.SUGAR) || isPlant(stack)) {
			return FARMING;
		}
		if (stack.is(Items.ROTTEN_FLESH) || stack.is(Items.BONE) || stack.is(Items.STRING) || stack.is(Items.SPIDER_EYE)
				|| stack.is(Items.GUNPOWDER) || stack.is(Items.ENDER_PEARL) || stack.is(Items.SLIME_BALL) || stack.is(Items.LEATHER)
				|| stack.is(Items.FEATHER) || stack.is(Items.BLAZE_ROD) || stack.is(Items.GHAST_TEAR) || stack.is(Items.PHANTOM_MEMBRANE)
				|| stack.is(Items.INK_SAC) || stack.is(Items.GLOW_INK_SAC) || stack.is(Items.MAGMA_CREAM) || stack.is(Items.RABBIT_HIDE)
				|| stack.is(Items.PRISMARINE_SHARD) || stack.is(Items.PRISMARINE_CRYSTALS) || stack.is(Items.SHULKER_SHELL)) {
			return MOB_DROPS;
		}
		if (path.contains("redstone") || path.contains("piston") || path.contains("repeater") || path.contains("comparator")
				|| path.contains("observer") || path.contains("hopper") || path.contains("dispenser") || path.contains("dropper")
				|| path.contains("lever") || path.endsWith("_button") || path.endsWith("_pressure_plate") || path.contains("rail")
				|| path.equals("tnt") || path.contains("daylight_detector") || path.equals("tripwire_hook") || path.equals("target")
				|| path.equals("note_block") || path.contains("sculk_sensor") || path.equals("crafter")) {
			return REDSTONE;
		}
		if (stack.getItem() instanceof BlockItem) {
			if (path.contains("cobble") || path.equals("stone") || path.contains("dirt") || path.equals("gravel") || path.endsWith("sand")
					|| path.contains("deepslate") || path.equals("netherrack") || path.equals("tuff") || path.equals("granite")
					|| path.equals("diorite") || path.equals("andesite") || path.equals("calcite") || path.equals("basalt")
					|| path.equals("blackstone") || path.equals("clay") || path.equals("mud") || path.equals("soul_sand")
					|| path.equals("soul_soil") || path.equals("end_stone") || path.equals("grass_block") || path.equals("moss_block")) {
				return NATURAL;
			}
			return BLOCKS;
		}
		return MISC;
	}

	private static boolean isPlant(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		return block instanceof VegetationBlock;
	}

	private ItemSorter() {
	}
}
