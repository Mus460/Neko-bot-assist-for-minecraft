package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Follows the owner like a pet: teleports next to them when left far behind, digs or builds its way
 * out when stuck (and teleports if that doesn't help), and follows them into other dimensions.
 */
public class FollowOwnerAction implements BotAction {

	private static final double START_FOLLOW_SQ = 4.0D * 4.0D;
	private static final double STOP_FOLLOW_SQ = 2.5D * 2.5D;
	private static final double TELEPORT_SQ = 24.0D * 24.0D;
	private static final int UNSTICK_AFTER_TICKS = 30;
	private static final int UNSTICK_INTERVAL = 10;
	private static final int TELEPORT_AFTER_STUCK_TICKS = 160;
	/** How long the owner must have been in another dimension before the bot follows. */
	private static final int DIMENSION_FOLLOW_DELAY = 40;

	private int repathCooldown;
	private int stuckTicks;
	private int awayTicks;
	@Nullable
	private Vec3 lastPosition;

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
		repathCooldown = 0;
		stuckTicks = 0;
		awayTicks = 0;
		lastPosition = bot.position();
	}

	@Override
	public void tick(ButterBotEntity bot) {
		Player owner = bot.getOwner();
		if (owner == null) {
			followAcrossDimensions(bot);
			bot.getNavigation().stop();
			return;
		}
		awayTicks = 0;
		if (owner.isSpectator()) {
			bot.getNavigation().stop();
			return;
		}
		if (bot.getMiner().isDigging()) {
			bot.getMiner().tickDig();
			return;
		}

		bot.getLookControl().setLookAt(owner, 10.0F, bot.getMaxHeadXRot());
		double distanceSq = bot.distanceToSqr(owner);
		boolean ownerStanding = owner.onGround() || owner.isPassenger();

		if (distanceSq > TELEPORT_SQ && ownerStanding) {
			bot.teleportNear(owner.blockPosition());
			stuckTicks = 0;
			return;
		}

		if (distanceSq < STOP_FOLLOW_SQ) {
			bot.getNavigation().stop();
			stuckTicks = 0;
			lastPosition = bot.position();
			return;
		}

		if (distanceSq > START_FOLLOW_SQ && --repathCooldown <= 0) {
			repathCooldown = 10;
			bot.getNavigation().moveTo(owner, distanceSq > 100 ? 1.3D : 1.1D);
		}
		trackStuck(bot, owner, distanceSq, ownerStanding);
	}

	/** Not moving while the owner is out of reach: dig or build towards them, then teleport as a last resort. */
	private void trackStuck(ButterBotEntity bot, Player owner, double distanceSq, boolean ownerStanding) {
		if (distanceSq <= START_FOLLOW_SQ) {
			stuckTicks = 0;
			lastPosition = bot.position();
			return;
		}
		if (lastPosition != null && bot.position().distanceToSqr(lastPosition) < 0.0025D) {
			stuckTicks++;
		} else {
			stuckTicks = Math.max(0, stuckTicks - 2);
		}
		lastPosition = bot.position();

		if (ownerStanding && stuckTicks >= UNSTICK_AFTER_TICKS && stuckTicks % UNSTICK_INTERVAL == 0) {
			TunnelHelper.digStepTowards(bot, owner.blockPosition());
		}
		if (ownerStanding && stuckTicks > TELEPORT_AFTER_STUCK_TICKS) {
			bot.teleportNear(owner.blockPosition());
			stuckTicks = 0;
		}
	}

	/** The owner went through a portal: wait a moment, then follow them. */
	private void followAcrossDimensions(ButterBotEntity bot) {
		UUID ownerId = bot.getOwnerUuid();
		if (ownerId == null || !(bot.level() instanceof ServerLevel level) || ++awayTicks < DIMENSION_FOLLOW_DELAY) {
			return;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || owner.isSpectator() || owner.level() == bot.level() || !owner.onGround()) {
			return;
		}
		awayTicks = 0;
		bot.followToLevel(owner.level(), owner.blockPosition());
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
