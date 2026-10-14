package dev.butterbot.entity;

/**
 * How a bot grows with work. Every mined block, harvested crop, crafted batch, smelted item and
 * defeated monster gives work XP; each level makes the bot faster, tougher and luckier.
 */
public final class BotLevels {

	public static final int MAX_LEVEL = 10;
	public static final int BASE_BACKPACK = 18;
	public static final int MAX_BACKPACK = 36;

	/** Work XP needed to go from {@code level} to the next one. */
	public static int xpToNext(int level) {
		return 40 + level * 30;
	}

	public static double maxHealth(int level) {
		return 30.0D + (level - 1) * 4.0D;
	}

	/** Base attack damage; the sword in its hand adds 5 (iron), 6 (diamond) or 7 (netherite). */
	public static double attackDamage(int level) {
		return 2.0D + (level - 1) * 0.5D;
	}

	/** Digging speed multiplier: 1.0 at level 1, 2.35 at level 10. */
	public static float digSpeed(int level) {
		return 1.0F + (level - 1) * 0.15F;
	}

	public static int digSpeedPercent(int level) {
		return Math.round((digSpeed(level) - 1.0F) * 100.0F);
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
		return 1 + level / 4;
	}

	/** Health regained every second while the bot has not been hurt for a while. */
	public static float regenPerSecond(int level) {
		return 0.5F + level * 0.1F;
	}

	private BotLevels() {
	}
}
