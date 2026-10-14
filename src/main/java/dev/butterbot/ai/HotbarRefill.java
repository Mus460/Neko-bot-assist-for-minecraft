package dev.butterbot.ai;

import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Keeps the owner's hotbar stocked while the bot is close by. When a stack of blocks, torches,
 * food, arrows, rockets or pearls runs low (a quarter of a stack or less), it is topped up from the
 * owner's own inventory first, then from the bot's backpack, then from the Bot Base if the bot is
 * standing near it. A stack that was used up completely is refilled too.
 */
public final class HotbarRefill {

	private static final double BASE_REACH_SQ = 16.0D * 16.0D;
	private static final int MESSAGE_COOLDOWN_TICKS = 60;

	private final Item[] lastItems = new Item[Inventory.getSelectionSize()];
	private final int[] lastCounts = new int[Inventory.getSelectionSize()];
	private long lastMessage;

	public void tick(ButterBotEntity bot, Player owner) {
		Inventory inventory = owner.getInventory();
		for (int slot = 0; slot < lastItems.length; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				if (isRefillable(stack) && stack.getCount() <= threshold(stack)) {
					int added = topUp(bot, inventory, slot, stack);
					if (added > 0) {
						announce(bot, owner, stack, added);
					}
				}
				ItemStack now = inventory.getItem(slot);
				lastItems[slot] = now.getItem();
				lastCounts[slot] = now.getCount();
				continue;
			}

			Item last = lastItems[slot];
			lastItems[slot] = null;
			if (last == null || lastCounts[slot] > threshold(new ItemStack(last)) || !isRefillable(new ItemStack(last))
					|| inventory.contains(other -> other.is(last))) {
				continue;
			}
			// The last of it was just used up: bring a fresh stack from the bot or the base.
			ItemStack fresh = takeFromBot(bot, other -> other.is(last), last.getDefaultMaxStackSize());
			if (!fresh.isEmpty()) {
				inventory.setItem(slot, fresh);
				lastItems[slot] = fresh.getItem();
				lastCounts[slot] = fresh.getCount();
				announce(bot, owner, fresh, fresh.getCount());
			}
		}
	}

	/** Blocks (torches included), food, arrows, firework rockets, ender pearls, snowballs and bone meal. */
	public static boolean isRefillable(ItemStack stack) {
		if (!stack.isStackable()) {
			return false;
		}
		return stack.getItem() instanceof BlockItem || stack.has(DataComponents.FOOD) || stack.is(ItemTags.ARROWS)
				|| stack.is(Items.FIREWORK_ROCKET) || stack.is(Items.ENDER_PEARL) || stack.is(Items.SNOWBALL)
				|| stack.is(Items.BONE_MEAL) || stack.is(Items.WIND_CHARGE);
	}

	private static int threshold(ItemStack stack) {
		return Math.max(1, stack.getMaxStackSize() / 4);
	}

	private int topUp(ButterBotEntity bot, Inventory inventory, int slot, ItemStack stack) {
		int wanted = stack.getMaxStackSize() - stack.getCount();
		int added = 0;
		// The owner's own backpack slots first: that is just tidying, nothing new is handed over.
		for (int i = Inventory.getSelectionSize(); i < inventory.getNonEquipmentItems().size() && added < wanted; i++) {
			ItemStack other = inventory.getItem(i);
			if (!other.isEmpty() && ItemStack.isSameItemSameComponents(other, stack)) {
				int move = Math.min(wanted - added, other.getCount());
				other.shrink(move);
				inventory.setItem(i, other);
				added += move;
			}
		}
		if (added < wanted) {
			ItemStack fromBot = takeFromBot(bot, other -> ItemStack.isSameItemSameComponents(other, stack), wanted - added);
			added += fromBot.getCount();
		}
		if (added > 0) {
			stack.grow(added);
			inventory.setItem(slot, stack);
		}
		return added;
	}

	/** Takes matching items from the bot's backpack, then from the base storage if the bot is near it. */
	private static ItemStack takeFromBot(ButterBotEntity bot, Predicate<ItemStack> match, int amount) {
		ItemStack result = ItemStack.EMPTY;
		result = takeFrom(bot.getInventory(), match, amount, result);
		BotBaseBlockEntity base = bot.getBase();
		if (result.getCount() < amount && base != null
				&& bot.distanceToSqr(Vec3.atCenterOf(base.getBlockPos())) < BASE_REACH_SQ) {
			result = takeFrom(base, match, amount - result.getCount(), result);
		}
		return result;
	}

	private static ItemStack takeFrom(Container container, Predicate<ItemStack> match, int amount, ItemStack result) {
		for (int i = 0; i < container.getContainerSize() && amount > 0; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || !match.test(stack) || (!result.isEmpty() && !ItemStack.isSameItemSameComponents(stack, result))) {
				continue;
			}
			int move = Math.min(amount, stack.getCount());
			if (result.isEmpty()) {
				result = stack.copyWithCount(move);
			} else {
				result.grow(move);
			}
			stack.shrink(move);
			container.setItem(i, stack);
			amount -= move;
		}
		container.setChanged();
		return result;
	}

	private void announce(ButterBotEntity bot, Player owner, ItemStack stack, int added) {
		bot.level().playSound(null, owner.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4F, 1.4F);
		long now = bot.level().getGameTime();
		if (owner instanceof ServerPlayer serverPlayer && now - lastMessage > MESSAGE_COOLDOWN_TICKS) {
			lastMessage = now;
			serverPlayer.sendOverlayMessage(Component.translatable("butterbot.msg.refilled", bot.getName(), stack.getHoverName(), added));
		}
	}
}
