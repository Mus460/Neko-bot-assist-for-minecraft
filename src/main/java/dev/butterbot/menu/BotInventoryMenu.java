package dev.butterbot.menu;

import dev.butterbot.entity.GearSlot;
import dev.butterbot.registry.ModMenus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A bot's gear row (armor, then sword, pickaxe, axe, shovel and hoe) above its backpack, above the
 * player's inventory. Shift-clicking a tool or armor piece from the player's inventory puts it on
 * the bot first.
 */
public class BotInventoryMenu extends AbstractContainerMenu {

	public static final int GEAR_Y = 18;
	public static final int BACKPACK_Y = 40;

	/** Sent to the client when the menu opens: which bot, and how big its backpack is. */
	public record OpenData(int botId, int backpackSize) {
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenData> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, OpenData::botId,
				ByteBufCodecs.VAR_INT, OpenData::backpackSize,
				OpenData::new);
	}

	private final Container gear;
	private final int botId;
	private final int rows;
	private final int backpackEnd;

	/** Client side. */
	public BotInventoryMenu(int containerId, Inventory inventory, OpenData data) {
		this(containerId, inventory, new SimpleContainer(GearSlot.COUNT), new SimpleContainer(rowsFor(data.backpackSize()) * 9), data);
	}

	public BotInventoryMenu(int containerId, Inventory inventory, Container gear, Container backpack, OpenData data) {
		super(ModMenus.BOT_INVENTORY, containerId);
		this.gear = gear;
		this.botId = data.botId();
		this.rows = rowsFor(data.backpackSize());
		for (GearSlot slot : GearSlot.values()) {
			this.addSlot(new GearSlotView(gear, slot, 8 + slot.ordinal() * 18, GEAR_Y));
		}
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < 9; column++) {
				this.addSlot(new Slot(backpack, column + row * 9, 8 + column * 18, BACKPACK_Y + row * 18));
			}
		}
		this.backpackEnd = this.slots.size();
		this.addStandardInventorySlots(inventory, 8, inventoryTop(rows));
	}

	public static int rowsFor(int backpackSize) {
		return Math.max(1, Math.min(6, (backpackSize + 8) / 9));
	}

	public static int inventoryTop(int rows) {
		return BACKPACK_Y + rows * 18 + 13;
	}

	public static int imageHeight(int rows) {
		return inventoryTop(rows) + 83;
	}

	public int botId() {
		return botId;
	}

	public int rows() {
		return rows;
	}

	/** The gear slot a menu slot shows, or null for the backpack and the player's inventory. */
	@Nullable
	public static GearSlot gearSlot(Slot slot) {
		return slot instanceof GearSlotView view ? view.gearSlot : null;
	}

	@Override
	public boolean stillValid(Player player) {
		return gear.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (slot == null || !slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack clicked = stack.copy();
		int end = this.slots.size();
		if (slotIndex < GearSlot.COUNT) {
			// Gear goes back to the player first, then into the backpack.
			if (!this.moveItemStackTo(stack, backpackEnd, end, true) && !this.moveItemStackTo(stack, GearSlot.COUNT, backpackEnd, false)) {
				return ItemStack.EMPTY;
			}
		} else if (!moveToGear(stack)) {
			boolean moved = slotIndex < backpackEnd
					? this.moveItemStackTo(stack, backpackEnd, end, true)
					: this.moveItemStackTo(stack, GearSlot.COUNT, backpackEnd, false);
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return clicked;
	}

	/** Puts a tool or armor piece into its empty gear slot. */
	private boolean moveToGear(ItemStack stack) {
		for (GearSlot gearSlot : GearSlot.values()) {
			int index = gearSlot.ordinal();
			if (gearSlot.accepts(stack) && !this.slots.get(index).hasItem()) {
				return this.moveItemStackTo(stack, index, index + 1, false);
			}
		}
		return false;
	}

	/** One gear slot: takes one item of its kind and shows what belongs there while empty. */
	private static final class GearSlotView extends Slot {
		private final GearSlot gearSlot;

		GearSlotView(Container container, GearSlot gearSlot, int x, int y) {
			super(container, gearSlot.ordinal(), x, y);
			this.gearSlot = gearSlot;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return gearSlot.accepts(stack);
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public Identifier getNoItemIcon() {
			return gearSlot.emptyIcon();
		}
	}
}
