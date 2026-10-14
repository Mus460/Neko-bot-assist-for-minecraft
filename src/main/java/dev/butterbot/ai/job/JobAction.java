package dev.butterbot.ai.job;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a bot's job whenever it has no other order. Miners and gatherers check the job chest, pick the
 * first item on the list that is still short, travel to a site outside the no-dig zone and start a
 * mining run there (the normal mining code does the digging; the delivery brings the haul back to
 * the chest). When everything on the list is in the chest the bot waits by it and starts again as
 * soon as something is taken out. Guards walk around their area, collect loot lying there and put
 * it in a chest. Farmers keep a farming run going around their spot.
 */
public class JobAction implements BotAction {

	private static final double CHEST_REACH = 2.8D;
	/** Close enough to the dig site to start working. */
	private static final double SITE_ARRIVED_SQ = 48.0D * 48.0D;
	private static final int STANDBY_RECHECK_TICKS = 200;
	/** How old the remembered chest contents may be before the chest is loaded and looked at again. */
	private static final long CHEST_MEMORY_TICKS = 20L * 120L;
	private static final int CHEST_LOAD_TIMEOUT = 20 * 30;
	private static final int FAILS_BEFORE_COOLDOWN = 6;
	private static final long NOT_FOUND_COOLDOWN = 20L * 60L * 10L;
	private static final long REMIND_TICKS = 20L * 60L * 3L;
	/** After a trip to the chest, don't go back for leftovers that didn't fit for this long. */
	private static final long DEPOSIT_RETRY_TICKS = 20L * 30L;
	private static final int WAIT_AFTER_PROBLEM = 20 * 30;
	private static final int PATROL_LOOT_STACK = 64;
	private static final long PATROL_DEPOSIT_TICKS = 20L * 120L;
	private static final int KEEP_FILLER = 32;

	private enum Step {CHECK, DEPOSIT, TRAVEL, STANDBY, WAIT}

	private enum PatrolStep {WALK, LOOK, PICKUP, DEPOSIT}

	private final JobTravel travel = new JobTravel();
	private final Walker walker = new Walker();
	private Step step = Step.CHECK;
	private int timer;
	@Nullable
	private ResourceCatalog.Entry nextEntry;
	private int nextCount;
	private boolean doneAnnounced;
	private long lastReminder = Long.MIN_VALUE;

