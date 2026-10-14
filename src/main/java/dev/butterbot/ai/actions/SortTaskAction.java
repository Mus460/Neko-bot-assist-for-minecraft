package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.ItemSorter;
import dev.butterbot.ai.Walker;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tidies the chests and barrels around the Bot Base: the bot visits each one, then stacks and groups
 * everything by kind. Whatever sits in the base storage is moved into the chests as well.
 */
public class SortTaskAction implements BotAction {

	public static final int RADIUS = 12;
	private static final double REACH_SQ = 2.6D * 2.6D;
	private static final int LOOK_TICKS = 8;
	private static final int MAX_STUCK_TICKS = 200;

	private final Walker walker = new Walker();
	private final List<BlockPos> toVisit = new ArrayList<>();
	private final Set<BlockPos> visited = new LinkedHashSet<>();
	private boolean planned;
	private int lookTicks;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.SORT;
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		walker.reset(bot);
		toVisit.clear();
		visited.clear();
		planned = false;
		lookTicks = 0;
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (!(bot.level() instanceof ServerLevel level)) {
			return;
		}
		if (!planned) {
			planned = true;
			toVisit.addAll(findContainers(level, center(bot)));
			Vec3 from = bot.position();
			toVisit.sort(Comparator.comparingDouble(pos -> from.distanceToSqr(Vec3.atCenterOf(pos))));
			if (toVisit.isEmpty()) {
				bot.tellOwner(Component.translatable("butterbot.msg.no_chests", RADIUS));
				bot.clearTask();
			}
			return;
		}

		if (toVisit.isEmpty()) {
			finish(bot, level);
			return;
		}

		BlockPos current = toVisit.getFirst();
		if (containerAt(level, current) == null) {
			toVisit.removeFirst();
			return;
		}
		if (bot.distanceToSqr(Vec3.atCenterOf(current)) > REACH_SQ) {
			bot.setBotState(BotState.MOVING_TO_TARGET);
			if (!walker.walk(bot, Vec3.atBottomCenterOf(current), 1.0D, false, MAX_STUCK_TICKS)) {
				toVisit.removeFirst();
				walker.reset(bot);
			}
			return;
		}

		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(current));
		if (lookTicks == 0) {
			level.playSound(null, current, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
			bot.showTool(ItemStack.EMPTY, 0);
		}
		if (++lookTicks < LOOK_TICKS) {
			return;
		}
		lookTicks = 0;
		level.playSound(null, current, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
		visited.add(current);
		// A double chest is one storage: both halves count as visited.
		BlockPos partner = partnerOf(level, current);
		if (partner != null) {
			visited.add(partner);
			toVisit.remove(partner);
		}
		toVisit.removeFirst();
		walker.reset(bot);
	}

	private void finish(ButterBotEntity bot, ServerLevel level) {
		List<Container> containers = new ArrayList<>();
		for (BlockPos pos : orderForSorting(level, visited)) {
			Container container = containerAt(level, pos);
			if (container != null) {
				containers.add(container);
			}
		}
		if (containers.isEmpty()) {
			bot.tellOwner(Component.translatable("butterbot.msg.no_chests", RADIUS));
			bot.clearTask();
			return;
		}
		BotBaseBlockEntity base = bot.getBase();
		Container inbox = base != null && base.getBlockPos().closerThan(center(bot), RADIUS + 1) ? base.getStorage() : null;
		ItemSorter.Result result = ItemSorter.sort(containers, inbox);
		for (ItemStack stack : result.overflow()) {
			bot.spawnAtLocation(level, stack);
		}
		bot.gainWorkXp(Math.max(1, result.stacks() / 4));
		bot.playTaskCompleteSound();
		bot.tellOwner(Component.translatable("butterbot.msg.sorted", containers.size(), result.stacks()));
		bot.clearTask();
	}

	private static BlockPos center(ButterBotEntity bot) {
		return bot.getBase() != null && bot.getBasePos() != null ? bot.getBasePos() : bot.blockPosition();
	}

	/** Chests and barrels near {@code center}, skipping loot chests nobody has opened yet. */
	private static List<BlockPos> findContainers(ServerLevel level, BlockPos center) {
		List<BlockPos> found = new ArrayList<>();
		ChunkPos centerChunk = ChunkPos.containing(center);
		int chunkRadius = (RADIUS >> 4) + 1;
		for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
			for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunk.x() + cx, centerChunk.z() + cz);
				if (chunk == null) {
					continue;
				}
				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					BlockPos pos = entry.getKey();
					if (Math.abs(pos.getX() - center.getX()) > RADIUS || Math.abs(pos.getZ() - center.getZ()) > RADIUS
							|| Math.abs(pos.getY() - center.getY()) > 6) {
						continue;
					}
					if (isSortable(entry.getValue())) {
						found.add(pos.immutable());
					}
				}
			}
		}
		return found;
	}

	private static boolean isSortable(BlockEntity blockEntity) {
		return (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity)
				&& ((RandomizableContainerBlockEntity) blockEntity).getLootTable() == null;
	}

	@Nullable
	private static Container containerAt(ServerLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity != null && isSortable(blockEntity) ? (Container) blockEntity : null;
	}

	@Nullable
	private static BlockPos partnerOf(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock) || !state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return null;
		}
		return pos.relative(ChestBlock.getConnectedDirection(state));
	}

	/** Bottom to top, row by row, with the two halves of a double chest next to each other (top half first). */
	private static List<BlockPos> orderForSorting(ServerLevel level, Set<BlockPos> positions) {
		List<BlockPos> sorted = new ArrayList<>(positions);
		sorted.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY()).thenComparingInt(pos -> pos.getX())
				.thenComparingInt(pos -> pos.getZ()));
		List<BlockPos> ordered = new ArrayList<>();
		Set<BlockPos> used = new LinkedHashSet<>();
		for (BlockPos pos : sorted) {
			if (!used.add(pos)) {
				continue;
			}
			BlockPos partner = partnerOf(level, pos);
			if (partner != null && positions.contains(partner) && used.add(partner)) {
				boolean thisFirst = level.getBlockState(pos).getValue(ChestBlock.TYPE) == ChestType.RIGHT;
				ordered.add(thisFirst ? pos : partner);
				ordered.add(thisFirst ? partner : pos);
			} else {
				ordered.add(pos);
			}
		}
		return ordered;
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.SORT;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
