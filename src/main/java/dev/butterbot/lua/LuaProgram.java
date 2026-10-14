package dev.butterbot.lua;

import org.jetbrains.annotations.Nullable;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a player-written Lua program on its own daemon thread.
 * <p>
 * Every {@code bot.*} call becomes a {@link Command} that the server thread executes
 * over one or more ticks; the Lua thread blocks until the command completes. This keeps
 * all world access on the server thread while letting scripts be written in a simple,
 * sequential style.
 * <p>
 * Safety: the sandbox removes io/os/luajava/require/package/coroutine/debug, caps string
 * allocations and enforces an instruction budget between bot calls so a runaway loop
 * cannot hang a CPU core forever.
 */
public final class LuaProgram {

	/** Instructions a script may execute between two bot calls before it is aborted. */
	public static final long MAX_INSTRUCTIONS_BETWEEN_CALLS = 20_000_000L;
	public static final int MAX_SCRIPT_LENGTH = 8000;
	private static final int HOOK_INTERVAL = 1_000;
	private static final int MAX_STRING_REP = 65_536;
	private static final int MAX_SAY_LENGTH = 200;

	/** A single bot action requested by the script, executed on the server thread. */
	public static final class Command {
		public final String name;
		public final Object[] args;
		private final CompletableFuture<Object[]> future = new CompletableFuture<>();

		/** Number of server ticks this command has been processed for. */
		public int ticks;
		/** Free-form state for multi-tick commands. */
		public boolean started;
		public int stuckTicks;
		public double lastX = Double.NaN;
		public double lastY = Double.NaN;
		public double lastZ = Double.NaN;

		Command(String name, Object[] args) {
			this.name = name;
			this.args = args;
		}

		public int intArg(int index) {
			return ((Number) args[index]).intValue();
		}

		public double doubleArg(int index) {
			return ((Number) args[index]).doubleValue();
		}

		public String stringArg(int index) {
			return String.valueOf(args[index]);
		}

		/** Completes the command with the given return values. Always returns {@code true}. */
		public boolean complete(Object... result) {
			future.complete(result);
			return true;
		}

		public boolean isDone() {
			return future.isDone();
		}

		void fail(Throwable cause) {
			future.completeExceptionally(cause);
		}
	}

	private final String script;
	private final String chunkName;
	private final AtomicReference<Command> pending = new AtomicReference<>();

	@Nullable
	private Thread thread;
	private volatile boolean finished;
	private volatile boolean stopped;
	@Nullable
	private volatile String errorMessage;
	private volatile long instructionsSinceCall;

	public LuaProgram(String script, String chunkName) {
		this.script = script;
		this.chunkName = chunkName;
	}

	public void start() {
		Thread t = new Thread(this::run, "ButterBot-Lua-" + chunkName);
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		this.thread = t;
		t.start();
	}

	/** The command waiting for the server thread, or {@code null}. */
	@Nullable
	public Command pending() {
		Command command = pending.get();
		return command == null || command.isDone() ? null : command;
	}

	public boolean isFinished() {
		return finished && pending() == null;
	}

	@Nullable
	public String getError() {
		return errorMessage;
	}

	public void shutdown() {
		stopped = true;
		Command command = pending.getAndSet(null);
		if (command != null) {
			command.fail(new InterruptedException("stopped"));
		}
		Thread t = thread;
		if (t != null) {
			t.interrupt();
		}
	}

	private void run() {
		try {
			Globals globals = createSandbox();
			LuaValue chunk = globals.load(script, chunkName);
			chunk.call();
		} catch (LuaError error) {
			if (!stopped) {
				errorMessage = trim(cleanMessage(error.getMessage()));
			}
		} catch (Throwable throwable) {
			if (!stopped) {
				errorMessage = trim(String.valueOf(throwable.getMessage()));
			}
		} finally {
			finished = true;
		}
	}

	private static String cleanMessage(@Nullable String message) {
		if (message == null) {
			return "unknown error";
		}
		return message;
	}

	private static String trim(String message) {
		return message.length() > MAX_SAY_LENGTH ? message.substring(0, MAX_SAY_LENGTH) + "..." : message;
	}

