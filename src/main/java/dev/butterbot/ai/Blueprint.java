package dev.butterbot.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A building the AI designed, as JSON: {"name": "...", "ops": [{"fill": [x1,y1,z1,x2,y2,z2], "block": "...",
 * "hollow": true}, {"set": [x,y,z], "block": "..."}]}. Blocks are written like in commands: "oak_stairs[facing=north]".
 * The design's frame: x to the right, y up (0 = the first layer above the ground, -1 = the ground itself), z away from
 * the owner; "north" faces the owner. It is turned to where the owner looks and placed in front of them.
 */
public final class Blueprint {

	public static final int MAX_SIZE = 48;
	public static final int MAX_HEIGHT = 40;
	public static final int MAX_BLOCKS = 20000;
	/** Never built, whatever the design says. */
	private static final Set<Block> FORBIDDEN = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.TNT, Blocks.BEDROCK,
			Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK,
			Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.BARRIER, Blocks.SPAWNER, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME,
			Blocks.NETHER_PORTAL, Blocks.END_GATEWAY, Blocks.LIGHT, Blocks.REINFORCED_DEEPSLATE, Blocks.TRIAL_SPAWNER, Blocks.VAULT);

	public record Placement(BlockPos pos, BlockState state) {
	}

	public record Plan(String name, List<Placement> placements) {
	}

	/** Reads the AI's answer (JSON, perhaps wrapped in text or a code block) into world placements in front of {@code origin}. */
	@Nullable
	public static Plan parse(String answer, BlockPos origin, Direction facing) {
		int start = answer.indexOf('{');
		int end = answer.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return null;
		}
		JsonObject json;
		try {
			json = JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		Rotation rotation = rotationFor(facing);
		// Later ops overwrite earlier ones at the same spot (a door cut into a wall).
		Map<BlockPos, BlockState> local = new LinkedHashMap<>();
		JsonArray ops = json.has("ops") && json.get("ops").isJsonArray() ? json.getAsJsonArray("ops") : new JsonArray();
		for (JsonElement element : ops) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject op = element.getAsJsonObject();
			BlockState state = op.has("block") ? state(op.get("block").getAsString()) : null;
			if (state == null) {
				continue;
			}
			if (op.has("set")) {
				int[] at = ints(op.get("set"), 3);
				if (at != null && inBounds(at[0], at[1], at[2])) {
					local.put(new BlockPos(at[0], at[1], at[2]), state);
				}
			} else if (op.has("fill")) {
				int[] box = ints(op.get("fill"), 6);
				if (box == null) {
					continue;
				}
				boolean hollow = op.has("hollow") && op.get("hollow").getAsBoolean();
				int x1 = Math.min(box[0], box[3]), x2 = Math.max(box[0], box[3]);
				int y1 = Math.min(box[1], box[4]), y2 = Math.max(box[1], box[4]);
				int z1 = Math.min(box[2], box[5]), z2 = Math.max(box[2], box[5]);
				for (int x = x1; x <= x2; x++) {
					for (int y = y1; y <= y2; y++) {
						for (int z = z1; z <= z2; z++) {
							boolean edge = x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
							if (inBounds(x, y, z) && (!hollow || edge)) {
								local.put(new BlockPos(x, y, z), state);
							}
						}
					}
				}
			}
			if (local.size() > MAX_BLOCKS) {
				break;
			}
		}
		List<Placement> placements = new ArrayList<>();
		local.forEach((pos, state) -> placements.add(new Placement(origin.offset(pos.rotate(rotation)), state.rotate(rotation))));
		// Clearing (air) first, top down; then solid blocks bottom up; then what hangs on them (doors, torches, crops...).
		placements.sort(Comparator.<Placement>comparingInt(p -> p.state().isAir() ? 0 : p.state().isSolid() ? 1 : 2)
				.thenComparingInt(p -> p.state().isAir() ? -p.pos().getY() : p.pos().getY()));
		String name = json.has("name") ? json.get("name").getAsString() : "bangunan";
		return placements.isEmpty() ? null : new Plan(name.length() > 40 ? name.substring(0, 40) : name, placements);
	}

	private static boolean inBounds(int x, int y, int z) {
		return Math.abs(x) <= MAX_SIZE / 2 && z >= -2 && z <= MAX_SIZE && y >= -8 && y <= MAX_HEIGHT;
	}

	/** The design's +z (south) turned to where the owner looks. */
	public static Rotation rotationFor(Direction facing) {
		return switch (facing) {
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	@Nullable
	private static int[] ints(JsonElement element, int count) {
		if (!element.isJsonArray() || element.getAsJsonArray().size() < count) {
			return null;
		}
		int[] values = new int[count];
		for (int i = 0; i < count; i++) {
			values[i] = element.getAsJsonArray().get(i).getAsInt();
		}
		return values;
	}

	/** "oak_stairs[facing=north,half=bottom]" or "minecraft:glass"; unknown blocks and properties are ignored. */
	@Nullable
	static BlockState state(String text) {
		String spec = text.trim().toLowerCase(Locale.ROOT);
		String props = "";
		int bracket = spec.indexOf('[');
		if (bracket >= 0) {
			props = spec.substring(bracket + 1, spec.endsWith("]") ? spec.length() - 1 : spec.length());
			spec = spec.substring(0, bracket);
		}
		Identifier id = Identifier.tryParse(spec.contains(":") ? spec : "minecraft:" + spec);
		if (id == null) {
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if (block == null || FORBIDDEN.contains(block)) {
			return null;
		}
		BlockState state = block.defaultBlockState();
		for (String pair : props.split(",")) {
			String[] kv = pair.split("=");
			if (kv.length == 2) {
				Property<?> property = block.getStateDefinition().getProperty(kv[0].trim());
				if (property != null) {
					state = with(state, property, kv[1].trim());
				}
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}

	private Blueprint() {
	}
}
