package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import dev.butterbot.block.BotBaseBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

public final class ModBlockEntities {
	public static final BlockEntityType<BotBaseBlockEntity> BOT_BASE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			ButterBot.id("bot_base"),
			new BlockEntityType<>(BotBaseBlockEntity::new, Set.of(ModBlocks.BOT_BASE)));

	public static void init() {
	}

	private ModBlockEntities() {
	}
}
