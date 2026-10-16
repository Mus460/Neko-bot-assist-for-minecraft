package dev.butterbot.entity;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What a bot can do, worked out from its level, its skills and its job mastery. The server applies
 * it and the panel shows it, both from this one place so they always agree.
 */
public record BotStats(double maxHealth, double attackDamage, double armor, float digSpeed, double speedMultiplier,
					   int backpackSlots, int fortune, int veinSize, int scanRadius, int smeltPerCycle, int farmRadiusBonus,
					   int magnetRadius, boolean swimmer, boolean feather, float regenPerSecond, float oreBonusChance,
					   float gatherBonusChance, float cropBonusChance, float sweepFraction, boolean growthAura,
					   float twinChance, double armorToughness, double knockbackResistance, double attackKnockback,
					   float damageTaken, float thorns, float lifeSteal, int attackCooldown, int level) {

	public static final int BASE_SCAN_RADIUS = 20;
	public static final double BASE_ARMOR = 4.0D;
	public static final double BASE_SPEED = 0.3D;
	public static final int MAX_BACKPACK = 54;
	/** Fortune III from levels + Big Luck II + miner grandmaster. */
	public static final int MAX_FORTUNE = 6;
	/** Legend (level 30): health, attack, digging and healing all half as much again. */
	private static final float LEGEND_BONUS = 1.5F;

	/** Abilities that come with the level by themselves (no skill point needed): one at every level from 11 to 30. */
	public enum Ability {
		TOUGH_HIDE(11), FIRE_IMMUNE(12), SPRINTER(13), POISON_IMMUNE(14), THORNS(15), STRONG_REGEN(16), IRON_GRIP(17),
		GOLDEN_HAND(18), HEALER(19), BLAST_RESISTANT(20), FAST_STRIKES(21), GUARDIAN_AURA(22), HAWK_EYES(23), STEEL_SKIN(24),
		SECOND_WIND(25), LIFE_STEAL(26), STORM_SLASH(27), NEKO_ROAR(28), LUCKY_PAWS(29), LEGEND(30);

		private final int level;

		Ability(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public Component displayName() {
			return Component.translatable("butterbot.ability." + id());
		}

		public Component description() {
			return Component.translatable("butterbot.ability." + id() + ".desc");
		}

		/** The ability that opens exactly at {@code level}, if any. */
		@Nullable
		public static Ability at(int level) {
			for (Ability ability : values()) {
				if (ability.level == level) {
					return ability;
				}
			}
			return null;
		}
	}

	/** Super powers, on top of the abilities: they fight with fire, frost and meteors, and only ever touch monsters. */
	public enum Power {
		FIRE_AURA(20), FROST_NOVA(25), METEOR_RAIN(30);

		private final int level;

		Power(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public Component displayName() {
			return Component.translatable("butterbot.power." + id());
		}

		public Component description() {
			return Component.translatable("butterbot.power." + id() + ".desc");
		}

		/** The power that opens exactly at {@code level}, if any. */
		@Nullable
		public static Power at(int level) {
			for (Power power : values()) {
				if (power.level == level) {
					return power;
				}
			}
			return null;
		}
	}

	public boolean has(Ability ability) {
		return level >= ability.level();
	}

	public boolean hasPower(Power power) {
		return level >= power.level();
	}

	public static BotStats of(int level, BotProgress progress) {
		return of(level, progress.ranks(), progress.mastery());
	}

	public static BotStats of(int level, int[] ranks, int[] masteryXp) {
		int lv = Math.max(1, Math.min(BotLevels.MAX_LEVEL, level));
		int miner = masteryRank(masteryXp, BotJob.MINER);
		int gatherer = masteryRank(masteryXp, BotJob.GATHERER);
		int guard = masteryRank(masteryXp, BotJob.GUARD);
		int farmer = masteryRank(masteryXp, BotJob.FARMER);
		int rancher = masteryRank(masteryXp, BotJob.RANCHER);
		boolean isLegend = lv >= Ability.LEGEND.level();
		float legend = isLegend ? LEGEND_BONUS : 1.0F;
		boolean lucky = lv >= Ability.LUCKY_PAWS.level();

		double maxHealth = (BotLevels.maxHealth(lv) + 6.0D * r(ranks, BotSkill.TOUGH) + 4.0D * guard) * legend;
		double attack = (BotLevels.attackDamage(lv) + 1.5D * r(ranks, BotSkill.STRENGTH)) * (1.0D + 0.08D * guard) * legend;
		double armor = BASE_ARMOR + 4.0D * r(ranks, BotSkill.ARMOR) + BotLevels.armorBonus(lv)
				+ (lv >= Ability.TOUGH_HIDE.level() ? 4.0D : 0.0D);
		double toughness = BotLevels.toughness(lv) + (isLegend ? 4.0D : 0.0D);
		float digSpeed = BotLevels.digSpeed(lv) * (1.0F + 0.10F * r(ranks, BotSkill.QUICK_DIG) + 0.05F * miner) * legend;
		double speed = (1.0D + 0.08D * r(ranks, BotSkill.SWIFT) + BotLevels.speedBonus(lv)
				+ (lv >= Ability.SPRINTER.level() ? 0.15D : 0.0D)) * (isLegend ? 1.1D : 1.0D);
		boolean ironGrip = lv >= Ability.IRON_GRIP.level();
		double knockbackResistance = isLegend ? 1.0D : Math.min(1.0D, BotLevels.knockbackResistance(lv) + (ironGrip ? 0.4D : 0.0D));
		double attackKnockback = 0.4D + (ironGrip ? 1.0D : 0.0D);
		float damageTaken = (lv >= Ability.STEEL_SKIN.level() ? 0.75F : 1.0F) * (isLegend ? 0.5F : 1.0F);
		float thorns = lv >= Ability.THORNS.level() ? (isLegend ? 0.5F : 0.3F) : 0.0F;
		float lifeSteal = lv >= Ability.LIFE_STEAL.level() ? 0.25F : 0.0F;
		int cooldown = BotLevels.attackCooldown(lv);
		if (lv >= Ability.FAST_STRIKES.level()) {
			cooldown = Math.max(2, cooldown / 2);
		}
		int backpack = Math.min(MAX_BACKPACK, BotLevels.backpackSize(lv) + 9 * r(ranks, BotSkill.POCKETS));
		int fortune = Math.min(MAX_FORTUNE, BotLevels.fortune(lv) + r(ranks, BotSkill.LUCKY) + (miner >= BotProgress.MAX_MASTERY ? 1 : 0));
		int vein = BotLevels.veinSize(lv) + 16 * r(ranks, BotSkill.BIG_VEIN);
		int scan = BASE_SCAN_RADIUS + 4 * r(ranks, BotSkill.ORE_SENSE);
		int smelt = BotLevels.smeltPerCycle(lv) + r(ranks, BotSkill.FAST_SMELT);
		int farmBonus = 4 * r(ranks, BotSkill.WIDE_FARM);
		int magnet = 2 * r(ranks, BotSkill.MAGNET);
		float regen = BotLevels.regenPerSecond(lv) * (lv >= Ability.STRONG_REGEN.level() ? 2.0F : 1.0F) * legend;
		float oreBonus = (lv >= Ability.GOLDEN_HAND.level() ? 0.10F : 0.0F) + (lucky ? 0.25F : 0.0F);
		float gatherBonus = 0.06F * gatherer + (lucky ? 0.25F : 0.0F);
		float cropBonus = 0.15F * r(ranks, BotSkill.GREEN_THUMB) + 0.08F * farmer + (lucky ? 0.25F : 0.0F);
		int sweepRank = r(ranks, BotSkill.SWEEP);
		float sweep = sweepRank <= 0 ? 0.0F : sweepRank == 1 ? 0.4F : 0.7F;
		if (lv >= Ability.STORM_SLASH.level()) {
			sweep = Math.max(sweep, 0.6F);
		}
		return new BotStats(maxHealth, attack, armor, digSpeed, speed, backpack, fortune, vein, scan, smelt, farmBonus, magnet,
				r(ranks, BotSkill.SWIMMER) > 0, r(ranks, BotSkill.FEATHER) > 0, regen, oreBonus, gatherBonus, cropBonus, sweep,
				farmer >= BotProgress.MAX_MASTERY, 0.08F * rancher, toughness, knockbackResistance, attackKnockback, damageTaken,
				thorns, lifeSteal, cooldown, lv);
	}

	private static int r(int[] ranks, BotSkill skill) {
		return BotProgress.rankIn(ranks, skill);
	}

	private static int masteryRank(int[] masteryXp, BotJob job) {
		int index = BotProgress.jobIndex(job);
		return index >= 0 && index < masteryXp.length ? BotProgress.masteryRank(masteryXp[index]) : 0;
	}

	/** Dig speed as "+X%" compared with a level 1 bot without skills. */
	public int digSpeedPercent() {
		return Math.round((digSpeed - 1.0F) * 100.0F);
	}
}
