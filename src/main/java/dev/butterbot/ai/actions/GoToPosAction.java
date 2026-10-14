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

	private static final double SHORTCUT_SQ = 64.0D * 64.0D;
	private static final double ARRIVED_SQ = 1.5D * 1.5D;

	private final Walker walker = new Walker();
	private boolean arrived;

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
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos target = bot.getGotoPos();
		if (target == null) {
			return;
		}
		Vec3 spot = Vec3.atBottomCenterOf(target);
		double distanceSq = bot.distanceToSqr(spot);
		if (distanceSq > SHORTCUT_SQ && bot.teleportNear(target)) {
			walker.reset(bot);
			return;
		}
		if (distanceSq > ARRIVED_SQ) {
			if (arrived && distanceSq < 4.0D * 4.0D) {
				// Pushed around a little while waiting: no need to make a fuss.
				bot.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0D);
				return;
			}
			if (!walker.walk(bot, spot, 1.15D, true, 400)) {
				bot.teleportNear(target);
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		if (!arrived) {
			arrived = true;
			bot.setBotState(BotState.IDLE);
			bot.say(Component.translatable("butterbot.msg.goto_arrived"));
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
