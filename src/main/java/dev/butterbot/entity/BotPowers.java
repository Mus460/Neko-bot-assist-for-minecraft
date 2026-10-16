package dev.butterbot.entity;

import dev.butterbot.ai.actions.GuardAction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The super powers of high-level bots, server side. They only ever touch monsters the bot would fight anyway
 * ({@link GuardAction#isHostile}: never the owner, villagers, pets or other bots) and never blocks: nothing is set
 * on fire and nothing is broken, so a house next to a fight stays as it is.
 * <ul>
 *     <li>Fire Aura (level 20): a ring of flames around the bot; monsters inside catch fire and burn.
 *     A legend's burns with hotter soul fire, wider.</li>
 *     <li>Frost Nova (level 25): every 8 seconds of a fight, a burst of snow freezes and slows the monsters around.</li>
 *     <li>Meteor Rain (level 30): every 15 seconds of a fight, burning meteors fall on up to three monsters and
 *     blow up (light and sound only for the blocks).</li>
 * </ul>
 */
final class BotPowers {

	private static final double FIRE_RADIUS = 4.0D;
	private static final double LEGEND_FIRE_RADIUS = 5.0D;
	private static final double FROST_RADIUS = 6.0D;
	private static final long FROST_COOLDOWN = 160L;
	private static final double METEOR_RANGE = 12.0D;
	private static final double METEOR_BLAST = 3.5D;
	private static final long METEOR_COOLDOWN = 300L;
	/** Ticks a meteor takes to fall. */
	private static final int METEOR_FALL_TICKS = 12;
	private static final double METEOR_HEIGHT = 14.0D;

	private long frostReadyAt;
	private long meteorReadyAt;
	private final List<Meteor> meteors = new ArrayList<>();

	/** A meteor falling on {@code target}, started at game time {@code start}. */
	private record Meteor(Vec3 target, long start) {
	}

	void tick(ButterBotEntity bot, ServerLevel level) {
		tickMeteors(bot, level);
		BotStats stats = bot.stats();
		if (!bot.isAlive() || !stats.hasPower(BotStats.Power.FIRE_AURA)) {
			return;
		}
		long now = level.getGameTime();
		boolean legend = stats.has(BotStats.Ability.LEGEND);
		if (bot.tickCount % 5 == 0) {
			fireAura(bot, level, legend, bot.tickCount % 20 == 0);
		}
		if (!bot.isFighting()) {
			return;
		}
		if (stats.hasPower(BotStats.Power.FROST_NOVA) && now >= frostReadyAt) {
			if (frostNova(bot, level)) {
				frostReadyAt = now + FROST_COOLDOWN;
			}
		}
		if (stats.hasPower(BotStats.Power.METEOR_RAIN) && now >= meteorReadyAt) {
			if (callMeteors(bot, level, now)) {
				meteorReadyAt = now + METEOR_COOLDOWN;
			}
		}
	}

	private static List<Mob> monstersAround(ButterBotEntity bot, ServerLevel level, Vec3 center, double radius) {
		return level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().move(center.subtract(bot.position())).inflate(radius),
				mob -> GuardAction.isHostile(bot, mob) && mob.position().distanceToSqr(center) <= radius * radius);
	}

	/** Flames circle the bot while monsters are close; once a second they catch fire and take burn damage. */
	private static void fireAura(ButterBotEntity bot, ServerLevel level, boolean legend, boolean burnNow) {
		double radius = legend ? LEGEND_FIRE_RADIUS : FIRE_RADIUS;
		List<Mob> inside = monstersAround(bot, level, bot.position(), radius);
		if (inside.isEmpty()) {
			return;
		}
		SimpleParticleType flame = legend ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
		int points = 20;
		float spin = bot.tickCount * 0.05F;
		for (int i = 0; i < points; i++) {
			double angle = spin + i * Mth.TWO_PI / points;
			level.sendParticles(flame, bot.getX() + Math.cos(angle) * radius, bot.getY() + 0.2D, bot.getZ() + Math.sin(angle) * radius,
					1, 0.05D, 0.1D, 0.05D, 0.01D);
		}
		level.sendParticles(flame, bot.getX(), bot.getY() + 0.3D, bot.getZ(), 6, radius * 0.5D, 0.1D, radius * 0.5D, 0.01D);
		if (!burnNow) {
			return;
		}
		float damage = legend ? 4.0F : 2.0F;
		for (Mob mob : inside) {
			mob.igniteForSeconds(4.0F);
			if (mob.hurtServer(level, bot.damageSources().onFire(), damage) && !mob.isAlive()) {
				bot.onDefeated(mob);
			}
		}
	}

	/** A burst of snow: the monsters around freeze, slow right down and take frost damage. True if any were hit. */
	private static boolean frostNova(ButterBotEntity bot, ServerLevel level) {
		List<Mob> hit = monstersAround(bot, level, bot.position(), FROST_RADIUS);
		if (hit.isEmpty()) {
			return false;
		}
		for (Mob mob : hit) {
			mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2), bot);
			if (mob.canFreeze()) {
				mob.setTicksFrozen(mob.getTicksRequiredToFreeze() + 80);
			}
			if (mob.hurtServer(level, bot.damageSources().freeze(), 6.0F) && !mob.isAlive()) {
				bot.onDefeated(mob);
			}
		}
		level.sendParticles(ParticleTypes.SNOWFLAKE, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 120, FROST_RADIUS * 0.4D, 0.6D,
				FROST_RADIUS * 0.4D, 0.15D);
		for (int i = 0; i < 24; i++) {
			double angle = i * Mth.TWO_PI / 24;
			level.sendParticles(ParticleTypes.ITEM_SNOWBALL, bot.getX() + Math.cos(angle) * FROST_RADIUS, bot.getY() + 0.3D,
					bot.getZ() + Math.sin(angle) * FROST_RADIUS, 2, 0.1D, 0.1D, 0.1D, 0.02D);
		}
		bot.playSound(SoundEvents.POWDER_SNOW_BREAK, 1.2F, 0.6F);
		bot.playSound(SoundEvents.PLAYER_HURT_FREEZE, 0.8F, 0.8F);
		return true;
	}

	/** Calls meteors down on up to three monsters within 12 blocks. True if there was something to aim at. */
	private boolean callMeteors(ButterBotEntity bot, ServerLevel level, long now) {
		List<Mob> targets = monstersAround(bot, level, bot.position(), METEOR_RANGE);
		if (targets.isEmpty()) {
			return false;
		}
		for (int i = 0; i < Math.min(3, targets.size()); i++) {
			Mob target = targets.get(i);
			meteors.add(new Meteor(target.position(), now + i * 4L));
		}
		bot.playSound(SoundEvents.BLAZE_SHOOT, 1.0F, 0.6F);
		return true;
	}

	/** Meteors fall with a trail of fire and smoke, then burst: monsters within 3.5 blocks burn and take 24 damage. */
	private void tickMeteors(ButterBotEntity bot, ServerLevel level) {
		if (meteors.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		Iterator<Meteor> it = meteors.iterator();
		while (it.hasNext()) {
			Meteor meteor = it.next();
			long age = now - meteor.start();
			if (age < 0L) {
				continue;
			}
			Vec3 target = meteor.target();
			if (age < METEOR_FALL_TICKS) {
				double height = METEOR_HEIGHT * (1.0D - age / (double) METEOR_FALL_TICKS);
				double drift = (METEOR_FALL_TICKS - age) * 0.25D;
				double x = target.x + drift;
				double y = target.y + height;
				level.sendParticles(ParticleTypes.FLAME, x, y, target.z, 8, 0.25D, 0.25D, 0.25D, 0.02D);
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.5D, target.z, 3, 0.2D, 0.2D, 0.2D, 0.01D);
				level.sendParticles(ParticleTypes.LAVA, x, y, target.z, 1, 0.1D, 0.1D, 0.1D, 0.0D);
				continue;
			}
			it.remove();
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 0.5D, target.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			level.sendParticles(ParticleTypes.FLAME, target.x, target.y + 0.5D, target.z, 40, 1.2D, 0.4D, 1.2D, 0.08D);
			level.sendParticles(ParticleTypes.LAVA, target.x, target.y + 0.5D, target.z, 12, 1.0D, 0.3D, 1.0D, 0.0D);
			level.playSound(null, target.x, target.y, target.z, SoundEvents.GENERIC_EXPLODE.value(), bot.getSoundSource(), 1.0F, 1.2F);
			for (Mob mob : monstersAround(bot, level, target, METEOR_BLAST)) {
				mob.igniteForSeconds(4.0F);
				Vec3 push = mob.position().subtract(target).normalize().scale(0.6D);
				mob.push(push.x, 0.4D, push.z);
				if (mob.hurtServer(level, bot.damageSources().mobAttack(bot), 24.0F) && !mob.isAlive()) {
					bot.onDefeated(mob);
				}
			}
		}
	}
}
