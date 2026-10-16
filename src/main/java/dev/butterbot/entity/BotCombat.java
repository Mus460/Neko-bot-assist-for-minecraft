package dev.butterbot.entity;

import dev.butterbot.ButterBot;
import dev.butterbot.ai.actions.GuardAction;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The bot's fighting tricks, server side: combos that build up damage, real critical blows, sidestepping, batting
 * arrows back, finishing off nearly dead monsters, fire / frost / thunder claws, a rage when badly hurt, a healing
 * draught, drawing monsters off a hurt owner, kill streaks, extra damage against bosses, floating damage numbers and a
 * boss bar with the bot's own health during boss fights. The levels each trick opens at are in {@link BotStats}.
 */
final class BotCombat {

	/** Damage numbers are text displays with this tag; any left over in a saved chunk are dropped when it loads. */
	public static final String POPUP_TAG = "butterbot_popup";
	static final Identifier BONUS_ID = ButterBot.id("combat_bonus");
	private static final int COMBO_TIMEOUT = 60;
	private static final int MAX_COMBO_STACKS = 20;
	/** Each hit of a combo adds 3% damage, up to +60%. */
	private static final float COMBO_STEP = 0.03F;
	private static final long STREAK_WINDOW = 400L;
	private static final int POPUP_TICKS = 18;
	private static final int MAX_POPUPS = 12;
	private static final long RAGE_TICKS = 200L;
	private static final long RAGE_COOLDOWN = 1200L;
	private static final long POTION_COOLDOWN = 1200L;
	private static final long TAUNT_COOLDOWN = 100L;
	private static final double TAUNT_RANGE = 12.0D;
	private static final double BOSS_BAR_RANGE = 64.0D;
	private static final double CLAW_CHAIN_RANGE = 4.0D;

	private int combo;
	private int lastHitTick = -1000;
	private boolean dashArmed;
	private boolean pendingCrit;
	private long rageUntil;
	private long rageReadyAt;
	private long potionReadyAt;
	private long tauntReadyAt;
	private long lastDodgePopup;
	private final ArrayDeque<Long> kills = new ArrayDeque<>();
	private int streakShown;
	private final List<Popup> popups = new ArrayList<>();
	@Nullable
	private ServerBossEvent bossBar;

	private record Popup(Display.TextDisplay display, int born, double rise) {
	}

	int combo() {
		return combo;
	}

	/** Shadow Dash: the next blow does double damage. */
	void armDash() {
		dashArmed = true;
	}

	boolean isRaging(long now) {
		return now < rageUntil;
	}

	// ------------------------------------------------------------------ blows

	/**
	 * How much harder the next blow hits: the combo, rage, a boss, a dash and (rolled here) a critical hit. Applied as
	 * a modifier on the attack damage around the vanilla blow, so enchantments and knockback work as usual.
	 */
	float prepareBlow(ButterBotEntity bot, Entity target, long now) {
		BotStats stats = bot.stats();
		float multiplier = 1.0F + COMBO_STEP * Math.min(combo, MAX_COMBO_STACKS);
		if (isRaging(now)) {
			multiplier *= 1.4F;
		}
		if (target instanceof LivingEntity living && BotLevels.isBoss(living)) {
			multiplier *= stats.bossDamage();
		}
		if (dashArmed) {
			multiplier *= 2.0F;
			dashArmed = false;
		}
		pendingCrit = bot.getRandom().nextFloat() < stats.critChance();
		if (pendingCrit) {
			multiplier *= stats.critMultiplier();
		}
		return multiplier;
	}

	boolean wasCritical() {
		return pendingCrit;
	}

