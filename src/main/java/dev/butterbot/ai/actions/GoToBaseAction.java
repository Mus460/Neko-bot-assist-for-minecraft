package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Walks home to the Bot Base, unloads its backpack and waits there. */
public class GoToBaseAction implements BotAction {

	private static final double ARRIVE_DISTANCE_SQ = 2.5D * 2.5D;
	private static final int MAX_STUCK_TICKS = 20 * 45;

	private final Walker walker = new Walker();

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.GO_TO_BASE && bot.getBasePos() != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.RETURNING);
		walker.reset(bot);
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos base = bot.getBasePos();
		if (base == null) {
			bot.clearTask();
			return;
		}
		if (bot.level().isLoaded(base) && !bot.level().getBlockState(base).is(ModBlocks.BOT_BASE)) {
			bot.tellOwner(Component.translatable("butterbot.msg.base_gone"));
			bot.setBasePos(null);
			bot.clearTask();
			return;
		}

		double distanceSq = bot.distanceToSqr(Vec3.atCenterOf(base));
		if (distanceSq <= ARRIVE_DISTANCE_SQ) {
			bot.getNavigation().stop();
			BotBaseBlockEntity baseEntity = bot.getBase();
			if (baseEntity != null) {
				if (bot.hasLoot()) {
					bot.depositInto(baseEntity);
				}
			}
			bot.say(Component.translatable("butterbot.msg.at_base"));
			bot.playTaskCompleteSound();
			bot.clearTask();
			return;
		}

		// Far away the base isn't loaded yet: head that way over the ground; it loads as the bot gets near.
		if (!walker.walkTo(bot, base, 1.1D, true, MAX_STUCK_TICKS)) {
			// Stuck even digging and building: hop home as a last resort.
			if (bot.lastResortTeleport(base)) {
				walker.reset(bot);
			} else {
				bot.tellOwner(Component.translatable("butterbot.msg.cant_reach_base"));
				bot.clearTask();
			}
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.GO_TO_BASE;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
