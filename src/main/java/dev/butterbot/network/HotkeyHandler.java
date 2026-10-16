package dev.butterbot.network;

import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModBlocks;
import dev.butterbot.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shortcut keys: orders for all of the player's bots at once, or for the nearest one. */
public final class HotkeyHandler {

	private static final double CONTEXT_RANGE_SQ = 72.0D * 72.0D;
	private static final double ATTACK_HELPERS_SQ = 48.0D * 48.0D;
	private static final int CONTEXT_MINE_COUNT = 16;
	private static final int CONTEXT_WOOD_COUNT = 32;

	public static void handle(ServerPlayer player, Payloads.Hotkey payload) {
		List<ButterBotEntity> all = BotAccess.listBots(player, Optional.empty());
		if (payload.action() == Payloads.Hotkey.JOB_TOGGLE) {
			toggleJobs(player, all);
			return;
		}
		// Bots with a job are listed from anywhere, but "all my bots" orders are for the ones around the player.
		List<ButterBotEntity> bots = new ArrayList<>();
		for (ButterBotEntity bot : all) {
			if (BotAccess.isNearby(player, bot)) {
				bots.add(bot);
			}
		}
		if (bots.isEmpty()) {
			fail(player, Component.translatable("butterbot.msg.no_bot_nearby", (int) BotAccess.DIRECT_RANGE));
			return;
		}
		ButterBotEntity nearest = bots.getFirst();
		switch (payload.action()) {
			case Payloads.Hotkey.CALL_ALL -> {
				for (ButterBotEntity bot : bots) {
					bot.pauseJob();
					bot.assignFollowTask();
				}
				tell(player, Component.translatable("butterbot.msg.hotkey_call", bots.size()));
			}
			case Payloads.Hotkey.HOME_ALL -> {
				int sent = 0;
				for (ButterBotEntity bot : bots) {
					if (bot.getBasePos() != null) {
						bot.pauseJob();
						bot.assignGoToBaseTask();
						sent++;
					}
				}
				if (sent == 0) {
					fail(player, Component.translatable("butterbot.msg.no_base"));
				} else {
					tell(player, Component.translatable("butterbot.msg.hotkey_home", sent));
				}
			}
			case Payloads.Hotkey.STOP_ALL -> {
				for (ButterBotEntity bot : bots) {
					bot.pauseJob();
					bot.clearTask();
					bot.clearOrderedTarget();
					bot.say(Component.translatable("butterbot.msg.stopping"));
				}
				tell(player, Component.translatable("butterbot.msg.hotkey_stop", bots.size()));
			}
			case Payloads.Hotkey.DELIVER_ALL -> {
				int sent = 0;
				for (ButterBotEntity bot : bots) {
					if (bot.hasLoot()) {
						bot.requestDelivery();
						sent++;
					}
				}
				tell(player, sent == 0 ? Component.translatable("butterbot.msg.nothing_to_deliver")
						: Component.translatable("butterbot.msg.hotkey_deliver", sent));
			}
			case Payloads.Hotkey.BACKPACK -> {
				if (player.distanceToSqr(nearest) > 8.0D * 8.0D) {
					fail(player, Component.translatable("butterbot.msg.too_far_backpack"));
				} else {
					nearest.openBackpack(player);
				}
			}
			case Payloads.Hotkey.GUARD_TOGGLE -> {
				boolean turnOn = bots.stream().anyMatch(bot -> !bot.guardSetting());
				for (ButterBotEntity bot : bots) {
					bot.setGuarding(turnOn);
					bot.say(Component.translatable(turnOn ? "butterbot.msg.guard_on" : "butterbot.msg.guard_off"));
				}
				tell(player, Component.translatable(turnOn ? "butterbot.msg.hotkey_guard_on" : "butterbot.msg.hotkey_guard_off", bots.size()));
			}
			case Payloads.Hotkey.LIGHT -> nearest.assignLightTask(player.blockPosition(), LightAreaAction.DEFAULT_RADIUS);
			case Payloads.Hotkey.DEBUG -> BotDebug.toggle(player, bots);
			case Payloads.Hotkey.FETCH_HELD -> {
				ItemStack held = player.getMainHandItem();
				if (held.isEmpty()) {
					fail(player, Component.translatable("butterbot.msg.hold_item"));
				} else {
					nearest.assignFetchTask(held.getItem(), held.getMaxStackSize());
				}
			}
			case Payloads.Hotkey.CONTEXT -> context(player, bots, payload);
			default -> {
			}
		}
	}

