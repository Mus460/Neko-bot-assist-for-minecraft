package dev.butterbot.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The bot's gear: armor it wears (in the vanilla armor slots, so it shows and protects like a
 * player's) and the tools it works with. An empty or worn-out slot means it uses its own tool.
 */
public enum GearSlot {
	HEAD(EquipmentSlot.HEAD, null, "container/slot/helmet"),
	CHEST(EquipmentSlot.CHEST, null, "container/slot/chestplate"),
	LEGS(EquipmentSlot.LEGS, null, "container/slot/leggings"),
	FEET(EquipmentSlot.FEET, null, "container/slot/boots"),
	SWORD(null, ItemTags.SWORDS, "container/slot/sword"),
	PICKAXE(null, ItemTags.PICKAXES, "container/slot/pickaxe"),
	AXE(null, ItemTags.AXES, "container/slot/axe"),
	SHOVEL(null, ItemTags.SHOVELS, "container/slot/shovel"),
	HOE(null, ItemTags.HOES, "container/slot/hoe");

	public static final int COUNT = values().length;
	public static final List<GearSlot> TOOLS = List.of(SWORD, PICKAXE, AXE, SHOVEL, HOE);
	/** Enchanted tools are put aside with this many uses left, so they never break. */
	public static final int RESERVE = 10;

	@Nullable
	private final EquipmentSlot armorSlot;
	@Nullable
	private final TagKey<Item> tools;
	private final Identifier emptyIcon;

	GearSlot(@Nullable EquipmentSlot armorSlot, @Nullable TagKey<Item> tools, String emptyIcon) {
		this.armorSlot = armorSlot;
		this.tools = tools;
		this.emptyIcon = Identifier.withDefaultNamespace(emptyIcon);
	}

	public boolean isArmor() {
		return armorSlot != null;
	}

	/** The vanilla armor slot (armor only). */
	public EquipmentSlot armorSlot() {
		if (armorSlot == null) {
			throw new IllegalStateException(this + " is not an armor slot");
		}
		return armorSlot;
	}

	/** Position among {@link #TOOLS} (tools only). */
	public int toolIndex() {
		return ordinal() - TOOLS.getFirst().ordinal();
	}

	public Identifier emptyIcon() {
		return emptyIcon;
	}

	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	public boolean accepts(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (armorSlot != null) {
			// Armor only (it shows on the bot): no pumpkins or heads.
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			return equippable != null && equippable.slot() == armorSlot && equippable.assetId().isPresent();
		}
		return stack.is(tools);
	}

	/** The tool slot for digging {@code state}, or null for blocks no tool is made for. */
	@Nullable
	public static GearSlot forBlock(BlockState state) {
		if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
			return AXE;
		}
		if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
			return SHOVEL;
		}
		if (state.is(BlockTags.MINEABLE_WITH_HOE) || state.is(BlockTags.CROPS)) {
			return HOE;
		}
		if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
			return PICKAXE;
		}
		return null;
	}

	/** An enchanted tool this close to breaking waits (unused) until it is repaired. */
	public static boolean isResting(ItemStack stack) {
		return stack.isEnchanted() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= RESERVE;
	}
}
