package dev.butterbot.client.render;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * The player model with the bot's own moves on top: dancing to a jukebox, stretching, waving, a high five, jumping
 * at thunder and shaking itself dry ({@link BotPoses}). The body, the armor over it and the neko parts all use it,
 * so they move together.
 */
public class BotModel extends PlayerModel {

	public BotModel(ModelPart root, boolean slim) {
		super(root, slim);
	}

	@Override
	public void setupAnim(AvatarRenderState state) {
		super.setupAnim(state);
		if (state instanceof ButterBotRenderState bot) {
			BotPoses.apply(this, bot);
		}
	}
}
