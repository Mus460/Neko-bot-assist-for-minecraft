package dev.butterbot.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Small helpers for player-facing text. All visible strings use translation keys. */
public final class Msg {

	public static MutableComponent prefixed(Component message) {
		return Component.literal("[Butter Bot] ").withStyle(ChatFormatting.GOLD)
				.append(message.copy().withStyle(style -> style.getColor() == null ? style.withColor(ChatFormatting.WHITE) : style));
	}

	private Msg() {
	}
}
