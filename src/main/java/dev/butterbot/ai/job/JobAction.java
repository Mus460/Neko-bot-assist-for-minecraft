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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a bot's job whenever it has no other order. Miners and gatherers check the job chest, pick the
 * first item on the list that is still short, travel to a site outside the no-dig zone and start a
 * mining run there (the normal mining code does the digging; the delivery brings the haul back to
 * the chest). When everything on the list is in the chest the bot waits by it and starts again as
 * soon as something is taken out. Guards walk around their area, collect loot lying there and put
 * it in a chest. Farmers keep a farming run going around their spot. Ranchers patrol like guards and
 * look after the animals: breeding, shearing, milking, collecting eggs and (if allowed) culling.
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
	private static final int KEEP_FOOD = 16;
	private static final long UNREACHABLE_LOOT_TICKS = 20L * 60L * 5L;
	private static final double OWNER_ITEM_DISTANCE_SQ = 5.0D * 5.0D;
	/** Rancher: close enough to feed, shear or milk an animal (over a fence too). */
	private static final double ANIMAL_REACH_SQ = 3.0D * 3.0D;
	private static final int CHORE_TIMEOUT = 20 * 15;
	/** Into a pen it can't walk into, the bot hops over the fence (only when this close). */
	private static final double HOP_RANGE_SQ = 24.0D * 24.0D;
	private static final long UNREACHABLE_ANIMAL_TICKS = 20L * 60L * 2L;
	private static final long FEED_RETRY_TICKS = 20L * 60L * 2L;
	/** A kind with no feed in the chest is looked for again after 2, 4, then 8 minutes. */
	private static final int MAX_FEED_WAIT_SHIFT = 2;
	/** Vanilla only pairs animals within 8 blocks of each other: the two fed ones are kept closer than that. */
	private static final double PAIR_RANGE_SQ = 6.0D * 6.0D;
	private static final long MILK_AGAIN_TICKS = 20L * 60L * 5L;
	/** Feed taken from the chest for each kind of animal. */
	private static final int FEED_STOCK = 32;
	/** Feed (per item) and empty buckets a rancher keeps instead of storing them. */
	private static final int KEEP_FEED = 64;
	private static final int BUCKET_STOCK = 4;
	private static final int KEEP_BUCKETS = 8;

	private enum Step {CHECK, DEPOSIT, TRAVEL, STANDBY, WAIT}

	private enum PatrolStep {WALK, LOOK, PICKUP, DEPOSIT, CHORE}

	private enum Chore {BREED, SHEAR, MILK, CULL}

	private final JobTravel travel = new JobTravel();
	private final Walker walker = new Walker();
	private Step step = Step.CHECK;
	private int timer;
	@Nullable
	private ResourceCatalog.Entry nextEntry;
	private int nextCount;
	private boolean doneAnnounced;
	private long lastReminder = JobConfig.NEVER;

	private PatrolStep patrolStep = PatrolStep.WALK;
	@Nullable
	private BlockPos waypoint;
	@Nullable
	private ItemEntity pickup;
	private long lastDeposit;
	/** After a failed loot trip (no chest, chest full, can't get there) the guard waits this long before trying again. */
	private long depositBlockedUntil;
	/** Loot the guard couldn't get to, by entity id, with the time it may try again. */
	private final Map<Integer, Long> unreachableLoot = new HashMap<>();
	private boolean pickupHopped;

	@Nullable
	private Animal choreAnimal;
	/** When breeding: the second animal of the pair, fed right after the first so the pair isn't left half done. */
	@Nullable
	private Animal chorePartner;
	@Nullable
	private Chore chore;
	private int choreTicks;
	private boolean choreHopped;
	/** Animals the rancher couldn't get to (or kill), by entity id, with the time it may try again. */
	private final Map<Integer, Long> unreachableAnimals = new HashMap<>();
	/** Cows milked lately, by entity id. */
	private final Map<Integer, Long> milked = new HashMap<>();
	/** The animal handed to the fighting code to cull, checked when the job gets the bot back. */
	@Nullable
	private UUID culling;
	private long cullOrdered;
	/** Set by the last look over the herds: some herd could breed but the bot has no feed for it. */
	private boolean wantsFeed;
	@Nullable
	private Animal hungry;
	private long feedRetryAt = JobConfig.NEVER;
	/** Kinds the chest had no feed for: trips in a row that found none, and when to look again. */
	private final Map<EntityType<?>, Integer> feedFails = new HashMap<>();
	private final Map<EntityType<?>, Long> feedWaitUntil = new HashMap<>();

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
		choreAnimal = null;
		chorePartner = null;
		chore = null;
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
			case GUARD -> tickPatrol(bot, jobs, home, false);
			case RANCHER -> tickPatrol(bot, jobs, home, true);
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
		if (!home.dimension().equals(Level.OVERWORLD)) {
			needsSetup(bot, jobs, "butterbot.msg.job_overworld_only");
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
		} else if (jobs.chestCountsTime() == Long.MIN_VALUE || now - jobs.chestCountsTime() > CHEST_MEMORY_TICKS) {
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
			// The last trip couldn't unload (chest full, missing or out of reach): wait instead of going back and forth.
			problem(bot, jobs, Component.translatable(nearlyFull ? "butterbot.msg.job_chest_full" : "butterbot.msg.job_chest_trouble",
					chest.toShortString()));
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
	public static boolean hasSomethingToDeliver(ButterBotEntity bot, JobConfig jobs) {
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

	// ================================================================ guards and ranchers

	/**
	 * Guards and ranchers both walk around their area, pick up what lies there and take it to a chest
	 * now and then. A rancher also looks after the animals in between (see {@link #findChore}).
	 */
	private void tickPatrol(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home, boolean ranch) {
		BlockPos center = jobs.center();
		if (home == null || center == null) {
			needsSetup(bot, jobs, "butterbot.msg.job_needs_center");
			return;
		}
		JobConfig.Phase working = ranch ? JobConfig.Phase.RANCHING : JobConfig.Phase.PATROL;
		int radius = jobs.radius();
		if (bot.level() != home || Walker.horizontalDistSq(bot, center) > (radius + 16.0D) * (radius + 16.0D)) {
			jobs.setPhase(working);
			if (travel.moveTo(bot, home, center, Math.max(3.0D, radius * 0.5D), false) == JobTravel.Result.FAILED) {
				warn(bot, Component.translatable("butterbot.msg.job_cant_reach_area", center.toShortString()));
			}
			return;
		}

		long now = home.getGameTime();
		if (ranch) {
			checkCull(bot, home, now);
		}
		if (bot.tickCount % 10 == 0) {
			bot.absorbExperience(bot.position(), 3.0D);
		}
		int loot = lootCount(bot, ranch);
		boolean restock = ranch && wantsFeed && now >= feedRetryAt;
		if (patrolStep != PatrolStep.DEPOSIT && patrolStep != PatrolStep.CHORE && now >= depositBlockedUntil && (restock
				|| loot > 0 && (loot >= PATROL_LOOT_STACK || bot.freeSlots() <= 3 || now - lastDeposit > PATROL_DEPOSIT_TICKS))) {
			patrolStep = PatrolStep.DEPOSIT;
			travel.reset(bot);
		}

		switch (patrolStep) {
			case DEPOSIT -> depositLoot(bot, jobs, home, center, radius, now, ranch);
			case PICKUP -> pickUp(bot, center, radius, ranch);
			case CHORE -> doChore(bot, jobs, home, center, radius, now);
			case WALK -> {
				jobs.setPhase(working);
				if (lookForLoot(bot, home, center, radius) || (ranch && findChore(bot, jobs, home, center, radius, now))) {
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
				jobs.setPhase(working);
				bot.setBotState(BotState.IDLE);
				bot.getNavigation().stop();
				if (lookForLoot(bot, home, center, radius) || (ranch && findChore(bot, jobs, home, center, radius, now))) {
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
		if (bot.tickCount % 20 != 0 || bot.isInventoryFull() || (bot.freeSlots() <= 3 && home.getGameTime() < depositBlockedUntil)) {
			return false;
		}
		long now = home.getGameTime();
		unreachableLoot.values().removeIf(until -> until < now);
		Player owner = bot.getOwner();
		AABB area = new AABB(center).inflate(radius, 10.0D, radius);
		double best = Double.MAX_VALUE;
		ItemEntity found = null;
		for (ItemEntity item : home.getEntitiesOfClass(ItemEntity.class, area, item -> item.isAlive() && !item.hasPickUpDelay()
				&& item.getOwner() == null && !item.getItem().isEmpty() && !unreachableLoot.containsKey(item.getId())
				&& (owner == null || owner.level() != home || item.distanceToSqr(owner) > OWNER_ITEM_DISTANCE_SQ))) {
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
		pickupHopped = false;
		patrolStep = PatrolStep.PICKUP;
		walker.reset(bot);
		return true;
	}

	private void pickUp(ButterBotEntity bot, BlockPos center, int radius, boolean ranch) {
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
			if (ranch && !pickupHopped && bot.distanceToSqr(item) < HOP_RANGE_SQ && bot.teleportNear(item.blockPosition(), false)) {
				// An egg or some wool inside a pen: hop over the fence.
				pickupHopped = true;
				walker.reset(bot);
				return;
			}
			// Down a pit, behind a fence, on a roof: leave it for a while instead of trying again and again.
			unreachableLoot.put(item.getId(), bot.level().getGameTime() + UNREACHABLE_LOOT_TICKS);
			pickup = null;
			patrolStep = PatrolStep.WALK;
		}
	}

	private void depositLoot(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius, long now, boolean ranch) {
		jobs.setPhase(JobConfig.Phase.LOOT);
		BlockPos chest = jobs.chest();
		BlockPos chestPos = chest != null && (!home.isLoaded(chest) || JobStorage.isStorage(home, chest))
				? chest : JobStorage.nearest(home, center, radius + 8);
		if (chestPos == null) {
			warn(bot, Component.translatable("butterbot.msg.job_no_loot_chest", radius));
			blockDeposit(now);
			patrolStep = PatrolStep.WALK;
			if (ranch) {
				feedTripDone(bot, now);
			}
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
			warn(bot, Component.translatable("butterbot.msg.job_chest_unreachable", chestPos.toShortString()));
			blockDeposit(now);
			if (ranch) {
				feedTripDone(bot, now);
			}
			return;
		}
		Container storage = JobStorage.open(home, chestPos);
		if (storage == null) {
			warn(bot, Component.translatable("butterbot.msg.job_chest_missing", chestPos.toShortString()));
			blockDeposit(now);
			if (ranch) {
				feedTripDone(bot, now);
			}
			return;
		}
		bot.getLookControl().setLookAt(Vec3.atCenterOf(chestPos));
		boolean[] full = {false};
		// Everything goes in except torches and a small reserve of food and lava-plugging blocks
		// (and, for a rancher, its feed and empty buckets).
		int[] kept = {0, 0, 0};
		Map<Item, Integer> keptFeed = new HashMap<>();
		int moved = JobStorage.deposit(bot, storage, stack -> {
			if (stack.is(Items.TORCH)) {
				return false;
			}
			if (ranch && stack.is(Items.BUCKET)) {
				if (kept[2] + stack.getCount() <= KEEP_BUCKETS) {
					kept[2] += stack.getCount();
					return false;
				}
				return true;
			}
			if (ranch && isFeed(stack)) {
				int already = keptFeed.getOrDefault(stack.getItem(), 0);
				if (already + stack.getCount() <= KEEP_FEED) {
					keptFeed.put(stack.getItem(), already + stack.getCount());
					return false;
				}
				return true;
			}
			int reserve = ButterBotEntity.isSafeFood(stack) ? 0 : ButterBotEntity.isFiller(stack) ? 1 : -1;
			if (reserve < 0) {
				return true;
			}
			int limit = reserve == 0 ? KEEP_FOOD : KEEP_FILLER;
			if (kept[reserve] + stack.getCount() <= limit) {
				kept[reserve] += stack.getCount();
				return false;
			}
			return true;
		}, full);
		int taken = ranch ? restock(bot, storage, home, center, radius) : 0;
		if (moved + taken > 0) {
			JobStorage.playOpenClose(home, chestPos);
		}
		if (moved > 0) {
			bot.say(Component.translatable("butterbot.msg.job_loot_stored", moved));
		}
		if (taken > 0) {
			bot.say(Component.translatable("butterbot.msg.ranch_restocked", taken));
		}
		if (full[0]) {
			warn(bot, Component.translatable("butterbot.msg.job_chest_full", chestPos.toShortString()));
			blockDeposit(now);
		}
		if (ranch) {
			feedTripDone(bot, now);
		}
	}

	private void blockDeposit(long now) {
		lastDeposit = now;
		depositBlockedUntil = now + PATROL_DEPOSIT_TICKS;
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

	/** What the bot carries that belongs in the chest (a rancher's feed and empty buckets stay with it). */
	private static int lootCount(ButterBotEntity bot, boolean ranch) {
		int count = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty() && !JobStorage.isSupply(stack) && !(ranch && (isFeed(stack) || stack.is(Items.BUCKET)))) {
				count += stack.getCount();
			}
		}
		return count;
	}

	// ================================================================ rancher chores

	/**
	 * Looks over the herds in the area and starts the most useful chore: finishing a pair it began to
	 * breed, culling a herd that is over the limit (only when the owner allowed it), breeding a herd
	 * that is under it, shearing sheep, milking cows. Notes when a herd could breed but there is no
	 * feed, so the next chest trip fetches some (or the owner is told).
	 */
	private boolean findChore(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius, long now) {
		if (bot.tickCount % 20 != 0) {
			return false;
		}
		unreachableAnimals.values().removeIf(until -> until < now);
		milked.values().removeIf(until -> until < now);
		wantsFeed = false;
		hungry = null;
		int buckets = bot.countItem(Items.BUCKET);
		boolean canMilk = buckets > 0 && (bot.freeSlots() > 0 || buckets == 1);
		Pick pick = new Pick();
		Map<EntityType<?>, Integer> othersCulling = culledByOthers(bot, home, center, radius);
		for (List<Animal> herd : Livestock.around(home, center, radius).values()) {
			EntityType<?> kind = herd.getFirst().getType();
			int limit = jobs.herdLimit(kind);
			int adults = 0;
			List<Animal> waiting = new ArrayList<>();
			List<Animal> ready = new ArrayList<>();
			List<Animal> cullable = new ArrayList<>();
			for (Animal animal : herd) {
				if (animal.isBaby()) {
					continue;
				}
				adults++;
				if (animal.isInLove()) {
					waiting.add(animal);
					continue;
				}
				if (unreachableAnimals.containsKey(animal.getId())) {
					continue;
				}
				if (canBreedNow(animal)) {
					ready.add(animal);
				}
				if (isCullable(animal)) {
					cullable.add(animal);
				}
			}
			int inLove = waiting.size();
			// Young that the pairs in love are about to have count towards the limit already.
			int pending = (inLove + 1) / 2;
			int feed = feedCount(bot, herd.getFirst());
			if (feed >= 2) {
				feedFails.remove(kind);
				feedWaitUntil.remove(kind);
			}
			int excess = herd.size() - othersCulling.getOrDefault(kind, 0) - limit;
			boolean finishing = false;
			if (inLove % 2 == 1 && feed > 0 && herd.size() + pending <= limit) {
				// One of a pair is fed and waiting: feed a partner close to it before the love wears off.
				for (Animal candidate : ready) {
					if (closestSqr(waiting, candidate) <= PAIR_RANGE_SQ) {
						pick.offer(bot, Chore.BREED, 0, candidate, null);
						finishing = true;
					}
				}
			}
			if (finishing) {
				continue;
			}
			if (jobs.butcher() && excess > 0 && adults > 2) {
				pick.offerAll(bot, Chore.CULL, 1, cullable);
			} else if (herd.size() + pending < limit && ready.size() >= 2) {
				if (feed >= 2) {
					offerPairs(bot, pick, ready);
				} else if (hungry == null && now >= feedWaitUntil.getOrDefault(kind, JobConfig.NEVER)) {
					wantsFeed = true;
					hungry = herd.getFirst();
				}
			}
			for (Animal animal : herd) {
				if (unreachableAnimals.containsKey(animal.getId())) {
					continue;
				}
				if (animal instanceof Sheep sheep && sheep.readyForShearing()) {
					pick.offer(bot, Chore.SHEAR, 3, animal);
				} else if (canMilk && animal instanceof AbstractCow && !animal.isBaby() && !milked.containsKey(animal.getId())) {
					pick.offer(bot, Chore.MILK, 4, animal);
				}
			}
		}
		if (pick.animal == null) {
			return false;
		}
		choreAnimal = pick.animal;
		chorePartner = pick.partner;
		chore = pick.chore;
		choreTicks = 0;
		choreHopped = false;
		walker.reset(bot);
		patrolStep = PatrolStep.CHORE;
		return true;
	}

	/** Walks up to the animal of the current chore and does it. */
	private void doChore(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius, long now) {
		jobs.setPhase(JobConfig.Phase.RANCHING);
		Animal animal = choreAnimal;
		Chore what = chore;
		if (animal == null || what == null || !animal.isAlive() || animal.level() != home
				|| !Livestock.inArea(animal, center, radius + 8) || !stillWanted(bot, jobs, animal, what)) {
			endChore();
			return;
		}
		if (bot.distanceToSqr(animal) > ANIMAL_REACH_SQ) {
			bot.setBotState(BotState.MOVING_TO_TARGET);
			if (++choreTicks > CHORE_TIMEOUT || !walker.walk(bot, animal.position(), 1.0D, false, 50)) {
				// Behind a fence: hop into the pen once; if even that doesn't help, leave the animal for a while.
				if (!choreHopped && bot.distanceToSqr(animal) < HOP_RANGE_SQ && bot.teleportNear(animal.blockPosition(), false)) {
					choreHopped = true;
					choreTicks = 0;
					walker.reset(bot);
					return;
				}
				unreachableAnimals.put(animal.getId(), now + UNREACHABLE_ANIMAL_TICKS);
				endChore();
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(animal);
		switch (what) {
			case BREED -> {
				feed(bot, jobs, home, center, radius, animal);
				Animal partner = chorePartner;
				chorePartner = null;
				if (partner != null && partner.isAlive() && canBreedNow(partner) && feedSlot(bot, partner) >= 0) {
					// Straight on to its partner, before anything else: half a pair only wastes the feed.
					choreAnimal = partner;
					choreTicks = 0;
					choreHopped = false;
					walker.reset(bot);
					return;
				}
			}
			case SHEAR -> shear(bot, jobs, home, animal);
			case MILK -> milk(bot, jobs, home, animal, now);
			case CULL -> {
				// Counted again right before: another rancher may have culled one meanwhile.
				if (overLimit(bot, jobs, home, center, radius, animal.getType())) {
					// The fighting code takes over until it is dead; checkCull looks at how it went.
					bot.cullAnimal(animal);
					culling = animal.getUUID();
					cullOrdered = now;
				}
			}
		}
		endChore();
	}

	private void endChore() {
		choreAnimal = null;
		chorePartner = null;
		chore = null;
		patrolStep = PatrolStep.WALK;
	}

	/** {@code kind} is over its limit (not counting animals other bots are culling) and has more than 2 grown up. */
	private static boolean overLimit(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius,
									 EntityType<?> kind) {
		List<Animal> herd = Livestock.around(home, center, radius).getOrDefault(kind, List.of());
		int others = culledByOthers(bot, home, center, radius).getOrDefault(kind, 0);
		int adults = 0;
		for (Animal animal : herd) {
			if (!animal.isBaby()) {
				adults++;
			}
		}
		return herd.size() - others > jobs.herdLimit(kind) && adults - others > 2;
	}

	/** Animals other bots are culling right now, by kind (so two ranchers don't both cull the same extra one). */
	private static Map<EntityType<?>, Integer> culledByOthers(ButterBotEntity bot, ServerLevel home, BlockPos center, int radius) {
		Map<EntityType<?>, Integer> counts = new HashMap<>();
		AABB around = new AABB(center).inflate(radius + 16.0D, 16.0D, radius + 16.0D);
		for (ButterBotEntity other : home.getEntitiesOfClass(ButterBotEntity.class, around, other -> other != bot && other.isAlive())) {
			Mob target = other.getCullTarget();
			if (target instanceof Animal animal) {
				counts.merge(animal.getType(), 1, Integer::sum);
			}
		}
		return counts;
	}

	/** For each ready animal, offers it with its nearest ready partner (close enough for vanilla to pair them). */
	private static void offerPairs(ButterBotEntity bot, Pick pick, List<Animal> ready) {
		for (Animal first : ready) {
			Animal partner = null;
			double best = PAIR_RANGE_SQ;
			for (Animal other : ready) {
				double distance = first.distanceToSqr(other);
				if (other != first && distance <= best) {
					best = distance;
					partner = other;
				}
			}
			if (partner != null) {
				pick.offer(bot, Chore.BREED, 2, first, partner);
			}
		}
	}

	private static double closestSqr(List<Animal> animals, Animal to) {
		double best = Double.MAX_VALUE;
		for (Animal animal : animals) {
			best = Math.min(best, animal.distanceToSqr(to));
		}
		return best;
	}

	/** Room under the limit for {@code extra} more of {@code kind}, counting the young that pairs in love will have. */
	private static boolean hasRoom(JobConfig jobs, ServerLevel home, BlockPos center, int radius, EntityType<?> kind, int extra) {
		List<Animal> herd = Livestock.around(home, center, radius).getOrDefault(kind, List.of());
		int inLove = 0;
		for (Animal animal : herd) {
			if (!animal.isBaby() && animal.isInLove()) {
				inLove++;
			}
		}
		return herd.size() + (inLove + 1) / 2 + extra <= jobs.herdLimit(kind);
	}

	/** The chore still makes sense (another bot or the owner may have done it meanwhile). */
	private static boolean stillWanted(ButterBotEntity bot, JobConfig jobs, Animal animal, Chore what) {
		return switch (what) {
			case BREED -> canBreedNow(animal) && feedSlot(bot, animal) >= 0;
			case SHEAR -> animal instanceof Sheep sheep && sheep.readyForShearing();
			case MILK -> !animal.isBaby() && bot.countItem(Items.BUCKET) > 0;
			case CULL -> jobs.butcher() && isCullable(animal);
		};
	}

	/** Feeds one animal so it is ready to breed; with rancher mastery a pair sometimes has twins (within the limit). */
	private static void feed(ButterBotEntity bot, JobConfig jobs, ServerLevel home, BlockPos center, int radius, Animal animal) {
		int slot = feedSlot(bot, animal);
		if (slot < 0) {
			return;
		}
		ItemStack stack = bot.getInventory().getItem(slot);
		bot.showTool(stack.copyWithCount(1), 10);
		stack.shrink(1);
		bot.getInventory().setItem(slot, stack);
		animal.setInLove(null);
		home.playSound(null, animal, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7F, 1.0F);
		bot.gainWorkXp(1);
		jobs.addStat(1);
		Animal partner = partnerFor(home, animal);
		if (partner != null && bot.getRandom().nextFloat() < bot.stats().twinChance()
				&& hasRoom(jobs, home, center, radius, animal.getType(), 1)) {
			AgeableMob twin = animal.getBreedOffspring(home, partner);
			if (twin != null) {
				twin.setBaby(true);
				twin.snapTo(animal.getX(), animal.getY(), animal.getZ(), 0.0F, 0.0F);
				home.addFreshEntityWithPassengers(twin);
				home.sendParticles(ParticleTypes.HEART, animal.getX(), animal.getY() + 1.0D, animal.getZ(), 6, 0.4D, 0.3D, 0.4D, 0.0D);
			}
		}
	}

	private static void shear(ButterBotEntity bot, JobConfig jobs, ServerLevel home, Animal animal) {
		if (!(animal instanceof Sheep sheep) || !sheep.readyForShearing()) {
			return;
		}
		ItemStack shears = new ItemStack(Items.SHEARS);
		bot.showTool(shears, 10);
		sheep.shear(home, SoundSource.PLAYERS, shears);
		bot.collectNearbyDrops(sheep.position(), 2.5D);
		bot.gainWorkXp(1);
		jobs.addStat(1);
	}

	private void milk(ButterBotEntity bot, JobConfig jobs, ServerLevel home, Animal animal, long now) {
		if (bot.takeItem(Items.BUCKET, 1) < 1) {
			return;
		}
		ItemStack rest = bot.getInventory().addItem(new ItemStack(Items.MILK_BUCKET));
		if (!rest.isEmpty()) {
			bot.getInventory().addItem(new ItemStack(Items.BUCKET));
			return;
		}
		bot.showTool(new ItemStack(Items.BUCKET), 10);
		home.playSound(null, animal, SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);
		milked.put(animal.getId(), now + MILK_AGAIN_TICKS);
		bot.gainWorkXp(1);
		jobs.addStat(1);
	}

	/** After the fighting code had its go at an animal to cull: one it couldn't kill is left alone for a while. */
	private void checkCull(ButterBotEntity bot, ServerLevel home, long now) {
		UUID id = culling;
		if (id == null || now - cullOrdered < 40L) {
			return;
		}
		culling = null;
		Entity animal = home.getEntity(id);
		if (animal != null && animal.isAlive()) {
			unreachableAnimals.put(animal.getId(), now + UNREACHABLE_ANIMAL_TICKS);
			if (bot.getCullTarget() == animal) {
				bot.stopCulling();
			}
		}
	}

	/**
	 * At the chest: takes feed for every kind of animal around (up to {@value #FEED_STOCK} each) and,
	 * when there are cows, a few empty buckets. Returns how many items were taken.
	 */
	private static int restock(ButterBotEntity bot, Container storage, ServerLevel home, BlockPos center, int radius) {
		int taken = 0;
		boolean cows = false;
		for (List<Animal> herd : Livestock.around(home, center, radius).values()) {
			Animal sample = herd.getFirst();
			cows |= sample instanceof AbstractCow;
			int have = feedCount(bot, sample);
			if (have < FEED_STOCK) {
				taken += JobStorage.take(bot, storage, stack -> eats(sample, stack), FEED_STOCK - have);
			}
		}
		int buckets = bot.countItem(Items.BUCKET);
		if (cows && buckets < BUCKET_STOCK) {
			taken += JobStorage.take(bot, storage, stack -> stack.is(Items.BUCKET), BUCKET_STOCK - buckets);
		}
		return taken;
	}

	/**
	 * After a chest trip: if a herd still has nothing to eat, that kind waits longer each time (2, 4,
	 * then 8 minutes) before the bot looks for feed again, and the owner is told the first time (then
	 * now and again), not on every trip.
	 */
	private void feedTripDone(ButterBotEntity bot, long now) {
		Animal waiting = hungry;
		boolean stillHungry = wantsFeed && waiting != null && waiting.isAlive() && feedCount(bot, waiting) < 2;
		wantsFeed = false;
		feedRetryAt = now + 200L;
		if (!stillHungry) {
			return;
		}
		EntityType<?> kind = waiting.getType();
		int fails = feedFails.merge(kind, 1, Integer::sum);
		feedWaitUntil.put(kind, now + (FEED_RETRY_TICKS << Math.min(fails - 1, MAX_FEED_WAIT_SHIFT)));
		if (fails == 1 || fails % 5 == 0) {
			bot.tellOwner(Component.translatable("butterbot.msg.ranch_no_feed", kind.getDescription(),
					new ItemStack(exampleFeed(waiting)).getHoverName()).withStyle(ChatFormatting.GOLD));
		}
	}

	/** Grown up, not breeding already and not resting after having young. */
	private static boolean canBreedNow(Animal animal) {
		return !animal.isBaby() && animal.getAge() == 0 && animal.canFallInLove() && !animal.isInLove();
	}

	/** Grown animals that may be culled: never babies, named, leashed or saddled animals, or one about to breed. */
	private static boolean isCullable(Animal animal) {
		return !animal.isBaby() && !animal.isInLove() && !animal.hasCustomName() && !animal.isLeashed()
				&& !animal.isSaddled() && !animal.isVehicle() && !animal.isPassenger();
	}

	/** Something farm animals eat (the rancher keeps it for breeding instead of storing it). */
	private static boolean isFeed(ItemStack stack) {
		return stack.is(ItemTags.COW_FOOD) || stack.is(ItemTags.SHEEP_FOOD) || stack.is(ItemTags.PIG_FOOD)
				|| stack.is(ItemTags.CHICKEN_FOOD) || stack.is(ItemTags.RABBIT_FOOD) || stack.is(ItemTags.GOAT_FOOD);
	}

	/** Food {@code animal} breeds on (golden carrots are too precious to feed rabbits). */
	private static boolean eats(Animal animal, ItemStack stack) {
		return !stack.isEmpty() && !stack.is(Items.GOLDEN_CARROT) && animal.isFood(stack);
	}

	private static int feedSlot(ButterBotEntity bot, Animal animal) {
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			if (eats(animal, bot.getInventory().getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	private static int feedCount(ButterBotEntity bot, Animal animal) {
		int count = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (eats(animal, stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** A common food for {@code animal}, to name in messages. */
	private static Item exampleFeed(Animal animal) {
		for (Item item : new Item[]{Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.DANDELION}) {
			if (animal.isFood(new ItemStack(item))) {
				return item;
			}
		}
		return Items.WHEAT;
	}

	/** Another animal of the same kind nearby that is ready to breed (the one {@code animal} will pair with). */
	@Nullable
	private static Animal partnerFor(ServerLevel level, Animal animal) {
		for (Animal other : level.getEntitiesOfClass(Animal.class, animal.getBoundingBox().inflate(8.0D),
				other -> other != animal && other.getClass() == animal.getClass() && other.isInLove() && !other.isBaby())) {
			return other;
		}
		return null;
	}

	/** The best chore found so far while looking over the herds: lowest rank first, then the nearest animal. */
	private static final class Pick {
		@Nullable
		Animal animal;
		@Nullable
		Animal partner;
		@Nullable
		Chore chore;
		int rank = Integer.MAX_VALUE;
		double distance = Double.MAX_VALUE;

		void offer(ButterBotEntity bot, Chore what, int rankOf, Animal candidate) {
			offer(bot, what, rankOf, candidate, null);
		}

		void offer(ButterBotEntity bot, Chore what, int rankOf, Animal candidate, @Nullable Animal pairedWith) {
			double away = bot.distanceToSqr(candidate);
			if (rankOf < rank || rankOf == rank && away < distance) {
				animal = candidate;
				partner = pairedWith;
				chore = what;
				rank = rankOf;
				distance = away;
			}
		}

		void offerAll(ButterBotEntity bot, Chore what, int rankOf, List<Animal> candidates) {
			for (Animal candidate : candidates) {
				offer(bot, what, rankOf, candidate);
			}
		}
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
