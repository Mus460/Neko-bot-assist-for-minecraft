package dev.butterbot.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hologram of a design waiting for the owner's yes: every planned block drawn see-through and tinted blue where
 * it differs from what is there, spots it would clear outlined red, and the owner's marks outlined yellow.
 */
public final class BuildPreview {

	private static final double RANGE = 96.0D;
	private static final double MARK_RANGE = 64.0D;
	private static final int GHOST_COLOR = ARGB.color(150, 170, 215, 255);
	private static final int CLEAR_COLOR = ARGB.color(255, 255, 70, 70);
	private static final int MARK_COLOR = ARGB.color(255, 255, 220, 40);
	private static final Direction[] FACES = Direction.values();

	private static Map<BlockPos, BlockState> ghosts = Map.of();
	private static List<BlockPos> marks = List.of();
	/** Planned blocks that are not air, counted once per update for the HUD hint. */
	private static int ghostCount;
	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<BlockStateModelPart> PARTS = new ArrayList<>();

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.BuildView.TYPE, (payload, context) -> receive(payload));
		LevelRenderEvents.COLLECT_SUBMITS.register(BuildPreview::render);
	}

	private static void receive(Payloads.BuildView view) {
		Map<BlockPos, BlockState> next = new HashMap<>();
		for (int i = 0; i < view.positions().length; i++) {
			next.put(BlockPos.of(view.positions()[i]), Block.stateById(view.states()[i]));
		}
		List<BlockPos> nextMarks = new ArrayList<>();
		for (long mark : view.marks()) {
			nextMarks.add(BlockPos.of(mark));
		}
		ghosts = next;
		marks = nextMarks;
		ghostCount = (int) next.values().stream().filter(state -> !state.isAir()).count();
	}

	/** A planned block that isn't there yet (the hologram shows only what would change). */
	private static boolean showsGhost(Minecraft minecraft, BlockPos pos) {
		BlockState planned = ghosts.get(pos);
		return planned != null && !planned.isAir() && !minecraft.level.getBlockState(pos).equals(planned);
	}

	/** How many blocks the hologram holds (0 when there is none), for the HUD hint. */
	static int ghostCount() {
		return ghosts.isEmpty() ? 0 : ghostCount;
	}

	private static void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || ghosts.isEmpty() && marks.isEmpty()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		PoseStack poseStack = context.poseStack();
		List<BlockPos> clears = new ArrayList<>();
		List<BlockPos> shown = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> ghost : ghosts.entrySet()) {
			BlockPos pos = ghost.getKey();
			if (pos.distToCenterSqr(camera) > RANGE * RANGE) {
				continue;
			}
			if (ghost.getValue().isAir()) {
				if (!minecraft.level.getBlockState(pos).isAir()) {
					clears.add(pos);
				}
			} else if (showsGhost(minecraft, pos)) {
				shown.add(pos);
			}
		}
		if (!shown.isEmpty()) {
			Map<BlockPos, BlockState> drawn = ghosts;
			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS),
					(pose, buffer) -> {
						for (BlockPos pos : shown) {
							drawBlock(minecraft, pose.pose(), buffer, pos, drawn.get(pos), camera);
						}
					});
		}
		for (BlockPos pos : clears.size() > 2000 ? clears.subList(0, 2000) : clears) {
			outline(context, poseStack, pos, camera, CLEAR_COLOR);
		}
		for (BlockPos pos : marks) {
			outline(context, poseStack, pos, camera, MARK_COLOR);
		}
	}

	/** The block's own model, faces against other hologram blocks left out. */
	private static void drawBlock(Minecraft minecraft, Matrix4f matrix, VertexConsumer buffer, BlockPos pos, BlockState state, Vec3 camera) {
		PARTS.clear();
		RANDOM.setSeed(pos.asLong());
		minecraft.getModelManager().getBlockStateModelSet().get(state).collectParts(RANDOM, PARTS);
		float x = (float) (pos.getX() - camera.x);
		float y = (float) (pos.getY() - camera.y);
		float z = (float) (pos.getZ() - camera.z);
		for (BlockStateModelPart part : PARTS) {
			for (Direction face : FACES) {
				BlockState neighbour = ghosts.get(pos.relative(face));
				if (neighbour != null && neighbour.isSolidRender()) {
					continue;
				}
				for (BakedQuad quad : part.getQuads(face)) {
					putQuad(matrix, buffer, quad, x, y, z);
				}
			}
			for (BakedQuad quad : part.getQuads(null)) {
				putQuad(matrix, buffer, quad, x, y, z);
			}
		}
	}

	private static void putQuad(Matrix4f matrix, VertexConsumer buffer, BakedQuad quad, float x, float y, float z) {
		Vector3fc normal = quad.direction().getUnitVec3f();
		Vector3f at = new Vector3f();
		for (int vertex = 0; vertex < 4; vertex++) {
			Vector3fc position = quad.position(vertex);
			matrix.transformPosition(position.x() + x, position.y() + y, position.z() + z, at);
			long uv = quad.packedUV(vertex);
			buffer.addVertex(at.x(), at.y(), at.z(), GHOST_COLOR, UVPair.unpackU(uv), UVPair.unpackV(uv), OverlayTexture.NO_OVERLAY,
					LightCoordsUtil.FULL_BRIGHT, normal.x(), normal.y(), normal.z());
		}
	}

	private static void outline(LevelRenderContext context, PoseStack poseStack, BlockPos pos, Vec3 camera, int color) {
		poseStack.pushPose();
		poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
		context.submitNodeCollector().submitShapeOutline(poseStack, Shapes.block(), RenderTypes.lines(), color, 3.0F, false);
		poseStack.popPose();
	}

	/**
	 * The mark key: marks (or unmarks) the hologram block or real block under the crosshair, whichever is nearer.
	 * With sneak held it clears every mark.
	 */
	static void markLookedAt(Minecraft minecraft, LocalPlayer player) {
		if (player.isShiftKeyDown()) {
			ClientPlayNetworking.send(new Payloads.BuildMark(Optional.empty()));
			return;
		}
		Vec3 eye = player.getEyePosition(1.0F);
		Vec3 look = player.getViewVector(1.0F);
		HitResult hit = player.pick(MARK_RANGE, 1.0F, false);
		double worldDistance = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().distanceTo(eye) : MARK_RANGE;
		BlockPos target = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit.getBlockPos() : null;
		for (double step = 0.0D; step < worldDistance; step += 0.2D) {
			BlockPos pos = BlockPos.containing(eye.add(look.scale(step)));
			if (showsGhost(minecraft, pos)) {
				target = pos;
				break;
			}
		}
		if (target != null) {
			ClientPlayNetworking.send(new Payloads.BuildMark(Optional.of(target)));
		}
	}

	private BuildPreview() {
	}
}
