package dev.butterbot.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Small server-side particle touches that only make the bot look alive: breath in the cold, bubbles under water,
 * a puff on landing, hearts beside a loved owner, a red pulse when badly hurt and smoke after long work. Looks only:
 * nothing here touches blocks or entities.
 */
public final class BotFx {

	private static final DustParticleOptions HURT_DUST = new DustParticleOptions(0xFF2020, 0.9F);
	/** Ticks of nonstop work before the bot starts to "overheat". */
	private static final int OVERHEAT_TICKS = 20 * 60 * 5;

	/** What the effects need to remember between ticks; weak keys so removed bots are forgotten. */
	private static final class State {
		double lastFall;
		int workTicks;
	}

	private static final Map<ButterBotEntity, State> STATES = new WeakHashMap<>();

	private BotFx() {
	}

	/** Called once per server tick from the bot's tick. */
	public static void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel level) || bot.isInvisible()) {
			return;
		}
		State state = STATES.computeIfAbsent(bot, b -> new State());
		int t = bot.tickCount;

		// Landing: fall distance is reset the tick the bot touches down.
		if (bot.onGround() && bot.fallDistance == 0.0D && state.lastFall > 1.0D && !bot.isInWater()) {
			BlockState ground = level.getBlockState(bot.blockPosition().below());
			if (!ground.isAir()) {
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), bot.getX(), bot.getY() + 0.05D, bot.getZ(),
						(int) Math.min(12.0D, 3.0D + state.lastFall * 2.0D), 0.3D, 0.02D, 0.3D, 0.05D);
			}
		}
		state.lastFall = bot.fallDistance;

		BotTask task = bot.getTask();
		state.workTicks = task != BotTask.NONE && task != BotTask.FOLLOW ? state.workTicks + 1 : 0;
		if (state.workTicks > OVERHEAT_TICKS && t % 100 == 0 && bot.getRandom().nextInt(3) == 0) {
			level.sendParticles(ParticleTypes.SMOKE, bot.getX(), bot.getY() + bot.getBbHeight() + 0.2D, bot.getZ(), 4, 0.15D, 0.05D, 0.15D, 0.02D);
		}

		if (bot.isUnderWater()) {
			if (t % 15 == 0) {
				Vec3 mouth = bot.getEyePosition().add(bot.getLookAngle().scale(0.3D));
				level.sendParticles(ParticleTypes.BUBBLE, mouth.x, mouth.y - 0.1D, mouth.z, 2, 0.05D, 0.05D, 0.05D, 0.05D);
			}
		} else if (t % 50 == 0) {
			BlockPos head = BlockPos.containing(bot.getEyePosition());
			if (level.getBiome(head).value().coldEnoughToSnow(head, level.getSeaLevel())) {
				Vec3 mouth = bot.getEyePosition().add(bot.getLookAngle().scale(0.35D));
				level.sendParticles(ParticleTypes.WHITE_SMOKE, mouth.x, mouth.y - 0.1D, mouth.z, 2, 0.03D, 0.03D, 0.03D, 0.005D);
			}
		}

		if (bot.getHealth() < bot.getMaxHealth() * 0.25F && t % 10 == 0) {
			// Pulse: bigger bursts every other half second.
			level.sendParticles(HURT_DUST, bot.getX(), bot.getY() + 0.8D, bot.getZ(), t % 20 == 0 ? 6 : 2, 0.35D, 0.5D, 0.35D, 0.0D);
		}

		if (t % 80 == 0 && bot.getBond() >= BotBond.FOND && bot.getRandom().nextInt(3) == 0) {
			Player owner = bot.getOwner();
			double dx = bot.getX() - bot.xo;
			double dz = bot.getZ() - bot.zo;
			if (owner != null && owner.level() == level && bot.distanceToSqr(owner) < 4.0D * 4.0D && dx * dx + dz * dz > 0.002D) {
				level.sendParticles(ParticleTypes.HEART, bot.getX(), bot.getY() + bot.getBbHeight() + 0.3D, bot.getZ(), 1, 0.2D, 0.1D, 0.2D, 0.0D);
			}
		}
	}
}
