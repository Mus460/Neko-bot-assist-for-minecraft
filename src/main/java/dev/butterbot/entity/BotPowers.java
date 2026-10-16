package dev.butterbot.entity;

import dev.butterbot.ai.actions.GuardAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The super powers of high-level bots, server side ({@link BotStats.Power}, one every level or two from 12 to 30).
 * They only ever touch monsters the bot would fight anyway ({@link GuardAction#isHostile}: never the owner,
 * villagers, pets or other bots) and never blocks: nothing is set on fire and nothing is broken, so a house next to a
 * fight stays as it is. Healing Bloom and Barrier help the owner instead. Damage grows with the bot's attack.
 * At most one power starts per second, so a fight doesn't open with all of them at once.
 */
final class BotPowers {

	private static final double FIRE_RADIUS = 4.0D;
	private static final double LEGEND_FIRE_RADIUS = 5.0D;
	private static final double FROST_RADIUS = 6.0D;
	private static final double METEOR_RANGE = 12.0D;
	private static final double METEOR_BLAST = 3.5D;
	/** Ticks a meteor takes to fall. */
	private static final int METEOR_FALL_TICKS = 12;
	private static final double METEOR_HEIGHT = 14.0D;
	private static final double SHOCKWAVE_RADIUS = 4.5D;
	private static final double BLADE_ORBIT = 1.6D;
	private static final double BLADE_REACH = 3.0D;
	private static final double CHAIN_RANGE = 10.0D;
	private static final double CHAIN_HOP = 6.0D;
	private static final double BLOOM_RANGE = 10.0D;
	private static final double DASH_MIN = 4.0D;
	private static final double DASH_MAX = 12.0D;
	private static final double BARRIER_RANGE = 12.0D;
	private static final double VOID_RANGE = 8.0D;
	private static final double VOID_BLAST = 3.5D;
	private static final int VOID_TICKS = 30;
	private static final double SMITE_RANGE = 16.0D;
	private static final int SMITE_DELAY = 10;
	private static final double BREATH_RANGE = 7.0D;
	/** Cosine of the half-angle of the breath's cone (about 35 degrees). */
	private static final double BREATH_CONE = 0.82D;
	private static final int BREATH_TICKS = 40;
	private static final double STORM_RADIUS = 4.0D;
	private static final int STORM_TICKS = 60;
	private static final int AVATAR_TICKS = 300;
	private static final double JUDGEMENT_RANGE = 48.0D;
	private static final int JUDGEMENT_FALL_TICKS = 20;
	/** At most one power starts this often, in ticks. */
	private static final int POWER_GAP = 20;

	/** Game time each power can be used again, by ordinal. */
	private final long[] readyAt = new long[BotStats.Power.values().length];
	private long nextPowerAt;
	private final List<Meteor> meteors = new ArrayList<>();
	private final List<Smite> smites = new ArrayList<>();
	@Nullable
	private Rift rift;
	private int breathTicks;
	private int stormTicks;
	private long avatarUntil;

	/** A meteor falling on {@code target}, started at game time {@code start}. */
	private record Meteor(Vec3 target, long start) {
	}

	/** A pillar of light coming down on {@code target}; a judgement is the boss-sized one. */
	private record Smite(LivingEntity target, long start, boolean judgement) {
	}

	/** A black hole at {@code center}, pulling monsters in until it collapses at {@code end}. */
	private record Rift(Vec3 center, long end) {
	}

	// ------------------------------------------------------------------ ticking

	void tick(ButterBotEntity bot, ServerLevel level) {
		long now = level.getGameTime();
		tickMeteors(bot, level);
		tickSmites(bot, level, now);
		tickRift(bot, level, now);
		tickBreath(bot, level);
		tickStorm(bot, level);
		tickAvatar(bot, level, now);
		if (!bot.isAlive()) {
			return;
		}
		BotStats stats = bot.stats();
		boolean legend = stats.has(BotStats.Ability.LEGEND);
		if (usable(bot, stats, BotStats.Power.FIRE_AURA) && bot.tickCount % 5 == 0) {
			fireAura(bot, level, legend, bot.tickCount % 20 == 0);
		}
		if (usable(bot, stats, BotStats.Power.HEALING_BLOOM) && bot.tickCount % 20 == 0 && ready(BotStats.Power.HEALING_BLOOM, now)
				&& healingBloom(bot, level)) {
			used(bot, BotStats.Power.HEALING_BLOOM, now, 600L, true);
		}
		if (usable(bot, stats, BotStats.Power.BARRIER) && bot.tickCount % 10 == 0 && ready(BotStats.Power.BARRIER, now)
				&& barrier(bot, level, legend)) {
			used(bot, BotStats.Power.BARRIER, now, legend ? 600L : 1200L, true);
		}
		if (!bot.isFighting()) {
			return;
		}
		if (usable(bot, stats, BotStats.Power.SPIRIT_BLADES)) {
			spiritBlades(bot, level);
		}
		if (now < nextPowerAt) {
			return;
		}
		float attack = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE);
		if (tryPower(bot, stats, now, BotStats.Power.JUDGEMENT, 600L, () -> judgement(bot, level, now))
				|| tryPower(bot, stats, now, BotStats.Power.METEOR_RAIN, 300L, () -> callMeteors(bot, level, now, legend))
				|| tryPower(bot, stats, now, BotStats.Power.BLADE_STORM, 400L, () -> bladeStorm(bot, level))
				|| tryPower(bot, stats, now, BotStats.Power.VOID_PULL, 400L, () -> voidPull(bot, level, now))
				|| tryPower(bot, stats, now, BotStats.Power.DRAGON_BREATH, 160L, () -> dragonBreath(bot, level))
				|| tryPower(bot, stats, now, BotStats.Power.FROST_NOVA, 160L, () -> frostNova(bot, level, attack))
				|| tryPower(bot, stats, now, BotStats.Power.HOLY_SMITE, 240L, () -> holySmite(bot, level, now))
				|| tryPower(bot, stats, now, BotStats.Power.THUNDER_CHAIN, 200L, () -> thunderChain(bot, level, attack, legend))
				|| tryPower(bot, stats, now, BotStats.Power.SHOCKWAVE, 160L, () -> shockwave(bot, level, attack))
				|| tryPower(bot, stats, now, BotStats.Power.SHADOW_DASH, 120L, () -> shadowDash(bot, level))) {
			nextPowerAt = now + POWER_GAP;
		} else {
			// Nothing to use a power on: look again in half a second rather than every tick.
			nextPowerAt = now + 10L;
		}
	}

	private interface Attempt {
		boolean run();
	}

	private boolean tryPower(ButterBotEntity bot, BotStats stats, long now, BotStats.Power power, long cooldown, Attempt attempt) {
		if (!usable(bot, stats, power) || !ready(power, now) || !attempt.run()) {
			return false;
		}
		used(bot, power, now, cooldown, power != BotStats.Power.SHADOW_DASH && power != BotStats.Power.SHOCKWAVE);
		return true;
	}

	private static boolean usable(ButterBotEntity bot, BotStats stats, BotStats.Power power) {
		return stats.hasPower(power) && bot.powerEnabled(power);
	}

	private boolean ready(BotStats.Power power, long now) {
		return now >= readyAt[power.ordinal()];
	}

	private void used(ButterBotEntity bot, BotStats.Power power, long now, long cooldown, boolean shout) {
		readyAt[power.ordinal()] = now + cooldown;
		bot.onPowerUsed(power, shout);
	}

	/** Seconds until {@code power} can be used again (0 when ready). */
	int cooldownSeconds(BotStats.Power power, long now) {
		return (int) Math.max(0L, (readyAt[power.ordinal()] - now + 19L) / 20L);
	}

	/** Avatar or not: the bot fights on instead of backing off while it lasts. */
	boolean isAvatar(long now) {
		return now < avatarUntil;
	}

	// ------------------------------------------------------------------ helpers

	private static List<Mob> monstersAround(ButterBotEntity bot, ServerLevel level, Vec3 center, double radius) {
		return level.getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(radius),
				mob -> GuardAction.isHostile(bot, mob) && mob.position().distanceToSqr(center) <= radius * radius);
	}

	/** Hurts a monster with a power; the dragon is hit in the head, where it takes full damage. */
	private static void strike(ButterBotEntity bot, ServerLevel level, LivingEntity mob, DamageSource source, float damage) {
		boolean hit = mob instanceof EnderDragon dragon ? dragon.hurt(level, dragon.head, source, damage) : mob.hurtServer(level, source, damage);
		if (hit && !mob.isAlive()) {
			bot.onDefeated(mob);
		}
	}

	private static DustParticleOptions tierDust(ButterBotEntity bot, float size) {
		return new DustParticleOptions(BotTier.of(bot.getBotLevel()).colorAt(bot.tickCount) & 0xFFFFFF, size);
	}

	private static void ring(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int points, double y) {
		for (int i = 0; i < points; i++) {
			double angle = i * Mth.TWO_PI / points;
			level.sendParticles(particle, center.x + Math.cos(angle) * radius, center.y + y, center.z + Math.sin(angle) * radius,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	/**
	 * A bolt of lightning made only of particles and sound. A real lightning bolt, even a visual-only one, powers
	 * lightning rods and scrapes the wax and rust off copper blocks around where it lands: this one touches nothing.
	 */
	static void lightning(ServerLevel level, Vec3 at, ButterBotEntity bot) {
		Vec3 point = at.add(0.0D, 18.0D, 0.0D);
		for (int i = 0; i < 9; i++) {
			Vec3 next = i == 8 ? at : point.add((bot.getRandom().nextDouble() - 0.5D) * 1.6D, -2.0D, (bot.getRandom().nextDouble() - 0.5D) * 1.6D);
			line(level, ParticleTypes.ELECTRIC_SPARK, point, next, 0.3D);
			line(level, ParticleTypes.END_ROD, point, next, 0.9D);
			point = next;
		}
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFE8F4FF), at.x, at.y + 1.0D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.5D, at.z, 30, 0.6D, 0.4D, 0.6D, 0.3D);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, bot.getSoundSource(), 1.2F, 1.0F + bot.getRandom().nextFloat() * 0.2F);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, bot.getSoundSource(), 1.0F, 1.0F);
	}

	private static void line(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to, double spacing) {
		Vec3 step = to.subtract(from);
		int points = Math.max(2, (int) Math.min(80.0D, step.length() / spacing));
		for (int i = 0; i <= points; i++) {
			Vec3 at = from.add(step.scale(i / (double) points));
			level.sendParticles(particle, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	// ------------------------------------------------------------------ Shockwave (12)

	/** The bot stamps the ground: monsters around fly back and take 60% of its attack. Needs two of them close. */
	private static boolean shockwave(ButterBotEntity bot, ServerLevel level, float attack) {
		List<Mob> hit = monstersAround(bot, level, bot.position(), SHOCKWAVE_RADIUS);
		if (hit.size() < 2 && hit.stream().noneMatch(BotLevels::isBoss)) {
			return false;
		}
		BlockState ground = level.getBlockState(bot.blockPosition().below());
		Vec3 center = bot.position();
		if (!ground.isAir()) {
			BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, ground);
			for (int r = 1; r <= 4; r++) {
				ring(level, dust, center, r, 8 + r * 4, 0.1D);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3D, center.z, 3, 1.0D, 0.1D, 1.0D, 0.0D);
		level.sendParticles(ParticleTypes.DUST_PLUME, center.x, center.y + 0.2D, center.z, 30, 2.0D, 0.1D, 2.0D, 0.05D);
		bot.playSound(SoundEvents.MACE_SMASH_GROUND_HEAVY, 1.2F, 0.8F);
		for (Mob mob : hit) {
			Vec3 away = mob.position().subtract(center).multiply(1.0D, 0.0D, 1.0D);
			if (away.lengthSqr() < 1.0E-4D) {
				away = new Vec3(1.0D, 0.0D, 0.0D);
			}
			away = away.normalize().scale(1.1D);
			mob.push(away.x, 0.5D, away.z);
			mob.needsSync = true;
			strike(bot, level, mob, bot.damageSources().mobAttack(bot), attack * 0.6F);
		}
		return true;
	}

	// ------------------------------------------------------------------ Spirit Blades (14)

	/** Three blades of light circle the bot in a fight; once a second they cut the monsters right next to it. */
	private static void spiritBlades(ButterBotEntity bot, ServerLevel level) {
		if (bot.tickCount % 2 == 0) {
			DustParticleOptions dust = tierDust(bot, 1.0F);
			for (int blade = 0; blade < 3; blade++) {
				double angle = bot.tickCount * 0.25D + blade * Mth.TWO_PI / 3.0D;
				for (int part = 0; part < 3; part++) {
					double r = BLADE_ORBIT - part * 0.25D;
					level.sendParticles(dust, bot.getX() + Math.cos(angle) * r, bot.getY() + 1.0D + part * 0.08D,
							bot.getZ() + Math.sin(angle) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
				}
				level.sendParticles(ParticleTypes.END_ROD, bot.getX() + Math.cos(angle) * BLADE_ORBIT, bot.getY() + 1.0D,
						bot.getZ() + Math.sin(angle) * BLADE_ORBIT, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
		if (bot.tickCount % 20 != 0) {
			return;
		}
		float damage = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.25F;
		for (Mob mob : monstersAround(bot, level, bot.position(), BLADE_REACH)) {
			level.sendParticles(ParticleTypes.ENCHANTED_HIT, mob.getX(), mob.getY(0.6D), mob.getZ(), 6, 0.3D, 0.3D, 0.3D, 0.1D);
			strike(bot, level, mob, bot.damageSources().mobAttack(bot), damage);
		}
	}

	// ------------------------------------------------------------------ Thunder Chain (16)

	/** Lightning jumps from monster to monster (five, eight for a legend), 60% of the attack each. Light only: no fire. */
	private static boolean thunderChain(ButterBotEntity bot, ServerLevel level, float attack, boolean legend) {
		List<Mob> around = monstersAround(bot, level, bot.position(), CHAIN_RANGE);
		if (around.isEmpty()) {
			return false;
		}
		around.sort(Comparator.comparingDouble(bot::distanceToSqr));
		List<LivingEntity> chain = new ArrayList<>();
		LivingEntity current = around.getFirst();
		int max = legend ? 8 : 5;
		while (current != null && chain.size() < max) {
			chain.add(current);
			LivingEntity from = current;
			current = null;
			double best = CHAIN_HOP * CHAIN_HOP;
			for (Mob mob : monstersAround(bot, level, from.position(), CHAIN_HOP)) {
				double distance = mob.distanceToSqr(from);
				if (!chain.contains(mob) && distance < best) {
					best = distance;
					current = mob;
				}
			}
		}
		lightning(level, chain.getFirst().position(), bot);
		Vec3 from = bot.getEyePosition();
		for (LivingEntity mob : chain) {
			Vec3 to = mob.getBoundingBox().getCenter();
			line(level, ParticleTypes.ELECTRIC_SPARK, from, to, 0.4D);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 12, 0.3D, 0.4D, 0.3D, 0.2D);
			strike(bot, level, mob, bot.damageSources().mobAttack(bot), attack * 0.6F);
			from = to;
		}
		bot.playSound(SoundEvents.TRIDENT_THUNDER.value(), 0.8F, 1.4F);
		return true;
	}

	// ------------------------------------------------------------------ Healing Bloom (18)

	/**
	 * When the bot or its owner is below half health, flowers bloom around the bot and heal it, its owner and the
	 * owner's other bots close by.
	 */
	private static boolean healingBloom(ButterBotEntity bot, ServerLevel level) {
		Player owner = bot.getOwner();
		boolean ownerNear = owner != null && owner.level() == level && owner.isAlive() && bot.distanceToSqr(owner) < BLOOM_RANGE * BLOOM_RANGE;
		boolean botHurt = bot.getHealth() < bot.getMaxHealth() * 0.5F;
		boolean ownerHurt = ownerNear && owner.getHealth() < owner.getMaxHealth() * 0.5F;
		if (!botHurt && !ownerHurt) {
			return false;
		}
		float heal = 8.0F + bot.getBotLevel() * 0.3F;
		List<LivingEntity> healed = new ArrayList<>();
		healed.add(bot);
		if (ownerNear) {
			healed.add(owner);
		}
		for (ButterBotEntity other : level.getEntitiesOfClass(ButterBotEntity.class, bot.getBoundingBox().inflate(8.0D),
				other -> other != bot && other.getOwnerUuid() != null && other.getOwnerUuid().equals(bot.getOwnerUuid()))) {
			healed.add(other);
		}
		for (LivingEntity each : healed) {
			each.heal(each == bot || each instanceof ButterBotEntity ? heal * 2.0F : heal);
			level.sendParticles(ParticleTypes.HEART, each.getX(), each.getY() + each.getBbHeight() + 0.3D, each.getZ(), 4, 0.4D, 0.2D, 0.4D, 0.0D);
		}
		Vec3 center = bot.position();
		ColorParticleOption petals = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0xFFF7A8C8);
		for (int r = 1; r <= 3; r++) {
			ring(level, ParticleTypes.HAPPY_VILLAGER, center, r * 1.2D, 10 + r * 4, 0.2D);
		}
		level.sendParticles(ParticleTypes.CHERRY_LEAVES, center.x, center.y + 2.5D, center.z, 40, 2.5D, 0.5D, 2.5D, 0.0D);
		level.sendParticles(petals, center.x, center.y + 2.0D, center.z, 20, 2.0D, 0.5D, 2.0D, 0.0D);
		bot.playSound(SoundEvents.AMETHYST_CLUSTER_BREAK, 1.0F, 1.6F);
		bot.playSound(SoundEvents.ALLAY_ITEM_GIVEN, 1.0F, 1.0F);
		return true;
	}

	// ------------------------------------------------------------------ Fire Aura (20)

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
		float attack = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE);
		float damage = Math.max(legend ? 4.0F : 2.0F, attack * (legend ? 0.15F : 0.1F));
		for (Mob mob : inside) {
			mob.igniteForSeconds(4.0F);
			strike(bot, level, mob, bot.damageSources().onFire(), damage);
		}
	}

	// ------------------------------------------------------------------ Shadow Dash (21)

	/**
	 * The bot vanishes in smoke and steps out right behind a monster 4 to 12 blocks away; its next blow does double
	 * damage. Only onto open ground it can stand on.
	 */
	private static boolean shadowDash(ButterBotEntity bot, ServerLevel level) {
		Mob target = null;
		for (Mob mob : monstersAround(bot, level, bot.position(), DASH_MAX)) {
			double distance = bot.distanceTo(mob);
			if (distance >= DASH_MIN && mob.onGround() && Math.abs(mob.getY() - bot.getY()) < 3.0D && !BotLevels.isBoss(mob)
					&& bot.getSensing().hasLineOfSight(mob)) {
				target = mob;
				break;
			}
		}
		if (target == null) {
			return false;
		}
		Vec3 look = target.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
		if (look.lengthSqr() < 1.0E-4D) {
			look = target.position().subtract(bot.position()).multiply(1.0D, 0.0D, 1.0D);
		}
		Vec3 behind = target.position().subtract(look.normalize().scale(1.4D));
		AABB box = bot.getBoundingBox().move(behind.subtract(bot.position()));
		BlockPos feet = BlockPos.containing(behind);
		// Only onto firm, dry ground in plain sight of the monster: never into lava, water, a wall or over a drop.
		if (!level.noCollision(bot, box) || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
				|| !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
				|| level.clip(new ClipContext(target.getEyePosition(), behind.add(0.0D, 1.0D, 0.0D), ClipContext.Block.COLLIDER,
				ClipContext.Fluid.ANY, target)).getType() != HitResult.Type.MISS) {
			return false;
		}
		Vec3 from = bot.position();
		line(level, ParticleTypes.LARGE_SMOKE, from.add(0.0D, 1.0D, 0.0D), behind.add(0.0D, 1.0D, 0.0D), 0.6D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0D, from.z, 30, 0.3D, 0.6D, 0.3D, 0.05D);
		bot.getNavigation().stop();
		bot.teleportTo(behind.x, behind.y, behind.z);
		bot.getLookControl().setLookAt(target, 180.0F, 90.0F);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, behind.x, behind.y + 1.0D, behind.z, 30, 0.3D, 0.6D, 0.3D, 0.05D);
		level.sendParticles(ParticleTypes.SMOKE, behind.x, behind.y + 0.5D, behind.z, 15, 0.3D, 0.3D, 0.3D, 0.02D);
		bot.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.8F, 1.5F);
		bot.combat().armDash();
		return true;
	}

	// ------------------------------------------------------------------ Barrier (22)

	/**
	 * When the owner drops below 40% health with monsters around (or was just hurt), a dome of light closes over them:
	 * absorption hearts and resistance for a few seconds.
	 */
	private static boolean barrier(ButterBotEntity bot, ServerLevel level, boolean legend) {
		Player owner = bot.getOwner();
		if (owner == null || owner.level() != level || !owner.isAlive() || owner.isSpectator() || owner.isCreative()
				|| bot.distanceToSqr(owner) > BARRIER_RANGE * BARRIER_RANGE || owner.getHealth() >= owner.getMaxHealth() * 0.4F) {
			return false;
		}
		boolean danger = owner.tickCount - owner.getLastHurtByMobTimestamp() < 100
				|| !monstersAround(bot, level, owner.position(), 10.0D).isEmpty();
		if (!danger) {
			return false;
		}
		owner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, legend ? 3 : 1), bot);
		owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 1), bot);
		Vec3 center = owner.position().add(0.0D, 1.0D, 0.0D);
		for (int i = 0; i < 60; i++) {
			// Points spread evenly over the upper half of a sphere.
			double y = i / 60.0D;
			double r = Math.sqrt(1.0D - y * y) * 1.6D;
			double angle = i * 2.39996D;
			level.sendParticles(ParticleTypes.END_ROD, center.x + Math.cos(angle) * r, center.y - 0.8D + y * 2.2D,
					center.z + Math.sin(angle) * r, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.sendParticles(ParticleTypes.GLOW, center.x, center.y, center.z, 20, 0.8D, 0.8D, 0.8D, 0.0D);
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.BEACON_POWER_SELECT, bot.getSoundSource(), 1.0F, 1.4F);
		if (owner instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.translatable("butterbot.power.barrier.cast", bot.getName()).withColor(0xFFE680), true);
		}
		return true;
	}

	// ------------------------------------------------------------------ Void Pull (23)

	/** A black hole opens among three or more monsters, drags them together and collapses on them. */
	private boolean voidPull(ButterBotEntity bot, ServerLevel level, long now) {
		List<Mob> around = monstersAround(bot, level, bot.position(), VOID_RANGE);
		around.removeIf(BotLevels::isBoss);
		if (around.size() < 3) {
			return false;
		}
		Vec3 center = Vec3.ZERO;
		for (Mob mob : around) {
			center = center.add(mob.position());
		}
		center = center.scale(1.0D / around.size());
		rift = new Rift(center, now + VOID_TICKS);
		bot.playSound(SoundEvents.PORTAL_TRIGGER, 0.6F, 1.8F);
		return true;
	}

	private void tickRift(ButterBotEntity bot, ServerLevel level, long now) {
		Rift current = rift;
		if (current == null) {
			return;
		}
		Vec3 center = current.center();
		if (now >= current.end() || !bot.isAlive()) {
			rift = null;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y + 1.0D, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			level.sendParticles(ParticleTypes.SQUID_INK, center.x, center.y + 1.0D, center.z, 40, 1.0D, 0.6D, 1.0D, 0.15D);
			level.playSound(null, center.x, center.y, center.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), bot.getSoundSource(), 1.2F, 0.6F);
			float damage = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE);
			for (Mob mob : monstersAround(bot, level, center, VOID_BLAST)) {
				strike(bot, level, mob, bot.damageSources().indirectMagic(bot, bot), damage);
			}
			return;
		}
		double spin = now * 0.5D;
		for (int i = 0; i < 12; i++) {
			double angle = spin + i * Mth.TWO_PI / 12.0D;
			double r = 0.6D + (i % 3) * 0.5D;
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x + Math.cos(angle) * r, center.y + 1.0D, center.z + Math.sin(angle) * r,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		level.sendParticles(ParticleTypes.SQUID_INK, center.x, center.y + 1.0D, center.z, 3, 0.2D, 0.2D, 0.2D, 0.0D);
		for (Mob mob : monstersAround(bot, level, center, VOID_RANGE)) {
			if (BotLevels.isBoss(mob)) {
				continue;
			}
			Vec3 pull = center.subtract(mob.position());
			if (pull.lengthSqr() > 0.25D) {
				mob.setDeltaMovement(mob.getDeltaMovement().scale(0.5D).add(pull.normalize().scale(0.3D)));
				mob.needsSync = true;
			}
		}
	}

	// ------------------------------------------------------------------ Holy Smite (24) and Judgement (29)

	/** A column of light marks the toughest monster around, then comes down on it: twice as hard on the undead. */
	private boolean holySmite(ButterBotEntity bot, ServerLevel level, long now) {
		Mob target = null;
		for (Mob mob : monstersAround(bot, level, bot.position(), SMITE_RANGE)) {
			if (target == null || mob.getMaxHealth() > target.getMaxHealth()) {
				target = mob;
			}
		}
		if (target == null) {
			return false;
		}
		smites.add(new Smite(target, now, false));
		bot.playSound(SoundEvents.BEACON_ACTIVATE, 0.8F, 1.6F);
		return true;
	}

	/** Against bosses only: a giant sword of light falls from the sky and strikes for three times the attack. */
	private boolean judgement(ButterBotEntity bot, ServerLevel level, long now) {
		LivingEntity boss = null;
		for (Mob mob : monstersAround(bot, level, bot.position(), JUDGEMENT_RANGE)) {
			if (BotLevels.isBoss(mob) && (boss == null || bot.distanceToSqr(mob) < bot.distanceToSqr(boss))) {
				boss = mob;
			}
		}
		if (boss == null) {
			return false;
		}
		smites.add(new Smite(boss, now, true));
		bot.playSound(SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE, 1.2F, 0.7F);
		return true;
	}

	private void tickSmites(ButterBotEntity bot, ServerLevel level, long now) {
		if (smites.isEmpty()) {
			return;
		}
		Iterator<Smite> it = smites.iterator();
		while (it.hasNext()) {
			Smite smite = it.next();
			LivingEntity target = smite.target();
			long age = now - smite.start();
			int delay = smite.judgement() ? JUDGEMENT_FALL_TICKS : SMITE_DELAY;
			if (!target.isAlive() || target.level() != level || !bot.isAlive()) {
				it.remove();
				continue;
			}
			Vec3 at = smite.judgement() ? target.getBoundingBox().getCenter() : target.position();
			if (age < delay) {
				if (smite.judgement()) {
					// The sword: a blade with a crossguard, falling from 24 blocks up.
					double tipY = at.y + 24.0D * (1.0D - age / (double) delay);
					DustParticleOptions gold = new DustParticleOptions(0xFFE680, 2.0F);
					for (int i = 0; i < 12; i++) {
						level.sendParticles(gold, at.x, tipY + i * 0.5D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
					}
					for (int i = -3; i <= 3; i++) {
						level.sendParticles(gold, at.x + i * 0.35D, tipY + 4.5D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
					}
					level.sendParticles(ParticleTypes.END_ROD, at.x, tipY, at.z, 4, 0.1D, 0.1D, 0.1D, 0.02D);
				} else {
					for (int i = 0; i < 8; i++) {
						level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + i * 0.6D + (age % 4) * 0.15D, at.z, 1, 0.05D, 0.0D, 0.05D, 0.0D);
					}
				}
				continue;
			}
			it.remove();
			float attack = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE);
			float damage = smite.judgement() ? attack * 3.0F : attack * 1.2F;
			if (!smite.judgement() && target.is(EntityTypeTags.UNDEAD)) {
				damage *= 2.0F;
			}
			for (int i = 0; i < (smite.judgement() ? 30 : 14); i++) {
				level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + i * 0.5D, at.z, 2, 0.15D, 0.0D, 0.15D, 0.01D);
			}
			level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF4C2), at.x, at.y + 1.0D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 1.0D, at.z, smite.judgement() ? 60 : 20, 0.6D, 1.0D, 0.6D, 0.3D);
			if (smite.judgement()) {
				lightning(level, target.position(), bot);
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
			level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_RESONATE, bot.getSoundSource(), 1.2F, smite.judgement() ? 0.6F : 1.4F);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, bot.getSoundSource(), 1.0F, 1.2F);
			strike(bot, level, target, bot.damageSources().indirectMagic(bot, bot), damage);
		}
	}

	// ------------------------------------------------------------------ Frost Nova (25)

	/** A burst of snow: the monsters around freeze, slow right down and take frost damage. True if any were hit. */
	private static boolean frostNova(ButterBotEntity bot, ServerLevel level, float attack) {
		List<Mob> hit = monstersAround(bot, level, bot.position(), FROST_RADIUS);
		if (hit.isEmpty()) {
			return false;
		}
		for (Mob mob : hit) {
			mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2), bot);
			if (mob.canFreeze()) {
				mob.setTicksFrozen(mob.getTicksRequiredToFreeze() + 80);
			}
			strike(bot, level, mob, bot.damageSources().freeze(), Math.max(6.0F, attack * 0.4F));
		}
		level.sendParticles(ParticleTypes.SNOWFLAKE, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 120, FROST_RADIUS * 0.4D, 0.6D,
				FROST_RADIUS * 0.4D, 0.15D);
		ring(level, ParticleTypes.ITEM_SNOWBALL, bot.position(), FROST_RADIUS, 24, 0.3D);
		bot.playSound(SoundEvents.POWDER_SNOW_BREAK, 1.2F, 0.6F);
		bot.playSound(SoundEvents.PLAYER_HURT_FREEZE, 0.8F, 0.8F);
		return true;
	}

	// ------------------------------------------------------------------ Dragon Breath (26)

	/** For two seconds the bot breathes purple dragon fire at the monsters in front of it. */
	private boolean dragonBreath(ButterBotEntity bot, ServerLevel level) {
		if (monstersInCone(bot, level).isEmpty()) {
			return false;
		}
		breathTicks = BREATH_TICKS;
		bot.playSound(SoundEvents.ENDER_DRAGON_SHOOT, 1.0F, 1.3F);
		return true;
	}

	private static List<Mob> monstersInCone(ButterBotEntity bot, ServerLevel level) {
		Vec3 eye = bot.getEyePosition();
		Vec3 look = bot.getLookAngle();
		return level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(BREATH_RANGE), mob -> {
			if (!GuardAction.isHostile(bot, mob)) {
				return false;
			}
			Vec3 to = mob.getBoundingBox().getCenter().subtract(eye);
			double distance = to.length();
			return distance < BREATH_RANGE && distance > 1.0E-3D && to.scale(1.0D / distance).dot(look) > BREATH_CONE;
		});
	}

	private void tickBreath(ButterBotEntity bot, ServerLevel level) {
		if (breathTicks <= 0) {
			return;
		}
		breathTicks--;
		if (!bot.isAlive()) {
			breathTicks = 0;
			return;
		}
		Vec3 eye = bot.getEyePosition();
		Vec3 look = bot.getLookAngle();
		PowerParticleOption breath = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
		for (int i = 0; i < 6; i++) {
			double reach = 0.8D + bot.getRandom().nextDouble() * (BREATH_RANGE - 1.0D);
			Vec3 at = eye.add(look.scale(reach));
			double spread = reach * 0.3D;
			level.sendParticles(breath, at.x, at.y, at.z, 2, spread, spread * 0.5D, spread, 0.01D);
		}
		if (breathTicks % 10 != 0) {
			return;
		}
		float damage = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.35F;
		for (Mob mob : monstersInCone(bot, level)) {
			strike(bot, level, mob, bot.damageSources().indirectMagic(bot, bot), damage);
		}
	}

	// ------------------------------------------------------------------ Blade Storm (27)

	/** With three or more monsters close, the bot spins like a whirlwind for three seconds, cutting everything around. */
	private boolean bladeStorm(ButterBotEntity bot, ServerLevel level) {
		if (monstersAround(bot, level, bot.position(), STORM_RADIUS).size() < 3) {
			return false;
		}
		stormTicks = STORM_TICKS;
		bot.playSound(SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 1.0F, 0.8F);
		return true;
	}

	private void tickStorm(ButterBotEntity bot, ServerLevel level) {
		if (stormTicks <= 0) {
			return;
		}
		stormTicks--;
		if (!bot.isAlive()) {
			stormTicks = 0;
			return;
		}
		// Spinning round: the body turns 40 degrees a tick.
		bot.setYRot(bot.getYRot() + 40.0F);
		bot.setYBodyRot(bot.getYRot());
		bot.setYHeadRot(bot.getYRot());
		if (stormTicks % 2 == 0) {
			ring(level, ParticleTypes.SWEEP_ATTACK, bot.position(), 2.0D + (stormTicks % 6) * 0.3D, 4, 1.0D);
			ring(level, tierDust(bot, 1.2F), bot.position(), STORM_RADIUS, 16, 0.6D);
		}
		if (stormTicks % 10 != 0) {
			return;
		}
		bot.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.8F + (stormTicks % 20) * 0.02F);
		float damage = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5F;
		for (Mob mob : monstersAround(bot, level, bot.position(), STORM_RADIUS)) {
			strike(bot, level, mob, bot.damageSources().mobAttack(bot), damage);
		}
	}

	// ------------------------------------------------------------------ Avatar (28)

	/**
	 * Called when the bot is hurt in a fight: below 30% health it takes on its avatar form for 15 seconds (once every
	 * three minutes): heals half its health, hits much harder, takes less damage and moves faster, wrapped in its
	 * tier's light and soul fire.
	 */
	boolean tryAvatar(ButterBotEntity bot, ServerLevel level) {
		long now = level.getGameTime();
		BotStats stats = bot.stats();
		if (!usable(bot, stats, BotStats.Power.AVATAR) || !ready(BotStats.Power.AVATAR, now) || !bot.isFighting()
				|| bot.getHealth() >= bot.getMaxHealth() * 0.3F || !bot.isAlive()) {
			return false;
		}
		used(bot, BotStats.Power.AVATAR, now, 3600L, true);
		avatarUntil = now + AVATAR_TICKS;
		bot.heal(bot.getMaxHealth() * 0.5F);
		bot.addEffect(new MobEffectInstance(MobEffects.STRENGTH, AVATAR_TICKS, 2), bot);
		bot.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, AVATAR_TICKS, 1), bot);
		bot.addEffect(new MobEffectInstance(MobEffects.SPEED, AVATAR_TICKS, 1), bot);
		bot.addEffect(new MobEffectInstance(MobEffects.REGENERATION, AVATAR_TICKS, 2), bot);
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), bot.getX(), bot.getY() + 1.0D, bot.getZ(),
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 80, 0.6D, 1.0D, 0.6D, 0.4D);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 60, 0.8D, 1.0D, 0.8D, 0.1D);
		bot.playSound(SoundEvents.ENDER_DRAGON_GROWL, 0.8F, 1.6F);
		bot.playSound(SoundEvents.TOTEM_USE, 0.8F, 1.4F);
		bot.playGesture(ButterBotEntity.GESTURE_WINGS);
		return true;
	}

	private void tickAvatar(ButterBotEntity bot, ServerLevel level, long now) {
		if (now >= avatarUntil || !bot.isAlive() || bot.tickCount % 2 != 0) {
			return;
		}
		ring(level, tierDust(bot, 1.3F), bot.position(), 1.2D, 12, 0.1D + (bot.tickCount % 20) * 0.1D);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 2, 0.4D, 0.7D, 0.4D, 0.02D);
		if (bot.tickCount % 10 == 0) {
			level.sendParticles(ParticleTypes.END_ROD, bot.getX(), bot.getY() + 2.2D, bot.getZ(), 3, 0.3D, 0.1D, 0.3D, 0.02D);
		}
	}

	// ------------------------------------------------------------------ Meteor Rain (30)

	/** Calls meteors down on up to three monsters within 12 blocks (five for a legend). True if there was something to aim at. */
	private boolean callMeteors(ButterBotEntity bot, ServerLevel level, long now, boolean legend) {
		List<Mob> targets = monstersAround(bot, level, bot.position(), METEOR_RANGE);
		if (targets.isEmpty()) {
			return false;
		}
		for (int i = 0; i < Math.min(legend ? 5 : 3, targets.size()); i++) {
			Mob target = targets.get(i);
			meteors.add(new Meteor(target.position(), now + i * 4L));
		}
		bot.playSound(SoundEvents.BLAZE_SHOOT, 1.0F, 0.6F);
		return true;
	}

	/** Meteors fall with a trail of fire and smoke, then burst: monsters within 3.5 blocks burn and take heavy damage. */
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
			float damage = Math.max(24.0F, (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.8F);
			for (Mob mob : monstersAround(bot, level, target, METEOR_BLAST)) {
				mob.igniteForSeconds(4.0F);
				Vec3 push = mob.position().subtract(target).normalize().scale(0.6D);
				mob.push(push.x, 0.4D, push.z);
				strike(bot, level, mob, bot.damageSources().mobAttack(bot), damage);
			}
		}
	}
}
