package dev.butterbot.client;

import dev.butterbot.client.screen.RingSector;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A marker on each bot out in the world: its face in a ring that shows its health, floating over its
 * head (seen through walls), with how far away it is. A bot behind the player or off screen gets its
 * marker on the screen's edge, with an arrow pointing the way. Bots closer than 20 blocks have their own
 * name label instead. Shown with the bot list in the first mode of the HUD key, and always (pulsing) for
 * a bot the player asked to see with "Show".
 */
public final class BotMarkers implements HudElement {

	/** Closer than this the bot's own label says enough; the marker fades in over the next blocks. */
	private static final double HIDE_WITHIN = 20.0D;
	private static final double FADE_UNTIL = 28.0D;
	/** The marker floats this high above the bot's feet, just over its head. */
	private static final double ABOVE_FEET = 2.1D;
	/** ...and its centre sits this much higher on screen, like a pin, so it doesn't cover the bot. */
	private static final float LIFT = 9.0F;
	/**
	 * Far points are projected this far out along the same line of sight (same spot on screen), so a bot
	 * farther away than the view reaches doesn't fall behind the far plane and look like it is behind.
	 */
	private static final double PROJECT_DISTANCE = 64.0D;
	/** Face size: one GUI pixel per skin pixel stays sharp at every GUI scale. */
	private static final int FACE = 8;
	private static final float RING_IN = 6.2F;
	private static final float RING_OUT = 7.6F;
	/** The hair-coloured ring around a bot being shown. */
	private static final float FOCUS_RING = 1.5F;
	/** Markers that can't be on screen slide along an ellipse this far in from the screen's edge. */
	private static final float EDGE = 20.0F;
	/** The arrow on the edge: how far its tip is from the marker's centre, its length and its half-width (radians). */
	private static final float ARROW_TIP = 16.0F;
	private static final float ARROW_LENGTH = 4.5F;
	private static final float ARROW_SPREAD = 0.55F;
	/** Rings this small look just as round with long steps (few pieces to draw every frame). */
	private static final float COARSE_STEP = (float) (Math.PI / 10.0D);
	private static final float TAU = (float) (Math.PI * 2.0D);
	private static final float TOP = (float) (-Math.PI / 2.0D);

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.gui.screen() != null
				|| minecraft.getDebugOverlay().showDebugScreen()) {
			return;
		}
		boolean all = BotHud.showsMarkers();
		if (!all && !BotFocus.any()) {
			return;
		}
		List<Payloads.HudBot> bots = BotHud.liveBots();
		if (bots.isEmpty()) {
			return;
		}
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
		Vec3 player = minecraft.player.getPosition(partialTick);
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		long now = Util.getMillis();
		float width = g.guiWidth();
		float height = g.guiHeight();
		for (Payloads.HudBot bot : bots) {
			if (bot.distance() < 0) {
				// In another dimension.
				continue;
			}
			boolean focused = BotFocus.isFocused(bot.id());
			if (!all && !focused) {
				continue;
			}
			// A bot this computer knows moves smoothly; one too far away slides between the once-a-second updates.
			Entity entity = minecraft.level.getEntity(bot.id());
			Vec3 feet = entity != null ? entity.getPosition(partialTick) : BotHud.reportedPosition(bot);
			double distance = feet.distanceTo(player);
			float far = (float) Mth.clamp((distance - HIDE_WITHIN) / (FADE_UNTIL - HIDE_WITHIN), 0.0D, 1.0D);
			if (far <= 0.0F && !focused) {
				continue;
			}

			Vec3 sight = feet.add(0.0D, ABOVE_FEET, 0.0D).subtract(camera);
			double length = sight.length();
			if (length < 0.1D) {
				continue;
			}
			if (length > PROJECT_DISTANCE) {
				sight = sight.scale(PROJECT_DISTANCE / length);
			}
			Vec3 ndc = minecraft.gameRenderer.projectPointToScreen(camera.add(sight));
			if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y) || !Double.isFinite(ndc.z)) {
				continue;
			}
			// Behind the camera the projection comes out mirrored (as in the vanilla locator bar).
			boolean behind = ndc.z > 1.0D;
			float x = (float) ((behind ? -ndc.x : ndc.x) + 1.0D) / 2.0F * width;
			float y = (float) (1.0D - (behind ? -ndc.y : ndc.y)) / 2.0F * height - LIFT;
			boolean onScreen = !behind && x >= EDGE && x <= width - EDGE && y >= EDGE && y <= height - EDGE;
			// Up close a bot being shown glows anyway: then its marker only comes back to point the way.
			float alpha = onScreen || !focused ? far : 1.0F;
			if (alpha < 0.05F) {
				continue;
			}

			float angle = 0.0F;
			if (!onScreen) {
				float dx = x - width / 2.0F;
				float dy = y + LIFT - height / 2.0F;
				if (behind) {
					// Behind the player: always on the lower part of the edge, pointing down or aside, never up
					// like a bot ahead and high above (the mirrored point is a little above the middle on flat ground).
					dy = Math.max(Math.abs(dy), 1.0F);
				}
				float rx = Math.max(1.0F, width / 2.0F - EDGE);
				float ry = Math.max(1.0F, height / 2.0F - EDGE);
				float onEllipse = 1.0F / (float) Math.sqrt(dx * dx / (rx * rx) + dy * dy / (ry * ry));
				x = width / 2.0F + dx * onEllipse;
				y = height / 2.0F + dy * onEllipse;
				angle = (float) Math.atan2(dy, dx);
			}
			drawMarker(g, minecraft.font, bot, x, y, alpha, focused, !onScreen, angle, distance, now);
		}
	}

	private static void drawMarker(GuiGraphicsExtractor g, Font font, Payloads.HudBot bot, float x, float y, float alpha,
								   boolean focused, boolean edge, float angle, double distance, long now) {
		ClientSkinManager.SkinInfo skin = bot.skinId().isEmpty() ? null : ClientSkinManager.skinFor(bot.skinId());
		if (skin == null) {
			skin = ClientSkinManager.defaultSkinFor(bot.skinVariant());
		}
		float health = bot.maxHealth() <= 0.0F ? 0.0F : Mth.clamp(bot.health() / bot.maxHealth(), 0.0F, 1.0F);
		int glow = ARGB.color(alpha, BotFocus.glowColor(skin.hairColor()));

		g.pose().pushMatrix();
		g.pose().translate(x, y);
		if (edge) {
			// A small wedge just outside the ring, its point towards the bot.
			int color = focused ? glow : ARGB.white(alpha * 0.9F);
			g.pose().pushMatrix();
			g.pose().translate(ARROW_TIP * Mth.cos(angle), ARROW_TIP * Mth.sin(angle));
			RingSector.draw(g, 0.0F, ARROW_LENGTH, angle + Mth.PI - ARROW_SPREAD, angle + Mth.PI + ARROW_SPREAD, color, color, COARSE_STEP);
			g.pose().popMatrix();
		}

		g.pose().pushMatrix();
		if (focused) {
			// Grows and shrinks between 1x and 1.25x about once a second.
			float pulse = 1.125F + 0.125F * (float) Math.sin(now / 150.0D);
			g.pose().scale(pulse, pulse);
			RingSector.draw(g, RING_OUT, RING_OUT + FOCUS_RING, 0.0F, TAU, glow, glow, COARSE_STEP);
		}
		int backing = ARGB.black(0.55F * alpha);
		RingSector.draw(g, 0.0F, RING_OUT, 0.0F, TAU, backing, backing, COARSE_STEP);
		PlayerFaceExtractor.extractRenderState(g, skin.texture(), -FACE / 2, -FACE / 2, FACE, true, false, ARGB.white(alpha));
		if (health > 0.0F) {
			// Health runs clockwise from the top.
			int color = ARGB.color(alpha, BotHud.healthColor(health));
			RingSector.draw(g, RING_IN, RING_OUT, TOP, TOP + TAU * health, color, color, COARSE_STEP);
		}
		g.pose().popMatrix();

		String text = Math.round(distance) + "m";
		g.pose().translate(0.0F, RING_OUT + 2.0F);
		g.pose().scale(0.75F, 0.75F);
		g.text(font, text, -font.width(text) / 2, 0, ARGB.white(alpha), true);
		g.pose().popMatrix();
	}
}
