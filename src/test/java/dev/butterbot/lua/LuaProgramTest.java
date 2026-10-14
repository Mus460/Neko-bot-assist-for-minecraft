package dev.butterbot.lua;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaProgramTest {

	/** Simulates the server thread: answers bot calls until the program ends. */
	private static List<String> run(LuaProgram program, long timeoutMs) throws InterruptedException {
		List<String> calls = new ArrayList<>();
		program.start();
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!program.isFinished()) {
			if (System.currentTimeMillis() > deadline) {
				program.shutdown();
				throw new AssertionError("program did not finish in time");
			}
			LuaProgram.Command command = program.pending();
			if (command != null) {
				calls.add(command.name);
				switch (command.name) {
					case "pos" -> command.complete(10, 64, -5);
					case "say" -> command.complete(true);
					case "count" -> command.complete(7);
					default -> command.complete(false);
				}
			}
			Thread.sleep(1);
		}
		return calls;
	}

	@Test
	void botCallsRoundTrip() throws InterruptedException {
		LuaProgram program = new LuaProgram("""
				local x, y, z = bot.pos()
				if x ~= 10 or y ~= 64 or z ~= -5 then error("bad pos") end
				if bot.count("dirt") ~= 7 then error("bad count") end
				bot.say("done " .. x)
				""", "test");
		List<String> calls = run(program, 5000);
		assertNull(program.getError(), () -> "unexpected error: " + program.getError());
		assertEquals(List.of("pos", "count", "say"), calls);
	}

	@Test
	void dangerousLibrariesAreRemoved() throws InterruptedException {
		LuaProgram program = new LuaProgram("""
				if os ~= nil or io ~= nil or luajava ~= nil or require ~= nil or debug ~= nil or coroutine ~= nil then
				  error("sandbox leak")
				end
				""", "sandbox");
		run(program, 5000);
		assertNull(program.getError(), () -> "unexpected error: " + program.getError());
	}

	@Test
	void runawayLoopIsAborted() throws InterruptedException {
		LuaProgram program = new LuaProgram("while true do end", "loop");
		run(program, 30000);
		assertNotNull(program.getError());
		assertTrue(program.getError().contains("too busy"), program.getError());
	}

	@Test
	void hugeStringRepIsRefused() throws InterruptedException {
		LuaProgram program = new LuaProgram("local s = string.rep('x', 100000000)", "rep");
		run(program, 5000);
		assertNotNull(program.getError());
		assertTrue(program.getError().contains("too large"), program.getError());
	}

	@Test
	void syntaxErrorsAreReported() throws InterruptedException {
		LuaProgram program = new LuaProgram("bot.say(", "syntax");
		run(program, 5000);
		assertNotNull(program.getError());
	}

	@Test
	void shutdownStopsABlockedProgram() throws InterruptedException {
		LuaProgram program = new LuaProgram("bot.sleep(100)", "sleep");
		program.start();
		long deadline = System.currentTimeMillis() + 5000;
		while (program.pending() == null && System.currentTimeMillis() < deadline) {
			Thread.sleep(1);
		}
		assertNotNull(program.pending());
		program.shutdown();
		deadline = System.currentTimeMillis() + 5000;
		while (!program.isFinished() && System.currentTimeMillis() < deadline) {
			Thread.sleep(1);
		}
		assertTrue(program.isFinished());
		assertNull(program.getError(), "a stopped program should not report an error");
	}
}
