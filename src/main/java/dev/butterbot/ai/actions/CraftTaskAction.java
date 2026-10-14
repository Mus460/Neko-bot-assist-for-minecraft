package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.ai.Walker;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Crafts an item from ingredients found in nearby chests, barrels and Bot Base storage:
 * plan recipes, look inside containers, collect exactly what is needed, craft, deliver.
 */
public class CraftTaskAction implements BotAction {

	private static final int CONTAINER_SCAN_RADIUS = 64;
	private static final double CHEST_REACH_SQ = 2.6D * 2.6D;
	private static final int CHECK_PAUSE_TICKS = 12;
	private static final int MAX_STUCK_TICKS = 300;

	private enum Phase {PLAN, CHECK, COLLECT, CRAFT}

	private record Plan(CraftingRecipe recipe, List<Ingredient> ingredients, int resultPerCraft) {
	}

	private record Withdrawal(BlockPos pos, Map<Item, Integer> items) {
	}

	private final Walker walker = new Walker();
	private Phase phase = Phase.PLAN;
	private final List<Plan> plans = new ArrayList<>();
	private final List<BlockPos> toCheck = new ArrayList<>();
	private final Map<BlockPos, Map<Item, Integer>> checkedContents = new LinkedHashMap<>();
	private final Map<Item, Integer> knownStock = new HashMap<>();
	private final List<Withdrawal> withdrawals = new ArrayList<>();
	private Map<Item, Integer> requiredItems = new LinkedHashMap<>();
	private int crafts;
	private int resultPerCraft = 1;
	private int checkPause;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 55;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.CRAFT && bot.getCraftItem() != null && !bot.isReturnRequested();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		phase = Phase.PLAN;
		plans.clear();
		toCheck.clear();
		checkedContents.clear();
		knownStock.clear();
		withdrawals.clear();
		requiredItems = new LinkedHashMap<>();
		crafts = 0;
		checkPause = 0;
		walker.reset(bot);
	}

	@Override
	public void tick(ButterBotEntity bot) {
		switch (phase) {
			case PLAN -> buildPlan(bot);
			case CHECK -> checkTick(bot);
			case COLLECT -> collectTick(bot);
			case CRAFT -> craftNow(bot);
		}
	}

	// ---------------------------------------------------------------- planning

	private void buildPlan(ButterBotEntity bot) {
		ServerLevel level = (ServerLevel) bot.level();
		Item target = bot.getCraftItem();
		if (target == null) {
			return;
		}

		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe)) {
				continue;
			}
			if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
				continue;
			}
			ItemStack result = previewResult(recipe);
			if (!result.isEmpty() && result.is(target)) {
				plans.add(new Plan(recipe, recipe.placementInfo().ingredients(), Math.max(1, result.getCount())));
			}
		}

		if (plans.isEmpty()) {
			bot.tellOwner(Component.translatable("butterbot.msg.no_recipe", new ItemStack(target).getHoverName()));
			bot.clearTask();
			return;
		}

		toCheck.addAll(findContainerPositions(level, bot));
		countContainer(bot.getInventory(), knownStock);

		if (trySatisfy(bot)) {
			return;
		}
		if (toCheck.isEmpty()) {
			reportMissing(bot);
			return;
		}
		bot.say(Component.translatable("butterbot.msg.checking_containers", toCheck.size(), CONTAINER_SCAN_RADIUS));
		bot.setBotState(BotState.MOVING_TO_TARGET);
		phase = Phase.CHECK;
	}

	/** Builds a sample crafting grid from the first option of every ingredient to learn the result. */
	private static ItemStack previewResult(CraftingRecipe recipe) {
		try {
			CraftingInput input;
			if (recipe instanceof ShapedRecipe shaped) {
				List<ItemStack> grid = new ArrayList<>();
				for (Optional<Ingredient> slot : shaped.getIngredients()) {
					grid.add(slot.map(CraftTaskAction::firstOption).orElse(ItemStack.EMPTY));
				}
				input = CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid);
			} else {
				List<ItemStack> items = new ArrayList<>();
				for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
					items.add(firstOption(ingredient));
				}
				if (items.isEmpty()) {
					return ItemStack.EMPTY;
				}
				input = CraftingInput.of(items.size(), 1, items);
			}
			return recipe.assemble(input);
		} catch (RuntimeException exception) {
			return ItemStack.EMPTY;
		}
	}

	private static ItemStack firstOption(Ingredient ingredient) {
		return ingredient.items().findFirst().map(holder -> new ItemStack(holder.value())).orElse(ItemStack.EMPTY);
	}

	private static List<BlockPos> findContainerPositions(ServerLevel level, ButterBotEntity bot) {
		List<BlockPos> positions = new ArrayList<>();
		BlockPos center = bot.blockPosition();
		ChunkPos centerChunk = ChunkPos.containing(center);
		int chunkRadius = (CONTAINER_SCAN_RADIUS >> 4) + 1;
		double maxDistSq = (double) CONTAINER_SCAN_RADIUS * CONTAINER_SCAN_RADIUS;

		for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
			for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunk.x() + cx, centerChunk.z() + cz);
				if (chunk == null) {
					continue;
				}
				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					BlockEntity be = entry.getValue();
					if ((be instanceof RandomizableContainerBlockEntity || be instanceof BotBaseBlockEntity)
							&& entry.getKey().distSqr(center) <= maxDistSq) {
						positions.add(entry.getKey().immutable());
					}
				}
			}
		}
		positions.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
		return positions;
	}

	@Nullable
	private static Container containerAt(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof BotBaseBlockEntity base) {
			return base.getStorage();
		}
		if (be instanceof RandomizableContainerBlockEntity container) {
			return container;
		}
		return null;
	}

	// ---------------------------------------------------------------- checking containers

	private void checkTick(ButterBotEntity bot) {
		if (toCheck.isEmpty()) {
			reportMissing(bot);
			return;
		}
		BlockPos current = toCheck.getFirst();
		ServerLevel level = (ServerLevel) bot.level();
		Container container = containerAt(level, current);
		if (container == null) {
			toCheck.removeFirst();
			return;
		}

		if (bot.distanceToSqr(Vec3.atCenterOf(current)) > CHEST_REACH_SQ) {
			if (!walker.walk(bot, Vec3.atBottomCenterOf(current), 1.0D, false, MAX_STUCK_TICKS)) {
				toCheck.removeFirst();
				walker.reset(bot);
			}
			return;
		}

		bot.getNavigation().stop();
		bot.getLookControl().setLookAt(Vec3.atCenterOf(current));
		if (checkPause == 0) {
			checkPause = CHECK_PAUSE_TICKS;
			level.playSound(null, current, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
			return;
		}
		if (--checkPause > 0) {
			return;
		}

		Map<Item, Integer> contents = new LinkedHashMap<>();
		countContainer(container, contents);
		checkedContents.put(current, contents);
		contents.forEach((item, amount) -> knownStock.merge(item, amount, Integer::sum));
		level.playSound(null, current, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
		toCheck.removeFirst();
		walker.reset(bot);
		trySatisfy(bot);
	}

	private boolean trySatisfy(ButterBotEntity bot) {
		for (Plan plan : plans) {
			if (!computeMissing(plan, bot.getCraftCount(), knownStock).isEmpty()) {
				continue;
			}
			resultPerCraft = plan.resultPerCraft();
			crafts = (bot.getCraftCount() + resultPerCraft - 1) / resultPerCraft;
			requiredItems = allocateIngredients(plan, crafts, knownStock);

			int freeSlots = freeSlots(bot.getInventory());
			while (crafts > 1 && stacksNeeded(requiredItems) > freeSlots) {
				crafts--;
				requiredItems = allocateIngredients(plan, crafts, knownStock);
			}
			if (crafts * resultPerCraft < bot.getCraftCount()) {
				bot.say(Component.translatable("butterbot.msg.backpack_limits_craft", crafts * resultPerCraft,
						new ItemStack(bot.getCraftItem()).getHoverName()));
			}

			planWithdrawals(bot, requiredItems);
			bot.say(Component.translatable("butterbot.msg.found_ingredients", checkedContents.size()));
			bot.setBotState(BotState.MOVING_TO_TARGET);
			phase = Phase.COLLECT;
			walker.reset(bot);
			return true;
		}
		return false;
	}

	private void reportMissing(ButterBotEntity bot) {
		List<MutableComponent> best = null;
		for (Plan plan : plans) {
			List<MutableComponent> missing = computeMissing(plan, bot.getCraftCount(), knownStock);
			if (best == null || missing.size() < best.size()) {
				best = missing;
			}
		}
		MutableComponent list = Component.empty();
		if (best != null) {
			for (int i = 0; i < best.size(); i++) {
				if (i > 0) {
					list.append(", ");
				}
				list.append(best.get(i));
			}
		}
		bot.tellOwner(Component.translatable("butterbot.msg.missing_ingredients", checkedContents.size(),
				bot.getCraftCount(), new ItemStack(bot.getCraftItem()).getHoverName(), list));
		bot.clearTask();
	}

	private static void countContainer(Container container, Map<Item, Integer> stock) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty()) {
				stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
	}

	private static List<MutableComponent> computeMissing(Plan plan, int wantedCount, Map<Item, Integer> stock) {
		int neededCrafts = (wantedCount + plan.resultPerCraft() - 1) / plan.resultPerCraft();
		Map<Item, Integer> remaining = new HashMap<>(stock);
		List<MutableComponent> missing = new ArrayList<>();

		for (Ingredient ingredient : plan.ingredients()) {
			int need = neededCrafts;
			for (Holder<Item> option : ingredient.items().toList()) {
				int available = remaining.getOrDefault(option.value(), 0);
				int take = Math.min(available, need);
				if (take > 0) {
					remaining.merge(option.value(), -take, Integer::sum);
					need -= take;
				}
				if (need == 0) {
					break;
				}
			}
			if (need > 0) {
				ItemStack example = firstOption(ingredient);
				missing.add(Component.literal(need + "x ").append(example.isEmpty() ? Component.literal("?") : example.getHoverName()));
			}
		}
		return missing;
	}

	private static Map<Item, Integer> allocateIngredients(Plan plan, int craftCount, Map<Item, Integer> stock) {
		Map<Item, Integer> remaining = new HashMap<>(stock);
		Map<Item, Integer> required = new LinkedHashMap<>();
		for (Ingredient ingredient : plan.ingredients()) {
			int need = craftCount;
			for (Holder<Item> option : ingredient.items().toList()) {
				int available = remaining.getOrDefault(option.value(), 0);
				int take = Math.min(available, need);
				if (take > 0) {
					remaining.merge(option.value(), -take, Integer::sum);
					required.merge(option.value(), take, Integer::sum);
					need -= take;
				}
				if (need == 0) {
					break;
				}
			}
		}
		return required;
	}

	private static int stacksNeeded(Map<Item, Integer> required) {
		int stacks = 0;
		for (Map.Entry<Item, Integer> entry : required.entrySet()) {
			int maxSize = new ItemStack(entry.getKey()).getMaxStackSize();
			stacks += (entry.getValue() + maxSize - 1) / maxSize;
		}
		return stacks;
	}

	private static int freeSlots(SimpleContainer container) {
		int free = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (container.getItem(i).isEmpty()) {
				free++;
			}
		}
		return Math.max(1, free - 1);
	}

	private void planWithdrawals(ButterBotEntity bot, Map<Item, Integer> required) {
		withdrawals.clear();
		Map<Item, Integer> toGather = new HashMap<>(required);
		SimpleContainer backpack = bot.getInventory();
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack stack = backpack.getItem(i);
			Integer needed = toGather.get(stack.getItem());
			if (needed != null && needed > 0) {
				toGather.put(stack.getItem(), Math.max(0, needed - stack.getCount()));
			}
		}
		for (Map.Entry<BlockPos, Map<Item, Integer>> checked : checkedContents.entrySet()) {
			Map<Item, Integer> take = new LinkedHashMap<>();
			for (Map.Entry<Item, Integer> entry : toGather.entrySet()) {
				if (entry.getValue() <= 0) {
					continue;
				}
				int amount = Math.min(checked.getValue().getOrDefault(entry.getKey(), 0), entry.getValue());
				if (amount > 0) {
					take.put(entry.getKey(), amount);
				}
			}
			if (!take.isEmpty()) {
				withdrawals.add(new Withdrawal(checked.getKey(), take));
				take.forEach((item, amount) -> toGather.merge(item, -amount, Integer::sum));
			}
		}
	}

	// ---------------------------------------------------------------- collecting and crafting

	private void collectTick(ButterBotEntity bot) {
		if (withdrawals.isEmpty()) {
			phase = Phase.CRAFT;
			return;
		}
		Withdrawal current = withdrawals.getFirst();
		ServerLevel level = (ServerLevel) bot.level();
		Container container = containerAt(level, current.pos());
		if (container == null) {
			bot.say(Component.translatable("butterbot.msg.container_gone", current.pos().toShortString()));
			start(bot);
			return;
		}

		if (bot.distanceToSqr(Vec3.atCenterOf(current.pos())) > CHEST_REACH_SQ) {
			if (!walker.walk(bot, Vec3.atBottomCenterOf(current.pos()), 1.0D, false, MAX_STUCK_TICKS)) {
				bot.tellOwner(Component.translatable("butterbot.msg.container_unreachable", current.pos().toShortString()));
				bot.clearTask();
			}
			return;
		}

		bot.getNavigation().stop();
		level.playSound(null, current.pos(), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
		for (Map.Entry<Item, Integer> entry : current.items().entrySet()) {
			int remaining = entry.getValue();
			for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
				ItemStack slotStack = container.getItem(i);
				if (!slotStack.is(entry.getKey())) {
					continue;
				}
				int take = Math.min(remaining, slotStack.getCount());
				ItemStack leftover = bot.getInventory().addItem(slotStack.copyWithCount(take));
				int taken = take - leftover.getCount();
				slotStack.shrink(taken);
				container.setItem(i, slotStack);
				remaining -= taken;
				if (!leftover.isEmpty()) {
					break;
				}
			}
		}
		container.setChanged();
		withdrawals.removeFirst();
		walker.reset(bot);
	}

	private void craftNow(ButterBotEntity bot) {
		Item target = bot.getCraftItem();
		if (target == null) {
			return;
		}
		SimpleContainer backpack = bot.getInventory();
		for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
			if (bot.countItem(entry.getKey()) < entry.getValue()) {
				bot.tellOwner(Component.translatable("butterbot.msg.short_after_collect", new ItemStack(entry.getKey()).getHoverName()));
				bot.clearTask();
				return;
			}
		}
		for (Map.Entry<Item, Integer> entry : requiredItems.entrySet()) {
			int toRemove = entry.getValue();
			for (int i = 0; i < backpack.getContainerSize() && toRemove > 0; i++) {
				ItemStack stack = backpack.getItem(i);
				if (stack.is(entry.getKey())) {
					int removed = Math.min(toRemove, stack.getCount());
					stack.shrink(removed);
					backpack.setItem(i, stack);
					toRemove -= removed;
				}
			}
		}

		int total = crafts * resultPerCraft;
		int remaining = total;
		int maxSize = new ItemStack(target).getMaxStackSize();
		while (remaining > 0) {
			int batch = Math.min(maxSize, remaining);
			ItemStack leftover = backpack.addItem(new ItemStack(target, batch));
			if (!leftover.isEmpty()) {
				bot.spawnAtLocation((ServerLevel) bot.level(), leftover);
			}
			remaining -= batch;
		}
		bot.level().playSound(null, bot.blockPosition(), SoundEvents.UI_LOOM_TAKE_RESULT, SoundSource.NEUTRAL, 0.8F, 1.0F);
		bot.say(Component.translatable("butterbot.msg.crafted", total, new ItemStack(target).getHoverName()));
		bot.gainWorkXp(crafts);
		bot.setReturnRequested(true);
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.CRAFT || bot.isReturnRequested();
	}

	@Override
	public void stop(ButterBotEntity bot) {
		bot.getNavigation().stop();
	}
}
