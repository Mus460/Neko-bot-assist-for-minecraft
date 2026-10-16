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

import java.util.ArrayList;
import java.util.List;

/**
 * The cat ears, bushy tail (two for a nekomata) and everything a bot wears as real model parts hung on a player
 * skeleton: collar and bell, tail bow, ear fluff, tiara, spirit orbs (earned with levels), a scarf in the snow,
 * sunglasses in the desert sun, an umbrella in the rain, and eyelids for blinking. They render the way armor and
 * mob models do, which shader packs and model mods all support, and the skeleton is posed from the same render
 * state as the bot's body, so they follow its head and hips exactly. The player cubes themselves are never drawn.
 * One instance draws one colour {@link Group}; {@link NekoLayer} tints each group.
 */
final class NekoModel extends PlayerModel {

	/** Parts drawn in one colour. */
	enum Group {
		HAIR, FUR, INNER_EAR, EAR_FLUFF, TAIL_TIP, BAND, COLLAR, BELL, BELL_SLIT, BOW, BOW_KNOT, CROWN, GEM, ORB, EYELID,
		GLASSES, SCARF, UMBRELLA, UMBRELLA_POLE
	}

	static final int TAIL_SEGMENTS = 8;
	static final float TAIL_SEGMENT_LENGTH = 1.5F;
	/** Half the thickness of each tail segment: slim at the hips, bushy in the middle, rounded at the tip. */
	private static final float[] TAIL_HALF = {0.8F, 0.95F, 1.1F, 1.25F, 1.35F, 1.4F, 1.3F, 1.05F};
	/** The fur layer is the same box turned 45 degrees around the tail, a little smaller, so the tail looks round. */
	private static final float FUR_SCALE = 0.88F;
	/** The tail segment that wears the tier band, and the one with the bow. */
	private static final int BAND_SEGMENT = TAIL_SEGMENTS - 3;
	private static final int BOW_SEGMENT = 1;
	/** How far a nekomata's two tails spread apart, in degrees. */
	private static final float TWIN_SPREAD = 16.0F;
	/** Where the ears turn (and grow) from: their bottom, in head space. */
	private static final float EAR_PIVOT_X = 2.7F;
	private static final float EAR_PIVOT_Y = -8.3F;
	private static final int MAX_ORBS = 3;
	/** Spirit orbs circle at this distance around the bot, in pixels. */
	private static final float ORB_RADIUS = 12.0F;

	/** Ear rows from bottom to top: y0, y1, inner x, outer x (pixels, head space, outward from centre). */
	private static final float[][] EAR_ROWS = {
			{-9.3F, -8.3F, 1.2F, 4.2F},
			{-10.3F, -9.3F, 2.2F, 4.2F},
			{-11.3F, -10.3F, 3.2F, 4.2F},
	};

	/** Set once a pose failed; the parts then stay hidden instead of taking the game down. */
	private static boolean failed;

	private final Group group;
	/** Ear pivots and their parts; index 0 is the ear on the +x side. */
	private final ModelPart[] ears = new ModelPart[2];
	private final ModelPart[] earRows = new ModelPart[2];
	private final ModelPart[] earInsides = new ModelPart[2];
	private final ModelPart[] earFluff = new ModelPart[2];
	/** Eyelids, hinged at their top edge so they can close fully (blink) or halfway (a happy squint). */
	private final ModelPart[] lids = new ModelPart[2];
	private final ModelPart crown;
	private final ModelPart gem;
	private final ModelPart glasses;
	private final ModelPart collar;
	private final ModelPart bell;
	private final ModelPart bellSlit;
	private final ModelPart badge;
	private final ModelPart badgeSlit;
	private final ModelPart scarf;
	private final ModelPart armoredScarf;
	private final ModelPart umbrellaPole;
	private final ModelPart umbrella;
	private final ModelPart[] orbs = new ModelPart[MAX_ORBS];
	private final Tail[] tails = new Tail[2];

