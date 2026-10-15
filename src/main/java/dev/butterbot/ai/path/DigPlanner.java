package dev.butterbot.ai.path;

import dev.butterbot.ai.MiningController;
import dev.butterbot.ai.TunnelHelper;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Plans a whole route through the terrain, the way a player who can dig would go: walk, step up or
 * down a block, drop a little way, dig through natural ground, dig straight down, and only when
 * there is no other way, bridge a gap or pillar up with cobblestone. Lava, deep drops and blocks the
 * bot may not dig (builds, bedrock, the no-dig zone...) are avoided.
 * <p>
 * It is an A* search over the positions of the bot's feet. A move costs about the ticks it takes,
 * digging included, so the route found is quick to do rather than just short. The search is
 * capped, and when the goal can't be reached the best partial route towards it is returned.
 */
public final class DigPlanner {

	private static final int MAX_NODES = 6000;
	private static final int MAX_RANGE = 48;
	private static final int MAX_HEIGHT_RANGE = 40;
	private static final int MAX_DROP = 3;
	/** Rough ticks per block walked. */
	private static final double WALK = 5.0D;
	private static final double CLIMB = 3.0D;
	/** Putting a block down is quick, but it uses cobblestone and leaves a mark: only when needed. */
	private static final double PLACE = 20.0D;
	private static final double PILLAR = 30.0D;
	private static final double WATER = 10.0D;
	private static final double NEXT_TO_LAVA = 40.0D;
	private static final double UNDER_GRAVEL = 20.0D;
	/** Opening a fence gate or door on the way. */
	private static final double GATE = 6.0D;
	/** A partial route must get at least this much closer (estimate units) to be worth walking. */
	private static final double MIN_PARTIAL_GAIN = WALK * 2.0D;

	/** Where the route has to end. */
	public interface Goal {
		boolean reached(BlockPos feet);

		/** Estimated cost from {@code feet} to the goal. */
		double estimate(BlockPos feet);
	}

	/** Close enough to {@code target} to dig it: the block touches the bot's body (or is right above its head / under its feet). */
	public static Goal mine(BlockPos target) {
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				int dx = Math.abs(target.getX() - feet.getX());
				int dz = Math.abs(target.getZ() - feet.getZ());
				int dy = target.getY() - feet.getY();
				if (dx == 0 && dz == 0) {
					return dy == -1 || dy == 2;
				}
				return dx + dz == 1 && dy >= -1 && dy <= 1;
			}

