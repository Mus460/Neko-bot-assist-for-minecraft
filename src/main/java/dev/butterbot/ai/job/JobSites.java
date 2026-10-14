package dev.butterbot.ai.job;

import dev.butterbot.entity.JobConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * Picks where a miner or gatherer goes to work: outside the no-dig zone around the base, on land,
 * preferably in a biome where the wanted item is common, and away from sites that came up empty.
 * Only the biome map is consulted, so no terrain is generated while choosing.
 */
public final class JobSites {

	/** How far past the edge of the no-dig zone the first sites are. */
	private static final int FIRST_RING = 48;
	private static final int RING_STEP = 64;
	private static final int RINGS = 12;
	private static final int POINTS_PER_RING = 16;
	/** A new site is at least this far from any site that turned out empty. */
	private static final int FAILED_SPACING = 96;

	@Nullable
	public static BlockPos pick(ServerLevel level, BlockPos middle, int zone, ResourceCatalog.Entry entry, JobConfig jobs,
								RandomSource random) {
		double start = random.nextDouble() * Math.PI * 2.0D;
		int y = level.getSeaLevel();
		BlockPos fallback = null;
		for (int ring = 0; ring < RINGS; ring++) {
			int distance = zone + FIRST_RING + ring * RING_STEP;
			for (int k = 0; k < POINTS_PER_RING; k++) {
				double angle = start + k * (Math.PI * 2.0D / POINTS_PER_RING) + ring * 0.37D;
				int x = middle.getX() + (int) Math.round(Math.cos(angle) * distance);
				int z = middle.getZ() + (int) Math.round(Math.sin(angle) * distance);
				BlockPos candidate = new BlockPos(x, y, z);
				if (jobs.isNearFailedSite(x, z, FAILED_SPACING) || !level.getWorldBorder().isWithinBounds(candidate)) {
					continue;
				}
				Holder<Biome> biome = level.getBiome(candidate);
				if (!ResourceCatalog.isLand(biome)) {
					continue;
				}
				if (entry.likes(biome)) {
					return candidate;
				}
				if (fallback == null) {
					fallback = candidate;
				}
			}
		}
		return fallback;
	}

	private JobSites() {
	}
}
