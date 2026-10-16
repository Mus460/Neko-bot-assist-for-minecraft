package dev.butterbot.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Looking after the owner and everyday life together, server side.
 * <p>
 * Help, by level: night vision in the dark (11), water breathing under water (13), fire resistance when burning (15),
 * a slow fall when falling far (17), a quick heal when badly hurt (19), cleansing bad effects (20), haste (21), speed
 * while sprinting (24) and Guardian Angel (28): a hit that would kill the owner leaves them standing instead. At every
 * level: warnings about a creeper behind them or a warden close by, and anger when someone hurts them.
 * <p>
 * Life: "good morning" with the number of days together, a birthday every hundred days, a flower now and then for a
 * close owner, remarks about new places and the weather, spotting diamonds, sneezing among flowers, yawning at night,
 * shooting stars, fireflies, chasing its tail, greeting the owner's pets, a lullaby while the owner sleeps and a cheer
 * when the owner's experience level goes up.
 */
final class BotCompanion {

	public static final int NIGHT_VISION_LEVEL = 11;
	public static final int WATER_BREATHING_LEVEL = 13;
	public static final int FIRE_SHIELD_LEVEL = 15;
	public static final int FEATHER_FALL_LEVEL = 17;
	public static final int QUICK_HEAL_LEVEL = 19;
	public static final int CLEANSE_LEVEL = 20;
	public static final int HASTE_LEVEL = 21;
	public static final int SWIFT_LEVEL = 24;
	public static final int GUARDIAN_ANGEL_LEVEL = 28;

	private static final double NEAR_SQ = 16.0D * 16.0D;
	private static final double CLOSE_SQ = 8.0D * 8.0D;
	private static final long QUICK_HEAL_COOLDOWN = 900L;
	private static final long GUARDIAN_COOLDOWN = 20L * 60L * 10L;
	private static final long LEGEND_GUARDIAN_COOLDOWN = 20L * 60L * 5L;
	private static final long BIOME_COOLDOWN = 20L * 60L * 3L;
	private static final int BIRTHDAY_EVERY_DAYS = 100;
	private static final Item[] FLOWERS = {Items.POPPY, Items.DANDELION, Items.CORNFLOWER, Items.ALLIUM, Items.AZURE_BLUET,
			Items.OXEYE_DAISY, Items.LILY_OF_THE_VALLEY, Items.PINK_TULIP, Items.BLUE_ORCHID, Items.SUNFLOWER};

	/** Day number (overworld clock) the bot first had an owner, the last day it said good morning and the last birthday. */
	private long claimDay = -1L;
	private long greetDay = -1L;
	private long birthdayDay = -1L;
	private long guardianReadyAt;
	private long quickHealReadyAt;
	private long creeperWarnAt;
	private long wardenWarnAt;
	private long ownerHurtReactAt;
	private int ownerHurtSeen = Integer.MIN_VALUE;
	private int ownerXpLevel = -1;
	private long cheerReadyAt;
	private String biome = "";
	private long biomeReadyAt;
	private boolean wasRaining;
	private boolean wasThundering;
	/** False until the weather was seen once (so a world loaded in the rain doesn't start with a remark). */
	private boolean weatherKnown;
	private final Set<Long> treasureSeen = new HashSet<>();
	private long treasureReadyAt;
	private long sneezeReadyAt;
	private long yawnReadyAt;
	private long starReadyAt;
	private long spinReadyAt;
	private long petGreetReadyAt;
	private int lullabyNotes;
	private boolean catchingFall;

	// ------------------------------------------------------------------ saving

	void save(ValueOutput output, long now) {
		output.putLong("ClaimDay", claimDay);
		output.putLong("GreetDay", greetDay);
		output.putLong("BirthdayDay", birthdayDay);
		output.putInt("GuardianCd", (int) Mth.clamp(guardianReadyAt - now, 0L, GUARDIAN_COOLDOWN));
	}

