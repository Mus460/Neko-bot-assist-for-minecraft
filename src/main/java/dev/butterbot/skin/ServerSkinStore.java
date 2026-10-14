package dev.butterbot.skin;

import dev.butterbot.ButterBot;
import dev.butterbot.network.Payloads;
import dev.butterbot.util.Msg;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores uploaded skins inside the world folder ({@code <world>/butterbot_skins/<sha1>.png}) and
 * hands them out to clients that need them. Runs on the server thread only.
 */
public final class ServerSkinStore {

	private static final long CACHE_LIMIT_BYTES = 48L * 1024 * 1024;
	private static final long UPLOAD_COOLDOWN_MS = 1500;
	private static final int MAX_FETCHES_PER_MINUTE = 60;

	@Nullable
	private static Path directory;
	private static final Set<String> known = new HashSet<>();
	private static final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, 0.75F, true);
	private static long cacheBytes;
	private static final Map<UUID, Long> lastUpload = new HashMap<>();
	private static final Map<UUID, long[]> fetchWindow = new HashMap<>();

	public static void init(MinecraftServer server) {
		clear();
		directory = server.getWorldPath(LevelResource.ROOT).resolve("butterbot_skins");
		try {
			Files.createDirectories(directory);
			try (Stream<Path> files = Files.list(directory)) {
				files.forEach(path -> {
					String name = path.getFileName().toString();
					if (name.endsWith(".png") && SkinLimits.isValidSkinId(name.substring(0, name.length() - 4))) {
						known.add(name.substring(0, name.length() - 4));
					}
				});
			}
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not prepare skin folder {}", directory, exception);
		}
	}

	public static void clear() {
		directory = null;
		known.clear();
		cache.clear();
		cacheBytes = 0;
		lastUpload.clear();
		fetchWindow.clear();
	}

	public static boolean has(String skinId) {
		return known.contains(skinId);
	}

	@Nullable
	public static byte[] get(String skinId) {
		if (!known.contains(skinId) || directory == null) {
			return null;
		}
		byte[] cached = cache.get(skinId);
		if (cached != null) {
			return cached;
		}
		try {
			byte[] data = Files.readAllBytes(directory.resolve(skinId + ".png"));
			remember(skinId, data);
			return data;
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not read skin {}", skinId, exception);
			known.remove(skinId);
			return null;
		}
	}

	private static void remember(String skinId, byte[] data) {
		cache.put(skinId, data);
		cacheBytes += data.length;
		var iterator = cache.entrySet().iterator();
		while (cacheBytes > CACHE_LIMIT_BYTES && iterator.hasNext()) {
			var eldest = iterator.next();
			if (eldest.getKey().equals(skinId)) {
				continue;
			}
			cacheBytes -= eldest.getValue().length;
			iterator.remove();
		}
	}

	public static void handleUpload(ServerPlayer player, String skinId, byte[] data) {
		if (directory == null || known.contains(skinId)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long previous = lastUpload.get(player.getUUID());
		if (previous != null && now - previous < UPLOAD_COOLDOWN_MS) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.skin.error.slow_down").withStyle(ChatFormatting.RED)));
			return;
		}
		lastUpload.put(player.getUUID(), now);

		if (!SkinLimits.isValidSkinId(skinId) || !SkinLimits.sha1Hex(data).equals(skinId)) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.skin.error.corrupt").withStyle(ChatFormatting.RED)));
			return;
		}
		String problem = SkinLimits.validate(data);
		if (problem != null) {
			player.sendSystemMessage(Msg.prefixed(Component.translatable(problem).withStyle(ChatFormatting.RED)));
			return;
		}
		if (known.contains(skinId)) {
			return;
		}
		try {
			Path target = directory.resolve(skinId + ".png");
			Path temp = directory.resolve(skinId + ".tmp");
			Files.write(temp, data);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			known.add(skinId);
			remember(skinId, data);
			ButterBot.LOGGER.info("Stored Butter Bot skin {} ({} bytes) from {}", skinId, data.length, player.getName().getString());
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not store skin {}", skinId, exception);
			player.sendSystemMessage(Msg.prefixed(Component.translatable("butterbot.skin.error.io").withStyle(ChatFormatting.RED)));
		}
	}

	/** Sends a stored skin to one player without waiting for them to ask. */
	public static void push(ServerPlayer player, String skinId) {
		byte[] data = get(skinId);
		if (data != null) {
			ServerPlayNetworking.send(player, new Payloads.SkinData(skinId, data));
		}
	}

	public static void handleFetch(ServerPlayer player, String skinId) {
		if (!SkinLimits.isValidSkinId(skinId)) {
			return;
		}
		long minute = System.currentTimeMillis() / 60_000L;
		long[] window = fetchWindow.computeIfAbsent(player.getUUID(), id -> new long[]{minute, 0});
		if (window[0] != minute) {
			window[0] = minute;
			window[1] = 0;
		}
		if (++window[1] > MAX_FETCHES_PER_MINUTE) {
			return;
		}
		byte[] data = get(skinId);
		if (data != null) {
			ServerPlayNetworking.send(player, new Payloads.SkinData(skinId, data));
		}
	}

	private ServerSkinStore() {
	}
}
