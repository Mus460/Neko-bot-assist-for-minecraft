package dev.butterbot.client;

import dev.butterbot.ButterBot;
import dev.butterbot.ClientHooks;
import dev.butterbot.client.render.BotBaseRenderer;
import dev.butterbot.client.render.ButterBotRenderer;
import dev.butterbot.client.screen.BotControlScreen;
import dev.butterbot.client.screen.BotInventoryScreen;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.network.Payloads;
import dev.butterbot.registry.ModBlockEntities;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.Minecraft;

import java.util.Optional;

public class ButterBotClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.BUTTER_BOT, ButterBotRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.BOT_BASE, BotBaseRenderer::new);
		MenuScreens.register(ModMenus.BOT_INVENTORY, BotInventoryScreen::new);

		ClientHooks.setBaseScreenOpener(pos -> Minecraft.getInstance().gui.setScreen(new BotControlScreen(Optional.of(pos), null)));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.BotList.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof BotControlScreen screen) {
				screen.setBots(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenBotScreen.TYPE, (payload, context) ->
				context.client().gui.setScreen(new BotControlScreen(Optional.empty(), payload.bot())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.SkinData.TYPE, (payload, context) ->
				ClientSkinManager.onSkinData(payload.skinId(), payload.data()));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.HudUpdate.TYPE, (payload, context) -> BotHud.update(payload));

		BotKeys.init();
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ButterBot.id("bot_markers"), new BotMarkers());
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ButterBot.id("bot_list"), new BotHud());

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> ClientSkinManager.ensureSetup());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientSkinManager.onDisconnect();
			BotHud.clear();
			BotFocus.clear();
		});
	}
}
