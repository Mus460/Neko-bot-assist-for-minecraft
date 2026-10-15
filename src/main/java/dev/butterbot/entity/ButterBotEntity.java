package dev.butterbot.entity;

import dev.butterbot.ai.BotBrain;
import dev.butterbot.ai.BotSmelter;
import dev.butterbot.ai.HotbarRefill;
import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TreeFeller;
import dev.butterbot.ai.Travel;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.ai.actions.CraftTaskAction;
import dev.butterbot.ai.actions.DeliverAction;
import dev.butterbot.ai.actions.ExpandSearchAction;
import dev.butterbot.ai.actions.FarmTaskAction;
import dev.butterbot.ai.actions.FetchTaskAction;
import dev.butterbot.ai.actions.FollowOwnerAction;
import dev.butterbot.ai.actions.GoToBaseAction;
import dev.butterbot.ai.actions.GoToPosAction;
import dev.butterbot.ai.actions.GuardAction;
import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.ai.actions.LuaScriptAction;
import dev.butterbot.ai.actions.MineTargetAction;
import dev.butterbot.ai.actions.RecoverItemsAction;
import dev.butterbot.ai.actions.SortTaskAction;
import dev.butterbot.ai.actions.TunnelTaskAction;
import dev.butterbot.ai.job.JobAction;
import dev.butterbot.ai.path.DigPath;
import dev.butterbot.ai.path.PathFollower;
import dev.butterbot.ai.job.JobStorage;
import dev.butterbot.ai.job.ResourceCatalog;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.item.ButterBotItem;
import dev.butterbot.menu.BotInventoryMenu;
import dev.butterbot.lua.LuaProgram;
import dev.butterbot.network.ModNetworking;
import dev.butterbot.registry.ModBlocks;
import dev.butterbot.registry.ModEntities;
import dev.butterbot.registry.ModSounds;
import dev.butterbot.skin.SkinLimits;
import dev.butterbot.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The Butter Bot: an owned helper that mines, tunnels, farms, sorts chests, crafts from chests,
 * guards its owner, follows its owner, delivers loot to its owner or its Bot Base, runs sandboxed
 * Lua programs and levels up with work.
 */
public class ButterBotEntity extends PathfinderMob {

