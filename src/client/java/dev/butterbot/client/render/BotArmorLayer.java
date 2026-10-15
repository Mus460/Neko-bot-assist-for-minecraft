package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * Draws the armor the owner gave the bot, fitted to wide or slim arms like a player's. Wraps the
 * vanilla armor layer, which can't take the bot's render state (a subclass of the player's) directly.
 */
public class BotArmorLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {
	private final HumanoidArmorLayer<AvatarRenderState, PlayerModel, PlayerModel> wide;
	private final HumanoidArmorLayer<AvatarRenderState, PlayerModel, PlayerModel> slim;

	public BotArmorLayer(RenderLayerParent<ButterBotRenderState, PlayerModel> renderer, EntityRendererProvider.Context context) {
		super(renderer);
		RenderLayerParent<AvatarRenderState, PlayerModel> parent = renderer::getModel;
		this.wide = new HumanoidArmorLayer<>(parent,
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), part -> new PlayerModel(part, false)),
				context.getEquipmentRenderer());
		this.slim = new HumanoidArmorLayer<>(parent,
				ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, context.getModelSet(), part -> new PlayerModel(part, true)),
				context.getEquipmentRenderer());
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state,
					   float yRot, float xRot) {
		(state.slim ? slim : wide).submit(poseStack, collector, lightCoords, state, yRot, xRot);
	}
}
