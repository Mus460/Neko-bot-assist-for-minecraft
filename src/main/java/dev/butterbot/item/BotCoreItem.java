package dev.butterbot.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * What a destroyed bot leaves behind: everything it remembers (name, skin, level, skills, mastery,
 * job, bond and settings) in the same saved data a packed bot keeps. Crafting it with 4 Iron Ingots turns
 * it back into a placeable {@link ButterBotItem}, which brings the bot back as it was.
 */
public class BotCoreItem extends Item {

	public BotCoreItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
								Consumer<Component> builder, TooltipFlag flag) {
		CompoundTag tag = ButterBotItem.savedTag(stack);
		if (tag != null) {
			// "Mochi · Level 17 · ♥ 72"; cores left before the bond existed have no heart.
			String name = tag.getStringOr("Name", "");
			MutableComponent line = (name.isEmpty() ? Component.translatable("item.butterbot.butter_bot") : Component.literal(name))
					.append(" · ").append(Component.translatable("butterbot.tooltip.level", tag.getIntOr("Level", 1)));
			tag.getInt("Bond").ifPresent(bond -> line.append(" · ♥ " + bond));
			builder.accept(line.withStyle(ChatFormatting.GOLD));
		}
		builder.accept(Component.translatable("butterbot.tooltip.core_revive").withStyle(ChatFormatting.GRAY));
	}
}