	private Globals createSandbox() {
		Globals globals = JsePlatform.standardGlobals();
		globals.load(new DebugLib());

		// Instruction budget hook, installed on the main Lua thread before debug is removed.
		LuaValue hook = new ZeroArgFunction() {
			@Override
			public LuaValue call() {
				if (stopped) {
					throw new LuaError("program stopped");
				}
				instructionsSinceCall += HOOK_INTERVAL;
				if (instructionsSinceCall > MAX_INSTRUCTIONS_BETWEEN_CALLS) {
					throw new LuaError("program is too busy - add bot.sleep() inside long loops");
				}
				return NONE;
			}
		};
		globals.get("debug").get("sethook").invoke(LuaValue.varargsOf(new LuaValue[]{
				hook, LuaValue.EMPTYSTRING, LuaValue.valueOf(HOOK_INTERVAL)}));

		for (String name : new String[]{"io", "os", "luajava", "require", "dofile", "loadfile",
				"package", "coroutine", "debug", "collectgarbage"}) {
			globals.set(name, LuaValue.NIL);
		}

		// string.rep can allocate gigabytes in one call; cap it.
		LuaTable string = globals.get("string").checktable();
		LuaValue originalRep = string.get("rep");
		string.set("rep", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				String s = args.checkjstring(1);
				int n = args.checkint(2);
				if ((long) s.length() * Math.max(0, n) > MAX_STRING_REP) {
					throw new LuaError("string.rep result too large");
				}
				return originalRep.invoke(args);
			}
		});

		globals.set("bot", createBotApi());
		globals.set("print", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				StringBuilder text = new StringBuilder();
				for (int i = 1; i <= args.narg(); i++) {
					if (i > 1) {
						text.append(' ');
					}
					text.append(args.arg(i).tojstring());
				}
				return submit("say", trim(text.toString()));
			}
		});
		return globals;
	}

	private LuaTable createBotApi() {
		LuaTable bot = new LuaTable();
		bot.set("say", fn(args -> submit("say", trim(args.arg(1).tojstring()))));
		bot.set("pos", fn(args -> submit("pos")));
		bot.set("sleep", fn(args -> submit("sleep", Math.max(0.05, Math.min(600.0, args.checkdouble(1))))));
		bot.set("goTo", fn(args -> submit("goto", args.checkint(1), args.checkint(2), args.checkint(3))));
		bot.set("dig", fn(args -> submit("dig", args.checkint(1), args.checkint(2), args.checkint(3))));
		bot.set("place", fn(args -> submit("place", args.checkint(1), args.checkint(2), args.checkint(3),
				args.checkjstring(4))));
		bot.set("block", fn(args -> submit("block", args.checkint(1), args.checkint(2), args.checkint(3))));
		bot.set("scan", fn(args -> submit("scan", args.checkjstring(1), args.optint(2, 16))));
		bot.set("deliver", fn(args -> submit("deliver")));
		bot.set("deposit", fn(args -> submit("deposit")));
		bot.set("home", fn(args -> submit("home")));
		bot.set("inventoryCount", fn(args -> submit("inventory")));
		bot.set("count", fn(args -> submit("count", args.checkjstring(1))));
		bot.set("ownerPos", fn(args -> submit("ownerpos")));
		bot.set("help", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue arg) {
				return LuaValue.valueOf("say pos sleep goTo dig place block scan deliver deposit home "
						+ "inventoryCount count ownerPos");
			}
		});
		return bot;
	}

	private interface LuaCall {
		Varargs call(Varargs args);
	}

	private static VarArgFunction fn(LuaCall call) {
		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return call.call(args);
			}
		};
	}

	private Varargs submit(String name, Object... args) {
		if (stopped) {
			throw new LuaError("program stopped");
		}
		instructionsSinceCall = 0;
		Command command = new Command(name, args);
		pending.set(command);
		try {
			Object[] result = command.future.get(30, TimeUnit.MINUTES);
			instructionsSinceCall = 0;
			return toVarargs(result);
		} catch (Exception exception) {
			throw new LuaError("program stopped");
		} finally {
			pending.compareAndSet(command, null);
		}
	}

	private static Varargs toVarargs(Object[] values) {
		if (values.length == 0) {
			return LuaValue.NONE;
		}
		LuaValue[] converted = new LuaValue[values.length];
		for (int i = 0; i < values.length; i++) {
			Object value = values[i];
			if (value == null) {
				converted[i] = LuaValue.NIL;
			} else if (value instanceof Boolean flag) {
				converted[i] = LuaValue.valueOf(flag);
			} else if (value instanceof Integer number) {
				converted[i] = LuaValue.valueOf(number);
			} else if (value instanceof Number number) {
				converted[i] = LuaValue.valueOf(number.doubleValue());
			} else {
				converted[i] = LuaValue.valueOf(String.valueOf(value));
			}
		}
		return LuaValue.varargsOf(converted);
	}
}
