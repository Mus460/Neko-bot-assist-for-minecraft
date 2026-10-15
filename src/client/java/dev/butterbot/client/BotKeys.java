package dev.butterbot.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.butterbot.ButterBot;
import dev.butterbot.client.screen.BotControlScreen;
import dev.butterbot.client.screen.CommandWheelScreen;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Shortcut keys for the bots. All of them can be changed under Options, Controls, Key Binds,
 * in the "Butter Bot" section. The defaults avoid every vanilla key and the keys Iris uses.
 */
public final class BotKeys {

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ButterBot.id("main"));
	/** How far "do what I'm looking at" reaches. */
	private static final double POINT_RANGE = 64.0D;

	private static final KeyMapping PANEL = key("panel", InputConstants.KEY_B);
	private static final KeyMapping CALL_ALL = key("call_all", InputConstants.KEY_H);
	private static final KeyMapping CONTEXT = key("context", InputConstants.KEY_Z);
	private static final KeyMapping STOP_ALL = key("stop_all", InputConstants.KEY_N);
	private static final KeyMapping HOME_ALL = key("home_all", InputConstants.KEY_J);
	private static final KeyMapping DELIVER_ALL = key("deliver_all", InputConstants.KEY_Y);
	private static final KeyMapping BACKPACK = key("backpack", InputConstants.KEY_V);
	private static final KeyMapping GUARD = key("guard", InputConstants.KEY_U);
	private static final KeyMapping LIGHT = key("light", InputConstants.KEY_I);
	private static final KeyMapping FETCH = key("fetch", InputConstants.KEY_M);
	private static final KeyMapping HUD = key("hud", InputConstants.KEY_RBRACKET);
	private static final KeyMapping JOBS = key("jobs", InputConstants.KEY_LBRACKET);
	private static final KeyMapping DEBUG = key("debug", InputConstants.KEY_K);

	/** What the next press of the context key picks for a bot's job (set from the Job tab). */
	public enum PickKind {CHEST, CENTER, SOURCE}

	private static final long PICK_MILLIS = 30_000L;
	private static UUID pickBot;
	private static PickKind pickKind;
	private static Optional<BlockPos> pickBase = Optional.empty();
	private static long pickUntil;
	private static int pickHintTicks;

	/** Waits for the player to look at a chest (or a spot) and press the context key. */
	public static void beginPick(UUID bot, Optional<BlockPos> base, PickKind kind) {
		pickBot = bot;
		pickKind = kind;
		pickBase = base;
		pickUntil = System.currentTimeMillis() + PICK_MILLIS;
		pickHintTicks = 0;
	}

	private static boolean picking() {
		return pickBot != null && System.currentTimeMillis() < pickUntil;
	}

	/** Holding the panel key this long opens the command wheel instead of the panel. */
	private static final int WHEEL_DELAY_TICKS = 5;
	/** Ticks the panel key has been held, or -1 when it is up. */
	private static int panelHeld = -1;

	/** Tap: the panel. Hold: the command wheel (let go over an order to give it). */
	private static void tickPanelKey(Minecraft minecraft, boolean connected) {
		int taps = 0;
		while (PANEL.consumeClick()) {
			taps++;
		}
		boolean free = connected && minecraft.gui.screen() == null;
		if (PANEL.isDown()) {
			panelHeld++;
			if (panelHeld == WHEEL_DELAY_TICKS && free) {
				pickBot = null;
				minecraft.gui.setScreen(new CommandWheelScreen());
			}
			return;
		}
		// A tap so short it began and ended between two ticks only shows up as a click.
		boolean tapped = panelHeld >= 0 ? panelHeld < WHEEL_DELAY_TICKS : taps > 0;
		panelHeld = -1;
		if (tapped && free) {
			pickBot = null;
			minecraft.gui.setScreen(new BotControlScreen(Optional.empty(), null));
		}
	}

	/** True if {@code event} is the panel key (screens use this to close themselves or end the wheel). */
	public static boolean isPanelKey(KeyEvent event) {
		return PANEL.matches(event);
	}

	public static Component panelKeyName() {
		return PANEL.getTranslatedKeyMessage();
	}

	private static KeyMapping key(String name, int defaultKey) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.butterbot." + name, InputConstants.Type.KEYBOARD, defaultKey, CATEGORY));
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BotKeys::tick);
	}

	private static void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		boolean connected = player != null && minecraft.getConnection() != null
				&& ClientPlayNetworking.canSend(Payloads.Hotkey.TYPE);
		tickPanelKey(minecraft, connected);
		while (HUD.consumeClick()) {
			BotHud.cycleMode();
		}
		send(connected, CALL_ALL, Payloads.Hotkey.CALL_ALL);
		send(connected, STOP_ALL, Payloads.Hotkey.STOP_ALL);
		send(connected, HOME_ALL, Payloads.Hotkey.HOME_ALL);
		send(connected, DELIVER_ALL, Payloads.Hotkey.DELIVER_ALL);
		send(connected, BACKPACK, Payloads.Hotkey.BACKPACK);
		send(connected, GUARD, Payloads.Hotkey.GUARD_TOGGLE);
		send(connected, LIGHT, Payloads.Hotkey.LIGHT);
		send(connected, FETCH, Payloads.Hotkey.FETCH_HELD);
		send(connected, JOBS, Payloads.Hotkey.JOB_TOGGLE);
		send(connected, DEBUG, Payloads.Hotkey.DEBUG);
		if (connected && picking() && pickHintTicks-- <= 0) {
			pickHintTicks = 20;
			player.sendOverlayMessage(Component.translatable(switch (pickKind) {
				case CHEST -> "butterbot.gui.pick_chest_hint";
				case SOURCE -> "butterbot.gui.pick_source_hint";
				case CENTER -> "butterbot.gui.pick_spot_hint";
			}, CONTEXT.getTranslatedKeyMessage()));
		}
		while (CONTEXT.consumeClick()) {
			if (!connected) {
				continue;
			}
			if (picking()) {
				pick(minecraft, player);
			} else {
				pickBot = null;
				pointAt(player);
			}
		}
	}

	/** Sends the looked-at block as the bot's job chest, source chest or work spot, then reopens the panel. */
	private static void pick(Minecraft minecraft, LocalPlayer player) {
		HitResult hit = player.pick(POINT_RANGE, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			player.sendOverlayMessage(Component.translatable("butterbot.gui.pick_nothing"));
			return;
		}
		BlockPos pos = pickKind == PickKind.CENTER ? blockHit.getBlockPos().above() : blockHit.getBlockPos();
		int action = switch (pickKind) {
			case CHEST -> Payloads.BotCommand.JOB_CHEST;
			case SOURCE -> Payloads.BotCommand.JOB_SOURCE;
			case CENTER -> Payloads.BotCommand.JOB_CENTER;
		};
		ClientPlayNetworking.send(new Payloads.BotCommand(pickBase, pickBot, action, "", new int[]{pos.getX(), pos.getY(), pos.getZ()}));
		UUID bot = pickBot;
		pickBot = null;
		minecraft.gui.setScreen(new BotControlScreen(pickBase, bot));
	}

	private static void send(boolean connected, KeyMapping key, int action) {
		while (key.consumeClick()) {
			if (connected) {
				ClientPlayNetworking.send(new Payloads.Hotkey(action, Optional.empty(), -1));
			}
		}
	}

	/** Finds the block or creature under the crosshair, up to {@link #POINT_RANGE} blocks away. */
	private static void pointAt(LocalPlayer player) {
		Vec3 eye = player.getEyePosition(1.0F);
		Vec3 look = player.getViewVector(1.0F);
		Vec3 end = eye.add(look.scale(POINT_RANGE));
		HitResult block = player.pick(POINT_RANGE, 1.0F, false);
		double blockDistanceSq = block.getType() == HitResult.Type.MISS ? POINT_RANGE * POINT_RANGE : block.getLocation().distanceToSqr(eye);
		AABB sweep = player.getBoundingBox().expandTowards(look.scale(POINT_RANGE)).inflate(1.0D);
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, sweep,
				(Entity target) -> !target.isSpectator() && target.isPickable(), blockDistanceSq);
		if (entity != null) {
			ClientPlayNetworking.send(new Payloads.Hotkey(Payloads.Hotkey.CONTEXT, Optional.empty(), entity.getEntity().getId()));
		} else if (block instanceof BlockHitResult blockHit && block.getType() == HitResult.Type.BLOCK) {
			ClientPlayNetworking.send(new Payloads.Hotkey(Payloads.Hotkey.CONTEXT, Optional.of(blockHit.getBlockPos()), -1));
		} else {
			ClientPlayNetworking.send(new Payloads.Hotkey(Payloads.Hotkey.CONTEXT, Optional.empty(), -1));
		}
	}

	private BotKeys() {
	}
}
