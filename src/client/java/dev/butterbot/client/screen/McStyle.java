package dev.butterbot.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Drawing helpers that copy the look of vanilla inventories: grey windows, sunken boxes and slots. */
final class McStyle {

	static final int TEXT = 0xFF404040;
	static final int TEXT_DIM = 0xFF6F6F6F;
	static final int TEXT_LIGHT = 0xFFFFFFFF;
	static final int TEXT_LIGHT_DIM = 0xFFAAAAAA;
	static final int GOOD = 0xFF2F7A1F;
	static final int GOOD_LIGHT = 0xFF7CFC5A;
	static final int BAD = 0xFFAA2A20;
	static final int BAD_LIGHT = 0xFFFF6B5E;
	static final int GOLD_LIGHT = 0xFFFFD45A;
	static final int HEALTH = 0xFFE03A3A;
	static final int XP = 0xFF80FF20;
	static final int PANEL = 0xFFC6C6C6;
	static final int TAB_IDLE = 0xFFA6A6A6;
	static final int SLOT = 0xFF8B8B8B;
	static final int SHADOW = 0xFF555555;
	static final int DARK = 0xFF373737;
	static final int WHITE = 0xFFFFFFFF;
	static final int BLACK = 0xFF000000;

	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	/** A window like the inventory: black rim, white light from the top left, grey shade at the bottom right. */
	static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int r = x + w;
		int b = y + h;
		g.fill(x + 1, y + 1, r - 1, b - 1, PANEL);
		g.fill(x + 2, y, r - 2, y + 1, BLACK);
		g.fill(x + 2, b - 1, r - 2, b, BLACK);
		g.fill(x, y + 2, x + 1, b - 2, BLACK);
		g.fill(r - 1, y + 2, r, b - 2, BLACK);
		g.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
		g.fill(r - 2, y + 1, r - 1, y + 2, BLACK);
		g.fill(x + 1, b - 2, x + 2, b - 1, BLACK);
		g.fill(r - 2, b - 2, r - 1, b - 1, BLACK);
		g.fill(x + 2, y + 1, r - 3, y + 3, WHITE);
		g.fill(x + 1, y + 2, x + 3, b - 3, WHITE);
		g.fill(x + 3, b - 3, r - 2, b - 1, SHADOW);
		g.fill(r - 3, y + 3, r - 1, b - 2, SHADOW);
	}

	/** A sunken box (like a slot or the player preview in the inventory). */
	static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
		g.fill(x, y, x + w, y + h, fill);
		g.fill(x, y, x + w - 1, y + 1, DARK);
		g.fill(x, y, x + 1, y + h - 1, DARK);
		g.fill(x + 1, y + h - 1, x + w, y + h, WHITE);
		g.fill(x + w - 1, y + 1, x + w, y + h, WHITE);
	}

	/** One inventory slot (18x18). */
	static void slot(GuiGraphicsExtractor g, int x, int y) {
		g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
	}

	/** A thin progress bar in a dark groove. */
	static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float progress, int color) {
		g.fill(x, y, x + w, y + h, DARK);
		int filled = Math.round((w - 2) * Math.clamp(progress, 0.0F, 1.0F));
		if (filled > 0) {
			g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, color);
		}
	}

	/** A tab on top of a window, like the creative inventory's. The selected one runs into the window below. */
	static void tab(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean selected) {
		int r = x + w;
		int b = y + h;
		int body = selected ? PANEL : TAB_IDLE;
		g.fill(x + 1, y + 1, r - 1, b, body);
		g.fill(x + 2, y, r - 2, y + 1, BLACK);
		g.fill(x + 1, y + 1, x + 2, y + 2, BLACK);
		g.fill(r - 2, y + 1, r - 1, y + 2, BLACK);
		g.fill(x, y + 2, x + 1, b, BLACK);
		g.fill(r - 1, y + 2, r, b, BLACK);
		g.fill(x + 2, y + 1, r - 2, y + 3, WHITE);
		g.fill(x + 1, y + 2, x + 3, b, WHITE);
		g.fill(r - 3, y + 3, r - 1, b, selected ? SHADOW : 0xFF6E6E6E);
		if (!selected) {
			g.fill(x, b - 1, r, b, BLACK);
		}
	}

	private McStyle() {
	}
}