	void load(ValueInput input, long now) {
		claimDay = input.getLongOr("ClaimDay", -1L);
		greetDay = input.getLongOr("GreetDay", -1L);
		birthdayDay = input.getLongOr("BirthdayDay", -1L);
		guardianReadyAt = now + input.getIntOr("GuardianCd", 0);
	}

	/** What packing the bot into an item keeps: the days together, birthdays and the Guardian Angel cooldown. */
	void saveTo(CompoundTag tag, long now) {
		tag.putLong("ClaimDay", claimDay);
		tag.putLong("GreetDay", greetDay);
		tag.putLong("BirthdayDay", birthdayDay);
		tag.putInt("GuardianCd", (int) Mth.clamp(guardianReadyAt - now, 0L, GUARDIAN_COOLDOWN));
	}

	void loadFrom(CompoundTag tag, long now) {
		claimDay = tag.getLongOr("ClaimDay", -1L);
		greetDay = tag.getLongOr("GreetDay", -1L);
		birthdayDay = tag.getLongOr("BirthdayDay", -1L);
		guardianReadyAt = now + tag.getIntOr("GuardianCd", 0);
	}

	/** Days since the bot first had an owner, counting today as day 1. */
	int daysTogether(ServerLevel level) {
		return claimDay < 0L ? 1 : (int) (day(level) - claimDay + 1L);
	}

	/** Seconds until Guardian Angel is ready again. */
	int guardianSeconds(long now) {
		return (int) Math.max(0L, (guardianReadyAt - now + 19L) / 20L);
	}

	private static long day(ServerLevel level) {
		return level.getOverworldClockTime() / 24000L;
	}

	// ------------------------------------------------------------------ ticking

	void tick(ButterBotEntity bot, ServerLevel level) {
		Player owner = bot.getOwner();
		boolean here = owner != null && owner.level() == level && owner.isAlive() && !owner.isSpectator() && bot.isAlive();
		if (!here) {
			lullabyNotes = 0;
			return;
		}
		if (claimDay < 0L) {
			claimDay = day(level);
		}
		double distance = bot.distanceToSqr(owner);
		long now = level.getGameTime();
		int lv = bot.getBotLevel();
		if (bot.tickCount % 5 == 0) {
			featherFall(bot, level, owner, distance, lv);
		}
		if (bot.tickCount % 10 == 0) {
			warnings(bot, level, owner, now);
		}
		if (bot.tickCount % 20 != 0) {
			return;
		}
		ownerBuffs(bot, level, owner, distance, lv, now);
		reactToOwner(bot, level, owner, distance, now);
		if (distance > NEAR_SQ) {
			return;
		}
		dailyLife(bot, level, owner, now);
	}

	// ------------------------------------------------------------------ help

