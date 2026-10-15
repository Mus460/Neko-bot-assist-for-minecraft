package dev.butterbot.ai.job;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The farm animals a rancher looks after (not pets, horses or wild animals), and how to count them
 * around its spot. Each kind can have its own limit; the panel shows them in {@link #KINDS} order.
 */
public final class Livestock {

	public static final List<EntityType<?>> KINDS = List.of(EntityTypes.SHEEP, EntityTypes.COW, EntityTypes.CHICKEN,
			EntityTypes.PIG, EntityTypes.GOAT, EntityTypes.RABBIT, EntityTypes.MOOSHROOM);

	/** How far above or below the spot animals still count as part of the ranch. */
	public static final double HEIGHT = 8.0D;

	public static boolean isLivestock(Animal animal) {
		return KINDS.contains(animal.getType());
	}

	/** The saved/network name of a kind, like {@code minecraft:sheep}. */
	public static String id(EntityType<?> kind) {
		return EntityType.getKey(kind).toString();
	}

	/** A kind by its id ({@code minecraft:sheep} or just {@code sheep}), or null if it isn't livestock. */
	@Nullable
	public static EntityType<?> byId(String id) {
		for (EntityType<?> kind : KINDS) {
			if (id(kind).equals(id) || EntityType.getKey(kind).getPath().equals(id)) {
				return kind;
			}
		}
		return null;
	}

	/** The item the panel shows for a kind. */
	public static Item icon(EntityType<?> kind) {
		if (kind == EntityTypes.SHEEP) {
			return Items.SHEEP_SPAWN_EGG;
		}
		if (kind == EntityTypes.COW) {
			return Items.COW_SPAWN_EGG;
		}
		if (kind == EntityTypes.CHICKEN) {
			return Items.CHICKEN_SPAWN_EGG;
		}
		if (kind == EntityTypes.PIG) {
			return Items.PIG_SPAWN_EGG;
		}
		if (kind == EntityTypes.GOAT) {
			return Items.GOAT_SPAWN_EGG;
		}
		if (kind == EntityTypes.RABBIT) {
			return Items.RABBIT_SPAWN_EGG;
		}
		return Items.MOOSHROOM_SPAWN_EGG;
	}

	/** Within {@code radius} blocks of the spot (sideways) and not far above or below it. */
	public static boolean inArea(Entity entity, BlockPos center, int radius) {
		double dx = entity.getX() - (center.getX() + 0.5D);
		double dz = entity.getZ() - (center.getZ() + 0.5D);
		return dx * dx + dz * dz <= (double) radius * radius && Math.abs(entity.getY() - center.getY()) <= HEIGHT;
	}

	/** The farm animals in the area, by kind. */
	public static Map<EntityType<?>, List<Animal>> around(ServerLevel level, BlockPos center, int radius) {
		Map<EntityType<?>, List<Animal>> herds = new HashMap<>();
		AABB area = new AABB(center).inflate(radius, HEIGHT, radius);
		for (Animal animal : level.getEntitiesOfClass(Animal.class, area,
				animal -> animal.isAlive() && isLivestock(animal) && inArea(animal, center, radius))) {
			herds.computeIfAbsent(animal.getType(), kind -> new ArrayList<>()).add(animal);
		}
		return herds;
	}

	private Livestock() {
	}
}
