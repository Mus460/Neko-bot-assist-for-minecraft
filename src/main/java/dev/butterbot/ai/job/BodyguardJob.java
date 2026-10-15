package dev.butterbot.ai.job;

import dev.butterbot.ai.Travel;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Bodyguard: stays close to another of the owner's bots wherever it goes (through portals too) and
 * fights the monsters around it (see GuardAction). Like following the owner, it walks; only when it
 * is stuck for a long while, or the other bot left for a dimension it knows no way to, does it hop
 * over as a last resort.
 */
final class BodyguardJob {

	private static final double START_SQ = 5.0D * 5.0D;
	private static final double STOP_SQ = 3.0D * 3.0D;
	private static final double RUN_SQ = 12.0D * 12.0D;
	private static final int MAX_STUCK_TICKS = 20 * 20;
	/** With no portal it knows, it waits this long (the other bot may come back) before hopping after it. */
	private static final int NO_PORTAL_WAIT_TICKS = 20 * 10;
	private static final long REMIND_TICKS = 20L * 60L * 3L;

	/** A ward that can't be found for this long (it may only be in a chunk that isn't loaded yet) counts as gone. */
	private static final int MISSING_GRACE_TICKS = 20 * 10;

	private final Walker walker = new Walker();
	private int awayTicks;
	private boolean toldNoWay;
	private int missingTicks;
	private long lastReminder = JobConfig.NEVER;

	/** The job (or the bot to guard) changed. */
	void reset(ButterBotEntity bot) {
		walker.reset(bot);
		awayTicks = 0;
		toldNoWay = false;
		missingTicks = 0;
	}

	/** Back after a fight or an order: what it already told the owner and how long it waited still count. */
	void resume(ButterBotEntity bot) {
		walker.reset(bot);
	}

	void tick(ButterBotEntity bot, JobConfig jobs) {
		ButterBotEntity ward = bot.findWard();
		if (ward == null) {
			bot.setBotState(BotState.IDLE);
			bot.getNavigation().stop();
			if (jobs.ward() == null) {
				jobs.setPhase(JobConfig.Phase.SETUP);
				remind(bot, jobs, "butterbot.msg.job_needs_ward");
			} else if (++missingTicks > MISSING_GRACE_TICKS) {
				jobs.setPhase(JobConfig.Phase.NO_WARD);
				remind(bot, jobs, "butterbot.msg.job_ward_missing");
			}
			return;
		}
		missingTicks = 0;
		jobs.setPhase(JobConfig.Phase.ESCORTING);
		if (ward.level() != bot.level()) {
			followAcross(bot, ward);
			return;
		}
		awayTicks = 0;
		toldNoWay = false;
		bot.getLookControl().setLookAt(ward, 10.0F, bot.getMaxHeadXRot());
		double distanceSq = bot.distanceToSqr(ward);
		if (distanceSq < STOP_SQ && !bot.getMiner().isDigging()) {
			bot.getNavigation().stop();
			walker.reset(bot);
			return;
		}
		if (distanceSq <= START_SQ && bot.getNavigation().isDone() && !bot.getMiner().isDigging()) {
			return;
		}
		bot.setBotState(BotState.MOVING_TO_TARGET);
		// Digs or builds its way only towards a bot standing somewhere (down its tunnel, say), and never
		// into the pen or field of a bot that farms, ranches or guards an area.
		boolean wardStanding = ward.onGround() || ward.isInWater();
		if (wardStanding && bot.shortcutTo(ward.blockPosition(), ButterBotEntity.FOLLOW_SHORTCUT_DISTANCE)) {
			// Teleport mode "far": left behind, it catches up at once.
			walker.reset(bot);
			return;
		}
		boolean dig = wardStanding && !ward.getJobs().job().usesArea();
		if (!walker.walk(bot, ward.position(), distanceSq > RUN_SQ ? 1.3D : 1.1D, dig, MAX_STUCK_TICKS)) {
			walker.reset(bot);
			// Stuck for a good while even digging and building: hop to it as a last resort.
			if (!wardStanding || !bot.lastResortTeleport(ward.blockPosition())) {
				bot.sayStuck();
			}
		}
	}

	/** The other bot went to another dimension: walk into the portal it took (or one this bot knows). */
	private void followAcross(ButterBotEntity bot, ButterBotEntity ward) {
		if (ward.onGround() && ward.level() instanceof ServerLevel there && bot.shortcutToLevel(there, ward.blockPosition())) {
			awayTicks = 0;
			return;
		}
		if (Travel.towards(bot, walker, ward.level().dimension(), bot.getLastWardPos())) {
			awayTicks = 0;
			return;
		}
		bot.getNavigation().stop();
		if (!toldNoWay) {
			toldNoWay = true;
			bot.tellOwner(Component.translatable("butterbot.msg.job_ward_no_way", ward.getName(),
					Travel.dimensionName(ward.level().dimension())).withStyle(ChatFormatting.GOLD));
		}
		// No portal leads there: hop after it as a last resort (it is the bot's job to be there).
		if (++awayTicks > NO_PORTAL_WAIT_TICKS && ward.onGround() && ward.level() instanceof ServerLevel wardLevel
				&& bot.lastResortToLevel(wardLevel, ward.blockPosition())) {
			awayTicks = 0;
		}
	}

	private void remind(ButterBotEntity bot, JobConfig jobs, String messageKey) {
		long now = bot.level().getGameTime();
		if (now - lastReminder >= REMIND_TICKS) {
			lastReminder = now;
			bot.tellOwner(Component.translatable(messageKey, jobs.job().displayName()).withStyle(ChatFormatting.GOLD));
		}
	}
}
