package dev.butterbot.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.butterbot.ai.job.Livestock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything about a bot's job: which job, its chest, the list of items it must put in that chest,
 * the area it works in and how far from the base it must stay when digging. Also keeps a little
 * progress (the current dig site, sites that turned out empty) so a reload doesn't start over.
 */
public final class JobConfig {

	public static final int MAX_NEEDS = 9;
	/** Up to a full double chest of one item. */
	public static final int MAX_NEED_COUNT = 54 * 64;
	public static final int DEFAULT_ZONE = 200;
	public static final int MAX_ZONE = 1000;
	public static final int ZONE_STEP = 25;
	public static final int DEFAULT_PATROL_RADIUS = 30;
	public static final int DEFAULT_FARM_RADIUS = 16;
	public static final int MIN_RADIUS = 6;
	public static final int MAX_PATROL_RADIUS = 64;
	public static final int MAX_FARM_RADIUS = 32;
	public static final int DEFAULT_RANCH_RADIUS = 16;
	public static final int MAX_RANCH_RADIUS = 32;
	/** Ranchers breed each kind of animal up to this many. */
	public static final int DEFAULT_HERD = 10;
	public static final int MIN_HERD = 2;
	public static final int MAX_HERD = 99;
	private static final int MAX_FAILED_SITES = 24;

	/** What the job is doing right now, for the status line. Not saved. */
	public enum Phase {SETUP, CHECKING, TRAVEL, WORKING, DEPOSIT, STANDBY, NOT_FOUND, CHEST_PROBLEM, PATROL, LOOT, FARMING, RANCHING}

	/** One line of the list: keep {@code count} of {@code item} in the job chest. */
	public record Need(Item item, int count) {
	}

