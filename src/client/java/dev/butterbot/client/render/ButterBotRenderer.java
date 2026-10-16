package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.butterbot.ButterBot;
import dev.butterbot.client.BotFocus;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotTier;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the bot as a person with a normal Minecraft player skin (wide or slim arms), optional cat
 * ears and tail, the armor and tool it is using, and a name line plus speech bubble above its head.
 * A bot being shown ("Show" in the panel) glows in its hair colour through walls under a beacon beam.
 */
public class ButterBotRenderer extends LivingEntityRenderer<ButterBotEntity, ButterBotRenderState, PlayerModel> {

	private static final double INFO_DISTANCE_SQ = 24.0D * 24.0D;
	private static final int SPEECH_WRAP_WIDTH = 140;
	private static final int SPEECH_COLOR = 0xFFF4C2;
	/** Height of one name tag line, in blocks (as vanilla stacks the score line under a name). */
	private static final float LINE_HEIGHT = 9.0F * 1.15F * 0.025F;
	/** Same proportions as real players. */
	private static final float PLAYER_SCALE = 0.9375F;
	/** The "Show" beam starts just over the head and reaches this far up. */
	private static final int BEAM_START = 2;
	private static final int BEAM_HEIGHT = 320;

	private final PlayerModel wideModel;
	private final PlayerModel slimModel;
	private final Map<Identifier, PlayerSkin> playerSkins = new HashMap<>();
	private final Map<Integer, Identifier> lastTextures = new HashMap<>();
	private boolean reported;

