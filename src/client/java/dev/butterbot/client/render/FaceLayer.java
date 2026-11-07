package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.butterbot.ButterBot;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Pose;

import java.util.Arrays;

/**
 * Expressions painted over the skin's face, for every bot (neko or not): heart eyes, wide eyes, dizzy spirals,
 * angry brows and happy "^ ^" eyes with the emote, pink cheeks when loved or petted, a mouth that moves while it
 * talks, a sweat drop when badly hurt, tears when just hit, and faintly glowing eyes in the dark. Flat quads a hair
 * in front of the head's face (in head pixels: face at z -4, eyes on rows -4 to -2), so they follow the head.
 */
public class FaceLayer extends RenderLayer<ButterBotRenderState, PlayerModel> {

	private static final Identifier TEXTURE = ButterBot.id("textures/entity/face.png");
	/** Just in front of the face and the neko eyelids (z -4.07); marks a little further out than what they cover. */
	private static final float Z_COVER = -4.09F;
	private static final float Z_MARK = -4.1F;
	private static final float Z_GLOW = -4.11F;
	/** Bitmaps are in half pixels. */
	private static final float CELL = 0.5F;
	private static final float[] HEART = cells("XX.XX", "XXXXX", ".XXX.", "..X..");
	private static final float[] SPIRAL = cells("XXXXX", "....X", ".XX.X", ".X..X", ".XXXX");
	private static final float[] HAPPY = cells("..X..", ".X.X.", "X...X");
	/** The brow on the +x eye: low at the inside, high at the outside. */
	private static final float[] BROW = cells("...XX", "XXX..");
	private static final float[] DROP = cells(".X.", "XXX", "XXX", ".X.");
	private static final int HEART_COLOR = 0xFFFF4F86;
	private static final int DARK_COLOR = 0xFF2A2230;
	private static final int WHITE_COLOR = 0xFFF8F8F8;
	private static final int BLUSH_COLOR = 0x99FF7FA0;
	private static final int MOUTH_COLOR = 0xFF4A1E26;
	private static final int WATER_COLOR = 0xDD8FD3FF;
	private static final int GLOW_COLOR = 0x70FFF0B8;

	private boolean failed;

