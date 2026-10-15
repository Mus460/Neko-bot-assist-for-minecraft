package dev.butterbot.client;

import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bots the player asked to see with "Show" in the panel. For half a minute such a bot glows in its
 * hair colour through walls, sends a beacon beam up to the sky and gets a pulsing marker on screen.
 */
public final class BotFocus {

	public static final long FOCUS_MILLIS = 30_000L;

	/** When each focus ends ({@link Util#getMillis()}). */
	private static final Map<UUID, Long> until = new HashMap<>();

	public static void set(UUID bot) {
		until.put(bot, Util.getMillis() + FOCUS_MILLIS);
	}

	/** Cheap enough to ask for every bot every frame: nearly always an empty map. */
	public static boolean isFocused(UUID bot) {
		if (until.isEmpty()) {
			return false;
		}
		Long end = until.get(bot);
		if (end == null) {
			return false;
		}
		if (Util.getMillis() < end) {
			return true;
		}
		until.remove(bot);
		return false;
	}

	public static boolean any() {
		if (until.isEmpty()) {
			return false;
		}
		long now = Util.getMillis();
		until.values().removeIf(end -> end <= now);
		return !until.isEmpty();
	}

	public static void clear() {
		until.clear();
	}

	/** The colour a focused bot glows in: its hair colour, lifted when too dark to show (black hair at night). */
	public static int glowColor(int hairColor) {
		int brightest = Math.max(ARGB.red(hairColor), Math.max(ARGB.green(hairColor), ARGB.blue(hairColor)));
		float lift = Mth.clamp((0x90 - brightest) / (float) 0x90, 0.0F, 1.0F) * 0.6F;
		return ARGB.opaque(ARGB.srgbLerp(lift, hairColor, 0xFFFFFFFF));
	}

	private BotFocus() {
	}
}
