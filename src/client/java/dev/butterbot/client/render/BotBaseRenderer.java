package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.butterbot.ButterBot;
import dev.butterbot.block.BotBaseBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/** Draws the Bot Base OBJ model (the block itself has an invisible render shape). */
public class BotBaseRenderer implements BlockEntityRenderer<BotBaseBlockEntity, BlockEntityRenderState> {

	public static final Identifier MODEL = ButterBot.id("obj/bot_base.obj");
	public static final Identifier TEXTURE = ButterBot.id("textures/entity/bot_base.png");

	private final ObjMesh mesh;

	public BotBaseRenderer(BlockEntityRendererProvider.Context context) {
		this.mesh = ObjMesh.load(MODEL);
	}

	@Override
	public BlockEntityRenderState createRenderState() {
		return new BlockEntityRenderState();
	}

	@Override
	public void submit(BlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
				(pose, buffer) -> mesh.render(pose, buffer, light, OverlayTexture.NO_OVERLAY, -1));
	}
}
