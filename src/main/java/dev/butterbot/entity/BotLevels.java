package dev.butterbot.entity;

import net.minecraft.tags.BlockItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a bot grows with work. Every mined block, harvested crop, crafted batch, smelted item and
 * defeated monster gives work XP (harder work gives more); each level makes the bot faster, tougher
 * and luckier and gives a skill point. Levels 1 to 10 come quickly, 11 to 30 take real work.
 * The bonuses from skills and job mastery are added on top in {@link BotStats}.
 */
public final class BotLevels {

	public static final int MAX_LEVEL = 30;
	/** Levels up to here grow fast; after it every level adds less but takes longer. */
	private static final int EARLY_LEVELS = 10;
	public static final int BASE_BACKPACK = 18;
	public static final int MAX_BACKPACK = 36;

	/** Work XP needed to go from {@code level} to the next one. */
	public static int xpToNext(int level) {
		int late = Math.max(0, level - EARLY_LEVELS);
		return 40 + level * 30 + 8 * late * late;
	}

	public static double maxHealth(int level) {
		return 30.0D + (early(level) - 1) * 4.0D + late(level) * 2.0D;
	}

	/** Base attack damage; the sword in its hand adds 5 (iron), 6 (diamond) or 7 (netherite). */
	public static double attackDamage(int level) {
		return 2.0D + (early(level) - 1) * 0.5D + late(level) * 0.25D;
	}

	/** Digging speed multiplier: 1.0 at level 1, 2.35 at level 10, 3.35 at level 30. */
	public static float digSpeed(int level) {
		return 1.0F + (early(level) - 1) * 0.15F + late(level) * 0.05F;
	}

	public static int backpackSize(int level) {
		return level >= 7 ? MAX_BACKPACK : level >= 4 ? 27 : BASE_BACKPACK;
	}

	/** Fortune level applied to everything the bot breaks. */
	public static int fortune(int level) {
		return level >= 10 ? 3 : level >= 8 ? 2 : level >= 5 ? 1 : 0;
	}

	/** How many extra connected ore blocks are mined together with the one the bot breaks. */
	public static int veinSize(int level) {
		return level >= 9 ? 64 : level >= 6 ? 24 : level >= 3 ? 8 : 0;
	}

	/** 0 = iron tools, 1 = diamond, 2 = netherite. */
	public static int toolTier(int level) {
		return level >= 9 ? 2 : level >= 5 ? 1 : 0;
	}

	public static int attackCooldown(int level) {
		return Math.max(6, 14 - level);
	}

	/** Items smelted every half second by the bot's built-in furnace. */
	public static int smeltPerCycle(int level) {
		return 1 + Math.min(level, 20) / 4;
	}

	/** Health regained every second while the bot has not been hurt for a while. */
	public static float regenPerSecond(int level) {
		return 0.5F + early(level) * 0.1F + late(level) * 0.05F;
	}

	/** Work XP for breaking a block: rare ores are worth much more than stone or dirt. */
	public static int blockXp(BlockState state) {
		if (state.is(Blocks.ANCIENT_DEBRIS)) {
			return 15;
		}
		if (state.is(BlockItemTags.DIAMOND_ORES.block())) {
			return 10;
		}
		if (state.is(BlockItemTags.EMERALD_ORES.block())) {
			return 8;
		}
		if (state.is(BlockItemTags.GOLD_ORES.block()) || state.is(BlockItemTags.LAPIS_ORES.block())) {
			return 4;
		}
		if (state.is(BlockItemTags.IRON_ORES.block()) || state.is(BlockItemTags.REDSTONE_ORES.block())) {
			return 3;
		}
		if (state.is(BlockItemTags.COAL_ORES.block()) || state.is(BlockItemTags.COPPER_ORES.block())
				|| state.is(Blocks.NETHER_QUARTZ_ORE)) {
			return 2;
		}
		return 1;
	}

	/** Work XP for defeating a monster: tougher monsters are worth more, bosses a lot. */
	public static int killXp(LivingEntity victim) {
		if (victim instanceof EnderDragon || victim instanceof WitherBoss) {
			return 200;
		}
		return Math.max(2, Math.min(100, Math.round(victim.getMaxHealth() / 4.0F)));
	}

	private static int early(int level) {
		return Math.max(1, Math.min(level, EARLY_LEVELS));
	}

	private static int late(int level) {
		return Math.max(0, Math.min(level, MAX_LEVEL) - EARLY_LEVELS);
	}

	private BotLevels() {
	}
}
