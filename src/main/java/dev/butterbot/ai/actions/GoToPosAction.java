package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** "Go over there": walks to a spot and waits there (guarding it, if guard mode is on) until the next order. */
public class GoToPosAction implements BotAction {

	private static final double ARRIVED_SQ = 1.5D * 1.5D;
	/** How long a bot with a job waits at the spot before going back to work. */
	private static final int JOB_WAIT_TICKS = 20 * 60;

	private final Walker walker = new Walker();
	private boolean arrived;
	private int waitedTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 50;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.GOTO && bot.getGotoPos() != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		walker.reset(bot);
		arrived = false;
		waitedTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos target = bot.getGotoPos();
		if (target == null) {
			return;
		}
		Vec3 spot = Vec3.atBottomCenterOf(target);
		double distanceSq = bot.distanceToSqr(spot);
		if (distanceSq > ARRIVED_SQ) {
			if (arrived && distanceSq < 4.0D * 4.0D) {
				// Pushed around a little while waiting: no need to make a fuss.
				bot.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0D);
				return;
			}
			if (bot.shortcutTo(target, ButterBotEntity.SHORTCUT_DISTANCE)) {
				walker.reset(bot);
				return;
			}
			if (!walker.walk(bot, spot, 1.15D, true, 400)) {
				// No way there even digging and building: hop there as a last resort, or say so and wait.
				if (bot.lastResortTeleport(target)) {
					walker.reset(bot);
				} else {
					bot.tellOwner(Component.translatable("butterbot.msg.goto_unreachable", target.toShortString()));
					bot.clearTask();
				}
			}
			return;
		}
		bot.getNavigation().stop();
		if (!arrived) {
			arrived = true;
			waitedTicks = 0;
			bot.setBotState(BotState.IDLE);
			bot.say(Component.translatable("butterbot.msg.goto_arrived"));
		}
		if (bot.isJobActive() && ++waitedTicks > JOB_WAIT_TICKS) {
			// A bot with a job only waits a while, then goes back to its job.
			bot.say(Component.translatable("butterbot.msg.goto_back_to_job"));
			bot.clearTask();
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.GOTO;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
