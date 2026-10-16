package dev.butterbot.client.render;

import dev.butterbot.ButterBot;
import dev.butterbot.entity.BotTier;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;

/**
 * The cat ears, tail (two for a nekomata), collar and bell as real model parts hung on a player skeleton. They
 * render the way armor and mob models do, which shader packs and model mods all support, and the skeleton is
 * posed from the same render state as the bot's body, so they follow its head and hips exactly. The player cubes
 * themselves are never drawn. One instance draws one colour {@link Group}; {@link NekoLayer} tints each group.
 */
final class NekoModel extends PlayerModel {

	/** Parts drawn in one colour. */
	enum Group {
		HAIR, INNER_EAR, TAIL_TIP, BAND, COLLAR, BELL, BELL_SLIT
	}

	static final int TAIL_SEGMENTS = 8;
	static final float TAIL_SEGMENT_LENGTH = 1.5F;
	/** The tail segment that wears the tier band. */
	private static final int BAND_SEGMENT = TAIL_SEGMENTS - 3;
	/** How far a nekomata's two tails spread apart, in degrees. */
	private static final float TWIN_SPREAD = 16.0F;
	/** Where the ears turn (and grow) from: their bottom, in head space. */
	private static final float EAR_PIVOT_X = 2.7F;
	private static final float EAR_PIVOT_Y = -8.3F;

	/** Ear rows from bottom to top: y0, y1, inner x, outer x (pixels, head space, outward from centre). */
	private static final float[][] EAR_ROWS = {
			{-9.3F, -8.3F, 1.2F, 4.2F},
			{-10.3F, -9.3F, 2.2F, 4.2F},
			{-11.3F, -10.3F, 3.2F, 4.2F},
	};

	/** Set once a pose failed; the parts then stay hidden instead of taking the game down. */
	private static boolean failed;

	private final Group group;
	/** Ear pivots, outer rows and pink insides; index 0 is the ear on the +x side. */
	private final ModelPart[] ears = new ModelPart[2];
	private final ModelPart[] earRows = new ModelPart[2];
	private final ModelPart[] earInsides = new ModelPart[2];
	private final ModelPart collar;
	private final ModelPart bell;
	private final ModelPart bellSlit;
	private final ModelPart badge;
	private final ModelPart badgeSlit;
	private final Tail[] tails = new Tail[2];

	/** One tail: a pitch joint and a yaw joint per segment, so each segment bends up and then sways sideways. */
	private record Tail(ModelPart[] pitch, ModelPart[] yaw, ModelPart[] segment, ModelPart band) {
	}

	NekoModel(boolean slim, Group group) {
		this(createLayer(slim).bakeRoot(), slim, group);
	}

