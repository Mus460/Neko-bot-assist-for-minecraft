package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.butterbot.ButterBot;
import dev.butterbot.client.BotFocus;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotTier;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

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
	/** Same proportions as real players. */
	private static final float PLAYER_SCALE = 0.9375F;
	/** The "Show" beam starts just over the head and reaches this far up. */
	private static final int BEAM_START = 2;
	private static final int BEAM_HEIGHT = 320;

	private final PlayerModel wideModel;
	private final PlayerModel slimModel;
	private final Map<Identifier, PlayerSkin> playerSkins = new HashMap<>();
	private final Map<Integer, Identifier> lastTextures = new HashMap<>();

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

	@Override
	protected boolean shouldShowName(ButterBotEntity entity, double distanceToCameraSq) {
		return false;
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
		float turn = standing ? ReactionAnimation.bodyTurn(state.reaction, state.reactionTime) : 0.0F;
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
		if (state.reaction != ButterBotEntity.REACTION_NONE) {
			// Leaning into a pat, chewing nods, or a pout's head turned aside with the chin up.
			state.xRot += ReactionAnimation.headPitch(state.reaction, state.reactionTime);
			state.yRot += ReactionAnimation.headTurn(state.reaction, state.reactionTime);
		}

		boolean close = state.distanceToCameraSq < INFO_DISTANCE_SQ && !entity.isInvisible();
		if (close) {
			// The level shows in the tier's colour; a nekomata (level 30) gets a star and a rainbow.
			String level = (state.tier == BotTier.LEGEND ? "★" : "") + "Lv" + entity.getBotLevel() + " ";
			state.infoLine = Component.literal(level).withColor(state.tier.colorAt(state.ageInTicks) & 0xFFFFFF)
					.append(entity.getName().copy().withStyle(ChatFormatting.WHITE));
			Component speech = entity.getSpeech();
			state.speech = speech.getString().isEmpty() ? null : speech;
		} else {
			state.infoLine = null;
			state.speech = null;
		}
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
		super.submit(state, poseStack, collector, camera);
		if (state.focused) {
			submitBeam(state, poseStack, collector);
		}
		if (state.infoLine != null) {
			submitLabels(state, poseStack, collector, camera);
		}
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

	private void submitLabels(ButterBotRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		Font font = getFont();
		poseStack.pushPose();
		// During the level-up cheer the label rises with the hop and makes room for the perked-up ears.
		float lift = state.levelUpTime >= 0.0F
				? LevelUpAnimation.hop(state.levelUpTime) * state.scale + 0.15F * LevelUpAnimation.strength(state.levelUpTime) : 0.0F;
		poseStack.translate(0.0F, state.boundingBoxHeight + 0.5F + lift, 0.0F);
		poseStack.rotate(camera.orientation);
		poseStack.scale(0.025F, -0.025F, 0.025F);

		FormattedCharSequence info = state.infoLine.getVisualOrderText();
		collector.submitText(poseStack, -font.width(info) / 2.0F, 0.0F, info, false, Font.DisplayMode.NORMAL,
				state.lightCoords, 0xFFFFFFFF, 0x40000000, 0);

		if (state.speech != null) {
			List<FormattedCharSequence> lines = font.split(state.speech, SPEECH_WRAP_WIDTH);
			float y = -6.0F - lines.size() * 10.0F;
			for (FormattedCharSequence line : lines) {
				collector.submitText(poseStack, -font.width(line) / 2.0F, y, line, false, Font.DisplayMode.NORMAL,
						state.lightCoords, 0xFFFFF4C2, 0x90201008, 0);
				y += 10.0F;
			}
		}
		poseStack.popPose();
	}
}
