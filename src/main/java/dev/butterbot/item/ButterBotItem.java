package dev.butterbot.item;

import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.entity.SmeltMode;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.registry.ModItems;
import dev.butterbot.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Places a Butter Bot that already belongs to the player who places it. A bot that was picked up
 * (sneak + right-click) keeps its name, skin, program, level and settings inside this item.
 */
public class ButterBotItem extends Item {

	private static final String TAG_ROOT = "ButterBot";

	public ButterBotItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack stack = context.getItemInHand();
		BlockPos clicked = context.getClickedPos();
		Direction face = context.getClickedFace();
		BlockState clickedState = level.getBlockState(clicked);
		BlockPos spawnPos = clickedState.getCollisionShape(level, clicked).isEmpty() ? clicked : clicked.relative(face);

		ButterBotEntity bot = ModEntities.BUTTER_BOT.spawn(serverLevel, stack, context.getPlayer(), spawnPos,
				EntitySpawnReason.SPAWN_ITEM_USE, true, !Objects.equals(clicked, spawnPos) && face == Direction.UP);
		if (bot == null) {
			return InteractionResult.FAIL;
		}

		applySavedData(stack, bot);
		if (context.getPlayer() instanceof ServerPlayer player) {
			bot.claim(player);
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.placed", bot.getName())));
		}
		stack.consume(1, context.getPlayer());
		return InteractionResult.SUCCESS_SERVER;
	}

	private static void applySavedData(ItemStack stack, ButterBotEntity bot) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		CompoundTag root = data.copyTag();
		CompoundTag tag = root.getCompoundOrEmpty(TAG_ROOT);
		if (tag.isEmpty()) {
			return;
		}
		bot.setNeko(tag.getBooleanOr("Neko", true));
		bot.setSkinId(tag.getStringOr("Skin", ""));
		bot.setLuaScript(tag.getStringOr("Script", ""));
		bot.setDeliveryMode(DeliveryMode.byOrdinal(tag.getIntOr("Delivery", 0)));
		bot.restoreProgress(tag.getIntOr("Level", 1), tag.getIntOr("WorkXp", 0));
		bot.addStoredXp(tag.getIntOr("StoredXp", 0));
		bot.setGuarding(tag.getBooleanOr("Guard", true));
		bot.setAutoTorch(tag.getBooleanOr("AutoTorch", true));
		bot.setFeedOwner(tag.getBooleanOr("FeedOwner", true));
		bot.setAutoRefill(tag.getBooleanOr("AutoRefill", true));
		bot.setSmeltMode(SmeltMode.byOrdinal(tag.getIntOr("SmeltMode", SmeltMode.ORES.ordinal())));
		tag.getLong("Base").ifPresent(base -> bot.setBasePos(BlockPos.of(base)));
		String name = tag.getStringOr("Name", "");
		if (!name.isEmpty()) {
			bot.setCustomName(Component.literal(name));
		}
	}

	/** Creates an item holding everything worth keeping from the given bot. */
	public static ItemStack createFrom(ButterBotEntity bot) {
		ItemStack stack = new ItemStack(ModItems.BUTTER_BOT);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("Neko", bot.isNeko());
		tag.putString("Skin", bot.getSkinId());
		tag.putString("Script", bot.getLuaScript());
		tag.putInt("Delivery", bot.getDeliveryMode().ordinal());
		tag.putInt("Level", bot.getBotLevel());
		tag.putInt("WorkXp", bot.getWorkXp());
		tag.putInt("StoredXp", bot.getStoredXp());
		tag.putBoolean("Guard", bot.isGuarding());
		tag.putBoolean("AutoTorch", bot.placesTorchesWhileMining());
		tag.putBoolean("FeedOwner", bot.feedsOwner());
		tag.putBoolean("AutoRefill", bot.refillsHotbar());
		tag.putInt("SmeltMode", bot.getSmeltMode().ordinal());
		if (bot.getBasePos() != null) {
			tag.putLong("Base", bot.getBasePos().asLong());
		}
		if (bot.hasCustomName()) {
			tag.putString("Name", bot.getName().getString());
			stack.set(DataComponents.CUSTOM_NAME, bot.getName().copy());
		}
		CompoundTag root = new CompoundTag();
		root.put(TAG_ROOT, tag);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		return stack;
	}

	@Nullable
	private static CompoundTag savedTag(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag().getCompoundOrEmpty(TAG_ROOT);
		return tag.isEmpty() ? null : tag;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
								Consumer<Component> builder, TooltipFlag flag) {
		CompoundTag tag = savedTag(stack);
		if (tag == null) {
			builder.accept(Component.translatable("butterbot.tooltip.new_bot").withStyle(ChatFormatting.GRAY));
			builder.accept(Component.translatable("butterbot.tooltip.place_hint").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		builder.accept(Component.translatable("butterbot.tooltip.level", tag.getIntOr("Level", 1)).withStyle(ChatFormatting.GOLD));
		if (!tag.getStringOr("Skin", "").isEmpty()) {
			builder.accept(Component.translatable("butterbot.tooltip.custom_skin").withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		if (!tag.getStringOr("Script", "").isBlank()) {
			builder.accept(Component.translatable("butterbot.tooltip.has_program").withStyle(ChatFormatting.AQUA));
		}
	}
}
