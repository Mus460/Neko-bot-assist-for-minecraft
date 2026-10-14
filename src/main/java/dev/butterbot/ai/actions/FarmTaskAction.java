package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.TreeFeller;
import dev.butterbot.ai.Walker;
import dev.butterbot.ai.job.JobStorage;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Farming around the Bot Base: harvests ripe crops and replants them, picks berries and cocoa,
 * cuts sugar cane above the bottom block, takes melons and pumpkins, fells grown trees and plants
 * their saplings, and sows seeds on empty farmland. The harvest goes into the base storage.
 * The job keeps going until the bot is told to stop.
 */
public class FarmTaskAction implements BotAction {

	public static final int RADIUS = 16;
	private static final int VERTICAL = 4;
	private static final int SCAN_INTERVAL = 40;
	private static final int WORK_TICKS = 6;
	private static final double REACH_SQ = 3.0D * 3.0D;
	private static final double BASE_REACH_SQ = 2.8D * 2.8D;
	private static final int MAX_STUCK_TICKS = 200;
	private static final int UNLOAD_WHEN_IDLE_ITEMS = 32;
	private static final long BASE_FULL_RETRY_TICKS = 20L * 60L;
	/** How often a grandmaster farmer helps the crops around it grow. */
	private static final int GROWTH_INTERVAL = 100;
	private static final int GROWTH_CROPS = 3;

	private enum Work {CROP, WART, COCOA, BERRY, CANE, FRUIT, TREE, SOW}

