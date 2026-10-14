package dev.butterbot.skin;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Shared limits and helpers for custom skins (content-addressed by SHA-1). Skins use the normal
 * Minecraft player skin layout: 64x64, legacy 64x32, or an HD square that is a multiple of 64.
 */
public final class SkinLimits {

	public static final int MAX_BYTES = 2 * 1024 * 1024;
	public static final int MAX_DIMENSION = 1024;
	public static final int BASE_SIZE = 64;
	private static final Pattern ID_PATTERN = Pattern.compile("[0-9a-f]{40}");
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	public static boolean isValidSkinId(@Nullable String id) {
		return id != null && ID_PATTERN.matcher(id).matches();
	}

	public static String sha1Hex(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** Reads width/height from the PNG IHDR chunk without decoding the image, or null if not a PNG. */
	@Nullable
	public static int[] readPngSize(byte[] data) {
		if (data.length < 33) {
			return null;
		}
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (data[i] != PNG_SIGNATURE[i]) {
				return null;
			}
		}
		ByteBuffer buffer = ByteBuffer.wrap(data);
		int chunkLength = buffer.getInt(8);
		if (chunkLength != 13 || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
			return null;
		}
		int width = buffer.getInt(16);
		int height = buffer.getInt(20);
		return new int[]{width, height};
	}

	/** Returns null when the PNG is acceptable, otherwise a translation key describing the problem. */
	@Nullable
	public static String validate(byte[] data) {
		if (data.length == 0 || data.length > MAX_BYTES) {
			return "butterbot.skin.error.too_big";
		}
		int[] size = readPngSize(data);
		if (size == null) {
			return "butterbot.skin.error.not_png";
		}
		if (!isPlayerSkinSize(size[0], size[1])) {
			return "butterbot.skin.error.bad_size";
		}
		return null;
	}

	/** 64x64, legacy 64x32, or an HD square (128, 256, 512, 1024). */
	public static boolean isPlayerSkinSize(int width, int height) {
		if (width == BASE_SIZE && height == BASE_SIZE / 2) {
			return true;
		}
		return width == height && width >= BASE_SIZE && width <= MAX_DIMENSION && Integer.bitCount(width) == 1;
	}

	private SkinLimits() {
	}
}
