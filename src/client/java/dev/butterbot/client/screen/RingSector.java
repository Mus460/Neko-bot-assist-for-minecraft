package dev.butterbot.client.screen;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jetbrains.annotations.Nullable;

/**
 * A piece of a ring (or a whole disc), drawn as a strip of small quads so its edges are smooth at any
 * GUI scale: from radius {@code r0} to {@code r1} and from angle {@code a0} to {@code a1} (radians,
 * clockwise from the right, around the pose's origin). Colours fade from the inner to the outer edge.
 */
record RingSector(Matrix3x2fc pose, float r0, float r1, float a0, float a1, int innerColor, int outerColor,
				  @Nullable ScreenRectangle bounds) implements GuiElementRenderState {

	/** Short enough steps that the curve looks round. */
	private static final float STEP = (float) (Math.PI / 48.0D);

	/** Draws a ring piece centred on the current pose origin. */
	static void draw(GuiGraphicsExtractor g, float r0, float r1, float a0, float a1, int innerColor, int outerColor) {
		if (r1 <= r0 || a1 <= a0 || ((innerColor | outerColor) >>> 24) == 0) {
			return;
		}
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		int size = (int) Math.ceil(r1);
		ScreenRectangle bounds = new ScreenRectangle(-size - 1, -size - 1, size * 2 + 2, size * 2 + 2).transformMaxBounds(pose);
		g.guiRenderState.addGuiElement(new RingSector(pose, r0, r1, a0, a1, innerColor, outerColor, bounds));
	}

	@Override
	public void buildVertices(VertexConsumer consumer) {
		int steps = Math.max(1, (int) Math.ceil((a1 - a0) / STEP));
		float step = (a1 - a0) / steps;
		for (int i = 0; i < steps; i++) {
			float from = a0 + i * step;
			float to = from + step;
			float cosFrom = (float) Math.cos(from);
			float sinFrom = (float) Math.sin(from);
			float cosTo = (float) Math.cos(to);
			float sinTo = (float) Math.sin(to);
			float[] xs = {r1 * cosFrom, r0 * cosFrom, r0 * cosTo, r1 * cosTo};
			float[] ys = {r1 * sinFrom, r0 * sinFrom, r0 * sinTo, r1 * sinTo};
			int[] colors = {outerColor, innerColor, innerColor, outerColor};
			// GUI quads are drawn with one winding only (back faces are culled): use the one rectangles use.
			float area = 0.0F;
			for (int v = 0; v < 4; v++) {
				int next = (v + 1) % 4;
				area += xs[v] * ys[next] - xs[next] * ys[v];
			}
			for (int v = 0; v < 4; v++) {
				int index = area > 0.0F ? 3 - v : v;
				consumer.addVertexWith2DPose(pose, xs[index], ys[index]).setColor(colors[index]);
			}
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.noTexture();
	}

	@Override
	@Nullable
	public ScreenRectangle scissorArea() {
		return null;
	}
}
