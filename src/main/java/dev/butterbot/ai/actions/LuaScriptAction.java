package dev.butterbot.ai.actions;

import dev.butterbot.ai.BotAction;
import dev.butterbot.entity.BotState;
import dev.butterbot.entity.BotTask;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.lua.LuaCommandExecutor;
import dev.butterbot.lua.LuaProgram;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Runs the bot's Lua program. Highest priority: a running program overrides every other behaviour. */
public class LuaScriptAction implements BotAction {

	@Nullable
	private LuaProgram program;
	@Nullable
	private LuaCommandExecutor executor;

	@Override
	public int getPriority(ButterBotEntity bot) {
		return 95;
	}

	@Override
	public boolean canRun(ButterBotEntity bot) {
		return bot.getTask() == BotTask.LUA && !bot.getLuaScript().isBlank();
	}

	@Override
	public void start(ButterBotEntity bot) {
		bot.setBotState(BotState.SEARCHING);
		try {
			program = new LuaProgram(bot.getLuaScript(), "bot_" + bot.getId());
			executor = new LuaCommandExecutor(bot);
			program.start();
		} catch (Throwable throwable) {
			program = null;
			executor = null;
			bot.tellOwner(Component.translatable("butterbot.msg.lua_engine_error", String.valueOf(throwable)).withStyle(ChatFormatting.RED));
			bot.clearTask();
		}
	}

	@Override
	public void tick(ButterBotEntity bot) {
		if (program == null || executor == null) {
			return;
		}
		try {
			LuaProgram.Command command = program.pending();
			if (command != null) {
				executor.advance(command);
				return;
			}
		} catch (Throwable throwable) {
			program.shutdown();
			program = null;
			bot.tellOwner(Component.translatable("butterbot.msg.lua_engine_error", String.valueOf(throwable)).withStyle(ChatFormatting.RED));
			bot.clearTask();
			return;
		}

		if (program.isFinished()) {
			String error = program.getError();
			if (error != null) {
				bot.tellOwner(Component.translatable("butterbot.msg.lua_error", error).withStyle(ChatFormatting.RED));
			} else {
				bot.say(Component.translatable("butterbot.msg.program_finished"));
				bot.playTaskCompleteSound();
			}
			program = null;
			bot.clearTask();
		}
	}

	@Override
	public boolean isFinished(ButterBotEntity bot) {
		return bot.getTask() != BotTask.LUA;
	}

	@Override
	public void stop(ButterBotEntity bot) {
		if (program != null) {
			program.shutdown();
			program = null;
		}
		executor = null;
		bot.getNavigation().stop();
		bot.getMiner().cancel();
	}
}
