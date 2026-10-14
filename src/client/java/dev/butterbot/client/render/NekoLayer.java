package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.butterbot.ButterBot;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Voxel cat ears on the head and a swaying tail on the back, tinted with the skin's hair colour.
 * Works with any skin, so every bot can be a neko.
 */
public class NekoLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	public static final Identifier TEXTURE = ButterBot.id("textures/entity/neko_parts.png");
	private static final float PX = 1.0F / 16.0F;
	private static final int EAR_INNER_COLOR = 0xFFF4AFC2;
	private static final int TAIL_SEGMENTS = 8;
	private static final float TAIL_SEGMENT_LENGTH = 1.5F;

	/** Ear rows from bottom to top: y0, y1, inner x, outer x (pixels, head space, outward from centre). */
	private static final float[][] EAR_ROWS = {
			{-9.3F, -8.3F, 1.2F, 4.2F},
			{-10.3F, -9.3F, 2.2F, 4.2F},
			{-11.3F, -10.3F, 3.2F, 4.2F},
	};

	public NekoLayer(RenderLayerParent<ButterBotRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state,
					   float yRot, float xRot) {
		if (!state.neko || state.isInvisible) {
			return;
		}
		PlayerModel model = getParentModel();
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
		int color = state.nekoColor;
		float age = state.ageInTicks;

		poseStack.pushPose();
		model.root().translateAndRotate(poseStack);
		model.head.translateAndRotate(poseStack);
		float twitch = earTwitch(age);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE), (pose, buffer) -> {
			submitEar(pose, buffer, 1, twitch, color, lightCoords, overlay);
			submitEar(pose, buffer, -1, twitch, color, lightCoords, overlay);
		});
		poseStack.popPose();

		poseStack.pushPose();
		model.root().translateAndRotate(poseStack);
		model.body.translateAndRotate(poseStack);
		float swayAmount = 6.0F + 14.0F * Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F) + (state.working ? 4.0F : 0.0F);
		float swaySpeed = state.working ? 0.2F : 0.12F;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
				(pose, buffer) -> submitTail(pose, buffer, age, swayAmount, swaySpeed, color, lightCoords, overlay));
		poseStack.popPose();
	}

	/** A quick flick every few seconds. */
	private static float earTwitch(float age) {
		float cycle = age % 70.0F;
		return cycle < 6.0F ? Mth.sin(cycle / 6.0F * Mth.PI) * 12.0F : 0.0F;
	}

	private static void submitEar(PoseStack.Pose pose, VertexConsumer buffer, int side, float twitchDegrees,
								  int color, int light, int overlay) {
		Matrix4f matrix = new Matrix4f(pose.pose()).scale(PX);
		Matrix3f normal = new Matrix3f(pose.normal());
		// Rotate around the bottom of the ear so it flicks outwards.
		float pivotX = side * 2.7F;
		float angle = side * twitchDegrees * Mth.DEG_TO_RAD;
		matrix.translate(pivotX, -8.3F, 0.0F).rotateZ(angle).translate(-pivotX, 8.3F, 0.0F);
		normal.rotateZ(angle);

		for (float[] row : EAR_ROWS) {
			float x0 = side > 0 ? row[2] : -row[3];
			float x1 = side > 0 ? row[3] : -row[2];
			box(matrix, normal, buffer, x0, row[0], -1.0F, x1, row[1], 0.2F, color, light, overlay);
		}
		float ix0 = side > 0 ? 2.4F : -3.6F;
		float ix1 = side > 0 ? 3.6F : -2.4F;
		box(matrix, normal, buffer, ix0, -9.9F, -1.15F, ix1, -8.4F, -1.0F, EAR_INNER_COLOR, light, overlay);
	}

	private static void submitTail(PoseStack.Pose pose, VertexConsumer buffer, float age, float swayAmount, float swaySpeed,
								   int color, int light, int overlay) {
		Matrix4f matrix = new Matrix4f(pose.pose()).scale(PX).translate(0.0F, 10.5F, 1.6F);
		Matrix3f normal = new Matrix3f(pose.normal());
		int tipColor = ARGB.srgbLerp(0.35F, color, 0xFFFFFFFF);
		float pitch = -20.0F;
		for (int i = 0; i < TAIL_SEGMENTS; i++) {
			float yaw = Mth.sin(age * swaySpeed - i * 0.45F) * swayAmount * (0.4F + i * 0.1F);
			matrix.rotateX(pitch * Mth.DEG_TO_RAD).rotateY(yaw * Mth.DEG_TO_RAD);
			normal.rotateX(pitch * Mth.DEG_TO_RAD).rotateY(yaw * Mth.DEG_TO_RAD);
			float half = i == TAIL_SEGMENTS - 1 ? 0.8F : 0.7F;
			box(matrix, normal, buffer, -half, -half, 0.0F, half, half, TAIL_SEGMENT_LENGTH + 0.2F,
					i >= TAIL_SEGMENTS - 2 ? tipColor : color, light, overlay);
			matrix.translate(0.0F, 0.0F, TAIL_SEGMENT_LENGTH);
			// Each segment bends a little further up, so the tail curls.
			pitch = 13.0F;
		}
	}

	/** Emits an axis-aligned box in pixel units. The texture is plain white, so the colour does the work. */
	private static void box(Matrix4f m, Matrix3f n, VertexConsumer b, float x0, float y0, float z0, float x1, float y1, float z1,
							int color, int light, int overlay) {
		// -Z (front)
		quad(m, n, b, 0, 0, -1, color, light, overlay, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
		// +Z (back)
		quad(m, n, b, 0, 0, 1, color, light, overlay, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1);
		// -X
		quad(m, n, b, -1, 0, 0, color, light, overlay, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1);
		// +X
		quad(m, n, b, 1, 0, 0, color, light, overlay, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
		// -Y (top in model space)
		quad(m, n, b, 0, -1, 0, color, light, overlay, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
		// +Y (bottom in model space)
		quad(m, n, b, 0, 1, 0, color, light, overlay, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
	}

	private static void quad(Matrix4f m, Matrix3f n, VertexConsumer b, float nx, float ny, float nz, int color, int light,
							 int overlay, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy,
							 float cz, float dx, float dy, float dz) {
		Vector3f normal = n.transform(new Vector3f(nx, ny, nz)).normalize();
		vertex(m, b, ax, ay, az, 0.0F, 0.0F, color, light, overlay, normal);
		vertex(m, b, bx, by, bz, 1.0F, 0.0F, color, light, overlay, normal);
		vertex(m, b, cx, cy, cz, 1.0F, 1.0F, color, light, overlay, normal);
		vertex(m, b, dx, dy, dz, 0.0F, 1.0F, color, light, overlay, normal);
	}

	private static void vertex(Matrix4f m, VertexConsumer b, float x, float y, float z, float u, float v, int color,
							   int light, int overlay, Vector3f normal) {
		b.addVertex(m, x, y, z)
				.setColor(color)
				.setUv(u, v)
				.setOverlay(overlay)
				.setLight(light)
				.setNormal(normal.x(), normal.y(), normal.z());
	}
}
