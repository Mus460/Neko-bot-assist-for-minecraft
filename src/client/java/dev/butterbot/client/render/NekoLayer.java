package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.butterbot.ButterBot;
import dev.butterbot.entity.BotTier;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;

import java.util.EnumMap;
import java.util.Map;

/**
 * 3D cat ears on the head and a bushy, swaying tail on the back, tinted with the skin's hair colour, plus what the
 * bot wears: works with any skin, so every bot can be a neko. Levels show on them: a collar with a bell and a band
 * on the tail in the tier's colour from level 5, a bow from 10, ear fluff and glowing bell from 15, glowing ears
 * and a tiara from 20, spirit orbs from 25, two tails and soul-fire wisps at 30. The weather dresses it: a scarf in
 * the snow, sunglasses in the desert sun, an umbrella in the rain. Ears and tail move with the bot's mood and the
 * level-up cheer ({@link NekoModel}); the eyes blink. The parts are a {@link NekoModel} submitted once per colour,
 * and glowing parts a second time as an emissive overlay (like spider eyes), so shader packs make them shine.
 */
public class NekoLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	public static final Identifier TEXTURE = ButterBot.id("textures/entity/neko_parts.png");
	private static final int EAR_INNER_COLOR = 0xFFF4AFC2;
	private static final int EAR_FLUFF_COLOR = 0xFFF7F1EC;
	private static final int COLLAR_COLOR = 0xFFC2303F;
	private static final int BELL_SLIT_COLOR = 0xFF3A2A10;
	private static final int CROWN_COLOR = 0xFFFFD447;
	private static final int GLASSES_COLOR = 0xFF16161D;
	private static final int SCARF_COLOR = 0xFFD7443E;
	/** A butter-yellow umbrella, of course. */
	private static final int UMBRELLA_COLOR = 0xFFFFE07A;
	private static final int UMBRELLA_POLE_COLOR = 0xFF6B4423;
	/** The soul-fire blue a nekomata's tail tips and wisps glow in. */
	private static final int SOUL_FIRE_COLOR = 0xFF7FE8FF;
	/** Warm white wings of light (Second Wind). */
	private static final int WING_COLOR = 0xFFFFF4D6;
	/** Opacity of the emissive overlay on glowing parts. */
	private static final int GLOW_ALPHA = 0x99;

	private final Map<NekoModel.Group, NekoModel> wide = new EnumMap<>(NekoModel.Group.class);
	private final Map<NekoModel.Group, NekoModel> slim = new EnumMap<>(NekoModel.Group.class);
	private boolean reported;
	private boolean failed;

	public NekoLayer(RenderLayerParent<ButterBotRenderState, PlayerModel> renderer) {
		super(renderer);
		for (NekoModel.Group group : NekoModel.Group.values()) {
			wide.put(group, new NekoModel(false, group));
			slim.put(group, new NekoModel(true, group));
		}
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state,
					   float yRot, float xRot) {
		if (!reported) {
			reported = true;
			ButterBot.LOGGER.info("Butter Bot neko parts: first draw (neko {}, invisible {}, tier {})", state.neko, state.isInvisible,
					state.tier);
		}
		if (!state.neko || state.isInvisible) {
			return;
		}
		try {
			submitParts(poseStack, collector, lightCoords, state);
		} catch (RuntimeException exception) {
			// Ears and tail are looks only: never let them crash the game.
			if (!failed) {
				failed = true;
				ButterBot.LOGGER.error("Butter Bot neko parts could not be drawn", exception);
			}
		}
	}

	private void submitParts(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state) {
		Parts parts = new Parts(collector, poseStack, state.slim ? slim : wide, state, lightCoords,
				LivingEntityRenderer.getOverlayCoords(state, 0.0F));
		BotTier tier = state.tier;
		int tierColor = tier.colorAt(state.ageInTicks);
		boolean legend = tier == BotTier.LEGEND;
		int hair = state.nekoColor;
		// From diamond on, the inside of the ears glows in the tier's colour.
		boolean glowingEars = tier.atLeast(BotTier.DIAMOND);
		int innerColor = glowingEars ? ARGB.srgbLerp(0.6F, EAR_INNER_COLOR, tierColor) : EAR_INNER_COLOR;
		int tipColor = legend ? ARGB.srgbLerp(0.5F, hair, SOUL_FIRE_COLOR) : ARGB.srgbLerp(0.35F, hair, 0xFFFFFFFF);
		int orbColor = legend ? SOUL_FIRE_COLOR : tierColor;
		boolean glows = tier.glows();

		parts.draw(NekoModel.Group.HAIR, false, hair);
		parts.draw(NekoModel.Group.FUR, false, ARGB.srgbLerp(0.18F, hair, 0xFFFFFFFF));
		parts.draw(NekoModel.Group.INNER_EAR, glowingEars, innerColor);
		parts.draw(NekoModel.Group.TAIL_TIP, legend, tipColor);
		if (state.faceColor != 0 && state.lidsClosed > 0.0F) {
			parts.draw(NekoModel.Group.EYELID, false, state.faceColor);
		}
		if (tier.atLeast(BotTier.BRONZE)) {
			parts.draw(NekoModel.Group.BAND, glows, tierColor);
			parts.draw(NekoModel.Group.COLLAR, false, COLLAR_COLOR);
			parts.draw(NekoModel.Group.BELL, glows, tierColor);
			parts.draw(NekoModel.Group.BELL_SLIT, false, BELL_SLIT_COLOR);
		}
		if (tier.atLeast(BotTier.SILVER)) {
			parts.draw(NekoModel.Group.BOW, false, tierColor);
			parts.draw(NekoModel.Group.BOW_KNOT, false, ARGB.srgbLerp(0.35F, tierColor, 0xFF000000));
		}
		if (tier.atLeast(BotTier.GOLD)) {
			parts.draw(NekoModel.Group.EAR_FLUFF, false, EAR_FLUFF_COLOR);
		}
		if (tier.atLeast(BotTier.DIAMOND)) {
			parts.draw(NekoModel.Group.CROWN, false, CROWN_COLOR);
			parts.draw(NekoModel.Group.GEM, true, tierColor);
		}
		if (tier.atLeast(BotTier.AMETHYST)) {
			parts.draw(NekoModel.Group.ORB, true, orbColor);
		}
		if (state.scarf) {
			parts.draw(NekoModel.Group.SCARF, false, SCARF_COLOR);
		}
		if (state.sunglasses) {
			parts.draw(NekoModel.Group.GLASSES, false, GLASSES_COLOR);
		}
		if (state.umbrella) {
			parts.draw(NekoModel.Group.UMBRELLA, false, UMBRELLA_COLOR);
			parts.draw(NekoModel.Group.UMBRELLA_POLE, false, UMBRELLA_POLE_COLOR);
		}
		boolean winged = state.gesture == ButterBotEntity.GESTURE_WINGS;
		if (winged) {
			parts.draw(NekoModel.Group.WING, true, WING_COLOR);
		}

		// The glowing parts again as an emissive overlay.
		if (glowingEars) {
			parts.glow(NekoModel.Group.INNER_EAR, innerColor);
		}
		if (glows) {
			parts.glow(NekoModel.Group.BAND, tierColor);
			parts.glow(NekoModel.Group.BELL, tierColor);
		}
		if (tier.atLeast(BotTier.DIAMOND)) {
			parts.glow(NekoModel.Group.GEM, tierColor);
		}
		if (tier.atLeast(BotTier.AMETHYST)) {
			parts.glow(NekoModel.Group.ORB, orbColor);
		}
		if (legend) {
			parts.glow(NekoModel.Group.TAIL_TIP, tipColor);
		}
		if (winged) {
			parts.glow(NekoModel.Group.WING, WING_COLOR);
		}
	}

	/** Submits colour groups of one bot. */
	private record Parts(SubmitNodeCollector collector, PoseStack poseStack, Map<NekoModel.Group, NekoModel> models,
						 ButterBotRenderState state, int light, int overlay) {

		/** One group in one colour; glowing groups are drawn full bright. */
		void draw(NekoModel.Group group, boolean glowing, int color) {
			submit(group, RenderTypes.entityCutout(TEXTURE), glowing ? LightCoordsUtil.FULL_BRIGHT : light, color);
		}

		/** The emissive overlay on a glowing group. */
		void glow(NekoModel.Group group, int color) {
			submit(group, RenderTypes.eyes(TEXTURE), LightCoordsUtil.FULL_BRIGHT, GLOW_ALPHA << 24 | color & 0xFFFFFF);
		}

		private void submit(NekoModel.Group group, RenderType renderType, int light, int color) {
			collector.submitModel(models.get(group), state, poseStack, renderType, light, overlay, color, null, state.outlineColor);
		}
	}
}
