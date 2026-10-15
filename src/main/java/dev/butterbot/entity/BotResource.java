package dev.butterbot.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Things a Butter Bot can be told to gather. Underground resources have an "optimal" Y level the bot
 * digs down to before it starts tunnelling; surface resources are found by roaming.
 */
public enum BotResource {
	DIAMOND(-58,
			state -> state.is(BlockItemTags.DIAMOND_ORES.block()),
			stack -> stack.is(Items.DIAMOND)),
	IRON(15,
			state -> state.is(BlockItemTags.IRON_ORES.block()),
			stack -> stack.is(Items.RAW_IRON) || stack.is(Items.IRON_INGOT)),
	GOLD(-16,
			state -> state.is(BlockItemTags.GOLD_ORES.block()),
			stack -> stack.is(Items.RAW_GOLD) || stack.is(Items.GOLD_INGOT) || stack.is(Items.GOLD_NUGGET)),
	COPPER(48,
			state -> state.is(BlockItemTags.COPPER_ORES.block()),
			stack -> stack.is(Items.RAW_COPPER) || stack.is(Items.COPPER_INGOT)),
	COAL(45,
			state -> state.is(BlockItemTags.COAL_ORES.block()),
			stack -> stack.is(Items.COAL)),
	REDSTONE(-58,
			state -> state.is(BlockItemTags.REDSTONE_ORES.block()),
			stack -> stack.is(Items.REDSTONE)),
	LAPIS(0,
			state -> state.is(BlockItemTags.LAPIS_ORES.block()),
			stack -> stack.is(Items.LAPIS_LAZULI)),
	EMERALD(100,
			state -> state.is(BlockItemTags.EMERALD_ORES.block()),
			stack -> stack.is(Items.EMERALD)),
	// Natural stone only: cobblestone is what the bot builds with (and what houses are made of), so
	// counting it would have the bot dig up its own steps, lava plugs and the owner's walls.
	STONE(40,
			state -> state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE),
			stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)),
	WOOD(null,
			state -> state.is(BlockTags.LOGS),
			stack -> stack.is(ItemTags.LOGS)),
	SAND(null,
			state -> state.is(BlockTags.SAND),
			stack -> stack.is(ItemTags.SAND)),
	// Ancient debris, only in the Nether and most common around Y 15. Needs a diamond pickaxe.
	NETHERITE(15, true,
			state -> state.is(Blocks.ANCIENT_DEBRIS),
			stack -> stack.is(Items.ANCIENT_DEBRIS) || stack.is(Items.NETHERITE_SCRAP) || stack.is(Items.NETHERITE_INGOT));

	@Nullable
	private final Integer optimalY;
	private final boolean nether;
	private final Predicate<BlockState> blockPredicate;
	private final Predicate<ItemStack> itemPredicate;

	BotResource(@Nullable Integer optimalY, Predicate<BlockState> blockPredicate, Predicate<ItemStack> itemPredicate) {
		this(optimalY, false, blockPredicate, itemPredicate);
	}

	BotResource(@Nullable Integer optimalY, boolean nether, Predicate<BlockState> blockPredicate, Predicate<ItemStack> itemPredicate) {
		this.optimalY = optimalY;
		this.nether = nether;
		this.blockPredicate = blockPredicate;
		this.itemPredicate = itemPredicate;
	}

	/** Only found in the Nether. */
	public boolean isNether() {
		return nether;
	}

	/**
	 * True if it can be dug in {@code dimension}: Nether finds only in the Nether, the overworld's ores
	 * and stone only in the overworld, gold in both (the Nether has gold ore too). Wood and sand anywhere.
	 */
	public boolean foundIn(ResourceKey<Level> dimension) {
		if (nether) {
			return dimension.equals(Level.NETHER);
		}
		if (!isUnderground()) {
			return true;
		}
		return dimension.equals(Level.OVERWORLD) || (this == GOLD && dimension.equals(Level.NETHER));
	}

	/** The Y level to dig at in {@code dimension} (Nether gold ore is everywhere above Y 10, not deep down). */
	public int optimalY(ResourceKey<Level> dimension) {
		return this == GOLD && dimension.equals(Level.NETHER) ? 15 : optimalY();
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Component displayName() {
		return Component.translatable("butterbot.resource." + id());
	}

	public boolean isUnderground() {
		return optimalY != null;
	}

	public int optimalY() {
		return optimalY == null ? 0 : optimalY;
	}

	public boolean matchesBlock(BlockState state) {
		return blockPredicate.test(state);
	}

	public boolean matchesItem(ItemStack stack) {
		return itemPredicate.test(stack);
	}

	@Nullable
	public static BotResource byId(String id) {
		for (BotResource resource : values()) {
			if (resource.id().equalsIgnoreCase(id)) {
				return resource;
			}
		}
		return null;
	}
}
