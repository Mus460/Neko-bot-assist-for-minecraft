package dev.butterbot;

import dev.butterbot.ai.actions.RecoverItemsAction;
import dev.butterbot.ai.job.JobChunks;
import dev.butterbot.command.BotCommand;
import dev.butterbot.network.HudSync;
import dev.butterbot.network.ModNetworking;
import dev.butterbot.registry.ModBlockEntities;
import dev.butterbot.registry.ModBlocks;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.registry.ModItems;
import dev.butterbot.registry.ModMenus;
import dev.butterbot.registry.ModSounds;
import dev.butterbot.skin.ServerSkinStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ButterBot implements ModInitializer {
	public static final String MOD_ID = "butterbot";
	public static final Logger LOGGER = LoggerFactory.getLogger("Butter Bot");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModSounds.init();
		ModBlocks.init();
		ModItems.init();
		ModBlockEntities.init();
		ModEntities.init();
		ModMenus.init();
		ModNetworking.init();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				BotCommand.register(dispatcher, buildContext));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				RecoverItemsAction.onOwnerDeath(player);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(HudSync::tick);
		ServerTickEvents.END_SERVER_TICK.register(JobChunks::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.butterbot.network.BotDebug::tick);

		ServerLifecycleEvents.SERVER_STARTED.register(ServerSkinStore::init);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerSkinStore.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> dev.butterbot.network.BotDebug.clear());

		LOGGER.info("Butter Bot ready. What is my purpose?");
	}
}