	private static final EntityDataAccessor<Component> DATA_SPEECH =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.COMPONENT);
	private static final EntityDataAccessor<String> DATA_SKIN =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DATA_WORKING =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> DATA_NEKO =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_SKIN_VARIANT =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_LEVEL =
			SynchedEntityData.defineId(ButterBotEntity.class, EntityDataSerializers.INT);

	/** Backpack size at level 1; it grows to {@link BotLevels#MAX_BACKPACK} with levels. */
	public static final int INVENTORY_SIZE = BotLevels.BASE_BACKPACK;
	public static final int MAX_NAME_LENGTH = 32;
	/** How many built-in skins there are; every bot of a player gets a different one while it can. */
	public static final int SKIN_VARIANTS = 5;
	/** Plantable items (seeds, saplings...) the bot keeps when it unloads at its base. */
	public static final int KEEP_PLANTABLES = 32;
	/** Cobblestone-like blocks the bot keeps while mining so it can plug lava. */
	private static final int KEEP_FILLER = 32;
	private static final int TORCH_LIGHT_THRESHOLD = 3;
	private static final int OWNER_FEED_FOOD_LEVEL = 14;

	private static final long BLACKLIST_DURATION_TICKS = 20L * 60L;
	private static final int SCAN_INTERVAL_TICKS = 40;
	private static final int SCAN_VERTICAL_RADIUS = 12;
	/** A buried block counts as this much farther (squared) than one with an open side. */
	private static final double BURIED_PENALTY = 20.0D;
	private static final long SECOND_WIND_COOLDOWN = 20L * 60L * 10L;
	/** Problems are told to the owner at most this often (the rest only show with the debug key). */
	private static final long PROBLEM_MESSAGE_TICKS = 20L * 60L;
	private static final long PROBLEM_MEMORY_TICKS = 20L * 60L * 5L;
	private static final float MAX_DIGGABLE_HARDNESS = 10.0F;
	/** Ancient debris needs a diamond pickaxe, as for a player: the bot gets its own at this level. */
	public static final int NETHERITE_MIN_LEVEL = 5;
	/** How long the bot keeps holding its tool after the last block it dug. */
	private static final int TOOL_HOLD_TICKS = 60;
	private static final int SWING_INTERVAL_TICKS = 5;
	/** Shortest pause between two voice lines. */
	private static final int VOICE_GAP_TICKS = 25;
	/** How long the bot stands at a portal that doesn't take it before it gives up on it. */
	private static final int PORTAL_PATIENCE_TICKS = 20 * 45;
	/** After giving up on a portal, portals are left alone this long (the caller falls back meanwhile). */
	private static final int PORTAL_GIVE_UP_TICKS = 20 * 60 * 3;
	private static final String[] DEFAULT_NAMES = {
			"Mochi", "Kuro", "Shiro", "Momo", "Yuki", "Hana", "Sora", "Neko", "Miko", "Tama",
			"Kiki", "Luna", "Coco", "Nana", "Rin", "Mimi", "Suzu", "Haru", "Nori", "Kinako"
	};

	private final BotBrain brain = new BotBrain();
	private final MiningController miner = new MiningController(this);
	private final BotSmelter smelter = new BotSmelter(this);
	private SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	/** Tools the owner gave it, in {@link GearSlot#TOOLS} order (armor sits in the vanilla armor slots). */
	private final SimpleContainer toolbox = new SimpleContainer(GearSlot.TOOLS.size());
	/** How far each piece of gear was warned about: 1 nearly worn out, 2 put aside so it doesn't break. */
	private final Map<GearSlot, Integer> wearWarnings = new EnumMap<>(GearSlot.class);
	private final Map<Long, Long> blacklistedTargets = new HashMap<>();

	@Nullable
	private UUID ownerUuid;
	@Nullable
	private String ownerName;
	@Nullable
	private BlockPos targetBlock;
	private BotState state = BotState.IDLE;
	private BotTask task = BotTask.NONE;
	@Nullable
	private BotResource resource;
	private int targetCount;
	private int collectedCount;
	private boolean returnRequested;
	private DeliveryMode deliveryMode = DeliveryMode.OWNER;

	@Nullable
	private Direction tunnelDirection;
	@Nullable
	private BlockPos tunnelOrigin;
	private int tunnelLength;
	private int tunnelProgress;
	private int tunnelWidth;
	private int tunnelHeight;
	private boolean tunnelTorches = true;

	@Nullable
	private Item craftItem;
	private int craftCount;

	@Nullable
	private BlockPos basePos;
	private String luaScript = "";

	@Nullable
	private BlockPos farmCenter;
	private int harvestedCount;

	// Progress and settings.
	private int workXp;
	/** Skills, job mastery and lifetime counters. */
	private final BotProgress progress = new BotProgress();
	@Nullable
	private BotStats cachedStats;
	/** Game time from which Second Wind can save the bot again. */
	private long secondWindReadyAt;
	/** What the bot is digging its way to and how (shown by the debug key). */
	@Nullable
	private BlockPos routeTarget;
	@Nullable
	private PathFollower routeFollower;
	/** The last thing that went wrong, for the status and the debug key. */
	@Nullable
	private Component lastProblem;
	private long lastProblemTime = JobConfig.NEVER;
	private long lastProblemMessage = JobConfig.NEVER;
	/** Experience points (from ores, smelting and monsters) waiting to be handed to the owner. */
	private int storedXp;
	private boolean guard = true;
	private boolean autoTorch = true;
	private boolean feedOwner = true;
	private SmeltMode smeltMode = SmeltMode.ORES;

	private int scanCooldown;
	/** Counts mining orders, so the search knows when a new one started. */
	private int mineRun;
	private long lastLavaWarning;
	private long lastLavaSealed;
	private long lastUnstuckMessage;
	/** Portals the bot came through, by "dimension it is in>dimension the portal leads to": its way back. */
	private final Map<String, BlockPos> portalExits = new HashMap<>();
	@Nullable
	private ResourceKey<Level> lastDimension;
	/** Where the owner was last seen in the bot's dimension (the portal, when they went through one). */
	@Nullable
	private BlockPos lastOwnerPos;
	/** Bodyguard: where the bot it guards was last seen in this dimension (it may have taken a portal there). */
	@Nullable
	private BlockPos lastWardPos;
	@Nullable
	private ButterBotEntity wardCache;
	/** The dimension the bot is walking into a portal for, and until when it may use one. */
	@Nullable
	private ResourceKey<Level> portalWish;
	private long portalWishUntil;
	/** How long the current try to get into a portal has lasted; past the patience it gives up on portals for a while. */
	private int portalTicks;
	private long portalGiveUpUntil;
	/** Pillaring: the block to put under the bot once its jump has lifted it high enough. */
	@Nullable
	private BlockPos pillarBase;
	private int pillarTicks;
	/** Fence gates and doors it opened on its way, closed again once it is through. */
	private final List<BlockPos> openedGates = new ArrayList<>();
	/** Where it last stood on firm ground (somewhere to go back to if it falls out of the world). */
	@Nullable
	private BlockPos lastSafeGround;
	private int wallTicks;
	private long nextRescue;
	private int ambientSoundTimer = 400;
	private int speechTicks;
	private int toolHoldTicks;
	private ItemStack displayTool = ItemStack.EMPTY;
	private int displayTicks;
	private int combatTicks;
	private int lastHurtTick = -1000;
	private int careTimer;
	private int eatCooldown;
	private int feedCooldown;
	private int torchCooldown;
	private int lastVoiceTick = -1000;
	/** False until the bot has been given its own built-in skin (bots from older versions get one on their first tick). */
	private boolean skinVariantChosen;

	// Keeping the owner's things safe after they die.
	@Nullable
	private BlockPos recoverPos;
	private final List<ItemStack> recoveredItems = new ArrayList<>();

	// Lighting up an area.
	@Nullable
	private BlockPos lightCenter;
	private int lightRadius = LightAreaAction.DEFAULT_RADIUS;
	private int torchesPlaced;

	// Fetching items from storage.
	@Nullable
	private Item fetchItem;
	private int fetchCount;
	private final List<ItemStack> fetchCarry = new ArrayList<>();

	// "Go over there and wait".
	@Nullable
	private BlockPos gotoPos;

	private boolean autoRefill = true;
	private final HotbarRefill hotbarRefill = new HotbarRefill();

	// A monster (or animal) the owner pointed at.
	@Nullable
	private UUID orderedTarget;
	/** The ordered target is an animal the rancher job is culling, not something the owner pointed at. */
	private boolean orderedCull;
	private long orderedUntil;

	// The standing job (miner, gatherer, guard, farmer) and the item a job's mining run is after.
	private final JobConfig jobs = new JobConfig();
	@Nullable
	private ResourceCatalog.Entry mineFilter;
	/** The current dig site came up empty, so the bot shouldn't come back to this exact spot. */
	private boolean jobSiteSpent;
	private boolean jobChanged;

	// Surface blocks opened while digging down, to be put back once the bot is underground.
	private record Seal(BlockPos pos, BlockState state, long expires) {
	}

	private static final int MAX_PENDING_SEALS = 32;
	private static final long SEAL_EXPIRY_TICKS = 20L * 60L * 10L;
	private final List<Seal> pendingSeals = new ArrayList<>();

	public ButterBotEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		brain.register(new MineTargetAction());
		brain.register(new ExpandSearchAction());
		brain.register(new TunnelTaskAction());
		brain.register(new CraftTaskAction());
		brain.register(new GoToBaseAction());
		brain.register(new FollowOwnerAction());
		brain.register(new DeliverAction());
		brain.register(new LuaScriptAction());
		brain.register(new GuardAction());
		brain.register(new FarmTaskAction());
		brain.register(new SortTaskAction());
		brain.register(new RecoverItemsAction());
		brain.register(new LightAreaAction());
		brain.register(new FetchTaskAction());
		brain.register(new GoToPosAction());
		brain.register(new JobAction());
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, BotLevels.maxHealth(1))
				.add(Attributes.MOVEMENT_SPEED, 0.3D)
				.add(Attributes.FOLLOW_RANGE, 48.0D)
				.add(Attributes.STEP_HEIGHT, 1.0D)
				.add(Attributes.SAFE_FALL_DISTANCE, 6.0D)
				.add(Attributes.ARMOR, 4.0D)
				.add(Attributes.ATTACK_DAMAGE, BotLevels.attackDamage(1))
				.add(Attributes.ATTACK_KNOCKBACK, 0.4D);
	}

	@Override
	protected PathNavigation createNavigation(Level level) {
		GroundPathNavigation navigation = new GroundPathNavigation(this, level);
		navigation.setCanOpenDoors(true);
		navigation.setCanFloat(true);
		return navigation;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_SPEECH, Component.empty());
		builder.define(DATA_SKIN, "");
		builder.define(DATA_WORKING, false);
		builder.define(DATA_NEKO, true);
		builder.define(DATA_SKIN_VARIANT, 0);
		builder.define(DATA_LEVEL, 1);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
	}

	@Override
	@Nullable
	public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
										EntitySpawnReason spawnReason, @Nullable SpawnGroupData spawnData) {
		speak(ModSounds.VOICE_HELLO, ModSounds.BOT_SPAWN, 1.5F, true);
		return super.finalizeSpawn(level, difficulty, spawnReason, spawnData);
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public void tick() {
		super.tick();

		if (this.level().isClientSide() || !this.isAlive()) {
			return;
		}

		if (!skinVariantChosen && ownerUuid != null) {
			chooseFreeSkinVariant();
		}
		handlePassiveScan();
		brain.tick(this);

		boolean working = task != BotTask.NONE || returnRequested;
		if (this.entityData.get(DATA_WORKING) != working) {
			this.entityData.set(DATA_WORKING, working);
		}

		if (working && --ambientSoundTimer <= 0) {
			ambientSoundTimer = 500 + this.random.nextInt(400);
			speak(ModSounds.VOICE_AMBIENT, ModSounds.BOT_AMBIENT, 0.8F, false);
		}

		if (speechTicks > 0 && --speechTicks == 0) {
			this.entityData.set(DATA_SPEECH, Component.empty());
		}

		// A courier with smelting on runs its furnace on raw ores even when smelting is off for the bot.
		SmeltMode furnace = smeltMode != SmeltMode.OFF ? smeltMode
				: jobs.isActive() && jobs.job() == BotJob.COURIER && jobs.smelt() ? SmeltMode.ORES : SmeltMode.OFF;
		if (furnace != SmeltMode.OFF && this.tickCount % 10 == 0) {
			smelter.tick(furnace);
		}
		if (autoTorch && task == BotTask.MINE && --torchCooldown <= 0) {
			torchCooldown = 10;
			placeTorchIfDark();
		}
		tickCare();
		tickAbilities();
		tickPillar();
		if (this.level() instanceof ServerLevel travelLevel) {
			tickTravelMemory(travelLevel);
			tickDanger(travelLevel);
			if (this.tickCount % 5 == 0 && !openedGates.isEmpty()) {
				closeGates(travelLevel);
			}
		}
		if (this.tickCount % 20 == 0 && this.level() instanceof ServerLevel serverLevel) {
			sealHoles(serverLevel);
		}
		if (this.isInWall()) {
			// Every tick: being pushed out of a block takes a few small nudges.
			escapeWall();
		}
		updateHeldTool();
	}

	/**
	 * Shows a fitting tool in the bot's hand while it digs, fights or farms, and swings its arm. What it
	 * holds is a copy, only for looks: it is never dropped (tools the owner gave it stay in its gear).
	 */
	private void updateHeldTool() {
		BlockPos digPos = miner.getDigPos();
		ItemStack wanted;
		if (combatTicks > 0) {
			combatTicks--;
			wanted = weapon();
		} else if (digPos != null) {
			toolHoldTicks = TOOL_HOLD_TICKS;
			wanted = diggingTool(this.level().getBlockState(digPos));
			if (this.tickCount % SWING_INTERVAL_TICKS == 0) {
				this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			}
		} else if (displayTicks > 0) {
			displayTicks--;
			wanted = displayTool;
		} else if (toolHoldTicks > 0 && task != BotTask.NONE) {
			toolHoldTicks--;
			wanted = this.getMainHandItem();
		} else {
			toolHoldTicks = 0;
			wanted = ItemStack.EMPTY;
		}
		if (!looksSame(this.getMainHandItem(), wanted)) {
			this.setItemSlot(EquipmentSlot.MAINHAND, wanted.copy());
		}
	}

	/** Same item with the same enchantments (wear doesn't matter): no need to swap what the bot holds. */
	private static boolean looksSame(ItemStack held, ItemStack wanted) {
		return held.isEmpty() == wanted.isEmpty() && ItemStack.isSameItem(held, wanted)
				&& Objects.equals(held.get(DataComponents.ENCHANTMENTS), wanted.get(DataComponents.ENCHANTMENTS));
	}

	/** Holds {@code tool} for a moment and swings it, for work that does not go through the miner. */
	public void showTool(ItemStack tool, int ticks) {
		this.displayTool = tool;
		this.displayTicks = ticks;
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}

	/** Called every tick while the bot is fighting: it holds its sword. */
	public void markCombat() {
		this.combatTicks = 40;
	}

	public boolean isFighting() {
		return combatTicks > 0;
	}

	/** After defeating a monster: keep its loot and experience, and learn from the fight. */
	public void onDefeated(LivingEntity victim) {
		if (this.level() instanceof ServerLevel serverLevel) {
			storedXp += mendGear(Math.max(1, victim.getExperienceReward(serverLevel, this)));
		}
		int xp = BotLevels.killXp(victim);
		progress.addStat(BotProgress.Stat.KILLS, 1);
		if (jobs.job() == BotJob.GUARD && jobs.isActive()) {
			// Guards learn their job from fights.
			gainMastery(BotJob.GUARD, xp);
		}
		gainWorkXp(xp);
		collectNearbyDrops(victim.position(), 3.0D);
		if (jobs.job() == BotJob.GUARD || jobs.job() == BotJob.BODYGUARD || (jobs.job() == BotJob.RANCHER && victim instanceof Animal)) {
			jobs.addStat(1);
		}
	}

	/**
	 * The tool the bot digs {@code blockState} with (what it shows in its hand): one the owner gave it,
	 * else its own. Given tools are the real stack: only read it.
	 */
	public ItemStack diggingTool(BlockState blockState) {
		ItemStack given = givenToolFor(blockState);
		return given.isEmpty() ? toolFor(blockState, BotLevels.toolTier(getBotLevel())) : given;
	}

	/** The bot's own tool for {@code blockState} (better ones as it levels up). */
	private static ItemStack toolFor(BlockState blockState, int tier) {
		GearSlot slot = GearSlot.forBlock(blockState);
		return ownTool(slot == null ? GearSlot.PICKAXE : slot, tier);
	}

	private static ItemStack ownTool(GearSlot slot, int tier) {
		return new ItemStack(switch (slot) {
			case SWORD -> tier == 2 ? Items.NETHERITE_SWORD : tier == 1 ? Items.DIAMOND_SWORD : Items.IRON_SWORD;
			case AXE -> tier == 2 ? Items.NETHERITE_AXE : tier == 1 ? Items.DIAMOND_AXE : Items.IRON_AXE;
			case SHOVEL -> tier == 2 ? Items.NETHERITE_SHOVEL : tier == 1 ? Items.DIAMOND_SHOVEL : Items.IRON_SHOVEL;
			case HOE -> tier == 2 ? Items.NETHERITE_HOE : tier == 1 ? Items.DIAMOND_HOE : Items.IRON_HOE;
			default -> tier == 2 ? Items.NETHERITE_PICKAXE : tier == 1 ? Items.DIAMOND_PICKAXE : Items.IRON_PICKAXE;
		});
	}

	/** The bot's own tool for {@code blockState}, carrying Fortune from its level. */
	private ItemStack miningTool(ServerLevel level, BlockState blockState) {
		ItemStack tool = toolFor(blockState, BotLevels.toolTier(getBotLevel()));
		int fortune = stats().fortune();
		if (fortune > 0) {
			tool.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), fortune);
		}
		return tool;
	}

	// ------------------------------------------------------------------ gear

	/** The armor piece or tool in {@code slot} (the stack itself, not a copy). */
	public ItemStack getGear(GearSlot slot) {
		return slot.isArmor() ? this.getItemBySlot(slot.armorSlot()) : toolbox.getItem(slot.toolIndex());
	}

	public void setGear(GearSlot slot, ItemStack stack) {
		if (slot.isArmor()) {
			this.setItemSlot(slot.armorSlot(), stack);
		} else {
			toolbox.setItem(slot.toolIndex(), stack);
		}
		wearWarnings.remove(slot);
	}

	/** The tool in {@code slot} if the bot works with it: not when missing or put aside before it breaks. */
	private ItemStack usableTool(GearSlot slot) {
		ItemStack stack = getGear(slot);
		return stack.isEmpty() || GearSlot.isResting(stack) ? ItemStack.EMPTY : stack;
	}

	/** The given tool for digging {@code state} (one that gets its drops), or EMPTY to use the bot's own. */
	private ItemStack givenToolFor(BlockState state) {
		GearSlot slot = GearSlot.forBlock(state);
		ItemStack tool = slot == null ? ItemStack.EMPTY : usableTool(slot);
		return tool.isEmpty() || state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state) ? ItemStack.EMPTY : tool;
	}

	/** A copy of the tool for {@code slot} to show while working with it: a given one, else its own. */
	public ItemStack workTool(GearSlot slot) {
		ItemStack given = usableTool(slot);
		return given.isEmpty() ? ownTool(slot, BotLevels.toolTier(getBotLevel())) : given.copy();
	}

	/**
	 * The sword the bot fights with: a given one (the real stack) when it is enchanted or hits at least
	 * as hard as the bot's own, else its own.
	 */
	private ItemStack weapon() {
		ItemStack own = ownTool(GearSlot.SWORD, BotLevels.toolTier(getBotLevel()));
		ItemStack sword = usableTool(GearSlot.SWORD);
		return sword.isEmpty() || !sword.isEnchanted() && attackBonus(sword) < attackBonus(own) ? own : sword;
	}

	private static double attackBonus(ItemStack stack) {
		return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
				.compute(Attributes.ATTACK_DAMAGE, 0.0D, EquipmentSlot.MAINHAND);
	}

	/** How fast the bot digs {@code state}: never slower than with its own tool; a given one can be faster (Efficiency counts). */
	public float toolSpeed(BlockState state) {
		float speed = toolFor(state, BotLevels.toolTier(getBotLevel())).getDestroySpeed(state);
		ItemStack given = givenToolFor(state);
		if (!given.isEmpty()) {
			float givenSpeed = given.getDestroySpeed(state);
			int efficiency = enchantmentLevel(Enchantments.EFFICIENCY, given);
			if (givenSpeed > 1.0F && efficiency > 0) {
				givenSpeed += efficiency * efficiency + 1;
			}
			speed = Math.max(speed, givenSpeed);
		}
		return Math.max(1.0F, speed);
	}

	/** Whether digging {@code state} gives its drops (with the bot's own tool or a given one). */
	public boolean canHarvest(BlockState state) {
		return !state.requiresCorrectToolForDrops() || toolFor(state, BotLevels.toolTier(getBotLevel())).isCorrectToolForDrops(state)
				|| !givenToolFor(state).isEmpty();
	}

	/** Ancient debris needs a diamond pickaxe: its own from level {@value #NETHERITE_MIN_LEVEL}, or a given one at any level. */
	public boolean canMineNetherite() {
		return getBotLevel() >= NETHERITE_MIN_LEVEL || !givenToolFor(Blocks.ANCIENT_DEBRIS.defaultBlockState()).isEmpty();
	}

	private int enchantmentLevel(ResourceKey<Enchantment> enchantment, ItemStack stack) {
		return this.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(enchantment)
				.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, stack)).orElse(0);
	}

	/**
	 * A copy of a given tool to work out drops with: Fortune at least the bot's own, and no Silk Touch
	 * on mining runs (ores must drop what the run is collecting).
	 */
	private ItemStack dropTool(ServerLevel level, ItemStack given) {
		ItemStack tool = given.copy();
		if (task == BotTask.MINE) {
			EnchantmentHelper.updateEnchantments(tool, enchantments -> enchantments.removeIf(holder -> holder.is(Enchantments.SILK_TOUCH)));
		}
		int fortune = stats().fortune();
		if (fortune > 0) {
			// Keeps the tool's own Fortune when that is higher.
			tool.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), fortune);
		}
		return tool;
	}

	/** Wears the given tool in {@code slot} (Unbreaking counts), telling the owner when it wears out or breaks. */
	private void wearTool(GearSlot slot, int amount) {
		ItemStack stack = getGear(slot);
		if (stack.isEmpty() || amount <= 0 || !(this.level() instanceof ServerLevel level)) {
			return;
		}
		stack.hurtAndBreak(amount, level, null, broken -> {
			this.playSound(SoundEvents.ITEM_BREAK.value(), 0.8F, 0.8F + this.random.nextFloat() * 0.4F);
			level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, broken.getItem()), getX(), getY() + 1.2D, getZ(),
					8, 0.2D, 0.2D, 0.2D, 0.05D);
			tellOwner(Component.translatable("butterbot.msg.gear_broke", broken.getHoverName()).withStyle(ChatFormatting.RED));
		});
		if (stack.isEmpty()) {
			setGear(slot, ItemStack.EMPTY);
		} else {
			checkWear(slot, stack);
		}
	}

	/**
	 * Warns the owner once when a piece of gear is nearly worn out, and when an enchanted tool is put
	 * aside so it doesn't break (it is used again once repaired, by Mending too).
	 */
	private void checkWear(GearSlot slot, ItemStack stack) {
		if (stack.isEmpty() || !stack.isDamageableItem()) {
			wearWarnings.remove(slot);
			return;
		}
		int left = stack.getMaxDamage() - stack.getDamageValue();
		int warning = !slot.isArmor() && GearSlot.isResting(stack) ? 2 : left <= Math.max(5, stack.getMaxDamage() / 20) ? 1 : 0;
		int before = wearWarnings.getOrDefault(slot, 0);
		if (warning == 0) {
			wearWarnings.remove(slot);
			return;
		}
		wearWarnings.put(slot, warning);
		if (warning > before) {
			tellOwner(Component.translatable(warning == 2 ? "butterbot.msg.gear_resting" : "butterbot.msg.gear_low",
					stack.getHoverName(), left).withStyle(ChatFormatting.GOLD));
		}
	}

	/** Experience the bot picks up first mends gear with Mending (like a player's); the rest is kept for the owner. */
	private int mendGear(int xp) {
		if (xp <= 0 || !(this.level() instanceof ServerLevel level)) {
			return xp;
		}
		for (GearSlot slot : GearSlot.values()) {
			ItemStack stack = getGear(slot);
			if (stack.isEmpty() || !stack.isDamaged() || !EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP)) {
				continue;
			}
			int durability = EnchantmentHelper.modifyDurabilityToRepairFromXp(level, stack, xp);
			int repair = Math.min(durability, stack.getDamageValue());
			if (repair <= 0) {
				continue;
			}
			stack.setDamageValue(stack.getDamageValue() - repair);
			checkWear(slot, stack);
			xp -= repair * xp / durability;
			if (xp <= 0) {
				return 0;
			}
		}
		return xp;
	}

	/** Gear belongs to the owner: it drops whole when the bot is destroyed. */
	private void dropGear(ServerLevel level) {
		for (GearSlot slot : GearSlot.values()) {
			ItemStack stack = getGear(slot);
			if (!stack.isEmpty()) {
				setGear(slot, ItemStack.EMPTY);
				// Curse of Vanishing: gone, as with a player.
				if (!EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
					this.spawnAtLocation(level, stack);
				}
			}
		}
	}

	/** Worn armor takes wear like a player's (Unbreaking counts). */
	@Override
	public void hurtArmor(DamageSource source, float damage) {
		this.doHurtEquipment(source, damage, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
		checkArmorWear();
	}

	@Override
	public void hurtHelmet(DamageSource source, float damage) {
		this.doHurtEquipment(source, damage, EquipmentSlot.HEAD);
		checkArmorWear();
	}

	private void checkArmorWear() {
		for (GearSlot slot : GearSlot.values()) {
			if (slot.isArmor()) {
				checkWear(slot, getGear(slot));
			}
		}
	}

	@Override
	public void onEquippedItemBroken(ItemStack brokenItem, EquipmentSlot inSlot) {
		super.onEquippedItemBroken(brokenItem, inSlot);
		if (inSlot.isArmor()) {
			tellOwner(Component.translatable("butterbot.msg.armor_broke", brokenItem.getHoverName()).withStyle(ChatFormatting.RED));
		}
	}

	// ------------------------------------------------------------------ interaction

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}

		ItemStack held = player.getItemInHand(hand);

		if (this.level().isClientSide()) {
			return held.isEmpty() ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}

		ServerPlayer serverPlayer = (ServerPlayer) player;

		if (ownerUuid == null) {
			if (!held.isEmpty()) {
				return InteractionResult.PASS;
			}
			claim(serverPlayer);
			serverPlayer.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.claimed",
					this.getName())));
			return InteractionResult.SUCCESS_SERVER;
		}

		if (!isOwnedBy(player)) {
			serverPlayer.sendSystemMessage(Component.translatable("butterbot.msg.not_yours",
					ownerName == null ? "?" : ownerName).withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}

		if (!held.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (player.isShiftKeyDown()) {
			pickUp(serverPlayer);
			return InteractionResult.SUCCESS_SERVER;
		}

		ModNetworking.openBotScreen(serverPlayer, this);
		return InteractionResult.SUCCESS_SERVER;
	}

	public void claim(ServerPlayer player) {
		this.ownerUuid = player.getUUID();
		this.ownerName = player.getName().getString();
		if (!this.hasCustomName()) {
			this.setCustomName(Component.literal(defaultName()));
		}
		if (!skinVariantChosen) {
			chooseFreeSkinVariant();
		}
		linkToNearbyBase();
	}

	/** Picks the built-in skin the fewest of the owner's other bots wear, so five bots get five different skins. */
	private void chooseFreeSkinVariant() {
		if (!(this.level() instanceof ServerLevel serverLevel) || ownerUuid == null) {
			return;
		}
		int[] used = new int[SKIN_VARIANTS];
		for (ServerLevel level : serverLevel.getServer().getAllLevels()) {
			for (ButterBotEntity other : level.getEntities(ModEntities.BUTTER_BOT,
					bot -> bot != this && bot.isAlive() && ownerUuid.equals(bot.getOwnerUuid()))) {
				if (other.skinVariantChosen) {
					used[other.getSkinVariant()]++;
				}
			}
		}
		int best = 0;
		for (int i = 1; i < SKIN_VARIANTS; i++) {
			if (used[i] < used[best]) {
				best = i;
			}
		}
		setSkinVariant(best);
	}

	/** A cute name picked from the bot's UUID, so it stays the same if the name is reset. */
	public String defaultName() {
		UUID id = this.getUUID();
		String base = DEFAULT_NAMES[Math.floorMod(id.hashCode(), DEFAULT_NAMES.length)];
		return base + "-" + id.toString().substring(0, 2).toUpperCase(Locale.ROOT);
	}

	private void linkToNearbyBase() {
		if (basePos != null) {
			return;
		}
		BlockPos origin = this.blockPosition();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int r = 0; r <= 16; r++) {
			for (int x = -r; x <= r; x++) {
				for (int y = -4; y <= 4; y++) {
					for (int z = -r; z <= r; z++) {
						if (Math.max(Math.abs(x), Math.abs(z)) != r) {
							continue;
						}
						cursor.setWithOffset(origin, x, y, z);
						if (this.level().isLoaded(cursor) && this.level().getBlockState(cursor).is(ModBlocks.BOT_BASE)) {
							basePos = cursor.immutable();
							return;
						}
					}
				}
			}
		}
	}

	/** Packs the bot back into an item, keeping its name, skin, program and settings. */
	public void pickUp(ServerPlayer player) {
		dropInventoryAt(player);
		ItemStack stack = ButterBotItem.createFrom(this);
		if (!player.getInventory().add(stack)) {
			this.spawnAtLocation((ServerLevel) this.level(), stack);
		}
		this.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 0.8F);
		player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.picked_up", this.getName())));
		brain.reset(this);
		this.discard();
	}

	/** Sends the bot away for good (summon a new one any time). Its backpack and gear go to the player. */
	public void dismiss(ServerPlayer player) {
		dropInventoryAt(player);
		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.9D, getZ(), 16, 0.3D, 0.5D, 0.3D, 0.02D);
		}
		speak(ModSounds.VOICE_BYE, null, 0.0F, true);
		brain.reset(this);
		this.discard();
	}

	private void dropInventoryAt(Player player) {
		List<ItemStack> everything = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			everything.add(inventory.removeItemNoUpdate(i));
		}
		for (GearSlot slot : GearSlot.values()) {
			everything.add(getGear(slot));
			setGear(slot, ItemStack.EMPTY);
		}
		everything.addAll(recoveredItems);
		everything.addAll(fetchCarry);
		recoveredItems.clear();
		fetchCarry.clear();
		recoverPos = null;
		for (ItemStack stack : everything) {
			if (!stack.isEmpty() && !player.getInventory().add(stack)) {
				this.spawnAtLocation((ServerLevel) this.level(), stack);
			}
		}
		giveStoredXp(player);
	}

	/** Opens the bot's gear (armor and tools) and backpack. */
	public void openBackpack(ServerPlayer player) {
		Component title = Component.translatable("butterbot.gui.backpack_title", this.getName());
		BotInventoryMenu.OpenData data = new BotInventoryMenu.OpenData(this.getId(), inventory.getContainerSize());
		ButterBotEntity bot = this;
		player.openMenu(new ExtendedMenuProvider<BotInventoryMenu.OpenData>() {
			@Override
			public BotInventoryMenu.OpenData getScreenOpeningData(ServerPlayer opener) {
				return data;
			}

			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player opener) {
				return new BotInventoryMenu(containerId, playerInventory, new BotGear(bot), new BotBackpack(bot), data);
			}
		});
	}

	// ------------------------------------------------------------------ status text

	public Component describeStatus() {
		return Component.translatable("butterbot.status.full",
				describeShortStatus(),
				getBotLevel(),
				countInventoryItems(),
				inventory.getContainerSize() * 64);
	}

	/** The status line; bots with a job say which job and what part of it they are doing. */
	public Component describeShortStatus() {
		Component taskStatus = describeTaskStatus();
		if (!jobs.hasJob()) {
			return taskStatus;
		}
		Component jobName = jobs.job().displayName();
		if (jobs.isPaused()) {
			return Component.translatable("butterbot.status.job_paused", jobName);
		}
		if (task != BotTask.NONE || returnRequested || combatTicks > 0 || recoverPos != null || !recoveredItems.isEmpty()) {
			return Component.translatable("butterbot.status.job_busy", jobName, taskStatus);
		}
		return switch (jobs.phase()) {
			case SETUP -> Component.translatable("butterbot.status.job_setup", jobName);
			case CHECKING -> Component.translatable("butterbot.status.job_checking", jobName);
			case TRAVEL, WORKING -> Component.translatable("butterbot.status.job_travel", jobName);
			case DEPOSIT -> Component.translatable("butterbot.status.job_deposit", jobName);
			case STANDBY -> Component.translatable("butterbot.status.job_standby", jobName);
			case NOT_FOUND -> Component.translatable("butterbot.status.job_not_found", jobName);
			case CHEST_PROBLEM -> Component.translatable("butterbot.status.job_chest_problem", jobName);
			case PATROL -> Component.translatable("butterbot.status.job_patrol", jobName, jobs.radius(), jobs.stat());
			case LOOT -> Component.translatable("butterbot.status.job_loot", jobName);
			case FARMING -> Component.translatable("butterbot.status.job_farming", jobName);
			case RANCHING -> Component.translatable("butterbot.status.job_ranching", jobName, jobs.radius(), jobs.stat());
			case LOADING -> Component.translatable("butterbot.status.job_loading", jobName);
			case CARRYING -> Component.translatable("butterbot.status.job_carrying", jobName, jobs.stat());
			case SMELTING -> Component.translatable("butterbot.status.job_smelting", jobName);
			case WAITING -> Component.translatable("butterbot.status.job_waiting", jobName, jobs.stat());
			case ESCORTING -> Component.translatable("butterbot.status.job_escorting", jobName, wardName(), jobs.stat());
			case NO_WARD -> Component.translatable("butterbot.status.job_no_ward", jobName);
		};
	}

	private Component wardName() {
		ButterBotEntity ward = findWard();
		return ward == null ? Component.literal("?") : ward.getName();
	}

	/** A few words for the on-screen list: only what the bot is doing right now. */
	public Component describeHudStatus() {
		if (jobs.hasJob() && jobs.isPaused()) {
			return Component.translatable("butterbot.hud.s.paused");
		}
		if (combatTicks > 0) {
			return Component.translatable("butterbot.hud.s.fighting");
		}
		if (recoverPos != null || !recoveredItems.isEmpty()) {
			return Component.translatable("butterbot.hud.s.recovering");
		}
		if (returnRequested || ((task == BotTask.MINE || task == BotTask.TUNNEL) && isInventoryFull())) {
			return Component.translatable("butterbot.hud.s.delivering");
		}
		return switch (task) {
			case NONE -> !jobs.hasJob() ? Component.translatable("butterbot.hud.s.idle") : switch (jobs.phase()) {
				case SETUP -> Component.translatable("butterbot.hud.s.setup");
				case CHECKING -> Component.translatable("butterbot.hud.s.checking");
				case TRAVEL, WORKING -> Component.translatable("butterbot.hud.s.travel");
				case DEPOSIT, LOOT -> Component.translatable("butterbot.hud.s.storing");
				case STANDBY -> Component.translatable("butterbot.hud.s.done");
				case NOT_FOUND -> Component.translatable("butterbot.hud.s.not_found");
				case CHEST_PROBLEM -> Component.translatable("butterbot.hud.s.chest_problem");
				case PATROL -> Component.translatable("butterbot.hud.s.patrol", jobs.stat());
				case FARMING -> Component.translatable("butterbot.hud.s.farming", harvestedCount);
				case RANCHING -> Component.translatable("butterbot.hud.s.ranching", jobs.stat());
				case LOADING -> Component.translatable("butterbot.hud.s.loading");
				case CARRYING -> Component.translatable("butterbot.hud.s.carrying");
				case SMELTING -> Component.translatable("butterbot.hud.s.smelting");
				case WAITING -> Component.translatable("butterbot.hud.s.courier_wait");
				case ESCORTING -> Component.translatable("butterbot.hud.s.escorting", wardName());
				case NO_WARD -> Component.translatable("butterbot.hud.s.no_ward");
			};
			case MINE -> mineFilter == null && resource == null ? Component.translatable("butterbot.hud.s.idle")
					: Component.translatable("butterbot.hud.s.mining",
					mineFilter != null ? new ItemStack(mineFilter.item()).getHoverName() : resource.displayName(), collectedCount, targetCount);
			case TUNNEL -> Component.translatable("butterbot.hud.s.tunnel", tunnelProgress, tunnelLength);
			case CRAFT -> craftItem == null ? Component.translatable("butterbot.hud.s.idle")
					: Component.translatable("butterbot.hud.s.crafting", new ItemStack(craftItem).getHoverName());
			case GO_TO_BASE -> Component.translatable("butterbot.hud.s.home");
			case FOLLOW -> Component.translatable("butterbot.hud.s.follow");
			case LUA -> Component.translatable("butterbot.hud.s.program");
			case FARM -> Component.translatable("butterbot.hud.s.farming", harvestedCount);
			case SORT -> Component.translatable("butterbot.hud.s.sorting");
			case LIGHT -> Component.translatable("butterbot.hud.s.lighting", torchesPlaced);
			case FETCH -> fetchItem == null ? Component.translatable("butterbot.hud.s.idle")
					: Component.translatable("butterbot.hud.s.fetching", new ItemStack(fetchItem).getHoverName());
			case GOTO -> Component.translatable("butterbot.hud.s.waiting");
		};
	}

	/** An item that stands for the bot's work in the on-screen list (air when it has nothing to do). */
	public Item hudIcon() {
		if (combatTicks > 0) {
			return Items.IRON_SWORD;
		}
		if (returnRequested) {
			return Items.CHEST;
		}
		switch (jobs.job()) {
			case MINER -> {
				return Items.IRON_PICKAXE;
			}
			case GATHERER -> {
				return Items.IRON_AXE;
			}
			case GUARD -> {
				return Items.IRON_SWORD;
			}
			case FARMER -> {
				return Items.IRON_HOE;
			}
			case RANCHER -> {
				return Items.WHEAT;
			}
			case COURIER -> {
				return Items.CHEST_MINECART;
			}
			case BODYGUARD -> {
				return Items.SHIELD;
			}
			case NONE -> {
			}
		}
		return switch (task) {
			case NONE -> Items.AIR;
			case MINE -> resource == BotResource.WOOD ? Items.IRON_AXE : resource == BotResource.SAND ? Items.IRON_SHOVEL : Items.IRON_PICKAXE;
			case TUNNEL -> Items.IRON_PICKAXE;
			case CRAFT -> Items.CRAFTING_TABLE;
			case GO_TO_BASE -> Items.COMPASS;
			case FOLLOW -> Items.LEAD;
			case LUA -> Items.REDSTONE;
			case FARM -> Items.IRON_HOE;
			case SORT -> Items.CHEST;
			case LIGHT -> Items.TORCH;
			case FETCH -> Items.BUNDLE;
			case GOTO -> Items.MAP;
		};
	}

	private Component describeTaskStatus() {
		if (combatTicks > 0) {
			return Component.translatable("butterbot.status.fighting");
		}
		if (recoverPos != null || !recoveredItems.isEmpty()) {
			return Component.translatable("butterbot.status.recovering");
		}
		if (returnRequested || ((task == BotTask.MINE || task == BotTask.TUNNEL) && isInventoryFull())) {
			if (isJobDriven() && jobs.chest() != null) {
				return Component.translatable("butterbot.status.delivering_job");
			}
			return Component.translatable(deliveryMode == DeliveryMode.BASE
					? "butterbot.status.delivering_base" : "butterbot.status.delivering_owner");
		}
		return switch (task) {
			case NONE -> Component.translatable("butterbot.status.idle");
			case MINE -> mineFilter != null
					? Component.translatable("butterbot.status.job_mining", new ItemStack(mineFilter.item()).getHoverName(),
					collectedCount, targetCount)
					: resource == null
					? Component.translatable("butterbot.status.idle")
					: Component.translatable("butterbot.status.mining", resource.displayName(), collectedCount, targetCount);
			case TUNNEL -> Component.translatable("butterbot.status.tunnel", tunnelProgress, tunnelLength);
			case CRAFT -> craftItem == null
					? Component.translatable("butterbot.status.idle")
					: Component.translatable("butterbot.status.crafting", craftCount, new ItemStack(craftItem).getHoverName());
			case GO_TO_BASE -> Component.translatable("butterbot.status.going_base");
			case FOLLOW -> Component.translatable("butterbot.status.following");
			case LUA -> Component.translatable("butterbot.status.program");
			case FARM -> Component.translatable("butterbot.status.farming", harvestedCount);
			case SORT -> Component.translatable("butterbot.status.sorting");
			case LIGHT -> Component.translatable("butterbot.status.lighting", torchesPlaced);
			case FETCH -> fetchItem == null
					? Component.translatable("butterbot.status.idle")
					: Component.translatable("butterbot.status.fetching", new ItemStack(fetchItem).getHoverName(),
					getFetchCarried(), fetchCount);
			case GOTO -> gotoPos == null
					? Component.translatable("butterbot.status.idle")
					: Component.translatable("butterbot.status.waiting_at", gotoPos.toShortString());
		};
	}

	// ------------------------------------------------------------------ skin

	public String getSkinId() {
		return this.entityData.get(DATA_SKIN);
	}

	public void setSkinId(String skinId) {
		this.entityData.set(DATA_SKIN, SkinLimits.isValidSkinId(skinId) ? skinId : "");
	}

	/** Which built-in skin the bot wears when it has no custom one (0 to {@link #SKIN_VARIANTS} - 1). */
	public int getSkinVariant() {
		return Math.floorMod(this.entityData.get(DATA_SKIN_VARIANT), SKIN_VARIANTS);
	}

	public void setSkinVariant(int variant) {
		this.entityData.set(DATA_SKIN_VARIANT, Math.floorMod(variant, SKIN_VARIANTS));
		this.skinVariantChosen = true;
	}

	/** Cat ears and a tail drawn on top of the skin. */
	public boolean isNeko() {
		return this.entityData.get(DATA_NEKO);
	}

	public void setNeko(boolean neko) {
		this.entityData.set(DATA_NEKO, neko);
	}

	public boolean isWorkingClient() {
		return this.entityData.get(DATA_WORKING);
	}

	// ------------------------------------------------------------------ levels and settings

	public int getBotLevel() {
		return this.entityData.get(DATA_LEVEL);
	}

	public int getWorkXp() {
		return workXp;
	}

	public int getStoredXp() {
		return storedXp;
	}

	public void addStoredXp(int amount) {
		storedXp = Math.max(0, storedXp + amount);
	}

	/** Restores progress saved in an item or in the world (load {@link #getProgress()} first). */
	public void restoreProgress(int level, int xp) {
		this.entityData.set(DATA_LEVEL, Math.max(1, Math.min(BotLevels.MAX_LEVEL, level)));
		this.workXp = Math.max(0, xp);
		applyLevelStats();
	}

	public BotProgress getProgress() {
		return progress;
	}

	/** What the bot can do now (level + skills + job mastery). */
	public BotStats stats() {
		int level = getBotLevel();
		BotStats stats = cachedStats;
		if (stats == null || stats.level() != level) {
			stats = BotStats.of(level, progress);
			cachedStats = stats;
		}
		return stats;
	}

	public int getSkillPoints() {
		return progress.availablePoints(getBotLevel());
	}

	public long getSecondWindReadyAt() {
		return secondWindReadyAt;
	}

	public void setSecondWindReadyAt(long gameTime) {
		this.secondWindReadyAt = gameTime;
	}

	public void addLifetimeStat(BotProgress.Stat stat, int amount) {
		progress.addStat(stat, amount);
	}

	/**
	 * Work experience: mined blocks, harvested crops, crafted batches, smelted items, defeated monsters.
	 * Work done for the bot's job also counts towards mastering that job (even at the top level).
	 */
	public void gainWorkXp(int amount) {
		if (amount <= 0) {
			return;
		}
		BotJob duty = dutyJob();
		if (duty != null) {
			gainMastery(duty, amount);
		}
		int level = getBotLevel();
		if (level >= BotLevels.MAX_LEVEL) {
			return;
		}
		workXp += amount;
		int start = level;
		while (level < BotLevels.MAX_LEVEL && workXp >= BotLevels.xpToNext(level)) {
			workXp -= BotLevels.xpToNext(level);
			level++;
		}
		if (level == start) {
			return;
		}
		if (level >= BotLevels.MAX_LEVEL) {
			workXp = 0;
		}
		this.entityData.set(DATA_LEVEL, level);
		applyLevelStats();
		this.setHealth(this.getMaxHealth());
		celebrateLevelUp(start, level);
	}

	/** The job the current work is for, if it is job work (guards get theirs from fights instead). */
	@Nullable
	private BotJob dutyJob() {
		if (!jobs.hasJob() || jobs.isPaused()) {
			return null;
		}
		return switch (jobs.job()) {
			case MINER, GATHERER -> isJobDriven() ? jobs.job() : null;
			case FARMER -> task == BotTask.FARM ? BotJob.FARMER : null;
			// Ranch chores (and culling) happen between orders.
			case RANCHER -> task == BotTask.NONE ? BotJob.RANCHER : null;
			case GUARD, COURIER, BODYGUARD, NONE -> null;
		};
	}

	private void gainMastery(BotJob job, int amount) {
		int rank = progress.addMasteryXp(job, amount);
		if (rank > 0) {
			onMasteryRankUp(job, rank);
		}
	}

	/** Fireworks, a title on the owner's screen for big levels, and a chat line telling what is new. */
	private void celebrateLevelUp(int from, int to) {
		this.playSound(SoundEvents.PLAYER_LEVELUP, 0.8F, 1.2F);
		this.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.7F, 1.0F);
		speak(ModSounds.VOICE_LEVEL_UP, null, 0.0F, true);
		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.FIREWORK, getX(), getY() + 1.2D, getZ(), 40, 0.5D, 0.8D, 0.5D, 0.12D);
			serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, getX(), getY() + 1.0D, getZ(), 25, 0.4D, 0.6D, 0.4D, 0.25D);
			serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 1.0D, getZ(), 15, 0.4D, 0.6D, 0.4D, 0.1D);
		}
		boolean milestone = false;
		Component highlight = null;
		for (int level = from + 1; level <= to; level++) {
			tellOwner(Component.translatable("butterbot.msg.level_up_named", getName(), level).append(" ").append(perkText(level))
					.append(" ").append(Component.translatable("butterbot.msg.skill_point_gained")));
			BotStats.Ability ability = BotStats.Ability.at(level);
			if (ability != null || level % 5 == 0 || isSkillRowLevel(level)) {
				milestone = true;
				highlight = ability != null ? ability.displayName()
						: isSkillRowLevel(level) ? Component.translatable("butterbot.levelup.new_skills_short") : null;
			}
		}
		if (getOwner() instanceof ServerPlayer owner) {
			if (milestone) {
				MutableComponent subtitle = getName().copy();
				if (highlight != null) {
					subtitle.append(" - ").append(highlight);
				}
				showTitle(owner, Component.translatable("butterbot.title.level", to).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), subtitle);
			} else {
				owner.sendSystemMessage(Component.translatable("butterbot.msg.level_up_bar", getName(), to)
						.withStyle(ChatFormatting.GOLD), true);
			}
		}
	}

	private static boolean isSkillRowLevel(int level) {
		for (int rowLevel : BotSkill.ROW_LEVEL) {
			if (rowLevel > 1 && rowLevel == level) {
				return true;
			}
		}
		return false;
	}

	/** What reaching {@code level} brings. */
	private static Component perkText(int level) {
		BotStats.Ability ability = BotStats.Ability.at(level);
		MutableComponent text = ability != null
				? Component.translatable("butterbot.levelup.ability", ability.displayName(), ability.description())
				: level <= 10 ? Component.translatable("butterbot.levelup.perk." + level)
				: Component.translatable("butterbot.levelup.perk.late");
		if (isSkillRowLevel(level)) {
			text.append(" ").append(Component.translatable("butterbot.levelup.new_skills"));
		}
		return text;
	}

	private void onMasteryRankUp(BotJob job, int rank) {
		applyLevelStats();
		Component rankName = Component.translatable("butterbot.mastery.rank." + rank);
		MutableComponent message = Component.translatable("butterbot.msg.mastery_up", getName(), job.displayName(), rankName);
		if (BotProgress.masteryPoints(rank) > BotProgress.masteryPoints(rank - 1)) {
			message.append(" ").append(Component.translatable("butterbot.msg.skill_point_gained"));
		}
		tellOwner(message.withStyle(ChatFormatting.AQUA));
		this.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6F, 1.0F);
		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.FIREWORK, getX(), getY() + 1.2D, getZ(), 25, 0.4D, 0.7D, 0.4D, 0.1D);
		}
		if (getOwner() instanceof ServerPlayer owner) {
			showTitle(owner, Component.translatable("butterbot.title.mastery", job.displayName(), rankName)
					.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), getName());
		}
	}

	private static void showTitle(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	/** Spends a skill point on {@code skill}. Returns null when learned, otherwise the reason it can't be. */
	@Nullable
	public Component raiseSkill(BotSkill skill) {
		BotProgress.Refusal refusal = progress.raise(skill, getBotLevel());
		if (refusal != null) {
			BotSkill previous = skill.previous();
			return switch (refusal) {
				case NO_POINTS -> Component.translatable("butterbot.msg.skill_no_points");
				case MAXED -> Component.translatable("butterbot.msg.skill_maxed", skill.displayName());
				case LEVEL -> Component.translatable("butterbot.msg.skill_level", skill.displayName(), skill.requiredLevel());
				case PREVIOUS -> Component.translatable("butterbot.msg.skill_previous",
						previous == null ? Component.empty() : previous.displayName());
			};
		}
		applyLevelStats();
		int rank = progress.rank(skill);
		say(Component.translatable("butterbot.msg.skill_learned", skill.displayName(), rank, skill.maxRank(), skill.effect(rank)));
		this.playSound(SoundEvents.ENCHANTMENT_TABLE_USE, 0.8F, 1.2F);
		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 1.2D, getZ(), 30, 0.4D, 0.6D, 0.4D, 0.5D);
		}
		return null;
	}

	/** Forgets all skills and gives the points back. */
	public void resetSkills() {
		int refunded = progress.resetSkills();
		applyLevelStats();
		say(Component.translatable("butterbot.msg.skills_reset", refunded));
	}

	/** Applies the level, skills and mastery to the bot's attributes and backpack. */
	private void applyLevelStats() {
		progress.fixOverspent(getBotLevel());
		cachedStats = null;
		BotStats stats = stats();
		setBaseAttribute(Attributes.MAX_HEALTH, stats.maxHealth());
		if (this.getHealth() > this.getMaxHealth()) {
			this.setHealth(this.getMaxHealth());
		}
		setBaseAttribute(Attributes.ATTACK_DAMAGE, stats.attackDamage());
		setBaseAttribute(Attributes.ARMOR, stats.armor());
		setBaseAttribute(Attributes.MOVEMENT_SPEED, BotStats.BASE_SPEED * stats.speedMultiplier());
		setBaseAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY, stats.swimmer() ? 1.0D : 0.0D);
		setBaseAttribute(Attributes.FALL_DAMAGE_MULTIPLIER, stats.feather() ? 0.0D : 1.0D);
		resizeBackpack(stats.backpackSlots());
	}

	private void setBaseAttribute(Holder<Attribute> attribute, double value) {
		AttributeInstance instance = this.getAttribute(attribute);
		if (instance != null && instance.getBaseValue() != value) {
			instance.setBaseValue(value);
		}
	}

	/**
	 * Grows the backpack right away. It only shrinks (after forgetting Magic Pockets) once everything
	 * in it fits in the smaller one, so nothing is ever lost.
	 */
	private void resizeBackpack(int size) {
		int current = inventory.getContainerSize();
		if (size == current) {
			return;
		}
		SimpleContainer resized = new SimpleContainer(size);
		if (size > current) {
			for (int i = 0; i < current; i++) {
				resized.setItem(i, inventory.getItem(i));
			}
		} else {
			int used = 0;
			for (int i = 0; i < current; i++) {
				if (!inventory.getItem(i).isEmpty()) {
					used++;
				}
			}
			if (used > size) {
				return;
			}
			int slot = 0;
			for (int i = 0; i < current; i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.isEmpty()) {
					resized.setItem(slot++, stack);
				}
			}
		}
		inventory = resized;
	}

	/** Abilities that work all the time: the item magnet, breathing under water and the legend's sparkle. */
	private void tickAbilities() {
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		BotStats stats = stats();
		if (stats.swimmer() && this.isUnderWater() && this.getAirSupply() < this.getMaxAirSupply()) {
			this.setAirSupply(this.getMaxAirSupply());
		}
		if (stats.magnetRadius() > 0 && this.tickCount % 10 == 0) {
			magnetPickup(serverLevel, stats.magnetRadius());
		}
		if (stats.has(BotStats.Ability.LEGEND) && this.tickCount % 40 == 0) {
			serverLevel.sendParticles(ParticleTypes.WAX_ON, getX(), getY() + 1.0D, getZ(), 3, 0.35D, 0.6D, 0.35D, 0.0D);
		}
	}

	/**
	 * Magnet skill: while mining, farming or guarding, loose drops around the bot fly into its backpack.
	 * Things someone threw, things right next to the owner and junk a mining run leaves behind are not
	 * touched, and it is off while the bot is rescuing the owner's things.
	 */
	private void magnetPickup(ServerLevel level, int radius) {
		boolean working = task == BotTask.MINE || task == BotTask.FARM
				|| (jobs.isActive() && (jobs.job() == BotJob.GUARD || jobs.job() == BotJob.RANCHER));
		if (!working || recoverPos != null || !recoveredItems.isEmpty() || isInventoryFull()) {
			return;
		}
		Player owner = getOwner();
		List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(radius),
				drop -> drop.isAlive() && !drop.hasPickUpDelay() && drop.getOwner() == null && !drop.getItem().isEmpty()
						&& (owner == null || drop.distanceToSqr(owner) > 5.0D * 5.0D));
		boolean picked = false;
		for (ItemEntity drop : drops) {
			ItemStack stack = drop.getItem();
			if (isJunkWhileMining(stack)) {
				continue;
			}
			int before = stack.getCount();
			ItemStack leftover = inventory.addItem(stack.copy());
			int taken = before - leftover.getCount();
			if (taken <= 0) {
				continue;
			}
			picked = true;
			if (isWantedLoot(stack)) {
				countCollected(taken);
			}
			if (leftover.isEmpty()) {
				drop.discard();
			} else {
				drop.setItem(leftover);
			}
		}
		if (picked) {
			this.playSound(SoundEvents.ITEM_PICKUP, 0.25F, 1.4F + this.random.nextFloat() * 0.4F);
		}
	}

	public boolean isGuarding() {
		// Ranchers keep monsters away from their animals.
		return guard || (jobs.isActive() && (jobs.job() == BotJob.GUARD || jobs.job() == BotJob.RANCHER || jobs.job() == BotJob.BODYGUARD));
	}

	/** The guard setting itself (a guard job fights even when it is off). */
	public boolean guardSetting() {
		return guard;
	}

	public void setGuarding(boolean guard) {
		this.guard = guard;
	}

	public boolean placesTorchesWhileMining() {
		return autoTorch;
	}

	public void setAutoTorch(boolean autoTorch) {
		this.autoTorch = autoTorch;
	}

	public boolean feedsOwner() {
		return feedOwner;
	}

	public void setFeedOwner(boolean feedOwner) {
		this.feedOwner = feedOwner;
	}

	public SmeltMode getSmeltMode() {
		return smeltMode;
	}

	public void setSmeltMode(SmeltMode smeltMode) {
		this.smeltMode = smeltMode;
	}

	// ------------------------------------------------------------------ ownership

	@Nullable
	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	@Nullable
	public String getOwnerName() {
		return ownerName;
	}

	public void setOwner(@Nullable UUID uuid, @Nullable String name) {
		this.ownerUuid = uuid;
		this.ownerName = name;
	}

	public boolean isOwnedBy(Player player) {
		return player.getUUID().equals(ownerUuid);
	}

	@Nullable
	public Player getOwner() {
		return ownerUuid == null ? null : this.level().getPlayerByUUID(ownerUuid);
	}

	public void release() {
		this.ownerUuid = null;
		this.ownerName = null;
		this.basePos = null;
		this.setCustomName(null);
		this.jobs.setJob(BotJob.NONE, item -> false);
		clearTask();
		say(Component.translatable("butterbot.msg.free"));
	}

	// ------------------------------------------------------------------ speech

	public void say(Component message) {
		this.entityData.set(DATA_SPEECH, message);
		this.speechTicks = Math.min(260, 80 + message.getString().length() * 2);
	}

	public void say(String literal) {
		say(Component.literal(literal));
	}

	public Component getSpeech() {
		return this.entityData.get(DATA_SPEECH);
	}

	public void setRoute(@Nullable BlockPos target, @Nullable PathFollower follower) {
		this.routeTarget = target == null ? null : target.immutable();
		this.routeFollower = follower;
	}

	@Nullable
	public BlockPos getRouteTarget() {
		return routeTarget;
	}

	/** The dig route being followed, or null when walking normally. */
	@Nullable
	public DigPath getRoute() {
		return routeFollower == null || !routeFollower.isActive() ? null : routeFollower.path();
	}

	public int getRouteProgress() {
		return routeFollower == null ? 0 : routeFollower.progress();
	}

	/** Something went wrong (a block it can't get to...): remembered for the debug key, and the owner is told now and then. */
	public void reportProblem(Component problem) {
		long now = this.level().getGameTime();
		lastProblem = problem;
		lastProblemTime = now;
		if (now - lastProblemMessage >= PROBLEM_MESSAGE_TICKS) {
			lastProblemMessage = now;
			tellOwner(problem.copy().withStyle(ChatFormatting.GOLD));
		}
	}

	/** The last problem if it happened in the last few minutes. */
	@Nullable
	public Component getLastProblem() {
		return lastProblem != null && this.level().getGameTime() - lastProblemTime < PROBLEM_MEMORY_TICKS ? lastProblem : null;
	}

	public void tellOwner(Component message) {
		say(message);
		if (getOwner() instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Msg.prefixed(this.getName().copy().append(": ").append(message)));
		}
	}

	public void warnLava() {
		long now = this.level().getGameTime();
		if (now - lastLavaWarning > 200L) {
			lastLavaWarning = now;
			say(Component.translatable("butterbot.msg.lava_ahead"));
			speak(ModSounds.VOICE_DANGER);
		}
	}

	// ------------------------------------------------------------------ tasks

	public BotTask getTask() {
		return task;
	}

	public BotState getBotState() {
		return state;
	}

	public void setBotState(BotState state) {
		this.state = state;
	}

	@Nullable
	public BotResource getResource() {
		return resource;
	}

	public int getTargetCount() {
		return targetCount;
	}

	public int getMineRun() {
		return mineRun;
	}

	public int getCollectedCount() {
		return collectedCount;
	}

	public DeliveryMode getDeliveryMode() {
		return deliveryMode;
	}

	public void setDeliveryMode(DeliveryMode deliveryMode) {
		this.deliveryMode = deliveryMode;
	}

	/** Starts a mining run. False (and the owner is told why) when it can't be dug here or by this bot. */
	public boolean assignMineTask(BotResource resource, int count) {
		if (!resource.foundIn(this.level().dimension())) {
			tellOwner(Component.translatable(resource.isNether() ? "butterbot.msg.mine_needs_nether" : "butterbot.msg.mine_needs_overworld",
					resource.displayName()).withStyle(ChatFormatting.RED));
			return false;
		}
		if (resource == BotResource.NETHERITE && !canMineNetherite()) {
			tellOwner(Component.translatable("butterbot.msg.netherite_needs_level", NETHERITE_MIN_LEVEL).withStyle(ChatFormatting.RED));
			return false;
		}
		clearTask();
		this.task = BotTask.MINE;
		this.resource = resource;
		this.targetCount = count;
		this.state = BotState.SEARCHING;
		this.mineRun++;
		requestRescan();
		say(Component.translatable("butterbot.msg.start_mining", count, resource.displayName()));
		speak(ModSounds.VOICE_OK);
		if (resource.isNether() && !stats().has(BotStats.Ability.FIRE_IMMUNE)) {
			tellOwner(Component.translatable("butterbot.msg.nether_fire_tip", BotStats.Ability.FIRE_IMMUNE.level())
					.withStyle(ChatFormatting.GOLD));
		}
		return true;
	}

	public void assignTunnelTask(BlockPos origin, Direction direction, int length, int width, int height, boolean torches) {
		clearTask();
		this.task = BotTask.TUNNEL;
		this.tunnelDirection = direction;
		this.tunnelOrigin = origin;
		this.tunnelLength = length;
		this.tunnelProgress = 0;
		this.tunnelWidth = width;
		this.tunnelHeight = height;
		this.tunnelTorches = torches;
		this.state = BotState.MOVING_TO_TARGET;
		say(Component.translatable("butterbot.msg.start_tunnel", width, height, origin.toShortString(), length,
				Component.translatable("butterbot.direction." + direction.getName())));
		speak(ModSounds.VOICE_OK);
	}

	public void assignGoToBaseTask() {
		if (basePos == null) {
			tellOwner(Component.translatable("butterbot.msg.no_base").withStyle(ChatFormatting.RED));
			return;
		}
		clearTask();
		this.task = BotTask.GO_TO_BASE;
		this.state = BotState.RETURNING;
		say(Component.translatable("butterbot.msg.going_base"));
		speak(ModSounds.VOICE_OK);
	}

	public void assignFollowTask() {
		clearTask();
		this.task = BotTask.FOLLOW;
		this.state = BotState.MOVING_TO_TARGET;
		say(Component.translatable("butterbot.msg.following"));
		speak(ModSounds.VOICE_OK);
	}

	public void assignLuaTask() {
		clearTask();
		this.task = BotTask.LUA;
		say(Component.translatable("butterbot.msg.running_program"));
		speak(ModSounds.VOICE_OK);
	}

	public void assignCraftTask(Item item, int count) {
		clearTask();
		this.task = BotTask.CRAFT;
		this.craftItem = item;
		this.craftCount = count;
		this.state = BotState.SEARCHING;
		say(Component.translatable("butterbot.msg.start_crafting"));
		speak(ModSounds.VOICE_OK);
	}

	/** Harvests and replants everything around the Bot Base (or around the bot's current spot). */
	public void assignFarmTask() {
		clearTask();
		this.task = BotTask.FARM;
		this.state = BotState.SEARCHING;
		this.farmCenter = getBase() != null ? basePos : this.blockPosition();
		this.harvestedCount = 0;
		say(Component.translatable("butterbot.msg.start_farming", FarmTaskAction.RADIUS));
		speak(ModSounds.VOICE_OK);
	}

	/** Tidies the chests and barrels around the Bot Base (or around the bot). */
	public void assignSortTask() {
		clearTask();
		this.task = BotTask.SORT;
		this.state = BotState.SEARCHING;
		say(Component.translatable("butterbot.msg.start_sorting"));
		speak(ModSounds.VOICE_OK);
	}

	/** Farms around a spot the owner pointed at (the Bot Base still wins if the bot has one). */
	public void assignFarmTask(BlockPos center) {
		assignFarmTask();
		this.farmCenter = center.immutable();
	}

	/** Puts torches on every dark spot within {@code radius} of {@code center} so no monsters spawn there. */
	public void assignLightTask(BlockPos center, int radius) {
		clearTask();
		this.task = BotTask.LIGHT;
		this.state = BotState.SEARCHING;
		this.lightCenter = center.immutable();
		this.lightRadius = Math.clamp(radius, 4, LightAreaAction.MAX_RADIUS);
		this.torchesPlaced = 0;
		say(Component.translatable("butterbot.msg.start_lighting", this.lightRadius));
		speak(ModSounds.VOICE_OK);
	}

	/** Brings {@code count} of {@code item} from the backpack, the Bot Base and the chests around it. */
	public void assignFetchTask(Item item, int count) {
		clearTask();
		this.task = BotTask.FETCH;
		this.state = BotState.SEARCHING;
		this.fetchItem = item;
		this.fetchCount = Math.max(1, count);
		say(Component.translatable("butterbot.msg.start_fetching", this.fetchCount, new ItemStack(item).getHoverName()));
		speak(ModSounds.VOICE_OK);
	}

	/** Walks to {@code pos} and waits there. */
	public void assignGoToTask(BlockPos pos) {
		clearTask();
		this.task = BotTask.GOTO;
		this.state = BotState.MOVING_TO_TARGET;
		this.gotoPos = pos.immutable();
		say(Component.translatable("butterbot.msg.goto_start"));
		speak(ModSounds.VOICE_OK);
	}

	@Nullable
	public BlockPos getLightCenter() {
		return lightCenter;
	}

	public int getLightRadius() {
		return lightRadius;
	}

	public int getTorchesPlaced() {
		return torchesPlaced;
	}

	public void countTorchPlaced() {
		torchesPlaced++;
	}

	@Nullable
	public Item getFetchItem() {
		return fetchItem;
	}

	public int getFetchCount() {
		return fetchCount;
	}

	public int getFetchCarried() {
		int carried = 0;
		for (ItemStack stack : fetchCarry) {
			carried += stack.getCount();
		}
		return carried;
	}

	public void addFetchCarry(ItemStack stack) {
		if (!stack.isEmpty()) {
			fetchCarry.add(stack);
		}
	}

	/** Moves up to {@code amount} of {@code item} from the backpack into the fetch bundle. */
	public void moveToFetchCarry(Item item, int amount) {
		for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				ItemStack part = stack.split(Math.min(amount, stack.getCount()));
				inventory.setItem(i, stack);
				amount -= part.getCount();
				fetchCarry.add(part);
			}
		}
	}

	/** Gives the fetched items to the player (whatever doesn't fit lands at their feet). */
	public void handOverFetch(Player player) {
		for (ItemStack stack : fetchCarry) {
			giveOrDrop(player, stack);
		}
		fetchCarry.clear();
		speak(ModSounds.VOICE_GIVE, null, 0.0F, true);
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		this.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
	}

	private void giveOrDrop(Player player, ItemStack stack) {
		if (!stack.isEmpty() && !player.getInventory().add(stack) && !stack.isEmpty()) {
			BehaviorUtils.throwItem(this, stack, player.position().add(0.0D, 0.5D, 0.0D));
		}
	}

	/** Items from an unfinished fetch go back into the backpack (or onto the ground if it is full). */
	private void stashFetchCarry() {
		for (ItemStack stack : fetchCarry) {
			ItemStack leftover = inventory.addItem(stack);
			if (!leftover.isEmpty() && this.level() instanceof ServerLevel serverLevel) {
				this.spawnAtLocation(serverLevel, leftover);
			}
		}
		fetchCarry.clear();
	}

	@Nullable
	public BlockPos getGotoPos() {
		return gotoPos;
	}

	// ------------------------------------------------------------------ keeping the owner's things safe

	/** The owner died at {@code pos}: go and pick up everything they dropped. */
	public void startRecovery(BlockPos pos) {
		this.recoverPos = pos.immutable();
		this.miner.cancel();
		say(Component.translatable("butterbot.msg.recover_start"));
		speak(ModSounds.VOICE_RESCUE, null, 0.0F, true);
	}

	@Nullable
	public BlockPos getRecoverPos() {
		return recoverPos;
	}

	public boolean hasRecoveredItems() {
		return !recoveredItems.isEmpty();
	}

	public void addRecovered(ItemStack stack) {
		for (ItemStack kept : recoveredItems) {
			if (ItemStack.isSameItemSameComponents(kept, stack) && kept.getCount() < kept.getMaxStackSize()) {
				int move = Math.min(stack.getCount(), kept.getMaxStackSize() - kept.getCount());
				kept.grow(move);
				stack.shrink(move);
				if (stack.isEmpty()) {
					return;
				}
			}
		}
		if (!stack.isEmpty()) {
			recoveredItems.add(stack);
		}
	}

	private int countRecovered() {
		int count = 0;
		for (ItemStack stack : recoveredItems) {
			count += stack.getCount();
		}
		return count;
	}

	/** Done picking things up at the death spot. */
	public void finishCollecting() {
		this.recoverPos = null;
		if (recoveredItems.isEmpty()) {
			tellOwner(Component.translatable("butterbot.msg.recover_nothing"));
		} else {
			tellOwner(Component.translatable("butterbot.msg.recover_safe", countRecovered()));
		}
	}

	/** Hands everything back: armor goes straight into empty armor slots, the rest into the inventory. */
	public void returnRecoveredTo(Player player) {
		int total = countRecovered();
		for (ItemStack stack : recoveredItems) {
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable != null && equippable.slot().isArmor() && player.getItemBySlot(equippable.slot()).isEmpty()) {
				player.setItemSlot(equippable.slot(), stack);
				continue;
			}
			giveOrDrop(player, stack);
		}
		recoveredItems.clear();
		speak(ModSounds.VOICE_GIVE, null, 0.0F, true);
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		this.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
		tellOwner(Component.translatable("butterbot.msg.recover_returned", total));
		giveStoredXp(player);
	}

	// ------------------------------------------------------------------ pointing at things

	/** The owner pointed at {@code mob}: attack it, even with guard mode off. */
	public void orderAttack(Mob mob) {
		this.orderedCull = false;
		this.orderedTarget = mob.getUUID();
		this.orderedUntil = this.level().getGameTime() + 20L * 60L;
		say(Component.translatable("butterbot.msg.attack_order", mob.getName()));
	}

	@Nullable
	public Mob getOrderedTarget() {
		if (orderedTarget == null || !(this.level() instanceof ServerLevel serverLevel)) {
			return null;
		}
		Entity entity = serverLevel.getEntity(orderedTarget);
		if (entity instanceof Mob mob && mob.isAlive() && serverLevel.getGameTime() < orderedUntil) {
			return mob;
		}
		orderedTarget = null;
		return null;
	}

	public void clearOrderedTarget() {
		orderedTarget = null;
	}

	/** A rancher culling {@code animal} (its herd is over the limit): the fighting code does it, quietly. */
	public void cullAnimal(Mob animal) {
		this.orderedTarget = animal.getUUID();
		this.orderedUntil = this.level().getGameTime() + 20L * 20L;
		this.orderedCull = true;
	}

	/** The animal this bot is culling right now, if any. */
	@Nullable
	public Mob getCullTarget() {
		return orderedCull ? getOrderedTarget() : null;
	}

	/** Calls off a cull (an attack the owner ordered is left alone). */
	public void stopCulling() {
		if (orderedCull) {
			orderedTarget = null;
			orderedCull = false;
		}
	}

	public boolean refillsHotbar() {
		return autoRefill;
	}

	public void setAutoRefill(boolean autoRefill) {
		this.autoRefill = autoRefill;
	}

	/** Where farming happens: the Bot Base if the bot has one, otherwise the spot it was given the job. */
	public BlockPos getFarmCenter() {
		if (jobs.isActive() && jobs.job() == BotJob.FARMER && jobs.center() != null) {
			return jobs.center();
		}
		if (basePos != null && getBase() != null) {
			return basePos;
		}
		return farmCenter != null ? farmCenter : this.blockPosition();
	}

	public void countHarvest() {
		harvestedCount++;
		progress.addStat(BotProgress.Stat.HARVESTED, 1);
		if (jobs.job() == BotJob.FARMER) {
			jobs.addStat(1);
		}
	}

	/** How far around its centre a farming run reaches (a farmer job can make it bigger or smaller). */
	public int getFarmRadius() {
		if (jobs.isActive() && jobs.job() == BotJob.FARMER) {
			return jobs.radius();
		}
		return FarmTaskAction.RADIUS + stats().farmRadiusBonus();
	}

	/**
	 * Where a farming run unloads: a farmer's job chest, otherwise the Bot Base. Null if there is
	 * neither (or it isn't loaded), in which case the harvest goes to the owner.
	 */
	@Nullable
	public BlockPos getFarmStorage() {
		if (jobs.isActive() && jobs.job() == BotJob.FARMER && jobs.chest() != null
				&& this.level() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(jobs.dimension())
				&& (!serverLevel.isLoaded(jobs.chest()) || JobStorage.isStorage(serverLevel, jobs.chest()))) {
			return jobs.chest();
		}
		return getBase() != null ? basePos : null;
	}

	/** Asks the bot to bring everything it carries to its owner or base right now. */
	public void requestDelivery() {
		if (!hasLoot()) {
			say(Component.translatable("butterbot.msg.nothing_to_deliver"));
			return;
		}
		clearTask();
		this.returnRequested = true;
		say(Component.translatable(deliveryMode == DeliveryMode.BASE && basePos != null
				? "butterbot.msg.delivering_base" : "butterbot.msg.delivering_owner"));
		speak(ModSounds.VOICE_OK);
	}

	public String getLuaScript() {
		return luaScript;
	}

	public void setLuaScript(String luaScript) {
		this.luaScript = luaScript.length() > LuaProgram.MAX_SCRIPT_LENGTH
				? luaScript.substring(0, LuaProgram.MAX_SCRIPT_LENGTH) : luaScript;
	}

	public void clearTask() {
		this.task = BotTask.NONE;
		this.resource = null;
		this.targetCount = 0;
		this.collectedCount = 0;
		this.targetBlock = null;
		this.returnRequested = false;
		this.tunnelDirection = null;
		this.tunnelOrigin = null;
		this.tunnelLength = 0;
		this.tunnelProgress = 0;
		this.tunnelWidth = 0;
		this.tunnelHeight = 0;
		this.craftItem = null;
		this.craftCount = 0;
		this.lightCenter = null;
		this.fetchItem = null;
		this.fetchCount = 0;
		this.gotoPos = null;
		this.mineFilter = null;
		stopCulling();
		if (!fetchCarry.isEmpty()) {
			stashFetchCarry();
		}
		this.state = BotState.IDLE;
		this.miner.cancel();
		this.getNavigation().stop();
	}

	/** Called by the delivery action once everything has been handed over. */
	public void finishDelivery() {
		boolean hadTask = task != BotTask.NONE;
		clearTask();
		if (hadTask) {
			playTaskCompleteSound();
		}
	}

	// ------------------------------------------------------------------ jobs

	public JobConfig getJobs() {
		return jobs;
	}

	public BotJob getJob() {
		return jobs.job();
	}

	public boolean hasJob() {
		return jobs.hasJob();
	}

	/** Has a job and it isn't paused. */
	public boolean isJobActive() {
		return jobs.isActive() && ownerUuid != null;
	}

	/** Gives the bot a new job (or none). The current order is dropped so it starts right away. */
	public void setJob(BotJob job) {
		jobs.setJob(job, item -> ResourceCatalog.canGet(job, item));
		stopCulling();
		if (task != BotTask.LUA) {
			clearTask();
		}
		jobChanged = true;
		if (job == BotJob.NONE) {
			say(Component.translatable("butterbot.msg.job_none"));
		} else {
			say(Component.translatable("butterbot.msg.job_set", job.displayName()));
			speak(ModSounds.VOICE_OK);
		}
	}

	/** The job's settings changed: drop the job's current run so it plans again with the new ones. */
	public void clearTaskForJob() {
		if (mineFilter != null || (task == BotTask.FARM && jobs.job() == BotJob.FARMER)) {
			clearTask();
		}
		jobChanged = true;
	}

	/** True once after the job's settings changed (read by the job action). */
	public boolean takeJobChange() {
		boolean changed = jobChanged;
		jobChanged = false;
		return changed;
	}

	public void pauseJob() {
		if (jobs.hasJob() && !jobs.isPaused()) {
			jobs.setPaused(true);
			stopCulling();
			if (mineFilter != null || task == BotTask.FARM) {
				clearTask();
			}
		}
	}

	/** Back to work: also drops "follow me" and "wait there" orders, which would otherwise never end. */
	public void resumeJob() {
		if (!jobs.hasJob()) {
			return;
		}
		jobs.setPaused(false);
		if (task == BotTask.FOLLOW || task == BotTask.GOTO || task == BotTask.GO_TO_BASE) {
			clearTask();
		}
	}

	/** True while the bot is on a mining run for its job (so the no-dig zone applies and the haul goes to the job chest). */
	public boolean isJobDriven() {
		return mineFilter != null && task == BotTask.MINE;
	}

	@Nullable
	public ResourceCatalog.Entry getMineFilter() {
		return mineFilter;
	}

	/** A mining run for the job: collect {@code count} of the entry's item around the current spot. */
	public void assignJobMineTask(ResourceCatalog.Entry entry, int count) {
		clearTask();
		this.task = BotTask.MINE;
		this.resource = entry.behavior();
		this.mineFilter = entry;
		this.targetCount = Math.max(1, count);
		this.state = BotState.SEARCHING;
		this.jobSiteSpent = false;
		this.mineRun++;
		requestRescan();
	}

	/** A farming run for the farmer job, without the usual chatter. */
	public void assignJobFarmTask(BlockPos center) {
		clearTask();
		this.task = BotTask.FARM;
		this.state = BotState.SEARCHING;
		this.farmCenter = center.immutable();
		this.harvestedCount = 0;
	}

	/** The search around the dig site found nothing more: try somewhere else. */
	public void onJobSiteExhausted() {
		Item item = mineFilter == null ? null : mineFilter.item();
		jobSiteSpent = true;
		if (item != null) {
			onJobSiteFailed(item);
		}
		if (JobAction.hasSomethingToDeliver(this, jobs)) {
			returnRequested = true;
		} else {
			clearTask();
		}
	}

	/** A dig site was empty or couldn't be reached. After several in a row the item is left alone for a while. */
	public void onJobSiteFailed(Item item) {
		jobs.failSite();
		if (jobs.failStreak(item) >= 6) {
			jobs.coolDown(item, this.level().getGameTime() + 20L * 60L * 10L);
			tellOwner(Component.translatable("butterbot.msg.job_not_found", new ItemStack(item).getHoverName()));
		} else {
			say(Component.translatable("butterbot.msg.job_new_site"));
		}
	}

	public boolean isJobSiteSpent() {
		return jobSiteSpent;
	}

	/**
	 * Miners and gatherers never dig within the no-dig zone around the base: not on their mining runs
	 * and not to get through it on the way to work (orders given by hand may).
	 */
	public boolean isJobZoneBlocked(BlockPos pos) {
		boolean jobWork = mineFilter != null || (task == BotTask.NONE && !returnRequested && jobs.isActive() && jobs.job().usesList());
		return jobWork && jobs.isInsideZone(this.level(), basePos, pos);
	}

	/** The Y level a mining run digs down to. */
	public int getMiningY() {
		if (mineFilter != null && mineFilter.optimalY() != null) {
			return mineFilter.optimalY();
		}
		return resource == null ? 0 : resource.optimalY(this.level().dimension());
	}

	public boolean isMiningUnderground() {
		if (mineFilter != null) {
			return mineFilter.underground();
		}
		return resource != null && resource.isUnderground();
	}

	/** The middle of a guard's patrol area, or null when the bot isn't patrolling here. */
	@Nullable
	public BlockPos getPatrolCenter() {
		if (!jobs.isActive() || (jobs.job() != BotJob.GUARD && jobs.job() != BotJob.RANCHER) || jobs.center() == null
				|| !this.level().dimension().equals(jobs.dimension())) {
			return null;
		}
		return jobs.center();
	}

	public int getPatrolRadius() {
		return jobs.radius();
	}

	@Nullable
	public Direction getTunnelDirection() {
		return tunnelDirection;
	}

	@Nullable
	public BlockPos getTunnelOrigin() {
		return tunnelOrigin;
	}

	public int getTunnelLength() {
		return tunnelLength;
	}

	public int getTunnelProgress() {
		return tunnelProgress;
	}

	public void setTunnelProgress(int tunnelProgress) {
		this.tunnelProgress = tunnelProgress;
	}

	public int getTunnelWidth() {
		return tunnelWidth;
	}

	public int getTunnelHeight() {
		return tunnelHeight;
	}

	public boolean placesTunnelTorches() {
		return tunnelTorches;
	}

	@Nullable
	public Item getCraftItem() {
		return craftItem;
	}

	public int getCraftCount() {
		return craftCount;
	}

	@Nullable
	public BlockPos getBasePos() {
		return basePos;
	}

	public void setBasePos(@Nullable BlockPos basePos) {
		this.basePos = basePos;
	}

	@Nullable
	public BotBaseBlockEntity getBase() {
		if (basePos == null || !this.level().isLoaded(basePos)) {
			return null;
		}
		return this.level().getBlockEntity(basePos) instanceof BotBaseBlockEntity base ? base : null;
	}

	public void playTaskCompleteSound() {
		// Not "important": right after handing something over, the hand-over line is enough.
		speak(ModSounds.VOICE_DONE, ModSounds.BOT_SPAWN, 0.45F, false);
	}

	/** One of the neko girl's voice lines, not more often than every {@link #VOICE_GAP_TICKS} (except important ones). */
	public void speak(SoundEvent line) {
		speak(line, null, 0.0F, false);
	}

	/**
	 * Plays a voice line (VOICEVOX:小夜/SAYO) while neko mode is on, or {@code robotSound} (if any) for the
	 * classic Butter Bot when it is off. Lines don't talk over each other unless {@code important}.
	 */
	private void speak(SoundEvent line, @Nullable SoundEvent robotSound, float robotVolume, boolean important) {
		if (!important && this.tickCount - lastVoiceTick < VOICE_GAP_TICKS) {
			return;
		}
		lastVoiceTick = this.tickCount;
		if (isNeko()) {
			this.playSound(line, 1.0F, 0.97F + this.random.nextFloat() * 0.06F);
		} else if (robotSound != null) {
			this.playSound(robotSound, robotVolume, 1.0F);
		}
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return isNeko() ? ModSounds.VOICE_HURT : super.getHurtSound(source);
	}

	@Override
	protected SoundEvent getDeathSound() {
		return isNeko() ? ModSounds.VOICE_DEATH : super.getDeathSound();
	}

	public boolean isReturnRequested() {
		return returnRequested;
	}

	public void setReturnRequested(boolean returnRequested) {
		this.returnRequested = returnRequested;
	}

	@Nullable
	public BlockPos getTargetBlock() {
		return targetBlock;
	}

	public void setTargetBlock(@Nullable BlockPos targetBlock) {
		this.targetBlock = targetBlock;
	}

	// ------------------------------------------------------------------ world helpers

	public boolean isTargetBlock(BlockState blockState) {
		if (mineFilter != null) {
			return mineFilter.matchesState(blockState);
		}
		return resource != null && resource.matchesBlock(blockState);
	}

	/** Like {@link #isTargetBlock} but also checks the surroundings and the no-dig zone (used when choosing targets). */
	private boolean isTargetAt(BlockPos pos, BlockState blockState) {
		if (mineFilter != null) {
			return mineFilter.matches(this.level(), pos, blockState) && !isJobZoneBlocked(pos);
		}
		if (resource == BotResource.WOOD) {
			// Only real trees: log houses and other builds are left alone.
			return isTargetBlock(blockState) && ResourceCatalog.isNaturalTree(this.level(), pos, blockState);
		}
		return isTargetBlock(blockState);
	}

	public void requestRescan() {
		scanCooldown = 0;
	}

	private void handlePassiveScan() {
		if (task != BotTask.MINE || resource == null || targetBlock != null || returnRequested || isInventoryFull()) {
			return;
		}
		if (scanCooldown-- > 0) {
			return;
		}
		scanCooldown = SCAN_INTERVAL_TICKS;
		targetBlock = scanForTargetBlock();
	}

	/**
	 * The best block to go for next. Not simply the nearest: blocks around the bot's own height are
	 * cheaper than ones far below or above, and blocks with an open side (in a cave wall, the side of
	 * the tunnel) are cheaper than buried ones. Trees are taken from the bottom log and canes from the
	 * top. Searches outwards ring by ring and stops once no farther ring can do better.
	 */
	@Nullable
	private BlockPos scanForTargetBlock() {
		Level level = this.level();
		BlockPos origin = this.blockPosition();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		boolean surface = !isMiningUnderground();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;

		int radius = stats().scanRadius();
		for (int ring = 0; ring <= radius; ring++) {
			// Every block in this ring is at least `ring` blocks away sideways.
			if (best != null && bestScore <= (double) ring * ring) {
				break;
			}
			for (int x = -ring; x <= ring; x++) {
				for (int z = -ring; z <= ring; z++) {
					if (Math.max(Math.abs(x), Math.abs(z)) != ring) {
						continue;
					}
					for (int y = -SCAN_VERTICAL_RADIUS; y <= SCAN_VERTICAL_RADIUS; y++) {
						cursor.setWithOffset(origin, x, y, z);
						if (!level.isLoaded(cursor)) {
							continue;
						}
						BlockState blockState = level.getBlockState(cursor);
						if (!isTargetAt(cursor, blockState)) {
							continue;
						}
						BlockPos pos = settleTarget(level, cursor.immutable(), blockState);
						if (isBlacklisted(pos) || !canDig(pos, level.getBlockState(pos))
								|| (surface && !level.getFluidState(pos.above()).isEmpty())) {
							continue;
						}
						double score = targetScore(level, origin, pos);
						if (score < bestScore) {
							bestScore = score;
							best = pos;
						}
					}
				}
			}
		}
		return best;
	}

	/** The bottom log of a tree (the whole tree falls and a sapling goes there); the top of canes, cactus and bamboo. */
	private static BlockPos settleTarget(Level level, BlockPos pos, BlockState blockState) {
		BlockPos.MutableBlockPos cursor = pos.mutable();
		if (blockState.is(BlockTags.LOGS)) {
			for (int i = 0; i < 32 && level.getBlockState(cursor.below()).is(blockState.getBlock()); i++) {
				cursor.move(0, -1, 0);
			}
		} else if (blockState.is(Blocks.SUGAR_CANE) || blockState.is(Blocks.CACTUS) || blockState.is(Blocks.BAMBOO)) {
			for (int i = 0; i < 32 && level.getBlockState(cursor.above()).is(blockState.getBlock()); i++) {
				cursor.move(0, 1, 0);
			}
		}
		return cursor.immutable();
	}

	private static double targetScore(Level level, BlockPos origin, BlockPos pos) {
		int dx = pos.getX() - origin.getX();
		int dy = pos.getY() - origin.getY();
		int dz = pos.getZ() - origin.getZ();
		double score = dx * dx + dz * dz;
		if (dy < 0) {
			score += dy * dy * 2.0D;
		} else if (dy > 1) {
			score += (dy - 1) * (dy - 1) * 3.0D;
		}
		if (!isExposed(level, pos)) {
			score += BURIED_PENALTY;
		}
		return score;
	}

	private static boolean isExposed(Level level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos side = pos.relative(direction);
			if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public void blacklistTarget(BlockPos pos) {
		blacklistedTargets.put(pos.asLong(), this.level().getGameTime() + BLACKLIST_DURATION_TICKS);
	}

	public boolean isBlacklisted(BlockPos pos) {
		Long expiry = blacklistedTargets.get(pos.asLong());
		if (expiry == null) {
			return false;
		}
		if (this.level().getGameTime() > expiry) {
			blacklistedTargets.remove(pos.asLong());
			return false;
		}
		return true;
	}

	public MiningController getMiner() {
		return miner;
	}

	public boolean canDig(BlockPos pos, BlockState blockState) {
		if (blockState.isAir() || blockState.hasBlockEntity() || !blockState.getFluidState().isEmpty()
				|| blockState.is(ModBlocks.BOT_BASE) || isJobZoneBlocked(pos)) {
			return false;
		}
		if (blockState.is(Blocks.ANCIENT_DEBRIS)) {
			// Far harder than anything else it digs, but a diamond pickaxe gets through it.
			return canMineNetherite();
		}
		float hardness = blockState.getDestroySpeed(this.level(), pos);
		return hardness >= 0.0F && hardness <= MAX_DIGGABLE_HARDNESS;
	}

	/**
	 * Makes it safe to dig {@code pos}: lava touching it is plugged with cobblestone-like blocks from
	 * the backpack (or plain cobblestone when it has none). Returns false only if that was impossible.
	 */
	public boolean makeDigSafe(BlockPos pos) {
		boolean sealed = false;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (!this.level().getFluidState(neighbour).is(FluidTags.LAVA)) {
				continue;
			}
			if (!plugWithFiller(neighbour)) {
				warnLava();
				return false;
			}
			sealed = true;
		}
		if (sealed) {
			long now = this.level().getGameTime();
			this.level().playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 1.4F);
			if (now - lastLavaSealed > 200L) {
				lastLavaSealed = now;
				say(Component.translatable("butterbot.msg.lava_sealed"));
			}
		}
		return true;
	}

	/** Replaces lava (or any replaceable block) at {@code pos} with a cobblestone-like block. */
	public boolean plugWithFiller(BlockPos pos) {
		if (!this.level().isLoaded(pos)) {
			return false;
		}
		BlockState current = this.level().getBlockState(pos);
		if (!current.canBeReplaced() && current.getFluidState().isEmpty()) {
			return true;
		}
		return this.level().setBlock(pos, takeFiller(), 3);
	}

	/**
	 * Puts a building block at {@code pos} (stairs, pillars and bridges when the bot is stuck).
	 * Returns false if the spot is taken by a block or an entity, or is in a job's no-dig zone.
	 */
	public boolean placeFillerAt(BlockPos pos) {
		Level level = this.level();
		if (!level.isLoaded(pos) || !level.getBlockState(pos).canBeReplaced() || isJobZoneBlocked(pos)) {
			return false;
		}
		BlockState preview = Blocks.COBBLESTONE.defaultBlockState();
		if (!level.isUnobstructed(preview, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
			return false;
		}
		BlockState filler = takeFiller();
		level.setBlock(pos, filler, 3);
		level.playSound(null, pos, filler.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		return true;
	}

	/** One cobblestone-like block from the backpack, or plain cobblestone when it has none. */
	private BlockState takeFiller() {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (isFiller(stack) && stack.getItem() instanceof BlockItem blockItem) {
				BlockState filler = blockItem.getBlock().defaultBlockState();
				stack.shrink(1);
				inventory.setItem(i, stack);
				return filler;
			}
		}
		return Blocks.COBBLESTONE.defaultBlockState();
	}

	// ------------------------------------------------------------------ getting unstuck

	// The bot walks everywhere (digging and building its way when it has to) and goes to other
	// dimensions through portals, like a player. It only teleports as a last resort: when walking has
	// failed for a good while, when it is in danger, or when it must reach another dimension and knows
	// no portal there.

	/**
	 * Last resort only: hops to a safe spot next to {@code target} in this dimension (and says so).
	 * False if there is no safe spot there or it isn't loaded.
	 */
	public boolean lastResortTeleport(BlockPos target) {
		if (!(this.level() instanceof ServerLevel serverLevel) || !serverLevel.isLoaded(target)) {
			return false;
		}
		BlockPos spot = findSafeSpot(serverLevel, target, 1, 3);
		if (spot == null) {
			return false;
		}
		hopTo(serverLevel, spot);
		long now = serverLevel.getGameTime();
		if (now - lastUnstuckMessage > 600L) {
			lastUnstuckMessage = now;
			say(Component.translatable("butterbot.msg.unstuck"));
			speak(ModSounds.VOICE_STUCK);
		}
		return true;
	}

	/** Last resort only: goes to {@code destination} without a portal (it knows none that leads there). */
	public boolean lastResortToLevel(ServerLevel destination, BlockPos near) {
		BlockPos spot = findSafeSpot(destination, near, 1, 3);
		if (spot == null || !(this.level() instanceof ServerLevel)) {
			return false;
		}
		this.getNavigation().stop();
		this.miner.cancel();
		brain.reset(this);
		say(Component.translatable("butterbot.msg.unstuck"));
		this.resetFallDistance();
		this.teleport(new net.minecraft.world.level.portal.TeleportTransition(destination,
				new Vec3(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D), Vec3.ZERO, this.getYRot(), this.getXRot(),
				net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
		return true;
	}

	private void hopTo(ServerLevel level, BlockPos spot) {
		level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.9D, getZ(), 10, 0.3D, 0.5D, 0.3D, 0.02D);
		this.getNavigation().stop();
		this.miner.cancel();
		this.pillarBase = null;
		// Arrives standing: a fall (out of the world, say) doesn't carry over and hurt it on landing.
		this.resetFallDistance();
		this.setDeltaMovement(Vec3.ZERO);
		this.teleportTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
		this.playSound(SoundEvents.CHORUS_FRUIT_TELEPORT, 0.6F, 1.2F);
		level.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.9D, getZ(), 10, 0.3D, 0.5D, 0.3D, 0.02D);
	}

	/**
	 * Danger: in lava without fire immunity, fallen out of the world, or wedged in a block it can't dig
	 * for a while. Gets to safety (the last firm ground, or near the owner) instead of dying.
	 */
	private void tickDanger(ServerLevel level) {
		if (this.onGround() && !this.isInWater() && !this.isInLava() && this.tickCount % 20 == 0) {
			lastSafeGround = this.blockPosition();
		}
		wallTicks = this.isInWall() ? wallTicks + 1 : 0;
		long now = level.getGameTime();
		if (now < nextRescue) {
			return;
		}
		boolean burning = this.isInLava() && !this.fireImmune();
		boolean fallingOut = this.getY() < level.getMinY() - 4;
		boolean wedged = wallTicks > 40;
		if (!burning && !fallingOut && !wedged) {
			return;
		}
		BlockPos spot = wedged ? findSafeSpot(level, this.blockPosition(), 0, 3) : findSafeSpot(level, this.blockPosition(), 1, 6);
		if (spot == null && lastSafeGround != null && level.isLoaded(lastSafeGround)) {
			spot = findSafeSpot(level, lastSafeGround, 0, 2);
		}
		Player owner = getOwner();
		if (spot == null && owner != null && owner.level() == level) {
			spot = findSafeSpot(level, owner.blockPosition(), 1, 3);
		}
		if (spot == null) {
			return;
		}
		nextRescue = now + 100L;
		wallTicks = 0;
		hopTo(level, spot);
		this.clearFire();
		say(Component.translatable("butterbot.msg.escape_danger"));
		speak(ModSounds.VOICE_STUCK);
	}

	/** Says now and then that it can't find a way somewhere (instead of hopping there). */
	public void sayStuck() {
		long now = this.level().getGameTime();
		if (now - lastUnstuckMessage > 600L) {
			lastUnstuckMessage = now;
			say(Component.translatable("butterbot.msg.stuck"));
			speak(ModSounds.VOICE_STUCK);
		}
	}

	private static String portalKey(ResourceKey<Level> from, ResourceKey<Level> to) {
		return from.identifier() + ">" + to.identifier();
	}

	/** A portal in this dimension the bot came through from {@code dimension} (so it leads back there), or null. */
	@Nullable
	public BlockPos portalTowards(ResourceKey<Level> dimension) {
		return portalExits.get(portalKey(this.level().dimension(), dimension));
	}

	public void forgetPortal(ResourceKey<Level> dimension) {
		portalExits.remove(portalKey(this.level().dimension(), dimension));
	}

	@Nullable
	public BlockPos getLastOwnerPos() {
		return lastOwnerPos;
	}

	/**
	 * Lets the bot use a portal to {@code dimension} for a moment (it never wanders through one by
	 * accident). Only the time spent {@code atPortal} counts against its patience, never the walk
	 * there. False once it has given up: the caller falls back to its last resort.
	 */
	public boolean wantPortal(ResourceKey<Level> dimension, boolean atPortal) {
		long now = this.level().getGameTime();
		if (now < portalGiveUpUntil) {
			return false;
		}
		if (portalWish == null || now > portalWishUntil || !portalWish.equals(dimension)) {
			portalTicks = 0;
		}
		if (atPortal && ++portalTicks > PORTAL_PATIENCE_TICKS) {
			giveUpPortal();
			return false;
		}
		portalWish = dimension;
		portalWishUntil = now + 40L;
		return true;
	}

	/** The portal can't be reached, or doesn't take the bot: portals are left alone for a while. */
	public void giveUpPortal() {
		portalWish = null;
		portalTicks = 0;
		portalGiveUpUntil = this.level().getGameTime() + PORTAL_GIVE_UP_TICKS;
	}

	/**
	 * Only portals it means to take, and only once the cooldown from the last trip is over (standing in
	 * a portal it may use would otherwise keep restarting that cooldown for ever).
	 */
	@Override
	public boolean canUsePortal(boolean ignorePassenger) {
		return portalWish != null && this.level().getGameTime() <= portalWishUntil && !this.isOnPortalCooldown()
				&& super.canUsePortal(ignorePassenger);
	}

	/** Notices when it came through a portal (that portal is its way back) and where the owner was last seen. */
	private void tickTravelMemory(ServerLevel level) {
		ResourceKey<Level> here = level.dimension();
		if (lastDimension != null && !lastDimension.equals(here)) {
			BlockPos portal = Travel.portalNear(level, this.blockPosition());
			if (portal != null) {
				portalExits.put(portalKey(here, lastDimension), portal);
			}
			lastOwnerPos = null;
			lastWardPos = null;
			portalWish = null;
		}
		lastDimension = here;
		if (this.tickCount % 5 == 0) {
			Player owner = getOwner();
			if (owner != null && owner.level() == level) {
				lastOwnerPos = owner.blockPosition();
			}
			ButterBotEntity ward = jobs.isActive() ? findWard() : null;
			if (ward != null && ward.level() == level) {
				lastWardPos = ward.blockPosition();
			}
		}
	}

	@Nullable
	public BlockPos getLastWardPos() {
		return lastWardPos;
	}

	/** The bot a bodyguard stays with, wherever it is: null without one, or when it is gone or packed up. */
	@Nullable
	public ButterBotEntity findWard() {
		UUID id = jobs.job() == BotJob.BODYGUARD ? jobs.ward() : null;
		if (id == null || !(this.level() instanceof ServerLevel here)) {
			wardCache = null;
			return null;
		}
		if (wardCache != null && !wardCache.isRemoved() && wardCache.isAlive() && wardCache.getUUID().equals(id)) {
			return wardCache;
		}
		wardCache = null;
		for (ServerLevel level : here.getServer().getAllLevels()) {
			if (level.getEntity(id) instanceof ButterBotEntity ward && ward != this && ward.isAlive()
					&& Objects.equals(ward.getOwnerUuid(), ownerUuid)) {
				wardCache = ward;
				break;
			}
		}
		return wardCache;
	}

	public BotSmelter getSmelter() {
		return smelter;
	}

	/**
	 * Pillars up like a player: jumps, and once its feet are above {@code base} a block goes there (from
	 * the bot's own tick, whatever action asked for it). False while a jump is already under way.
	 */
	public boolean startPillarJump(BlockPos base) {
		if (pillarBase != null) {
			return false;
		}
		pillarBase = base.immutable();
		pillarTicks = 0;
		this.getNavigation().stop();
		this.getJumpControl().jump();
		return true;
	}

	public boolean isPillaring() {
		return pillarBase != null;
	}

	private void tickPillar() {
		BlockPos base = pillarBase;
		if (base == null) {
			return;
		}
		boolean swimming = this.isInWater() || this.isInLava();
		if (swimming) {
			// Swims up slowly instead of jumping.
			this.getJumpControl().jump();
		}
		if (this.getY() >= base.getY() + 1.0D - 1.0E-3D) {
			pillarBase = null;
			if (this.level().getBlockState(base).canBeReplaced()) {
				placeFillerAt(base);
			}
			return;
		}
		if (++pillarTicks > (swimming ? 60 : 20)) {
			// The jump didn't get high enough (a ceiling): the caller tries again.
			pillarBase = null;
		}
	}

	/** Opens a closed fence gate or wooden door at {@code pos} to walk through; it is closed again behind the bot. */
	public boolean openGate(BlockPos pos) {
		Level level = this.level();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof FenceGateBlock) {
			if (!state.getValue(FenceGateBlock.OPEN)) {
				level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
				level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
				level.gameEvent(this, GameEvent.BLOCK_OPEN, pos);
			}
		} else if (state.getBlock() instanceof DoorBlock door && state.is(BlockTags.WOODEN_DOORS)) {
			door.setOpen(this, level, state, pos, true);
		} else {
			return false;
		}
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		// Doors by their lower half, so the check before closing covers the whole doorway.
		BlockPos key = state.getBlock() instanceof DoorBlock && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
				? pos.below().immutable() : pos.immutable();
		if (!openedGates.contains(key)) {
			openedGates.add(key);
		}
		return true;
	}

	/** Closes the gates and doors it opened once it is through and nobody stands in them, so animals stay in. */
	private void closeGates(ServerLevel level) {
		for (java.util.Iterator<BlockPos> it = openedGates.iterator(); it.hasNext(); ) {
			BlockPos pos = it.next();
			if (!level.isLoaded(pos)) {
				it.remove();
				continue;
			}
			BlockState state = level.getBlockState(pos);
			boolean gate = state.getBlock() instanceof FenceGateBlock;
			if (!gate && !(state.getBlock() instanceof DoorBlock) || !state.getValue(BlockStateProperties.OPEN)) {
				it.remove();
				continue;
			}
			AABB space = new AABB(pos).expandTowards(0.0D, gate ? 0.0D : 1.0D, 0.0D);
			if (this.getBoundingBox().intersects(space.inflate(0.4D))
					|| !level.getEntitiesOfClass(LivingEntity.class, space, LivingEntity::isAlive).isEmpty()) {
				continue;
			}
			if (gate) {
				level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), 10);
				level.playSound(null, pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
				level.gameEvent(this, GameEvent.BLOCK_CLOSE, pos);
			} else if (state.getBlock() instanceof DoorBlock door) {
				door.setOpen(this, level, state, pos, false);
			}
			it.remove();
		}
	}

	/**
	 * Bots keep the area around them running while they work a job or are on their way somewhere (they
	 * walk everywhere), but not while they only wait: never a free chunk loader.
	 */
	public boolean keepsChunksLoaded() {
		if (jobs.hasJob() || recoverPos != null || returnRequested || !recoveredItems.isEmpty()) {
			// Jobs run wherever the bot is (a paused one stays loaded, or it couldn't be found to resume
			// it). A bot bringing things back stays loaded too, so it can still find an owner who logged
			// off once they are back.
			return true;
		}
		boolean ownerOnline = this.level() instanceof ServerLevel level && ownerUuid != null
				&& level.getServer().getPlayerList().getPlayer(ownerUuid) != null;
		return switch (task) {
			// A plain farm order works while the owner is around, like the rest of the world.
			case NONE, LUA, FARM -> false;
			case FOLLOW -> ownerOnline;
			case GOTO -> gotoPos != null && dev.butterbot.ai.Walker.horizontalDistSq(this, gotoPos) > 4.0D * 4.0D;
			default -> true;
		};
	}

	/** A spot with solid ground, room for the bot and no liquid, between {@code minRadius} and {@code maxRadius} of {@code center}. */
	@Nullable
	public static BlockPos findSafeSpot(ServerLevel level, BlockPos center, int minRadius, int maxRadius) {
		for (int r = minRadius; r <= maxRadius; r++) {
			for (int dy : new int[]{0, 1, -1, 2, -2}) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						BlockPos feet = center.offset(dx, dy, dz);
						if (isSafeSpot(level, feet)) {
							return feet;
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean isSafeSpot(Level level, BlockPos feet) {
		if (!level.isLoaded(feet) || !level.isLoaded(feet.below())) {
			return false;
		}
		BlockPos below = feet.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
				&& level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				&& level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
				&& !level.getBlockState(below).is(Blocks.MAGMA_BLOCK);
	}

	/** Breaks free when a block ends up inside the bot (falling gravel, sand...), or is pushed out of it bit by bit. */
	private void escapeWall() {
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockPos eyes = BlockPos.containing(this.getEyePosition());
		for (BlockPos pos : new BlockPos[]{eyes, this.blockPosition()}) {
			BlockState blockState = serverLevel.getBlockState(pos);
			if (blockState.getCollisionShape(serverLevel, pos).isEmpty()) {
				continue;
			}
			if (TunnelHelper.isNaturalTerrain(blockState) && canDig(pos, blockState) && makeDigSafe(pos)) {
				harvestBlock(serverLevel, pos, blockState, true);
				return;
			}
		}
		// Something it may not dig (a build): slides out to the nearest free side, as players are pushed out.
		this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0D, this.getZ());
	}

	/** Blocks the bot happily uses to plug lava. */
	public static boolean isFiller(ItemStack stack) {
		return stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.DIRT)
				|| stack.is(Items.NETHERRACK) || stack.is(Items.STONE) || stack.is(Items.DEEPSLATE)
				|| stack.is(Items.ANDESITE) || stack.is(Items.DIORITE) || stack.is(Items.GRANITE)
				|| stack.is(Items.TUFF) || stack.is(Items.BLACKSTONE);
	}

	private int countFiller() {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (isFiller(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Lights up dark caves while mining: a torch at its feet when the block light gets low underground. */
	private void placeTorchIfDark() {
		Level level = this.level();
		BlockPos feet = this.blockPosition();
		if (level.getBrightness(LightLayer.SKY, feet) > 7 || level.getBrightness(LightLayer.BLOCK, feet) >= TORCH_LIGHT_THRESHOLD) {
			return;
		}
		BlockState torch = Blocks.TORCH.defaultBlockState();
		if (!level.getBlockState(feet).isAir() || !torch.canSurvive(level, feet) || !level.getFluidState(feet).isEmpty()) {
			return;
		}
		// Torches from the backpack are used first; otherwise the bot makes its own.
		takeItem(Items.TORCH, 1);
		level.setBlock(feet, torch, 3);
		level.playSound(null, feet, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6F, 1.2F);
	}

	// ------------------------------------------------------------------ breaking blocks

	/**
	 * Breaks a block as the bot: drops go straight into the backpack (with Fortune from its level),
	 * ore experience is kept for the owner, and work XP is earned. Wood jobs fell the whole tree and
	 * high-level ore jobs take the whole vein.
	 */
	public void breakBlock(BlockPos pos) {
		if (!(this.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockState blockState = serverLevel.getBlockState(pos);
		if (blockState.isAir()) {
			return;
		}
		boolean target = task == BotTask.MINE && isTargetBlock(blockState);
		if (!target && task == BotTask.MINE && shouldSealLater(serverLevel, pos, blockState)) {
			// Opening the ground on the way down: remember the block and put it back once the bot is below.
			pendingSeals.add(new Seal(pos.immutable(), blockState, serverLevel.getGameTime() + SEAL_EXPIRY_TICKS));
			serverLevel.destroyBlock(pos, false, this);
			return;
		}
		harvestBlock(serverLevel, pos, blockState, false, target && rollBonus(blockState) ? 1 : 0);
		gainWorkXp(BotLevels.blockXp(blockState));
		progress.addStat(BotProgress.Stat.MINED, 1);
		if (target && resource == BotResource.WOOD) {
			TreeFeller.fell(this, serverLevel, pos, blockState);
		} else if (target && resource != null && resource.isUnderground() && resource != BotResource.STONE) {
			mineVein(serverLevel, pos);
		}
	}

	/** Ground open to the sky that the bot digs through on its way down (not the things it is collecting). */
	private boolean shouldSealLater(ServerLevel level, BlockPos pos, BlockState blockState) {
		return pendingSeals.size() < MAX_PENDING_SEALS && TunnelHelper.isNaturalTerrain(blockState)
				&& !blockState.is(BlockTags.LEAVES) && !blockState.is(BlockTags.ICE) && level.canSeeSky(pos.above());
	}

	/**
	 * Closes the top of the stairs the bot dug down: once it is a few blocks below them (or has walked
	 * away), the surface blocks it opened are put back exactly as they were, so no hole is left behind.
	 */
	private void sealHoles(ServerLevel level) {
		if (pendingSeals.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		// Bottom first, so each restored block rests on the one below it.
		pendingSeals.sort(java.util.Comparator.comparingInt(seal -> seal.pos().getY()));
		boolean played = false;
		for (java.util.Iterator<Seal> it = pendingSeals.iterator(); it.hasNext(); ) {
			Seal seal = it.next();
			BlockPos pos = seal.pos();
			if (now > seal.expires() || !level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
				it.remove();
				continue;
			}
			double dx = this.getX() - (pos.getX() + 0.5D);
			double dz = this.getZ() - (pos.getZ() + 0.5D);
			boolean wellBelow = this.getBlockY() <= pos.getY() - 3;
			if (!wellBelow && dx * dx + dz * dz < 6.0D * 6.0D) {
				continue;
			}
			if (!level.getEntities((Entity) null, new AABB(pos), entity -> !(entity instanceof ItemEntity)).isEmpty()) {
				continue;
			}
			BlockState restored = seal.state();
			if (restored.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()))) {
				// Sand or gravel over an open shaft would fall straight down onto the bot: use a block that stays put.
				restored = restored.is(Blocks.RED_SAND) ? Blocks.RED_SANDSTONE.defaultBlockState()
						: restored.is(BlockTags.SAND) ? Blocks.SANDSTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
			}
			level.setBlock(pos, restored, Block.UPDATE_ALL);
			if (!played) {
				played = true;
				level.playSound(null, pos, restored.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
			}
			it.remove();
		}
	}

	/** Removes one block and collects its drops. With {@code keepAll} nothing is left on the ground. */
	public void harvestBlock(ServerLevel level, BlockPos pos, BlockState blockState, boolean keepAll) {
		harvestBlock(level, pos, blockState, keepAll, 0);
	}

	/** {@link #harvestBlock(ServerLevel, BlockPos, BlockState, boolean)}, getting every drop {@code extraCopies} more times. */
	public void harvestBlock(ServerLevel level, BlockPos pos, BlockState blockState, boolean keepAll, int extraCopies) {
		// A tool the owner gave it counts (Fortune, Silk Touch...) and wears; otherwise it uses its own.
		ItemStack given = givenToolFor(blockState);
		boolean wears = !given.isEmpty() && blockState.getDestroySpeed(level, pos) != 0.0F;
		ItemStack tool = given.isEmpty() ? miningTool(level, blockState) : dropTool(level, given);
		List<ItemStack> drops = Block.getDrops(blockState, level, pos, level.getBlockEntity(pos), this, tool);
		level.destroyBlock(pos, false, this);
		blockState.spawnAfterBreak(level, pos, tool, true);
		GearSlot toolSlot = GearSlot.forBlock(blockState);
		if (wears && toolSlot != null) {
			Tool toolData = given.get(DataComponents.TOOL);
			wearTool(toolSlot, toolData == null ? 1 : toolData.damagePerBlock());
		}
		for (ItemStack drop : drops) {
			for (int i = 0; i < extraCopies; i++) {
				collectLoot(drop.copy(), pos, keepAll);
			}
			collectLoot(drop, pos, keepAll);
		}
		absorbExperience(Vec3.atCenterOf(pos), 2.5D);
	}

	/**
	 * Golden Hand (ores, from level 18) and gatherer mastery (wood and plants) sometimes double what a
	 * block gives.
	 */
	public boolean rollBonus(BlockState blockState) {
		BotStats stats = stats();
		float chance = BotLevels.blockXp(blockState) > 1 ? stats.oreBonusChance()
				: !isMiningUnderground() && !TunnelHelper.isNaturalTerrain(blockState) ? stats.gatherBonusChance() : 0.0F;
		return chance > 0.0F && this.random.nextFloat() < chance;
	}

	/** Mines the ore blocks connected to the one just broken, as many as the bot's level and skills allow. */
	private void mineVein(ServerLevel level, BlockPos origin) {
		int limit = stats().veinSize();
		if (limit <= 0) {
			return;
		}
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		queue.add(origin);
		seen.add(origin);
		int mined = 0;
		while (!queue.isEmpty() && mined < limit && !isInventoryFull()) {
			BlockPos current = queue.poll();
			for (BlockPos cursor : BlockPos.betweenClosed(current.offset(-1, -1, -1), current.offset(1, 1, 1))) {
				if (mined >= limit) {
					break;
				}
				BlockPos next = cursor.immutable();
				if (!seen.add(next) || next.distManhattan(origin) > 12 || !level.isLoaded(next)) {
					continue;
				}
				BlockState nextState = level.getBlockState(next);
				if (!isTargetBlock(nextState) || !canDig(next, nextState) || !makeDigSafe(next)) {
					continue;
				}
				harvestBlock(level, next, nextState, false, rollBonus(nextState) ? 1 : 0);
				gainWorkXp(BotLevels.blockXp(nextState));
				progress.addStat(BotProgress.Stat.MINED, 1);
				mined++;
				queue.add(next);
			}
		}
	}

	public boolean hasLineOfSight(BlockPos pos) {
		Vec3 eye = this.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		BlockHitResult hit = this.level().clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	// ------------------------------------------------------------------ inventory

	public SimpleContainer getInventory() {
		return inventory;
	}

	public boolean hasLoot() {
		return !inventory.isEmpty();
	}

	/**
	 * No room left: every slot is taken and, on a mining run, no half-filled stack of what it is
	 * collecting is left (half stacks of cobblestone or byproducts are no room for more ore).
	 */
	public boolean isInventoryFull() {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				return false;
			}
			if (stack.getCount() < stack.getMaxStackSize() && (task != BotTask.MINE || isWantedLoot(stack))) {
				return false;
			}
		}
		return true;
	}

	public int countInventoryItems() {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			count += inventory.getItem(i).getCount();
		}
		return count;
	}

	public int countItem(Item item) {
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** Picks up item entities lying around {@code center} (monster loot, things knocked loose...). */
	public void collectNearbyDrops(Vec3 center, double radius) {
		List<ItemEntity> drops = this.level().getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(radius));
		for (ItemEntity drop : drops) {
			if (!drop.isAlive() || drop.getOwner() instanceof Player) {
				continue;
			}
			ItemStack leftover = inventory.addItem(drop.getItem().copy());
			if (leftover.isEmpty()) {
				drop.discard();
			} else {
				drop.setItem(leftover);
			}
		}
	}

	/**
	 * Puts a drop in the backpack, counting it towards a mining job. Mining jobs leave junk such as
	 * dirt on the ground (but keep a little cobblestone for plugging lava) unless {@code keepAll}.
	 */
	public void collectLoot(ItemStack stack, BlockPos pos, boolean keepAll) {
		if (stack.isEmpty()) {
			return;
		}
		boolean wanted = isWantedLoot(stack);
		if (!keepAll && isJunkWhileMining(stack)) {
			Block.popResource(this.level(), pos, stack);
			return;
		}
		int before = stack.getCount();
		ItemStack leftover = inventory.addItem(stack);
		int taken = before - leftover.getCount();
		if (wanted && taken > 0) {
			countCollected(taken);
		}
		if (!leftover.isEmpty()) {
			Block.popResource(this.level(), pos, leftover);
		}
	}

	/** What the current mining run is collecting. */
	private boolean isWantedLoot(ItemStack stack) {
		return mineFilter != null ? stack.is(mineFilter.item()) : resource != null && resource.matchesItem(stack);
	}

	/** Things a mining run leaves on the ground (dirt, gravel...), apart from a little cobblestone for plugging lava. */
	private boolean isJunkWhileMining(ItemStack stack) {
		if (task != BotTask.MINE || isWantedLoot(stack) || (mineFilter != null && jobs.isNeeded(stack.getItem()))
				|| isUsefulByproduct(stack)) {
			return false;
		}
		return !(isFiller(stack) && countFiller() < KEEP_FILLER);
	}

	private void countCollected(int amount) {
		if (resource == null || task != BotTask.MINE) {
			return;
		}
		collectedCount += amount;
		if (mineFilter != null) {
			jobs.siteWorked();
		}
		if (targetCount > 0 && collectedCount >= targetCount && !returnRequested) {
			returnRequested = true;
			say(Component.translatable("butterbot.msg.collected_enough", collectedCount,
					mineFilter != null ? new ItemStack(mineFilter.item()).getHoverName() : resource.displayName()));
		}
	}

	/** While mining, the bot also keeps other ores it happens to break, but not cobblestone/dirt. */
	private static boolean isUsefulByproduct(ItemStack stack) {
		for (BotResource other : BotResource.values()) {
			if (other.isUnderground() && other != BotResource.STONE && other.matchesItem(stack)) {
				return true;
			}
		}
		return stack.is(Items.IRON_INGOT) || stack.is(Items.GOLD_INGOT) || stack.is(Items.COPPER_INGOT)
				|| stack.is(Items.ANCIENT_DEBRIS) || stack.is(Items.NETHERITE_SCRAP) || stack.is(Items.QUARTZ)
				|| stack.is(Items.AMETHYST_SHARD) || stack.is(Items.FLINT) || stack.is(Items.GOLD_NUGGET);
	}

	/** Removes up to {@code amount} of an item from the backpack and returns how many were taken. */
	public int takeItem(Item item, int amount) {
		int taken = 0;
		for (int i = 0; i < inventory.getContainerSize() && taken < amount; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				int take = Math.min(amount - taken, stack.getCount());
				stack.shrink(take);
				inventory.setItem(i, stack);
				taken += take;
			}
		}
		return taken;
	}

	public int freeSlots() {
		int free = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/** Seeds, crops that replant themselves, nether wart, cocoa beans and saplings. */
	public static boolean isPlantable(ItemStack stack) {
		if (stack.is(ItemTags.SAPLINGS)) {
			return true;
		}
		return stack.getItem() instanceof BlockItem blockItem
				&& (blockItem.getBlock() instanceof CropBlock || blockItem.getBlock() instanceof NetherWartBlock
				|| blockItem.getBlock() == Blocks.COCOA);
	}

	public void deliverInventoryTo(Player player) {
		deliverInventoryTo(player, 0);
	}

	/** Hands the backpack over, keeping up to {@code keepPlantables} of each kind of seed and sapling. */
	public void deliverInventoryTo(Player player, int keepPlantables) {
		int total = 0;
		Map<Item, Integer> kept = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			ItemStack give;
			if (keepPlantables > 0 && isPlantable(stack)) {
				int alreadyKept = kept.getOrDefault(stack.getItem(), 0);
				int keep = Math.max(0, Math.min(stack.getCount(), keepPlantables - alreadyKept));
				kept.put(stack.getItem(), alreadyKept + keep);
				if (keep == stack.getCount()) {
					continue;
				}
				give = stack.split(stack.getCount() - keep);
				inventory.setItem(i, stack);
			} else {
				give = inventory.removeItemNoUpdate(i);
			}
			total += give.getCount();
			BehaviorUtils.throwItem(this, give, player.position().add(0.0D, 0.5D, 0.0D));
		}
		if (total > 0) {
			tellOwner(Component.translatable("butterbot.msg.here_you_go", total));
			speak(ModSounds.VOICE_GIVE, null, 0.0F, true);
		}
		giveStoredXp(player);
	}

	/** Moves as much of the backpack as fits into the base storage. Returns true if the backpack is empty. */
	public boolean depositInto(BotBaseBlockEntity base) {
		return depositInto(base, 0);
	}

	/**
	 * Moves the backpack into the base storage, keeping up to {@code keepPlantables} of every seed or
	 * sapling for replanting. Returns true if nothing (except those) is left.
	 */
	public boolean depositInto(BotBaseBlockEntity base, int keepPlantables) {
		return depositInto(base, base.getBlockPos(), keepPlantables);
	}

	/** {@link #depositInto(BotBaseBlockEntity, int)} for any storage (a farmer's job chest, for example). */
	public boolean depositInto(Container base, BlockPos storagePos, int keepPlantables) {
		int moved = 0;
		Map<Item, Integer> kept = new HashMap<>();
		boolean leftBehind = false;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (keepPlantables > 0 && isPlantable(stack)) {
				int alreadyKept = kept.getOrDefault(stack.getItem(), 0);
				int keep = Math.min(stack.getCount(), keepPlantables - alreadyKept);
				if (keep > 0) {
					kept.put(stack.getItem(), alreadyKept + keep);
					if (keep == stack.getCount()) {
						continue;
					}
					ItemStack toStore = stack.split(stack.getCount() - keep);
					ItemStack leftover = JobStorage.insert(base, toStore);
					moved += toStore.getCount() - leftover.getCount();
					stack.grow(leftover.getCount());
					leftBehind |= !leftover.isEmpty();
					inventory.setItem(i, stack);
					continue;
				}
			}
			int before = stack.getCount();
			ItemStack leftover = JobStorage.insert(base, stack);
			moved += before - leftover.getCount();
			leftBehind |= !leftover.isEmpty();
			inventory.setItem(i, leftover);
		}
		if (moved > 0) {
			say(Component.translatable("butterbot.msg.deposited", moved));
			this.level().playSound(null, storagePos, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.6F, 1.2F);
		}
		return !leftBehind;
	}

	// ------------------------------------------------------------------ experience, food and healing

	/** Soaks up experience orbs around {@code center} and keeps them for the owner (after mending its gear). */
	public void absorbExperience(Vec3 center, double radius) {
		for (ExperienceOrb orb : this.level().getEntitiesOfClass(ExperienceOrb.class, new AABB(center, center).inflate(radius))) {
			if (orb.isAlive()) {
				storedXp += mendGear(orb.getValue());
				orb.discard();
			}
		}
	}

	public void giveStoredXp(Player player) {
		if (storedXp <= 0) {
			return;
		}
		int amount = storedXp;
		storedXp = 0;
		player.giveExperiencePoints(amount);
		this.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.xp_given", this.getName(), amount)));
		}
	}

	/** Food without side effects: no rotten flesh, golden apples, chorus fruit, stews or poison. */
	public static boolean isSafeFood(ItemStack stack) {
		if (!stack.has(DataComponents.FOOD) || stack.has(DataComponents.USE_REMAINDER)
				|| stack.has(DataComponents.SUSPICIOUS_STEW_EFFECTS)) {
			return false;
		}
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		return consumable == null || consumable.onConsumeEffects().isEmpty();
	}

	/** The backpack slot with the most filling safe food, or -1. */
	private int bestFoodSlot() {
		int best = -1;
		int bestNutrition = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			FoodProperties food = stack.get(DataComponents.FOOD);
			if (food != null && isSafeFood(stack) && food.nutrition() > bestNutrition) {
				best = i;
				bestNutrition = food.nutrition();
			}
		}
		return best;
	}

	public boolean hasSafeFood() {
		return bestFoodSlot() >= 0;
	}

	/** Eats the best food in the backpack to heal. Returns false if there was nothing to eat. */
	public boolean eatFromBackpack() {
		int slot = bestFoodSlot();
		if (slot < 0 || !(this.level() instanceof ServerLevel serverLevel)) {
			return false;
		}
		ItemStack stack = inventory.getItem(slot);
		FoodProperties food = stack.get(DataComponents.FOOD);
		Item item = stack.getItem();
		stack.shrink(1);
		inventory.setItem(slot, stack);
		this.heal(food == null ? 2.0F : food.nutrition() * 1.5F + food.saturation());
		this.playSound(SoundEvents.GENERIC_EAT.value(), 0.7F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.2F);
		serverLevel.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, item), getX(), getEyeY() - 0.2D, getZ(),
				8, 0.2D, 0.1D, 0.2D, 0.05D);
		eatCooldown = 40;
		return true;
	}

	private void feedOwnerFrom(Player owner) {
		int slot = bestFoodSlot();
		if (slot < 0) {
			return;
		}
		ItemStack stack = inventory.getItem(slot);
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) {
			return;
		}
		Component name = stack.getHoverName().copy();
		Item item = stack.getItem();
		stack.shrink(1);
		inventory.setItem(slot, stack);
		owner.getFoodData().eat(food);
		this.level().playSound(null, owner.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.6F, 1.0F);
		if (this.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, item), owner.getX(), owner.getEyeY() - 0.2D,
					owner.getZ(), 8, 0.2D, 0.1D, 0.2D, 0.05D);
		}
		this.getLookControl().setLookAt(owner);
		this.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		say(Component.translatable("butterbot.msg.fed_owner", name));
		speak(ModSounds.VOICE_FEED);
	}

	/** Once a second: regenerate, eat when hurt, feed a hungry owner and hand over saved-up XP. */
	private void tickCare() {
		if (++careTimer < 20) {
			return;
		}
		careTimer = 0;
		eatCooldown = Math.max(0, eatCooldown - 20);
		feedCooldown = Math.max(0, feedCooldown - 20);

		if (this.getHealth() < this.getMaxHealth() && this.tickCount - lastHurtTick > 100) {
			this.heal(stats().regenPerSecond());
		}
		if (inventory.getContainerSize() > stats().backpackSlots()) {
			// A smaller backpack waits until its contents fit.
			resizeBackpack(stats().backpackSlots());
		}
		if (this.getHealth() < this.getMaxHealth() * 0.5F && eatCooldown == 0) {
			eatFromBackpack();
		}

		Player owner = getOwner();
		if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.isCreative()) {
			return;
		}
		double distanceSq = this.distanceToSqr(owner);
		if (feedOwner && feedCooldown == 0 && distanceSq < 6.0D * 6.0D
				&& owner.getFoodData().getFoodLevel() <= OWNER_FEED_FOOD_LEVEL) {
			feedCooldown = 60;
			feedOwnerFrom(owner);
		}
		if (storedXp > 0 && distanceSq < 4.0D * 4.0D) {
			giveStoredXp(owner);
		}
		if (autoRefill && distanceSq < 8.0D * 8.0D) {
			hotbarRefill.tick(this, owner);
		}
	}

	public boolean placeBlockFromInventory(BlockPos pos, Item item) {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item) && item instanceof net.minecraft.world.item.BlockItem blockItem) {
				BlockState placeState = blockItem.getBlock().defaultBlockState();
				if (!this.level().getBlockState(pos).canBeReplaced() || !placeState.canSurvive(this.level(), pos)) {
					return false;
				}
				if (!this.level().isUnobstructed(placeState, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) {
					return false;
				}
				this.level().setBlock(pos, placeState, 3);
				this.level().playSound(null, pos, placeState.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
				stack.shrink(1);
				inventory.setItem(i, stack);
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("BotState", state.name());
		output.putString("BotTask", task.name());
		output.putString("BotResource", resource == null ? "" : resource.name());
		output.putInt("TargetCount", targetCount);
		output.putInt("CollectedCount", collectedCount);
		output.putBoolean("ReturnRequested", returnRequested);
		output.putString("Delivery", deliveryMode.name());
		output.putInt("BotLevel", getBotLevel());
		output.putInt("WorkXp", workXp);
		output.putIntArray("Progress", progress.save());
		output.putLong("SecondWind", secondWindReadyAt);
		output.putInt("StoredXp", storedXp);
		output.putBoolean("Guard", guard);
		output.putBoolean("AutoTorch", autoTorch);
		output.putBoolean("FeedOwner", feedOwner);
		output.putString("SmeltMode", smeltMode.name());
		output.storeNullable("FarmCenter", BlockPos.CODEC, farmCenter);
		output.putInt("Harvested", harvestedCount);
		inventory.storeAsItemList(output.list("BotInventory", ItemStack.CODEC));
		ValueOutput.TypedOutputList<ItemStackWithSlot> tools = output.list("Tools", ItemStackWithSlot.CODEC);
		for (int i = 0; i < toolbox.getContainerSize(); i++) {
			if (!toolbox.getItem(i).isEmpty()) {
				tools.add(new ItemStackWithSlot(i, toolbox.getItem(i)));
			}
		}

		if (tunnelDirection != null && tunnelOrigin != null) {
			output.putString("TunnelDir", tunnelDirection.getName());
			output.store("TunnelOrigin", BlockPos.CODEC, tunnelOrigin);
			output.putInt("TunnelLength", tunnelLength);
			output.putInt("TunnelProgress", tunnelProgress);
			output.putInt("TunnelWidth", tunnelWidth);
			output.putInt("TunnelHeight", tunnelHeight);
			output.putBoolean("TunnelTorches", tunnelTorches);
		}
		if (craftItem != null) {
			output.putString("CraftItem", BuiltInRegistries.ITEM.getKey(craftItem).toString());
			output.putInt("CraftCount", craftCount);
		}
		output.storeNullable("BasePos", BlockPos.CODEC, basePos);
		output.putString("LuaScript", luaScript);
		output.putString("Skin", getSkinId());
		if (skinVariantChosen) {
			output.putInt("SkinVariant", getSkinVariant());
		}
		output.putBoolean("Neko", isNeko());
		output.storeNullable("Owner", UUIDUtil.CODEC, ownerUuid);
		if (ownerName != null) {
			output.putString("OwnerName", ownerName);
		}
		output.storeNullable("TargetBlock", BlockPos.CODEC, targetBlock);
		output.putBoolean("AutoRefill", autoRefill);
		output.storeNullable("RecoverPos", BlockPos.CODEC, recoverPos);
		ValueOutput.TypedOutputList<ItemStack> recovered = output.list("Recovered", ItemStack.CODEC);
		recoveredItems.forEach(recovered::add);
		ValueOutput.TypedOutputList<ItemStack> carry = output.list("FetchCarry", ItemStack.CODEC);
		fetchCarry.forEach(carry::add);
		if (task == BotTask.LIGHT && lightCenter != null) {
			output.store("LightCenter", BlockPos.CODEC, lightCenter);
			output.putInt("LightRadius", lightRadius);
			output.putInt("TorchesPlaced", torchesPlaced);
		}
		if (task == BotTask.FETCH && fetchItem != null) {
			output.putString("FetchItem", BuiltInRegistries.ITEM.getKey(fetchItem).toString());
			output.putInt("FetchCount", fetchCount);
		}
		output.storeNullable("GotoPos", BlockPos.CODEC, gotoPos);
		output.store("Job", JobConfig.Data.CODEC, jobs.save());
		output.storeNullable("LastDimension", Level.RESOURCE_KEY_CODEC, lastDimension);
		output.store("PortalExits", Codec.unboundedMap(Codec.STRING, BlockPos.CODEC), Map.copyOf(portalExits));
		output.putBoolean("JobMining", mineFilter != null);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		state = readEnum(input.getStringOr("BotState", ""), BotState.class, BotState.IDLE);
		task = readEnum(input.getStringOr("BotTask", ""), BotTask.class, BotTask.NONE);
		String resourceName = input.getStringOr("BotResource", "");
		resource = resourceName.isEmpty() ? null : readEnum(resourceName, BotResource.class, null);
		targetCount = input.getIntOr("TargetCount", 0);
		collectedCount = input.getIntOr("CollectedCount", 0);
		returnRequested = input.getBooleanOr("ReturnRequested", false);
		deliveryMode = readEnum(input.getStringOr("Delivery", ""), DeliveryMode.class, DeliveryMode.OWNER);
		// Level and skills decide the backpack size, so they are restored before the backpack.
		input.getIntArray("Progress").ifPresent(progress::load);
		secondWindReadyAt = input.getLongOr("SecondWind", 0L);
		restoreProgress(input.getIntOr("BotLevel", 1), input.getIntOr("WorkXp", 0));
		storedXp = input.getIntOr("StoredXp", 0);
		guard = input.getBooleanOr("Guard", true);
		autoTorch = input.getBooleanOr("AutoTorch", true);
		feedOwner = input.getBooleanOr("FeedOwner", true);
		smeltMode = readEnum(input.getStringOr("SmeltMode", ""), SmeltMode.class, SmeltMode.ORES);
		farmCenter = input.read("FarmCenter", BlockPos.CODEC).orElse(null);
		harvestedCount = input.getIntOr("Harvested", 0);
		inventory.fromItemList(input.listOrEmpty("BotInventory", ItemStack.CODEC));
		toolbox.clearContent();
		for (ItemStackWithSlot tool : input.listOrEmpty("Tools", ItemStackWithSlot.CODEC)) {
			if (tool.isValidInContainer(toolbox.getContainerSize())) {
				toolbox.setItem(tool.slot(), tool.stack());
			}
		}
		// The first version with gear saved one list, empty slots included.
		input.read("BotTools", ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(tools -> {
			for (int i = 0; i < Math.min(tools.size(), toolbox.getContainerSize()); i++) {
				if (toolbox.getItem(i).isEmpty()) {
					toolbox.setItem(i, tools.get(i));
				}
			}
		});

		ownerUuid = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		ownerName = input.getString("OwnerName").orElse(null);
		targetBlock = input.read("TargetBlock", BlockPos.CODEC).orElse(null);
		basePos = input.read("BasePos", BlockPos.CODEC).orElse(null);

		input.getString("TunnelDir").ifPresent(dir -> {
			tunnelDirection = Direction.byName(dir);
			tunnelOrigin = input.read("TunnelOrigin", BlockPos.CODEC).orElse(null);
			tunnelLength = input.getIntOr("TunnelLength", 0);
			tunnelProgress = input.getIntOr("TunnelProgress", 0);
			tunnelWidth = input.getIntOr("TunnelWidth", 2);
			tunnelHeight = input.getIntOr("TunnelHeight", 2);
			tunnelTorches = input.getBooleanOr("TunnelTorches", true);
		});

		input.getString("CraftItem").ifPresent(id -> {
			Identifier itemId = Identifier.tryParse(id);
			craftItem = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
			craftCount = input.getIntOr("CraftCount", 1);
		});

		luaScript = input.getStringOr("LuaScript", "");
		setSkinId(input.getStringOr("Skin", ""));
		input.getInt("SkinVariant").ifPresent(this::setSkinVariant);
		setNeko(input.getBooleanOr("Neko", true));

		autoRefill = input.getBooleanOr("AutoRefill", true);
		recoverPos = input.read("RecoverPos", BlockPos.CODEC).orElse(null);
		recoveredItems.clear();
		input.listOrEmpty("Recovered", ItemStack.CODEC).forEach(recoveredItems::add);
		fetchCarry.clear();
		input.listOrEmpty("FetchCarry", ItemStack.CODEC).forEach(fetchCarry::add);
		lightCenter = input.read("LightCenter", BlockPos.CODEC).orElse(null);
		lightRadius = input.getIntOr("LightRadius", LightAreaAction.DEFAULT_RADIUS);
		torchesPlaced = input.getIntOr("TorchesPlaced", 0);
		input.getString("FetchItem").ifPresent(id -> {
			Identifier itemId = Identifier.tryParse(id);
			fetchItem = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
			fetchCount = input.getIntOr("FetchCount", 1);
		});
		gotoPos = input.read("GotoPos", BlockPos.CODEC).orElse(null);
		input.read("Job", JobConfig.Data.CODEC).ifPresent(jobs::load);
		lastDimension = input.read("LastDimension", Level.RESOURCE_KEY_CODEC).orElse(null);
		portalExits.clear();
		input.read("PortalExits", Codec.unboundedMap(Codec.STRING, BlockPos.CODEC)).ifPresent(portalExits::putAll);
		if (input.getBooleanOr("JobMining", false) && task == BotTask.MINE) {
			// The job picks its mining run up again by itself (back at its saved dig site).
			task = BotTask.NONE;
			state = BotState.IDLE;
			resource = null;
			returnRequested = false;
		}

		// Programs never resume automatically after a reload; half-configured tasks are dropped.
		if (task == BotTask.LUA
				|| (task == BotTask.MINE && resource == null)
				|| (task == BotTask.TUNNEL && (tunnelDirection == null || tunnelOrigin == null))
				|| (task == BotTask.CRAFT && craftItem == null)
				|| (task == BotTask.LIGHT && lightCenter == null)
				|| (task == BotTask.FETCH && fetchItem == null)
				|| (task == BotTask.GOTO && gotoPos == null)) {
			task = BotTask.NONE;
			state = BotState.IDLE;
		}
	}

	@Nullable
	private static <T extends Enum<T>> T readEnum(String name, Class<T> type, @Nullable T fallback) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException exception) {
			return fallback;
		}
	}

	// ------------------------------------------------------------------ misc overrides

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	public boolean canBreatheUnderwater() {
		return true;
	}

	/** Never gets trapped in a boat or minecart it bumps into. */
	@Override
	protected boolean canRide(net.minecraft.world.entity.Entity vehicle) {
		return false;
	}

	/** Powder snow doesn't freeze it. */
	@Override
	public boolean canFreeze() {
		return false;
	}

	/** Owners can't break their own bot by accident; sneak while attacking to really hit it. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (source.getEntity() instanceof Player player && isOwnedBy(player) && !player.isShiftKeyDown()) {
			return false;
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION) && stats().has(BotStats.Ability.BLAST_RESISTANT)) {
			damage *= 0.5F;
		}
		boolean hurt = super.hurtServer(level, source, damage);
		if (hurt) {
			lastHurtTick = this.tickCount;
		}
		return hurt;
	}

	/** Second Wind (level 25): a blow that would destroy the bot leaves it at half health instead, once every 10 minutes. */
	@Override
	protected void actuallyHurt(ServerLevel level, DamageSource source, float damage) {
		super.actuallyHurt(level, source, damage);
		if (this.isDeadOrDying() && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
				&& stats().has(BotStats.Ability.SECOND_WIND) && level.getGameTime() >= secondWindReadyAt) {
			secondWindReadyAt = level.getGameTime() + SECOND_WIND_COOLDOWN;
			this.setHealth(this.getMaxHealth() * 0.5F);
			level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, getX(), getY() + 1.0D, getZ(), 40, 0.5D, 0.8D, 0.5D, 0.3D);
			this.playSound(SoundEvents.TOTEM_USE, 0.8F, 1.2F);
			tellOwner(Component.translatable("butterbot.msg.second_wind", getName()).withStyle(ChatFormatting.GOLD));
		}
	}

	/** Fire and lava don't hurt it from level 12 on. */
	@Override
	public boolean fireImmune() {
		return super.fireImmune() || getBotLevel() >= BotStats.Ability.FIRE_IMMUNE.level();
	}

	/** Poison and wither don't affect it from level 14 on. */
	@Override
	public boolean canBeAffected(MobEffectInstance effect) {
		if ((effect.is(MobEffects.POISON) || effect.is(MobEffects.WITHER)) && getBotLevel() >= BotStats.Ability.POISON_IMMUNE.level()) {
			return false;
		}
		return super.canBeAffected(effect);
	}

	/**
	 * Sweeping Blow skill: a hit also catches the monsters right around the target (only ones the bot
	 * would fight anyway: no Endermen, piglins, Wardens or named mobs that leave it alone).
	 */
	@Override
	public boolean doHurtTarget(ServerLevel level, Entity target) {
		ItemStack held = weapon();
		if (!looksSame(this.getMainHandItem(), held)) {
			// Its enchantments (Sharpness, Fire Aspect, Looting...) count from the first blow.
			this.setItemSlot(EquipmentSlot.MAINHAND, held.copy());
		}
		boolean hit = super.doHurtTarget(level, target);
		if (hit && !held.isEmpty() && held == getGear(GearSlot.SWORD)) {
			Weapon weaponData = held.get(DataComponents.WEAPON);
			wearTool(GearSlot.SWORD, weaponData == null ? 1 : weaponData.itemDamagePerAttack());
		}
		float sweep = stats().sweepFraction();
		if (!hit || sweep <= 0.0F) {
			return hit;
		}
		List<Mob> near = level.getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(2.0D, 0.5D, 2.0D),
				mob -> mob != target && GuardAction.isHostile(this, mob));
		if (near.isEmpty()) {
			return true;
		}
		float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE) * sweep;
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5D), target.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
		this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 0.8F, 1.0F);
		for (Mob other : near) {
			if (other.hurtServer(level, this.damageSources().mobAttack(this), damage) && !other.isAlive()) {
				onDefeated(other);
			}
		}
		return true;
	}

	/** Bots tiptoe through fields: landing on farmland never tramples it. */
	@Override
	protected void checkFallDamage(double y, boolean onGround, BlockState onState, BlockPos onPos) {
		if (onState.is(Blocks.FARMLAND) || this.level().getBlockState(onPos.below()).is(Blocks.FARMLAND)) {
			this.resetFallDistance();
			return;
		}
		super.checkFallDamage(y, onGround, onState, onPos);
	}

	@Override
	public void die(DamageSource source) {
		// The held tool is only for looks; never drop it.
		this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		super.die(source);
		if (this.level() instanceof ServerLevel serverLevel && storedXp > 0) {
			ExperienceOrb.award(serverLevel, this.position(), storedXp);
			storedXp = 0;
		}
		if (!this.level().isClientSide()) {
			brain.reset(this);
			if (getOwner() instanceof ServerPlayer owner) {
				owner.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.msg.destroyed",
						this.getName(), source.getLocalizedDeathMessage(this))).withStyle(ChatFormatting.RED));
			}
		}
	}

	/** Gear drops whole, never with the random wear of a monster's dropped equipment. */
	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		dropGear(level);
		super.dropCustomDeathLoot(level, source, killedByPlayer);
	}

	@Override
	protected void dropEquipment(ServerLevel level) {
		dropGear(level);
		super.dropEquipment(level);
		Containers.dropContents(level, this.blockPosition(), inventory);
		// The owner's rescued things and half-fetched items are not lost either.
		for (ItemStack stack : recoveredItems) {
			this.spawnAtLocation(level, stack);
		}
		for (ItemStack stack : fetchCarry) {
			this.spawnAtLocation(level, stack);
		}
		recoveredItems.clear();
		fetchCarry.clear();
	}
}
