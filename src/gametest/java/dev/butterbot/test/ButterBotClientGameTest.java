package dev.butterbot.test;

import dev.butterbot.client.screen.BotControlScreen;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModBlocks;
import dev.butterbot.registry.ModEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.Optional;

/**
 * Visual smoke test: builds a small scene with a Bot Base and two Butter Bots, then takes
 * screenshots of the world and of every control panel tab.
 */
public class ButterBotClientGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();

			BlockPos[] basePos = new BlockPos[1];
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos feet = player.blockPosition();
				BlockPos base = feet.offset(0, 0, 4);
				level.setBlockAndUpdate(base, ModBlocks.BOT_BASE.defaultBlockState());
				basePos[0] = base;

				ButterBotEntity first = ModEntities.BUTTER_BOT.create(level, EntitySpawnReason.COMMAND);
				first.snapTo(feet.getX() + 2.5, feet.getY(), feet.getZ() + 4.5, 200.0F, 0.0F);
				level.addFreshEntity(first);
				first.claim(player);
				first.setBasePos(base);
				first.say(Component.literal("What is my purpose?"));

				ButterBotEntity second = ModEntities.BUTTER_BOT.create(level, EntitySpawnReason.COMMAND);
				second.snapTo(feet.getX() - 2.5, feet.getY(), feet.getZ() + 4.5, 160.0F, 0.0F);
				level.addFreshEntity(second);
				second.claim(player);
				second.setNeko(false);

				player.connection.teleport(feet.getX() + 0.5, feet.getY(), feet.getZ() - 1.5, 0.0F, 15.0F);
			});
			context.waitTicks(40);
			context.takeScreenshot("butterbot_world");

			context.setScreen(() -> new BotControlScreen(Optional.of(basePos[0]), null));
			context.waitTicks(20);
			context.takeScreenshot("butterbot_gui_bots");

			for (String tab : new String[]{"Control", "Mining", "Crafting", "Tunnel", "Program", "Skin"}) {
				context.clickScreenButton(tab);
				context.waitTicks(5);
				context.takeScreenshot("butterbot_gui_" + tab.toLowerCase());
			}
			context.setScreen(() -> null);
		}
	}
}