	/** After a blow landed: the combo grows, a number pops up, the claws bite and nearly dead monsters are finished. */
	void afterHit(ButterBotEntity bot, ServerLevel level, Entity target, float healthBefore) {
		int tick = bot.tickCount;
		combo = tick - lastHitTick > COMBO_TIMEOUT ? 1 : combo + 1;
		lastHitTick = tick;
		bot.syncCombo(combo);
		if (bot.getProgress().recordStat(BotProgress.Stat.BEST_COMBO, combo)) {
			BotAchievements.check(bot);
		}
		BotStats stats = bot.stats();
		if (!(target instanceof LivingEntity living)) {
			return;
		}
		float dealt = Math.max(0.0F, healthBefore - (living.isAlive() ? living.getHealth() : 0.0F));
		if (dealt > 0.0F) {
			popup(bot, level, living, pendingCrit
					? Component.literal("✦ " + format(dealt) + "!").withStyle(ChatFormatting.BOLD).withColor(0xFFC23D)
					: Component.literal(format(dealt)).withColor(0xFFFFFF));
		}
		if (pendingCrit) {
			level.sendParticles(ParticleTypes.CRIT, living.getX(), living.getY(0.6D), living.getZ(), 14, 0.3D, 0.3D, 0.3D, 0.3D);
			level.sendParticles(ParticleTypes.ENCHANTED_HIT, living.getX(), living.getY(0.6D), living.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.2D);
			bot.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 0.8F, 1.0F);
		}
		if (!living.isAlive()) {
			return;
		}
		int lv = stats.level();
		if (lv >= BotStats.FIRE_CLAWS_LEVEL) {
			living.igniteForSeconds(3.0F);
		}
		if (lv >= BotStats.FROST_CLAWS_LEVEL) {
			living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0), bot);
			level.sendParticles(ParticleTypes.SNOWFLAKE, living.getX(), living.getY(0.5D), living.getZ(), 6, 0.3D, 0.4D, 0.3D, 0.02D);
		}
		if (lv >= BotStats.THUNDER_CLAWS_LEVEL && bot.getRandom().nextFloat() < 0.25F) {
			thunderClaws(bot, level, living);
		}
		float threshold = stats.executeThreshold();
		if (threshold > 0.0F && !BotLevels.isBoss(living) && living.getHealth() < living.getMaxHealth() * threshold) {
			execute(bot, level, living);
		}
	}

	/** Sparks jump from the target to up to two monsters next to it, 30% of the attack each. */
	private static void thunderClaws(ButterBotEntity bot, ServerLevel level, LivingEntity target) {
		float damage = (float) bot.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.3F;
		int hit = 0;
		Vec3 from = target.getBoundingBox().getCenter();
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, from.x, from.y, from.z, 10, 0.3D, 0.4D, 0.3D, 0.15D);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(CLAW_CHAIN_RANGE),
				mob -> mob != target && GuardAction.isHostile(bot, mob))) {
			Vec3 to = mob.getBoundingBox().getCenter();
			Vec3 step = to.subtract(from);
			for (int i = 1; i <= 8; i++) {
				Vec3 at = from.add(step.scale(i / 8.0D));
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
			}
			if (mob.hurtServer(level, bot.damageSources().mobAttack(bot), damage) && !mob.isAlive()) {
				bot.onDefeated(mob);
			}
			if (++hit >= 2) {
				break;
			}
		}
		bot.playSound(SoundEvents.TRIDENT_THUNDER.value(), 0.3F, 2.0F);
	}

	/** Finishing blow: a monster (not a boss) this low on health falls at once, in a puff of souls. */
	private void execute(ButterBotEntity bot, ServerLevel level, LivingEntity target) {
		level.sendParticles(ParticleTypes.SOUL, target.getX(), target.getY(0.5D), target.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.05D);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.6D), target.getZ(), 2, 0.2D, 0.2D, 0.2D, 0.0D);
		bot.playSound(SoundEvents.SOUL_ESCAPE.value(), 1.0F, 0.8F);
		bot.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.7F);
		popup(bot, level, target, Component.translatable("butterbot.combat.execute").withStyle(ChatFormatting.BOLD).withColor(0xC04BFF));
		target.hurtServer(level, bot.damageSources().mobAttack(bot), target.getMaxHealth() * 10.0F);
	}

	// ------------------------------------------------------------------ being hit

	/**
	 * Sidestepping (from level 12): a blow or a shot from something misses now and then. The bot hops aside in a puff
	 * of cloud. True if it dodged.
	 */
	boolean tryDodge(ButterBotEntity bot, ServerLevel level, DamageSource source) {
		float chance = bot.stats().dodgeChance();
		if (chance <= 0.0F || source.getEntity() == null || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
				|| source.is(DamageTypeTags.IS_EXPLOSION) || source.getEntity() instanceof Player player && bot.isOwnedBy(player)
				|| bot.getRandom().nextFloat() >= chance) {
			return false;
		}
		Vec3 away = bot.position().subtract(source.getEntity().position()).multiply(1.0D, 0.0D, 1.0D);
		if (away.lengthSqr() < 1.0E-4D) {
			away = new Vec3(1.0D, 0.0D, 0.0D);
		}
		// A step to the side, not back.
		Vec3 side = new Vec3(-away.z, 0.0D, away.x).normalize().scale(bot.getRandom().nextBoolean() ? 0.45D : -0.45D);
		if (bot.onGround()) {
			bot.setDeltaMovement(bot.getDeltaMovement().add(side.x, 0.15D, side.z));
			bot.needsSync = true;
		}
		level.sendParticles(ParticleTypes.CLOUD, bot.getX(), bot.getY() + 0.8D, bot.getZ(), 8, 0.3D, 0.4D, 0.3D, 0.02D);
		bot.playSound(SoundEvents.PLAYER_ATTACK_NODAMAGE, 0.8F, 1.6F);
		long now = level.getGameTime();
		if (now - lastDodgePopup > 10L) {
			lastDodgePopup = now;
			popup(bot, level, bot, Component.translatable("butterbot.combat.miss").withStyle(ChatFormatting.ITALIC).withColor(0x8FE3FF));
		}
		return true;
	}

	/**
	 * Batting shots back (from level 16): an arrow, trident or fireball from a monster may be knocked back at whoever
	 * shot it, which takes the hit instead. True if it was deflected.
	 */
	boolean tryDeflect(ButterBotEntity bot, ServerLevel level, DamageSource source, float damage) {
		float chance = bot.stats().deflectChance();
		if (chance <= 0.0F || !(source.getDirectEntity() instanceof Projectile) || !(source.getEntity() instanceof LivingEntity shooter)
				|| shooter == bot || shooter instanceof Player player && bot.isOwnedBy(player) || bot.getRandom().nextFloat() >= chance) {
			return false;
		}
		Vec3 at = source.getDirectEntity().position();
		level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 12, 0.2D, 0.2D, 0.2D, 0.3D);
		level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 8, 0.2D, 0.2D, 0.2D, 0.3D);
		bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		bot.playSound(SoundEvents.SHIELD_BLOCK.value(), 1.0F, 1.4F);
		popup(bot, level, bot, Component.translatable("butterbot.combat.deflect").withColor(0xFFE680));
		// Only monsters get their shot back: never a snow golem, the owner's llama or another player.
		if (shooter instanceof Mob mob && GuardAction.isHostile(bot, mob)) {
			if (mob.hurtServer(level, bot.damageSources().thorns(bot), Math.max(damage, 4.0F) * 1.5F) && !mob.isAlive()) {
				bot.onDefeated(mob);
			}
		}
		return true;
	}

	/** After the bot was hurt: rage, the healing draught, or (from the powers) its avatar form. */
	void afterHurt(ButterBotEntity bot, ServerLevel level) {
		long now = level.getGameTime();
		BotStats stats = bot.stats();
		float health = bot.getHealth() / bot.getMaxHealth();
		if (health < 0.3F && stats.level() >= BotStats.POTION_LEVEL && now >= potionReadyAt && bot.isAlive()) {
			potionReadyAt = now + POTION_COOLDOWN;
			bot.heal(bot.getMaxHealth() * 0.3F);
			bot.playSound(SoundEvents.GENERIC_DRINK.value(), 1.0F, 1.0F);
			level.sendParticles(ParticleTypes.HEART, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 5, 0.4D, 0.2D, 0.4D, 0.0D);
			level.sendParticles(ParticleTypes.WAX_ON, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 15, 0.4D, 0.6D, 0.4D, 0.0D);
			bot.say(Component.translatable("butterbot.combat.potion"));
			health = bot.getHealth() / bot.getMaxHealth();
		}
		if (health < 0.35F && stats.level() >= BotStats.RAGE_LEVEL && bot.isFighting() && now >= rageReadyAt && bot.isAlive()) {
			rageUntil = now + RAGE_TICKS;
			rageReadyAt = now + RAGE_COOLDOWN;
			bot.addEffect(new MobEffectInstance(MobEffects.SPEED, (int) RAGE_TICKS, 0), bot);
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 6, 0.4D, 0.3D, 0.4D, 0.0D);
			level.sendParticles(ParticleTypes.FLAME, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 30, 0.5D, 0.8D, 0.5D, 0.05D);
			bot.playSound(SoundEvents.RAVAGER_ROAR, 0.6F, 1.7F);
			bot.emote(ButterBotEntity.EMOTE_ANGER);
			bot.say(Component.translatable("butterbot.combat.rage"));
		}
	}

	// ------------------------------------------------------------------ kills

	/** A defeated monster: the kill streak grows (Rampage at 5, Unstoppable at 10, Godlike at 15) and bosses are celebrated. */
	void onKill(ButterBotEntity bot, ServerLevel level, LivingEntity victim) {
		long now = level.getGameTime();
		while (!kills.isEmpty() && now - kills.peekFirst() > STREAK_WINDOW) {
			kills.pollFirst();
		}
		if (kills.isEmpty()) {
			streakShown = 0;
		}
		kills.addLast(now);
		int streak = kills.size();
		bot.getProgress().recordStat(BotProgress.Stat.BEST_STREAK, streak);
		int milestone = streak >= 15 ? 15 : streak >= 10 ? 10 : streak >= 5 ? 5 : 0;
		if (milestone > streakShown) {
			streakShown = milestone;
			Player owner = bot.getOwner();
			if (owner instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.translatable("butterbot.combat.streak." + milestone, bot.getName(), streak)
						.withStyle(ChatFormatting.BOLD).withColor(milestone == 15 ? 0xFF4BD8 : milestone == 10 ? 0xFF8A3D : 0xFFD23D), true);
			}
			bot.playSound(SoundEvents.PLAYER_LEVELUP, 0.6F, 0.8F + milestone * 0.04F);
			level.sendParticles(ParticleTypes.FIREWORK, bot.getX(), bot.getY() + 1.5D, bot.getZ(), 10 + milestone, 0.4D, 0.5D, 0.4D, 0.08D);
		}
		if (BotLevels.isBoss(victim)) {
			bossDown(bot, level, victim);
		}
		BotAchievements.check(bot);
	}

	private static void bossDown(ButterBotEntity bot, ServerLevel level, LivingEntity boss) {
		bot.getProgress().addStat(BotProgress.Stat.BOSSES, 1);
		BotAchievements.onBossKill(bot, boss);
		for (int i = 0; i < 4; i++) {
			level.sendParticles(ParticleTypes.FIREWORK, bot.getX(), bot.getY() + 2.0D + i, bot.getZ(), 30, 1.0D + i * 0.5D, 0.6D, 1.0D + i * 0.5D, 0.15D);
		}
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 60, 0.8D, 1.2D, 0.8D, 0.4D);
		bot.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
		bot.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 0.8F);
		bot.playGesture(ButterBotEntity.GESTURE_WINGS);
		bot.emote(ButterBotEntity.EMOTE_CHECK);
		if (bot.getOwner() instanceof ServerPlayer owner) {
			ButterBotEntity.showTitle(owner, Component.translatable("butterbot.combat.boss_down").withStyle(ChatFormatting.BOLD).withColor(0xFFC23D),
					Component.translatable("butterbot.combat.boss_down_sub", bot.getName(), boss.getName()));
		}
	}

	// ------------------------------------------------------------------ ticking

	void tick(ButterBotEntity bot, ServerLevel level) {
		long now = level.getGameTime();
		if (combo > 0 && bot.tickCount - lastHitTick > COMBO_TIMEOUT) {
			combo = 0;
			bot.syncCombo(0);
		}
		tickPopups(bot);
		if (!bot.isAlive()) {
			return;
		}
		if (isRaging(now) && bot.tickCount % 4 == 0) {
			level.sendParticles(new DustParticleOptions(0xFF2A2A, 1.2F), bot.getX(), bot.getY() + 1.0D, bot.getZ(), 4, 0.4D, 0.7D, 0.4D, 0.0D);
			level.sendParticles(ParticleTypes.SMALL_FLAME, bot.getX(), bot.getY() + 0.2D, bot.getZ(), 2, 0.3D, 0.1D, 0.3D, 0.01D);
		}
		if (bot.tickCount % 20 == 0) {
			taunt(bot, level, now);
		}
		if (bot.tickCount % 10 == 0) {
			updateBossBar(bot, level);
		}
	}

	/**
	 * Drawing monsters off (from level 10): when the owner is below half health, monsters going after them turn on the
	 * bot instead, with a hiss.
	 */
	private void taunt(ButterBotEntity bot, ServerLevel level, long now) {
		Player owner = bot.getOwner();
		if (now < tauntReadyAt || bot.getBotLevel() < BotStats.TAUNT_LEVEL || !bot.isGuarding() || owner == null || owner.level() != level
				|| owner.getHealth() >= owner.getMaxHealth() * 0.5F || bot.distanceToSqr(owner) > 24.0D * 24.0D) {
			return;
		}
		boolean taunted = false;
		for (Mob mob : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(TAUNT_RANGE),
				mob -> mob.getTarget() == owner && GuardAction.isHostile(bot, mob) && !BotLevels.isBoss(mob))) {
			mob.setTarget(bot);
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + mob.getBbHeight() + 0.3D, mob.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
			taunted = true;
		}
		if (taunted) {
			tauntReadyAt = now + TAUNT_COOLDOWN;
			bot.playSound(ButterBotEntity.catSounds().hissSound().value(), 1.0F, 1.0F);
			bot.emote(ButterBotEntity.EMOTE_ANGER);
			bot.say(Component.translatable("butterbot.combat.taunt"));
		}
	}

	/** During a boss fight the owner sees the bot's own health as a boss bar in its tier's colour. */
	private void updateBossBar(ButterBotEntity bot, ServerLevel level) {
		ServerPlayer owner = bot.getOwner() instanceof ServerPlayer player && player.level() == level ? player : null;
		boolean show = owner != null && bot.isAlive() && bot.isFighting() && !level.getEntitiesOfClass(Mob.class,
				bot.getBoundingBox().inflate(BOSS_BAR_RANGE), mob -> mob.isAlive() && BotLevels.isBoss(mob) && GuardAction.isHostile(bot, mob)).isEmpty();
		if (!show) {
			removeBossBar();
			return;
		}
		BotTier tier = BotTier.of(bot.getBotLevel());
		int stars = bot.getProgress().stat(BotProgress.Stat.STARS);
		Component name = Component.literal((stars > 0 ? "★" + stars + " " : "") + "Lv" + bot.getBotLevel() + " ")
				.append(bot.getName()).withColor(tier.color() & 0xFFFFFF);
		if (bossBar == null) {
			bossBar = new ServerBossEvent(UUID.randomUUID(), name, barColor(tier), BossEvent.BossBarOverlay.NOTCHED_10);
		}
		bossBar.setName(name);
		bossBar.setColor(barColor(tier));
		bossBar.setProgress(Mth.clamp(bot.getHealth() / bot.getMaxHealth(), 0.0F, 1.0F));
		if (!bossBar.getPlayers().contains(owner)) {
			bossBar.removeAllPlayers();
			bossBar.addPlayer(owner);
		}
	}

	private static BossEvent.BossBarColor barColor(BotTier tier) {
		return switch (tier) {
			case KITTEN, SILVER -> BossEvent.BossBarColor.WHITE;
			case BRONZE, GOLD -> BossEvent.BossBarColor.YELLOW;
			case DIAMOND -> BossEvent.BossBarColor.BLUE;
			case AMETHYST -> BossEvent.BossBarColor.PURPLE;
			case LEGEND -> BossEvent.BossBarColor.PINK;
		};
	}

	private void removeBossBar() {
		if (bossBar != null) {
			bossBar.removeAllPlayers();
			bossBar = null;
		}
	}

	/** The bot is gone (died, packed up, unloaded): its boss bar and damage numbers go with it. */
	void remove() {
		removeBossBar();
		for (Popup popup : popups) {
			popup.display().discard();
		}
		popups.clear();
	}

	// ------------------------------------------------------------------ damage numbers

	/** A number (or word) that pops up over {@code over} and floats up for a second. Can be switched off per bot. */
	private void popup(ButterBotEntity bot, ServerLevel level, Entity over, Component text) {
		if (!bot.showsPopups() || popups.size() >= MAX_POPUPS) {
			return;
		}
		Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
		if (display == null) {
			return;
		}
		display.setText(text);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setBackgroundColor(0);
		display.setFlags(Display.TextDisplay.FLAG_SHADOW);
		// Readable at night and in caves too.
		display.setBrightnessOverride(Brightness.FULL_BRIGHT);
		display.setPosRotInterpolationDuration(2);
		display.addTag(POPUP_TAG);
		double jitter = 0.5D;
		display.snapTo(over.getX() + (bot.getRandom().nextDouble() - 0.5D) * jitter, over.getY() + over.getBbHeight() + 0.25D,
				over.getZ() + (bot.getRandom().nextDouble() - 0.5D) * jitter);
		if (level.addFreshEntity(display)) {
			popups.add(new Popup(display, bot.tickCount, 0.05D + bot.getRandom().nextDouble() * 0.02D));
		}
	}

	private void tickPopups(ButterBotEntity bot) {
		Iterator<Popup> it = popups.iterator();
		while (it.hasNext()) {
			Popup popup = it.next();
			Display.TextDisplay display = popup.display();
			if (bot.tickCount - popup.born() > POPUP_TICKS || display.isRemoved()) {
				display.discard();
				it.remove();
				continue;
			}
			display.setPos(display.getX(), display.getY() + popup.rise(), display.getZ());
		}
	}

	private static String format(float damage) {
		return damage >= 10.0F || Math.abs(damage - Math.round(damage)) < 0.05F
				? String.valueOf(Math.round(damage)) : String.format(java.util.Locale.ROOT, "%.1f", damage);
	}
}
