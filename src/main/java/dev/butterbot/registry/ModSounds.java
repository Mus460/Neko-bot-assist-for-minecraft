package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
	public static final SoundEvent BOT_SPAWN = register("butter_bot_spawn");
	public static final SoundEvent BOT_AMBIENT = register("butter_bot_ambient");

	private static SoundEvent register(String name) {
		Identifier id = ButterBot.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {
	}

	private ModSounds() {
	}
}
