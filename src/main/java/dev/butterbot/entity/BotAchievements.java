package dev.butterbot.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.Locale;

/**
 * Achievements a bot earns over its life, kept as bits in {@link BotProgress.Stat#ACHIEVEMENTS} (so they are saved
 * and reach the panel with the other counters). Each one brings a chat line with a chime and fireworks, a little bond
 * and some work XP (stars, past level 30).
 */
public final class BotAchievements {

	private static final int REWARD_XP = 150;
	private static final int REWARD_BOND = 2;

	public enum Achievement {
		FIRST_BLOOD, HUNTER, SLAYER, BOSS_HUNTER, WARDEN_SLAYER, WITHER_SLAYER, DRAGON_SLAYER, COMBO_MASTER, RAMPAGE, GODLIKE,
		MINER, FARMER, CRAFTER, SOULMATE, LEGEND, STARBORN, MYTHIC, GUARDIAN_ANGEL, SURVIVOR, POWER_USER;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public int bit() {
			return 1 << ordinal();
		}

		public Component displayName() {
			return Component.translatable("butterbot.achievement." + id());
		}

		public Component description() {
			return Component.translatable("butterbot.achievement." + id() + ".desc");
		}

		public boolean in(int bits) {
			return (bits & bit()) != 0;
		}
	}

	/** Unlocks every achievement whose counter has been reached. */
	static void check(ButterBotEntity bot) {
		BotProgress progress = bot.getProgress();
		int kills = progress.stat(BotProgress.Stat.KILLS);
		int stars = progress.stat(BotProgress.Stat.STARS);
		unlockIf(bot, Achievement.FIRST_BLOOD, kills >= 1);
		unlockIf(bot, Achievement.HUNTER, kills >= 100);
		unlockIf(bot, Achievement.SLAYER, kills >= 1000);
		unlockIf(bot, Achievement.BOSS_HUNTER, progress.stat(BotProgress.Stat.BOSSES) >= 1);
		unlockIf(bot, Achievement.COMBO_MASTER, progress.stat(BotProgress.Stat.BEST_COMBO) >= 20);
		unlockIf(bot, Achievement.RAMPAGE, progress.stat(BotProgress.Stat.BEST_STREAK) >= 10);
		unlockIf(bot, Achievement.GODLIKE, progress.stat(BotProgress.Stat.BEST_STREAK) >= 15);
		unlockIf(bot, Achievement.MINER, progress.stat(BotProgress.Stat.MINED) >= 1000);
		unlockIf(bot, Achievement.FARMER, progress.stat(BotProgress.Stat.HARVESTED) >= 1000);
		unlockIf(bot, Achievement.CRAFTER, progress.stat(BotProgress.Stat.CRAFTED) >= 100);
		unlockIf(bot, Achievement.SOULMATE, bot.getBond() >= BotBond.MAX);
		unlockIf(bot, Achievement.LEGEND, bot.getBotLevel() >= BotLevels.MAX_LEVEL);
		unlockIf(bot, Achievement.STARBORN, stars >= 1);
		unlockIf(bot, Achievement.MYTHIC, stars >= BotLevels.MAX_STARS);
		unlockIf(bot, Achievement.GUARDIAN_ANGEL, progress.stat(BotProgress.Stat.OWNER_SAVES) >= 1);
		unlockIf(bot, Achievement.POWER_USER, progress.stat(BotProgress.Stat.POWERS_USED) >= 100);
	}

	static void onBossKill(ButterBotEntity bot, LivingEntity boss) {
		if (boss instanceof Warden) {
			unlock(bot, Achievement.WARDEN_SLAYER);
		} else if (boss instanceof WitherBoss) {
			unlock(bot, Achievement.WITHER_SLAYER);
		} else if (boss instanceof EnderDragon) {
			unlock(bot, Achievement.DRAGON_SLAYER);
		}
	}

	private static void unlockIf(ButterBotEntity bot, Achievement achievement, boolean reached) {
		if (reached) {
			unlock(bot, achievement);
		}
	}

	static void unlock(ButterBotEntity bot, Achievement achievement) {
		BotProgress progress = bot.getProgress();
		int bits = progress.stat(BotProgress.Stat.ACHIEVEMENTS);
		if (achievement.in(bits) || bot.level().isClientSide()) {
			return;
		}
		// The bit goes in before the reward: the reward's XP may complete other achievements.
		progress.setStat(BotProgress.Stat.ACHIEVEMENTS, bits | achievement.bit());
		int count = Integer.bitCount(bits | achievement.bit());
		if (bot.level() instanceof ServerLevel level) {
			level.sendParticles(ParticleTypes.FIREWORK, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 25, 0.4D, 0.5D, 0.4D, 0.1D);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, bot.getX(), bot.getY() + 1.0D, bot.getZ(), 10, 0.4D, 0.6D, 0.4D, 0.0D);
		}
		if (bot.getOwner() instanceof ServerPlayer owner) {
			owner.sendSystemMessage(Component.translatable("butterbot.achievement.unlocked", bot.getName(), achievement.displayName(),
					count, Achievement.values().length).withStyle(ChatFormatting.GOLD)
					.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
					.append(achievement.description().copy().withStyle(ChatFormatting.GRAY)));
			owner.level().playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
					SoundSource.PLAYERS, 0.6F, 1.2F);
		}
		bot.emote(ButterBotEntity.EMOTE_CHECK);
		bot.addBond(REWARD_BOND);
		bot.gainWorkXp(REWARD_XP);
	}

	public static int count(int bits) {
		int count = 0;
		for (Achievement achievement : Achievement.values()) {
			if (achievement.in(bits)) {
				count++;
			}
		}
		return count;
	}

	private BotAchievements() {
	}
}
