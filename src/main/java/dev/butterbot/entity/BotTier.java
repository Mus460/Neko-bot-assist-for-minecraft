package dev.butterbot.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Looks that grow with a bot's level. Every five levels the bot reaches a new tier, which colours its
 * collar bell, tail band and name; from silver on it gives off sparkles, from gold on the bell and band
 * glow, and at level 30 it becomes a nekomata with two tails and soul-fire wisps.
 */
public enum BotTier {
	KITTEN(1, 0xFFFFAA00, 0xFF8A5A00),
	BRONZE(5, 0xFFE0904F, 0xFF8A4A1A),
	SILVER(10, 0xFFDDE4EC, 0xFF56606C),
	GOLD(15, 0xFFFFD447, 0xFF946400),
	DIAMOND(20, 0xFF5CEBE0, 0xFF0E8079),
	AMETHYST(25, 0xFFC08CFF, 0xFF6F3FBF),
	LEGEND(30, 0xFFFF7AD9, 0xFFB02A7E);

	/** How long the legend tier takes to go once through the rainbow, in ticks. */
	private static final float RAINBOW_TICKS = 120.0F;

	private final int minLevel;
	private final int color;
	private final int guiColor;

	BotTier(int minLevel, int color, int guiColor) {
		this.minLevel = minLevel;
		this.color = color;
		this.guiColor = guiColor;
	}

	public static BotTier of(int level) {
		BotTier tier = KITTEN;
		for (BotTier candidate : values()) {
			if (level >= candidate.minLevel) {
				tier = candidate;
			}
		}
		return tier;
	}

	/** The tier with this ordinal, or kitten when it is out of range. */
	public static BotTier byIndex(int index) {
		BotTier[] all = values();
		return index >= 0 && index < all.length ? all[index] : KITTEN;
	}

	public int minLevel() {
		return minLevel;
	}

	/** Bright ARGB colour for things in the world. */
	public int color() {
		return color;
	}

	/** Darker ARGB colour that reads well on the light panel background. */
	public int guiColor() {
		return guiColor;
	}

	/** The colour right now: the legend tier slowly cycles through the rainbow. */
	public int colorAt(float ageTicks) {
		if (this != LEGEND) {
			return color;
		}
		float hue = (ageTicks / RAINBOW_TICKS) % 1.0F;
		return Mth.hsvToArgb(hue < 0.0F ? hue + 1.0F : hue, 0.5F, 1.0F, 255);
	}

	/** From gold on, the bell and tail band glow in the dark. */
	public boolean glows() {
		return compareTo(GOLD) >= 0;
	}

	public boolean atLeast(BotTier other) {
		return compareTo(other) >= 0;
	}

	public String key() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Component displayName() {
		return Component.translatable("butterbot.tier." + key());
	}
}
