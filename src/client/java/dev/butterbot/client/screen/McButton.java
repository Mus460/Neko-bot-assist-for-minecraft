package dev.butterbot.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A normal Minecraft button that can also show an item icon, a big "tile" with the icon above the
 * label, or an on/off lamp. Labels and states can follow the bot live, so the panel doesn't have to
 * be rebuilt (and text boxes don't lose focus) whenever new data arrives.
 */
public class McButton extends Button {

	public enum Layout {TEXT, ICON, TILE, TOGGLE, TAB, SLOT}

	private final Layout layout;
	@Nullable
	private final ItemStack icon;
	@Nullable
	private Supplier<Component> label;
	@Nullable
	private BooleanSupplier lamp;
	@Nullable
	private BooleanSupplier selected;
	private boolean danger;

	public McButton(int x, int y, int width, int height, Component message, @Nullable ItemStack icon, Layout layout, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		this.layout = layout;
		this.icon = icon == null || icon.isEmpty() ? null : icon;
		setOverrideRenderHighlightedSprite(() -> isHoveredOrFocused() || isSelectedNow());
	}

	public static McButton text(int x, int y, int w, int h, Component message, OnPress onPress) {
		return new McButton(x, y, w, h, message, null, Layout.TEXT, onPress);
	}

	public static McButton icon(int x, int y, int w, int h, Component message, ItemStack icon, OnPress onPress) {
		return new McButton(x, y, w, h, message, icon, Layout.ICON, onPress);
	}

	public static McButton tile(int x, int y, int w, int h, Component message, ItemStack icon, OnPress onPress) {
		return new McButton(x, y, w, h, message, icon, Layout.TILE, onPress);
	}

	public static McButton toggle(int x, int y, int w, int h, Component message, @Nullable ItemStack icon, BooleanSupplier on,
								  OnPress onPress) {
		return new McButton(x, y, w, h, message, icon, Layout.TOGGLE, onPress).lamp(on);
	}

	public McButton label(Supplier<Component> label) {
		this.label = label;
		return this;
	}

	public McButton lamp(BooleanSupplier lamp) {
		this.lamp = lamp;
		return this;
	}

	public McButton selected(BooleanSupplier selected) {
		this.selected = selected;
		return this;
	}

	public McButton danger() {
		this.danger = true;
		return this;
	}

	public McButton tip(Component tooltip) {
		setTooltip(Tooltip.create(tooltip));
		return this;
	}

	public McButton enabled(boolean enabled) {
		this.active = enabled;
		return this;
	}

	private boolean isSelectedNow() {
		return selected != null && selected.getAsBoolean();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Font font = Minecraft.getInstance().font;
		Component text = label != null ? label.get() : getMessage();

		if (layout == Layout.TAB) {
			McStyle.tab(g, x, y, w, h, isSelectedNow());
			if (icon != null) {
				g.item(icon, x + 5, y + 4);
			}
			String tabText = font.plainSubstrByWidth(text.getString(), w - (icon != null ? 27 : 8));
			g.text(font, tabText, x + (icon != null ? 23 : 4), y + 8, McStyle.TEXT, false);
			return;
		}

		extractDefaultSprite(g);
		if (isSelectedNow()) {
			g.outline(x, y, w, h, McStyle.WHITE);
		}
		int color = !active ? McStyle.TEXT_LIGHT_DIM : danger ? McStyle.BAD_LIGHT : McStyle.TEXT_LIGHT;
		switch (layout) {
			case TEXT -> {
				String s = font.plainSubstrByWidth(text.getString(), w - 6);
				g.text(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, color, true);
			}
			case ICON -> {
				int textX = x + 4;
				if (icon != null) {
					g.item(icon, x + 3, y + (h - 16) / 2);
					textX = x + 22;
				}
				String s = font.plainSubstrByWidth(text.getString(), x + w - 4 - textX);
				int free = x + w - 3 - textX;
				g.text(font, s, textX + Math.max(0, (free - font.width(s)) / 2), y + (h - 8) / 2, color, true);
			}
			case TILE -> {
				if (icon != null) {
					g.item(icon, x + (w - 16) / 2, y + 4);
				}
				String s = font.plainSubstrByWidth(text.getString(), w - 4);
				g.text(font, s, x + (w - font.width(s)) / 2, y + h - 12, color, true);
			}
			case TOGGLE -> {
				int textX = x + 5;
				if (icon != null) {
					g.item(icon, x + 3, y + (h - 16) / 2);
					textX = x + 22;
				}
				boolean on = lamp != null && lamp.getAsBoolean();
				int lampX = x + w - 12;
				int lampY = y + (h - 8) / 2;
				g.fill(lampX - 1, lampY - 1, lampX + 9, lampY + 9, McStyle.BLACK);
				g.fill(lampX, lampY, lampX + 8, lampY + 8, on ? 0xFF3FD13F : 0xFF6B1F1F);
				g.fill(lampX + 1, lampY + 1, lampX + 4, lampY + 3, on ? 0xFFB6FFB6 : 0xFFA04848);
				String s = font.plainSubstrByWidth(text.getString(), lampX - 4 - textX);
				g.text(font, s, textX, y + (h - 8) / 2, color, true);
			}
			case SLOT -> {
				if (icon != null) {
					g.item(icon, x + (w - 16) / 2, y + (h - 16) / 2);
				}
			}
			case TAB -> {
			}
		}
	}
}
