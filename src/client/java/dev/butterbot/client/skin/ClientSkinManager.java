package dev.butterbot.client.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.butterbot.ButterBot;
import dev.butterbot.network.Payloads;
import dev.butterbot.skin.SkinLimits;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Client side of custom skins. Skins are normal Minecraft player skins (64x64, legacy 64x32 or HD).
 * <ul>
 *     <li>Local skins: PNG files in {@code config/butterbot/skins/}.</li>
 *     <li>Skins are identified by the SHA-1 of their bytes, so names may differ between players.</li>
 *     <li>Unknown skins seen on bots are fetched from the server once and cached on disk.</li>
 *     <li>Bots without a custom skin wear the neko skin that ships with the mod.</li>
 * </ul>
 * All methods must be called on the client (render) thread.
 */
public final class ClientSkinManager {

	public static final Path SKIN_DIR = FabricLoader.getInstance().getConfigDir().resolve("butterbot").resolve("skins");
	private static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve("butterbot_skin_cache");
	private static final String TEMPLATE_NAME = "_template_steve.png";
	private static final Identifier TEMPLATE_SOURCE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
	/** "Neko Girl Uniform" by brandongrahn (mc-skins.net), slim arms. */
	public static final Identifier DEFAULT_SKIN = ButterBot.id("textures/entity/neko_default.png");
	/** SHA-1 of the original neko_girl_slim.png that {@link #DEFAULT_SKIN} was made from. */
	private static final String DEFAULT_SOURCE_SKIN_ID = "c0f14f2947062f4d3d69ae264eaf4175008cd5d1";
	private static final long REFETCH_DELAY_MS = 30_000L;
	private static final int MAX_LOCAL_SKINS = 200;
	private static final int FALLBACK_HAIR_COLOR = 0xFF3B2A20;

	/** A loaded skin: its texture, arm model and the hair colour used for the cat ears and tail. */
	public record SkinInfo(Identifier texture, boolean slim, int hairColor) {
	}

	public record LocalSkin(String fileName, Path path, String skinId, int width, int height) {
		public String displayName() {
			String name = fileName.toLowerCase(Locale.ROOT).endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
			return name.replace('_', ' ');
		}
	}

	private record FileKey(long size, long modified) {
	}

	private record CachedHash(FileKey key, String skinId, int width, int height) {
	}

	private static final Map<String, SkinInfo> loaded = new HashMap<>();
	private static final Map<Identifier, SkinInfo> defaults = new HashMap<>();
	private static final Set<String> failed = new HashSet<>();
	private static final Map<String, Long> requested = new HashMap<>();
	private static final Map<String, Path> localById = new HashMap<>();
	private static final Map<Path, CachedHash> hashCache = new HashMap<>();
	/** Skins already looked for on disk without success; avoids file checks every frame. */
	private static final Set<String> notOnDisk = new HashSet<>();
	private static List<LocalSkin> localSkins = List.of();
	/** The skin folder is read once on first use, even if the Skin tab was never opened. */
	private static boolean scannedOnce;

	public static void ensureSetup() {
		try {
			Files.createDirectories(SKIN_DIR);
			Files.createDirectories(CACHE_DIR);
			Path template = SKIN_DIR.resolve(TEMPLATE_NAME);
			if (Files.notExists(template)) {
				Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(TEMPLATE_SOURCE);
				if (resource.isPresent()) {
					try (InputStream in = resource.get().open()) {
						Files.copy(in, template);
					}
				}
			}
			Path readme = SKIN_DIR.resolve("_README.txt");
			String text = """
					Butter Bot skins
					================
					Bots use normal Minecraft player skins, the same files you would use for yourself.

					- Download any skin PNG (for example from a skin website) and put it in this folder,
					  or drag the file onto the Skin tab of the Bot control panel.
					- Supported sizes: 64x64, old 64x32 skins, and HD skins (128x128 up to 1024x1024). At most 2 MB.
					- Slim (Alex-style) arms are detected automatically. Put "slim" or "wide" in the file name to force one.
					- _template_steve.png is the default Steve skin, handy as a starting point for your own.
					- Files starting with an underscore are hidden from the list.

					Open the control panel (right-click your bot, or type /butterbot menu), go to the Skin tab,
					choose a file and press Apply. The skin is sent to the server once and every player sees it.
					The "Neko" button adds cat ears and a tail on top of any skin.
					""";
			if (Files.notExists(readme) || !Files.readString(readme, StandardCharsets.UTF_8).equals(text)) {
				Files.writeString(readme, text, StandardCharsets.UTF_8);
			}
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not prepare the skin folder {}", SKIN_DIR, exception);
		}
	}

	public static List<LocalSkin> localSkins() {
		return localSkins;
	}