	private NekoModel(ModelPart root, boolean slim, Group group) {
		super(root, slim);
		this.group = group;
		for (int i = 0; i < 2; i++) {
			ears[i] = head.getChild(earName(i));
			earRows[i] = ears[i].getChild("rows");
			earInsides[i] = ears[i].getChild("inside");
		}
		collar = body.getChild("neko_collar");
		bell = body.getChild("neko_bell");
		bellSlit = body.getChild("neko_bell_slit");
		badge = body.getChild("neko_badge");
		badgeSlit = body.getChild("neko_badge_slit");
		for (int t = 0; t < 2; t++) {
			ModelPart[] pitch = new ModelPart[TAIL_SEGMENTS];
			ModelPart[] yaw = new ModelPart[TAIL_SEGMENTS];
			ModelPart[] segment = new ModelPart[TAIL_SEGMENTS];
			ModelPart parent = body;
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				pitch[i] = parent.getChild(tailName(t, i));
				yaw[i] = pitch[i].getChild("yaw");
				segment[i] = yaw[i].getChild("segment");
				parent = yaw[i];
			}
			tails[t] = new Tail(pitch, yaw, segment, yaw[BAND_SEGMENT].getChild("band"));
		}
	}

	private static String earName(int index) {
		return index == 0 ? "neko_ear_plus" : "neko_ear_minus";
	}

	private static String tailName(int tail, int segment) {
		return "neko_tail" + tail + "_" + segment;
	}

	/** A box from (x0, y0, z0) to (x1, y1, z1) in pixels. The texture is plain white, so only the tint matters. */
	private static CubeListBuilder box(float x0, float y0, float z0, float x1, float y1, float z1) {
		return CubeListBuilder.create().addBox(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
	}

	private static LayerDefinition createLayer(boolean slim) {
		MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, slim);
		PartDefinition head = mesh.getRoot().getChild("head");
		PartDefinition body = mesh.getRoot().getChild("body");

		for (int i = 0; i < 2; i++) {
			int side = i == 0 ? 1 : -1;
			float pivotX = side * EAR_PIVOT_X;
			PartDefinition ear = head.addOrReplaceChild(earName(i), CubeListBuilder.create(), PartPose.offset(pivotX, EAR_PIVOT_Y, 0.0F));
			CubeListBuilder rows = CubeListBuilder.create();
			for (float[] row : EAR_ROWS) {
				float x0 = side > 0 ? row[2] : -row[3];
				float x1 = side > 0 ? row[3] : -row[2];
				rows.addBox(x0 - pivotX, row[0] - EAR_PIVOT_Y, -1.0F, x1 - x0, row[1] - row[0], 1.2F);
			}
			ear.addOrReplaceChild("rows", rows, PartPose.ZERO);
			float insideX0 = (side > 0 ? 2.4F : -3.6F) - pivotX;
			ear.addOrReplaceChild("inside", box(insideX0, -9.9F - EAR_PIVOT_Y, -1.15F, insideX0 + 1.2F, -8.4F - EAR_PIVOT_Y, -1.0F),
					PartPose.ZERO);
		}

		// Collar and bell; over a chestplate only the bell shows, pinned on its front like a badge.
		body.addOrReplaceChild("neko_collar", box(-4.4F, 0.2F, -2.4F, 4.4F, 1.4F, 2.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_bell", box(-1.0F, 1.0F, -3.4F, 1.0F, 3.0F, -2.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_bell_slit", box(-0.6F, 2.4F, -3.45F, 0.6F, 2.7F, -3.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_badge", box(-1.0F, 3.0F, -4.1F, 1.0F, 5.0F, -3.1F), PartPose.ZERO);
		body.addOrReplaceChild("neko_badge_slit", box(-0.6F, 4.4F, -4.15F, 0.6F, 4.7F, -4.1F), PartPose.ZERO);

		for (int t = 0; t < 2; t++) {
			PartDefinition parent = body;
			PartPose joint = PartPose.offset(0.0F, 10.5F, 1.6F);
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				PartDefinition pitch = parent.addOrReplaceChild(tailName(t, i), CubeListBuilder.create(), joint);
				PartDefinition yaw = pitch.addOrReplaceChild("yaw", CubeListBuilder.create(), PartPose.ZERO);
				float half = i == TAIL_SEGMENTS - 1 ? 0.8F : 0.7F;
				yaw.addOrReplaceChild("segment", box(-half, -half, 0.0F, half, half, TAIL_SEGMENT_LENGTH + 0.2F), PartPose.ZERO);
				if (i == BAND_SEGMENT) {
					float ring = half + 0.15F;
					yaw.addOrReplaceChild("band", box(-ring, -ring, TAIL_SEGMENT_LENGTH * 0.3F, ring, ring, TAIL_SEGMENT_LENGTH * 0.75F),
							PartPose.ZERO);
				}
				parent = yaw;
				joint = PartPose.offset(0.0F, 0.0F, TAIL_SEGMENT_LENGTH);
			}
		}
		return LayerDefinition.create(mesh, 64, 64);
	}

	@Override
	public void setupAnim(AvatarRenderState avatar) {
		super.setupAnim(avatar);
		// Only the neko parts are drawn; the head and body stay as joints for them.
		head.skipDraw = true;
		body.skipDraw = true;
		hat.visible = false;
		jacket.visible = false;
		rightArm.visible = false;
		leftArm.visible = false;
		rightLeg.visible = false;
		leftLeg.visible = false;
		if (!(avatar instanceof ButterBotRenderState state)) {
			showGroup(false, false, false);
			return;
		}
		BotTier tier = state.tier;
		boolean legend = tier == BotTier.LEGEND;
		// An elytra fills the chest slot but draws nothing on the torso, so only real chestplates hide the collar.
		boolean armored = !state.chestEquipment.isEmpty() && !state.chestEquipment.has(DataComponents.GLIDER);
		try {
			showGroup(tier != BotTier.KITTEN, armored, legend);
			poseEars(state);
			poseTails(state, legend);
		} catch (RuntimeException exception) {
			// Ears and tail are looks only: never let them crash the game.
			head.visible = false;
			body.visible = false;
			if (!failed) {
				failed = true;
				ButterBot.LOGGER.error("Butter Bot neko parts could not be posed, hiding them", exception);
			}
		}
	}

	/** Shows only this instance's colour group (the collar and band from bronze on, the second tail at level 30). */
	private void showGroup(boolean adorned, boolean armored, boolean legend) {
		for (int i = 0; i < 2; i++) {
			earRows[i].visible = group == Group.HAIR;
			earInsides[i].visible = group == Group.INNER_EAR;
		}
		collar.visible = adorned && !armored && group == Group.COLLAR;
		bell.visible = adorned && !armored && group == Group.BELL;
		bellSlit.visible = adorned && !armored && group == Group.BELL_SLIT;
		badge.visible = adorned && armored && group == Group.BELL;
		badgeSlit.visible = adorned && armored && group == Group.BELL_SLIT;
		for (int t = 0; t < 2; t++) {
			Tail tail = tails[t];
			tail.pitch()[0].visible = t == 0 || legend;
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				boolean tip = i >= TAIL_SEGMENTS - 2;
				tail.segment()[i].visible = group == (tip ? Group.TAIL_TIP : Group.HAIR);
			}
			tail.band().visible = adorned && group == Group.BAND;
		}
	}

	/** Ears twitch now and then, perk up and flick in turn on a level up, and fold out when petted or pouting. */
	private void poseEars(ButterBotRenderState state) {
		float cheer = LevelUpAnimation.strength(state.levelUpTime);
		float cheerTime = Math.max(0.0F, state.levelUpTime);
		float twitch = earTwitch(state.ageInTicks);
		float fold = ReactionAnimation.earFold(state.reaction, state.reactionTime, state.reactionStrength);
		float flick = cheer * 18.0F;
		float scale = 1.0F + 0.25F * cheer;
		for (int i = 0; i < 2; i++) {
			int side = i == 0 ? 1 : -1;
			float angle = twitch + fold + flick * Mth.sin(cheerTime * 1.3F + (i == 0 ? 0.0F : Mth.HALF_PI));
			ModelPart ear = ears[i];
			ear.zRot = side * angle * Mth.DEG_TO_RAD;
			ear.xScale = scale;
			ear.yScale = scale;
			ear.zScale = scale;
		}
	}

	/** A quick flick every few seconds. */
	private static float earTwitch(float age) {
		float cycle = age % 70.0F;
		return cycle < 6.0F ? Mth.sin(cycle / 6.0F * Mth.PI) * 12.0F : 0.0F;
	}

	/**
	 * The tail hangs down from the hips and curls up, swaying more while walking or working, faster when petted.
	 * On a level up it stands up (leaning back, clear of the head) and quivers. Higher tiers grow it longer and
	 * fluffier; a nekomata's two tails turn apart around their own axis and sway in step.
	 */
	private void poseTails(ButterBotRenderState state, boolean legend) {
		float cheer = LevelUpAnimation.strength(state.levelUpTime);
		float cheerTime = Math.max(0.0F, state.levelUpTime);
		float grow = state.tier.ordinal();
		float length = 1.0F + 0.04F * grow / TAIL_SEGMENT_LENGTH;
		float thickness = (1.0F + 0.04F * grow) * (1.0F + 0.3F * cheer);
		float sway = 6.0F + 14.0F * Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F) + (state.working ? 4.0F : 0.0F);
		float swaySpeed = state.working ? 0.2F : 0.12F;
		float wag = state.reactionStrength;
		float wagTime = Math.max(0.0F, state.reactionTime) * ReactionAnimation.wagSpeed(state.reaction);
		float wagSway = ReactionAnimation.wagSway(state.reaction);
		for (int t = 0; t < 2; t++) {
			Tail tail = tails[t];
			float spread = legend ? (t == 0 ? -TWIN_SPREAD : TWIN_SPREAD) : 0.0F;
			float pitch = Mth.lerp(cheer, -20.0F, 38.0F);
			float bend = Mth.lerp(cheer, 13.0F, 5.0F);
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				float calm = Mth.sin(state.ageInTicks * swaySpeed - i * 0.45F) * sway * (0.4F + i * 0.1F);
				if (wag > 0.0F) {
					// Petted or fed it wags twice as fast; a pout lashes it wider still.
					float wagging = Mth.sin(wagTime * swaySpeed - i * 0.45F) * sway * wagSway * (0.4F + i * 0.1F);
					calm = Mth.lerp(wag, calm, wagging);
				}
				float quiver = Mth.sin(cheerTime * 2.2F - i * 0.6F) * 7.0F * (0.3F + i * 0.12F);
				float yaw = Mth.lerp(cheer, calm, quiver) + (i == 0 ? spread : 0.0F);
				tail.pitch()[i].xRot = pitch * Mth.DEG_TO_RAD;
				tail.yaw()[i].yRot = yaw * Mth.DEG_TO_RAD;
				if (i > 0) {
					tail.pitch()[i].z = TAIL_SEGMENT_LENGTH * length;
				}
				ModelPart segment = tail.segment()[i];
				segment.xScale = thickness;
				segment.yScale = thickness;
				segment.zScale = length;
				if (i == BAND_SEGMENT) {
					tail.band().xScale = thickness;
					tail.band().yScale = thickness;
					tail.band().zScale = length;
				}
				// Each segment bends a little further up, so the tail curls.
				pitch = bend;
			}
		}
	}
}
