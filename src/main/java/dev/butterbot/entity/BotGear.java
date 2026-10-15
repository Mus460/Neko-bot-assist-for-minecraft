package dev.butterbot.entity;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Exposes a bot's gear (four armor pieces, then sword, pickaxe, axe, shovel and hoe) to its
 * inventory menu, in {@link GearSlot} order.
 */
public final class BotGear implements Container {
	private final ButterBotEntity bot;

	public BotGear(ButterBotEntity bot) {
		this.bot = bot;
	}

	private static GearSlot slot(int index) {
		return GearSlot.values()[index];
	}

	private static boolean exists(int index) {
		return index >= 0 && index < GearSlot.COUNT;
	}

	@Override
	public int getContainerSize() {
		return GearSlot.COUNT;
	}

	@Override
	public boolean isEmpty() {
		for (GearSlot slot : GearSlot.values()) {
			if (!bot.getGear(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int index) {
		return exists(index) ? bot.getGear(slot(index)) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int index, int count) {
		ItemStack current = getItem(index);
		if (current.isEmpty() || count <= 0) {
			return ItemStack.EMPTY;
		}
		ItemStack taken = current.split(count);
		bot.setGear(slot(index), current.isEmpty() ? ItemStack.EMPTY : current);
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int index) {
		ItemStack current = getItem(index);
		if (!current.isEmpty()) {
			bot.setGear(slot(index), ItemStack.EMPTY);
		}
		return current;
	}

	@Override
	public void setItem(int index, ItemStack stack) {
		if (exists(index)) {
			bot.setGear(slot(index), stack);
		}
	}

	@Override
	public int getMaxStackSize() {
		return 1;
	}

	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		return exists(index) && slot(index).accepts(stack);
	}

	@Override
	public void setChanged() {
	}

	@Override
	public boolean stillValid(Player player) {
		return bot.isAlive() && !bot.isRemoved() && bot.isOwnedBy(player) && player.distanceToSqr(bot) < 10.0D * 10.0D;
	}

	@Override
	public void clearContent() {
		for (GearSlot slot : GearSlot.values()) {
			bot.setGear(slot, ItemStack.EMPTY);
		}
	}
}
