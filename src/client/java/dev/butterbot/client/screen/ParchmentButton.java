package dev.butterbot.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Flat buttons in the Bot control panel's "notebook" style: purple ribbons and parchment buttons. */
public class ParchmentButton extends Button {

	public enum Style {RIBBON, PARCHMENT, DANGER, ACCENT}

	private final Style style;
	private boolean selected;

	public ParchmentButton(int x, int y, int width, int height, Component message, OnPress onPress, Style style) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.style = style;
	}

	public static ParchmentButton ribbon(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new ParchmentButton(x, y, w, h, message, onPress, Style.RIBBON);
	}

	public static ParchmentButton parchment(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new ParchmentButton(x, y, w, h, message, onPress, Style.PARCHMENT);
	}

	public static ParchmentButton danger(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new ParchmentButton(x, y, w, h, message, onPress, Style.DANGER);
	}

	public static ParchmentButton accent(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new ParchmentButton(x, y, w, h, message, onPress, Style.ACCENT);
	}

	public ParchmentButton selected(boolean selected) {
		this.selected = selected;
		return this;
	}

	public boolean isSelected() {
		return selected;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hovered = this.isHoveredOrFocused();
		int background;
		int border;
		int text;
		boolean shadow;
		switch (style) {
			case RIBBON -> {
				background = selected ? 0xFF6E36B4 : hovered ? 0xFF5B2D96 : 0xFF473068;
				border = selected ? 0xFF9B6BE0 : 0xFF2C1B45;
				text = 0xFFFFFFFF;
				shadow = true;
			}
			case DANGER -> {
				background = !active ? 0xFFCDBE99 : hovered ? 0xFFF3C7B8 : 0xFFE9B8A6;
				border = 0xFF8A2B1C;
				text = active ? 0xFF6B1A0E : 0xFF80714F;
				shadow = false;
			}
			case ACCENT -> {
				background = !active ? 0xFFCDBE99 : hovered ? 0xFFC9E8B0 : 0xFFB6DB98;
				border = 0xFF3D6B22;
				text = active ? 0xFF1F3D10 : 0xFF80714F;
				shadow = false;
			}
			default -> {
				background = !active ? 0xFFCDBE99 : selected ? 0xFFFAF2D4 : hovered ? 0xFFF9F0D8 : 0xFFEBDDB6;
				border = selected ? 0xFFC9A227 : 0xFF6B4A2F;
				text = active ? 0xFF3F2A18 : 0xFF80714F;
				shadow = false;
			}
		}

		int x = getX();
		int y = getY();
		graphics.fill(x, y, x + width, y + height, border);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, style == Style.RIBBON ? 0x30FFFFFF : 0x60FFFFFF);
		if (selected && style != Style.RIBBON) {
			graphics.outline(x - 1, y - 1, width + 2, height + 2, 0xFFC9A227);
		}

		Font font = Minecraft.getInstance().font;
		Component message = getMessage();
		String plain = message.getString();
		int maxWidth = width - 6;
		if (font.width(message) > maxWidth) {
			plain = font.plainSubstrByWidth(plain, maxWidth - font.width("..")) + "..";
			int textX = x + (width - font.width(plain)) / 2;
			graphics.text(font, plain, textX, y + (height - 8) / 2, text, shadow);
		} else {
			int textX = x + (width - font.width(message)) / 2;
			graphics.text(font, message, textX, y + (height - 8) / 2, text, shadow);
		}
	}
}
