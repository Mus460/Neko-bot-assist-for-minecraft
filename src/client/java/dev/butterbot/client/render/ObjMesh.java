package dev.butterbot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.butterbot.ButterBot;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A minimal Wavefront OBJ mesh (positions, UVs, normals, triangles or quads) rendered straight
 * into a {@link VertexConsumer}. UVs map to the whole texture, so any PNG with the same layout can
 * be used as a skin.
 */
public final class ObjMesh {

	/** Interleaved per-vertex data: x, y, z, u, v, nx, ny, nz. Every 4 vertices form one quad. */
	private final float[] data;
	private final int vertexCount;

	private ObjMesh(float[] data, int vertexCount) {
		this.data = data;
		this.vertexCount = vertexCount;
	}

	public int quadCount() {
		return vertexCount / 4;
	}

	public void render(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		for (int i = 0; i < vertexCount; i++) {
			int o = i * 8;
			pose.transformNormal(data[o + 5], data[o + 6], data[o + 7], normal);
			buffer.addVertex(matrix, data[o], data[o + 1], data[o + 2])
					.setColor(color)
					.setUv(data[o + 3], data[o + 4])
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(normal.x(), normal.y(), normal.z());
		}
	}

	public static ObjMesh load(Identifier location) {
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
		if (resource.isEmpty()) {
			ButterBot.LOGGER.error("Missing OBJ model {}", location);
			return new ObjMesh(new float[0], 0);
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8))) {
			return parse(reader, location);
		} catch (IOException | RuntimeException exception) {
			ButterBot.LOGGER.error("Could not load OBJ model {}", location, exception);
			return new ObjMesh(new float[0], 0);
		}
	}

	static ObjMesh parse(BufferedReader reader, Identifier location) throws IOException {
		List<float[]> positions = new ArrayList<>();
		List<float[]> uvs = new ArrayList<>();
		List<float[]> normals = new ArrayList<>();
		List<int[]> corners = new ArrayList<>();

		String line;
		while ((line = reader.readLine()) != null) {
			line = line.trim();
			if (line.isEmpty() || line.charAt(0) == '#') {
				continue;
			}
			String[] parts = line.split("\\s+");
			switch (parts[0]) {
				case "v" -> positions.add(new float[]{f(parts[1]), f(parts[2]), f(parts[3])});
				case "vt" -> uvs.add(new float[]{f(parts[1]), parts.length > 2 ? f(parts[2]) : 0.0F});
				case "vn" -> normals.add(new float[]{f(parts[1]), f(parts[2]), f(parts[3])});
				case "f" -> {
					int n = parts.length - 1;
					if (n < 3) {
						continue;
					}
					int[][] face = new int[n][];
					for (int k = 0; k < n; k++) {
						face[k] = parseCorner(parts[k + 1], positions.size(), uvs.size(), normals.size());
					}
					// Triangles become degenerate quads (last corner repeated); polygons are fanned.
					for (int k = 1; k + 1 < n; k += 2) {
						corners.add(face[0]);
						corners.add(face[k]);
						corners.add(face[k + 1]);
						corners.add(k + 2 < n ? face[k + 2] : face[k + 1]);
					}
				}
				default -> {
				}
			}
		}

		float[] data = new float[corners.size() * 8];
		for (int i = 0; i < corners.size(); i++) {
			int[] c = corners.get(i);
			float[] p = positions.get(c[0]);
			float[] t = c[1] >= 0 ? uvs.get(c[1]) : new float[]{0.0F, 0.0F};
			float[] nrm = c[2] >= 0 ? normals.get(c[2]) : new float[]{0.0F, 1.0F, 0.0F};
			int o = i * 8;
			data[o] = p[0];
			data[o + 1] = p[1];
			data[o + 2] = p[2];
			data[o + 3] = t[0];
			data[o + 4] = 1.0F - t[1]; // OBJ UV origin is bottom-left, textures are top-left
			data[o + 5] = nrm[0];
			data[o + 6] = nrm[1];
			data[o + 7] = nrm[2];
		}
		ButterBot.LOGGER.debug("Loaded OBJ {} with {} quads", location, corners.size() / 4);
		return new ObjMesh(data, corners.size());
	}

	private static int[] parseCorner(String token, int positionCount, int uvCount, int normalCount) {
		String[] idx = token.split("/", -1);
		int v = index(idx[0], positionCount);
		int vt = idx.length > 1 && !idx[1].isEmpty() ? index(idx[1], uvCount) : -1;
		int vn = idx.length > 2 && !idx[2].isEmpty() ? index(idx[2], normalCount) : -1;
		return new int[]{v, vt, vn};
	}

	private static int index(String raw, int count) {
		int value = Integer.parseInt(raw);
		return value < 0 ? count + value : value - 1;
	}

	private static float f(String raw) {
		return Float.parseFloat(raw);
	}
}
