package dev.butterbot.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.butterbot.client.BotKeys;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * The command wheel: hold the panel key, point at an order and let go (or click it). Orders go to
 * every bot around the player, like the shortcut keys. Letting go in the middle does nothing;
 * clicking the middle opens the full panel.
 */
public class CommandWheelScreen extends Screen {

	private record Option(String key, Item icon, int hotkey) {
	}

	private static final Option[] OPTIONS = {
			new Option("call", Items.LEAD, Payloads.Hotkey.CALL_ALL),
			new Option("stop", Items.BARRIER, Payloads.Hotkey.STOP_ALL),
			new Option("home", Items.BED.pick(DyeColor.RED), Payloads.Hotkey.HOME_ALL),
			new Option("deliver", Items.HOPPER, Payloads.Hotkey.DELIVER_ALL),
			new Option("backpack", Items.BUNDLE, Payloads.Hotkey.BACKPACK),
			new Option("light", Items.TORCH, Payloads.Hotkey.LIGHT),
			new Option("jobs", Items.BELL, Payloads.Hotkey.JOB_TOGGLE),
			new Option("guard", Items.IRON_SWORD, Payloads.Hotkey.GUARD_TOGGLE),
	};
	private static final int RADIUS_X = 92;
	private static final int RADIUS_Y = 66;
	private static final int BOX_W = 80;
	private static final int BOX_H = 34;
	private static final int DEAD_ZONE = 24;

	private int hovered = -1;

	public CommandWheelScreen() {
		super(Component.translatable("butterbot.ui.wheel.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Keeps the world visible: just a light shade instead of the usual blur. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, width, height, 0x50000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int centerX = width / 2;
		int centerY = height / 2;
		hovered = optionAt(mouseX, mouseY);

		for (int i = 0; i < OPTIONS.length; i++) {
			int x = boxX(i);
			int y = boxY(i);
			boolean lit = i == hovered;
			if (lit) {
				g.fill(x - 2, y - 2, x + BOX_W + 2, y + BOX_H + 2, McStyle.WHITE);
			}
			McStyle.panel(g, x, y, BOX_W, BOX_H);
			if (lit) {
				g.fill(x + 3, y + 3, x + BOX_W - 3, y + BOX_H - 3, 0xFFDCDCDC);
			}
			g.item(new ItemStack(OPTIONS[i].icon()), x + (BOX_W - 16) / 2, y + 4);
			String label = font.plainSubstrByWidth(Component.translatable("butterbot.ui.wheel." + OPTIONS[i].key()).getString(), BOX_W - 8);
			g.text(font, label, x + (BOX_W - font.width(label)) / 2, y + 22, McStyle.TEXT, false);
		}

		McStyle.panel(g, centerX - 44, centerY - 14, 88, 28);
		Component middle = hovered >= 0 ? Component.translatable("butterbot.ui.wheel.release", BotKeys.panelKeyName())
				: Component.translatable("butterbot.ui.wheel.panel");
		String text = font.plainSubstrByWidth(middle.getString(), 80);
		g.text(font, text, centerX - font.width(text) / 2, centerY - 4, McStyle.TEXT, false);

		Component hint = Component.translatable("butterbot.ui.wheel.hint", BotKeys.panelKeyName());
		g.text(font, hint, centerX - font.width(hint) / 2, centerY + RADIUS_Y + BOX_H / 2 + 8, McStyle.TEXT_LIGHT, true);
	}

	private int boxX(int index) {
		double angle = -Math.PI / 2.0D + index * (Math.PI / 4.0D);
		return width / 2 + (int) Math.round(Math.cos(angle) * RADIUS_X) - BOX_W / 2;
	}

	private int boxY(int index) {
		double angle = -Math.PI / 2.0D + index * (Math.PI / 4.0D);
		return height / 2 + (int) Math.round(Math.sin(angle) * RADIUS_Y) - BOX_H / 2;
	}

	/** Which order the mouse points at: the eighth of the circle it is in, or -1 near the middle. */
	private int optionAt(double mouseX, double mouseY) {
		double dx = mouseX - width / 2.0D;
		double dy = mouseY - height / 2.0D;
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
			return -1;
		}
		double angle = Math.atan2(dy * RADIUS_X / (double) RADIUS_Y, dx) + Math.PI / 2.0D;
		angle = (angle % (Math.PI * 2.0D) + Math.PI * 2.0D) % (Math.PI * 2.0D);
		return (int) Math.floor((angle + Math.PI / 8.0D) / (Math.PI / 4.0D)) % OPTIONS.length;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (BotKeys.isPanelKey(event)) {
			if (hovered >= 0) {
				run(hovered);
			} else {
				onClose();
			}
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			int option = optionAt(event.x(), event.y());
			if (option >= 0) {
				run(option);
			} else if (minecraft != null) {
				minecraft.gui.setScreen(new BotControlScreen(Optional.empty(), null));
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void run(int option) {
		if (ClientPlayNetworking.canSend(Payloads.Hotkey.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Hotkey(OPTIONS[option].hotkey(), Optional.empty(), -1));
		}
		onClose();
	}
}
