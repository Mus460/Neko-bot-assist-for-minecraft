package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.butterbot.ButterBot;
import dev.butterbot.entity.BotTier;
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
 * 3D cat ears on the head and a swaying tail on the back, tinted with the skin's hair colour. Works with any skin,
 * so every bot can be a neko. Levels show on them too: a collar with a bell and a band on the tail in the tier's
 * colour from level 5, glowing from level 15, glowing ears from level 20 and two tails at level 30. On a level up
 * the ears perk up and flick and the tail stands up and quivers; a head pat folds the ears outwards and quickens
 * the tail ({@link ReactionAnimation}). The parts are a {@link NekoModel} submitted once per colour, and glowing
 * parts a second time as an emissive overlay (like spider eyes), so shader packs make them shine.
 */
public class NekoLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	public static final Identifier TEXTURE = ButterBot.id("textures/entity/neko_parts.png");
	private static final int EAR_INNER_COLOR = 0xFFF4AFC2;
	private static final int COLLAR_COLOR = 0xFFC2303F;
	private static final int BELL_SLIT_COLOR = 0xFF3A2A10;
	/** The soul-fire blue a nekomata's tail tips fade into. */
	private static final int SOUL_FIRE_COLOR = 0xFF7FE8FF;
	/** Opacity of the emissive overlay on glowing parts. */
	private static final int GLOW_ALPHA = 0x99;

	private final Map<NekoModel.Group, NekoModel> wide = new EnumMap<>(NekoModel.Group.class);
	private final Map<NekoModel.Group, NekoModel> slim = new EnumMap<>(NekoModel.Group.class);
	private boolean reported;

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
		Map<NekoModel.Group, NekoModel> models = state.slim ? slim : wide;
		int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
		BotTier tier = state.tier;
		int tierColor = tier.colorAt(state.ageInTicks);
		boolean legend = tier == BotTier.LEGEND;
		boolean adorned = tier != BotTier.KITTEN;
		// From diamond on, the inside of the ears glows in the tier's colour.
		boolean glowingEars = tier.atLeast(BotTier.DIAMOND);
		int innerColor = glowingEars ? ARGB.srgbLerp(0.6F, EAR_INNER_COLOR, tierColor) : EAR_INNER_COLOR;
		int tipColor = legend ? ARGB.srgbLerp(0.5F, state.nekoColor, SOUL_FIRE_COLOR) : ARGB.srgbLerp(0.35F, state.nekoColor, 0xFFFFFFFF);
		int glowLight = tier.glows() ? LightCoordsUtil.FULL_BRIGHT : lightCoords;

		RenderType cutout = RenderTypes.entityCutout(TEXTURE);
		draw(collector, poseStack, models, NekoModel.Group.HAIR, state, cutout, lightCoords, overlay, state.nekoColor);
		draw(collector, poseStack, models, NekoModel.Group.INNER_EAR, state, cutout, glowingEars ? LightCoordsUtil.FULL_BRIGHT : lightCoords,
				overlay, innerColor);
		draw(collector, poseStack, models, NekoModel.Group.TAIL_TIP, state, cutout, legend ? LightCoordsUtil.FULL_BRIGHT : lightCoords,
				overlay, tipColor);
		if (adorned) {
			draw(collector, poseStack, models, NekoModel.Group.BAND, state, cutout, glowLight, overlay, tierColor);
			draw(collector, poseStack, models, NekoModel.Group.COLLAR, state, cutout, lightCoords, overlay, COLLAR_COLOR);
			draw(collector, poseStack, models, NekoModel.Group.BELL, state, cutout, glowLight, overlay, tierColor);
			draw(collector, poseStack, models, NekoModel.Group.BELL_SLIT, state, cutout, lightCoords, overlay, BELL_SLIT_COLOR);
		}

		// The glowing parts again as an emissive overlay.
		RenderType eyes = RenderTypes.eyes(TEXTURE);
		if (glowingEars) {
			draw(collector, poseStack, models, NekoModel.Group.INNER_EAR, state, eyes, LightCoordsUtil.FULL_BRIGHT, overlay, glow(innerColor));
		}
		if (tier.glows()) {
			draw(collector, poseStack, models, NekoModel.Group.BAND, state, eyes, LightCoordsUtil.FULL_BRIGHT, overlay, glow(tierColor));
			draw(collector, poseStack, models, NekoModel.Group.BELL, state, eyes, LightCoordsUtil.FULL_BRIGHT, overlay, glow(tierColor));
		}
		if (legend) {
			draw(collector, poseStack, models, NekoModel.Group.TAIL_TIP, state, eyes, LightCoordsUtil.FULL_BRIGHT, overlay, glow(tipColor));
		}
	}

	private static void draw(SubmitNodeCollector collector, PoseStack poseStack, Map<NekoModel.Group, NekoModel> models,
							 NekoModel.Group group, ButterBotRenderState state, RenderType renderType, int light, int overlay, int color) {
		collector.submitModel(models.get(group), state, poseStack, renderType, light, overlay, color, null, state.outlineColor);
	}

	/** Translucent copy of a colour for the emissive overlay. */
	private static int glow(int color) {
		return GLOW_ALPHA << 24 | color & 0xFFFFFF;
	}
}