	public ButterBotRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		this.wideModel = this.model;
		this.slimModel = new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		this.addLayer(new BotArmorLayer(this, context));
		this.addLayer(new BotItemInHandLayer(this));
		this.addLayer(new NekoLayer(this));
	}

	@Override
	public ButterBotRenderState createRenderState() {
		return new ButterBotRenderState();
	}

	/** A bot being shown keeps its beam even while it is out of sight or far away. */
	@Override
	public boolean shouldRender(ButterBotEntity entity, Frustum culler, double camX, double camY, double camZ, float partialTicks) {
		return BotFocus.isFocused(entity.getUUID()) || super.shouldRender(entity, culler, camX, camY, camZ, partialTicks);
	}

	/** The "Lv name" line shows within 24 blocks, drawn as a vanilla name tag. */
	@Override
	protected boolean shouldShowName(ButterBotEntity entity, double distanceToCameraSq) {
		return distanceToCameraSq < INFO_DISTANCE_SQ && !entity.isInvisible();
	}

	/** The level in the tier's colour, then the name; a nekomata (level 30) gets a star and a rainbow. */
	@Override
	protected Component getNameTag(ButterBotEntity entity) {
		BotTier tier = BotTier.of(entity.getBotLevel());
		String level = (tier == BotTier.LEGEND ? "\u2605" : "") + "Lv" + entity.getBotLevel() + " ";
		return Component.literal(level).withColor(tier.colorAt(entity.tickCount) & 0xFFFFFF)
				.append(entity.getName().copy().withStyle(ChatFormatting.WHITE));
	}

	@Override
	public Identifier getTextureLocation(ButterBotRenderState state) {
		return state.texture;
	}

	@Override
	protected void scale(ButterBotRenderState state, PoseStack poseStack) {
		poseStack.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
	}

	/** The level-up cheer: a little hop with a twirl. A pout turns the bot away. */
	@Override
	protected void setupRotations(ButterBotRenderState state, PoseStack poseStack, float bodyRot, float entityScale) {
		boolean standing = state.deathTime <= 0.0F && !state.hasPose(Pose.SLEEPING);
		boolean cheering = state.levelUpTime >= 0.0F && standing;
		if (cheering) {
			// Hop before the rotations, while up is still up (a bot named Dinnerbone is drawn upside down).
			poseStack.translate(0.0F, LevelUpAnimation.hop(state.levelUpTime), 0.0F);
		}
		super.setupRotations(state, poseStack, bodyRot, entityScale);
		if (cheering) {
			poseStack.rotateDegrees(Axis.YP, LevelUpAnimation.twirl(state.levelUpTime));
		}
		float turn = standing ? ReactionAnimation.bodyTurn(state.reaction, state.reactionStrength) : 0.0F;
		if (turn != 0.0F) {
			poseStack.rotateDegrees(Axis.YP, turn);
		}
	}

	@Override
	public void extractRenderState(ButterBotEntity entity, ButterBotRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
		state.rightArmPose = armPose(entity, HumanoidArm.RIGHT);
		state.leftArmPose = armPose(entity, HumanoidArm.LEFT);

		ClientSkinManager.SkinInfo skin = ClientSkinManager.skinFor(entity.getSkinId());
		if (skin == null) {
			skin = ClientSkinManager.defaultSkinFor(entity.getSkinVariant());
		}
		state.texture = skin.texture();
		state.slim = skin.slim();
		// Anything that reads the player-style skin field (other mods, shader packs) sees the same picture.
		state.skin = playerSkinFor(skin);
		state.id = entity.getId();
		logTextureChange(entity, skin);
		state.nekoColor = skin.hairColor();
		state.neko = entity.isNeko();
		state.working = entity.isWorkingClient();
		state.tier = BotTier.of(entity.getBotLevel());
		state.levelUpTime = entity.levelUpAnimTime(partialTicks);
		state.focused = BotFocus.isFocused(entity.getUUID());
		if (state.focused) {
			// The vanilla glowing outline, drawn through walls.
			state.focusColor = BotFocus.glowColor(skin.hairColor());
			state.outlineColor = state.focusColor;
			state.beamTime = entity.tickCount % 40 + partialTicks;
		}
		state.reactionTime = entity.reactionTime(partialTicks);
		state.reaction = state.reactionTime >= 0.0F ? entity.reactionType() : ButterBotEntity.REACTION_NONE;
		state.reactionStrength = ReactionAnimation.strength(state.reactionTime, entity.reactionLeft(partialTicks));
		if (state.reaction != ButterBotEntity.REACTION_NONE) {
			// Leaning into a pat, chewing nods, or a pout's head turned aside with the chin up.
			state.xRot += ReactionAnimation.headPitch(state.reaction, state.reactionTime, state.reactionStrength);
			state.yRot += ReactionAnimation.headTurn(state.reaction, state.reactionStrength);
		}

		// Speech bubbles show over the name tag, wrapped into short lines.
		Component speech = state.nameTag != null ? entity.getSpeech() : Component.empty();
		state.speechLines = speech.getString().isEmpty() ? List.of() : wrap(speech);
	}

	private List<Component> wrap(Component speech) {
		List<Component> lines = new ArrayList<>();
		for (FormattedText line : getFont().getSplitter().splitLines(speech, SPEECH_WRAP_WIDTH, Style.EMPTY)) {
			lines.add(Component.literal(line.getString()).withColor(SPEECH_COLOR));
		}
		return lines;
	}

	private PlayerSkin playerSkinFor(ClientSkinManager.SkinInfo skin) {
		return playerSkins.computeIfAbsent(skin.texture(), texture -> PlayerSkin.insecure(
				new ClientAsset.ResourceTexture(texture, texture), null, null, skin.slim() ? PlayerModelType.SLIM : PlayerModelType.WIDE));
	}

	/** Writes one log line whenever a bot starts wearing a different skin, to make skin problems easy to trace. */
	private void logTextureChange(ButterBotEntity entity, ClientSkinManager.SkinInfo skin) {
		if (skin.texture().equals(lastTextures.get(entity.getId()))) {
			return;
		}
		if (lastTextures.size() > 256) {
			lastTextures.clear();
		}
		lastTextures.put(entity.getId(), skin.texture());
		ButterBot.LOGGER.info("Butter Bot {} is drawn with {} ({} arms, skin id '{}')", entity.getName().getString(), skin.texture(),
				skin.slim() ? "slim" : "wide", entity.getSkinId());
	}

	private static HumanoidModel.ArmPose armPose(ButterBotEntity entity, HumanoidArm arm) {
		return entity.getItemHeldByArm(arm).isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
	}
	@Override
	public void submit(ButterBotRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		this.model = state.slim ? slimModel : wideModel;
		if (!reported) {
			reported = true;
			ButterBot.LOGGER.info("Butter Bot renderer: first draw (layers {}, name tag {}, distance {})", layers.size(),
					state.nameTag != null, Math.sqrt(state.distanceToCameraSq));
		}
		super.submit(state, poseStack, collector, camera);
		if (state.focused) {
			submitBeam(state, poseStack, collector);
		}
	}

	/**
	 * The vanilla name tag ("Lv name"), with the speech bubble's lines above it. During the level-up cheer they
	 * rise with the hop and make room for the perked-up ears.
	 */
	@Override
	protected void submitNameDisplay(ButterBotRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
									 CameraRenderState camera) {
		float lift = state.levelUpTime >= 0.0F
				? LevelUpAnimation.hop(state.levelUpTime) * state.scale + 0.15F * LevelUpAnimation.strength(state.levelUpTime) : 0.0F;
		poseStack.pushPose();
		poseStack.translate(0.0F, lift, 0.0F);
		super.submitNameDisplay(state, poseStack, collector, camera);
		if (state.nameTag != null) {
			for (int i = state.speechLines.size() - 1; i >= 0; i--) {
				// One line up each time, as vanilla stacks the score line.
				poseStack.translate(0.0F, LINE_HEIGHT, 0.0F);
				collector.submitNameTag(poseStack, state.nameTagAttachment, 0, state.speechLines.get(i), !state.isDiscrete,
						state.lightCoords, camera);
			}
		}
		poseStack.popPose();
	}

	/** A beacon beam in the glow colour from just over the head up to the sky, wider far away like a beacon's. */
	private static void submitBeam(ButterBotRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		float width = Math.max(1.0F, (float) Math.sqrt(state.distanceToCameraSq) / 96.0F);
		poseStack.pushPose();
		// The beam centres itself on a block (+0.5): take that back so it comes out of the bot.
		poseStack.translate(-0.5F, 0.0F, -0.5F);
		BeaconRenderer.submitBeaconBeam(poseStack, collector, BeaconRenderer.BEAM_LOCATION, 1.0F, state.beamTime, BEAM_START,
				BEAM_HEIGHT, state.focusColor, 0.2F * width, 0.25F * width);
		poseStack.popPose();
	}
}
