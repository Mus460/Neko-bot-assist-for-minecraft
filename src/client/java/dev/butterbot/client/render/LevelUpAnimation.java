package dev.butterbot.client.render;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.util.Mth;

/** Curves for the level-up cheer: a hop with a twirl, perked and flicking ears, and a raised, quivering tail. */
final class LevelUpAnimation {

	/** Ticks the twirl takes; it happens during the first hop. */
	private static final float TWIRL_TICKS = 10.0F;
	private static final float HOP_TICKS = 10.0F;
	private static final float BOUNCE_TICKS = 6.0F;

	private LevelUpAnimation() {
	}

	/** 0 to 1: up quickly at the start, held, and gently back down over the last ten ticks. */
	static float strength(float time) {
		if (time < 0.0F) {
			return 0.0F;
		}
		float up = Mth.clamp(time / 4.0F, 0.0F, 1.0F);
		float down = Mth.clamp((ButterBotEntity.LEVEL_UP_ANIM_TICKS - time) / 10.0F, 0.0F, 1.0F);
		return smooth(Math.min(up, down));
	}

	/** Height of the hop in blocks: a jump with the twirl, then a small bounce. */
	static float hop(float time) {
		if (time < 0.0F) {
			return 0.0F;
		}
		if (time < HOP_TICKS) {
			return 0.45F * Mth.sin(time / HOP_TICKS * Mth.PI);
		}
		if (time < HOP_TICKS + BOUNCE_TICKS) {
			return 0.15F * Mth.sin((time - HOP_TICKS) / BOUNCE_TICKS * Mth.PI);
		}
		return 0.0F;
	}

	/** Degrees of the twirl: one full turn during the first hop. */
	static float twirl(float time) {
		if (time < 0.0F || time >= TWIRL_TICKS) {
			return 0.0F;
		}
		return 360.0F * smooth(time / TWIRL_TICKS);
	}

	private static float smooth(float x) {
		return x * x * (3.0F - 2.0F * x);
	}
}