	/** Pauses every bot that is working at its job; if none is, puts every bot with a job back to work. */
	private static void toggleJobs(ServerPlayer player, List<ButterBotEntity> bots) {
		List<ButterBotEntity> withJob = new ArrayList<>();
		for (ButterBotEntity bot : bots) {
			if (bot.hasJob()) {
				withJob.add(bot);
			}
		}
		if (withJob.isEmpty()) {
			fail(player, Component.translatable("butterbot.msg.hotkey_no_jobs"));
			return;
		}
		boolean pause = withJob.stream().anyMatch(bot -> !bot.getJobs().isPaused());
		for (ButterBotEntity bot : withJob) {
			JobControl.setPaused(bot, pause);
		}
		tell(player, Component.translatable(pause ? "butterbot.msg.hotkey_jobs_paused" : "butterbot.msg.hotkey_jobs_resumed",
				withJob.size()));
	}

	/** "Do what I'm looking at": mine an ore, fell a tree, farm, sort chests, attack, or just go there. */
	private static void context(ServerPlayer player, List<ButterBotEntity> bots, Payloads.Hotkey payload) {
		ServerLevel level = player.level();
		ButterBotEntity nearest = bots.getFirst();
		if (payload.entity() >= 0) {
			Entity entity = level.getEntity(payload.entity());
			if (entity == null || entity.distanceToSqr(player) > CONTEXT_RANGE_SQ) {
				return;
			}
			if (entity instanceof ButterBotEntity bot && bot.isOwnedBy(player)) {
				ModNetworking.openBotScreen(player, bot);
				return;
			}
			if (entity instanceof Mob mob && canBeOrderedToAttack(mob)) {
				int helpers = 0;
				for (ButterBotEntity bot : bots) {
					if (bot.distanceToSqr(mob) < ATTACK_HELPERS_SQ) {
						bot.orderAttack(mob);
						helpers++;
					}
				}
				if (helpers == 0) {
					nearest.orderAttack(mob);
					helpers = 1;
				}
				tell(player, Component.translatable("butterbot.msg.hotkey_attack", helpers, mob.getName()));
				return;
			}
		}
		if (payload.block().isEmpty()) {
			nearest.assignFollowTask();
			return;
		}
		BlockPos pos = payload.block().get();
		if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > CONTEXT_RANGE_SQ) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (state.is(ModBlocks.BOT_BASE)) {
			nearest.setBasePos(pos);
			nearest.assignGoToBaseTask();
			return;
		}
		if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
			nearest.assignSortTask();
			return;
		}
		BotResource resource = mineableResource(state);
		if (resource != null) {
			if (nearest.assignMineTask(resource, resource == BotResource.WOOD ? CONTEXT_WOOD_COUNT : CONTEXT_MINE_COUNT)) {
				nearest.setTargetBlock(pos);
			}
			return;
		}
		if (isFarmBlock(state)) {
			nearest.assignFarmTask(pos);
			return;
		}
		// Anything else: walk there and wait. Stand on top of solid blocks, inside open ones (grass, snow).
		BlockPos spot = state.getCollisionShape(level, pos).isEmpty() ? pos : pos.above();
		nearest.assignGoToTask(spot);
	}

	/** Pointing at ores and logs means "mine that". Plain stone, dirt and sand mean "go there". */
	private static BotResource mineableResource(BlockState state) {
		for (BotResource resource : BotResource.values()) {
			if (resource != BotResource.STONE && resource != BotResource.SAND && resource.matchesBlock(state)) {
				return resource;
			}
		}
		return null;
	}

	private static boolean isFarmBlock(BlockState state) {
		return state.getBlock() instanceof CropBlock || state.getBlock() instanceof NetherWartBlock
				|| state.getBlock() instanceof SweetBerryBushBlock || state.getBlock() instanceof CocoaBlock
				|| state.is(Blocks.FARMLAND) || state.is(Blocks.SUGAR_CANE);
	}

	/** Anything the owner points at (the Warden and bosses too), except villagers, golems, pets and other bots. */
	private static boolean canBeOrderedToAttack(Mob mob) {
		if (mob instanceof ButterBotEntity || mob instanceof Npc || mob instanceof IronGolem) {
			return false;
		}
		return !(mob instanceof TamableAnimal tamable && tamable.isTame());
	}

	private static void tell(ServerPlayer player, Component message) {
		player.sendOverlayMessage(message);
	}

	private static void fail(ServerPlayer player, Component message) {
		player.sendSystemMessage(Msg.prefixed(message.copy().withStyle(ChatFormatting.RED)));
	}

	private HotkeyHandler() {
	}
}
