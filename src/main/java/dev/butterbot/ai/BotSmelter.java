package dev.butterbot.ai;

import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.SmeltMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bot's built-in furnace: while it carries coal (or other fuel) it smelts what it carries,
 * so raw ores come home as ingots. Furnace experience is kept for the owner.
 */
public final class BotSmelter {

	/** How many items one piece of fuel smelts, like a vanilla furnace. */
	private static final Map<Item, Float> FUEL = Map.of(
			Items.COAL, 8.0F,
			Items.CHARCOAL, 8.0F,
			Items.COAL_BLOCK, 80.0F,
			Items.BLAZE_ROD, 12.0F,
			Items.DRIED_KELP_BLOCK, 20.0F);
	private static final Set<Item> ORES = Set.of(
			Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER, Items.ANCIENT_DEBRIS,
			Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE,
			Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE, Items.NETHER_GOLD_ORE);

	private record Smelt(ItemStack result, float experience) {
	}

	private static final Smelt NONE = new Smelt(ItemStack.EMPTY, 0.0F);

	private final ButterBotEntity bot;
	private final Map<Item, Smelt> recipes = new HashMap<>();
	/** Items the fuel already burnt can still smelt. */
	private float heat;
	private float experience;

	public BotSmelter(ButterBotEntity bot) {
		this.bot = bot;
	}

	/** Smelts a few items from the backpack. Called every half second. */
	public void tick(SmeltMode mode) {
		if (!(bot.level() instanceof ServerLevel level)) {
			return;
		}
		SimpleContainer backpack = bot.getInventory();
		int smelted = 0;
		for (int round = 0; round < BotLevels.smeltPerCycle(bot.getBotLevel()); round++) {
			int slot = findInput(level, backpack, mode);
			if (slot < 0) {
				break;
			}
			if (heat < 1.0F && !burnFuel(backpack, backpack.getItem(slot).getItem())) {
				break;
			}
			ItemStack input = backpack.getItem(slot);
			Smelt smelt = recipeFor(level, input);
			ItemStack result = smelt.result().copy();
			input.shrink(1);
			backpack.setItem(slot, input);
			if (!backpack.canAddItem(result)) {
				input.grow(1);
				backpack.setItem(slot, input);
				break;
			}
			backpack.addItem(result);
			heat -= 1.0F;
			experience += smelt.experience();
			smelted++;
		}
		if (smelted == 0) {
			return;
		}
		int wholeXp = (int) experience;
		if (wholeXp > 0) {
			experience -= wholeXp;
			bot.addStoredXp(wholeXp);
		}
		bot.gainWorkXp(smelted);
		level.sendParticles(ParticleTypes.SMOKE, bot.getX(), bot.getY() + 1.2D, bot.getZ(), 3, 0.15D, 0.1D, 0.15D, 0.01D);
		if (bot.getRandom().nextInt(6) == 0) {
			level.playSound(null, bot.blockPosition(), SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.NEUTRAL, 0.4F, 1.0F);
		}
	}

	private int findInput(ServerLevel level, SimpleContainer backpack, SmeltMode mode) {
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			if (stack.isEmpty() || FUEL.containsKey(stack.getItem()) || stack.has(DataComponents.MAX_DAMAGE)) {
				continue;
			}
			Smelt smelt = recipeFor(level, stack);
			if (smelt.result().isEmpty()) {
				continue;
			}
			boolean ore = ORES.contains(stack.getItem());
			boolean cooking = stack.has(DataComponents.FOOD) && smelt.result().has(DataComponents.FOOD);
			if (mode == SmeltMode.ALL || ore || cooking) {
				return i;
			}
		}
		return -1;
	}

	/** Burns one piece of fuel, never the item that is about to be smelted. */
	private boolean burnFuel(SimpleContainer backpack, Item smelting) {
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			Float value = FUEL.get(stack.getItem());
			if (value == null || stack.is(smelting)) {
				continue;
			}
			stack.shrink(1);
			backpack.setItem(i, stack);
			heat += value;
			return true;
		}
		return false;
	}

	private Smelt recipeFor(ServerLevel level, ItemStack stack) {
		return recipes.computeIfAbsent(stack.getItem(), item -> {
			SingleRecipeInput input = new SingleRecipeInput(new ItemStack(item));
			Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, input, level);
			if (recipe.isEmpty()) {
				return NONE;
			}
			ItemStack result = recipe.get().value().assemble(input);
			return result.isEmpty() ? NONE : new Smelt(result, recipe.get().value().experience());
		});
	}
}
