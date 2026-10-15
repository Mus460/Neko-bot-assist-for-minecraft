package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import dev.butterbot.item.BotCoreItem;
import dev.butterbot.item.ButterBotItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.DamageResistant;

import java.util.function.Function;

public final class ModItems {
	public static final Item BUTTER_BOT = register("butter_bot", ButterBotItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
	public static final Item BOT_BASE = register("bot_base",
			properties -> new BlockItem(ModBlocks.BOT_BASE, properties),
			new Item.Properties().useBlockDescriptionPrefix());
	/** Left by a destroyed bot; fire, explosions and cactus can't destroy it (see the bot_core_immune tag). */
	public static final Item BOT_CORE = register("bot_core", BotCoreItem::new,
			new Item.Properties().stacksTo(1).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
					.delayedComponent(DataComponents.DAMAGE_RESISTANT, context -> new DamageResistant(context.getOrThrow(
							TagKey.create(Registries.DAMAGE_TYPE, ButterBot.id("bot_core_immune"))))));

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ButterBot.id(name));
		Item item = factory.apply(properties.setId(key));
		if (item instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, item);
		}
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(BOT_BASE));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(output -> output.accept(BUTTER_BOT));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(BUTTER_BOT));
	}

	private ModItems() {
	}
}
