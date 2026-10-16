package dev.butterbot.client.render;

import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;

/** The bot's moves, applied on top of the player model's own pose. */
final class BotPoses {

	private BotPoses() {
	}

	static void apply(PlayerModel model, ButterBotRenderState state) {
		if (state.action == ButterBotEntity.ACTION_DANCE) {
			dance(model, state.ageInTicks);
		}
		float strength = state.gestureStrength;
		if (strength <= 0.0F) {
			return;
		}
		float time = Math.max(0.0F, state.gestureTime);
		switch (state.gesture) {
			case ButterBotEntity.GESTURE_STRETCH -> {
				// Both arms up and the head back, like a big yawn.
				raise(model.rightArm, strength, -2.9F, 0.25F);
				raise(model.leftArm, strength, -2.9F, -0.25F);
				model.head.xRot = Mth.lerp(strength, model.head.xRot, -0.45F);
			}
			case ButterBotEntity.GESTURE_STARTLE -> {
				raise(model.rightArm, strength, -2.4F, 0.7F);
				raise(model.leftArm, strength, -2.4F, -0.7F);
			}
			case ButterBotEntity.GESTURE_SHAKE -> {
				model.head.zRot += Mth.sin(time * 2.6F) * 0.45F * strength;
				model.body.zRot += Mth.sin(time * 2.6F + 1.0F) * 0.2F * strength;
			}
			case ButterBotEntity.GESTURE_WAVE -> raise(model.rightArm, strength, -2.8F, 0.3F + Mth.sin(time * 0.9F) * 0.45F);
			case ButterBotEntity.GESTURE_HIGH_FIVE -> raise(model.rightArm, strength, -2.2F, 0.15F);
			case ButterBotEntity.GESTURE_SPIN -> {
				// Looking back over its shoulder at the tail, arms out for balance.
				raise(model.rightArm, strength, -0.4F, 1.1F);
				raise(model.leftArm, strength, -0.4F, -1.1F);
				model.head.yRot += 0.8F * strength;
			}
			default -> {
			}
		}
	}

	/** Raises an arm to the given angles, blended in by {@code strength}. Outwards is +z for the right arm. */
	private static void raise(ModelPart arm, float strength, float xRot, float zRot) {
		arm.xRot = Mth.lerp(strength, arm.xRot, xRot);
		arm.zRot = Mth.lerp(strength, arm.zRot, zRot);
	}

	/** Nodding to the beat, arms going up in turn, a sway and little kicks. */
	private static void dance(PlayerModel model, float age) {
		float beat = age * 0.45F;
		float swing = Mth.sin(beat);
		model.head.xRot += Mth.sin(beat * 2.0F) * 0.22F;
		model.body.zRot = swing * 0.07F;
		model.rightArm.xRot = -1.5F - 1.1F * swing;
		model.rightArm.zRot = 0.25F;
		model.leftArm.xRot = -1.5F + 1.1F * swing;
		model.leftArm.zRot = -0.25F;
		model.rightLeg.xRot = Math.max(0.0F, swing) * -0.3F;
		model.leftLeg.xRot = Math.max(0.0F, -swing) * -0.3F;
	}
}
