package dev.butterbot;

import net.minecraft.core.BlockPos;

import java.util.function.Consumer;

/**
 * Bridges common code to client-only code without loading client classes on a dedicated server.
 * The client entrypoint installs the real implementations.
 */
public final class ClientHooks {

	private static Consumer<BlockPos> baseScreenOpener = pos -> {
	};

	public static void setBaseScreenOpener(Consumer<BlockPos> opener) {
		baseScreenOpener = opener;
	}

	public static void openBaseScreen(BlockPos pos) {
		baseScreenOpener.accept(pos);
	}

	private ClientHooks() {
	}
}
