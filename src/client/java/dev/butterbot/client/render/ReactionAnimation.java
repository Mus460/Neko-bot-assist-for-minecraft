package dev.butterbot.client.render;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.util.Mth;

/**
 * Curves for the bond reactions. A head pat (or nuzzle) folds the ears out, speeds the tail up and leans the head
 * into the hand; a treat gives chewing nods and a happy tail; a pout turns the head and body away with the chin up,
 * ears flat and the tail lashing.
 */
final class ReactionAnimation {

	private ReactionAnimation() {
	}

	/**
	 * 0 to 1: in over four ticks, held, and out over the last ten ({@code left} ticks before the end). A pat
	 * while one plays only pushes the end out, so the reaction stays held instead of starting over.
	 */
	static float strength(float time, float left) {
		if (time < 0.0F) {
			return 0.0F;
		}
		float in = Mth.clamp(time / 4.0F, 0.0F, 1.0F);
		float out = Mth.clamp(left / 10.0F, 0.0F, 1.0F);
		return smooth(Math.min(in, out));
	}

	/** Degrees the ears fold outwards. */
	static float earFold(int reaction, float time, float s) {
		return switch (reaction) {
			case ButterBotEntity.REACTION_PET -> 45.0F * s;
			case ButterBotEntity.REACTION_POUT -> 38.0F * s;
			// Perked up and flicking while it chews.
			case ButterBotEntity.REACTION_TREAT -> 10.0F * s * Mth.sin(time * 0.9F);
			default -> 0.0F;
		};
	}

	/** Degrees the head tips forward (negative: chin up). */
	static float headPitch(int reaction, float time, float s) {
		return switch (reaction) {
			case ButterBotEntity.REACTION_PET -> 12.0F * s;
			case ButterBotEntity.REACTION_POUT -> -15.0F * s;
			case ButterBotEntity.REACTION_TREAT -> 7.0F * s * (0.5F + 0.5F * Mth.sin(time * 0.9F));
			default -> 0.0F;
		};
	}

	/** Degrees the head turns aside (a pout's "hmph!"). */
	static float headTurn(int reaction, float s) {
		return reaction == ButterBotEntity.REACTION_POUT ? 50.0F * s : 0.0F;
	}

	/** Degrees the whole body turns away. */
	static float bodyTurn(int reaction, float s) {
		return reaction == ButterBotEntity.REACTION_POUT ? 35.0F * s : 0.0F;
	}

	/** How much faster than usual the tail sways. */
	static float wagSpeed(int reaction) {
		return reaction == ButterBotEntity.REACTION_POUT ? 2.6F : 2.0F;
	}

	/** How much wider than usual the tail sways. */
	static float wagSway(int reaction) {
		return reaction == ButterBotEntity.REACTION_POUT ? 1.6F : 1.0F;
	}

	private static float smooth(float x) {
		return x * x * (3.0F - 2.0F * x);
	}
}