			@Override
			public double estimate(BlockPos feet) {
				int distance = Math.abs(target.getX() - feet.getX()) + Math.abs(target.getY() - feet.getY())
						+ Math.abs(target.getZ() - feet.getZ());
				return Math.max(0, distance - 1) * WALK;
			}
		};
	}

	/** Feet within {@code tolerance} blocks (sideways) and one block (up or down) of {@code spot}. */
	public static Goal reach(BlockPos spot, int tolerance) {
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				return Math.abs(spot.getX() - feet.getX()) + Math.abs(spot.getZ() - feet.getZ()) <= tolerance
						&& Math.abs(spot.getY() - feet.getY()) <= 1;
			}

			@Override
			public double estimate(BlockPos feet) {
				int distance = Math.abs(spot.getX() - feet.getX()) + Math.abs(spot.getY() - feet.getY())
						+ Math.abs(spot.getZ() - feet.getZ());
				return Math.max(0, distance - tolerance) * WALK;
			}
		};
	}

	/**
	 * UNKNOWN: not loaded or outside the world, never stood on or walked through. GATE: a closed fence
	 * gate or door, opened on the way. WALL: taller than a block (fences, walls), never stood on.
	 */
	private enum Kind {OPEN, WATER, DIG, SOLID, LAVA, HAZARD, UNKNOWN, GATE, WALL}

	private record Cell(Kind kind, double digCost) {
		boolean passable() {
			return kind == Kind.OPEN || kind == Kind.WATER;
		}

		/** Something the bot can stand on. */
		boolean floor() {
			return kind == Kind.DIG || kind == Kind.SOLID;
		}
	}

	private static final class Node {
		final BlockPos pos;
		double g;
		double f;
		@Nullable
		Node parent;
		@Nullable
		DigPath.Step step;
		boolean closed;

		Node(BlockPos pos) {
			this.pos = pos;
		}
	}

	private record Open(Node node, double f) {
	}

	private final ButterBotEntity bot;
	private final Level level;
	@Nullable
	private final BlockPos target;
	private final BlockPos start;
	/** False: walk, climb, drop, swim and open gates only (no digging, bridging or pillaring). */
	private final boolean dig;
	private final Map<Long, Cell> cells = new HashMap<>();

	private DigPlanner(ButterBotEntity bot, @Nullable BlockPos target, boolean dig) {
		this.bot = bot;
		this.level = bot.level();
		this.target = target;
		this.start = PathFollower.feetOf(bot);
		this.dig = dig;
	}

	/**
	 * A route for {@code bot} to {@code goal}, or null if there is none worth taking. {@code target}
	 * is the block it is going to mine (it may be dug even though it isn't plain terrain, like a log).
	 */
	@Nullable
	public static DigPath plan(ButterBotEntity bot, Goal goal, @Nullable BlockPos target) {
		return new DigPlanner(bot, target, true).search(goal);
	}

	/** A route that only walks (and opens gates): for places where nothing may be dug or built, like a ranch. */
	@Nullable
	public static DigPath planWalking(ButterBotEntity bot, Goal goal) {
		return new DigPlanner(bot, null, false).search(goal);
	}

	@Nullable
	private DigPath search(Goal goal) {
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Node first = new Node(start);
		first.f = goal.estimate(start);
		nodes.put(start.asLong(), first);
		open.add(new Open(first, first.f));
		Node best = first;
		double bestEstimate = first.f;
		int expanded = 0;

		while (!open.isEmpty() && expanded < MAX_NODES) {
			Open entry = open.poll();
			Node node = entry.node;
			if (node.closed || entry.f > node.f) {
				continue;
			}
			node.closed = true;
			expanded++;
			if (goal.reached(node.pos)) {
				return build(node, true);
			}
			double estimate = goal.estimate(node.pos);
			if (estimate < bestEstimate) {
				bestEstimate = estimate;
				best = node;
			}
			for (Candidate next : moves(node.pos)) {
				if (Math.abs(next.feet.getX() - start.getX()) > MAX_RANGE || Math.abs(next.feet.getZ() - start.getZ()) > MAX_RANGE
						|| Math.abs(next.feet.getY() - start.getY()) > MAX_HEIGHT_RANGE) {
					continue;
				}
				if (!standable(next.feet)) {
					continue;
				}
				double g = node.g + next.cost;
				Node child = nodes.computeIfAbsent(next.feet.asLong(), key -> new Node(next.feet));
				if (child.closed || (child.parent != null || child == first) && g >= child.g) {
					continue;
				}
				child.g = g;
				child.f = g + goal.estimate(next.feet);
				child.parent = node;
				child.step = next.step;
				open.add(new Open(child, child.f));
			}
		}
		// Out of reach (or too far to work out): walk the part that gets closest, if it gets anywhere.
		if (best == first || goal.estimate(start) - bestEstimate < MIN_PARTIAL_GAIN) {
			return null;
		}
		return build(best, false);
	}

	private DigPath build(Node end, boolean reached) {
		List<DigPath.Step> steps = new ArrayList<>();
		for (Node node = end; node.parent != null; node = node.parent) {
			steps.add(node.step);
		}
		Collections.reverse(steps);
		return new DigPath(start, List.copyOf(steps), reached);
	}

	private record Candidate(BlockPos feet, double cost, DigPath.Step step) {
	}

	/** Every move from feet at {@code p}. */
	private List<Candidate> moves(BlockPos p) {
		List<Candidate> out = new ArrayList<>(12);
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos front = p.relative(direction);
			Cell feet = cell(front);
			Cell head = cell(front.above());
			boolean bodyClear = clearable(feet) && clearable(head);
			if (bodyClear) {
				double bodyCost = enter(feet) + enter(head);
				Cell floor = cell(front.below());
				if (floor.floor() && safeFloor(front.below())) {
					// Walk (digging the two blocks in front if needed).
					out.add(new Candidate(front, WALK + bodyCost, step(front, DigPath.Move.WALK, null, front.above(), front)));
				} else if (feet.kind == Kind.WATER && floor.kind == Kind.WATER && head.kind == Kind.OPEN) {
					// Deep water: swim across the top.
					out.add(new Candidate(front, WALK + bodyCost, step(front, DigPath.Move.SWIM, null, front.above(), front)));
				} else if (floor.passable()) {
					// An edge: drop down a little, or bridge the gap.
					addDrop(out, front, bodyCost);
					if (dig) {
						out.add(new Candidate(front, WALK + bodyCost + PLACE, step(front, DigPath.Move.BRIDGE, front.below(), front.above(), front)));
					}
				}
				if (floor.kind == Kind.DIG) {
					// A stair step down: dig the block in front at foot level too and step into it.
					BlockPos down = front.below();
					Cell landing = cell(down.below());
					if (landing.floor() && safeFloor(down.below())) {
						out.add(new Candidate(down, WALK + CLIMB + bodyCost + floor.digCost,
								step(down, DigPath.Move.STEP_DOWN, null, front.above(), front, down)));
					}
				}
			}
			// A stair step up: room above the head here, the two blocks above the step in front clear.
			BlockPos up = front.above();
			Cell aboveHead = cell(p.above(2));
			Cell upFeet = cell(up);
			Cell upHead = cell(up.above());
			if (feet.floor() && safeFloor(front) && clearable(aboveHead) && clearable(upFeet) && clearable(upHead)) {
				out.add(new Candidate(up, WALK + CLIMB + enter(aboveHead) + enter(upFeet) + enter(upHead),
						step(up, DigPath.Move.STEP_UP, null, p.above(2), up.above(), up)));
			}
		}
		// Dig straight down into the block under the feet (with solid ground under that).
		BlockPos below = p.below();
		Cell under = cell(below);
		if (under.kind == Kind.DIG && cell(below.below()).floor() && safeFloor(below.below())) {
			out.add(new Candidate(below, CLIMB + under.digCost, step(below, DigPath.Move.DIG_DOWN, null, below)));
		}
		// Pillar up: jump and put a block where the feet were.
		Cell aboveHead = cell(p.above(2));
		if (dig && clearable(aboveHead) && cell(p).passable()) {
			out.add(new Candidate(p.above(), PILLAR + enter(aboveHead), step(p.above(), DigPath.Move.PILLAR, null, p.above(2))));
		}
		return out;
	}

	/** Walking off the edge in front and landing 1 to {@value #MAX_DROP} blocks lower, through open air only. */
	private void addDrop(List<Candidate> out, BlockPos front, double bodyCost) {
		for (int depth = 1; depth <= MAX_DROP; depth++) {
			BlockPos feet = front.below(depth);
			Cell cell = cell(feet);
			if (!cell.passable()) {
				return;
			}
			Cell floor = cell(feet.below());
			if (floor.kind == Kind.LAVA) {
				return;
			}
			if (floor.floor()) {
				if (safeFloor(feet.below())) {
					out.add(new Candidate(feet, WALK + bodyCost + depth * 2.0D, step(feet, DigPath.Move.DROP, null, front.above(), front)));
				}
				return;
			}
		}
	}

	private static DigPath.Step step(BlockPos feet, DigPath.Move move, @Nullable BlockPos place, BlockPos... dig) {
		return new DigPath.Step(feet.immutable(), move, List.of(dig), place);
	}

	/**
	 * The bot can stay with its feet here. In water deeper than one block it floats up to the top
	 * cell, so only the top cell of a pool counts.
	 */
	private boolean standable(BlockPos feet) {
		return cell(feet).kind != Kind.WATER || cell(feet.above()).kind != Kind.WATER;
	}

	/** Open, something the bot may dig away, or a gate it opens. */
	private static boolean clearable(Cell cell) {
		return cell.passable() || cell.kind == Kind.DIG || cell.kind == Kind.GATE;
	}

	/** Extra cost of moving through a cell: digging it, or wading through water. */
	private static double enter(Cell cell) {
		return switch (cell.kind) {
			case DIG -> cell.digCost;
			case WATER -> WATER;
			case GATE -> GATE;
			default -> 0.0D;
		};
	}

	/** Ground that doesn't hurt: no magma, campfires, cactus or the like. */
	private boolean safeFloor(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.is(Blocks.MAGMA_BLOCK) && !state.is(BlockTags.CAMPFIRES) && !state.is(Blocks.CACTUS)
				&& !state.is(Blocks.POINTED_DRIPSTONE) && !state.is(Blocks.SWEET_BERRY_BUSH);
	}

	private Cell cell(BlockPos pos) {
		long key = pos.asLong();
		Cell cached = cells.get(key);
		if (cached == null) {
			cached = classify(pos);
			cells.put(key, cached);
		}
		return cached;
	}

	private Cell classify(BlockPos pos) {
		if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
			return new Cell(Kind.UNKNOWN, 0.0D);
		}
		BlockState state = level.getBlockState(pos);
		FluidState fluid = state.getFluidState();
		if (fluid.is(FluidTags.LAVA)) {
			return new Cell(Kind.LAVA, 0.0D);
		}
		if (TunnelHelper.isGateway(state)) {
			// Open ones are walked through as they are (an open door's frame is in the way only a little).
			return new Cell(TunnelHelper.isClosedGateway(state) ? Kind.GATE : Kind.OPEN, 0.0D);
		}
		if (state.getCollisionShape(level, pos).isEmpty()) {
			if (TunnelHelper.isTrap(state)) {
				return dig && TunnelHelper.canClear(bot, pos, state) ? new Cell(Kind.DIG, digCost(pos, state)) : new Cell(Kind.HAZARD, 0.0D);
			}
			if (state.is(BlockTags.FIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)) {
				return new Cell(Kind.HAZARD, 0.0D);
			}
			return new Cell(fluid.isEmpty() ? Kind.OPEN : Kind.WATER, 0.0D);
		}
		if (dig && (pos.equals(target) || TunnelHelper.canClear(bot, pos, state))) {
			return new Cell(Kind.DIG, digCost(pos, state));
		}
		if (state.getCollisionShape(level, pos).max(Direction.Axis.Y) > 1.0D) {
			// Fences and walls: too tall to step onto.
			return new Cell(Kind.WALL, 0.0D);
		}
		return new Cell(Kind.SOLID, 0.0D);
	}

	private double digCost(BlockPos pos, BlockState state) {
		double cost = MiningController.estimateTicks(bot, pos, state) + 2.0D;
		BlockPos above = pos.above();
		if (level.isLoaded(above) && level.getBlockState(above).getBlock() instanceof FallingBlock) {
			// Sand or gravel comes down into the hole and has to be dug again.
			cost += UNDER_GRAVEL;
		}
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (level.isLoaded(neighbour) && level.getFluidState(neighbour).is(FluidTags.LAVA)) {
				// The bot plugs the lava first, but going near it at all is best avoided.
				cost += NEXT_TO_LAVA;
				break;
			}
		}
		return cost;
	}
}
