package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
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
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Guard mode: fights monsters that come close to the bot or its owner (or the bot it guards, for a
 * bodyguard), then goes back to its job. Wardens, the Wither and the Ender Dragon too, and any creature
 * (a wolf, a golem, an angry enderman...) that goes after the owner or its bots. Targets out of reach (a
 * flying dragon, phantoms, ghasts) get bolts of light; against the dragon it first shoots the end crystals
 * that heal it. It never attacks players, villagers or the owner's pets, and leaves neutral mobs alone until
 * they attack. When badly hurt with no food it backs off.
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
	/** A bodyguard fights monsters this close to the bot it guards, and chases them this far from it. */
	private static final double WARD_RADIUS = 12.0D;
	private static final double WARD_CHASE_SQ = 20.0D * 20.0D;
	/** ...but only while it is this close to that bot itself (otherwise it catches up first). */
	private static final double WARD_LINK_SQ = 20.0D * 20.0D;
	/** Bosses (the Ender Dragon, the Wither) are fought from this far away. */
	private static final double BOSS_RANGE = 64.0D;
	/** Bolts of light reach this far (bosses: {@link #BOSS_RANGE}). */
	private static final double SHOOT_RANGE_SQ = 32.0D * 32.0D;
	/** Ticks of chasing without reaching the target before it shoots instead. */
	private static final int SHOOT_AFTER_TICKS = 40;
	/** End crystals this close to the bot are shot first in a dragon fight. */
	private static final double CRYSTAL_RANGE = 64.0D;

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
				attackCooldown = bot.stats().attackCooldown();
				bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
				bot.doHurtTarget(level, current);
				chaseTicks = 0;
				if (!current.isAlive()) {
					bot.onDefeated(current);
					target = null;
				}
			}
			return;
		}
		chaseTicks++;
		if (canShoot(bot, current)) {
			if (attackCooldown <= 0) {
				attackCooldown = Math.max(10, bot.stats().attackCooldown() * 2);
				// The dragon heals from its crystals: those go first.
				EndCrystal crystal = current instanceof EnderDragon ? nearestCrystal(bot, level) : null;
				bot.rangedStrike(level, crystal != null ? crystal : current);
				chaseTicks = SHOOT_AFTER_TICKS;
				if (!current.isAlive()) {
					bot.onDefeated(current);
					target = null;
				}
			}
		} else if (chaseTicks > GIVE_UP_TICKS) {
			ignore(bot, current);
			target = null;
		}
	}

	/**
	 * Shoots what it can't reach: bosses always, flying or high-up targets at once, anything else once it has
	 * chased for a couple of seconds without getting there.
	 */
	private boolean canShoot(ButterBotEntity bot, Mob mob) {
		double range = isBoss(mob) ? BOSS_RANGE * BOSS_RANGE : SHOOT_RANGE_SQ;
		if (bot.distanceToSqr(mob) > range || !bot.getSensing().hasLineOfSight(mob)) {
			return false;
		}
		return isBoss(mob) || !mob.onGround() || mob.getY() - bot.getY() > 2.0D || chaseTicks > SHOOT_AFTER_TICKS;
	}

	@Nullable
	private static EndCrystal nearestCrystal(ButterBotEntity bot, ServerLevel level) {
		EndCrystal best = null;
		double bestDistance = Double.MAX_VALUE;
		for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, bot.getBoundingBox().inflate(CRYSTAL_RANGE))) {
			double distance = bot.distanceToSqr(crystal);
			if (crystal.isAlive() && distance < bestDistance && bot.hasLineOfSight(crystal)) {
				best = crystal;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static boolean isBoss(Mob mob) {
		return mob instanceof EnderDragon || mob instanceof WitherBoss;
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

	/** Normally monsters near the bot; a patrolling guard keeps to its area, a bodyguard to the bot it guards. Bosses from far. */
	private static boolean withinChase(ButterBotEntity bot, Mob mob) {
		if (isBoss(mob)) {
			return bot.distanceToSqr(mob) < BOSS_RANGE * BOSS_RANGE;
		}
		ButterBotEntity ward = nearWard(bot);
		if (ward != null && mob.distanceToSqr(ward) < WARD_CHASE_SQ) {
			return true;
		}
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
		for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, around, mob -> isValidTarget(bot, mob))) {
			double distance = bot.distanceToSqr(mob);
			if (distance < bestDistance && bot.getSensing().hasLineOfSight(mob)) {
				best = mob;
				bestDistance = distance;
			}
		}
		BlockPos center = bot.getPatrolCenter();
		if (center != null) {
			// Patrolling: every monster in the area, not just the ones next to the bot.
			double radius = bot.getPatrolRadius();
			AABB area = new AABB(center).inflate(radius, 8.0D, radius);
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, area, mob -> isValidTarget(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (distance < bestDistance
						&& (bot.getSensing().hasLineOfSight(mob) || Math.abs(mob.getY() - center.getY()) < 6.0D)) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		ButterBotEntity ward = nearWard(bot);
		if (ward != null) {
			// Bodyguard: the monsters around the bot it guards, first of all the ones going for it.
			AABB aroundWard = ward.getBoundingBox().inflate(WARD_RADIUS, 5.0D, WARD_RADIUS);
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, aroundWard, mob -> isValidTarget(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (mob.getTarget() == ward || ward.getLastHurtByMob() == mob) {
					distance *= 0.25D;
				}
				if (distance < bestDistance && (mob.hasLineOfSight(ward) || bot.getSensing().hasLineOfSight(mob))) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		if (best == null) {
			// Bosses (the dragon flying high, the Wither) from far away.
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(BOSS_RANGE),
					mob -> isBoss(mob) && isValidTarget(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (distance < bestDistance) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		Player owner = bot.getOwner();
		if (owner != null && owner.level() == bot.level() && bot.distanceToSqr(owner) < OWNER_LINK_SQ) {
			AABB aroundOwner = owner.getBoundingBox().inflate(OWNER_RADIUS, 5.0D, OWNER_RADIUS);
			for (Mob mob : bot.level().getEntitiesOfClass(Mob.class, aroundOwner, mob -> isValidTarget(bot, mob))) {
				double distance = bot.distanceToSqr(mob);
				if (distance < bestDistance && mob.hasLineOfSight(owner)) {
					best = mob;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/** The bot a bodyguard guards, when it is close (fights far from it would keep the bodyguard from catching up). */
	@Nullable
	private static ButterBotEntity nearWard(ButterBotEntity bot) {
		ButterBotEntity ward = bot.findWard();
		return ward != null && ward.level() == bot.level() && bot.distanceToSqr(ward) < WARD_LINK_SQ ? ward : null;
	}

	/**
	 * What the bot fights: every monster, wardens and bosses included, and any creature at all that goes after the
	 * bot, its owner or the owner's other bots, or just hurt the owner. Neutral mobs (endermen, piglins, wolves,
	 * golems...) and named mobs only once they attack; never villagers, other bots or creakings (they can't be
	 * hurt while their heart stands).
	 */
	public static boolean isHostile(ButterBotEntity bot, Mob mob) {
		if (!mob.isAlive() || mob instanceof ButterBotEntity || mob.isInvulnerable() || mob instanceof Creaking || mob instanceof Npc) {
			return false;
		}
		if (isAttackingUs(bot, mob.getTarget()) || hurtOwner(bot, mob)) {
			return true;
		}
		if (!(mob instanceof Enemy) || mob instanceof TamableAnimal tamable && tamable.isTame()) {
			return false;
		}
		return !(mob instanceof NeutralMob || mob instanceof AbstractPiglin || mob.hasCustomName());
	}

	/** The mob that hurt the owner in the last 10 seconds. */
	private static boolean hurtOwner(ButterBotEntity bot, Mob mob) {
		Player owner = bot.getOwner();
		return owner != null && owner.getLastHurtByMob() == mob && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200;
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
