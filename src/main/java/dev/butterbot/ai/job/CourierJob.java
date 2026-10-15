package dev.butterbot.ai.job;

import dev.butterbot.ai.BotSmelter;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Courier: goes round its source chests, takes what is in them and carries it to the job chest,
 * again and again. With smelting on it takes fuel along and its built-in furnace smelts raw ores on
 * the way; at the chest it waits while the furnace still gets somewhere, so the chest gets ingots.
 */
final class CourierJob {

	private static final double CHEST_REACH = 2.8D;
	/** After a round that found nothing, look again this much later. */
	private static final int IDLE_TICKS = 20 * 30;
	private static final int PROBLEM_TICKS = 20 * 30;
	/** Longest wait by the chest for the furnace to finish. */
	private static final int SMELT_WAIT_TICKS = 20 * 90;
	/** The furnace hasn't made anything for this long (no room, no fuel): stop waiting for it. */
	private static final int SMELT_STALL_TICKS = 20 * 3;
	/** Fuel and food (in all) the courier keeps for itself instead of storing them. */
	private static final int KEEP_FUEL = 32;
	private static final int KEEP_FOOD = 16;
	/** Free backpack slots needed to go on to the next source rather than unload first. */
	private static final int MIN_FREE_SLOTS = 3;
	/** Slots left free when smelting, for the furnace's ingots. */
	private static final int FURNACE_ROOM = 2;
	private static final long REMIND_TICKS = 20L * 60L * 3L;

	private enum Step {LOAD, UNLOAD, WAIT}

	private final JobTravel travel = new JobTravel();
	private Step step = Step.LOAD;
	/** Sources visited in this round; each round starts one source further on, so every source gets its turn. */
	private int visited;
	private int roundStart;
	/** Items taken in this round (a round with nothing to take ends in a wait). */
	private int taken;
	private int timer;
	private int smeltedMark;
	private int lastSmeltProgress;
	private long lastReminder = JobConfig.NEVER;

	/** The job (or its settings) changed: start a fresh round. */
	void reset(ButterBotEntity bot) {
		travel.reset(bot);
		step = Step.LOAD;
		visited = 0;
		taken = 0;
		timer = 0;
	}

	/** Back to work after a fight or an order: carry on where it was. */
	void resume(ButterBotEntity bot) {
		travel.reset(bot);
	}

	void tick(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home) {
		if (home == null || jobs.chest() == null) {
			needsSetup(bot, jobs, "butterbot.msg.job_needs_chest");
			return;
		}
		if (jobs.sources().isEmpty() && step == Step.LOAD) {
			if (!carriesCargo(bot, jobs)) {
				needsSetup(bot, jobs, "butterbot.msg.job_needs_sources");
				return;
			}
			// The sources were taken off the list: bring what it still carries first.
			goUnload(bot);
		}
		switch (step) {
			case LOAD -> load(bot, jobs, home);
			case UNLOAD -> unload(bot, jobs, home);
			case WAIT -> {
				bot.getNavigation().stop();
				if (--timer <= 0) {
					startRound(bot, jobs);
				}
			}
		}
	}

	private void startRound(ButterBotEntity bot, JobConfig jobs) {
		step = Step.LOAD;
		roundStart = jobs.sources().isEmpty() ? 0 : (roundStart + 1) % jobs.sources().size();
		visited = 0;
		taken = 0;
		travel.reset(bot);
	}

	private void goUnload(ButterBotEntity bot) {
		step = Step.UNLOAD;
		timer = 0;
		smeltedMark = -1;
		lastSmeltProgress = 0;
		travel.reset(bot);
	}

	/** To the next source chest, and take what fits. */
	private void load(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		List<BlockPos> sources = jobs.sources();
		if (visited >= sources.size() || bot.freeSlots() < MIN_FREE_SLOTS) {
			// Round done (or the backpack is full): unload what it has, or wait for more.
			if (carriesCargo(bot, jobs)) {
				goUnload(bot);
			} else {
				jobs.setPhase(JobConfig.Phase.WAITING);
				step = Step.WAIT;
				timer = IDLE_TICKS;
			}
			return;
		}
		BlockPos pos = sources.get((roundStart + visited) % sources.size());
		BlockPos chest = jobs.chest();
		if (JobStorage.sameStorage(home, pos, chest) || JobStorage.sameStorage(home, chest, pos)) {
			// A chest placed since joined this source and the job chest into one: carrying would go round in circles.
			warn(bot, jobs, Component.translatable("butterbot.msg.job_source_is_chest"));
			visited++;
			return;
		}
		jobs.setPhase(JobConfig.Phase.LOADING);
		bot.setBotState(BotState.MOVING_TO_TARGET);
		JobTravel.Result result = travel.moveTo(bot, home, pos, CHEST_REACH, false);
		if (result == JobTravel.Result.MOVING) {
			return;
		}
		if (result == JobTravel.Result.ARRIVED) {
			Container container = JobStorage.open(home, pos);
			if (container == null) {
				warn(bot, jobs, Component.translatable("butterbot.msg.job_source_missing", pos.toShortString()));
			} else {
				bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
				int keepFree = jobs.smelt() ? FURNACE_ROOM : 0;
				int got = JobStorage.take(bot, container, stack -> bot.freeSlots() > keepFree, Integer.MAX_VALUE);
				if (got > 0) {
					JobStorage.playOpenClose(home, pos);
					taken += got;
				}
			}
		} else {
			// Can't get there (or it is in another dimension with no way known): try the next one.
			warn(bot, jobs, Component.translatable("butterbot.msg.job_source_unreachable", pos.toShortString()));
		}
		visited++;
		travel.reset(bot);
	}

