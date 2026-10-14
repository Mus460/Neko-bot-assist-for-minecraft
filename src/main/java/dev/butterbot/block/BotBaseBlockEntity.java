package dev.butterbot.block;

import dev.butterbot.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Bot Base keeps a 27-slot storage that bots deposit their loot into.
 * It is a normal {@link Container}, so hoppers and comparators work with it.
 */
public class BotBaseBlockEntity extends BlockEntity implements Container {

	public static final int STORAGE_SIZE = 27;

	private final SimpleContainer storage = new SimpleContainer(STORAGE_SIZE) {
		@Override
		public void setChanged() {
			super.setChanged();
			BotBaseBlockEntity.this.setChanged();
		}
	};

	public BotBaseBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BOT_BASE, pos, state);
	}

	public SimpleContainer getStorage() {
		return storage;
	}

	public Component getDisplayName() {
		return Component.translatable("block.butterbot.bot_base");
	}

	/** Inserts a stack, returning what did not fit. */
	public ItemStack insert(ItemStack stack) {
		ItemStack leftover = storage.addItem(stack.copy());
		setChanged();
		return leftover;
	}

	public boolean isFull() {
		for (int i = 0; i < storage.getContainerSize(); i++) {
			ItemStack stack = storage.getItem(i);
			if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
				return false;
			}
		}
		return true;
	}

	public boolean takeOne(Item item) {
		for (int i = 0; i < storage.getContainerSize(); i++) {
			ItemStack stack = storage.getItem(i);
			if (stack.is(item)) {
				stack.shrink(1);
				storage.setItem(i, stack);
				return true;
			}
		}
		return false;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		storage.clearContent();
		storage.fromItemList(input.listOrEmpty("Storage", ItemStack.CODEC));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		storage.storeAsItemList(output.list("Storage", ItemStack.CODEC));
	}

	// ---------------------------------------------------------------- Container delegation

	@Override
	public int getContainerSize() {
		return storage.getContainerSize();
	}

	@Override
	public boolean isEmpty() {
		return storage.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return storage.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int count) {
		return storage.removeItem(slot, count);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return storage.removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		storage.setItem(slot, stack);
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		storage.clearContent();
	}
}
