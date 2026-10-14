package dev.butterbot.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The skill tree: four branches of four skills. Every level (and every second job mastery rank) gives
 * a skill point. A skill row opens at a certain bot level, and a skill can only be learned once the
 * skill above it in the same branch has at least one rank, so each bot grows into a specialist.
 */
public enum BotSkill {
	// Mining
	QUICK_DIG(Branch.MINING, 0, 5, Items.IRON_PICKAXE),
	ORE_SENSE(Branch.MINING, 1, 3, Items.SPYGLASS),
	BIG_VEIN(Branch.MINING, 2, 3, Items.RAW_IRON),
	LUCKY(Branch.MINING, 3, 2, Items.EMERALD),
	// Combat
	STRENGTH(Branch.COMBAT, 0, 5, Items.IRON_SWORD),
	TOUGH(Branch.COMBAT, 1, 5, Items.GOLDEN_APPLE),
	ARMOR(Branch.COMBAT, 2, 3, Items.IRON_CHESTPLATE),
	SWEEP(Branch.COMBAT, 3, 2, Items.DIAMOND_SWORD),
	// Farming and handiwork
	GREEN_THUMB(Branch.FARMING, 0, 3, Items.WHEAT),
	FAST_SMELT(Branch.FARMING, 1, 3, Items.FURNACE),
	WIDE_FARM(Branch.FARMING, 2, 2, Items.IRON_HOE),
	POCKETS(Branch.FARMING, 3, 2, Items.BUNDLE),
	// Moving around
	SWIFT(Branch.MOVEMENT, 0, 3, Items.SUGAR),
	MAGNET(Branch.MOVEMENT, 1, 3, Items.HOPPER),
	SWIMMER(Branch.MOVEMENT, 2, 1, Items.HEART_OF_THE_SEA),
	FEATHER(Branch.MOVEMENT, 3, 1, Items.FEATHER);

	public enum Branch {
		MINING(Items.DIAMOND_PICKAXE), COMBAT(Items.DIAMOND_SWORD), FARMING(Items.WHEAT), MOVEMENT(Items.FEATHER);

		private final Item icon;

		Branch(Item icon) {
			this.icon = icon;
		}

		public Item icon() {
			return icon;
		}

		public Component displayName() {
			return Component.translatable("butterbot.skill.branch." + name().toLowerCase(Locale.ROOT));
		}
	}

	/** The bot level each row of the tree opens at. */
	public static final int[] ROW_LEVEL = {1, 8, 16, 24};

	private final Branch branch;
	private final int row;
	private final int maxRank;
	private final Item icon;

	BotSkill(Branch branch, int row, int maxRank, Item icon) {
		this.branch = branch;
		this.row = row;
		this.maxRank = maxRank;
		this.icon = icon;
	}

	public Branch branch() {
		return branch;
	}

	public int row() {
		return row;
	}

	public int maxRank() {
		return maxRank;
	}

	public Item icon() {
		return icon;
	}

	public int requiredLevel() {
		return ROW_LEVEL[row];
	}

	/** The skill above this one in its branch (it needs a rank first), or null for the top row. */
	@Nullable
	public BotSkill previous() {
		if (row == 0) {
			return null;
		}
		for (BotSkill skill : values()) {
			if (skill.branch == branch && skill.row == row - 1) {
				return skill;
			}
		}
		return null;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Component displayName() {
		return Component.translatable("butterbot.skill." + id());
	}

	/** What the skill does at {@code rank} (0 = not learned), for tooltips and chat. */
	public Component effect(int rank) {
		String key = "butterbot.skill." + id() + ".effect";
		return switch (this) {
			case QUICK_DIG -> Component.translatable(key, rank * 10);
			case ORE_SENSE -> Component.translatable(key, BotStats.BASE_SCAN_RADIUS + rank * 4);
			case BIG_VEIN -> Component.translatable(key, rank * 16);
			case LUCKY -> Component.translatable(key, rank);
			case STRENGTH -> Component.translatable(key, String.format(Locale.ROOT, "%.1f", rank * 1.5D));
			case TOUGH -> Component.translatable(key, rank * 6);
			case ARMOR -> Component.translatable(key, rank * 4);
			case SWEEP -> Component.translatable(key, rank == 0 ? 0 : rank == 1 ? 40 : 70);
			case GREEN_THUMB -> Component.translatable(key, rank * 15);
			case FAST_SMELT -> Component.translatable(key, rank);
			case WIDE_FARM -> Component.translatable(key, rank * 4);
			case POCKETS -> Component.translatable(key, rank * 9);
			case SWIFT -> Component.translatable(key, rank * 8);
			case MAGNET -> Component.translatable(key, rank * 2);
			case SWIMMER, FEATHER -> Component.translatable(key);
		};
	}

	@Nullable
	public static BotSkill byOrdinal(int ordinal) {
		BotSkill[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
	}

	@Nullable
	public static BotSkill byId(String id) {
		for (BotSkill skill : values()) {
			if (skill.id().equalsIgnoreCase(id)) {
				return skill;
			}
		}
		return null;
	}

	/** The skill in {@code branch} at {@code row}. */
	public static BotSkill at(Branch branch, int row) {
		for (BotSkill skill : values()) {
			if (skill.branch == branch && skill.row == row) {
				return skill;
			}
		}
		throw new IllegalArgumentException(branch + " row " + row);
	}
}
