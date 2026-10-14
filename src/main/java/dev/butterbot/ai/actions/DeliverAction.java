package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.DeliveryMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
	private static final double BASE_DISTANCE_SQ = 2.8D * 2.8D;
	private static final int MAX_STUCK_TICKS = 20 * 45;
	/** Farther than this the bot takes a shortcut instead of walking (it could leave the loaded area). */
	private static final double SHORTCUT_DISTANCE_SQ = 64.0D * 64.0D;

	private final Walker walker = new Walker();
	private boolean done;
	private int waitTicks;

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
				|| (bot.getTask() == BotTask.FARM && bot.getBase() == null));
		return bot.isReturnRequested() || fullMidJob;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.RETURNING);
		walker.reset(bot);
		done = false;
		waitTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
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

	private void deliverToBase(ButterBotEntity bot, BotBaseBlockEntity base) {
		BlockPos pos = base.getBlockPos();
		double distanceSq = bot.distanceToSqr(Vec3.atCenterOf(pos));
		if (distanceSq > SHORTCUT_DISTANCE_SQ && bot.teleportNear(pos)) {
			walker.reset(bot);
			return;
		}
		if (distanceSq > BASE_DISTANCE_SQ) {
			if (!walker.walk(bot, Vec3.atBottomCenterOf(pos), 1.1D, true, MAX_STUCK_TICKS)) {
				if (!bot.teleportNear(pos)) {
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
			if (waitTicks++ % 600 == 0) {
				bot.say(Component.translatable("butterbot.msg.waiting_for_owner"));
			}
			bot.getNavigation().stop();
			return;
		}

		double distanceSq = bot.distanceToSqr(owner);
		if (distanceSq > SHORTCUT_DISTANCE_SQ && (owner.onGround() || owner.isPassenger()) && bot.teleportNear(owner.blockPosition())) {
			walker.reset(bot);
			return;
		}
		if (distanceSq > OWNER_DISTANCE_SQ) {
			if (!walker.walk(bot, owner.position(), 1.15D, true, MAX_STUCK_TICKS)) {
				bot.teleportNear(owner.blockPosition());
				walker.reset(bot);
			}
			return;
		}

		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(owner);
		bot.deliverInventoryTo(owner);
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
