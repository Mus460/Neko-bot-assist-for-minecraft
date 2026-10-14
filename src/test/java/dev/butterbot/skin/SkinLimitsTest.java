package dev.butterbot.skin;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinLimitsTest {

	private static byte[] pngHeader(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocate(64);
		buffer.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
		buffer.putInt(13);
		buffer.put(new byte[]{'I', 'H', 'D', 'R'});
		buffer.putInt(width);
		buffer.putInt(height);
		return buffer.array();
	}

	@Test
	void readsPngSize() {
		assertArrayEquals(new int[]{1024, 512}, SkinLimits.readPngSize(pngHeader(1024, 512)));
	}

	@Test
	void rejectsNonPng() {
		assertNull(SkinLimits.readPngSize("definitely not a png file, just text...".getBytes()));
		assertEquals("butterbot.skin.error.not_png", SkinLimits.validate("hello hello hello hello hello hello".getBytes()));
	}

	@Test
	void rejectsBadDimensions() {
		assertEquals("butterbot.skin.error.bad_size", SkinLimits.validate(pngHeader(4096, 4096)));
		assertEquals("butterbot.skin.error.bad_size", SkinLimits.validate(pngHeader(8, 8)));
		assertEquals("butterbot.skin.error.bad_size", SkinLimits.validate(pngHeader(100, 100)));
		assertEquals("butterbot.skin.error.bad_size", SkinLimits.validate(pngHeader(128, 64)));
		assertNull(SkinLimits.validate(pngHeader(64, 64)));
		assertNull(SkinLimits.validate(pngHeader(64, 32)));
		assertNull(SkinLimits.validate(pngHeader(512, 512)));
	}

	@Test
	void skinIdsAreSha1Hex() {
		String id = SkinLimits.sha1Hex("abc".getBytes());
		assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", id);
		assertTrue(SkinLimits.isValidSkinId(id));
		assertFalse(SkinLimits.isValidSkinId("../../etc/passwd"));
		assertFalse(SkinLimits.isValidSkinId(id.toUpperCase()));
		assertFalse(SkinLimits.isValidSkinId(""));
	}
}