	/** To the job chest: wait while the furnace still gets somewhere, then put everything in. */
	private void unload(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		BlockPos chest = jobs.chest();
		jobs.setPhase(JobConfig.Phase.CARRYING);
		bot.setBotState(BotState.RETURNING);
		JobTravel.Result result = travel.moveTo(bot, home, chest, CHEST_REACH, false);
		if (result == JobTravel.Result.MOVING) {
			return;
		}
		if (result != JobTravel.Result.ARRIVED) {
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_unreachable", chest.toShortString()));
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(chest));
		if (furnaceBusy(bot, jobs)) {
			// The chest should get ingots, not raw ores.
			jobs.setPhase(JobConfig.Phase.SMELTING);
			return;
		}
		Container container = JobStorage.open(home, chest);
		if (container == null) {
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_missing", chest.toShortString()));
			return;
		}
		boolean[] leftOver = {false};
		int moved = deposit(bot, jobs, container, leftOver);
		if (moved > 0) {
			JobStorage.playOpenClose(home, chest);
			jobs.addStat(moved);
			bot.gainWorkXp(Math.max(1, moved / 16));
			bot.say(Component.translatable("butterbot.msg.job_courier_delivered", moved));
		}
		if (leftOver[0]) {
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_full", chest.toShortString()));
			return;
		}
		if (taken == 0 && moved == 0) {
			jobs.setPhase(JobConfig.Phase.WAITING);
			step = Step.WAIT;
			timer = IDLE_TICKS;
			return;
		}
		startRound(bot, jobs);
	}

	/** Smelting is on and the furnace still has ores to do and keeps getting through them (up to a limit). */
	private boolean furnaceBusy(ButterBotEntity bot, JobConfig jobs) {
		BotSmelter smelter = bot.getSmelter();
		if (!jobs.smelt() || timer >= SMELT_WAIT_TICKS || !smelter.canSmeltOres()) {
			return false;
		}
		timer++;
		if (smelter.smeltedCount() != smeltedMark) {
			smeltedMark = smelter.smeltedCount();
			lastSmeltProgress = timer;
		}
		return timer - lastSmeltProgress <= SMELT_STALL_TICKS;
	}

	/**
	 * Puts the cargo into {@code container}: everything except up to {@value #KEEP_FOOD} food and, when
	 * smelting, {@value #KEEP_FUEL} fuel, which the courier keeps for itself.
	 */
	private static int deposit(ButterBotEntity bot, JobConfig jobs, Container container, boolean[] leftOver) {
		SimpleContainer backpack = bot.getInventory();
		int foodLeft = KEEP_FOOD;
		int fuelLeft = jobs.smelt() ? KEEP_FUEL : 0;
		int moved = 0;
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			int keep = 0;
			if (ButterBotEntity.isSafeFood(stack)) {
				keep = Math.min(stack.getCount(), foodLeft);
				foodLeft -= keep;
			} else if (BotSmelter.isFuel(stack)) {
				keep = Math.min(stack.getCount(), fuelLeft);
				fuelLeft -= keep;
			}
			int give = stack.getCount() - keep;
			if (give <= 0) {
				continue;
			}
			ItemStack rest = JobStorage.insert(container, stack.copyWithCount(give));
			int put = give - rest.getCount();
			if (!rest.isEmpty()) {
				leftOver[0] = true;
			}
			if (put > 0) {
				stack.shrink(put);
				backpack.setItem(i, stack);
				moved += put;
			}
		}
		return moved;
	}

	/** Something to bring to the chest (more than the food and fuel it keeps). */
	private static boolean carriesCargo(ButterBotEntity bot, JobConfig jobs) {
		SimpleContainer backpack = bot.getInventory();
		int food = 0;
		int fuel = 0;
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (ButterBotEntity.isSafeFood(stack)) {
				food += stack.getCount();
			} else if (BotSmelter.isFuel(stack)) {
				fuel += stack.getCount();
			} else {
				return true;
			}
		}
		return food > KEEP_FOOD || fuel > (jobs.smelt() ? KEEP_FUEL : 0);
	}

	private void problem(ButterBotEntity bot, JobConfig jobs, Component message) {
		jobs.setPhase(JobConfig.Phase.CHEST_PROBLEM);
		warn(bot, jobs, message);
		step = Step.WAIT;
		timer = PROBLEM_TICKS;
	}

	private static void warn(ButterBotEntity bot, JobConfig jobs, Component message) {
		if (jobs.warnNow(bot.level().getGameTime())) {
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
}
