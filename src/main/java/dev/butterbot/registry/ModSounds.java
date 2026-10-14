package dev.butterbot.registry;

import dev.butterbot.ButterBot;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
	public static final SoundEvent BOT_SPAWN = register("butter_bot_spawn");
	public static final SoundEvent BOT_AMBIENT = register("butter_bot_ambient");

	// The neko girl's voice (VOICEVOX:小夜/SAYO).
	public static final SoundEvent VOICE_HELLO = register("voice.hello");
	public static final SoundEvent VOICE_OK = register("voice.ok");
	public static final SoundEvent VOICE_DONE = register("voice.done");
	public static final SoundEvent VOICE_GIVE = register("voice.give");
	public static final SoundEvent VOICE_HURT = register("voice.hurt");
	public static final SoundEvent VOICE_DEATH = register("voice.death");
	public static final SoundEvent VOICE_ATTACK = register("voice.attack");
	public static final SoundEvent VOICE_LEVEL_UP = register("voice.level_up");
	public static final SoundEvent VOICE_AMBIENT = register("voice.ambient");
	public static final SoundEvent VOICE_RESCUE = register("voice.rescue");
	public static final SoundEvent VOICE_BYE = register("voice.bye");
	public static final SoundEvent VOICE_FEED = register("voice.feed");
	public static final SoundEvent VOICE_DANGER = register("voice.danger");
	public static final SoundEvent VOICE_STUCK = register("voice.stuck");

	private static SoundEvent register(String name) {
		Identifier id = ButterBot.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void init() {
	}

	private ModSounds() {
	}
}
