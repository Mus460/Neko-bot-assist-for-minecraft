package dev.butterbot.entity;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Exposes a bot's backpack to a vanilla chest menu, closing it if the bot dies or walks away. Always
 * reads the bot's current backpack, which grows when the bot levels up.
 */
public final class BotBackpack implements Container {
	private final ButterBotEntity bot;

	public BotBackpack(ButterBotEntity bot) {
		this.bot = bot;
	}

	private SimpleContainer inventory() {
		return bot.getInventory();
	}

	@Override
	public int getContainerSize() {
		return inventory().getContainerSize();
	}

	@Override
	public boolean isEmpty() {
		return inventory().isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return inventory().getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		return inventory().removeItem(slot, count);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return inventory().removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		inventory().setItem(slot, stack);
	}

	@Override
	public void setChanged() {
		inventory().setChanged();
	}

	@Override
	public boolean stillValid(Player player) {
		return bot.isAlive() && !bot.isRemoved() && bot.isOwnedBy(player) && player.distanceToSqr(bot) < 10.0D * 10.0D;
	}

	@Override
	public void clearContent() {
		inventory().clearContent();
	}
}