	private PatrolStep patrolStep = PatrolStep.WALK;
	@Nullable
	private BlockPos waypoint;
	@Nullable
	private ItemEntity pickup;
	private long lastDeposit;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 35;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.isJobActive() && bot.getTask() == BotTask.NONE && !bot.isReturnRequested() && bot.getOwnerUuid() != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		travel.reset(bot);
		walker.reset(bot);
		step = Step.CHECK;
		timer = 0;
		patrolStep = PatrolStep.WALK;
		waypoint = null;
		pickup = null;
		bot.setBotState(BotState.SEARCHING);
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel botLevel)) {
			return;
		}
		if (bot.getMiner().isDigging()) {
			bot.getMiner().tickDig();
			return;
		}
		if (bot.takeJobChange()) {
			start(bot);
		}
		JobConfig jobs = bot.getJobs();
		ServerLevel home = jobs.level(botLevel.getServer());
		switch (jobs.job()) {
			case MINER, GATHERER -> tickList(bot, jobs, home);
			case GUARD -> tickPatrol(bot, jobs, home);
			case FARMER -> tickFarmer(bot, jobs, home);
			case NONE -> {
			}
		}
	}

	// ================================================================ miners and gatherers

	private void tickList(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home) {
		if (home == null || jobs.chest() == null || jobs.needs().isEmpty()) {
			needsSetup(bot, jobs, jobs.chest() == null ? "butterbot.msg.job_needs_chest" : "butterbot.msg.job_needs_list");
			return;
		}
		switch (step) {
			case CHECK -> check(bot, jobs, home);
			case DEPOSIT -> deposit(bot, jobs, home);
			case TRAVEL -> travelToSite(bot, jobs, home);
			case STANDBY -> standby(bot, jobs, home);
			case WAIT -> {
				bot.getNavigation().stop();
				if (--timer <= 0) {
					step = Step.CHECK;
				}
			}
		}
	}

	private void check(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		BlockPos chest = jobs.chest();
		long now = home.getGameTime();
		boolean chestTrouble = jobs.phase() == JobConfig.Phase.CHEST_PROBLEM;
		if (jobs.phase() != JobConfig.Phase.STANDBY && jobs.phase() != JobConfig.Phase.NOT_FOUND) {
			jobs.setPhase(JobConfig.Phase.CHECKING);
		}

		// What is in the chest? Look if it is loaded; otherwise trust a recent look, or load it for a moment.
		if (home.isLoaded(chest)) {
			Map<Item, Integer> counts = new HashMap<>();
			List<Item> items = new ArrayList<>();
			jobs.needs().forEach(need -> items.add(need.item()));
			if (!JobStorage.countInto(home, chest, items, counts)) {
				problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_missing", chest.toShortString()));
				return;
			}
			jobs.rememberChestCounts(counts, now);
			timer = 0;
		} else if (now - jobs.chestCountsTime() > CHEST_MEMORY_TICKS) {
			bot.getNavigation().stop();
			if (timer++ % 20 == 0) {
				JobChunks.preload(home, chest);
			}
			if (timer > CHEST_LOAD_TIMEOUT) {
				problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_unreachable", chest.toShortString()));
			}
			return;
		}

		ResourceCatalog.Entry pick = null;
		int remaining = 0;
		boolean coolingDown = false;
		for (JobConfig.Need need : jobs.needs()) {
			int have = Math.max(0, jobs.knownChestCount(need.item())) + bot.countItem(need.item());
			if (have >= need.count()) {
				continue;
			}
			ResourceCatalog.Entry entry = ResourceCatalog.get(need.item());
			if (entry == null || entry.job() != jobs.job()) {
				continue;
			}
			if (jobs.isCoolingDown(need.item(), now)) {
				coolingDown = true;
				continue;
			}
			pick = entry;
			remaining = need.count() - have;
			break;
		}

		boolean triedRecently = now - jobs.lastDepositTry() < DEPOSIT_RETRY_TICKS;
		boolean listItems = carriesListItems(bot, jobs);
		boolean nearlyFull = bot.freeSlots() < 4 && hasSomethingToDeliver(bot, jobs);
		if (!triedRecently && ((listItems && (pick == null || bot.level() != home
				|| bot.distanceToSqr(Vec3.atCenterOf(chest)) < 16.0D * 16.0D)) || nearlyFull)) {
			step = Step.DEPOSIT;
			travel.reset(bot);
			return;
		}
		if (nearlyFull || (chestTrouble && listItems)) {
			// The last trip couldn't unload (chest or base full): wait instead of going back and forth.
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_full", chest.toShortString()));
			return;
		}

		if (pick == null) {
			jobs.setPhase(coolingDown ? JobConfig.Phase.NOT_FOUND : JobConfig.Phase.STANDBY);
			if (!coolingDown && !doneAnnounced) {
				doneAnnounced = true;
				bot.tellOwner(Component.translatable("butterbot.msg.job_list_done", chest.toShortString()));
				bot.speak(ModSounds.VOICE_DONE);
			}
			step = Step.STANDBY;
			timer = 0;
			travel.reset(bot);
			return;
		}

		if (doneAnnounced) {
			doneAnnounced = false;
			bot.say(Component.translatable("butterbot.msg.job_list_short", new ItemStack(pick.item()).getHoverName()));
		}
		nextEntry = pick;
		nextCount = remaining;

		if (jobs.site() == null || jobs.siteItem() != pick.item()) {
			BlockPos middle = jobs.zoneCenter(bot.getBasePos());
			BlockPos site = middle == null ? null : JobSites.pick(home, middle, jobs.zone(), pick, jobs, bot.getRandom());
			if (site == null) {
				jobs.coolDown(pick.item(), now + NOT_FOUND_COOLDOWN);
				bot.tellOwner(Component.translatable("butterbot.msg.job_no_site", new ItemStack(pick.item()).getHoverName()));
				return;
			}
			jobs.setSite(site, pick.item(), false);
		}
		step = Step.TRAVEL;
		travel.reset(bot);
	}

	private void travelToSite(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		BlockPos site = jobs.site();
		ResourceCatalog.Entry entry = nextEntry;
		if (site == null || entry == null) {
			step = Step.CHECK;
			return;
		}
		jobs.setPhase(JobConfig.Phase.TRAVEL);
		bot.setBotState(BotState.MOVING_TO_TARGET);
		boolean there = bot.level() == home && (jobs.isSiteExact()
				? bot.distanceToSqr(Vec3.atBottomCenterOf(site)) < 6.0D * 6.0D
				: Walker.horizontalDistSq(bot, site) < SITE_ARRIVED_SQ);
		if (there) {
			BlockPos middle = jobs.zoneCenter(bot.getBasePos());
			int fromBase = middle == null ? 0 : (int) Math.round(Math.sqrt(bot.blockPosition().distSqr(middle.atY(bot.getBlockY()))));
			bot.say(Component.translatable("butterbot.msg.job_working", new ItemStack(entry.item()).getHoverName(), nextCount, fromBase));
			jobs.setPhase(JobConfig.Phase.WORKING);
			bot.assignJobMineTask(entry, nextCount);
			return;
		}
		JobTravel.Result result = travel.moveTo(bot, home, site, 3.0D, !jobs.isSiteExact());
		if (result == JobTravel.Result.FAILED) {
			bot.onJobSiteFailed(entry.item());
			step = Step.CHECK;
		}
	}

	private void deposit(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		BlockPos chest = jobs.chest();
		jobs.setPhase(JobConfig.Phase.DEPOSIT);
		bot.setBotState(BotState.RETURNING);
		JobTravel.Result result = travel.moveTo(bot, home, chest, CHEST_REACH, false);
		if (result == JobTravel.Result.FAILED) {
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_unreachable", chest.toShortString()));
			return;
		}
		if (result != JobTravel.Result.ARRIVED) {
			return;
		}
		bot.getLookControl().setLookAt(Vec3.atCenterOf(chest));
		if (depositAtChest(bot, home, chest) < 0) {
			step = Step.WAIT;
			timer = WAIT_AFTER_PROBLEM;
			return;
		}
		step = Step.CHECK;
	}

	private void standby(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		BlockPos chest = jobs.chest();
		bot.setBotState(BotState.IDLE);
		if (bot.level() != home || bot.distanceToSqr(Vec3.atCenterOf(chest)) > 6.0D * 6.0D) {
			if (travel.moveTo(bot, home, chest, 4.0D, false) == JobTravel.Result.FAILED) {
				step = Step.WAIT;
				timer = WAIT_AFTER_PROBLEM;
			}
			return;
		}
		bot.getNavigation().stop();
		if (++timer >= STANDBY_RECHECK_TICKS) {
			timer = 0;
			step = Step.CHECK;
		}
	}

	private void problem(ButterBotEntity bot, JobConfig jobs, Component message) {
		jobs.setPhase(JobConfig.Phase.CHEST_PROBLEM);
		warn(bot, message);
		step = Step.WAIT;
		timer = WAIT_AFTER_PROBLEM;
	}

	private static void warn(ButterBotEntity bot, Component message) {
		if (bot.getJobs().warnNow(bot.level().getGameTime())) {
			bot.tellOwner(message.copy().withStyle(ChatFormatting.GOLD));
		}
	}

	private void needsSetup(ButterBotEntity bot, JobConfig jobs, String messageKey) {
		jobs.setPhase(JobConfig.Phase.SETUP);
		bot.setBotState(BotState.IDLE);
		bot.getNavigation().stop();
		long now = bot.level().getGameTime();
		if (now - lastReminder >= REMIND_TICKS) {
			lastReminder = now;
			bot.tellOwner(Component.translatable(messageKey, jobs.job().displayName()));
		}
	}

	private static boolean carriesListItems(ButterBotEntity bot, JobConfig jobs) {
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty() && jobs.isNeeded(stack.getItem())) {
				return true;
			}
		}
		return false;
	}

	/** True if the backpack holds something for the chest (list items, or loot worth keeping). */
	private static boolean hasSomethingToDeliver(ButterBotEntity bot, JobConfig jobs) {
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty() && (jobs.isNeeded(stack.getItem()) || !JobStorage.isSupply(stack))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Unloads at the job chest: list items go in it; other finds go to the Bot Base when it is near the
	 * chest, otherwise into the chest too. Torches, food and a few lava-plugging blocks are kept.
	 * Returns the number of list items stored, or -1 if the chest is missing or full.
	 */
	public static int depositAtChest(ButterBotEntity bot, ServerLevel home, BlockPos chestPos) {
		JobConfig jobs = bot.getJobs();
		jobs.markDepositTry(home.getGameTime());
		Container chest = JobStorage.open(home, chestPos);
		if (chest == null) {
			jobs.setPhase(JobConfig.Phase.CHEST_PROBLEM);
			warn(bot, Component.translatable("butterbot.msg.job_chest_missing", chestPos.toShortString()));
			return -1;
		}
		boolean[] full = {false};
		int stored = JobStorage.deposit(bot, chest, stack -> jobs.isNeeded(stack.getItem()), full);
		BotBaseBlockEntity base = bot.getBase();
		Container extra = base != null && base.getBlockPos().closerThan(chestPos, 32.0D) ? base : chest;
		boolean[] extraFull = {false};
		int[] fillerKept = {0};
		int others = JobStorage.deposit(bot, extra, stack -> {
			if (jobs.isNeeded(stack.getItem())) {
				return false;
			}
			if (ButterBotEntity.isFiller(stack)) {
				// A stack or so of cobblestone stays for plugging lava; the rest is stored.
				if (fillerKept[0] + stack.getCount() <= KEEP_FILLER) {
					fillerKept[0] += stack.getCount();
					return false;
				}
				return true;
			}
			return !JobStorage.isSupply(stack);
		}, extraFull);
		if (stored + others > 0) {
			JobStorage.playOpenClose(home, chestPos);
			jobs.addStat(stored);
			bot.say(Component.translatable("butterbot.msg.job_deposited", stored + others));
			bot.speak(ModSounds.VOICE_GIVE);
		}
		// Remember the new contents right away so the next check doesn't have to look again.
		Map<Item, Integer> counts = new HashMap<>();
		List<Item> items = new ArrayList<>();
		jobs.needs().forEach(need -> items.add(need.item()));
		if (JobStorage.countInto(home, chestPos, items, counts)) {
			jobs.rememberChestCounts(counts, home.getGameTime());
		}
		if (full[0] || (extraFull[0] && bot.freeSlots() < 4)) {
			jobs.setPhase(JobConfig.Phase.CHEST_PROBLEM);
			warn(bot, Component.translatable("butterbot.msg.job_chest_full", chestPos.toShortString()));
			return -1;
		}
		return stored;
	}

	// ================================================================ guards

	private void tickPatrol(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home) {
		BlockPos center = jobs.center();
		if (home == null || center == null) {
			needsSetup(bot, jobs, "butterbot.msg.job_needs_center");
			return;
		}
		int radius = jobs.radius();
		if (bot.level() != home || Walker.horizontalDistSq(bot, center) > (radius + 16.0D) * (radius + 16.0D)) {
			jobs.setPhase(JobConfig.Phase.PATROL);
			if (travel.moveTo(bot, home, center, Math.max(3.0D, radius * 0.5D), false) == JobTravel.Result.FAILED) {
				warn(bot, Component.translatable("butterbot.msg.job_cant_reach_area", center.toShortString()));
			}
			return;
		}

		long now = home.getGameTime();
		if (bot.tickCount % 10 == 0) {
			bot.absorbExperience(bot.position(), 3.0D);
		}
		int loot = lootCount(bot);
		if (patrolStep != PatrolStep.DEPOSIT && loot > 0
				&& (loot >= PATROL_LOOT_STACK || bot.freeSlots() <= 3 || now - lastDeposit > PATROL_DEPOSIT_TICKS)) {
			patrolStep = PatrolStep.DEPOSIT;
			travel.reset(bot);
		}

		switch (patrolStep) {
			case DEPOSIT -> depositLoot(bot, jobs, home, center, radius, now);
			case PICKUP -> pickUp(bot, center, radius);
			case WALK -> {
				jobs.setPhase(JobConfig.Phase.PATROL);
				if (lookForLoot(bot, home, center, radius)) {
					return;
				}
				if (waypoint == null) {
					waypoint = pickWaypoint(bot, home, center, radius);
					walker.reset(bot);
					if (waypoint == null) {
						return;
					}
				}
				bot.setBotState(BotState.MOVING_TO_TARGET);
				if (Walker.horizontalDistSq(bot, waypoint) < 2.0D * 2.0D) {
					patrolStep = PatrolStep.LOOK;
					timer = 40 + bot.getRandom().nextInt(80);
					waypoint = null;
					bot.getNavigation().stop();
				} else if (!walker.walk(bot, Vec3.atBottomCenterOf(waypoint), 0.9D, false, 80)) {
					waypoint = null;
				}
			}
			case LOOK -> {
				jobs.setPhase(JobConfig.Phase.PATROL);
				bot.setBotState(BotState.IDLE);
				bot.getNavigation().stop();
				if (lookForLoot(bot, home, center, radius)) {
					return;
				}
				if (--timer <= 0) {
					patrolStep = PatrolStep.WALK;
				}
			}
		}
	}

	/** Loot lying in the area (things nobody threw away on purpose): go and pick it up. */
	private boolean lookForLoot(ButterBotEntity bot, ServerLevel home, BlockPos center, int radius) {
		if (bot.tickCount % 20 != 0 || bot.isInventoryFull()) {
			return false;
		}
		AABB area = new AABB(center).inflate(radius, 10.0D, radius);
		double best = Double.MAX_VALUE;
		ItemEntity found = null;
		for (ItemEntity item : home.getEntitiesOfClass(ItemEntity.class, area, item -> item.isAlive() && !item.hasPickUpDelay()
				&& item.getOwner() == null && !item.getItem().isEmpty())) {
			double dx = item.getX() - (center.getX() + 0.5D);
			double dz = item.getZ() - (center.getZ() + 0.5D);
			if (dx * dx + dz * dz > (double) radius * radius) {
				continue;
			}
			double distance = bot.distanceToSqr(item);
			if (distance < best) {
				best = distance;
				found = item;
			}
		}
		if (found == null) {
			return false;
		}
		pickup = found;
		patrolStep = PatrolStep.PICKUP;
		walker.reset(bot);
		return true;
	}

	private void pickUp(ButterBotEntity bot, BlockPos center, int radius) {
		ItemEntity item = pickup;
		if (item == null || !item.isAlive()) {
			pickup = null;
			patrolStep = PatrolStep.WALK;
			return;
		}
		bot.setBotState(BotState.MOVING_TO_TARGET);
		if (bot.distanceToSqr(item) < 2.0D * 2.0D) {
			bot.getNavigation().stop();
			bot.collectNearbyDrops(item.position(), 2.0D);
			bot.absorbExperience(item.position(), 3.0D);
			pickup = null;
			patrolStep = PatrolStep.WALK;
			return;
		}
		if (!walker.walk(bot, item.position(), 1.1D, false, 60)) {
			pickup = null;
			patrolStep = PatrolStep.WALK;
		}
	}

	private void depositLoot(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius, long now) {
		jobs.setPhase(JobConfig.Phase.LOOT);
		BlockPos chestPos = jobs.chest() != null && JobStorage.isStorage(home, jobs.chest())
				? jobs.chest() : JobStorage.nearest(home, center, radius + 8);
		if (chestPos == null) {
			warn(bot, Component.translatable("butterbot.msg.job_no_loot_chest", radius));
			lastDeposit = now;
			patrolStep = PatrolStep.WALK;
			return;
		}
		bot.setBotState(BotState.RETURNING);
		JobTravel.Result result = travel.moveTo(bot, home, chestPos, CHEST_REACH, false);
		if (result == JobTravel.Result.MOVING) {
			return;
		}
		lastDeposit = now;
		patrolStep = PatrolStep.WALK;
		if (result == JobTravel.Result.FAILED) {
			return;
		}
		Container chest = JobStorage.open(home, chestPos);
		if (chest == null) {
			return;
		}
		bot.getLookControl().setLookAt(Vec3.atCenterOf(chestPos));
		boolean[] full = {false};
		int moved = JobStorage.deposit(bot, chest, stack -> !JobStorage.isSupply(stack), full);
		if (moved > 0) {
			JobStorage.playOpenClose(home, chestPos);
			bot.say(Component.translatable("butterbot.msg.job_loot_stored", moved));
		}
		if (full[0]) {
			warn(bot, Component.translatable("butterbot.msg.job_chest_full", chestPos.toShortString()));
		}
	}

	/** A random spot on the ground inside the area. */
	@Nullable
	private static BlockPos pickWaypoint(ButterBotEntity bot, ServerLevel home, BlockPos center, int radius) {
		double angle = bot.getRandom().nextDouble() * Math.PI * 2.0D;
		double distance = Math.sqrt(bot.getRandom().nextDouble()) * radius;
		BlockPos column = center.offset((int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance));
		if (!home.isLoaded(column)) {
			return null;
		}
		BlockPos ground = JobTravel.ground(home, column);
		if (Math.abs(ground.getY() - center.getY()) > 12) {
			// A cliff or a deep valley: stay near the height of the centre instead.
			ground = column;
		}
		return ButterBotEntity.findSafeSpot(home, ground, 0, 2);
	}

	private static int lootCount(ButterBotEntity bot) {
		int count = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty() && !JobStorage.isSupply(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	// ================================================================ farmers

	private void tickFarmer(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home) {
		BlockPos center = jobs.center();
		if (home == null || center == null) {
			needsSetup(bot, jobs, "butterbot.msg.job_needs_center");
			return;
		}
		int radius = jobs.radius();
		jobs.setPhase(JobConfig.Phase.FARMING);
		if (bot.level() != home || Walker.horizontalDistSq(bot, center) > (radius + 8.0D) * (radius + 8.0D)) {
			if (travel.moveTo(bot, home, center, 4.0D, false) == JobTravel.Result.FAILED) {
				warn(bot, Component.translatable("butterbot.msg.job_cant_reach_area", center.toShortString()));
			}
			return;
		}
		bot.assignJobFarmTask(center);
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return false;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
