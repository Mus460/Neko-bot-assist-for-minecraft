package dev.butterbot.ai.job;

import dev.butterbot.ai.BotSmelter;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.entity.SmeltMode;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Courier: goes round its source chests, takes everything in them and carries it to the job chest,
 * again and again. With smelting on it takes fuel along and its built-in furnace smelts raw ores on
 * the way; at the chest it waits until they are done, so the chest gets ingots.
 */
final class CourierJob {

	private static final double CHEST_REACH = 2.8D;
	/** After a round that found nothing, look again this much later. */
	private static final int IDLE_TICKS = 20 * 30;
	private static final int PROBLEM_TICKS = 20 * 30;
	/** Longest wait by the chest for the furnace to finish. */
	private static final int SMELT_WAIT_TICKS = 20 * 90;
	/** Fuel (per item) and food the courier keeps for itself instead of storing them. */
	private static final int KEEP_FUEL = 32;
	private static final int KEEP_FOOD = 16;
	/** Free backpack slots needed to go on to the next source rather than unload first. */
	private static final int MIN_FREE_SLOTS = 3;
	private static final long REMIND_TICKS = 20L * 60L * 3L;

	private enum Step {LOAD, UNLOAD, WAIT}

	private final JobTravel travel = new JobTravel();
	private Step step = Step.LOAD;
	/** The source the courier is heading for in this round. */
	private int source;
	/** Items taken in this round (a round with nothing to take ends in a wait). */
	private int taken;
	private int timer;
	private long lastReminder = JobConfig.NEVER;

	void reset(ButterBotEntity bot) {
		travel.reset(bot);
		step = Step.LOAD;
		source = 0;
		taken = 0;
		timer = 0;
	}

	void tick(ButterBotEntity bot, JobConfig jobs, @Nullable ServerLevel home) {
		if (home == null || jobs.chest() == null || jobs.sources().isEmpty()) {
			needsSetup(bot, jobs, jobs.chest() == null ? "butterbot.msg.job_needs_chest" : "butterbot.msg.job_needs_sources");
			return;
		}
		switch (step) {
			case LOAD -> load(bot, jobs, home);
			case UNLOAD -> unload(bot, jobs, home);
			case WAIT -> {
				bot.getNavigation().stop();
				if (--timer <= 0) {
					startRound(bot);
				}
			}
		}
	}

	private void startRound(ButterBotEntity bot) {
		step = Step.LOAD;
		source = 0;
		taken = 0;
		travel.reset(bot);
	}

	/** To the next source chest, and take everything that fits. */
	private void load(ButterBotEntity bot, JobConfig jobs, ServerLevel home) {
		List<BlockPos> sources = jobs.sources();
		if (source >= sources.size() || bot.freeSlots() < MIN_FREE_SLOTS) {
			// Round done (or the backpack is full): unload what it has, or wait for more.
			if (carriesCargo(bot, jobs)) {
				step = Step.UNLOAD;
				timer = 0;
				travel.reset(bot);
			} else {
				jobs.setPhase(JobConfig.Phase.WAITING);
				step = Step.WAIT;
				timer = IDLE_TICKS;
			}
			return;
		}
		BlockPos pos = sources.get(source);
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
				int got = JobStorage.take(bot, container, stack -> true, Integer.MAX_VALUE);
				if (got > 0) {
					JobStorage.playOpenClose(home, pos);
					taken += got;
				}
			}
		} else {
			// Can't get there (or it is in another dimension with no way known): try the next one.
			warn(bot, jobs, Component.translatable("butterbot.msg.job_source_unreachable", pos.toShortString()));
		}
		source++;
		travel.reset(bot);
	}

	/** To the job chest: wait for the furnace if it is busy, then put everything in. */
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
		if (jobs.smelt() && timer < SMELT_WAIT_TICKS && bot.getSmelter().canSmelt(SmeltMode.ORES)) {
			// The furnace is still going: the chest should get ingots, not raw ores.
			jobs.setPhase(JobConfig.Phase.SMELTING);
			timer++;
			return;
		}
		Container container = JobStorage.open(home, chest);
		if (container == null) {
			problem(bot, jobs, Component.translatable("butterbot.msg.job_chest_missing", chest.toShortString()));
			return;
		}
		boolean[] leftOver = {false};
		int moved = JobStorage.deposit(bot, container, keepFor(bot, jobs), leftOver);
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
		startRound(bot);
	}

	/** What goes in the chest: everything but some food, and with smelting on some fuel for the next trips. */
	private static java.util.function.Predicate<ItemStack> keepFor(ButterBotEntity bot, JobConfig jobs) {
		Map<Item, Integer> kept = new HashMap<>();
		return stack -> {
			int keep = ButterBotEntity.isSafeFood(stack) ? KEEP_FOOD : jobs.smelt() && BotSmelter.isFuel(stack) ? KEEP_FUEL : 0;
			if (keep == 0) {
				return true;
			}
			int already = kept.getOrDefault(stack.getItem(), 0);
			if (already + stack.getCount() <= keep) {
				kept.put(stack.getItem(), already + stack.getCount());
				return false;
			}
			return true;
		};
	}

	/** Something to bring to the chest (not just the food and fuel it keeps). */
	private static boolean carriesCargo(ButterBotEntity bot, JobConfig jobs) {
		SimpleContainer backpack = bot.getInventory();
		java.util.function.Predicate<ItemStack> stored = keepFor(bot, jobs);
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			if (!stack.isEmpty() && stored.test(stack)) {
				return true;
			}
		}
		return false;
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
