package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Travel;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Follows the owner like a pet, on foot: runs to catch up when left behind, digs or builds its way
 * through when stuck (only towards an owner standing somewhere), and follows them through the portal
 * they took into another dimension. Only when that all fails does it hop to them as a last resort.
 */
public class FollowOwnerAction implements BotAction {

	private static final double START_FOLLOW_SQ = 4.0D * 4.0D;
	private static final double STOP_FOLLOW_SQ = 2.5D * 2.5D;
	private static final double RUN_SQ = 10.0D * 10.0D;
	private static final int MAX_STUCK_TICKS = 20 * 20;
	/** With no portal it knows, it waits this long (the owner may come back) before hopping after them. */
	private static final int NO_PORTAL_WAIT_TICKS = 20 * 10;

	private final Walker walker = new Walker();
	private boolean toldNoWay;
	private int awayTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 30;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.FOLLOW;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		walker.reset(bot);
		toldNoWay = false;
		awayTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		Player owner = bot.getOwner();
		if (owner == null) {
			followAcrossDimensions(bot);
			return;
		}
		toldNoWay = false;
		awayTicks = 0;
		if (owner.isSpectator()) {
			bot.getNavigation().stop();
			return;
		}
		bot.getLookControl().setLookAt(owner, 10.0F, bot.getMaxHeadXRot());
		double distanceSq = bot.distanceToSqr(owner);
		if (distanceSq < STOP_FOLLOW_SQ && !bot.getMiner().isDigging()) {
			bot.getNavigation().stop();
			walker.reset(bot);
			return;
		}
		if (distanceSq <= START_FOLLOW_SQ && bot.getNavigation().isDone() && !bot.getMiner().isDigging()) {
			return;
		}
		// Dig or build towards an owner standing somewhere, never up into the air after one who is flying.
		boolean ownerStanding = owner.onGround() || owner.isPassenger() || owner.isInWater();
		if (!walker.walk(bot, owner.position(), distanceSq > RUN_SQ ? 1.3D : 1.1D, ownerStanding, MAX_STUCK_TICKS)) {
			walker.reset(bot);
			// Stuck for a good while even digging and building: hop to them as a last resort.
			if (!ownerStanding || !bot.lastResortTeleport(owner.blockPosition())) {
				bot.sayStuck();
			}
		}
	}

	/** The owner went to another dimension: walk into the portal they took (or one the bot knows). */
	private void followAcrossDimensions(ButterBotEntity bot) {
		UUID ownerId = bot.getOwnerUuid();
		if (ownerId == null || !(bot.level() instanceof ServerLevel level)) {
			bot.getNavigation().stop();
			return;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.isSpectator() || owner.level() == bot.level()) {
			bot.getNavigation().stop();
			return;
		}
		if (Travel.towards(bot, walker, owner.level().dimension(), bot.getLastOwnerPos())) {
			awayTicks = 0;
			return;
		}
		bot.getNavigation().stop();
		if (!toldNoWay) {
			toldNoWay = true;
			bot.tellOwner(Component.translatable("butterbot.msg.follow_no_way", Travel.dimensionName(owner.level().dimension()))
					.withStyle(ChatFormatting.GOLD));
		}
		// No portal leads there (they died and respawned, used a command...): hop after them as a last resort.
		if (++awayTicks > NO_PORTAL_WAIT_TICKS && owner.onGround() && bot.lastResortToLevel(owner.level(), owner.blockPosition())) {
			awayTicks = 0;
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.FOLLOW;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
