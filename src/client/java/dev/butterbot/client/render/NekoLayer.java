package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.butterbot.ButterBot;
import dev.butterbot.entity.BotTier;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Voxel cat ears on the head and a swaying tail on the back, tinted with the skin's hair colour.
 * Works with any skin, so every bot can be a neko. Levels show on them too: a collar with a bell and a
 * band on the tail in the tier's colour from level 5, glowing from level 15, glowing ears from level 20
 * and two tails at level 30. On a level up the ears perk up and flick and the tail stands up and quivers; a head
 * pat folds the ears outwards and quickens the tail ({@link ReactionAnimation}).
 * Glowing parts are drawn a second time as an emissive overlay (like spider eyes), so shader packs make them shine.
 */
public class NekoLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	public static final Identifier TEXTURE = ButterBot.id("textures/entity/neko_parts.png");
	private static final float PX = 1.0F / 16.0F;
	private static final int EAR_INNER_COLOR = 0xFFF4AFC2;
	private static final int COLLAR_COLOR = 0xFFC2303F;
	private static final int BELL_SLIT_COLOR = 0xFF3A2A10;
	/** The soul-fire blue a nekomata's tail tips fade into. */
	private static final int SOUL_FIRE_COLOR = 0xFF7FE8FF;
	private static final int TAIL_SEGMENTS = 8;
	private static final float TAIL_SEGMENT_LENGTH = 1.5F;
	/** The tail segment that wears the tier band. */
	private static final int BAND_SEGMENT = TAIL_SEGMENTS - 3;
	/** How far a nekomata's two tails spread apart, in degrees. */
	private static final float TWIN_SPREAD = 16.0F;
	/** Opacity of the emissive overlay on glowing parts. */
	private static final int GLOW_ALPHA = 0x99;

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
		BotTier tier = state.tier;
		int tierColor = tier.colorAt(age);
		float cheer = LevelUpAnimation.strength(state.levelUpTime);
		float cheerTime = Math.max(0.0F, state.levelUpTime);

		poseStack.pushPose();
		model.root().translateAndRotate(poseStack);
		model.head.translateAndRotate(poseStack);
		// On a level up the ears perk up and flick in turn; a pat or a pout folds them outwards.
		float twitch = earTwitch(age);
		float flick = cheer * 18.0F;
		float fold = ReactionAnimation.earFold(state.reaction, state.reactionTime);
		float rightAngle = twitch + fold + flick * Mth.sin(cheerTime * 1.3F);
		float leftAngle = twitch + fold + flick * Mth.sin(cheerTime * 1.3F + Mth.HALF_PI);
		float earScale = 1.0F + 0.25F * cheer;
		// From diamond on, the inside of the ears glows in the tier's colour.
		boolean glowingEars = tier.atLeast(BotTier.DIAMOND);
		int innerColor = glowingEars ? ARGB.srgbLerp(0.6F, EAR_INNER_COLOR, tierColor) : EAR_INNER_COLOR;
		int innerLight = glowingEars ? LightCoordsUtil.FULL_BRIGHT : lightCoords;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE), (pose, buffer) -> {
			submitEar(pose, buffer, 1, rightAngle, earScale, color, innerColor, lightCoords, innerLight, overlay, false);
			submitEar(pose, buffer, -1, leftAngle, earScale, color, innerColor, lightCoords, innerLight, overlay, false);
		});
		if (glowingEars) {
			int innerGlow = glow(innerColor);
			collector.submitCustomGeometry(poseStack, RenderTypes.eyes(TEXTURE), (pose, buffer) -> {
				submitEar(pose, buffer, 1, rightAngle, earScale, color, innerGlow, lightCoords, innerLight, overlay, true);
				submitEar(pose, buffer, -1, leftAngle, earScale, color, innerGlow, lightCoords, innerLight, overlay, true);
			});
		}
		poseStack.popPose();

		poseStack.pushPose();
		model.root().translateAndRotate(poseStack);
		model.body.translateAndRotate(poseStack);
		float grow = tier.ordinal();
		boolean legend = tier == BotTier.LEGEND;
		float reactionTime = Math.max(0.0F, state.reactionTime);
		Tail tail = new Tail(age,
				6.0F + 14.0F * Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F) + (state.working ? 4.0F : 0.0F),
				state.working ? 0.2F : 0.12F,
				ReactionAnimation.strength(state.reactionTime), reactionTime * ReactionAnimation.wagSpeed(state.reaction),
				ReactionAnimation.wagSway(state.reaction),
				cheer, cheerTime,
				TAIL_SEGMENT_LENGTH + 0.04F * grow,
				(1.0F + 0.04F * grow) * (1.0F + 0.3F * cheer),
				color,
				legend ? ARGB.srgbLerp(0.5F, color, SOUL_FIRE_COLOR) : ARGB.srgbLerp(0.35F, color, 0xFFFFFFFF),
				legend, tier != BotTier.KITTEN, tierColor, tier.glows());
		// An elytra fills the chest slot but draws nothing on the torso, so only real chestplates hide the collar.
		boolean armored = !state.chestEquipment.isEmpty() && !state.chestEquipment.has(DataComponents.GLIDER);
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE),
				(pose, buffer) -> submitBody(pose, buffer, tail, armored, false, lightCoords, overlay));
		if (tier.glows()) {
			collector.submitCustomGeometry(poseStack, RenderTypes.eyes(TEXTURE),
					(pose, buffer) -> submitBody(pose, buffer, tail, armored, true, lightCoords, overlay));
		}
		poseStack.popPose();
	}

	/**
	 * How the tail, collar and bell are drawn this frame. {@code wag} blends in the faster sway of a bond reaction,
	 * which runs on its own clock ({@code wagTime}, already sped up) so it eases in and out without a jump.
	 */
	private record Tail(float age, float sway, float swaySpeed, float wag, float wagTime, float wagSway, float cheer,
						float cheerTime, float segmentLength, float thickness, int color, int tipColor, boolean twin, boolean band,
						int bandColor, boolean glowing) {
	}

	/** Translucent copy of a colour for the emissive overlay. */
	private static int glow(int color) {
		return GLOW_ALPHA << 24 | color & 0xFFFFFF;
	}

	/**
	 * Collar and tail (or the nekomata's two tails). In the glow pass only the glowing parts are drawn: the
	 * bell, the tail band and a nekomata's soul-fire tips.
	 */
	private static void submitBody(PoseStack.Pose pose, VertexConsumer buffer, Tail tail, boolean armored, boolean glowPass,
								   int light, int overlay) {
		if (tail.band()) {
			submitCollar(pose, buffer, armored, tail.bandColor(), tail.glowing(), glowPass, light, overlay);
		}
		if (tail.twin()) {
			submitTail(pose, buffer, tail, -TWIN_SPREAD, glowPass, light, overlay);
			submitTail(pose, buffer, tail, TWIN_SPREAD, glowPass, light, overlay);
		} else {
			submitTail(pose, buffer, tail, 0.0F, glowPass, light, overlay);
		}
	}

	/** A quick flick every few seconds. */
	private static float earTwitch(float age) {
		float cycle = age % 70.0F;
		return cycle < 6.0F ? Mth.sin(cycle / 6.0F * Mth.PI) * 12.0F : 0.0F;
	}

	private static void submitEar(PoseStack.Pose pose, VertexConsumer buffer, int side, float angleDegrees, float scale,
								  int color, int innerColor, int light, int innerLight, int overlay, boolean glowPass) {
		Matrix4f matrix = new Matrix4f(pose.pose()).scale(PX);
		Matrix3f normal = new Matrix3f(pose.normal());
		// Rotate (and on a level up, grow) around the bottom of the ear so it flicks outwards.
		float pivotX = side * 2.7F;
		float angle = side * angleDegrees * Mth.DEG_TO_RAD;
		matrix.translate(pivotX, -8.3F, 0.0F).rotateZ(angle).scale(scale).translate(-pivotX, 8.3F, 0.0F);
		normal.rotateZ(angle);

		// The glow pass only draws the glowing inside of the ear.
		if (!glowPass) {
			for (float[] row : EAR_ROWS) {
				float x0 = side > 0 ? row[2] : -row[3];
				float x1 = side > 0 ? row[3] : -row[2];
				box(matrix, normal, buffer, x0, row[0], -1.0F, x1, row[1], 0.2F, color, light, overlay);
			}
		}
		float ix0 = side > 0 ? 2.4F : -3.6F;
		float ix1 = side > 0 ? 3.6F : -2.4F;
		box(matrix, normal, buffer, ix0, -9.9F, -1.15F, ix1, -8.4F, -1.0F, innerColor, innerLight, overlay);
	}

	/** A red collar with a bell in the tier's colour; over a chestplate only the bell shows, pinned on like a badge. */
	private static void submitCollar(PoseStack.Pose pose, VertexConsumer buffer, boolean armored, int bellColor, boolean glowing,
									 boolean glowPass, int light, int overlay) {
		if (glowPass && !glowing) {
			return;
		}
		Matrix4f matrix = new Matrix4f(pose.pose()).scale(PX);
		Matrix3f normal = new Matrix3f(pose.normal());
		int bell = glowPass ? glow(bellColor) : bellColor;
		int bellLight = glowing ? LightCoordsUtil.FULL_BRIGHT : light;
		if (armored) {
			box(matrix, normal, buffer, -1.0F, 3.0F, -4.1F, 1.0F, 5.0F, -3.1F, bell, bellLight, overlay);
			if (!glowPass) {
				box(matrix, normal, buffer, -0.6F, 4.4F, -4.15F, 0.6F, 4.7F, -4.1F, BELL_SLIT_COLOR, light, overlay);
			}
			return;
		}
		if (!glowPass) {
			box(matrix, normal, buffer, -4.4F, 0.2F, -2.4F, 4.4F, 1.4F, 2.4F, COLLAR_COLOR, light, overlay);
		}
		box(matrix, normal, buffer, -1.0F, 1.0F, -3.4F, 1.0F, 3.0F, -2.4F, bell, bellLight, overlay);
		if (!glowPass) {
			box(matrix, normal, buffer, -0.6F, 2.4F, -3.45F, 0.6F, 2.7F, -3.4F, BELL_SLIT_COLOR, light, overlay);
		}
	}

	/**
	 * One tail: hanging down from the hips and curling up, swaying more while walking or working. On a level
	 * up it stands up (leaning back, clear of the head) and quivers. {@code spread} turns a nekomata's two tails
	 * apart around the tail's own axis, so they stay apart whether hanging or raised; both sway in step.
	 */
	private static void submitTail(PoseStack.Pose pose, VertexConsumer buffer, Tail tail, float spread, boolean glowPass,
								   int light, int overlay) {
		Matrix4f matrix = new Matrix4f(pose.pose()).scale(PX).translate(0.0F, 10.5F, 1.6F);
		Matrix3f normal = new Matrix3f(pose.normal());
		float pitch = Mth.lerp(tail.cheer(), -20.0F, 38.0F);
		float bend = Mth.lerp(tail.cheer(), 13.0F, 5.0F);
		for (int i = 0; i < TAIL_SEGMENTS; i++) {
			float calm = Mth.sin(tail.age() * tail.swaySpeed() - i * 0.45F) * tail.sway() * (0.4F + i * 0.1F);
			if (tail.wag() > 0.0F) {
				// Petted or fed it wags twice as fast; a pout lashes it wider still.
				float wag = Mth.sin(tail.wagTime() * tail.swaySpeed() - i * 0.45F) * tail.sway() * tail.wagSway() * (0.4F + i * 0.1F);
				calm = Mth.lerp(tail.wag(), calm, wag);
			}
			float quiver = Mth.sin(tail.cheerTime() * 2.2F - i * 0.6F) * 7.0F * (0.3F + i * 0.12F);
			float yaw = Mth.lerp(tail.cheer(), calm, quiver) + (i == 0 ? spread : 0.0F);
			matrix.rotateX(pitch * Mth.DEG_TO_RAD).rotateY(yaw * Mth.DEG_TO_RAD);
			normal.rotateX(pitch * Mth.DEG_TO_RAD).rotateY(yaw * Mth.DEG_TO_RAD);
			// A nekomata's tail tips burn with soul fire.
			boolean tip = i >= TAIL_SEGMENTS - 2;
			boolean glowingTip = tip && tail.twin();
			float half = (i == TAIL_SEGMENTS - 1 ? 0.8F : 0.7F) * tail.thickness();
			if (!glowPass || glowingTip) {
				int segment = tip ? tail.tipColor() : tail.color();
				box(matrix, normal, buffer, -half, -half, 0.0F, half, half, tail.segmentLength() + 0.2F,
						glowPass ? glow(segment) : segment, glowingTip ? LightCoordsUtil.FULL_BRIGHT : light, overlay);
			}
			if (tail.band() && i == BAND_SEGMENT && (!glowPass || tail.glowing())) {
				float ring = half + 0.15F;
				box(matrix, normal, buffer, -ring, -ring, tail.segmentLength() * 0.3F, ring, ring, tail.segmentLength() * 0.75F,
						glowPass ? glow(tail.bandColor()) : tail.bandColor(), tail.glowing() ? LightCoordsUtil.FULL_BRIGHT : light,
						overlay);
			}
			matrix.translate(0.0F, 0.0F, tail.segmentLength());
			// Each segment bends a little further up, so the tail curls.
			pitch = bend;
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
		// Wound a, d, c, b so each face is front-facing from outside: entity shaders light back faces as if
		// they faced the other way.
		vertex(m, b, ax, ay, az, 0.0F, 0.0F, color, light, overlay, normal);
		vertex(m, b, dx, dy, dz, 0.0F, 1.0F, color, light, overlay, normal);
		vertex(m, b, cx, cy, cz, 1.0F, 1.0F, color, light, overlay, normal);
		vertex(m, b, bx, by, bz, 1.0F, 0.0F, color, light, overlay, normal);
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
