package dev.butterbot.test;

import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.item.ButterBotItem;
import dev.butterbot.registry.ModBlocks;
import dev.butterbot.registry.ModEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public class ButterBotGameTests {

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
	}

	private static ButterBotEntity spawnBot(GameTestHelper helper, int x, int z) {
		return helper.spawn(ModEntities.BUTTER_BOT, new BlockPos(x, 1, z));
	}

	@GameTest(maxTicks = 600)
	public void minesNearbyDiamondOre(GameTestHelper helper) {
		floor(helper);
		BlockPos ore = new BlockPos(6, 1, 6);
		helper.setBlock(ore, Blocks.DIAMOND_ORE);
		ButterBotEntity bot = spawnBot(helper, 1, 1);
		bot.assignMineTask(BotResource.DIAMOND, 1);

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIAMOND_ORE, ore);
			helper.assertTrue(bot.countItem(Items.DIAMOND) >= 1, Component.literal("bot should carry a diamond"));
			helper.assertTrue(bot.isReturnRequested(), Component.literal("bot should want to deliver"));
		});
	}

	@GameTest(maxTicks = 800)
	public void craftsChestFromChestContents(GameTestHelper helper) {
		floor(helper);
		BlockPos chestPos = new BlockPos(5, 1, 1);
		helper.setBlock(chestPos, Blocks.CHEST);
		ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPos));
		helper.assertTrue(chest != null, Component.literal("chest block entity missing"));
		chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 16));

		ButterBotEntity bot = spawnBot(helper, 1, 4);
		bot.assignCraftTask(Items.CHEST, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(bot.countItem(Items.CHEST) >= 1, Component.literal("bot should have crafted a chest"));
			helper.assertTrue(chest.getItem(0).getCount() == 8, Component.literal("bot should take exactly 8 planks"));
		});
	}

	@GameTest(maxTicks = 900)
	public void digsTunnel(GameTestHelper helper) {
		floor(helper);
		for (int x = 2; x <= 6; x++) {
			for (int y = 1; y <= 3; y++) {
				for (int z = 1; z <= 3; z++) {
					helper.setBlock(x, y, z, Blocks.STONE);
				}
			}
		}
		ButterBotEntity bot = spawnBot(helper, 1, 2);
		BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
		bot.assignTunnelTask(origin, Direction.EAST, 4, 1, 2, false);

		helper.succeedWhen(() -> {
			for (int x = 2; x <= 5; x++) {
				helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(x, 1, 2));
				helper.assertBlockNotPresent(Blocks.STONE, new BlockPos(x, 2, 2));
			}
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(6, 1, 2));
			helper.assertTrue(bot.getTask() != BotTask.TUNNEL || bot.isReturnRequested(), Component.literal("tunnel should be finished"));
		});
	}

	@GameTest(maxTicks = 400)
	public void luaProgramDigsBlock(GameTestHelper helper) {
		floor(helper);
		BlockPos target = new BlockPos(3, 1, 1);
		helper.setBlock(target, Blocks.DIRT);
		ButterBotEntity bot = spawnBot(helper, 1, 1);
		BlockPos abs = helper.absolutePos(target);
		bot.setLuaScript("local ok = bot.dig(" + abs.getX() + ", " + abs.getY() + ", " + abs.getZ() + ")\n"
				+ "if not ok then error('dig failed') end\n"
				+ "bot.say('dug it')\n");
		bot.assignLuaTask();

		helper.succeedWhen(() -> {
			helper.assertBlockNotPresent(Blocks.DIRT, target);
			helper.assertTrue(bot.getTask() == BotTask.NONE, Component.literal("program should have finished"));
		});
	}

	@GameTest(maxTicks = 60)
	public void depositsIntoBase(GameTestHelper helper) {
		floor(helper);
		BlockPos basePos = new BlockPos(2, 1, 2);
		helper.setBlock(basePos, ModBlocks.BOT_BASE);
		BotBaseBlockEntity base = (BotBaseBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(basePos));
		helper.assertTrue(base != null, Component.literal("base block entity missing"));

		ButterBotEntity bot = spawnBot(helper, 3, 2);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 32));
		bot.setBasePos(helper.absolutePos(basePos));
		bot.setDeliveryMode(DeliveryMode.BASE);
		bot.requestDelivery();

		helper.succeedWhen(() -> {
			helper.assertTrue(!bot.hasLoot(), Component.literal("backpack should be empty"));
			helper.assertTrue(base.getStorage().countItem(Items.COBBLESTONE) == 32, Component.literal("base should hold the cobblestone"));
		});
	}

	@GameTest
	public void pickedUpBotKeepsItsData(GameTestHelper helper) {
		floor(helper);
		ButterBotEntity bot = spawnBot(helper, 1, 1);
		bot.setNeko(false);
		bot.setLuaScript("bot.say('hi')");
		bot.setDeliveryMode(DeliveryMode.BASE);
		bot.setSkinId("a9993e364706816aba3e25717850c26c9cd0d89d");
		bot.setCustomName(Component.literal("Herbert"));

		ItemStack stack = ButterBotItem.createFrom(bot);
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		helper.assertTrue(data != null, Component.literal("item should carry custom data"));
		CompoundTag tag = data.copyTag().getCompoundOrEmpty("ButterBot");
		helper.assertTrue(!tag.getBooleanOr("Neko", true), Component.literal("neko setting not kept"));
		helper.assertTrue(tag.getStringOr("Script", "").equals("bot.say('hi')"), Component.literal("script not kept"));
		helper.assertTrue(tag.getIntOr("Delivery", 0) == DeliveryMode.BASE.ordinal(), Component.literal("delivery mode not kept"));
		helper.assertTrue(tag.getStringOr("Skin", "").equals("a9993e364706816aba3e25717850c26c9cd0d89d"), Component.literal("skin not kept"));
		helper.assertTrue(tag.getStringOr("Name", "").equals("Herbert"), Component.literal("name not kept"));
		helper.succeed();
	}
}