	private final Walker walker = new Walker();
	@Nullable
	private BlockPos target;
	@Nullable
	private Work work;
	private int scanCooldown;
	private int workTicks;
	private boolean unloading;
	/** Game time until which a full base is not tried again. */
	private long baseFullUntil;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 50;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.FARM && !bot.isReturnRequested();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		walker.reset(bot);
		target = null;
		work = null;
		scanCooldown = 0;
		workTicks = 0;
		unloading = false;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel level)) {
			return;
		}
		if (bot.getMiner().isDigging()) {
			bot.getMiner().tickDig();
			return;
		}

		if (bot.tickCount % GROWTH_INTERVAL == 0 && bot.stats().growthAura()) {
			growthAura(bot, level);
		}

		BlockPos storage = bot.getFarmStorage();
		boolean canUnload = storage != null && level.getGameTime() >= baseFullUntil;
		if (canUnload && bot.freeSlots() <= 2) {
			unloading = true;
		}
		if (unloading) {
			unload(bot, level, storage);
			return;
		}

		if (target == null) {
			if (--scanCooldown > 0) {
				return;
			}
			scanCooldown = SCAN_INTERVAL;
			findWork(bot, level);
			if (target == null) {
				bot.setBotState(BotState.IDLE);
				if (canUnload && harvestCarried(bot) >= UNLOAD_WHEN_IDLE_ITEMS) {
					unloading = true;
				}
				return;
			}
			walker.reset(bot);
		}

		BlockPos pos = target;
		if (work == null || !isWork(bot, level, pos, work)) {
			target = null;
			scanCooldown = 0;
			return;
		}

		if (bot.distanceToSqr(Vec3.atCenterOf(pos)) <= REACH_SQ) {
			bot.getNavigation().stop();
			bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
			bot.setBotState(BotState.MINING);
			if (workTicks++ == 0) {
				bot.showTool(new ItemStack(work == Work.TREE ? Items.IRON_AXE : Items.IRON_HOE), 20);
			}
			if (workTicks < WORK_TICKS) {
				return;
			}
			workTicks = 0;
			doWork(bot, level, pos, work);
			target = null;
			scanCooldown = 0;
			return;
		}

		bot.setBotState(BotState.MOVING_TO_TARGET);
		if (!walker.walk(bot, Vec3.atBottomCenterOf(pos), 1.0D, false, MAX_STUCK_TICKS)) {
			bot.blacklistTarget(pos);
			target = null;
			scanCooldown = 0;
			walker.reset(bot);
		}
	}

	private void unload(ButterBotEntity bot, ServerLevel level, @Nullable BlockPos pos) {
		if (pos == null) {
			unloading = false;
			return;
		}
		bot.setBotState(BotState.RETURNING);
		if (bot.distanceToSqr(Vec3.atCenterOf(pos)) > BASE_REACH_SQ) {
			if (!walker.walk(bot, Vec3.atBottomCenterOf(pos), 1.1D, true, MAX_STUCK_TICKS)) {
				if (!bot.teleportNear(pos)) {
					bot.tellOwner(Component.translatable("butterbot.msg.cant_reach_base"));
					unloading = false;
				}
				walker.reset(bot);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		Container storage = JobStorage.open(level, pos);
		if (storage == null) {
			unloading = false;
			return;
		}
		if (!bot.depositInto(storage, pos, ButterBotEntity.KEEP_PLANTABLES)) {
			bot.tellOwner(Component.translatable("butterbot.msg.base_full"));
			baseFullUntil = bot.level().getGameTime() + BASE_FULL_RETRY_TICKS;
		}
		unloading = false;
		walker.reset(bot);
	}

	/** Items in the backpack that would be unloaded (the seeds and saplings it keeps do not count). */
	private static int harvestCarried(ButterBotEntity bot) {
		int count = 0;
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!ButterBotEntity.isPlantable(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Grandmaster farmer: every few seconds a few young crops in the field grow one stage. */
	private static void growthAura(ButterBotEntity bot, ServerLevel level) {
		BlockPos center = bot.getFarmCenter();
		int radius = bot.getFarmRadius();
		int grown = 0;
		for (int attempt = 0; attempt < 24 && grown < GROWTH_CROPS; attempt++) {
			BlockPos pos = center.offset(bot.getRandom().nextInt(radius * 2 + 1) - radius, bot.getRandom().nextInt(VERTICAL * 2 + 1) - VERTICAL,
					bot.getRandom().nextInt(radius * 2 + 1) - radius);
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
				level.setBlock(pos, crop.getStateForAge(crop.getAge(state) + 1), Block.UPDATE_CLIENTS);
				level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 4, 0.3D, 0.3D, 0.3D, 0.0D);
				grown++;
			}
		}
	}

	// ---------------------------------------------------------------- finding work

	private void findWork(ButterBotEntity bot, ServerLevel level) {
		BlockPos center = bot.getFarmCenter();
		int radius = bot.getFarmRadius();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		double best = Double.MAX_VALUE;
		target = null;
		work = null;
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				for (int y = -VERTICAL; y <= VERTICAL; y++) {
					cursor.setWithOffset(center, x, y, z);
					if (!level.isLoaded(cursor)) {
						continue;
					}
					Work found = workAt(bot, level, cursor);
					if (found == null || bot.isBlacklisted(cursor)) {
						continue;
					}
					double distance = bot.distanceToSqr(Vec3.atCenterOf(cursor));
					if (distance < best) {
						best = distance;
						target = cursor.immutable();
						work = found;
					}
				}
			}
		}
	}

	private boolean isWork(ButterBotEntity bot, ServerLevel level, BlockPos pos, Work expected) {
		return workAt(bot, level, pos) == expected;
	}

	@Nullable
	private Work workAt(ButterBotEntity bot, ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) {
			return crop.isMaxAge(state) ? Work.CROP : null;
		}
		if (block instanceof NetherWartBlock) {
			return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE ? Work.WART : null;
		}
		if (block instanceof CocoaBlock) {
			return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE ? Work.COCOA : null;
		}
		if (block instanceof SweetBerryBushBlock) {
			return state.getValue(SweetBerryBushBlock.AGE) >= 2 ? Work.BERRY : null;
		}
		if (state.is(Blocks.SUGAR_CANE)) {
			// Cut everything above the bottom block so the cane grows back.
			BlockState below = level.getBlockState(pos.below());
			return below.is(Blocks.SUGAR_CANE) && !level.getBlockState(pos.below(2)).is(Blocks.SUGAR_CANE) ? Work.CANE : null;
		}
		if (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				if (level.getBlockState(pos.relative(direction)).getBlock() instanceof AttachedStemBlock) {
					return Work.FRUIT;
				}
			}
			return null;
		}
		if (TreeFeller.isTreeBase(level, pos)) {
			return Work.TREE;
		}
		if (level.getBlockState(pos.below()).is(Blocks.FARMLAND) && state.isAir() && seedFor(bot, level, pos) != null) {
			return Work.SOW;
		}
		return null;
	}

	/** A seed from the backpack that can grow at {@code pos}, preferring whatever grows next to it. */
	@Nullable
	private static Item seedFor(ButterBotEntity bot, ServerLevel level, BlockPos pos) {
		List<Item> candidates = new ArrayList<>();
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock
					&& blockItem.getBlock().defaultBlockState().canSurvive(level, pos)) {
				candidates.add(stack.getItem());
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			Item neighbour = level.getBlockState(pos.relative(direction)).getBlock().asItem();
			if (candidates.contains(neighbour)) {
				return neighbour;
			}
		}
		return candidates.getFirst();
	}

	// ---------------------------------------------------------------- doing the work

	private void doWork(ButterBotEntity bot, ServerLevel level, BlockPos pos, Work job) {
		BlockState state = level.getBlockState(pos);
		switch (job) {
			case CROP -> {
				CropBlock crop = (CropBlock) state.getBlock();
				harvestAndReplant(bot, level, pos, state, crop.getStateForAge(0));
			}
			case WART -> harvestAndReplant(bot, level, pos, state, state.setValue(NetherWartBlock.AGE, 0));
			case COCOA -> harvestAndReplant(bot, level, pos, state, state.setValue(CocoaBlock.AGE, 0));
			case BERRY -> {
				int age = state.getValue(SweetBerryBushBlock.AGE);
				int count = 1 + level.getRandom().nextInt(2) + (age >= SweetBerryBushBlock.MAX_AGE ? 1 : 0);
				if (bonusHarvest(bot)) {
					count *= 2;
				}
				bot.collectLoot(new ItemStack(Items.SWEET_BERRIES, count), pos, true);
				level.setBlock(pos, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
				level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 1.0F);
			}
			case CANE -> {
				BlockPos top = pos;
				while (level.getBlockState(top.above()).is(Blocks.SUGAR_CANE)) {
					top = top.above();
				}
				for (BlockPos cut = top; cut.getY() >= pos.getY(); cut = cut.below()) {
					bot.harvestBlock(level, cut, level.getBlockState(cut), true);
				}
			}
			case FRUIT -> bot.harvestBlock(level, pos, state, true);
			case TREE -> {
				bot.harvestBlock(level, pos, state, true);
				TreeFeller.fell(bot, level, pos, state);
			}
			case SOW -> {
				Item seed = seedFor(bot, level, pos);
				if (seed == null || !bot.placeBlockFromInventory(pos, seed)) {
					return;
				}
				level.playSound(null, pos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
				bot.gainWorkXp(1);
				return;
			}
		}
		bot.countHarvest();
		bot.gainWorkXp(1);
	}

	/** Collects the drops of a ripe plant and puts a young one back, using one of its own seeds. */
	private static void harvestAndReplant(ButterBotEntity bot, ServerLevel level, BlockPos pos, BlockState ripe, BlockState young) {
		Item seed = ripe.getBlock().asItem();
		List<ItemStack> drops = Block.getDrops(ripe, level, pos, null, bot, ItemStack.EMPTY);
		boolean replanted = false;
		for (ItemStack drop : drops) {
			if (!replanted && drop.is(seed)) {
				drop.shrink(1);
				replanted = true;
			}
		}
		if (!replanted) {
			replanted = bot.takeItem(seed, 1) > 0;
		}
		level.levelEvent(2001, pos, Block.getId(ripe));
		level.setBlock(pos, replanted ? young : Blocks.AIR.defaultBlockState(), 3);
		boolean bonus = bonusHarvest(bot);
		for (ItemStack drop : drops) {
			if (bonus && !drop.isEmpty()) {
				bot.collectLoot(drop.copy(), pos, true);
			}
			bot.collectLoot(drop, pos, true);
		}
	}

	/** Green Thumb skill and farmer mastery: sometimes a plant gives twice as much. */
	private static boolean bonusHarvest(ButterBotEntity bot) {
		float chance = bot.stats().cropBonusChance();
		return chance > 0.0F && bot.getRandom().nextFloat() < chance;
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.FARM;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
