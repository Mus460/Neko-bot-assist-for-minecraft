package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Guard mode: fights monsters that come close to the bot or its owner, then goes back to its job.
 * It never attacks players, animals or pets, leaves neutral mobs (endermen, piglins...) alone until
 * they attack, and stays away from bosses and wardens. When badly hurt with no food it backs off.
 */
public class GuardAction implements BotAction {

	private static final double BOT_RADIUS = 10.0D;
	private static final double OWNER_RADIUS = 12.0D;
	private static final double OWNER_LINK_SQ = 24.0D * 24.0D;
	private static final double MAX_CHASE_SQ = 20.0D * 20.0D;
	private static final double MAX_ORDERED_CHASE_SQ = 48.0D * 48.0D;
	private static final int SCAN_INTERVAL = 10;
	private static final int GIVE_UP_TICKS = 200;
	private static final int FLEE_TICKS = 60;
	private static final long IGNORE_TICKS = 20L * 30L;
	/** A patrolling guard chases monsters this far past the edge of its area, no farther. */
	private static final double PATROL_CHASE_MARGIN = 10.0D;

	@Nullable
	private Mob target;
	private int scanCooldown;
	private int attackCooldown;
	private int chaseTicks;
	private int fleeTicks;
	private int repathCooldown;
	private final Map<Integer, Long> ignored = new HashMap<>();

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 92;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		// Something the owner pointed at is attacked even with guard mode off.
		Mob ordered = bot.getOrderedTarget();
		if (ordered != null && bot.distanceToSqr(ordered) < MAX_ORDERED_CHASE_SQ) {
			target = ordered;
			return true;
		}
		if (!bot.isGuarding() || bot.getTask() == BotTask.LUA) {
			target = null;
			return false;
		}
		if (target != null && isValidTarget(bot, target)) {
			return true;
		}
		target = null;
		if (--scanCooldown > 0) {
			return false;
		}
		scanCooldown = SCAN_INTERVAL;
		target = findTarget(bot);
		return target != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.MOVING_TO_TARGET);
		bot.getMiner().cancel();
		bot.speak(ModSounds.VOICE_ATTACK);
		chaseTicks = 0;
		fleeTicks = 0;
		repathCooldown = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		Mob current = target;
		if (current == null || !(bot.level() instanceof ServerLevel level)) {
			return;
		}
		bot.markCombat();
		bot.getLookControl().setLookAt(current, 30.0F, 30.0F);

		boolean weak = bot.getHealth() < bot.getMaxHealth() * 0.3F;
		if (weak && bot.hasSafeFood()) {
			bot.eatFromBackpack();
			weak = false;
		}
		if (weak || fleeTicks > 0) {
			flee(bot, current);
			return;
		}

		if (attackCooldown > 0) {
			attackCooldown--;
		}
		if (--repathCooldown <= 0) {
			repathCooldown = 8;
			bot.getNavigation().moveTo(current, 1.25D);
		}
		if (bot.isWithinMeleeAttackRange(current) && bot.getSensing().hasLineOfSight(current)) {
			if (attackCooldown <= 0) {
				attackCooldown = BotLevels.attackCooldown(bot.getBotLevel());
				bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
				bot.doHurtTarget(level, current);
				chaseTicks = 0;
				if (!current.isAlive()) {
					bot.onDefeated(current);
					target = null;
				}
			}
		} else if (++chaseTicks > GIVE_UP_TICKS) {
			ignore(bot, current);
			target = null;
		}
	}

	/** Runs away from the monster towards the owner for a few seconds, then forgets it for a while. */
	private void flee(ButterBotEntity bot, Mob threat) {
		if (fleeTicks == 0) {
			fleeTicks = FLEE_TICKS;
		}
		if (--fleeTicks <= 0) {
			ignore(bot, threat);
			target = null;
			return;
		}
		Player owner = bot.getOwner();
		Vec3 destination;
		if (owner != null && owner.level() == bot.level() && bot.distanceToSqr(owner) < OWNER_LINK_SQ) {
			destination = owner.position();
		} else {
			Vec3 away = bot.position().subtract(threat.position()).multiply(1.0D, 0.0D, 1.0D);
			if (away.lengthSqr() < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
			}
			destination = bot.position().add(away.normalize().scale(8.0D));
		}
		if (--repathCooldown <= 0) {
			repathCooldown = 10;
			bot.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.35D);
		}
	}

	private void ignore(ButterBotEntity bot, Mob mob) {
		ignored.put(mob.getId(), bot.level().getGameTime() + IGNORE_TICKS);
	}

	private boolean isIgnored(ButterBotEntity bot, Mob mob) {
		long now = bot.level().getGameTime();
		for (Iterator<Map.Entry<Integer, Long>> it = ignored.entrySet().iterator(); it.hasNext(); ) {
			if (it.next().getValue() < now) {
				it.remove();
			}
		}
		return ignored.containsKey(mob.getId());
	}

	private boolean isValidTarget(ButterBotEntity bot, Mob mob) {
		if (mob == bot.getOrderedTarget()) {
			return mob.level() == bot.level() && bot.distanceToSqr(mob) < MAX_ORDERED_CHASE_SQ;
		}
		return mob.isAlive() && !mob.isRemoved() && mob.level() == bot.level()
				&& withinChase(bot, mob) && isHostile(bot, mob) && !isIgnored(bot, mob);
	}

	/** Normally monsters near the bot; a patrolling guard keeps to its area instead. */
	private static boolean withinChase(ButterBotEntity bot, Mob mob) {
		BlockPos center = bot.getPatrolCenter();
		if (center == null) {
			return bot.distanceToSqr(mob) < MAX_CHASE_SQ;
		}
		double limit = bot.getPatrolRadius() + PATROL_CHASE_MARGIN;
		double dx = mob.getX() - (center.getX() + 0.5D);
		double dz = mob.getZ() - (center.getZ() + 0.5D);
		return dx * dx + dz * dz < limit * limit && Math.abs(mob.getY() - center.getY()) < 16.0D;
	}

	@Nullable
	private Mob findTarget(ButterBotEntity bot) {
		Mob best = null;
		double bestDistance = Double.MAX_VALUE;
		AABB around = bot.getBoundingBox().inflate(BOT_RADIUS, 5.0D, BOT_RADIUS);
		for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, around, mob -> isHostile(bot, mob))) {
			double distance = bot.distanceToSqr(mob);
			if (distance < bestDistance && !isIgnored(bot, mob) && bot.getSensing().hasLineOfSight(mob)) {
				best = mob;
				bestDistance = distance;
			}
		}
		BlockPos center = bot.getPatrolCenter();
		if (center != null) {
			// Patrolling: every monster in the area, not just the ones next to the bot.
			double radius = bot.getPatrolRadius();
			AABB area = new AABB(center).inflate(radius, 8.0D, radius);
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, area, mob -> isHostile(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (distance < bestDistance && !isIgnored(bot, mob) && withinChase(bot, mob)
						&& (bot.getSensing().hasLineOfSight(mob) || Math.abs(mob.getY() - center.getY()) < 6.0D)) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		Player owner = bot.getOwner();
		if (owner != null && owner.level() == bot.level() && bot.distanceToSqr(owner) < OWNER_LINK_SQ) {
			AABB aroundOwner = owner.getBoundingBox().inflate(OWNER_RADIUS, 5.0D, OWNER_RADIUS);
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, aroundOwner, mob -> isHostile(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (distance < bestDistance && !isIgnored(bot, mob) && mob.hasLineOfSight(owner)) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/** Monsters only. Neutral mobs and named mobs count only once they go after the bot or its owner. */
	static boolean isHostile(ButterBotEntity bot, Mob mob) {
		if (!mob.isAlive() || mob instanceof ButterBotEntity || mob.isInvulnerable() || !(mob instanceof Enemy)) {
			return false;
		}
		if (mob instanceof Warden || mob instanceof Creaking || mob instanceof EnderDragon || mob instanceof WitherBoss) {
			return false;
		}
		if (mob instanceof TamableAnimal tamable && tamable.isTame()) {
			return false;
		}
		if (mob instanceof NeutralMob || mob instanceof AbstractPiglin || mob.hasCustomName()) {
			return isAttackingUs(bot, mob.getTarget());
		}
		return true;
	}

	private static boolean isAttackingUs(ButterBotEntity bot, @Nullable LivingEntity victim) {
		if (victim == null) {
			return false;
		}
		if (victim == bot) {
			return true;
		}
		if (victim instanceof Player player) {
			return bot.isOwnedBy(player);
		}
		return victim instanceof ButterBotEntity other && other.getOwnerUuid() != null
				&& other.getOwnerUuid().equals(bot.getOwnerUuid());
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return target == null || !isValidTarget(bot, target);
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		fleeTicks = 0;
	}
}
