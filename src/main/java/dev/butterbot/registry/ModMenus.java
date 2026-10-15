package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import dev.butterbot.menu.BotInventoryMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModMenus {

	/** A bot's gear and backpack. */
	public static final ExtendedMenuType<BotInventoryMenu, BotInventoryMenu.OpenData> BOT_INVENTORY = Registry.register(
			BuiltInRegistries.MENU,
			ButterBot.id("bot_inventory"),
			new ExtendedMenuType<>(BotInventoryMenu::new, BotInventoryMenu.OpenData.STREAM_CODEC));

	public static void init() {
	}

	private ModMenus() {
	}
}