	/**
	 * One tail: a pitch joint and a yaw joint per segment, so each segment bends up and then sways sideways; each
	 * segment has a core and a fur layer. The band sits on {@link #BAND_SEGMENT}, the bow on {@link #BOW_SEGMENT}.
	 */
	private record Tail(ModelPart[] pitch, ModelPart[] yaw, ModelPart[] segment, ModelPart[] fur, List<ModelPart> band,
						ModelPart bow, List<ModelPart> bowLoops, ModelPart bowKnot) {
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
			earFluff[i] = ears[i].getChild("fluff");
			lids[i] = head.getChild(i == 0 ? "neko_lid_plus" : "neko_lid_minus");
		}
		crown = head.getChild("neko_crown");
		gem = head.getChild("neko_gem");
		glasses = head.getChild("neko_glasses");
		collar = body.getChild("neko_collar");
		bell = body.getChild("neko_bell");
		bellSlit = body.getChild("neko_bell_slit");
		badge = body.getChild("neko_badge");
		badgeSlit = body.getChild("neko_badge_slit");
		scarf = body.getChild("neko_scarf");
		armoredScarf = body.getChild("neko_scarf_armored");
		umbrellaPole = body.getChild("neko_umbrella_pole");
		umbrella = body.getChild("neko_umbrella");
		for (int i = 0; i < MAX_ORBS; i++) {
			orbs[i] = root.getChild("neko_orb" + i);
		}
		for (int t = 0; t < 2; t++) {
			ModelPart[] pitch = new ModelPart[TAIL_SEGMENTS];
			ModelPart[] yaw = new ModelPart[TAIL_SEGMENTS];
			ModelPart[] segment = new ModelPart[TAIL_SEGMENTS];
			ModelPart[] fur = new ModelPart[TAIL_SEGMENTS];
			ModelPart parent = body;
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				pitch[i] = parent.getChild(tailName(t, i));
				yaw[i] = pitch[i].getChild("yaw");
				segment[i] = yaw[i].getChild("segment");
				fur[i] = yaw[i].getChild("fur");
				parent = yaw[i];
			}
			ModelPart bow = yaw[BOW_SEGMENT].getChild("bow");
			tails[t] = new Tail(pitch, yaw, segment, fur,
					List.of(yaw[BAND_SEGMENT].getChild("band"), yaw[BAND_SEGMENT].getChild("band_fur")),
					bow, List.of(bow.getChild("loop_plus"), bow.getChild("loop_minus")), bow.getChild("knot"));
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
		PartDefinition root = mesh.getRoot();
		PartDefinition head = root.getChild("head");
		PartDefinition body = root.getChild("body");

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
			// A soft white tuft at the bottom of the ear, in front of the pink.
			float fluffX0 = (side > 0 ? 2.3F : -3.5F) - pivotX;
			ear.addOrReplaceChild("fluff", box(fluffX0, -9.5F - EAR_PIVOT_Y, -1.4F, fluffX0 + 1.2F, -8.3F - EAR_PIVOT_Y, -1.15F),
					PartPose.ZERO);
			// Eyelids over the usual eye rows (face pixels 4 and 5), hinged at the top.
			float lidX0 = side > 0 ? 1.0F : -3.0F;
			head.addOrReplaceChild(i == 0 ? "neko_lid_plus" : "neko_lid_minus", box(lidX0, 0.0F, -4.07F, lidX0 + 2.0F, 2.0F, -4.01F),
					PartPose.offset(0.0F, -4.0F, 0.0F));
		}
		// A small tiara between the ears, with a gem at the front.
		head.addOrReplaceChild("neko_crown", box(-1.1F, -9.0F, -1.0F, 1.1F, -8.2F, 1.0F)
				.addBox(-1.1F, -9.9F, -1.0F, 0.5F, 0.9F, 0.5F)
				.addBox(-0.3F, -10.4F, -1.0F, 0.6F, 1.4F, 0.5F)
				.addBox(0.6F, -9.9F, -1.0F, 0.5F, 0.9F, 0.5F), PartPose.ZERO);
		head.addOrReplaceChild("neko_gem", box(-0.4F, -8.9F, -1.25F, 0.4F, -8.3F, -1.0F), PartPose.ZERO);
		// Sunglasses: two dark lenses, a bridge and the arms, just in front of the hat layer.
		head.addOrReplaceChild("neko_glasses", box(0.4F, -4.7F, -4.75F, 3.5F, -2.9F, -4.55F)
				.addBox(-3.5F, -4.7F, -4.75F, 3.1F, 1.8F, 0.2F)
				.addBox(-0.4F, -4.5F, -4.75F, 0.8F, 0.4F, 0.2F)
				.addBox(4.55F, -4.5F, -4.75F, 0.2F, 0.4F, 3.6F)
				.addBox(-4.75F, -4.5F, -4.75F, 0.2F, 0.4F, 3.6F), PartPose.ZERO);

		// Collar and bell; over a chestplate only the bell shows, pinned on its front like a badge.
		body.addOrReplaceChild("neko_collar", box(-4.4F, 0.2F, -2.4F, 4.4F, 1.4F, 2.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_bell", box(-1.0F, 1.0F, -3.4F, 1.0F, 3.0F, -2.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_bell_slit", box(-0.6F, 2.4F, -3.45F, 0.6F, 2.7F, -3.4F), PartPose.ZERO);
		body.addOrReplaceChild("neko_badge", box(-1.0F, 3.0F, -4.1F, 1.0F, 5.0F, -3.1F), PartPose.ZERO);
		body.addOrReplaceChild("neko_badge_slit", box(-0.6F, 4.4F, -4.15F, 0.6F, 4.7F, -4.1F), PartPose.ZERO);
		// A knitted scarf with one end hanging in front (bigger over a chestplate).
		body.addOrReplaceChild("neko_scarf", box(-4.6F, -0.4F, -2.7F, 4.6F, 1.8F, 2.7F)
				.addBox(-3.4F, 1.8F, -2.95F, 1.6F, 4.6F, 0.6F), PartPose.ZERO);
		body.addOrReplaceChild("neko_scarf_armored", box(-5.3F, -1.4F, -3.3F, 5.3F, 0.8F, 3.3F)
				.addBox(-3.9F, 0.8F, -3.6F, 1.6F, 5.0F, 0.6F), PartPose.ZERO);
		// An umbrella held up in front of the right hand: a pole and a three-step canopy over the head.
		body.addOrReplaceChild("neko_umbrella_pole", box(-5.8F, -17.0F, -3.5F, -5.2F, 11.0F, -2.9F), PartPose.ZERO);
		body.addOrReplaceChild("neko_umbrella", box(-12.5F, -17.6F, -10.2F, 1.9F, -16.6F, 4.2F)
				.addBox(-10.0F, -18.4F, -7.7F, 9.4F, 0.8F, 9.4F)
				.addBox(-6.3F, -19.2F, -4.0F, 2.0F, 0.8F, 2.0F), PartPose.ZERO);

		for (int i = 0; i < MAX_ORBS; i++) {
			root.addOrReplaceChild("neko_orb" + i, box(-0.9F, -0.9F, -0.9F, 0.9F, 0.9F, 0.9F), PartPose.ZERO);
		}

		for (int t = 0; t < 2; t++) {
			PartDefinition parent = body;
			PartPose joint = PartPose.offset(0.0F, 10.5F, 1.6F);
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				PartDefinition pitch = parent.addOrReplaceChild(tailName(t, i), CubeListBuilder.create(), joint);
				PartDefinition yaw = pitch.addOrReplaceChild("yaw", CubeListBuilder.create(), PartPose.ZERO);
				float half = TAIL_HALF[i];
				float length = TAIL_SEGMENT_LENGTH + 0.2F;
				yaw.addOrReplaceChild("segment", box(-half, -half, 0.0F, half, half, length), PartPose.ZERO);
				float fur = half * FUR_SCALE;
				yaw.addOrReplaceChild("fur", box(-fur, -fur, 0.05F, fur, fur, length - 0.05F), PartPose.rotation(0.0F, 0.0F, Mth.PI / 4.0F));
				if (i == BAND_SEGMENT) {
					float ring = half + 0.15F;
					float ringFur = ring * FUR_SCALE * 1.1F;
					float z0 = TAIL_SEGMENT_LENGTH * 0.3F;
					float z1 = TAIL_SEGMENT_LENGTH * 0.75F;
					yaw.addOrReplaceChild("band", box(-ring, -ring, z0, ring, ring, z1), PartPose.ZERO);
					yaw.addOrReplaceChild("band_fur", box(-ringFur, -ringFur, z0, ringFur, ringFur, z1),
							PartPose.rotation(0.0F, 0.0F, Mth.PI / 4.0F));
				}
				if (i == BOW_SEGMENT) {
					// A bow on top of the tail near the hips: a knot and two loops tilted outwards.
					PartDefinition bow = yaw.addOrReplaceChild("bow", CubeListBuilder.create(), PartPose.offset(0.0F, -half, 0.8F));
					bow.addOrReplaceChild("knot", box(-0.5F, -0.9F, -0.4F, 0.5F, 0.1F, 0.4F), PartPose.ZERO);
					bow.addOrReplaceChild("loop_plus", box(0.0F, -0.6F, -0.35F, 1.9F, 0.6F, 0.35F),
							PartPose.offsetAndRotation(0.4F, -0.4F, 0.0F, 0.0F, 0.0F, -0.35F));
					bow.addOrReplaceChild("loop_minus", box(-1.9F, -0.6F, -0.35F, 0.0F, 0.6F, 0.35F),
							PartPose.offsetAndRotation(-0.4F, -0.4F, 0.0F, 0.0F, 0.0F, 0.35F));
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
			head.visible = false;
			body.visible = false;
			return;
		}
		try {
			showGroup(state);
			poseEars(state);
			poseLids(state);
			poseTails(state);
			poseOrbs(state);
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

	/**
	 * Shows only this instance's colour group, and only what the bot has earned or the weather calls for: collar
	 * and tail band from level 5, the bow from 10, ear fluff from 15, the tiara from 20 (not under a helmet), spirit
	 * orbs from 25 (three and a second tail at 30).
	 */
	private void showGroup(ButterBotRenderState state) {
		BotTier tier = state.tier;
		boolean legend = tier == BotTier.LEGEND;
		boolean adorned = tier.atLeast(BotTier.BRONZE);
		// An elytra fills the chest slot but draws nothing on the torso, so only real chestplates hide the collar.
		boolean armored = !state.chestEquipment.isEmpty() && !state.chestEquipment.has(DataComponents.GLIDER);
		boolean collared = adorned && !state.scarf;
		boolean crowned = tier.atLeast(BotTier.DIAMOND) && state.headEquipment.isEmpty();
		int orbCount = legend ? 3 : tier.atLeast(BotTier.AMETHYST) ? 2 : 0;

		for (int i = 0; i < 2; i++) {
			earRows[i].visible = group == Group.HAIR;
			earInsides[i].visible = group == Group.INNER_EAR;
			earFluff[i].visible = group == Group.EAR_FLUFF && tier.atLeast(BotTier.GOLD);
			lids[i].visible = group == Group.EYELID && state.faceColor != 0 && state.lidsClosed > 0.0F && !state.sunglasses;
		}
		crown.visible = group == Group.CROWN && crowned;
		gem.visible = group == Group.GEM && crowned;
		glasses.visible = group == Group.GLASSES && state.sunglasses;
		collar.visible = group == Group.COLLAR && collared && !armored;
		bell.visible = group == Group.BELL && collared && !armored;
		bellSlit.visible = group == Group.BELL_SLIT && collared && !armored;
		badge.visible = group == Group.BELL && collared && armored;
		badgeSlit.visible = group == Group.BELL_SLIT && collared && armored;
		scarf.visible = group == Group.SCARF && state.scarf && !armored;
		armoredScarf.visible = group == Group.SCARF && state.scarf && armored;
		umbrellaPole.visible = group == Group.UMBRELLA_POLE && state.umbrella;
		umbrella.visible = group == Group.UMBRELLA && state.umbrella;
		for (int i = 0; i < MAX_ORBS; i++) {
			orbs[i].visible = group == Group.ORB && i < orbCount;
		}
		for (int t = 0; t < 2; t++) {
			Tail tail = tails[t];
			tail.pitch()[0].visible = t == 0 || legend;
			for (int i = 0; i < TAIL_SEGMENTS; i++) {
				boolean tip = i >= TAIL_SEGMENTS - 2;
				tail.segment()[i].visible = group == (tip ? Group.TAIL_TIP : Group.HAIR);
				tail.fur()[i].visible = group == (tip ? Group.TAIL_TIP : Group.FUR);
			}
			for (ModelPart band : tail.band()) {
				band.visible = group == Group.BAND && adorned;
			}
			boolean bowed = tier.atLeast(BotTier.SILVER);
			for (ModelPart loop : tail.bowLoops()) {
				loop.visible = group == Group.BOW && bowed;
			}
			tail.bowKnot().visible = group == Group.BOW_KNOT && bowed;
		}
	}

	/**
	 * Ears twitch now and then, perk up and flick in turn on a level up, and fold out when petted or pouting. Their
	 * mood shows too: perked when the owner is close, flat back when scared or fighting, drooping when hurt badly.
	 */
	private void poseEars(ButterBotRenderState state) {
		float cheer = LevelUpAnimation.strength(state.levelUpTime);
		float cheerTime = Math.max(0.0F, state.levelUpTime);
		float twitch = earTwitch(state.ageInTicks);
		float fold = ReactionAnimation.earFold(state.reaction, state.reactionTime, state.reactionStrength);
		float flick = cheer * 18.0F;
		float mood = state.scared ? 55.0F : state.fighting ? 35.0F : state.lowHealth ? 28.0F : state.ownerNear ? -6.0F : 0.0F;
		float scale = 1.0F + 0.25F * cheer + (state.ownerNear && !state.scared && !state.fighting ? 0.06F : 0.0F);
		for (int i = 0; i < 2; i++) {
			int side = i == 0 ? 1 : -1;
			float angle = twitch + fold + mood * (1.0F - cheer) + flick * Mth.sin(cheerTime * 1.3F + (i == 0 ? 0.0F : Mth.HALF_PI));
			ModelPart ear = ears[i];
			ear.zRot = side * angle * Mth.DEG_TO_RAD;
			// Scared ears also lie back.
			ear.xRot = state.scared ? 0.5F : 0.0F;
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

	/** Eyelids close for a blink and half close in a happy squint. */
	private void poseLids(ButterBotRenderState state) {
		for (ModelPart lid : lids) {
			lid.yScale = Math.max(0.01F, state.lidsClosed);
		}
	}

	/**
	 * The tail hangs down from the hips and curls up, swaying more while walking or working, faster when petted.
	 * On a level up it stands up (leaning back, clear of the head) and quivers. It wags fast when the owner is close,
	 * puffs up when the bot is scared or fighting and droops when it is badly hurt. Higher tiers grow it longer
	 * and fluffier; a nekomata's two tails turn apart around their own axis and sway in step.
	 */
	private void poseTails(ButterBotRenderState state) {
		boolean legend = state.tier == BotTier.LEGEND;
		float cheer = LevelUpAnimation.strength(state.levelUpTime);
		float cheerTime = Math.max(0.0F, state.levelUpTime);
		float grow = state.tier.ordinal();
		float length = 1.0F + 0.04F * grow / TAIL_SEGMENT_LENGTH;
		float puff = state.scared || state.fighting ? 1.35F : 1.0F;
		float thickness = (1.0F + 0.04F * grow) * (1.0F + 0.3F * cheer) * puff;
		float sway = 6.0F + 14.0F * Mth.clamp(state.walkAnimationSpeed, 0.0F, 1.0F) + (state.working ? 4.0F : 0.0F)
				+ (state.ownerNear ? 6.0F : 0.0F);
		float swaySpeed = state.working ? 0.2F : state.ownerNear ? 0.26F : 0.12F;
		if (state.lowHealth) {
			sway *= 0.4F;
			swaySpeed *= 0.6F;
		}
		float wag = state.reactionStrength;
		float wagTime = Math.max(0.0F, state.reactionTime) * ReactionAnimation.wagSpeed(state.reaction);
		float wagSway = ReactionAnimation.wagSway(state.reaction);
		// A badly hurt bot's tail hangs low and barely curls.
		float restPitch = state.lowHealth ? -45.0F : -20.0F;
		float restBend = state.lowHealth ? 6.0F : 13.0F;
		for (int t = 0; t < 2; t++) {
			Tail tail = tails[t];
			float spread = legend ? (t == 0 ? -TWIN_SPREAD : TWIN_SPREAD) : 0.0F;
			float pitch = Mth.lerp(cheer, restPitch, 38.0F);
			float bend = Mth.lerp(cheer, restBend, 5.0F);
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
				scale(tail.segment()[i], thickness, length);
				scale(tail.fur()[i], thickness, length);
				if (i == BAND_SEGMENT) {
					for (ModelPart band : tail.band()) {
						scale(band, thickness, length);
					}
				}
				if (i == BOW_SEGMENT) {
					// The bow sits on the fur, however thick the tail is right now.
					tail.bow().y = -TAIL_HALF[BOW_SEGMENT] * thickness;
				}
				// Each segment bends a little further up, so the tail curls.
				pitch = bend;
			}
		}
	}

	private static void scale(ModelPart part, float thickness, float length) {
		part.xScale = thickness;
		part.yScale = thickness;
		part.zScale = length;
	}

	/** Spirit orbs circle the bot at chest height, bobbing and spinning. */
	private void poseOrbs(ButterBotRenderState state) {
		int count = state.tier == BotTier.LEGEND ? 3 : 2;
		float age = state.ageInTicks;
		for (int i = 0; i < MAX_ORBS; i++) {
			float angle = age * 0.06F + i * Mth.TWO_PI / count;
			ModelPart orb = orbs[i];
			orb.x = Mth.cos(angle) * ORB_RADIUS;
			orb.z = Mth.sin(angle) * ORB_RADIUS;
			orb.y = 4.0F + Mth.sin(age * 0.1F + i * 2.0F) * 1.5F;
			orb.yRot = age * 0.2F;
			orb.xRot = 0.6F;
		}
	}
}
