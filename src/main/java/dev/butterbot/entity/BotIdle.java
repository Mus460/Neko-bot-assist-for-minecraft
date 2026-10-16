package dev.butterbot.entity;

import dev.butterbot.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a bot does when it has nothing to do, server side: it sits down next to an owner who stands still for a
 * while (and stretches now and then), sleeps beside the owner's bed (with a "good morning" and sometimes a small
 * gift, like a cat), dances to a jukebox, jumps at thunder, shakes itself dry after rain, welcomes the owner back
 * ("Okaeri!") and high-fives other bots of the same owner. Poses are synced as {@link #action()}; one-off moves
 * are gestures sent to watching clients.
 */
final class BotIdle {

	static final int NONE = 0;
	static final int SIT = 1;
	static final int SLEEP = 2;
	static final int DANCE = 3;

	/** The owner must stand still this long before the bot sits down, in ticks. */
	private static final int SIT_AFTER_TICKS = 200;
	private static final double SIT_RANGE_SQ = 6.0D * 6.0D;
	private static final double BED_RANGE_SQ = 16.0D * 16.0D;
	private static final double JUKEBOX_RANGE_SQ = 10.0D * 10.0D;
	/** The owner away this long (game time) counts as "back home" when seen again. */
	private static final long WELCOME_AFTER_TICKS = 3600L;
	private static final double WELCOME_RANGE_SQ = 20.0D * 20.0D;
	private static final int HIGH_FIVE_COOLDOWN = 2400;
	/** Wet for this long (rain or water) before it needs a shake once dry. */
	private static final int WET_TICKS = 40;

	private int action = NONE;
	private Vec3 ownerLastPos;
	private int ownerStillTicks;
	private int stretchTimer = 400;
	private long ownerSeenAt;
	private int highFiveCooldown = HIGH_FIVE_COOLDOWN / 4;
	private int wetTicks;
	private int dryTicks;
	private int lastBoltId;
	private BlockPos jukebox;

	int action() {
		return action;
	}

	long ownerSeenAt() {
		return ownerSeenAt;
	}

	void setOwnerSeenAt(long gameTime) {
		ownerSeenAt = gameTime;
	}

	void tick(ButterBotEntity bot, ServerLevel level) {
		if (highFiveCooldown > 0) {
			highFiveCooldown--;
		}
		tickWet(bot);
		if (bot.tickCount % 5 == 0) {
			checkThunder(bot, level);
		}
		if (bot.tickCount % 10 != 0) {
			return;
		}
		Player owner = bot.getOwner();
		boolean ownerHere = owner != null && owner.level() == level && owner.isAlive();
		welcome(bot, level, ownerHere ? owner : null);

		boolean free = isFree(bot);
		// Walking somewhere (following, fetching...) ends sitting and dancing; a sleeper only lies down once there.
		if (!free || bot.getNavigation().isInProgress()) {
			setAction(bot, NONE);
			if (!free) {
				return;
			}
		}
		if (bot.tickCount % 40 == 0) {
			jukebox = findPlayingJukebox(bot, level);
		}
		if (ownerHere && owner.isSleeping() && bot.distanceToSqr(owner) < BED_RANGE_SQ) {
			sleepByBed(bot, owner);
			return;
		}
		if (action == SLEEP) {
			wakeUp(bot, level, owner);
		}
		if (jukebox != null) {
			setAction(bot, DANCE);
			return;
		}
		if (action == DANCE) {
			setAction(bot, NONE);
		}
		sitByOwner(bot, ownerHere ? owner : null);
		if (action == NONE && bot.tickCount % 40 == 0) {
			tryHighFive(bot, level);
		}
	}

	/** Nothing to do: no order, no running job, no fight. */
	private static boolean isFree(ButterBotEntity bot) {
		BotTask task = bot.getTask();
		return (task == BotTask.NONE || task == BotTask.FOLLOW) && !bot.isJobActive() && !bot.isFighting() && !bot.isPassenger()
				&& !bot.isInWater();
	}

	private void setAction(ButterBotEntity bot, int next) {
		if (action == next) {
			return;
		}
		action = next;
		if (next != NONE) {
			bot.getNavigation().stop();
		}
		bot.syncIdleAction(next);
	}

	/** Sits down once the owner has stood still for a while next to it; gets up when the owner moves on. */
	private void sitByOwner(ButterBotEntity bot, Player owner) {
		if (owner == null || bot.distanceToSqr(owner) > SIT_RANGE_SQ) {
			ownerStillTicks = 0;
			ownerLastPos = null;
			setAction(bot, NONE);
			return;
		}
		Vec3 pos = owner.position();
		if (ownerLastPos != null && pos.distanceToSqr(ownerLastPos) < 0.02D) {
			ownerStillTicks += 10;
		} else {
			ownerStillTicks = 0;
			setAction(bot, NONE);
		}
		ownerLastPos = pos;
		if (ownerStillTicks >= SIT_AFTER_TICKS && !bot.getNavigation().isInProgress()) {
			setAction(bot, SIT);
		}
		if (action == SIT) {
			stretchTimer -= 10;
			if (stretchTimer <= 0) {
				stretchTimer = 400 + bot.getRandom().nextInt(500);
				bot.playGesture(ButterBotEntity.GESTURE_STRETCH);
			}
		}
	}

	/** Walks up to the owner's bed and lies down beside it while the owner sleeps. */
	private void sleepByBed(ButterBotEntity bot, Player owner) {
		if (action == SLEEP) {
			return;
		}
		if (bot.distanceToSqr(owner) > 3.0D * 3.0D) {
			if (!bot.getNavigation().isInProgress()) {
				bot.getNavigation().moveTo(owner, 1.0D);
			}
			return;
		}
		setAction(bot, SLEEP);
	}

	/** The owner got up: a stretch and a "good morning" in the morning, and now and then a small gift. */
	private void wakeUp(ButterBotEntity bot, ServerLevel level, Player owner) {
		setAction(bot, NONE);
		if (!level.isBrightOutside()) {
			return;
		}
		bot.playGesture(ButterBotEntity.GESTURE_STRETCH);
		bot.say(Component.translatable("butterbot.idle.morning"));
		bot.speak(ModSounds.VOICE_HELLO);
		if (owner != null && bot.getRandom().nextInt(10) < 3) {
			BlockPos at = bot.blockPosition();
			boolean gave = bot.dropFromGiftLootTable(level, BuiltInLootTables.CAT_MORNING_GIFT,
					(server, stack) -> server.addFreshEntity(new ItemEntity(server, at.getX() + 0.5D, at.getY() + 0.3D, at.getZ() + 0.5D, stack)));
			if (gave) {
				bot.say(Component.translatable("butterbot.idle.gift"));
			}
		}
	}

	/** A jukebox playing a song within 10 blocks, looking through the block entities of the chunks around. */
	private static BlockPos findPlayingJukebox(ButterBotEntity bot, ServerLevel level) {
		ChunkPos center = bot.chunkPosition();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					if (entity instanceof JukeboxBlockEntity jukebox && jukebox.getSongPlayer().isPlaying()
							&& entity.getBlockPos().distToCenterSqr(bot.position()) < JUKEBOX_RANGE_SQ) {
						return entity.getBlockPos();
					}
				}
			}
		}
		return null;
	}

	/** Lightning nearby makes it jump (and squeak now and then). */
	private void checkThunder(ButterBotEntity bot, ServerLevel level) {
		if (!level.isThundering()) {
			return;
		}
		for (LightningBolt bolt : level.getEntitiesOfClass(LightningBolt.class, bot.getBoundingBox().inflate(48.0D))) {
			if (bolt.tickCount < 10 && bolt.getId() != lastBoltId) {
				lastBoltId = bolt.getId();
				bot.playGesture(ButterBotEntity.GESTURE_STARTLE);
				if (bot.getRandom().nextBoolean()) {
					bot.say(Component.translatable("butterbot.idle.startle." + bot.getRandom().nextInt(2)));
				}
				return;
			}
		}
	}

	/** After rain or a swim, a good shake once it is dry again. */
	private void tickWet(ButterBotEntity bot) {
		if (bot.isInWaterOrRain()) {
			wetTicks = Math.min(wetTicks + 1, WET_TICKS * 4);
			dryTicks = 0;
			return;
		}
		if (wetTicks >= WET_TICKS && ++dryTicks >= 20 && action != SLEEP) {
			wetTicks = 0;
			dryTicks = 0;
			bot.playGesture(ButterBotEntity.GESTURE_SHAKE);
		} else if (wetTicks < WET_TICKS && dryTicks > 0) {
			wetTicks = 0;
		}
	}

	/** Seeing the owner again after a long time away: a wave, hearts and "Okaeri!". */
	private void welcome(ButterBotEntity bot, ServerLevel level, Player owner) {
		long now = level.getGameTime();
		if (owner == null || bot.distanceToSqr(owner) > WELCOME_RANGE_SQ) {
			return;
		}
		if (ownerSeenAt > 0L && now - ownerSeenAt >= WELCOME_AFTER_TICKS) {
			bot.getLookControl().setLookAt(owner);
			bot.playGesture(ButterBotEntity.GESTURE_WAVE);
			bot.say(Component.translatable("butterbot.idle.okaeri." + bot.getRandom().nextInt(3)));
			bot.speak(ModSounds.VOICE_HELLO);
			level.sendParticles(ParticleTypes.HEART, bot.getX(), bot.getY() + 2.0D, bot.getZ(), 4, 0.4D, 0.2D, 0.4D, 0.0D);
		}
		ownerSeenAt = now;
	}

	/** Two free bots of the same owner standing close give each other a high five. */
	private void tryHighFive(ButterBotEntity bot, ServerLevel level) {
		if (highFiveCooldown > 0 || bot.getOwnerUuid() == null) {
			return;
		}
		AABB around = bot.getBoundingBox().inflate(2.5D);
		for (ButterBotEntity other : level.getEntitiesOfClass(ButterBotEntity.class, around,
				candidate -> candidate != bot && candidate.isAlive() && bot.getOwnerUuid().equals(candidate.getOwnerUuid()))) {
			BotIdle otherIdle = other.idle();
			if (otherIdle.highFiveCooldown > 0 || otherIdle.action != NONE || !isFree(other)) {
				continue;
			}
			highFiveCooldown = HIGH_FIVE_COOLDOWN + bot.getRandom().nextInt(1200);
			otherIdle.highFiveCooldown = highFiveCooldown;
			bot.getLookControl().setLookAt(other);
			other.getLookControl().setLookAt(bot);
			bot.playGesture(ButterBotEntity.GESTURE_HIGH_FIVE);
			other.playGesture(ButterBotEntity.GESTURE_HIGH_FIVE);
			Vec3 middle = bot.position().add(other.position()).scale(0.5D);
			level.sendParticles(ParticleTypes.CRIT, middle.x, middle.y + 1.9D, middle.z, 8, 0.15D, 0.15D, 0.15D, 0.1D);
			level.playSound(null, middle.x, middle.y + 1.5D, middle.z, SoundEvents.NOTE_BLOCK_HAT.value(), bot.getSoundSource(), 0.6F, 1.6F);
			return;
		}
	}
}
