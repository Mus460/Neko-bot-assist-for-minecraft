package dev.butterbot.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.butterbot.client.BotHud;
import dev.butterbot.client.BotKeys;
import dev.butterbot.network.Payloads;
import dev.butterbot.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * The command wheel: hold the panel key, point at an order and let go (or click it, or press 1-8).
 * Orders go to every bot around the player, like the shortcut keys. Letting go in the middle does
 * nothing; clicking the middle opens the full panel.
 * <p>
 * Drawn as a real ring of eight pieces: it pops open, the piece under the mouse slides out and lights
 * up, a marker on the hub follows the mouse, and the middle and the line below say what the order
 * does.
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
	private static final float SLICE = (float) (Math.PI * 2.0D / 8.0D);
	/** Gap between two pieces (radians, each side). */
	private static final float GAP = 0.03F;
	private static final float HUB = 30.0F;
	private static final float MAX_OUTER = 98.0F;
	/** How far the piece under the mouse slides out. */
	private static final float POP = 7.0F;
	private static final float OPEN_MILLIS = 160.0F;
	/** How fast the highlight follows the mouse (higher is snappier). */
	private static final float EASE_SPEED = 16.0F;

	private static final int PIECE_IN = 0xC8101318;
	private static final int PIECE_OUT = 0xC8262C38;
	private static final int LIT_IN = 0xE87A5410;
	private static final int LIT_OUT = 0xF0EDBB45;
	private static final int RIM = 0x40FFFFFF;
	private static final int LIT_RIM = 0xFFFFE7A0;
	private static final int HUB_IN = 0xF00C0E12;
	private static final int HUB_OUT = 0xF01A1F27;
	private static final int ACCENT = 0xFFF3C24E;
	private static final int TEXT = 0xFFE9ECF1;
	private static final int TEXT_DIM = 0xFFA4ABB8;

	private final long openedAt = Util.getMillis();
	private long lastFrame = openedAt;
	/** 0..1 per piece: how lit it is right now (eases towards 1 for the piece under the mouse). */
	private final float[] lit = new float[OPTIONS.length];
	/** The hub marker's angle, easing after the mouse. */
	private float marker = Float.NaN;
	private float markerShown;
	private int hovered = -1;
	private boolean chosen;

	public CommandWheelScreen() {
		super(Component.translatable("butterbot.ui.wheel.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Keeps the world visible: just a light shade (fading in) instead of the usual blur. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, width, height, withAlpha(0x60000000, openProgress()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		long now = Util.getMillis();
		float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
		lastFrame = now;
		float fade = openProgress();
		float scale = Math.max(0.05F, easeOutBack(fade));

		int nowHovered = optionAt(mouseX, mouseY);
		if (nowHovered != hovered) {
			hovered = nowHovered;
			if (hovered >= 0) {
				playClick(1.9F, 0.15F);
			}
		}
		float ease = 1.0F - (float) Math.exp(-EASE_SPEED * dt);
		for (int i = 0; i < OPTIONS.length; i++) {
			lit[i] += ((i == hovered ? 1.0F : 0.0F) - lit[i]) * ease;
		}
		float centerX = width / 2.0F;
		float centerY = height / 2.0F;
		updateMarker(mouseX - centerX, mouseY - centerY, ease);

		float outer = outerRadius();
		g.pose().pushMatrix();
		g.pose().translate(centerX, centerY);
		g.pose().scale(scale, scale);
		drawPieces(g, outer, fade);
		drawHub(g, fade);
		g.pose().popMatrix();

		drawCaptions(g, centerX, centerY, outer, fade);
	}

	private void drawPieces(GuiGraphicsExtractor g, float outer, float fade) {
		for (int i = 0; i < OPTIONS.length; i++) {
			float glow = lit[i];
			float mid = angleOf(i);
			float from = mid - SLICE / 2.0F + GAP;
			float to = mid + SLICE / 2.0F - GAP;
			float push = glow * POP;
			float inner = HUB + 4.0F + push;
			float out = outer + push;
			RingSector.draw(g, inner, out, from, to, withAlpha(mix(PIECE_IN, LIT_IN, glow), fade),
					withAlpha(mix(PIECE_OUT, LIT_OUT, glow), fade));
			// A thin bright rim along the outside edge.
			RingSector.draw(g, out - 1.5F, out, from, to, withAlpha(mix(RIM, LIT_RIM, glow), fade),
					withAlpha(mix(RIM, LIT_RIM, glow), fade));

			// Icon (bigger when lit) with its name under it.
			float iconRadius = (inner + out) / 2.0F - 3.0F;
			float x = (float) Math.cos(mid) * iconRadius;
			float y = (float) Math.sin(mid) * iconRadius;
			float iconScale = 1.0F + 0.35F * glow;
			g.pose().pushMatrix();
			g.pose().translate(x, y - 3.0F * glow);
			g.pose().scale(iconScale, iconScale);
			g.item(new ItemStack(OPTIONS[i].icon()), -8, -10);
			g.pose().popMatrix();

			String label = font.plainSubstrByWidth(name(i).getString(), 84);
			g.pose().pushMatrix();
			g.pose().translate(x, y + 9.0F + 2.0F * glow);
			g.pose().scale(0.75F, 0.75F);
			g.text(font, label, -font.width(label) / 2, 0, withAlpha(mix(TEXT_DIM, 0xFFFFFFFF, glow), fade), true);
			g.pose().popMatrix();

			// Its number, for picking it with the keyboard.
			float numberRadius = out - 8.0F;
			float numberAngle = to - 0.12F;
			g.pose().pushMatrix();
			g.pose().translate((float) Math.cos(numberAngle) * numberRadius, (float) Math.sin(numberAngle) * numberRadius);
			g.pose().scale(0.6F, 0.6F);
			String number = String.valueOf(i + 1);
			g.text(font, number, -font.width(number) / 2, -4, withAlpha(0x90FFFFFF, fade), false);
			g.pose().popMatrix();
		}
	}

	private void drawHub(GuiGraphicsExtractor g, float fade) {
		RingSector.draw(g, 0.0F, HUB, 0.0F, (float) (Math.PI * 2.0D), withAlpha(HUB_IN, fade), withAlpha(HUB_OUT, fade));
		RingSector.draw(g, HUB - 1.5F, HUB, 0.0F, (float) (Math.PI * 2.0D), withAlpha(0x50FFFFFF, fade), withAlpha(0x50FFFFFF, fade));
		if (!Float.isNaN(marker) && markerShown > 0.01F) {
			// The marker on the hub's edge, pointing where the mouse is.
			RingSector.draw(g, HUB - 3.0F, HUB + 2.0F, marker - 0.26F, marker + 0.26F,
					withAlpha(ACCENT, fade * markerShown), withAlpha(ACCENT, fade * markerShown));
		}
		if (hovered < 0) {
			// Nothing picked: the middle opens the panel.
			g.item(new ItemStack(ModItems.BUTTER_BOT), -8, -13);
			String panel = Component.translatable("butterbot.ui.wheel.hub").getString();
			g.pose().pushMatrix();
			g.pose().translate(0.0F, 6.0F);
			g.pose().scale(0.75F, 0.75F);
			g.text(font, panel, -font.width(panel) / 2, 0, withAlpha(TEXT_DIM, fade), false);
			g.pose().popMatrix();
			return;
		}
		// The order's name, up to two lines.
		List<FormattedCharSequence> lines = font.split(name(hovered), (int) ((HUB * 2.0F - 8.0F) / 0.8F));
		int count = Math.min(2, lines.size());
		g.pose().pushMatrix();
		g.pose().scale(0.8F, 0.8F);
		int top = -count * 10 / 2;
		for (int i = 0; i < count; i++) {
			FormattedCharSequence line = lines.get(i);
			g.text(font, line, -font.width(line) / 2, top + i * 10 + 1, withAlpha(ACCENT, fade), false);
		}
		g.pose().popMatrix();
	}

	/** Above the wheel: how many bots will listen. Below: what the order does, and the keys. */
	private void drawCaptions(GuiGraphicsExtractor g, float centerX, float centerY, float outer, float fade) {
		int bots = BotHud.botsInReach();
		Component title = bots < 0 ? this.title : bots == 0 ? Component.translatable("butterbot.ui.wheel.nearby_none")
				: Component.translatable("butterbot.ui.wheel.nearby", bots);
		int titleY = (int) (centerY - outer - POP - 14.0F);
		g.text(font, title, (int) centerX - font.width(title) / 2, titleY, withAlpha(bots == 0 ? 0xFFFF8A80 : TEXT, fade), true);

		Component line = hovered >= 0 ? Component.translatable("butterbot.ui.wheel." + OPTIONS[hovered].key() + ".desc")
				: Component.translatable("butterbot.ui.wheel.hint", BotKeys.panelKeyName());
		int lineY = (int) (centerY + outer + POP + 6.0F);
		String text = font.plainSubstrByWidth(line.getString(), width - 16);
		g.text(font, text, (int) centerX - font.width(text) / 2, lineY, withAlpha(hovered >= 0 ? TEXT : TEXT_DIM, fade), true);

		Component keys = hovered >= 0 ? Component.translatable("butterbot.ui.wheel.release", BotKeys.panelKeyName())
				: Component.translatable("butterbot.ui.wheel.keys");
		String keyText = font.plainSubstrByWidth(keys.getString(), width - 16);
		g.pose().pushMatrix();
		g.pose().translate(centerX, lineY + 12.0F);
		g.pose().scale(0.75F, 0.75F);
		g.text(font, keyText, -font.width(keyText) / 2, 0, withAlpha(TEXT_DIM, fade), true);
		g.pose().popMatrix();
	}

	/** The marker eases round the shorter way after the mouse, and fades out near the middle. */
	private void updateMarker(float dx, float dy, float ease) {
		boolean away = dx * dx + dy * dy >= HUB * HUB * 0.36F;
		markerShown += ((away ? 1.0F : 0.0F) - markerShown) * ease;
		if (!away) {
			return;
		}
		float target = (float) Math.atan2(dy, dx);
		if (Float.isNaN(marker)) {
			marker = target;
			return;
		}
		float diff = target - marker;
		diff -= (float) (Math.PI * 2.0D) * (float) Math.floor((diff + Math.PI) / (Math.PI * 2.0D));
		marker += diff * Math.min(1.0F, ease * 1.6F);
	}

	private float outerRadius() {
		// Small windows: the wheel shrinks so the captions above and below still fit.
		return Math.max(HUB + 40.0F, Math.min(MAX_OUTER, height / 2.0F - POP - 34.0F));
	}

	private float openProgress() {
		return Math.min(1.0F, (Util.getMillis() - openedAt) / OPEN_MILLIS);
	}

	private static Component name(int option) {
		return Component.translatable("butterbot.ui.wheel." + OPTIONS[option].key());
	}

	/** Piece {@code index}: the first at the top, then clockwise. */
	private static float angleOf(int index) {
		return (float) (-Math.PI / 2.0D) + index * SLICE;
	}

	/** Which order the mouse points at: the piece of the circle it is in, or -1 near the middle. */
	private int optionAt(double mouseX, double mouseY) {
		double dx = mouseX - width / 2.0D;
		double dy = mouseY - height / 2.0D;
		if (dx * dx + dy * dy < HUB * HUB) {
			return -1;
		}
		double angle = Math.atan2(dy, dx) + Math.PI / 2.0D;
		angle = (angle % (Math.PI * 2.0D) + Math.PI * 2.0D) % (Math.PI * 2.0D);
		return (int) Math.floor((angle + SLICE / 2.0D) / SLICE) % OPTIONS.length;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int number = event.key() - InputConstants.KEY_1;
		if (number >= 0 && number < OPTIONS.length && event.key() <= InputConstants.KEY_8) {
			run(number);
			return true;
		}
		return super.keyPressed(event);
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
				playClick(1.0F, 0.3F);
				minecraft.gui.setScreen(new BotControlScreen(Optional.empty(), null));
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void run(int option) {
		if (chosen) {
			return;
		}
		chosen = true;
		if (ClientPlayNetworking.canSend(Payloads.Hotkey.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Hotkey(OPTIONS[option].hotkey(), Optional.empty(), -1));
		}
		playClick(1.0F, 0.35F);
		onClose();
	}

	private void playClick(float pitch, float volume) {
		if (minecraft != null) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
		}
	}

	/** Overshoots a little and settles: the wheel pops open. */
	private static float easeOutBack(float t) {
		float c1 = 1.4F;
		float c3 = c1 + 1.0F;
		float u = t - 1.0F;
		return 1.0F + c3 * u * u * u + c1 * u * u;
	}

	private static int withAlpha(int color, float factor) {
		int alpha = Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, factor)));
		return (alpha << 24) | (color & 0xFFFFFF);
	}

	/** Blends two colours (alpha too): {@code t} 0 is {@code from}, 1 is {@code to}. */
	private static int mix(int from, int to, float t) {
		int result = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			int a = (from >>> shift) & 0xFF;
			int b = (to >>> shift) & 0xFF;
			result |= (Math.round(a + (b - a) * t) & 0xFF) << shift;
		}
		return result;
	}
}
