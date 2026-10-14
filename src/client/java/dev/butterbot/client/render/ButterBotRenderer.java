package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.butterbot.ButterBot;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the bot as a person with a normal Minecraft player skin (wide or slim arms), optional cat
 * ears and tail, the tool it is using, and a name line plus speech bubble above its head.
 */
public class ButterBotRenderer extends LivingEntityRenderer<ButterBotEntity, ButterBotRenderState, PlayerModel> {

	private static final double INFO_DISTANCE_SQ = 24.0D * 24.0D;
	private static final int SPEECH_WRAP_WIDTH = 140;
	/** Same proportions as real players. */
	private static final float PLAYER_SCALE = 0.9375F;

	private final PlayerModel wideModel;
	private final PlayerModel slimModel;
	private final Map<Identifier, PlayerSkin> playerSkins = new HashMap<>();
	private final Map<Integer, Identifier> lastTextures = new HashMap<>();

	public ButterBotRenderer(EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		this.wideModel = this.model;
		this.slimModel = new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		this.addLayer(new BotItemInHandLayer(this));
		this.addLayer(new NekoLayer(this));
	}

	@Override
	public ButterBotRenderState createRenderState() {
		return new ButterBotRenderState();
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

	@Override
	public void extractRenderState(ButterBotEntity entity, ButterBotRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
		state.rightArmPose = armPose(entity, HumanoidArm.RIGHT);
		state.leftArmPose = armPose(entity, HumanoidArm.LEFT);

		ClientSkinManager.SkinInfo skin = ClientSkinManager.skinFor(entity.getSkinId());
		if (skin == null) {
			skin = ClientSkinManager.defaultSkinFor(entity.getUUID());
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

		boolean close = state.distanceToCameraSq < INFO_DISTANCE_SQ && !entity.isInvisible();
		if (close) {
			state.infoLine = Component.literal("Lv" + entity.getBotLevel() + " ").withStyle(ChatFormatting.GOLD)
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
		if (state.infoLine != null) {
			submitLabels(state, poseStack, collector, camera);
		}
	}

	private void submitLabels(ButterBotRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		Font font = getFont();
		poseStack.pushPose();
		poseStack.translate(0.0F, state.boundingBoxHeight + 0.5F, 0.0F);
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
