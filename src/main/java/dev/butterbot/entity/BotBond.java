package dev.butterbot.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Bond (kedekatan) between a bot and its owner: 0 to 100, grown by head pats, treats, levels and time spent
 * together, lowered by sneak-hitting it. It never decays by itself. Every bot also has one favourite treat,
 * picked from its UUID when it is first made and kept from then on (a packed bot gets a new UUID when placed).
 */
public final class BotBond {

	public static final int MIN = 0;
	public static final int MAX = 100;
	/** Where new bots (and bots from older versions) start: already friendly. */
	public static final int DEFAULT = 20;
	/** Lowest bond of each tier: shy, friendly, fond, soulmate. */
	private static final int[] TIER_MIN = {0, 20, 50, 80};
	public static final int FOND = TIER_MIN[2];
	public static final int SOULMATE = TIER_MIN[3];
	/** Pink used for hearts and the bond meter. */
	public static final int COLOR = 0xFFFF7FB0;

	/** The treats one of them can be a bot's favourite, by index. */
	private static final Item[] FAVORITES = {
			Items.COOKIE, Items.COOKED_COD, Items.COOKED_SALMON, Items.SWEET_BERRIES, Items.PUMPKIN_PIE, Items.GLOW_BERRIES
	};

	private BotBond() {
	}

	public static int clamp(int bond) {
		return Math.max(MIN, Math.min(MAX, bond));
	}

	/** 0 shy (Malu-malu), 1 friendly (Akrab), 2 fond (Sayang), 3 soulmate (Belahan Jiwa). */
	public static int tier(int bond) {
		int tier = 0;
		for (int i = 1; i < TIER_MIN.length; i++) {
			if (bond >= TIER_MIN[i]) {
				tier = i;
			}
		}
		return tier;
	}

	public static Component tierName(int bond) {
		return Component.translatable("butterbot.bond.tier." + tier(bond));
	}

	/** The favourite treat for a bot that has none yet, from its UUID. */
	public static int favoriteFor(UUID id) {
		return Math.floorMod((int) (id.getLeastSignificantBits() >>> 16), FAVORITES.length);
	}

	public static boolean isValidFavorite(int index) {
		return index >= 0 && index < FAVORITES.length;
	}

	public static Item favoriteItem(int index) {
		return FAVORITES[Math.floorMod(index, FAVORITES.length)];
	}

	/** Fish (except pufferfish), cookies, pumpkin pie, berries and cake. */
	public static boolean isTreat(ItemStack stack) {
		if (stack.isEmpty() || isPuffer(stack)) {
			return false;
		}
		return stack.is(ItemTags.FISHES) || stack.is(Items.COOKIE) || stack.is(Items.PUMPKIN_PIE) || stack.is(Items.SWEET_BERRIES)
				|| stack.is(Items.GLOW_BERRIES) || stack.is(Items.CAKE);
	}

	/** Pufferfish is in the fish tag, but no cat wants one. */
	public static boolean isPuffer(ItemStack stack) {
		return stack.is(Items.PUFFERFISH);
	}

	/** Bond for a treat (before the favourite bonus): fish is a cat's best treat. */
	public static int treatGain(ItemStack stack) {
		return stack.is(ItemTags.FISHES) ? 4 : 3;
	}

	public static boolean isFavorite(ItemStack stack, int favorite) {
		return isValidFavorite(favorite) && stack.is(FAVORITES[favorite]);
	}
}
