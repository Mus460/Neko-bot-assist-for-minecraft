package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.network.BotAccess;
import dev.butterbot.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * After the owner dies: run to the death spot, scoop up everything that was dropped before it
 * despawns, then bring it back once the owner has respawned (even in another dimension).
 */
public class RecoverItemsAction implements BotAction {

	private static final double SHORTCUT_SQ = 64.0D * 64.0D;
	private static final double ARRIVED_SQ = 6.0D * 6.0D;
	private static final double PICKUP_RADIUS = 10.0D;
	/** Keep sweeping a little after arriving: items may still be flying or bouncing. */
	private static final int SWEEP_TICKS = 60;
	private static final int GIVE_UP_TICKS = 20 * 90;
	private static final double OWNER_TELEPORT_SQ = 24.0D * 24.0D;
	private static final double HAND_OVER_SQ = 2.5D * 2.5D;
	private static final int DIMENSION_FOLLOW_DELAY = 40;

	private final Walker walker = new Walker();
	private int ticks;
	private int sweepTicks;
	private int awayTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		// Dropped items vanish after five minutes, so collecting beats even fighting. Bringing them
		// back can wait for a fight to end.
		return bot.getRecoverPos() != null ? 93 : 88;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getRecoverPos() != null || bot.hasRecoveredItems();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.RETURNING);
		bot.getMiner().cancel();
		walker.reset(bot);
		ticks = 0;
		sweepTicks = 0;
		awayTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos spot = bot.getRecoverPos();
		if (spot != null) {
			collect(bot, level, spot);
		} else {
			bringBack(bot, level);
		}
	}

	private void collect(ButterBotEntity bot, ServerLevel level, BlockPos spot) {
		Vec3 center = Vec3.atBottomCenterOf(spot);
		double distanceSq = bot.distanceToSqr(center);
		if (++ticks > GIVE_UP_TICKS) {
			bot.finishCollecting();
			return;
		}
		if (distanceSq > SHORTCUT_SQ) {
			if (!bot.teleportNear(spot)) {
				bot.finishCollecting();
			}
			walker.reset(bot);
			return;
		}
		if (distanceSq < ARRIVED_SQ * 4.0D) {
			sweep(bot, level, center);
		}
		if (distanceSq > ARRIVED_SQ) {
			if (!walker.walk(bot, center, 1.3D, true, 200)) {
				bot.teleportNear(spot);
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		if (++sweepTicks >= SWEEP_TICKS) {
			bot.finishCollecting();
		}
	}

	/** Pulls in every dropped item and experience orb around the death spot. */
	private static void sweep(ButterBotEntity bot, ServerLevel level, Vec3 center) {
		AABB area = new AABB(center, center).inflate(PICKUP_RADIUS, 6.0D, PICKUP_RADIUS);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area, ItemEntity::isAlive);
		for (ItemEntity item : items) {
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, item.getX(), item.getY() + 0.2D, item.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.0D);
			bot.addRecovered(item.getItem().copy());
			item.discard();
		}
		if (!items.isEmpty()) {
			level.playSound(null, bot.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 0.9F);
		}
		bot.absorbExperience(center, PICKUP_RADIUS);
	}

	private void bringBack(ButterBotEntity bot, ServerLevel level) {
		Player owner = bot.getOwner();
		if (owner == null) {
			followAcrossDimensions(bot, level);
			bot.getNavigation().stop();
			return;
		}
		awayTicks = 0;
		if (!owner.isAlive() || owner.isSpectator()) {
			bot.getNavigation().stop();
			return;
		}
		double distanceSq = bot.distanceToSqr(owner);
		if (distanceSq > OWNER_TELEPORT_SQ && (owner.onGround() || owner.isPassenger())) {
			bot.teleportNear(owner.blockPosition());
			walker.reset(bot);
			return;
		}
		if (distanceSq > HAND_OVER_SQ) {
			if (!walker.walk(bot, owner.position(), 1.25D, true, 200)) {
				bot.teleportNear(owner.blockPosition());
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(owner);
		bot.returnRecoveredTo(owner);
	}

	/** The owner respawned in another dimension (for example died in the Nether, woke up at home). */
	private void followAcrossDimensions(ButterBotEntity bot, ServerLevel level) {
		UUID ownerId = bot.getOwnerUuid();
		if (ownerId == null || ++awayTicks < DIMENSION_FOLLOW_DELAY) {
			return;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
		if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.level() == level || !owner.onGround()) {
			return;
		}
		awayTicks = 0;
		bot.followToLevel(owner.level(), owner.blockPosition());
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getRecoverPos() == null && !bot.hasRecoveredItems();
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}

	/** Called when a player dies: the nearest of their bots runs to keep their things safe. */
	public static void onOwnerDeath(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos spot = player.blockPosition().immutable();
		String where = spot.getX() + " " + spot.getY() + " " + spot.getZ();
		ButterBotEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (ButterBotEntity bot : level.getEntitiesOfClass(ButterBotEntity.class, player.getBoundingBox().inflate(BotAccess.DIRECT_RANGE),
				bot -> bot.isAlive() && bot.isOwnedBy(player))) {
			double distance = bot.distanceToSqr(player);
			if (distance < best) {
				best = distance;
				nearest = bot;
			}
		}
		if (nearest == null) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.death_no_bot", where)));
			return;
		}
		nearest.startRecovery(spot);
		player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.death_recovering", nearest.getName(), where)));
	}
}
