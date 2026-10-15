package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.StorageFinder;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * "Bring me 32 iron ingots": takes what the bot already carries, then visits the Bot Base and the
 * chests, barrels and shulker boxes around it (and around the owner) until it has enough, and walks
 * the items over to the owner.
 */
public class FetchTaskAction implements BotAction {

	private static final double REACH_SQ = 2.6D * 2.6D;
	private static final double SHORTCUT_SQ = 64.0D * 64.0D;
	private static final double HAND_OVER_SQ = 2.5D * 2.5D;
	private static final int LOOK_TICKS = 6;
	private static final int OWNER_WAIT_TICKS = 20 * 20;

	private final Walker walker = new Walker();
	private final List<BlockPos> toVisit = new ArrayList<>();
	private boolean planned;
	private boolean returning;
	private int lookTicks;
	private int waitTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 56;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.FETCH && bot.getFetchItem() != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		walker.reset(bot);
		toVisit.clear();
		planned = false;
		returning = false;
		lookTicks = 0;
		waitTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		Item item = bot.getFetchItem();
		if (item == null || !(bot.level() instanceof ServerLevel level)) {
			return;
		}
		Predicate<ItemStack> match = stack -> stack.is(item);
		if (!planned) {
			planned = true;
			bot.moveToFetchCarry(item, missing(bot));
			if (missing(bot) > 0) {
				plan(bot, level, match);
			}
			return;
		}
		if (!returning && (missing(bot) <= 0 || toVisit.isEmpty())) {
			returning = true;
			walker.reset(bot);
		}
		if (returning) {
			bringToOwner(bot, item);
		} else {
			visitNext(bot, level, match);
		}
	}

	private static int missing(ButterBotEntity bot) {
		return bot.getFetchCount() - bot.getFetchCarried();
	}

	private void plan(ButterBotEntity bot, ServerLevel level, Predicate<ItemStack> match) {
		List<BlockPos> centers = new ArrayList<>();
		if (bot.getBase() != null && bot.getBasePos() != null) {
			centers.add(bot.getBasePos());
		}
		Player owner = bot.getOwner();
		if (owner != null) {
			centers.add(owner.blockPosition());
		}
		centers.add(bot.blockPosition());
		Vec3 from = bot.position();
		StorageFinder.find(level, centers, match).stream()
				.map(StorageFinder.Found::pos)
				.sorted(Comparator.comparingDouble(pos -> from.distanceToSqr(Vec3.atCenterOf(pos))))
				.forEach(toVisit::add);
		if (!toVisit.isEmpty()) {
			bot.setBotState(BotState.MOVING_TO_TARGET);
		}
	}

	private void visitNext(ButterBotEntity bot, ServerLevel level, Predicate<ItemStack> match) {
		BlockPos current = toVisit.getFirst();
		Container container = StorageFinder.containerAt(level, current);
		if (container == null) {
			toVisit.removeFirst();
			return;
		}
		double distanceSq = bot.distanceToSqr(Vec3.atCenterOf(current));
		if (distanceSq > REACH_SQ) {
			if (!walker.walk(bot, Vec3.atBottomCenterOf(current), 1.15D, true, 400)) {
				// Can't get to that chest even digging and building: hop there, or try the next one.
				if (!bot.lastResortTeleport(current)) {
					toVisit.removeFirst();
				}
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(current));
		if (lookTicks == 0) {
			level.playSound(null, current, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
			bot.showTool(ItemStack.EMPTY, 0);
		}
		if (++lookTicks < LOOK_TICKS) {
			return;
		}
		lookTicks = 0;
		List<ItemStack> taken = new ArrayList<>();
		StorageFinder.take(container, match, missing(bot), taken);
		taken.forEach(bot::addFetchCarry);
		level.playSound(null, current, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
		toVisit.removeFirst();
		walker.reset(bot);
	}

	private void bringToOwner(ButterBotEntity bot, Item item) {
		Component name = new ItemStack(item).getHoverName();
		int carried = bot.getFetchCarried();
		if (carried == 0) {
			bot.tellOwner(Component.translatable("butterbot.msg.fetch_none", name, StorageFinder.RADIUS));
			bot.clearTask();
			return;
		}
		Player owner = bot.getOwner();
		if (owner == null || !owner.isAlive() || owner.isSpectator()) {
			bot.getNavigation().stop();
			if (++waitTicks > OWNER_WAIT_TICKS) {
				// Owner gone: keep the items in the backpack instead of waiting forever.
				bot.say(Component.translatable("butterbot.msg.fetch_kept", name));
				bot.clearTask();
			}
			return;
		}
		waitTicks = 0;
		double distanceSq = bot.distanceToSqr(owner);
		if (distanceSq > HAND_OVER_SQ) {
			boolean ownerStanding = owner.onGround() || owner.isPassenger() || owner.isInWater();
			if (!walker.walk(bot, owner.position(), 1.2D, ownerStanding, 400)) {
				if (!ownerStanding || !bot.lastResortTeleport(owner.blockPosition())) {
					bot.sayStuck();
				}
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(owner);
		int wanted = bot.getFetchCount();
		bot.handOverFetch(owner);
		bot.tellOwner(carried >= wanted
				? Component.translatable("butterbot.msg.fetch_done", carried, name)
				: Component.translatable("butterbot.msg.fetch_partial", carried, wanted, name));
		bot.gainWorkXp(1);
		bot.playTaskCompleteSound();
		bot.clearTask();
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.FETCH;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