	public FaceLayer(RenderLayerParent<ButterBotRenderState, PlayerModel> renderer) {
		super(renderer);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, ButterBotRenderState state,
					   float yRot, float xRot) {
		if (state.isInvisible || failed) {
			return;
		}
		int emote = state.emote;
		boolean happyEyes = (emote == ButterBotEntity.EMOTE_MUSIC || emote == ButterBotEntity.EMOTE_CHECK) && state.faceColor != 0;
		boolean moodEyes = !state.sunglasses && (happyEyes || emote == ButterBotEntity.EMOTE_HEART
				|| emote == ButterBotEntity.EMOTE_SURPRISE || emote == ButterBotEntity.EMOTE_QUESTION);
		boolean brows = emote == ButterBotEntity.EMOTE_ANGER;
		boolean blush = emote == ButterBotEntity.EMOTE_HEART || state.reaction == ButterBotEntity.REACTION_PET;
		boolean sweat = state.lowHealth;
		boolean tears = state.scared && !state.sunglasses;
		float mouth = state.mouthOpen;
		boolean glow = state.inDark && !moodEyes && !state.sunglasses && state.lidsClosed < 0.5F && !state.hasPose(Pose.SLEEPING);
		if (!moodEyes && !brows && !blush && !sweat && !tears && mouth <= 0.0F && !glow) {
			return;
		}
		poseStack.pushPose();
		try {
			PlayerModel model = getParentModel();
			model.root().translateAndRotate(poseStack);
			model.head.translateAndRotate(poseStack);
			poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
			int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
			int face = 0xFF000000 | state.faceColor;
			float age = state.ageInTicks;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, buffer) -> {
				Quads q = new Quads(pose, buffer, lightCoords, overlay);
				for (int side = -1; side <= 1; side += 2) {
					float cx = 2.0F * side;
					boolean mirror = side < 0;
					if (moodEyes) {
						if (happyEyes) {
							q.rect(cx - 1.0F, -4.0F, cx + 1.0F, -2.0F, Z_COVER, face);
							q.bitmap(HAPPY, 5, cx - 1.25F, -3.75F, mirror, DARK_COLOR);
						} else if (emote == ButterBotEntity.EMOTE_HEART) {
							q.bitmap(HEART, 5, cx - 1.25F, -4.0F, false, HEART_COLOR);
						} else if (emote == ButterBotEntity.EMOTE_SURPRISE) {
							q.rect(cx - 1.25F, -4.25F, cx + 1.25F, -1.75F, Z_COVER, WHITE_COLOR);
							q.rect(cx - 0.5F, -3.5F, cx + 0.5F, -2.5F, Z_MARK, DARK_COLOR);
						} else {
							// Dizzy spirals that turn with every half second.
							q.bitmap(SPIRAL, 5, cx - 1.25F, -4.25F, mirror != ((int) (age / 10.0F) % 2 == 0), DARK_COLOR);
						}
					}
					if (brows) {
						q.bitmap(BROW, 5, cx - 1.25F, -5.25F, mirror, DARK_COLOR);
					}
					if (blush) {
						q.rect(cx - 1.0F + 0.25F * side, -1.75F, cx + 1.0F + 0.25F * side, -1.0F, Z_COVER, BLUSH_COLOR);
					}
					if (tears) {
						float drip = (age * 0.15F) % 1.0F;
						q.rect(cx + 0.25F * side - 0.25F, -2.0F, cx + 0.25F * side + 0.25F, -1.0F + drip, Z_MARK, WATER_COLOR);
					}
				}
				if (sweat) {
					// Slides down the temple and starts over.
					float slide = (age * 0.04F) % 1.0F;
					q.bitmap(DROP, 3, 2.5F, -6.5F + slide * 1.5F, false, WATER_COLOR);
				}
				if (mouth > 0.0F) {
					q.rect(-0.75F, -1.75F, 0.75F, -1.5F + 0.85F * mouth, Z_MARK, MOUTH_COLOR);
				}
			});
			if (glow) {
				collector.submitCustomGeometry(poseStack, RenderTypes.eyes(TEXTURE), (pose, buffer) -> {
					Quads q = new Quads(pose, buffer, LightCoordsUtil.FULL_BRIGHT, overlay);
					q.rect(1.0F, -4.0F, 3.0F, -2.0F, Z_GLOW, GLOW_COLOR);
					q.rect(-3.0F, -4.0F, -1.0F, -2.0F, Z_GLOW, GLOW_COLOR);
				});
			}
		} catch (RuntimeException exception) {
			// Looks only: never let them crash the game.
			failed = true;
			ButterBot.LOGGER.error("Butter Bot face could not be drawn, hiding it", exception);
		} finally {
			poseStack.popPose();
		}
	}

	/** Turns rows of 'X' into rectangles (x0, y0, x1, y1 in cells), one per run along a row. */
	private static float[] cells(String... rows) {
		float[] out = new float[0];
		for (int y = 0; y < rows.length; y++) {
			String row = rows[y];
			for (int x = 0; x < row.length(); x++) {
				if (row.charAt(x) != 'X') {
					continue;
				}
				int end = x;
				while (end < row.length() && row.charAt(end) == 'X') {
					end++;
				}
				out = Arrays.copyOf(out, out.length + 4);
				out[out.length - 4] = x;
				out[out.length - 3] = y;
				out[out.length - 2] = end;
				out[out.length - 1] = y + 1;
				x = end;
			}
		}
		return out;
	}

	/** Writes flat quads facing out of the face. */
	private record Quads(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {

		/** A bitmap 'width' cells wide with its top left at (x, y), flipped left to right when mirrored. */
		void bitmap(float[] cells, int width, float x, float y, boolean mirror, int color) {
			for (int i = 0; i < cells.length; i += 4) {
				float x0 = mirror ? width - cells[i + 2] : cells[i];
				float x1 = mirror ? width - cells[i] : cells[i + 2];
				rect(x + x0 * CELL, y + cells[i + 1] * CELL, x + x1 * CELL, y + cells[i + 3] * CELL, Z_MARK, color);
			}
		}

		/** In the same corner order as a model cube's north face. */
		void rect(float x0, float y0, float x1, float y1, float z, int color) {
			vertex(x1, y0, z, color);
			vertex(x0, y0, z, color);
			vertex(x0, y1, z, color);
			vertex(x1, y1, z, color);
		}

		private void vertex(float x, float y, float z, int color) {
			buffer.addVertex(pose, x, y, z).setColor(color).setUv(0.5F, 0.5F).setOverlay(overlay).setLight(light)
					.setNormal(pose, 0.0F, 0.0F, -1.0F);
		}
	}
}