	/** Re-reads the skin folder. Cheap when nothing changed (hashes are cached by size and date). */
	public static List<LocalSkin> rescan() {
		ensureSetup();
		List<LocalSkin> result = new ArrayList<>();
		localById.clear();
		try (Stream<Path> files = Files.list(SKIN_DIR)) {
			List<Path> pngs = files
					.filter(path -> Files.isRegularFile(path))
					.filter(path -> {
						String name = path.getFileName().toString();
						return name.toLowerCase(Locale.ROOT).endsWith(".png") && !name.startsWith("_");
					})
					.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
					.limit(MAX_LOCAL_SKINS)
					.toList();
			for (Path path : pngs) {
				LocalSkin skin = describe(path);
				if (skin != null) {
					result.add(skin);
					localById.put(skin.skinId(), path);
				}
			}
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not list skins in {}", SKIN_DIR, exception);
		}
		localSkins = List.copyOf(result);
		notOnDisk.clear();
		scannedOnce = true;
		return localSkins;
	}

	@Nullable
	private static LocalSkin describe(Path path) {
		try {
			FileKey key = new FileKey(Files.size(path), Files.getLastModifiedTime(path).toMillis());
			if (key.size() <= 0 || key.size() > SkinLimits.MAX_BYTES) {
				return null;
			}
			CachedHash cached = hashCache.get(path);
			if (cached == null || !cached.key().equals(key)) {
				byte[] data = Files.readAllBytes(path);
				if (SkinLimits.validate(data) != null) {
					return null;
				}
				int[] size = SkinLimits.readPngSize(data);
				cached = new CachedHash(key, SkinLimits.sha1Hex(data), size[0], size[1]);
				hashCache.put(path, cached);
			}
			return new LocalSkin(path.getFileName().toString(), path, cached.skinId(), cached.width(), cached.height());
		} catch (IOException exception) {
			return null;
		}
	}

	/** Texture for a skin id, or {@code null} while it is unknown, downloading or broken. */
	@Nullable
	public static Identifier textureFor(String skinId) {
		SkinInfo info = skinFor(skinId);
		return info == null ? null : info.texture();
	}

	/**
	 * The loaded custom skin, or {@code null} to use the default skin (no skin, still downloading, or
	 * broken file). Unknown skins are requested from the server.
	 */
	@Nullable
	public static SkinInfo skinFor(String skinId) {
		if (skinId == null || skinId.isEmpty() || !SkinLimits.isValidSkinId(skinId)) {
			return null;
		}
		SkinInfo info = loaded.get(skinId);
		if (info != null || failed.contains(skinId)) {
			return info;
		}

		if (!notOnDisk.contains(skinId)) {
			if (!scannedOnce) {
				rescan();
			}
			Path local = localById.get(skinId);
			byte[] data = readLocal(skinId);
			if (data != null) {
				return register(skinId, data, local == null ? null : local.getFileName().toString());
			}
			notOnDisk.add(skinId);
		}

		long now = System.currentTimeMillis();
		Long last = requested.get(skinId);
		if ((last == null || now - last > REFETCH_DELAY_MS) && Minecraft.getInstance().getConnection() != null) {
			if (last == null) {
				ButterBot.LOGGER.info("Butter Bot skin {} is not on this computer yet, asking the server", skinId);
			}
			requested.put(skinId, now);
			ClientPlayNetworking.send(new Payloads.SkinFetch(skinId));
		}
		return null;
	}

	public enum Status { DEFAULT, READY, LOADING, FAILED }

	/** How a bot's skin is doing on this computer, for the Skin tab. */
	public static Status statusOf(String skinId) {
		if (skinId == null || skinId.isEmpty()) {
			return Status.DEFAULT;
		}
		if (loaded.containsKey(skinId)) {
			return Status.READY;
		}
		return failed.contains(skinId) || !SkinLimits.isValidSkinId(skinId) ? Status.FAILED : Status.LOADING;
	}

	/** True for the neko skin file the built-in default was made from: applying it changes nothing visible. */
	public static boolean looksLikeDefault(String skinId) {
		return DEFAULT_SOURCE_SKIN_ID.equals(skinId);
	}

	/** The skin every bot wears until it gets a custom one: the neko girl that ships with the mod. */
	public static SkinInfo defaultSkinFor(UUID botId) {
		return defaults.computeIfAbsent(DEFAULT_SKIN, id -> new SkinInfo(id, true, readResourceHairColor(id)));
	}

	private static int readResourceHairColor(Identifier texture) {
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
		if (resource.isEmpty()) {
			return FALLBACK_HAIR_COLOR;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			return hairColor(image);
		} catch (IOException | RuntimeException exception) {
			return FALLBACK_HAIR_COLOR;
		}
	}

