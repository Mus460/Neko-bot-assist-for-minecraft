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
					   float gatherBonusChance, float cropBonusChance, float sweepFraction, boolean growthAura, int level) {

	public static final int BASE_SCAN_RADIUS = 20;
	public static final double BASE_ARMOR = 4.0D;
	public static final double BASE_SPEED = 0.3D;
	public static final int MAX_BACKPACK = 54;
	/** Fortune III from levels + Big Luck II + miner grandmaster. */
	public static final int MAX_FORTUNE = 6;
	private static final float LEGEND_BONUS = 1.1F;

	/** Abilities that come with the level by themselves (no skill point needed). */
	public enum Ability {
		FIRE_IMMUNE(12), POISON_IMMUNE(14), STRONG_REGEN(16), GOLDEN_HAND(18), BLAST_RESISTANT(20), SECOND_WIND(25), LEGEND(30);

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

	public boolean has(Ability ability) {
		return level >= ability.level();
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
		float legend = lv >= Ability.LEGEND.level() ? LEGEND_BONUS : 1.0F;

		double maxHealth = (BotLevels.maxHealth(lv) + 6.0D * r(ranks, BotSkill.TOUGH) + 4.0D * guard) * legend;
		double attack = (BotLevels.attackDamage(lv) + 1.5D * r(ranks, BotSkill.STRENGTH)) * (1.0D + 0.08D * guard) * legend;
		double armor = BASE_ARMOR + 4.0D * r(ranks, BotSkill.ARMOR);
		float digSpeed = BotLevels.digSpeed(lv) * (1.0F + 0.10F * r(ranks, BotSkill.QUICK_DIG) + 0.05F * miner) * legend;
		double speed = 1.0D + 0.08D * r(ranks, BotSkill.SWIFT);
		int backpack = Math.min(MAX_BACKPACK, BotLevels.backpackSize(lv) + 9 * r(ranks, BotSkill.POCKETS));
		int fortune = Math.min(MAX_FORTUNE, BotLevels.fortune(lv) + r(ranks, BotSkill.LUCKY) + (miner >= BotProgress.MAX_MASTERY ? 1 : 0));
		int vein = BotLevels.veinSize(lv) + 16 * r(ranks, BotSkill.BIG_VEIN);
		int scan = BASE_SCAN_RADIUS + 4 * r(ranks, BotSkill.ORE_SENSE);
		int smelt = BotLevels.smeltPerCycle(lv) + r(ranks, BotSkill.FAST_SMELT);
		int farmBonus = 4 * r(ranks, BotSkill.WIDE_FARM);
		int magnet = 2 * r(ranks, BotSkill.MAGNET);
		float regen = BotLevels.regenPerSecond(lv) * (lv >= Ability.STRONG_REGEN.level() ? 2.0F : 1.0F);
		float oreBonus = lv >= Ability.GOLDEN_HAND.level() ? 0.10F : 0.0F;
		float gatherBonus = 0.06F * gatherer;
		float cropBonus = 0.15F * r(ranks, BotSkill.GREEN_THUMB) + 0.08F * farmer;
		int sweepRank = r(ranks, BotSkill.SWEEP);
		float sweep = sweepRank <= 0 ? 0.0F : sweepRank == 1 ? 0.4F : 0.7F;
		return new BotStats(maxHealth, attack, armor, digSpeed, speed, backpack, fortune, vein, scan, smelt, farmBonus, magnet,
				r(ranks, BotSkill.SWIMMER) > 0, r(ranks, BotSkill.FEATHER) > 0, regen, oreBonus, gatherBonus, cropBonus, sweep,
				farmer >= BotProgress.MAX_MASTERY, lv);
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