	private record NeedData(String item, int count) {
		static final Codec<NeedData> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("Item").forGetter(NeedData::item),
				Codec.INT.fieldOf("Count").forGetter(NeedData::count)
		).apply(i, NeedData::new));
	}

	/** The saved form of a job. */
	public record Data(String job, boolean paused, Optional<ResourceKey<Level>> dimension, Optional<BlockPos> chest,
					   Optional<BlockPos> center, List<NeedData> needs, int zone, int radius, Optional<BlockPos> site,
					   String siteItem, boolean siteExact, List<BlockPos> failedSites, int stat, int herd, boolean butcher,
					   Map<String, Integer> herdLimits) {
		public static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.optionalFieldOf("Job", "").forGetter(Data::job),
				Codec.BOOL.optionalFieldOf("Paused", false).forGetter(Data::paused),
				Level.RESOURCE_KEY_CODEC.optionalFieldOf("Dimension").forGetter(Data::dimension),
				BlockPos.CODEC.optionalFieldOf("Chest").forGetter(Data::chest),
				BlockPos.CODEC.optionalFieldOf("Center").forGetter(Data::center),
				NeedData.CODEC.listOf().optionalFieldOf("Needs", List.of()).forGetter(Data::needs),
				Codec.INT.optionalFieldOf("Zone", DEFAULT_ZONE).forGetter(Data::zone),
				Codec.INT.optionalFieldOf("Radius", 0).forGetter(Data::radius),
				BlockPos.CODEC.optionalFieldOf("Site").forGetter(Data::site),
				Codec.STRING.optionalFieldOf("SiteItem", "").forGetter(Data::siteItem),
				Codec.BOOL.optionalFieldOf("SiteExact", false).forGetter(Data::siteExact),
				BlockPos.CODEC.listOf().optionalFieldOf("FailedSites", List.of()).forGetter(Data::failedSites),
				Codec.INT.optionalFieldOf("Stat", 0).forGetter(Data::stat),
				Codec.INT.optionalFieldOf("Herd", DEFAULT_HERD).forGetter(Data::herd),
				Codec.BOOL.optionalFieldOf("Butcher", false).forGetter(Data::butcher),
				Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("HerdLimits", Map.of()).forGetter(Data::herdLimits)
		).apply(i, Data::new));
	}

	private BotJob job = BotJob.NONE;
	private boolean paused;
	@Nullable
	private ResourceKey<Level> dimension;
	@Nullable
	private BlockPos chest;
	@Nullable
	private BlockPos center;
	private final List<Need> needs = new ArrayList<>();
	private int zone = DEFAULT_ZONE;
	private int radius;
	@Nullable
	private BlockPos site;
	@Nullable
	private Item siteItem;
	/** True when {@link #site} is the exact spot the bot left (down in its tunnel), not just an area. */
	private boolean siteExact;
	private final List<BlockPos> failedSites = new ArrayList<>();
	/** Items delivered (list jobs), monsters defeated (guard), crops harvested (farmer) or chores done (rancher). */
	private int stat;
	/** Rancher: how many of each kind of animal to keep (kinds without a limit of their own). */
	private int herd = DEFAULT_HERD;
	/** Rancher: limits for single kinds of animal, by entity type id. */
	private final Map<String, Integer> herdLimits = new HashMap<>();
	/** Rancher: may slaughter animals over {@link #herd}. Off unless the owner turns it on. */
	private boolean butcher;

	// Not saved.
	private Phase phase = Phase.SETUP;
	private final Map<Item, Integer> chestCounts = new HashMap<>();
	private long chestCountsTime = Long.MIN_VALUE;
	private final Map<Item, Long> cooldowns = new HashMap<>();
	private final Map<Item, Integer> failStreak = new HashMap<>();
	/**
	 * "Long ago" for timestamps: far enough back for every wait, and safe to subtract from
	 * ({@code now - Long.MIN_VALUE} overflows and makes every "has it been long enough" check fail).
	 */
	public static final long NEVER = -1_000_000_000L;
	private long lastWarning = NEVER;
	private long lastDepositTry = NEVER;

	// ------------------------------------------------------------------ job

	public BotJob job() {
		return job;
	}

	/** Switches job. Settings that still make sense are kept; list items the new job can't get are dropped. */
	public void setJob(BotJob newJob, java.util.function.Predicate<Item> allowed) {
		if (newJob != job) {
			job = newJob;
			needs.removeIf(need -> !allowed.test(need.item()));
			site = null;
			siteItem = null;
			siteExact = false;
			failedSites.clear();
			cooldowns.clear();
			failStreak.clear();
			stat = 0;
			radius = 0;
		}
		paused = false;
		phase = Phase.SETUP;
	}

	public boolean hasJob() {
		return job != BotJob.NONE;
	}

	public boolean isPaused() {
		return paused;
	}

	public void setPaused(boolean paused) {
		this.paused = paused;
	}

	public boolean isActive() {
		return job != BotJob.NONE && !paused;
	}

	public Phase phase() {
		return phase;
	}

	public void setPhase(Phase phase) {
		this.phase = phase;
	}

	// ------------------------------------------------------------------ places

	@Nullable
	public ResourceKey<Level> dimension() {
		return dimension;
	}

	@Nullable
	public ServerLevel level(MinecraftServer server) {
		return dimension == null ? null : server.getLevel(dimension);
	}

	@Nullable
	public BlockPos chest() {
		return chest;
	}

	public void setChest(ResourceKey<Level> dimension, @Nullable BlockPos chest) {
		moveTo(dimension);
		this.chest = chest == null ? null : chest.immutable();
		chestCountsTime = Long.MIN_VALUE;
		chestCounts.clear();
		// Without a Bot Base the no-dig zone is around the chest, so a moved chest moves the zone too.
		clearSite();
	}

	@Nullable
	public BlockPos center() {
		return center;
	}

	public void setCenter(ResourceKey<Level> dimension, @Nullable BlockPos center) {
		moveTo(dimension);
		this.center = center == null ? null : center.immutable();
	}

	/** Chest and centre are always in the same dimension; picking a spot elsewhere forgets the other one. */
	private void moveTo(ResourceKey<Level> newDimension) {
		if (dimension != null && !dimension.equals(newDimension)) {
			chest = null;
			center = null;
			site = null;
			failedSites.clear();
		}
		dimension = newDimension;
	}

	public int zone() {
		return zone;
	}

	public void setZone(int zone) {
		this.zone = Math.clamp(zone, 0, MAX_ZONE);
		// The old dig site may be inside the new zone: pick a new one.
		clearSite();
	}

	public void clearSite() {
		setSite(null, null, false);
	}

	/** Patrol or farm radius (depends on the job). */
	public int radius() {
		if (radius > 0) {
			return Math.min(radius, maxRadius());
		}
		return switch (job) {
			case FARMER -> DEFAULT_FARM_RADIUS;
			case RANCHER -> DEFAULT_RANCH_RADIUS;
			default -> DEFAULT_PATROL_RADIUS;
		};
	}

	public int maxRadius() {
		return maxRadiusFor(job);
	}

	public static int maxRadiusFor(BotJob job) {
		return switch (job) {
			case FARMER -> MAX_FARM_RADIUS;
			case RANCHER -> MAX_RANCH_RADIUS;
			default -> MAX_PATROL_RADIUS;
		};
	}

	public int herd() {
		return herd;
	}

	/** One limit for every kind of animal (limits set for single kinds are dropped). */
	public void setHerd(int herd) {
		this.herd = Math.clamp(herd, MIN_HERD, MAX_HERD);
		herdLimits.clear();
	}

	/** How many of {@code kind} the rancher keeps: its own limit, or the one for every kind. */
	public int herdLimit(EntityType<?> kind) {
		return herdLimits.getOrDefault(Livestock.id(kind), herd);
	}

	public boolean hasHerdLimit(EntityType<?> kind) {
		return herdLimits.containsKey(Livestock.id(kind));
	}

	public void setHerdLimit(EntityType<?> kind, int limit) {
		herdLimits.put(Livestock.id(kind), Math.clamp(limit, MIN_HERD, MAX_HERD));
	}

	public boolean butcher() {
		return butcher;
	}

	public void setButcher(boolean butcher) {
		this.butcher = butcher;
	}

	public void setRadius(int radius) {
		this.radius = Math.clamp(radius, MIN_RADIUS, maxRadius());
	}

	/**
	 * The middle of the no-dig zone: the Bot Base when the bot has one, otherwise the job chest (or the
	 * job's centre).
	 */
	@Nullable
	public BlockPos zoneCenter(@Nullable BlockPos basePos) {
		if (basePos != null) {
			return basePos;
		}
		return chest != null ? chest : center;
	}

	/** True if digging at {@code pos} would break the "not within {@link #zone()} blocks of the base" rule. */
	public boolean isInsideZone(Level level, @Nullable BlockPos basePos, BlockPos pos) {
		if (zone <= 0 || (dimension != null && !dimension.equals(level.dimension()))) {
			return false;
		}
		BlockPos middle = zoneCenter(basePos);
		if (middle == null) {
			return false;
		}
		long dx = pos.getX() - middle.getX();
		long dz = pos.getZ() - middle.getZ();
		return dx * dx + dz * dz < (long) zone * zone;
	}

	// ------------------------------------------------------------------ the list

	public List<Need> needs() {
		return Collections.unmodifiableList(needs);
	}

	public boolean isNeeded(Item item) {
		for (Need need : needs) {
			if (need.item() == item) {
				return true;
			}
		}
		return false;
	}

	/** Sets how many of {@code item} the chest should hold; 0 takes it off the list. Returns false if the list is full. */
	public boolean setNeed(Item item, int count) {
		for (int i = 0; i < needs.size(); i++) {
			if (needs.get(i).item() == item) {
				if (count <= 0) {
					needs.remove(i);
				} else {
					needs.set(i, new Need(item, Math.min(count, MAX_NEED_COUNT)));
				}
				cooldowns.remove(item);
				failStreak.remove(item);
				return true;
			}
		}
		if (count <= 0) {
			return true;
		}
		if (needs.size() >= MAX_NEEDS) {
			return false;
		}
		needs.add(new Need(item, Math.min(count, MAX_NEED_COUNT)));
		return true;
	}

	public void clearNeeds() {
		needs.clear();
		cooldowns.clear();
		failStreak.clear();
	}

	// ------------------------------------------------------------------ what is in the chest

	public void rememberChestCounts(Map<Item, Integer> counts, long gameTime) {
		chestCounts.clear();
		chestCounts.putAll(counts);
		chestCountsTime = gameTime;
	}

	/** How many the chest held when it was last looked at, or -1 if it hasn't been looked at yet. */
	public int knownChestCount(Item item) {
		if (chestCountsTime == Long.MIN_VALUE) {
			return -1;
		}
		return chestCounts.getOrDefault(item, 0);
	}

	public long chestCountsTime() {
		return chestCountsTime;
	}

	public Map<Item, Integer> chestCounts() {
		return Collections.unmodifiableMap(chestCounts);
	}

	// ------------------------------------------------------------------ dig sites

	@Nullable
	public BlockPos site() {
		return site;
	}

	@Nullable
	public Item siteItem() {
		return siteItem;
	}

	public boolean isSiteExact() {
		return siteExact;
	}

	public void setSite(@Nullable BlockPos site, @Nullable Item item, boolean exact) {
		this.site = site == null ? null : site.immutable();
		this.siteItem = item;
		this.siteExact = site != null && exact;
	}

	/** The site had nothing (more) to give: don't go back there. */
	public void failSite() {
		if (site != null) {
			failedSites.add(site);
			while (failedSites.size() > MAX_FAILED_SITES) {
				failedSites.removeFirst();
			}
		}
		if (siteItem != null) {
			failStreak.merge(siteItem, 1, Integer::sum);
		}
		site = null;
		siteExact = false;
	}

	public void siteWorked() {
		if (siteItem != null) {
			failStreak.remove(siteItem);
		}
	}

	public int failStreak(Item item) {
		return failStreak.getOrDefault(item, 0);
	}

	public boolean isNearFailedSite(int x, int z, int distance) {
		long limit = (long) distance * distance;
		for (BlockPos failed : failedSites) {
			long dx = failed.getX() - x;
			long dz = failed.getZ() - z;
			if (dx * dx + dz * dz < limit) {
				return true;
			}
		}
		return false;
	}

	/** Stops looking for {@code item} until {@code untilGameTime} (it couldn't be found). */
	public void coolDown(Item item, long untilGameTime) {
		cooldowns.put(item, untilGameTime);
		failStreak.remove(item);
	}

	public boolean isCoolingDown(Item item, long gameTime) {
		Long until = cooldowns.get(item);
		if (until == null) {
			return false;
		}
		if (gameTime >= until) {
			cooldowns.remove(item);
			return false;
		}
		return true;
	}

	/** True at most once every 5 minutes, so problems (full chest...) are reported without spamming the chat. */
	public boolean warnNow(long gameTime) {
		if (gameTime - lastWarning < 20L * 60L * 5L) {
			return false;
		}
		lastWarning = gameTime;
		return true;
	}

	public void markDepositTry(long gameTime) {
		lastDepositTry = gameTime;
	}

	public long lastDepositTry() {
		return lastDepositTry;
	}

	// ------------------------------------------------------------------ stats

	public int stat() {
		return stat;
	}

	public void addStat(int amount) {
		stat += amount;
	}

	// ------------------------------------------------------------------ saving

	public Data save() {
		List<NeedData> savedNeeds = new ArrayList<>();
		for (Need need : needs) {
			savedNeeds.add(new NeedData(BuiltInRegistries.ITEM.getKey(need.item()).toString(), need.count()));
		}
		return new Data(job.name(), paused, Optional.ofNullable(dimension), Optional.ofNullable(chest),
				Optional.ofNullable(center), savedNeeds, zone, radius, Optional.ofNullable(site),
				siteItem == null ? "" : BuiltInRegistries.ITEM.getKey(siteItem).toString(), siteExact, List.copyOf(failedSites), stat,
				herd, butcher, Map.copyOf(herdLimits));
	}

	public void load(Data data) {
		job = BotJob.byId(data.job());
		paused = data.paused();
		dimension = data.dimension().orElse(null);
		chest = data.chest().orElse(null);
		center = data.center().orElse(null);
		needs.clear();
		for (NeedData need : data.needs()) {
			Item item = itemById(need.item());
			if (item != null && need.count() > 0 && needs.size() < MAX_NEEDS) {
				needs.add(new Need(item, Math.min(need.count(), MAX_NEED_COUNT)));
			}
		}
		zone = Math.clamp(data.zone(), 0, MAX_ZONE);
		radius = data.radius() <= 0 ? 0 : Math.clamp(data.radius(), MIN_RADIUS, MAX_PATROL_RADIUS);
		site = data.site().orElse(null);
		siteItem = itemById(data.siteItem());
		siteExact = site != null && data.siteExact();
		failedSites.clear();
		failedSites.addAll(data.failedSites());
		stat = Math.max(0, data.stat());
		herd = Math.clamp(data.herd(), MIN_HERD, MAX_HERD);
		butcher = data.butcher();
		herdLimits.clear();
		data.herdLimits().forEach((kind, limit) -> {
			if (Livestock.byId(kind) != null) {
				herdLimits.put(kind, Math.clamp(limit, MIN_HERD, MAX_HERD));
			}
		});
		phase = Phase.SETUP;
	}

	@Nullable
	private static Item itemById(String id) {
		Identifier identifier = id.isEmpty() ? null : Identifier.tryParse(id);
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}
}