	@Nullable
	private static byte[] readLocal(String skinId) {
		Path local = localById.get(skinId);
		Path cached = CACHE_DIR.resolve(skinId + ".png");
		for (Path path : new Path[]{local, cached}) {
			if (path == null || Files.notExists(path)) {
				continue;
			}
			try {
				byte[] data = Files.readAllBytes(path);
				if (SkinLimits.sha1Hex(data).equals(skinId)) {
					return data;
				}
			} catch (IOException ignored) {
				// fall through to the next source
			}
		}
		return null;
	}

	@Nullable
	private static SkinInfo register(String skinId, byte[] data, @Nullable String fileName) {
		NativeImage image = null;
		try {
			image = NativeImage.read(data);
			if (!SkinLimits.isPlayerSkinSize(image.getWidth(), image.getHeight())) {
				throw new IOException("not a player skin: " + image.getWidth() + "x" + image.getHeight());
			}
			image = prepareSkin(image);
			boolean slim = detectSlim(image, fileName);
			int hair = hairColor(image);
			DynamicTexture texture = new DynamicTexture(() -> "Butter Bot skin " + skinId, image);
			image = null; // owned by the texture now
			Identifier id = ButterBot.id("dynamic/skin_" + skinId);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			SkinInfo info = new SkinInfo(id, slim, hair);
			loaded.put(skinId, info);
			failed.remove(skinId);
			ButterBot.LOGGER.info("Butter Bot skin {} ready ({} arms){}", skinId, slim ? "slim" : "wide",
					fileName == null ? "" : " from " + fileName);
			return info;
		} catch (IOException | RuntimeException exception) {
			if (image != null) {
				image.close();
			}
			failed.add(skinId);
			ButterBot.LOGGER.warn("Butter Bot skin {} could not be loaded, using the default skin", skinId, exception);
			return null;
		}
	}

	/**
	 * Same clean-up vanilla does for downloaded player skins: old 64x32 skins are expanded to the
	 * 64x64 layout, and the base layer is made fully opaque so stray transparent pixels don't leave holes.
	 */
	private static NativeImage prepareSkin(NativeImage image) {
		boolean legacy = image.getHeight() * 2 == image.getWidth();
		if (legacy) {
			NativeImage expanded = new NativeImage(64, 64, true);
			expanded.copyFrom(image);
			image.close();
			image = expanded;
			image.fillRect(0, 32, 64, 32, 0);
			image.copyRect(4, 16, 16, 32, 4, 4, true, false);
			image.copyRect(8, 16, 16, 32, 4, 4, true, false);
			image.copyRect(0, 20, 24, 32, 4, 12, true, false);
			image.copyRect(4, 20, 16, 32, 4, 12, true, false);
			image.copyRect(8, 20, 8, 32, 4, 12, true, false);
			image.copyRect(12, 20, 16, 32, 4, 12, true, false);
			image.copyRect(44, 16, -8, 32, 4, 4, true, false);
			image.copyRect(48, 16, -8, 32, 4, 4, true, false);
			image.copyRect(40, 20, 0, 32, 4, 12, true, false);
			image.copyRect(44, 20, -8, 32, 4, 12, true, false);
			image.copyRect(48, 20, -16, 32, 4, 12, true, false);
			image.copyRect(52, 20, -8, 32, 4, 12, true, false);
		}
		int f = image.getWidth() / SkinLimits.BASE_SIZE;
		setNoAlpha(image, 0, 0, 32 * f, 16 * f);
		if (legacy) {
			notchTransparencyHack(image, 32, 0, 64, 32);
		}
		setNoAlpha(image, 0, 16 * f, 64 * f, 32 * f);
		setNoAlpha(image, 16 * f, 48 * f, 48 * f, 64 * f);
		return image;
	}

