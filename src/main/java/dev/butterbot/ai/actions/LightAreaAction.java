package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lights up an area so monsters can't spawn in it: finds every dark spot a zombie could spawn on,
 * plans torches so each one is covered, walks around placing them, then checks again (light can be
 * blocked by walls) and fills the gaps.
 */
public class LightAreaAction implements BotAction {

	public static final int DEFAULT_RADIUS = 24;
	public static final int MAX_RADIUS = 48;
	private static final int VERTICAL_DOWN = 4;
	private static final int VERTICAL_UP = 6;
	/** Columns scanned per tick, so a big area doesn't cause a lag spike. */
	private static final int COLUMNS_PER_TICK = 6;
	/** A torch gives light 14, so spots up to 13 blocks away get at least 1. Stay well inside that. */
	private static final int COVER_DISTANCE = 11;
	private static final double REACH_SQ = 4.5D * 4.5D;
	private static final double SHORTCUT_SQ = 64.0D * 64.0D;
	private static final int MAX_PASSES = 3;
	/** Light updates take a moment; wait before checking for spots that are still dark. */
	private static final int SETTLE_TICKS = 40;

	private enum Phase { SCAN, PLACE, SETTLE }

	private final Walker walker = new Walker();
	private final List<BlockPos> darkSpots = new ArrayList<>();
	private final List<BlockPos> plan = new ArrayList<>();
	private Phase phase = Phase.SCAN;
	private int scanX;
	private int pass;
	private int settleTicks;
	private int skipped;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.LIGHT && bot.getLightCenter() != null;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		walker.reset(bot);
		startScan(bot);
		pass = 0;
		skipped = 0;
	}

	private void startScan(ButterBotEntity bot) {
		phase = Phase.SCAN;
		scanX = -bot.getLightRadius();
		darkSpots.clear();
		plan.clear();
	}

	@Override
	public void tick(ButterBotEntity bot) {
		BlockPos center = bot.getLightCenter();
		if (center == null || !(bot.level() instanceof ServerLevel level)) {
			return;
		}
		switch (phase) {
			case SCAN -> scan(bot, level, center);
			case PLACE -> place(bot, level);
			case SETTLE -> {
				bot.getNavigation().stop();
				if (++settleTicks >= SETTLE_TICKS) {
					startScan(bot);
				}
			}
		}
	}

	private void scan(ButterBotEntity bot, ServerLevel level, BlockPos center) {
		int radius = bot.getLightRadius();
		for (int i = 0; i < COLUMNS_PER_TICK && scanX <= radius; i++, scanX++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (scanX * scanX + dz * dz > radius * radius) {
					continue;
				}
				for (int dy = -VERTICAL_DOWN; dy <= VERTICAL_UP; dy++) {
					BlockPos pos = center.offset(scanX, dy, dz);
					if (isDarkSpawnSpot(level, pos)) {
						darkSpots.add(pos);
					}
				}
			}
		}
		if (scanX > radius) {
			buildPlan(bot, level, center);
		}
	}

	/** Greedy cover: take the nearest dark spot that can hold a torch, mark everything it lights as done. */
	private void buildPlan(ButterBotEntity bot, ServerLevel level, BlockPos center) {
		darkSpots.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
		Set<BlockPos> covered = new HashSet<>();
		List<BlockPos> torches = new ArrayList<>();
		for (BlockPos spot : darkSpots) {
			if (covered.contains(spot) || !canHoldTorch(level, spot)) {
				continue;
			}
			torches.add(spot);
			for (BlockPos other : darkSpots) {
				if (Math.abs(other.getX() - spot.getX()) + Math.abs(other.getY() - spot.getY())
						+ Math.abs(other.getZ() - spot.getZ()) <= COVER_DISTANCE) {
					covered.add(other);
				}
			}
		}
		darkSpots.clear();

		// Visit them in a sensible order: always the nearest one next.
		BlockPos from = bot.blockPosition();
		while (!torches.isEmpty()) {
			BlockPos nearest = torches.getFirst();
			for (BlockPos pos : torches) {
				if (pos.distSqr(from) < nearest.distSqr(from)) {
					nearest = pos;
				}
			}
			torches.remove(nearest);
			plan.add(nearest);
			from = nearest;
		}

		if (plan.isEmpty()) {
			finish(bot);
			return;
		}
		phase = Phase.PLACE;
		bot.setBotState(BotState.MOVING_TO_TARGET);
		walker.reset(bot);
	}

	private void place(ButterBotEntity bot, ServerLevel level) {
		if (plan.isEmpty()) {
			if (++pass >= MAX_PASSES) {
				finish(bot);
			} else {
				phase = Phase.SETTLE;
				settleTicks = 0;
			}
			return;
		}
		BlockPos target = plan.getFirst();
		if (!isDarkSpawnSpot(level, target) || !canHoldTorch(level, target)) {
			plan.removeFirst();
			walker.reset(bot);
			return;
		}
		double distanceSq = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target));
		if (distanceSq > REACH_SQ) {
			if (distanceSq > SHORTCUT_SQ) {
				bot.teleportNear(target);
				walker.reset(bot);
				return;
			}
			if (!walker.walk(bot, Vec3.atBottomCenterOf(target), 1.1D, false, 60)) {
				// Unreachable (a closed cave, a roof...): leave it rather than digging into builds.
				plan.removeFirst();
				skipped++;
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(target));
		// Torches from the backpack are used first; otherwise the bot makes its own.
		bot.takeItem(Items.TORCH, 1);
		level.setBlock(target, Blocks.TORCH.defaultBlockState(), 3);
		level.playSound(null, target, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.7F, 1.2F);
		bot.showTool(new ItemStack(Items.TORCH), 10);
		bot.countTorchPlaced();
		plan.removeFirst();
		walker.reset(bot);
	}

	private void finish(ButterBotEntity bot) {
		int placed = bot.getTorchesPlaced();
		if (placed == 0 && skipped == 0) {
			bot.tellOwner(Component.translatable("butterbot.msg.light_already", bot.getLightRadius()));
		} else {
			bot.tellOwner(Component.translatable("butterbot.msg.light_done", placed, bot.getLightRadius()));
			if (skipped > 0) {
				bot.tellOwner(Component.translatable("butterbot.msg.light_skipped", skipped));
			}
		}
		bot.gainWorkXp(Math.max(1, placed / 4));
		bot.playTaskCompleteSound();
		bot.clearTask();
	}

	/** Where a monster could spawn right now: an empty, dark spot on top of a spawnable block. */
	static boolean isDarkSpawnSpot(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState feet = level.getBlockState(pos);
		if (!feet.canBeReplaced() || !feet.getCollisionShape(level, pos).isEmpty() || !level.getFluidState(pos).isEmpty()) {
			return false;
		}
		BlockPos head = pos.above();
		if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty() || !level.getFluidState(head).isEmpty()) {
			return false;
		}
		BlockPos below = pos.below();
		return level.getBlockState(below).isValidSpawn(level, below, EntityTypes.ZOMBIE)
				&& level.getBrightness(LightLayer.BLOCK, pos) == 0;
	}

	private static boolean canHoldTorch(ServerLevel level, BlockPos pos) {
		return Blocks.TORCH.defaultBlockState().canSurvive(level, pos);
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.LIGHT;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
