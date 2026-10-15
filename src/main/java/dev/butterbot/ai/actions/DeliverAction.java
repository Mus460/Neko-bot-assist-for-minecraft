package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Travel;
import dev.butterbot.ai.Walker;
import dev.butterbot.ai.job.JobAction;
import dev.butterbot.ai.job.JobTravel;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.entity.JobConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Brings the backpack to the owner or to the Bot Base storage. Runs when a job is finished
 * ({@code returnRequested}) or when the backpack fills up mid-job, in which case the job resumes
 * after unloading.
 */
public class DeliverAction implements BotAction {

	private static final double OWNER_DISTANCE_SQ = 2.5D * 2.5D;
	private static final double BASE_DISTANCE_SQ_ROOT = 2.8D;
	private static final double BASE_DISTANCE_SQ = BASE_DISTANCE_SQ_ROOT * BASE_DISTANCE_SQ_ROOT;
	private static final int MAX_STUCK_TICKS = 20 * 45;

	private final Walker walker = new Walker();
	private final JobTravel jobTravel = new JobTravel();
	private boolean done;
	private int waitTicks;
	private int siteRecordedFor = Integer.MIN_VALUE;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return bot.isInventoryFull() ? 90 : 80;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		if (!bot.hasLoot()) {
			return false;
		}
		// Farming bots with a base unload there themselves.
		boolean fullMidJob = bot.isInventoryFull()
				&& (bot.getTask() == BotTask.MINE || bot.getTask() == BotTask.TUNNEL
				|| (bot.getTask() == BotTask.FARM && bot.getFarmStorage() == null));
		return bot.isReturnRequested() || fullMidJob;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.RETURNING);
		walker.reset(bot);
		jobTravel.reset(bot);
		done = false;
		waitTicks = 0;
		if (bot.isJobDriven() && !bot.isJobSiteSpent() && bot.getMineFilter() != null && bot.getMineRun() != siteRecordedFor
				&& !bot.getJobs().isInsideZone(bot.level(), bot.getBasePos(), bot.blockPosition())) {
			// Come back to exactly this spot (down in the tunnel) after unloading. Only once per run: a
			// delivery interrupted near the chest (a fight) must not move the spot next to the chest.
			siteRecordedFor = bot.getMineRun();
			bot.getJobs().setSite(bot.blockPosition(), bot.getMineFilter().item(), true);
		}
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (bot.isJobDriven()) {
			deliverToJobChest(bot);
			return;
		}
		BotBaseBlockEntity base = bot.getDeliveryMode() == DeliveryMode.BASE ? bot.getBase() : null;
		if (base != null && !base.isFull()) {
			deliverToBase(bot, base);
			return;
		}
		if (bot.getDeliveryMode() == DeliveryMode.BASE && base == null && bot.getBasePos() != null && waitTicks == 0) {
			bot.say(Component.translatable("butterbot.msg.base_missing_fallback"));
		}
		deliverToOwner(bot);
	}

	/** A job's haul goes to the job chest, however far it is; then the job plans the next trip. */
	private void deliverToJobChest(ButterBotEntity bot) {
		JobConfig jobs = bot.getJobs();
		ServerLevel home = bot.level() instanceof ServerLevel level ? jobs.level(level.getServer()) : null;
		BlockPos chest = jobs.chest();
		if (home == null || chest == null) {
			deliverToOwner(bot);
			return;
		}
		JobTravel.Result result = jobTravel.moveTo(bot, home, chest, BASE_DISTANCE_SQ_ROOT, false);
		if (result == JobTravel.Result.MOVING) {
			return;
		}
		if (result == JobTravel.Result.NO_WAY) {
			// The job chest is in another dimension and the bot knows no portal: bring it to the owner instead.
			deliverToOwner(bot);
			return;
		}
		if (result == JobTravel.Result.ARRIVED) {
			bot.getLookControl().setLookAt(Vec3.atCenterOf(chest));
			JobAction.depositAtChest(bot, home, chest);
		} else {
			bot.tellOwner(Component.translatable("butterbot.msg.job_chest_unreachable", chest.toShortString()));
		}
		done = true;
		bot.clearTask();
	}

	private void deliverToBase(ButterBotEntity bot, BotBaseBlockEntity base) {
		BlockPos pos = base.getBlockPos();
		double distanceSq = bot.distanceToSqr(Vec3.atCenterOf(pos));
		if (distanceSq > BASE_DISTANCE_SQ) {
			if (bot.shortcutTo(pos, ButterBotEntity.SHORTCUT_DISTANCE)) {
				walker.reset(bot);
				return;
			}
			if (!walker.walk(bot, Vec3.atBottomCenterOf(pos), 1.1D, true, MAX_STUCK_TICKS)) {
				// Stuck even digging and building: hop there as a last resort, or bring it to the owner.
				if (!bot.lastResortTeleport(pos)) {
					bot.tellOwner(Component.translatable("butterbot.msg.cant_reach_base"));
					bot.setDeliveryMode(DeliveryMode.OWNER);
				}
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		boolean empty = bot.depositInto(base);
		if (!empty) {
			bot.tellOwner(Component.translatable("butterbot.msg.base_full"));
		}
		complete(bot);
	}

	private void deliverToOwner(ButterBotEntity bot) {
		Player owner = bot.getOwner();
		if (owner == null || owner.isSpectator()) {
			// Owner offline or in another dimension: use the base if there is one instead of waiting forever.
			BotBaseBlockEntity base = bot.getBase();
			if (base != null && !base.isFull()) {
				deliverToBase(bot, base);
				return;
			}
			Player away = bot.level() instanceof ServerLevel level && bot.getOwnerUuid() != null
					? level.getServer().getPlayerList().getPlayer(bot.getOwnerUuid()) : null;
			if (away != null && !away.isSpectator() && away.level() != bot.level() && away.onGround()
					&& away.level() instanceof ServerLevel there && bot.shortcutToLevel(there, away.blockPosition())) {
				return;
			}
			if (away != null && !away.isSpectator() && away.level() != bot.level()
					&& Travel.towards(bot, walker, away.level().dimension(), bot.getLastOwnerPos())) {
				// In another dimension: go after them through the portal.
				return;
			}
			if (waitTicks++ % 600 == 0) {
				bot.say(Component.translatable("butterbot.msg.waiting_for_owner"));
			}
			bot.getNavigation().stop();
			return;
		}

		double distanceSq = bot.distanceToSqr(owner);
		if (distanceSq > OWNER_DISTANCE_SQ) {
			boolean ownerStanding = owner.onGround() || owner.isPassenger() || owner.isInWater();
			if (ownerStanding && bot.shortcutTo(owner.blockPosition(), ButterBotEntity.SHORTCUT_DISTANCE)) {
				walker.reset(bot);
				return;
			}
			if (!walker.walk(bot, owner.position(), 1.15D, ownerStanding, MAX_STUCK_TICKS)) {
				if (!ownerStanding || !bot.lastResortTeleport(owner.blockPosition())) {
					bot.sayStuck();
				}
				walker.reset(bot);
			}
			return;
		}

		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(owner);
		// A farmer keeps its seeds and saplings, or it couldn't sow the field again.
		bot.deliverInventoryTo(owner, bot.getTask() == BotTask.FARM ? ButterBotEntity.KEEP_PLANTABLES : 0);
		complete(bot);
	}

	private void complete(ButterBotEntity bot) {
		done = true;
		if (bot.isReturnRequested() && !bot.hasLoot()) {
			bot.finishDelivery();
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return done || !bot.hasLoot();
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
		if (bot.isReturnRequested() && !bot.hasLoot()) {
			bot.finishDelivery();
		}
	}

	@Nullable
	static Player ownerOf(ButterBotEntity bot) {
		return bot.getOwner();
	}
}
