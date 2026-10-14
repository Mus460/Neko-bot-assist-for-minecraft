package dev.butterbot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.butterbot.ai.StorageFinder;
import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.entity.BotJob;
import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.BotProgress;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotSkill;
import dev.butterbot.entity.BotStats;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.entity.SmeltMode;
import dev.butterbot.network.BotAccess;
import dev.butterbot.network.JobControl;
import dev.butterbot.network.ModNetworking;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.registry.ModItems;
import dev.butterbot.util.Msg;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code /butterbot ...} - summon bots and give quick orders to your nearest bot. Available to every
 * player because it only creates bots for, and controls bots owned by, the player using it.
 */
public final class BotCommand {

	private static final int DEFAULT_COUNT = 64;
	/** How many bots one player may have loaded at the same time. */
	public static final int MAX_BOTS_PER_PLAYER = 5;

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("butterbot");

		for (String literal : new String[]{"summon", "spawn"}) {
			root.then(Commands.literal(literal)
					.executes(ctx -> summon(ctx.getSource(), null))
					.then(Commands.argument("name", StringArgumentType.greedyString())
							.executes(ctx -> summon(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
		}
		root.then(Commands.literal("dismiss").executes(ctx -> dismiss(ctx.getSource())));
		root.then(Commands.literal("menu").executes(ctx -> simple(ctx.getSource(), bot ->
				ModNetworking.openBotScreen(ctx.getSource().getPlayerOrException(), bot))));
		root.then(Commands.literal("getbase").executes(ctx -> giveBase(ctx.getSource())));
		root.then(Commands.literal("neko")
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(ctx -> {
							boolean neko = BoolArgumentType.getBool(ctx, "enabled");
							return simple(ctx.getSource(), bot -> {
								bot.setNeko(neko);
								bot.say(Component.translatable(neko ? "butterbot.msg.neko_on" : "butterbot.msg.neko_off"));
							});
						})));

		root.then(toggle("guard", ButterBotEntity::setGuarding, "butterbot.msg.guard_on", "butterbot.msg.guard_off"));
		root.then(toggle("torches", ButterBotEntity::setAutoTorch, "butterbot.msg.torches_on", "butterbot.msg.torches_off"));
		root.then(toggle("feed", ButterBotEntity::setFeedOwner, "butterbot.msg.feed_on", "butterbot.msg.feed_off"));
		LiteralArgumentBuilder<CommandSourceStack> smelt = Commands.literal("smelt");
		for (SmeltMode mode : SmeltMode.values()) {
			smelt.then(Commands.literal(mode.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> simple(ctx.getSource(), bot -> {
				bot.setSmeltMode(mode);
				bot.say(Component.translatable("butterbot.msg.smelt_mode", Component.translatable(mode.key())));
			})));
		}
		root.then(smelt);
		root.then(Commands.literal("farm").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::assignFarmTask)));
		root.then(Commands.literal("sort").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::assignSortTask)));
		root.then(Commands.literal("level").executes(ctx -> level(ctx.getSource())));
		LiteralArgumentBuilder<CommandSourceStack> skill = Commands.literal("skill").executes(ctx -> skills(ctx.getSource()));
		for (BotSkill each : BotSkill.values()) {
			skill.then(Commands.literal(each.id()).executes(ctx -> learnSkill(ctx.getSource(), each)));
		}
		skill.then(Commands.literal("reset").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::resetSkills)));
		root.then(skill);
		root.then(toggle("refill", ButterBotEntity::setAutoRefill, "butterbot.msg.refill_on", "butterbot.msg.refill_off"));
		root.then(Commands.literal("light")
				.executes(ctx -> light(ctx.getSource(), LightAreaAction.DEFAULT_RADIUS))
				.then(Commands.argument("radius", IntegerArgumentType.integer(4, LightAreaAction.MAX_RADIUS))
						.executes(ctx -> light(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))));
		root.then(Commands.literal("fetch")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> fetch(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), -1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, ModNetworking.MAX_JOB_COUNT))
								.executes(ctx -> fetch(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(),
										IntegerArgumentType.getInteger(ctx, "count"))))));
		root.then(Commands.literal("where")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> where(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value()))));
		root.then(Commands.literal("death").executes(ctx -> death(ctx.getSource())));
		root.then(Commands.literal("skin")
				.then(Commands.argument("number", IntegerArgumentType.integer(1, ButterBotEntity.SKIN_VARIANTS))
						.executes(ctx -> {
							int variant = IntegerArgumentType.getInteger(ctx, "number") - 1;
							return simple(ctx.getSource(), bot -> {
								bot.setSkinId("");
								bot.setSkinVariant(variant);
								bot.say(Component.translatable("butterbot.msg.skin_default_set",
										Component.translatable("butterbot.skin.default." + variant)));
							});
						})));
		LiteralArgumentBuilder<CommandSourceStack> find = Commands.literal("find");
		for (BotResource resource : BotResource.values()) {
			find.then(Commands.literal(resource.id()).executes(ctx -> simple(ctx.getSource(), bot ->
					ModNetworking.reportFind(ctx.getSource().getPlayerOrException(), bot, resource))));
		}
		root.then(find);

		LiteralArgumentBuilder<CommandSourceStack> mine = Commands.literal("mine");
		for (BotResource resource : BotResource.values()) {
			mine.then(Commands.literal(resource.id())
					.executes(ctx -> mine(ctx.getSource(), resource, DEFAULT_COUNT))
					.then(Commands.argument("count", IntegerArgumentType.integer(1, ModNetworking.MAX_JOB_COUNT))
							.executes(ctx -> mine(ctx.getSource(), resource, IntegerArgumentType.getInteger(ctx, "count")))));
		}
		root.then(mine);

		root.then(Commands.literal("tunnel")
				.then(Commands.argument("fromX", IntegerArgumentType.integer())
						.then(Commands.argument("fromZ", IntegerArgumentType.integer())
								.then(Commands.argument("toX", IntegerArgumentType.integer())
										.then(Commands.argument("toZ", IntegerArgumentType.integer())
												.executes(ctx -> tunnel(ctx, 2, 2, true))
												.then(Commands.argument("width", IntegerArgumentType.integer(1, 5))
														.then(Commands.argument("height", IntegerArgumentType.integer(2, 5))
																.executes(ctx -> tunnel(ctx, IntegerArgumentType.getInteger(ctx, "width"),
																		IntegerArgumentType.getInteger(ctx, "height"), true))
																.then(Commands.argument("torches", BoolArgumentType.bool())
																		.executes(ctx -> tunnel(ctx, IntegerArgumentType.getInteger(ctx, "width"),
																				IntegerArgumentType.getInteger(ctx, "height"),
																				BoolArgumentType.getBool(ctx, "torches")))))))))));

		root.then(Commands.literal("craft")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> craft(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, ModNetworking.MAX_JOB_COUNT))
								.executes(ctx -> craft(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(),
										IntegerArgumentType.getInteger(ctx, "count"))))));

		root.then(Commands.literal("base").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::assignGoToBaseTask)));
		root.then(Commands.literal("follow").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::assignFollowTask)));
		root.then(Commands.literal("come").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::assignFollowTask)));
		root.then(Commands.literal("deliver").executes(ctx -> simple(ctx.getSource(), ButterBotEntity::requestDelivery)));
		root.then(Commands.literal("stop").executes(ctx -> simple(ctx.getSource(), bot -> {
			bot.pauseJob();
			bot.clearTask();
			bot.clearOrderedTarget();
			bot.say(Component.translatable("butterbot.msg.stopping"));
		})));
		root.then(job(buildContext));
		root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
		root.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));

		dispatcher.register(root);
	}

	/**
	 * {@code /butterbot job ...}: give the nearest bot a job (miner, gatherer, guard, farmer) and set its
	 * chest, list, area and no-dig zone.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> job(CommandBuildContext buildContext) {
		LiteralArgumentBuilder<CommandSourceStack> job = Commands.literal("job")
				.executes(ctx -> jobInfo(ctx.getSource()));
		job.then(Commands.literal("info").executes(ctx -> jobInfo(ctx.getSource())));

		LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
		String[][] names = {{"none", "NONE"}, {"miner", "MINER"}, {"gatherer", "GATHERER"}, {"guard", "GUARD"}, {"farmer", "FARMER"},
				{"penambang", "MINER"}, {"pengumpul", "GATHERER"}, {"penjaga", "GUARD"}, {"petani", "FARMER"}, {"asisten", "NONE"}};
		for (String[] name : names) {
			BotJob value = BotJob.valueOf(name[1]);
			set.then(Commands.literal(name[0]).executes(ctx -> simple(ctx.getSource(), bot ->
					JobControl.setJob(ctx.getSource().getPlayerOrException(), bot, value))));
		}
		job.then(set);

		job.then(Commands.literal("chest")
				.executes(ctx -> simple(ctx.getSource(), bot -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					BlockPos looked = JobControl.lookedAtStorage(player);
					if (looked == null) {
						ctx.getSource().sendFailure(Component.translatable("butterbot.msg.job_look_at_chest"));
					} else {
						JobControl.setChest(player, bot, looked);
					}
				}))
				.then(Commands.literal("clear").executes(ctx -> simple(ctx.getSource(), bot ->
						JobControl.setChest(ctx.getSource().getPlayerOrException(), bot, null)))));

		job.then(Commands.literal("need")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> jobNeed(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), 64))
						.then(Commands.argument("count", IntegerArgumentType.integer(0, JobConfig.MAX_NEED_COUNT))
								.executes(ctx -> jobNeed(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(),
										IntegerArgumentType.getInteger(ctx, "count"))))));
		job.then(Commands.literal("remove")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> jobNeed(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), 0))));
		job.then(Commands.literal("clear").executes(ctx -> simple(ctx.getSource(), bot -> {
			bot.getJobs().clearNeeds();
			bot.clearTaskForJob();
			ctx.getSource().sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.job_list_cleared", bot.getName())), false);
		})));
		job.then(Commands.literal("zone")
				.then(Commands.argument("blocks", IntegerArgumentType.integer(0, JobConfig.MAX_ZONE))
						.executes(ctx -> {
							int zone = IntegerArgumentType.getInteger(ctx, "blocks");
							return simple(ctx.getSource(), bot -> {
								bot.getJobs().setZone(zone);
								bot.clearTaskForJob();
								ctx.getSource().sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.job_zone_set",
										bot.getName(), zone)), false);
							});
						})));
		job.then(Commands.literal("center").executes(ctx -> simple(ctx.getSource(), bot -> {
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			JobControl.setCenter(player, bot, player.blockPosition());
		})));
		job.then(Commands.literal("radius")
				.then(Commands.argument("blocks", IntegerArgumentType.integer(JobConfig.MIN_RADIUS, JobConfig.MAX_PATROL_RADIUS))
						.executes(ctx -> {
							int radius = IntegerArgumentType.getInteger(ctx, "blocks");
							return simple(ctx.getSource(), bot -> {
								bot.getJobs().setRadius(radius);
								bot.clearTaskForJob();
								ctx.getSource().sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.job_radius_set",
										bot.getName(), bot.getJobs().radius())), false);
							});
						})));
		job.then(Commands.literal("pause").executes(ctx -> simple(ctx.getSource(), bot -> JobControl.setPaused(bot, true))));
		job.then(Commands.literal("resume").executes(ctx -> simple(ctx.getSource(), bot -> JobControl.setPaused(bot, false))));
		return job;
	}

	private static int jobInfo(CommandSourceStack source) throws CommandSyntaxException {
		return simple(source, bot -> JobControl.sendInfo(source.getPlayerOrException(), bot));
	}

	private static int jobNeed(CommandSourceStack source, Item item, int count) throws CommandSyntaxException {
		return simple(source, bot -> {
			ServerPlayer player = source.getPlayerOrException();
			if (JobControl.setNeed(player, bot, item, count)) {
				source.sendSuccess(() -> Msg.prefixed(count > 0
						? Component.translatable("butterbot.msg.job_need_set", bot.getName(), count, new ItemStack(item).getHoverName())
						: Component.translatable("butterbot.msg.job_need_removed", bot.getName(), new ItemStack(item).getHoverName())), false);
				JobControl.explainMissing(player, bot);
			}
		});
	}

	@Nullable
	private static ButterBotEntity nearest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<ButterBotEntity> bots = BotAccess.listBots(player, Optional.empty());
		if (bots.isEmpty()) {
			source.sendFailure(Component.translatable("butterbot.msg.no_bot_nearby", (int) BotAccess.DIRECT_RANGE));
			return null;
		}
		return bots.getFirst();
	}

	private static int mine(CommandSourceStack source, BotResource resource, int count) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		bot.assignMineTask(resource, count);
		return 1;
	}

	private static int craft(CommandSourceStack source, Item item, int count) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		bot.assignCraftTask(item, count);
		return 1;
	}

	private static int tunnel(CommandContext<CommandSourceStack> ctx, int width, int height, boolean torches) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(ctx.getSource());
		if (bot == null) {
			return 0;
		}
		ModNetworking.assignTunnel(ctx.getSource().getPlayerOrException(), bot,
				IntegerArgumentType.getInteger(ctx, "fromX"), IntegerArgumentType.getInteger(ctx, "fromZ"),
				IntegerArgumentType.getInteger(ctx, "toX"), IntegerArgumentType.getInteger(ctx, "toZ"),
				width, height, torches);
		return 1;
	}

	private interface BotOrder {
		void apply(ButterBotEntity bot) throws CommandSyntaxException;
	}

	private interface BotSetting {
		void set(ButterBotEntity bot, boolean enabled);
	}

	/** {@code /butterbot <name> <true|false>} for an on/off setting of the nearest bot. */
	private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, BotSetting setting, String onKey, String offKey) {
		return Commands.literal(name)
				.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(ctx -> {
							boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
							return simple(ctx.getSource(), bot -> {
								setting.set(bot, enabled);
								bot.say(Component.translatable(enabled ? onKey : offKey));
							});
						}));
	}

	private static int level(CommandSourceStack source) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		int level = bot.getBotLevel();
		BotStats stats = bot.stats();
		Component progress = level >= BotLevels.MAX_LEVEL
				? Component.translatable("butterbot.msg.level_max")
				: Component.translatable("butterbot.msg.level_progress", bot.getWorkXp(), BotLevels.xpToNext(level));
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.level_info", bot.getName(), level,
				progress, stats.digSpeedPercent(), bot.getInventory().getContainerSize(), (int) bot.getMaxHealth(),
				stats.fortune(), stats.veinSize(), bot.getStoredXp())), false);
		BotProgress learned = bot.getProgress();
		source.sendSuccess(() -> Component.translatable("butterbot.msg.level_mastery", bot.getSkillPoints(),
				masteryName(learned, BotJob.MINER), masteryName(learned, BotJob.GATHERER),
				masteryName(learned, BotJob.GUARD), masteryName(learned, BotJob.FARMER)), false);
		return level;
	}

	private static Component masteryName(BotProgress progress, BotJob job) {
		return Component.translatable("butterbot.mastery.rank." + progress.masteryRank(job));
	}

	/** Lists the skill tree with the bot's ranks and how many points are left. */
	private static int skills(CommandSourceStack source) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		BotProgress progress = bot.getProgress();
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.skills_header", bot.getName(),
				bot.getSkillPoints())), false);
		for (BotSkill.Branch branch : BotSkill.Branch.values()) {
			MutableComponent line = Component.empty().append(branch.displayName().copy().withStyle(ChatFormatting.GOLD)).append(": ");
			for (int row = 0; row < BotSkill.ROW_LEVEL.length; row++) {
				BotSkill each = BotSkill.at(branch, row);
				int rank = progress.rank(each);
				if (row > 0) {
					line.append(", ");
				}
				line.append(each.displayName().copy().withStyle(rank > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY))
						.append(Component.literal(" " + rank + "/" + each.maxRank() + " [" + each.id() + "]").withStyle(ChatFormatting.DARK_GRAY));
			}
			source.sendSuccess(() -> line, false);
		}
		source.sendSuccess(() -> Component.translatable("butterbot.msg.skills_hint").withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int learnSkill(CommandSourceStack source, BotSkill skill) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		Component problem = bot.raiseSkill(skill);
		if (problem != null) {
			source.sendFailure(problem);
			return 0;
		}
		return 1;
	}

	private static int light(CommandSourceStack source, int radius) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		bot.assignLightTask(source.getPlayerOrException().blockPosition(), radius);
		return 1;
	}

	/** Without a count the bot brings one full stack. */
	private static int fetch(CommandSourceStack source, Item item, int count) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		bot.assignFetchTask(item, count > 0 ? count : new ItemStack(item).getMaxStackSize());
		return 1;
	}

	/** Lists the chests (and bots) holding an item and makes those chests sparkle for a moment. */
	private static int where(CommandSourceStack source, Item item) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.level();
		List<ButterBotEntity> bots = BotAccess.listBots(player, Optional.empty());
		List<BlockPos> centers = new ArrayList<>();
		centers.add(player.blockPosition());
		for (ButterBotEntity bot : bots) {
			if (bot.getBasePos() != null && bot.getBase() != null && !centers.contains(bot.getBasePos())) {
				centers.add(bot.getBasePos());
			}
		}
		List<StorageFinder.Found> found = new ArrayList<>(StorageFinder.find(level, centers, stack -> stack.is(item)));
		int carried = 0;
		for (ButterBotEntity bot : bots) {
			carried += bot.countItem(item);
		}
		Component name = new ItemStack(item).getHoverName();
		if (found.isEmpty() && carried == 0) {
			source.sendFailure(Component.translatable("butterbot.msg.where_none", name, StorageFinder.RADIUS));
			return 0;
		}
		int total = carried;
		for (StorageFinder.Found entry : found) {
			total += entry.count();
		}
		Vec3 from = player.position();
		found.sort(Comparator.comparingDouble(entry -> from.distanceToSqr(Vec3.atCenterOf(entry.pos()))));
		int shownTotal = total;
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.where_header", name, shownTotal)), false);
		for (int i = 0; i < Math.min(6, found.size()); i++) {
			StorageFinder.Found entry = found.get(i);
			BlockPos pos = entry.pos();
			int distance = (int) Math.round(Math.sqrt(pos.distToCenterSqr(from)));
			Component block = level.getBlockState(pos).getBlock().getName();
			source.sendSuccess(() -> Component.translatable("butterbot.msg.where_line", entry.count(), block,
					pos.getX() + " " + pos.getY() + " " + pos.getZ(), distance), false);
			level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true, true, pos.getX() + 0.5D, pos.getY() + 1.2D,
					pos.getZ() + 0.5D, 15, 0.3D, 0.3D, 0.3D, 0.0D);
		}
		if (carried > 0) {
			int shownCarried = carried;
			source.sendSuccess(() -> Component.translatable("butterbot.msg.where_bots", shownCarried), false);
		}
		return total;
	}

	private static int death(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		Optional<GlobalPos> death = player.getLastDeathLocation();
		if (death.isEmpty()) {
			source.sendFailure(Component.translatable("butterbot.msg.death_unknown"));
			return 0;
		}
		BlockPos pos = death.get().pos();
		boolean here = death.get().dimension() == player.level().dimension();
		Component message = Component.translatable(here ? "butterbot.msg.death_info" : "butterbot.msg.death_info_other",
				pos.getX() + " " + pos.getY() + " " + pos.getZ(),
				here ? (int) Math.round(Math.sqrt(pos.distToCenterSqr(player.position()))) : 0);
		source.sendSuccess(() -> Msg.prefixed(message), false);
		return 1;
	}

	// ---------------------------------------------------------------- summoning

	private static int summon(CommandSourceStack source, @Nullable String name) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int owned = countOwnedBots(player);
		if (owned >= MAX_BOTS_PER_PLAYER) {
			source.sendFailure(Component.translatable("butterbot.msg.too_many_bots", MAX_BOTS_PER_PLAYER));
			return 0;
		}

		ServerLevel level = player.level();
		ButterBotEntity bot = ModEntities.BUTTER_BOT.spawn(level, findSpawnSpot(level, player), EntitySpawnReason.COMMAND);
		if (bot == null) {
			source.sendFailure(Component.translatable("butterbot.msg.summon_failed"));
			return 0;
		}

		// Face the player who called it.
		float yaw = player.getYRot() + 180.0F;
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setYBodyRot(yaw);

		if (name != null && !name.isBlank()) {
			String trimmed = name.strip();
			if (trimmed.length() > ButterBotEntity.MAX_NAME_LENGTH) {
				trimmed = trimmed.substring(0, ButterBotEntity.MAX_NAME_LENGTH);
			}
			bot.setCustomName(Component.literal(trimmed));
		}
		bot.claim(player);
		bot.say(Component.translatable("butterbot.msg.hello"));
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.summoned", bot.getName(),
				owned + 1, MAX_BOTS_PER_PLAYER)), false);
		return 1;
	}

	private static int countOwnedBots(ServerPlayer player) {
		int count = 0;
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			count += level.getEntities(ModEntities.BUTTER_BOT,
					bot -> bot.isAlive() && player.getUUID().equals(bot.getOwnerUuid())).size();
		}
		return count;
	}

	/** A free spot about two blocks in front of the player, or the player's own position. */
	private static BlockPos findSpawnSpot(ServerLevel level, ServerPlayer player) {
		Vec3 look = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
		if (look.lengthSqr() < 1.0E-4D) {
			look = new Vec3(0.0D, 0.0D, 1.0D);
		}
		look = look.normalize();
		for (double distance : new double[]{2.0D, 1.5D, 1.0D}) {
			BlockPos candidate = BlockPos.containing(player.getX() + look.x * distance, player.getY(), player.getZ() + look.z * distance);
			for (int dy : new int[]{0, 1, -1}) {
				BlockPos pos = candidate.above(dy);
				if (isFreeSpot(level, pos)) {
					return pos;
				}
			}
		}
		return player.blockPosition();
	}

	private static boolean isFreeSpot(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
				&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
				&& level.getFluidState(pos).isEmpty()
				&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
	}

	private static int dismiss(CommandSourceStack source) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		Component name = bot.getName().copy();
		bot.dismiss(source.getPlayerOrException());
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.dismissed", name)), false);
		return 1;
	}

	private static int giveBase(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack stack = new ItemStack(ModItems.BOT_BASE);
		if (!player.getInventory().add(stack)) {
			player.spawnAtLocation(player.level(), stack);
		}
		source.sendSuccess(() -> Msg.prefixed(Component.translatable("butterbot.msg.base_given")), false);
		return 1;
	}

	private static int simple(CommandSourceStack source, BotOrder order) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		order.apply(bot);
		return 1;
	}

	private static int status(CommandSourceStack source) throws CommandSyntaxException {
		ButterBotEntity bot = nearest(source);
		if (bot == null) {
			return 0;
		}
		source.sendSuccess(() -> Msg.prefixed(bot.getName().copy().append(": ").append(bot.describeStatus())), false);
		return 1;
	}

	private static int list(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<ButterBotEntity> bots = BotAccess.listBots(player, Optional.empty());
		if (bots.isEmpty()) {
			source.sendFailure(Component.translatable("butterbot.msg.no_bot_nearby", (int) BotAccess.DIRECT_RANGE));
			return 0;
		}
		for (ButterBotEntity bot : bots) {
			source.sendSuccess(() -> Msg.prefixed(bot.getName().copy()
					.append(" (" + bot.blockPosition().toShortString() + "): ")
					.append(bot.describeShortStatus())), false);
		}
		return bots.size();
	}

	private BotCommand() {
	}
}
