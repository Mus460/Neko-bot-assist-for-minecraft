package dev.butterbot.ai;

import dev.butterbot.entity.BotResource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the nearest block of a resource around a point, from bedrock to the build limit, in the
 * chunks that are currently loaded. Chunks are searched in rings from the centre outwards and the
 * search stops as soon as no farther ring can hold anything closer. Chunk sections that cannot
 * contain the resource are skipped without looking at single blocks.
 */
public final class OreFinder {

	public static final int RADIUS = 200;
	private static final int CLUSTER_REACH = 3;

	/** The nearest block (or null) and how many blocks of it sit together at that spot. */
	public record Result(@Nullable BlockPos nearest, int cluster) {
	}

	public static Result find(ServerLevel level, BlockPos center, BotResource resource) {
		ChunkPos centerChunk = ChunkPos.containing(center);
		int maxRing = (RADIUS >> 4) + 1;
		Search search = new Search(center, resource);
		for (int ring = 0; ring <= maxRing; ring++) {
			if (search.nearest != null) {
				// Nothing in this ring can be closer than (ring - 1) chunks horizontally.
				double closestPossible = Math.max(0, (ring - 1) * 16);
				if (closestPossible * closestPossible > search.nearestDistance) {
					break;
				}
			}
			for (int cx = -ring; cx <= ring; cx++) {
				for (int cz = -ring; cz <= ring; cz++) {
					if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) {
						continue;
					}
					LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunk.x() + cx, centerChunk.z() + cz);
					if (chunk != null) {
						search.scan(chunk, centerChunk.x() + cx, centerChunk.z() + cz);
					}
				}
			}
		}
		BlockPos nearest = search.nearest;
		return new Result(nearest, nearest == null ? 0 : countAround(level, nearest, resource));
	}

	private static int countAround(ServerLevel level, BlockPos origin, BotResource resource) {
		int count = 0;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-CLUSTER_REACH, -CLUSTER_REACH, -CLUSTER_REACH),
				origin.offset(CLUSTER_REACH, CLUSTER_REACH, CLUSTER_REACH))) {
			if (level.isLoaded(pos) && resource.matchesBlock(level.getBlockState(pos))) {
				count++;
			}
		}
		return count;
	}

	private static final class Search {
		private final BlockPos center;
		private final BotResource resource;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		@Nullable
		private BlockPos nearest;
		private double nearestDistance = Double.MAX_VALUE;

		private Search(BlockPos center, BotResource resource) {
			this.center = center;
			this.resource = resource;
		}

		private void scan(LevelChunk chunk, int chunkX, int chunkZ) {
			LevelChunkSection[] sections = chunk.getSections();
			for (int index = 0; index < sections.length; index++) {
				LevelChunkSection section = sections[index];
				if (section == null || section.hasOnlyAir() || !section.maybeHas(resource::matchesBlock)) {
					continue;
				}
				int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState state = section.getBlockState(x, y, z);
							if (!resource.matchesBlock(state)) {
								continue;
							}
							cursor.set((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
							int dx = cursor.getX() - center.getX();
							int dz = cursor.getZ() - center.getZ();
							if (dx * dx + dz * dz > RADIUS * RADIUS) {
								continue;
							}
							double distance = cursor.distSqr(center);
							if (distance < nearestDistance) {
								nearestDistance = distance;
								nearest = cursor.immutable();
							}
						}
					}
				}
			}
		}
	}

	private OreFinder() {
	}
}
