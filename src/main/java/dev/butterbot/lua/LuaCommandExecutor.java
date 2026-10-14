package dev.butterbot.lua;

import dev.butterbot.ai.MiningController;
import dev.butterbot.block.BotBaseBlockEntity;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Executes {@link LuaProgram.Command}s on the server thread, one tick at a time. */
public final class LuaCommandExecutor {

	private final ButterBotEntity bot;

	public LuaCommandExecutor(ButterBotEntity bot) {
		this.bot = bot;
	}

	public void advance(LuaProgram.Command command) {
		command.ticks++;
		switch (command.name) {
			case "say" -> {
				bot.say(command.stringArg(0));
				command.complete(true);
			}
			case "pos" -> {
				BlockPos pos = bot.blockPosition();
				command.complete(pos.getX(), pos.getY(), pos.getZ());
			}
			case "sleep" -> {
				int needed = (int) Math.round(command.doubleArg(0) * 20.0D);
				if (command.ticks >= Math.max(1, needed)) {
					command.complete(true);
				}
			}
			case "goto" -> advanceGoTo(command, new BlockPos(command.intArg(0), command.intArg(1), command.intArg(2)), 2400);
			case "dig" -> advanceDig(command);
			case "place" -> advancePlace(command);
			case "block" -> {
				BlockPos pos = new BlockPos(command.intArg(0), command.intArg(1), command.intArg(2));
				if (!bot.level().isLoaded(pos)) {
					command.complete((Object) null);
				} else {
					BlockState state = bot.level().getBlockState(pos);
					command.complete(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
				}
			}
			case "scan" -> advanceScan(command);
			case "deliver" -> advanceDeliver(command);
			case "deposit" -> advanceDeposit(command);
			case "home" -> {
				BlockPos base = bot.getBasePos();
				if (base == null) {
					command.complete(false);
				} else {
					advanceGoTo(command, base, 6000);
				}
			}
			case "inventory" -> command.complete(bot.countInventoryItems());
			case "count" -> {
				Item item = parseItem(command.stringArg(0));
				command.complete(item == null ? 0 : bot.countItem(item));
			}
			case "ownerpos" -> {
				Player owner = bot.getOwner();
				if (owner == null) {
					command.complete((Object) null);
				} else {
					BlockPos pos = owner.blockPosition();
					command.complete(pos.getX(), pos.getY(), pos.getZ());
				}
			}
			default -> command.complete(false);
		}
	}

	private void advanceGoTo(LuaProgram.Command command, BlockPos target, int maxTicks) {
		if (bot.position().distanceToSqr(Vec3.atBottomCenterOf(target)) <= 2.6D) {
			bot.getNavigation().stop();
			command.complete(true);
			return;
		}
		if (!command.started || command.ticks % 20 == 0) {
			command.started = true;
			bot.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
		}
		if (command.ticks % 20 == 0) {
			if (!Double.isNaN(command.lastX)
					&& bot.position().distanceToSqr(command.lastX, command.lastY, command.lastZ) < 0.04D) {
				command.stuckTicks += 20;
			} else {
				command.stuckTicks = 0;
			}
			command.lastX = bot.getX();
			command.lastY = bot.getY();
			command.lastZ = bot.getZ();
		}
		if (command.stuckTicks > 200 || command.ticks > maxTicks) {
			bot.getNavigation().stop();
			command.complete(false);
		}
	}

	private void advanceDig(LuaProgram.Command command) {
		BlockPos target = new BlockPos(command.intArg(0), command.intArg(1), command.intArg(2));
		Level level = bot.level();
		BlockState state = level.getBlockState(target);
		if (state.isAir()) {
			command.complete(true);
			return;
		}
		if (bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > 6.0D * 6.0D) {
			command.complete(false);
			return;
		}
		MiningController miner = bot.getMiner();
		if (!command.started) {
			command.started = true;
			bot.getNavigation().stop();
			if (!bot.makeDigSafe(target)) {
				bot.warnLava();
				command.complete(false);
				return;
			}
			if (!miner.beginDig(target)) {
				command.complete(false);
			}
			return;
		}
		if (miner.tickDig()) {
			command.complete(true);
		} else if (!miner.isDigging()) {
			command.complete(level.getBlockState(target).isAir());
		}
	}

	private void advancePlace(LuaProgram.Command command) {
		BlockPos target = new BlockPos(command.intArg(0), command.intArg(1), command.intArg(2));
		Item item = parseItem(command.stringArg(3));
		if (item == null || bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > 5.0D * 5.0D) {
			command.complete(false);
			return;
		}
		command.complete(bot.placeBlockFromInventory(target, item));
	}

	private void advanceScan(LuaProgram.Command command) {
		String query = command.stringArg(0).toLowerCase(Locale.ROOT);
		int radius = Mth.clamp(command.intArg(1), 1, 24);
		if (query.contains(":")) {
			query = query.substring(query.indexOf(':') + 1);
		}
		Level level = bot.level();
		BlockPos origin = bot.blockPosition();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		BlockPos closest = null;
		double closestDistance = Double.MAX_VALUE;

		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					cursor.setWithOffset(origin, x, y, z);
					if (!level.isLoaded(cursor)) {
						continue;
					}
					BlockState state = level.getBlockState(cursor);
					if (state.isAir() || state.is(ModBlocks.BOT_BASE)) {
						continue;
					}
					Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
					if (!key.getPath().contains(query)) {
						continue;
					}
					double distance = origin.distSqr(cursor);
					if (distance < closestDistance) {
						closestDistance = distance;
						closest = cursor.immutable();
					}
				}
			}
		}
		if (closest == null) {
			command.complete((Object) null);
		} else {
			command.complete(closest.getX(), closest.getY(), closest.getZ());
		}
	}

	private void advanceDeliver(LuaProgram.Command command) {
		Player owner = bot.getOwner();
		if (owner == null) {
			command.complete(false);
			return;
		}
		if (bot.distanceToSqr(owner) > 6.25D) {
			if (!command.started || command.ticks % 20 == 0) {
				command.started = true;
				bot.getNavigation().moveTo(owner, 1.1D);
			}
			if (command.ticks > 1200) {
				bot.getNavigation().stop();
				command.complete(false);
			}
			return;
		}
		bot.getNavigation().stop();
		bot.deliverInventoryTo(owner);
		command.complete(true);
	}

	private void advanceDeposit(LuaProgram.Command command) {
		BotBaseBlockEntity base = bot.getBase();
		if (base == null) {
			command.complete(false);
			return;
		}
		BlockPos pos = base.getBlockPos();
		if (bot.distanceToSqr(Vec3.atCenterOf(pos)) > 2.8D * 2.8D) {
			advanceGoToNoComplete(command, pos);
			if (command.ticks > 6000) {
				bot.getNavigation().stop();
				command.complete(false);
			}
			return;
		}
		bot.getNavigation().stop();
		boolean empty = bot.depositInto(base);
		command.complete(empty);
	}

	private void advanceGoToNoComplete(LuaProgram.Command command, BlockPos target) {
		if (!command.started || command.ticks % 20 == 0) {
			command.started = true;
			bot.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
		}
	}

	@Nullable
	private static Item parseItem(String name) {
		String id = name.contains(":") ? name : "minecraft:" + name;
		Identifier identifier = Identifier.tryParse(id.toLowerCase(Locale.ROOT));
		return identifier == null ? null : BuiltInRegistries.ITEM.getOptional(identifier).orElse(null);
	}
}
