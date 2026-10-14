package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Draws the items in the bot's hands. Same placement as the vanilla item-in-hand layer, which can't
 * be used directly because the bot's render state is a subclass of the player's.
 */
public class BotItemInHandLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	public BotItemInHandLayer(RenderLayerParent<ButterBotRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state,
					   float yRot, float xRot) {
		submitArm(state, state.rightHandItemState, HumanoidArm.RIGHT, poseStack, collector, lightCoords);
		submitArm(state, state.leftHandItemState, HumanoidArm.LEFT, poseStack, collector, lightCoords);
	}

	private void submitArm(ButterBotRenderState state, ItemStackRenderState item, HumanoidArm arm, PoseStack poseStack,
						   SubmitNodeCollector collector, int lightCoords) {
		if (item.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		getParentModel().translateToHand(state, arm, poseStack);
		poseStack.rotateDegrees(Axis.XP, -90.0F);
		poseStack.rotateDegrees(Axis.YP, 180.0F);
		boolean left = arm == HumanoidArm.LEFT;
		poseStack.translate((left ? -1 : 1) / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
		item.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		poseStack.popPose();
	}
}
