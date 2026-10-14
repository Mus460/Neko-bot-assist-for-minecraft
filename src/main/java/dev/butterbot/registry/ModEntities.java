package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import dev.butterbot.entity.ButterBotEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private static final ResourceKey<EntityType<?>> BUTTER_BOT_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, ButterBot.id("butter_bot"));

	public static final EntityType<ButterBotEntity> BUTTER_BOT = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			BUTTER_BOT_KEY,
			EntityType.Builder.of(ButterBotEntity::new, MobCategory.MISC)
					.sized(0.6F, 1.8F)
					.eyeHeight(1.62F)
					.fireImmune()
					.clientTrackingRange(10)
					.build(BUTTER_BOT_KEY));

	public static void init() {
		FabricDefaultAttributeRegistry.register(BUTTER_BOT, ButterBotEntity.createAttributes());
	}

	private ModEntities() {
	}
}
