package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import dev.butterbot.block.BotBaseBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;

public final class ModBlocks {
	public static final Block BOT_BASE = register("bot_base", BotBaseBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_MAGENTA)
					.strength(2.0F, 6.0F)
					.sound(SoundType.METAL)
					.lightLevel(state -> 7)
					.noOcclusion());

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
								  BlockBehaviour.Properties properties) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, ButterBot.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
	}

	public static void init() {
	}

	private ModBlocks() {
	}
}