	private static void setNoAlpha(NativeImage image, int x0, int y0, int x1, int y1) {
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) {
				image.setPixel(x, y, ARGB.opaque(image.getPixel(x, y)));
			}
		}
	}

	/** Old skins drew a solid hat layer to mean "no hat"; vanilla clears it in that case. */
	private static void notchTransparencyHack(NativeImage image, int x0, int y0, int x1, int y1) {
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) {
				if (ARGB.alpha(image.getPixel(x, y)) < 128) {
					return;
				}
			}
		}
		for (int x = x0; x < x1; x++) {
			for (int y = y0; y < y1; y++) {
				image.setPixel(x, y, image.getPixel(x, y) & 0x00FFFFFF);
			}
		}
	}

	/**
	 * Slim skins leave the outer columns of the right arm's back face empty. A "slim" or "wide" in the
	 * file name overrides the guess.
	 */
	private static boolean detectSlim(NativeImage image, @Nullable String fileName) {
		if (fileName != null) {
			String name = fileName.toLowerCase(Locale.ROOT);
			if (name.contains("slim") || name.contains("alex")) {
				return true;
			}
			if (name.contains("wide") || name.contains("classic") || name.contains("steve")) {
				return false;
			}
		}
		int f = image.getWidth() / SkinLimits.BASE_SIZE;
		for (int x = 54 * f; x < 56 * f; x++) {
			for (int y = 20 * f; y < 32 * f; y++) {
				if (ARGB.alpha(image.getPixel(x, y)) != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/** Average colour of the top of the head (hat layer first, since hair is often drawn there). */
	private static int hairColor(NativeImage image) {
		int f = Math.max(1, image.getWidth() / SkinLimits.BASE_SIZE);
		int hat = averageOpaque(image, 40 * f, 0, 48 * f, 8 * f, 0.25F);
		if (hat != 0) {
			return hat;
		}
		int head = averageOpaque(image, 8 * f, 0, 16 * f, 8 * f, 0.25F);
		return head != 0 ? head : FALLBACK_HAIR_COLOR;
	}

	/** Average of the opaque pixels in a region, or 0 if fewer than {@code minCoverage} of them are opaque. */
	private static int averageOpaque(NativeImage image, int x0, int y0, int x1, int y1, float minCoverage) {
		long r = 0;
		long g = 0;
		long b = 0;
		int count = 0;
		int total = 0;
		for (int x = x0; x < x1 && x < image.getWidth(); x++) {
			for (int y = y0; y < y1 && y < image.getHeight(); y++) {
				total++;
				int pixel = image.getPixel(x, y);
				if (ARGB.alpha(pixel) >= 128) {
					r += ARGB.red(pixel);
					g += ARGB.green(pixel);
					b += ARGB.blue(pixel);
					count++;
				}
			}
		}
		if (count == 0 || count < total * minCoverage) {
			return 0;
		}
		return ARGB.color(255, (int) (r / count), (int) (g / count), (int) (b / count));
	}

	/** Called when the server sends a skin this client asked for. */
	public static void onSkinData(String skinId, byte[] data) {
		requested.remove(skinId);
		if (!SkinLimits.isValidSkinId(skinId) || !SkinLimits.sha1Hex(data).equals(skinId) || SkinLimits.validate(data) != null) {
			ButterBot.LOGGER.warn("Butter Bot skin {} from the server is damaged ({} bytes), using the default skin", skinId, data.length);
			failed.add(skinId);
			return;
		}
		try {
			Files.createDirectories(CACHE_DIR);
			Files.write(CACHE_DIR.resolve(skinId + ".png"), data);
		} catch (IOException exception) {
			ButterBot.LOGGER.warn("Could not cache skin {}", skinId, exception);
		}
		if (!loaded.containsKey(skinId)) {
			register(skinId, data, null);
		}
	}

	/** Uploads the skin and asks the server to put it on the bot. */
	public static boolean apply(Optional<BlockPos> base, UUID botId, LocalSkin skin) {
		byte[] data;
		try {
			data = Files.readAllBytes(skin.path());
		} catch (IOException exception) {
			return false;
		}
		if (SkinLimits.validate(data) != null || !SkinLimits.sha1Hex(data).equals(skin.skinId())) {
			rescan();
			return false;
		}
		// Always send the bytes: the server ignores skins it already has, and this keeps a retry working
		// if an earlier upload was rejected (for example by the upload cooldown).
		ClientPlayNetworking.send(new Payloads.SkinUpload(skin.skinId(), data));
		ClientPlayNetworking.send(new Payloads.BotCommand(base, botId, Payloads.BotCommand.SET_SKIN, skin.skinId(), new int[0]));
		return true;
	}

	/**
	 * Copies a dropped file into the skin folder. Returns the error translation key, or null on success.
	 */
	@Nullable
	public static String importFile(Path source) {
		try {
			if (!Files.isRegularFile(source) || Files.size(source) > SkinLimits.MAX_BYTES) {
				return "butterbot.skin.error.too_big";
			}
			byte[] data = Files.readAllBytes(source);
			String problem = SkinLimits.validate(data);
			if (problem != null) {
				return problem;
			}
			ensureSetup();
			String name = source.getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
			if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
				name = name + ".png";
			}
			if (name.startsWith("_")) {
				name = "skin" + name;
			}
			Path target = SKIN_DIR.resolve(name);
			if (Files.exists(target) && !SkinLimits.sha1Hex(Files.readAllBytes(target)).equals(SkinLimits.sha1Hex(data))) {
				String base = name.substring(0, name.length() - 4);
				int n = 2;
				while (Files.exists(target)) {
					target = SKIN_DIR.resolve(base + "_" + n++ + ".png");
				}
			}
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			rescan();
			return null;
		} catch (IOException exception) {
			return "butterbot.skin.error.io";
		}
	}

	/** Forget per-connection state (pending requests and failures) when leaving a world or server. */
	public static void onDisconnect() {
		notOnDisk.clear();
		requested.clear();
		failed.clear();
	}

	private ClientSkinManager() {
	}
}