	/** Slow falling for an owner falling more than five blocks within 24 blocks of the bot, with a puff of feathers. */
	private void featherFall(ButterBotEntity bot, ServerLevel level, Player owner, double distance, int lv) {
		if (lv < FEATHER_FALL_LEVEL) {
			return;
		}
		boolean falling = owner.fallDistance > 5.0D && !owner.onGround() && !owner.isFallFlying() && !owner.getAbilities().flying
				&& !owner.isInWater() && distance < 24.0D * 24.0D;
		if (!falling) {
			catchingFall = false;
			return;
		}
		owner.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0, true, false, true), bot);
		if (!catchingFall) {
			catchingFall = true;
			level.sendParticles(ParticleTypes.CLOUD, owner.getX(), owner.getY(), owner.getZ(), 12, 0.4D, 0.2D, 0.4D, 0.02D);
			level.sendParticles(ParticleTypes.END_ROD, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 8, 0.4D, 0.4D, 0.4D, 0.02D);
			bot.playSound(SoundEvents.PHANTOM_FLAP, 0.6F, 1.6F);
		}
	}

	private static void buff(Player owner, ButterBotEntity bot, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect,
							 int ticks, int amplifier) {
		MobEffectInstance current = owner.getEffect(effect);
		if (current == null || current.getAmplifier() < amplifier || current.endsWithin(ticks - 25)) {
			owner.addEffect(new MobEffectInstance(effect, ticks, amplifier, true, false, true), bot);
		}
	}

	private void ownerBuffs(ButterBotEntity bot, ServerLevel level, Player owner, double distance, int lv, long now) {
		if (distance > NEAR_SQ) {
			return;
		}
		boolean legend = lv >= BotLevels.MAX_LEVEL;
		if (lv >= NIGHT_VISION_LEVEL && level.getMaxLocalRawBrightness(owner.blockPosition()) < 5) {
			// Long enough that the screen never flickers as it runs out.
			buff(owner, bot, MobEffects.NIGHT_VISION, 400, 0);
		}
		if (lv >= WATER_BREATHING_LEVEL && owner.isUnderWater()) {
			buff(owner, bot, MobEffects.WATER_BREATHING, 100, 0);
		}
		if (lv >= FIRE_SHIELD_LEVEL && (owner.isOnFire() || owner.isInLava())) {
			buff(owner, bot, MobEffects.FIRE_RESISTANCE, 100, 0);
		}
		if (lv >= QUICK_HEAL_LEVEL && now >= quickHealReadyAt && owner.getHealth() < owner.getMaxHealth() * 0.25F) {
			quickHealReadyAt = now + QUICK_HEAL_COOLDOWN;
			owner.heal(8.0F);
			level.sendParticles(ParticleTypes.HEART, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 10, 0.5D, 0.6D, 0.5D, 0.1D);
			bot.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.4F);
			bot.say(Component.translatable("butterbot.care.quick_heal"));
		}
		if (lv >= CLEANSE_LEVEL && bot.tickCount % 100 == 0) {
			cleanse(bot, level, owner);
		}
		if (lv >= HASTE_LEVEL && distance < CLOSE_SQ) {
			buff(owner, bot, MobEffects.HASTE, 80, legend ? 1 : 0);
		}
		if (lv >= SWIFT_LEVEL && distance < CLOSE_SQ && owner.isSprinting()) {
			buff(owner, bot, MobEffects.SPEED, 60, 0);
		}
	}

	/** Takes poison, wither, blindness, darkness, nausea, slowness, weakness and the like off the owner. */
	private static void cleanse(ButterBotEntity bot, ServerLevel level, Player owner) {
		List<MobEffectInstance> bad = new ArrayList<>();
		for (MobEffectInstance effect : owner.getActiveEffects()) {
			if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
				bad.add(effect);
			}
		}
		if (bad.isEmpty()) {
			return;
		}
		for (MobEffectInstance effect : bad) {
			owner.removeEffect(effect.getEffect());
		}
		level.sendParticles(ParticleTypes.WAX_OFF, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 20, 0.4D, 0.6D, 0.4D, 0.0D);
		level.sendParticles(ParticleTypes.END_ROD, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 10, 0.4D, 0.6D, 0.4D, 0.02D);
		bot.playSound(SoundEvents.ZOMBIE_VILLAGER_CURE, 0.4F, 1.8F);
		bot.say(Component.translatable("butterbot.care.cleanse"));
	}

	/**
	 * Guardian Angel (level 28): a blow that would kill the owner (within 48 blocks) leaves them on their feet with a
	 * few hearts, regeneration and absorption. Once every 10 minutes (5 for a legend); not against the void or /kill.
	 */
	boolean tryRescue(ButterBotEntity bot, ServerLevel level, ServerPlayer owner) {
		long now = level.getGameTime();
		if (bot.getBotLevel() < GUARDIAN_ANGEL_LEVEL || now < guardianReadyAt) {
			return false;
		}
		guardianReadyAt = now + (bot.getBotLevel() >= BotLevels.MAX_LEVEL ? LEGEND_GUARDIAN_COOLDOWN : GUARDIAN_COOLDOWN);
		owner.setHealth(Math.max(8.0F, owner.getMaxHealth() * 0.4F));
		owner.clearFire();
		owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1), bot);
		owner.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 1), bot);
		owner.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 400, 0), bot);
		owner.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 2), bot);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 80, 0.6D, 1.0D, 0.6D, 0.4D);
		level.sendParticles(ParticleTypes.END_ROD, owner.getX(), owner.getY() + 1.0D, owner.getZ(), 40, 0.5D, 1.2D, 0.5D, 0.1D);
		level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.TOTEM_USE, bot.getSoundSource(), 1.0F, 1.1F);
		bot.playGesture(ButterBotEntity.GESTURE_WINGS);
		bot.getProgress().addStat(BotProgress.Stat.OWNER_SAVES, 1);
		ButterBotEntity.showTitle(owner, Component.translatable("butterbot.care.guardian_title").withStyle(ChatFormatting.BOLD).withColor(0xFFE680),
				Component.translatable("butterbot.care.guardian_sub", bot.getName()));
		bot.tellOwner(Component.translatable("butterbot.care.guardian_line").withStyle(ChatFormatting.GOLD));
		BotAchievements.check(bot);
		return true;
	}

	/** Called for any player about to die: a bot of theirs close by may save them. */
	static boolean rescue(ServerPlayer player, DamageSource source) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || player.isSpectator() || player.isCreative()) {
			return false;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).has(DataComponents.DEATH_PROTECTION)) {
				// Their own totem saves them; Guardian Angel stays ready for next time.
				return false;
			}
		}
		ServerLevel level = player.level();
		for (ButterBotEntity bot : level.getEntitiesOfClass(ButterBotEntity.class, player.getBoundingBox().inflate(48.0D),
				bot -> bot.isAlive() && bot.isOwnedBy(player))) {
			if (bot.companion().tryRescue(bot, level, player)) {
				return true;
			}
		}
		return false;
	}

	/** The owner killed something near their bots: they learn from it too (half the XP), and a boss gets a party. */
	static void onOwnerKill(ServerLevel level, ServerPlayer player, LivingEntity victim) {
		for (ButterBotEntity bot : level.getEntitiesOfClass(ButterBotEntity.class, player.getBoundingBox().inflate(24.0D),
				bot -> bot.isAlive() && bot.isOwnedBy(player))) {
			bot.gainWorkXp(Math.max(1, BotLevels.killXp(victim) / 2));
			if (BotLevels.isBoss(victim)) {
				bot.playGesture(ButterBotEntity.GESTURE_HIGH_FIVE);
				bot.emote(ButterBotEntity.EMOTE_HEART);
				bot.say(Component.translatable("butterbot.life.owner_boss", victim.getName()));
				level.sendParticles(ParticleTypes.FIREWORK, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 40, 1.0D, 0.8D, 1.0D, 0.15D);
				bot.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 1.0F);
			}
		}
	}

	/** After the owner respawns, the bots that are around (in any dimension) say something kind, and those close heal them a little. */
	static void onOwnerRespawn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		int spoke = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (ButterBotEntity bot : level.getEntities(EntityTypeTest.forClass(ButterBotEntity.class), bot -> bot.isAlive() && bot.isOwnedBy(player))) {
				if (spoke++ < 3) {
					bot.tellOwner(Component.translatable("butterbot.care.respawn"));
				}
				if (level == player.level() && bot.distanceToSqr(player) < 64.0D * 64.0D) {
					player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0), bot);
				}
			}
		}
	}

	// ------------------------------------------------------------------ warnings and reactions

	private void warnings(ButterBotEntity bot, ServerLevel level, Player owner, long now) {
		if (!(owner instanceof ServerPlayer player) || bot.distanceToSqr(owner) > 32.0D * 32.0D) {
			return;
		}
		if (now >= creeperWarnAt) {
			Vec3 look = owner.getLookAngle().multiply(1.0D, 0.0D, 1.0D);
			for (Creeper creeper : level.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(6.0D, 3.0D, 6.0D))) {
				Vec3 to = creeper.position().subtract(owner.position()).multiply(1.0D, 0.0D, 1.0D);
				boolean behind = look.lengthSqr() > 1.0E-4D && to.lengthSqr() > 1.0E-4D && look.normalize().dot(to.normalize()) < 0.0D;
				if (behind && (creeper.getSwellDir() > 0 || creeper.getTarget() == owner)) {
					creeperWarnAt = now + 200L;
					player.sendSystemMessage(Component.translatable("butterbot.care.creeper", bot.getName()).withStyle(ChatFormatting.BOLD)
							.withColor(0xFF5A5A), true);
					bot.emote(ButterBotEntity.EMOTE_SURPRISE);
					bot.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0F, 1.6F);
					break;
				}
			}
		}
		if (now >= wardenWarnAt && !level.getEntitiesOfClass(Warden.class, owner.getBoundingBox().inflate(32.0D)).isEmpty()) {
			wardenWarnAt = now + 1200L;
			player.sendSystemMessage(Component.translatable("butterbot.care.warden", bot.getName()).withColor(0x2FD4C8), true);
			bot.emote(ButterBotEntity.EMOTE_SURPRISE);
		}
	}

	/** Anger when something hurts the owner, and a cheer when the owner's experience level goes up. */
	private void reactToOwner(ButterBotEntity bot, ServerLevel level, Player owner, double distance, long now) {
		int hurtAt = owner.getLastHurtByMobTimestamp();
		if (owner.getLastHurtByMob() != null && hurtAt != ownerHurtSeen && owner.tickCount - hurtAt < 40) {
			ownerHurtSeen = hurtAt;
			if (now >= ownerHurtReactAt && distance < NEAR_SQ) {
				ownerHurtReactAt = now + 600L;
				bot.emote(ButterBotEntity.EMOTE_ANGER);
				bot.say(Component.translatable("butterbot.care.owner_hurt"));
			}
		}
		int xpLevel = owner.experienceLevel;
		if (ownerXpLevel >= 0 && xpLevel > ownerXpLevel && distance < NEAR_SQ && now >= cheerReadyAt) {
			cheerReadyAt = now + 600L;
			bot.playGesture(ButterBotEntity.GESTURE_WAVE);
			level.sendParticles(ParticleTypes.NOTE, bot.getX(), bot.getY() + 2.2D, bot.getZ(), 5, 0.4D, 0.2D, 0.4D, 1.0D);
			bot.say(Component.translatable(xpLevel % 10 == 0 ? "butterbot.life.owner_level_big" : "butterbot.life.owner_level", xpLevel));
		}
		ownerXpLevel = xpLevel;
	}

	// ------------------------------------------------------------------ everyday life

	private static boolean isFree(ButterBotEntity bot) {
		BotTask task = bot.getTask();
		return (task == BotTask.NONE || task == BotTask.FOLLOW) && !bot.isJobActive() && !bot.isFighting();
	}

	private void dailyLife(ButterBotEntity bot, ServerLevel level, Player owner, long now) {
		long today = day(level);
		if (owner.isSleeping()) {
			lullaby(bot, level);
			return;
		}
		lullabyNotes = 0;
		// Good morning: once a day, in the morning (6:00 to 12:00).
		if (today != greetDay && level.isBrightOutside() && level.getOverworldClockTime() % 24000L < 6000L) {
			greetDay = today;
			morning(bot, level, owner, today);
			return;
		}
		if (!isFree(bot)) {
			return;
		}
		String here = biomeName(level, bot.blockPosition());
		if (!here.equals(biome)) {
			biome = here;
			if (!here.isEmpty() && now >= biomeReadyAt) {
				biomeReadyAt = now + BIOME_COOLDOWN;
				bot.say(Component.translatable("butterbot.biome." + here));
				bot.emote(ButterBotEntity.EMOTE_SURPRISE);
				return;
			}
		}
		if (weather(bot, level)) {
			return;
		}
		if (bot.tickCount % 60 == 0 && now >= treasureReadyAt && spotTreasure(bot, level)) {
			treasureReadyAt = now + 600L;
			return;
		}
		boolean night = level.isDarkOutside();
		if (night && bot.tickCount % 20 == 0) {
			fireflies(bot, level, here);
		}
		if (now >= sneezeReadyAt && here.equals("flowers") || now >= sneezeReadyAt && here.equals("cherry")) {
			if (bot.getRandom().nextInt(90) == 0) {
				sneezeReadyAt = now + 1200L;
				sneeze(bot, level);
				return;
			}
		}
		if (night && now >= yawnReadyAt && bot.getRandom().nextInt(120) == 0) {
			yawnReadyAt = now + 2400L;
			bot.playGesture(ButterBotEntity.GESTURE_STRETCH);
			bot.say(Component.translatable("butterbot.life.yawn"));
			return;
		}
		if (night && now >= starReadyAt && !level.isRaining() && level.canSeeSky(bot.blockPosition().above())
				&& bot.getRandom().nextInt(60) == 0) {
			starReadyAt = now + 1800L;
			shootingStar(bot, level);
			return;
		}
		if (bot.isNeko() && now >= spinReadyAt && bot.idleAction() == ButterBotEntity.ACTION_NONE && bot.getRandom().nextInt(180) == 0) {
			spinReadyAt = now + 1800L;
			bot.playGesture(ButterBotEntity.GESTURE_SPIN);
			bot.say(Component.translatable("butterbot.life.tail_chase"));
			return;
		}
		if (now >= petGreetReadyAt && bot.getRandom().nextInt(60) == 0) {
			greetPet(bot, level, owner, now);
		}
	}

	/**
	 * The first morning of each day: good morning with the days together, a flower for a close owner, and every
	 * hundred days a birthday with fireworks and a cake.
	 */
	private void morning(ButterBotEntity bot, ServerLevel level, Player owner, long today) {
		int days = (int) (today - claimDay + 1L);
		bot.playGesture(ButterBotEntity.GESTURE_WAVE);
		if (days > 1 && (days - 1) % BIRTHDAY_EVERY_DAYS == 0 && birthdayDay != today) {
			birthdayDay = today;
			birthday(bot, level, owner, days - 1);
			return;
		}
		bot.say(Component.translatable("butterbot.life.morning", days));
		if (bot.getBond() >= 90) {
			Item flower = FLOWERS[bot.getRandom().nextInt(FLOWERS.length)];
			give(bot, owner, new ItemStack(flower));
			level.sendParticles(ParticleTypes.HEART, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 3, 0.3D, 0.2D, 0.3D, 0.0D);
			bot.tellOwner(Component.translatable("butterbot.life.flower", new ItemStack(flower).getHoverName()).withColor(0xFF9AC8));
		}
	}

	private static void birthday(ButterBotEntity bot, ServerLevel level, Player owner, int days) {
		for (int i = 0; i < 5; i++) {
			level.sendParticles(ParticleTypes.FIREWORK, bot.getX(), bot.getY() + 2.0D + i * 0.6D, bot.getZ(), 25, 1.0D, 0.5D, 1.0D, 0.12D);
		}
		level.sendParticles(ParticleTypes.NOTE, bot.getX(), bot.getY() + 2.4D, bot.getZ(), 10, 0.6D, 0.3D, 0.6D, 1.0D);
		bot.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 1.0F);
		bot.playSound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 1.0F, 1.0F);
		bot.playGesture(ButterBotEntity.GESTURE_WINGS);
		give(bot, owner, new ItemStack(Items.CAKE));
		bot.addBond(5);
		if (owner instanceof ServerPlayer player) {
			ButterBotEntity.showTitle(player, Component.translatable("butterbot.life.birthday_title", bot.getName()).withColor(0xFF9AC8),
					Component.translatable("butterbot.life.birthday_sub", days));
		}
		bot.tellOwner(Component.translatable("butterbot.life.birthday", days).withColor(0xFF9AC8));
	}

	private static void give(ButterBotEntity bot, Player owner, ItemStack stack) {
		if (!owner.getInventory().add(stack) && !stack.isEmpty()) {
			ItemEntity drop = new ItemEntity(bot.level(), owner.getX(), owner.getY() + 0.5D, owner.getZ(), stack);
			drop.setDefaultPickUpDelay();
			bot.level().addFreshEntity(drop);
		}
		bot.playSound(SoundEvents.ITEM_PICKUP, 0.6F, 1.2F);
	}

	/** A remark when rain or a thunderstorm starts. True if it said something. */
	private boolean weather(ButterBotEntity bot, ServerLevel level) {
		boolean raining = level.isRaining();
		boolean thundering = level.isThundering();
		if (!weatherKnown) {
			weatherKnown = true;
			wasRaining = raining;
			wasThundering = thundering;
			return false;
		}
		boolean said = false;
		if (thundering && !wasThundering) {
			bot.say(Component.translatable("butterbot.life.thunder"));
			said = true;
		} else if (raining && !wasRaining) {
			bot.say(Component.translatable("butterbot.life.rain"));
			said = true;
		} else if (!raining && wasRaining && level.isBrightOutside()) {
			bot.say(Component.translatable("butterbot.life.rain_stop"));
			said = true;
		}
		wasRaining = raining;
		wasThundering = thundering;
		return said;
	}

	/** Diamonds, emeralds or ancient debris in sight within six blocks: "!", the gem in a thought bubble and a sparkle on it. */
	private boolean spotTreasure(ButterBotEntity bot, ServerLevel level) {
		BlockPos center = bot.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6, -4, -6), center.offset(6, 4, 6))) {
			BlockState state = level.getBlockState(pos);
			Item gem = state.is(BlockItemTags.DIAMOND_ORES.block()) ? Items.DIAMOND
					: state.is(BlockItemTags.EMERALD_ORES.block()) ? Items.EMERALD
					: state.is(Blocks.ANCIENT_DEBRIS) ? Items.ANCIENT_DEBRIS : null;
			if (gem == null || treasureSeen.contains(pos.asLong()) || !exposed(level, pos) || !bot.hasLineOfSight(pos)) {
				continue;
			}
			if (treasureSeen.size() > 256) {
				treasureSeen.clear();
			}
			treasureSeen.add(pos.asLong());
			bot.emote(ButterBotEntity.EMOTE_SURPRISE);
			bot.think(gem);
			bot.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
			level.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 20, 0.4D, 0.4D, 0.4D, 0.0D);
			bot.say(Component.translatable("butterbot.life.treasure", new ItemStack(gem).getHoverName(), pos.getX(), pos.getY(), pos.getZ()));
			bot.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.6F);
			return true;
		}
		return false;
	}

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
			if (level.getBlockState(pos.relative(direction)).isAir()) {
				return true;
			}
		}
		return false;
	}

	private static void fireflies(ButterBotEntity bot, ServerLevel level, String biome) {
		if (biome.equals("desert") || biome.equals("snow") || biome.equals("ocean") || biome.equals("badlands") || biome.equals("end")
				|| biome.equals("nether") || biome.equals("crimson") || biome.equals("warped") || biome.equals("soul_valley")
				|| biome.equals("basalt") || !level.canSeeSky(bot.blockPosition().above())) {
			return;
		}
		level.sendParticles(ParticleTypes.FIREFLY, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 2, 3.0D, 1.0D, 3.0D, 0.0D);
	}

	private static void sneeze(ButterBotEntity bot, ServerLevel level) {
		Vec3 look = bot.getLookAngle();
		Vec3 nose = bot.getEyePosition().add(look.scale(0.4D));
		level.sendParticles(ParticleTypes.SNEEZE, nose.x, nose.y, nose.z, 6, 0.1D, 0.05D, 0.1D, 0.02D);
		bot.playSound(SoundEvents.PANDA_SNEEZE, 0.8F, 1.6F);
		bot.playGesture(ButterBotEntity.GESTURE_STARTLE);
		bot.say(Component.translatable("butterbot.life.sneeze"));
	}

	/** A streak of light across the night sky over the bot, and a wish. */
	private static void shootingStar(ButterBotEntity bot, ServerLevel level) {
		double angle = bot.getRandom().nextDouble() * Mth.TWO_PI;
		Vec3 start = bot.position().add(Math.cos(angle) * 18.0D, 28.0D + bot.getRandom().nextDouble() * 6.0D, Math.sin(angle) * 18.0D);
		Vec3 direction = new Vec3(-Math.cos(angle), -0.35D, -Math.sin(angle)).normalize();
		for (int i = 0; i < 24; i++) {
			Vec3 at = start.add(direction.scale(i * 0.9D));
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			if (i % 4 == 0) {
				level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
			}
		}
		bot.getLookControl().setLookAt(start.x, start.y, start.z);
		bot.playSound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.4F, 2.0F);
		bot.say(Component.translatable("butterbot.life.shooting_star"));
	}

	/** Now and then the bot greets one of the owner's tame pets nearby, with hearts between them. */
	private void greetPet(ButterBotEntity bot, ServerLevel level, Player owner, long now) {
		for (TamableAnimal pet : level.getEntitiesOfClass(TamableAnimal.class, bot.getBoundingBox().inflate(8.0D),
				pet -> pet.isTame() && pet.isOwnedBy(owner) && pet.isAlive())) {
			petGreetReadyAt = now + 2400L;
			bot.getLookControl().setLookAt(pet);
			bot.playGesture(ButterBotEntity.GESTURE_WAVE);
			Vec3 from = bot.getEyePosition();
			Vec3 to = pet.getEyePosition();
			for (int i = 1; i < 6; i++) {
				Vec3 at = from.add(to.subtract(from).scale(i / 6.0D));
				level.sendParticles(ParticleTypes.HEART, at.x, at.y + 0.3D, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
			bot.say(Component.translatable("butterbot.life.pet_hello", pet.getName()));
			return;
		}
	}

	/** Soft notes over the bot while the owner falls asleep. */
	private void lullaby(ButterBotEntity bot, ServerLevel level) {
		if (lullabyNotes >= 6) {
			return;
		}
		lullabyNotes++;
		level.sendParticles(ParticleTypes.NOTE, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 1, 0.3D, 0.1D, 0.3D, bot.getRandom().nextDouble());
		float[] tune = {0.7F, 0.8F, 0.9F, 0.8F, 0.7F, 0.6F};
		bot.playSound(SoundEvents.NOTE_BLOCK_HARP.value(), 0.35F, tune[lullabyNotes - 1]);
		if (lullabyNotes == 1) {
			bot.say(Component.translatable("butterbot.life.lullaby"));
		}
	}

	/** A short name for the kind of place, for the bot's remark; empty for places it has nothing to say about. */
	static String biomeName(ServerLevel level, BlockPos pos) {
		String path = level.getBiome(pos).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
		return switch (path) {
			case "desert" -> "desert";
			case "badlands", "eroded_badlands", "wooded_badlands" -> "badlands";
			case "jungle", "sparse_jungle", "bamboo_jungle" -> "jungle";
			case "swamp", "mangrove_swamp" -> "swamp";
			case "mushroom_fields" -> "mushroom";
			case "cherry_grove" -> "cherry";
			case "flower_forest", "meadow", "sunflower_plains" -> "flowers";
			case "pale_garden" -> "pale_garden";
			case "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "frozen_peaks", "grove", "snowy_beach", "frozen_river" -> "snow";
			case "jagged_peaks", "stony_peaks", "windswept_hills", "windswept_gravelly_hills" -> "mountain";
			case "deep_dark" -> "deep_dark";
			case "lush_caves" -> "lush_caves";
			case "dripstone_caves" -> "dripstone";
			case "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
				 "frozen_ocean", "deep_frozen_ocean" -> "ocean";
			case "beach" -> "beach";
			case "savanna", "savanna_plateau", "windswept_savanna" -> "savanna";
			case "nether_wastes" -> "nether";
			case "crimson_forest" -> "crimson";
			case "warped_forest" -> "warped";
			case "soul_sand_valley" -> "soul_valley";
			case "basalt_deltas" -> "basalt";
			case "the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens" -> "end";
			default -> "";
		};
	}
}
