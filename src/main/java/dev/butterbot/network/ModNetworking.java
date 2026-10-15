package dev.butterbot.network;

import dev.butterbot.ai.OreFinder;
import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.ai.job.JobStorage;
import dev.butterbot.entity.BotJob;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotSkill;
import dev.butterbot.entity.BotStats;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.ai.job.Livestock;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.entity.SmeltMode;
import dev.butterbot.skin.ServerSkinStore;
import dev.butterbot.skin.SkinLimits;
import dev.butterbot.util.Msg;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ModNetworking {

	public static final int MAX_TUNNEL_LENGTH = 512;
	public static final int MAX_JOB_COUNT = BotStats.MAX_BACKPACK * 64;

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Payloads.RequestBots.TYPE, Payloads.RequestBots.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.BotCommand.TYPE, Payloads.BotCommand.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.SaveScript.TYPE, Payloads.SaveScript.CODEC);
		PayloadTypeRegistry.serverboundPlay().registerLarge(Payloads.SkinUpload.TYPE, Payloads.SkinUpload.CODEC,
				SkinLimits.MAX_BYTES + 1024);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.SkinFetch.TYPE, Payloads.SkinFetch.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Hotkey.TYPE, Payloads.Hotkey.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(Payloads.BotList.TYPE, Payloads.BotList.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.OpenBotScreen.TYPE, Payloads.OpenBotScreen.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Payloads.SkinData.TYPE, Payloads.SkinData.CODEC,
				SkinLimits.MAX_BYTES + 1024);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.HudUpdate.TYPE, Payloads.HudUpdate.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.RequestBots.TYPE,
				(payload, context) -> sendBotList(context.player(), payload.base()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.BotCommand.TYPE,
				(payload, context) -> handleCommand(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SaveScript.TYPE,
				(payload, context) -> handleScript(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SkinUpload.TYPE,
				(payload, context) -> ServerSkinStore.handleUpload(context.player(), payload.skinId(), payload.data()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SkinFetch.TYPE,
				(payload, context) -> ServerSkinStore.handleFetch(context.player(), payload.skinId()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.Hotkey.TYPE,
				(payload, context) -> HotkeyHandler.handle(context.player(), payload));
	}

	public static void openBotScreen(ServerPlayer player, ButterBotEntity bot) {
		ServerPlayNetworking.send(player, new Payloads.OpenBotScreen(bot.getUUID()));
	}

	public static void sendBotList(ServerPlayer player, Optional<BlockPos> base) {
		List<Payloads.BotEntry> entries = new ArrayList<>();
		for (ButterBotEntity bot : BotAccess.listBots(player, base)) {
			if (entries.size() >= 64) {
				break;
			}
			entries.add(new Payloads.BotEntry(
					bot.getUUID(),
					bot.getName().getString(),
					bot.describeShortStatus(),
					bot.isNeko(),
					bot.getTask().ordinal(),
					bot.getSkinId(),
					bot.getLuaScript(),
					bot.getDeliveryMode().ordinal(),
					bot.countInventoryItems(),
					bot.blockPosition(),
					bot.getBasePos() != null,
					bot.getHealth() / bot.getMaxHealth(),
					bot.getBotLevel(),
					bot.getWorkXp(),
					bot.getStoredXp(),
					bot.guardSetting(),
					bot.placesTorchesWhileMining(),
					bot.feedsOwner(),
					bot.getSmeltMode().ordinal(),
					bot.refillsHotbar(),
					bot.getSkinVariant(),
					jobView(bot),
					bot.getMaxHealth(),
					bot.getInventory().getContainerSize(),
					bot.getProgress().ranks(),
					bot.getProgress().mastery(),
					bot.getProgress().stats(),
					bot.teleportsFar()));
		}
		Optional<BlockPos> validBase = BotAccess.isValidBase(player, base) ? base : Optional.empty();
		ServerPlayNetworking.send(player, new Payloads.BotList(validBase, entries));
	}

	/** The job part of the panel data. Looks into the job chest when it is loaded, so the list shows live counts. */
	private static Payloads.JobView jobView(ButterBotEntity bot) {
		JobConfig jobs = bot.getJobs();
		if (!jobs.hasJob()) {
			return new Payloads.JobView(0, false, Optional.empty(), false, Optional.empty(), jobs.zone(), jobs.radius(), 0,
					jobs.herd(), jobs.butcher(), List.of(), List.of(), List.copyOf(jobs.sources()), jobs.smelt(), Optional.empty(), "");
		}
		ServerLevel home = jobs.level(((ServerLevel) bot.level()).getServer());
		BlockPos chest = jobs.chest();
		boolean chestOk = false;
		if (home != null && chest != null && home.isLoaded(chest)) {
			Map<Item, Integer> counts = new HashMap<>();
			List<Item> items = new ArrayList<>();
			jobs.needs().forEach(need -> items.add(need.item()));
			chestOk = JobStorage.countInto(home, chest, items, counts);
			if (chestOk) {
				jobs.rememberChestCounts(counts, home.getGameTime());
			}
		} else if (chest != null) {
			// Far away and not loaded: it was fine the last time the bot looked.
			chestOk = jobs.chestCountsTime() != Long.MIN_VALUE;
		}
		List<Payloads.NeedView> needs = new ArrayList<>();
		for (JobConfig.Need need : jobs.needs()) {
			needs.add(new Payloads.NeedView(BuiltInRegistries.ITEM.getKey(need.item()).toString(), need.count(),
					jobs.knownChestCount(need.item())));
		}
		List<Payloads.HerdView> herds = new ArrayList<>();
		if (jobs.job() == BotJob.RANCHER) {
			// How many of each kind are in the area right now (unknown while it isn't loaded).
			BlockPos center = jobs.center();
			Map<EntityType<?>, List<Animal>> around = home != null && center != null && home.isLoaded(center)
					? Livestock.around(home, center, jobs.radius()) : null;
			for (EntityType<?> kind : Livestock.KINDS) {
				int count = around == null ? -1 : around.getOrDefault(kind, List.of()).size();
				herds.add(new Payloads.HerdView(Livestock.id(kind), count, jobs.herdLimit(kind), jobs.hasHerdLimit(kind)));
			}
		}
		ButterBotEntity ward = bot.findWard();
		return new Payloads.JobView(jobs.job().ordinal(), jobs.isPaused(), Optional.ofNullable(chest), chestOk,
				Optional.ofNullable(jobs.center()), jobs.zone(), jobs.radius(), jobs.stat(), jobs.herd(), jobs.butcher(), needs, herds,
				List.copyOf(jobs.sources()), jobs.smelt(), Optional.ofNullable(jobs.ward()), ward == null ? "" : ward.getName().getString());
	}

	private static void handleCommand(ServerPlayer player, Payloads.BotCommand payload) {
		ButterBotEntity bot = BotAccess.resolve(player, payload.bot(), payload.base());
		if (bot == null) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.bot_out_of_reach")
					.withStyle(ChatFormatting.RED)));
			sendBotList(player, payload.base());
			return;
		}
		boolean validBase = BotAccess.isValidBase(player, payload.base());

		switch (payload.action()) {
			case Payloads.BotCommand.SUMMON -> {
				if (validBase) {
					bot.setBasePos(payload.base().get());
				}
				bot.assignGoToBaseTask();
			}
			case Payloads.BotCommand.STOP -> {
				bot.pauseJob();
				bot.clearTask();
				bot.say(Component.translatable("butterbot.msg.stopping"));
			}
			case Payloads.BotCommand.STATUS -> player.sendSystemMessage(Msg.prefixed(
					bot.getName().copy().append(": ").append(bot.describeStatus())));
			case Payloads.BotCommand.MINE -> {
				BotResource resource = BotResource.byId(payload.text());
				if (resource == null) {
					player.sendSystemMessage(Component.translatable("butterbot.msg.unknown_resource", payload.text())
							.withStyle(ChatFormatting.RED));
					return;
				}
				bot.assignMineTask(resource, Mth.clamp(payload.arg(0, 64), 1, MAX_JOB_COUNT));
			}
			case Payloads.BotCommand.CRAFT -> {
				Item item = parseItem(payload.text());
				if (item == null || item == Items.AIR) {
					player.sendSystemMessage(Component.translatable("butterbot.msg.unknown_item", payload.text())
							.withStyle(ChatFormatting.RED));
					return;
				}
				bot.assignCraftTask(item, Mth.clamp(payload.arg(0, 1), 1, MAX_JOB_COUNT));
			}
			case Payloads.BotCommand.TUNNEL -> assignTunnel(player, bot, payload.arg(0, 0), payload.arg(1, 0),
					payload.arg(2, 0), payload.arg(3, 0), payload.arg(4, 2), payload.arg(5, 2), payload.arg(6, 1) != 0);
			case Payloads.BotCommand.RELEASE -> {
				bot.release();
				player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.released", bot.getName())));
			}
			case Payloads.BotCommand.SET_NEKO -> {
				boolean neko = payload.arg(0, 1) != 0;
				bot.setNeko(neko);
				bot.say(Component.translatable(neko ? "butterbot.msg.neko_on" : "butterbot.msg.neko_off"));
			}
			case Payloads.BotCommand.FOLLOW -> bot.assignFollowTask();
			case Payloads.BotCommand.DELIVER -> bot.requestDelivery();
			case Payloads.BotCommand.BACKPACK -> {
				if (player.distanceToSqr(bot) > 8.0D * 8.0D) {
					player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.too_far_backpack")
							.withStyle(ChatFormatting.RED)));
				} else {
					bot.openBackpack(player);
					return;
				}
			}
			case Payloads.BotCommand.DELIVERY_MODE -> {
				DeliveryMode mode = DeliveryMode.byOrdinal(payload.arg(0, 0));
				if (mode == DeliveryMode.BASE && bot.getBasePos() == null) {
					player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.no_base")
							.withStyle(ChatFormatting.RED)));
				} else {
					bot.setDeliveryMode(mode);
				}
			}
			case Payloads.BotCommand.RENAME -> {
				String name = payload.text().strip();
				if (name.length() > ButterBotEntity.MAX_NAME_LENGTH) {
					name = name.substring(0, ButterBotEntity.MAX_NAME_LENGTH);
				}
				bot.setCustomName(Component.literal(name.isEmpty() ? bot.defaultName() : name));
			}
			case Payloads.BotCommand.SET_SKIN -> {
				String skinId = payload.text();
				if (skinId.isEmpty()) {
					bot.setSkinId("");
					bot.say(Component.translatable("butterbot.msg.skin_reset"));
				} else if (SkinLimits.isValidSkinId(skinId) && ServerSkinStore.has(skinId)) {
					bot.setSkinId(skinId);
					bot.say(Component.translatable("butterbot.msg.skin_applied"));
					player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.skin_applied_chat", bot.getName())));
					// Hand the picture straight to this player so the bot changes right away.
					ServerSkinStore.push(player, skinId);
				} else {
					player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.skin_missing")
							.withStyle(ChatFormatting.RED)));
				}
			}
			case Payloads.BotCommand.DEFAULT_SKIN -> {
				bot.setSkinId("");
				bot.setSkinVariant(payload.arg(0, 0));
				bot.say(Component.translatable("butterbot.msg.skin_default_set", Component.translatable(
						"butterbot.skin.default." + bot.getSkinVariant())));
			}
			case Payloads.BotCommand.GUARD -> {
				bot.setGuarding(payload.arg(0, 1) != 0);
				bot.say(Component.translatable(bot.isGuarding() ? "butterbot.msg.guard_on" : "butterbot.msg.guard_off"));
			}
			case Payloads.BotCommand.AUTO_TORCH -> {
				bot.setAutoTorch(payload.arg(0, 1) != 0);
				bot.say(Component.translatable(bot.placesTorchesWhileMining() ? "butterbot.msg.torches_on" : "butterbot.msg.torches_off"));
			}
			case Payloads.BotCommand.FEED -> {
				bot.setFeedOwner(payload.arg(0, 1) != 0);
				bot.say(Component.translatable(bot.feedsOwner() ? "butterbot.msg.feed_on" : "butterbot.msg.feed_off"));
			}
			case Payloads.BotCommand.SMELT_MODE -> {
				bot.setSmeltMode(SmeltMode.byOrdinal(payload.arg(0, 1)));
				bot.say(Component.translatable("butterbot.msg.smelt_mode", Component.translatable(bot.getSmeltMode().key())));
			}
			case Payloads.BotCommand.TELEPORT -> {
				bot.setFarTeleport(payload.arg(0, 1) != 0);
				bot.say(Component.translatable(bot.teleportsFar() ? "butterbot.msg.teleport_far" : "butterbot.msg.teleport_emergency"));
			}
			case Payloads.BotCommand.REFILL -> {
				bot.setAutoRefill(payload.arg(0, 1) != 0);
				bot.say(Component.translatable(bot.refillsHotbar() ? "butterbot.msg.refill_on" : "butterbot.msg.refill_off"));
			}
			case Payloads.BotCommand.LIGHT -> bot.assignLightTask(player.blockPosition(),
					Mth.clamp(payload.arg(0, LightAreaAction.DEFAULT_RADIUS), 4, LightAreaAction.MAX_RADIUS));
			case Payloads.BotCommand.FARM -> bot.assignFarmTask();
			case Payloads.BotCommand.SORT -> bot.assignSortTask();
			case Payloads.BotCommand.FIND -> {
				BotResource resource = BotResource.byId(payload.text());
				if (resource != null) {
					reportFind(player, bot, resource);
				}
			}
			case Payloads.BotCommand.JOB_SET -> JobControl.setJob(player, bot, BotJob.byOrdinal(payload.arg(0, 0)));
			case Payloads.BotCommand.JOB_PAUSE -> JobControl.setPaused(bot, payload.arg(0, 1) != 0);
			case Payloads.BotCommand.JOB_NEED -> {
				Item item = parseItem(payload.text());
				if (item == null || item == Items.AIR) {
					player.sendSystemMessage(Component.translatable("butterbot.msg.unknown_item", payload.text())
							.withStyle(ChatFormatting.RED));
				} else {
					JobControl.setNeed(player, bot, item, Mth.clamp(payload.arg(0, 64), 0, JobConfig.MAX_NEED_COUNT));
				}
			}
			case Payloads.BotCommand.JOB_CLEAR_LIST -> {
				bot.getJobs().clearNeeds();
				bot.clearTaskForJob();
			}
			case Payloads.BotCommand.JOB_ZONE -> {
				bot.getJobs().setZone(payload.arg(0, JobConfig.DEFAULT_ZONE));
				bot.clearTaskForJob();
			}
			case Payloads.BotCommand.JOB_RADIUS -> {
				bot.getJobs().setRadius(payload.arg(0, JobConfig.DEFAULT_PATROL_RADIUS));
				bot.clearTaskForJob();
			}
			case Payloads.BotCommand.JOB_CENTER -> JobControl.setCenter(player, bot, payload.args().length >= 3
					? new BlockPos(payload.arg(0, 0), payload.arg(1, 0), payload.arg(2, 0)) : player.blockPosition());
			case Payloads.BotCommand.JOB_CHEST -> JobControl.setChest(player, bot, payload.args().length >= 3
					? new BlockPos(payload.arg(0, 0), payload.arg(1, 0), payload.arg(2, 0)) : null);
			case Payloads.BotCommand.SKILL_UP -> {
				BotSkill skill = BotSkill.byOrdinal(payload.arg(0, -1));
				Component problem = skill == null ? null : bot.raiseSkill(skill);
				if (problem != null) {
					player.sendSystemMessage(Msg.prefixed(problem.copy().withStyle(ChatFormatting.RED)));
				}
			}
			case Payloads.BotCommand.SKILL_RESET -> bot.resetSkills();
			case Payloads.BotCommand.JOB_HERD -> {
				int limit = payload.arg(0, JobConfig.DEFAULT_HERD);
				EntityType<?> kind = Livestock.byId(payload.text());
				if (kind != null) {
					bot.getJobs().setHerdLimit(kind, limit);
				} else if (payload.text().isEmpty()) {
					bot.getJobs().setHerd(limit);
				}
			}
			case Payloads.BotCommand.JOB_SOURCE -> {
				if (payload.args().length >= 3 && payload.text().equals("remove")) {
					bot.getJobs().removeSource(new BlockPos(payload.arg(0, 0), payload.arg(1, 0), payload.arg(2, 0)));
				} else if (payload.args().length >= 3) {
					JobControl.addSource(player, bot, new BlockPos(payload.arg(0, 0), payload.arg(1, 0), payload.arg(2, 0)));
				} else if (payload.args().length == 1) {
					bot.getJobs().removeSource(payload.arg(0, -1));
				} else {
					bot.getJobs().clearSources();
				}
			}
			case Payloads.BotCommand.JOB_SMELT -> bot.getJobs().setSmelt(payload.arg(0, 1) != 0);
			case Payloads.BotCommand.JOB_WARD -> JobControl.setWard(player, bot, payload.text());
			case Payloads.BotCommand.JOB_BUTCHER -> {
				bot.getJobs().setButcher(payload.arg(0, 0) != 0);
				if (!bot.getJobs().butcher()) {
					bot.stopCulling();
				}
			}
			case Payloads.BotCommand.LINK_BASE -> {
				if (validBase) {
					bot.setBasePos(payload.base().get());
					bot.say(Component.translatable("butterbot.msg.base_set"));
				}
			}
			default -> {
			}
		}
		sendBotList(player, payload.base());
	}

	private static void handleScript(ServerPlayer player, Payloads.SaveScript payload) {
		ButterBotEntity bot = BotAccess.resolve(player, payload.bot(), payload.base());
		if (bot == null) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.bot_out_of_reach")
					.withStyle(ChatFormatting.RED)));
			return;
		}
		bot.setLuaScript(payload.script());
		if (payload.run() && !payload.script().isBlank()) {
			bot.assignLuaTask();
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.program_started", bot.getName())));
		} else {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.program_saved", bot.getName())));
		}
		sendBotList(player, payload.base());
	}

	public static void assignTunnel(ServerPlayer player, ButterBotEntity bot, int fromX, int fromZ, int toX, int toZ,
									int width, int height, boolean torches) {
		int deltaX = toX - fromX;
		int deltaZ = toZ - fromZ;
		Direction direction;
		int length;
		if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
			direction = deltaX >= 0 ? Direction.EAST : Direction.WEST;
			length = Math.abs(deltaX) + 1;
		} else {
			direction = deltaZ >= 0 ? Direction.SOUTH : Direction.NORTH;
			length = Math.abs(deltaZ) + 1;
		}
		if (length > MAX_TUNNEL_LENGTH) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.tunnel_too_long", MAX_TUNNEL_LENGTH)
					.withStyle(ChatFormatting.RED)));
			return;
		}
		BlockPos origin = new BlockPos(fromX, bot.blockPosition().getY(), fromZ);
		bot.assignTunnelTask(origin, direction, length, Mth.clamp(width, 1, 5), Mth.clamp(height, 2, 5), torches);
	}

	/** Tells the player where the nearest block of {@code resource} is (and how many are around). */
	public static void reportFind(ServerPlayer player, ButterBotEntity bot, BotResource resource) {
		OreFinder.Result result = OreFinder.find(player.level(), player.blockPosition(), resource);
		BlockPos nearest = result.nearest();
		if (nearest == null) {
			Component message = Component.translatable("butterbot.msg.find_none", resource.displayName(), OreFinder.RADIUS);
			bot.say(message);
			player.sendSystemMessage(Msg.prefixed(message));
			return;
		}
		int distance = (int) Math.round(Math.sqrt(nearest.distSqr(player.blockPosition())));
		int below = player.blockPosition().getY() - nearest.getY();
		Component height = below > 0 ? Component.translatable("butterbot.msg.find_below", below)
				: below < 0 ? Component.translatable("butterbot.msg.find_above", -below)
				: Component.translatable("butterbot.msg.find_level");
		Component message = Component.translatable("butterbot.msg.find_result", resource.displayName(),
				nearest.getX() + " " + nearest.getY() + " " + nearest.getZ(), distance, height, result.cluster());
		bot.say(Component.translatable("butterbot.msg.find_short", resource.displayName(), distance));
		player.sendSystemMessage(Msg.prefixed(message));
	}

	@Nullable
	public static Item parseItem(String raw) {
		String id = raw.trim().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) {
			return null;
		}
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		Identifier identifier = Identifier.tryParse(id);
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}

	private ModNetworking() {
	}
}
