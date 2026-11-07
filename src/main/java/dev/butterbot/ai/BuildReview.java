package dev.butterbot.ai;

import dev.butterbot.ai.actions.BuildAction;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.network.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The owner's say over what the AI designs: a design waits as a hologram (only the owner sees it) until they answer
 * yes or cancel, and the owner can mark spots on the hologram or on the finished building and ask for changes there.
 * Kept in memory only: a reload forgets designs and marks.
 */
public final class BuildReview {

	public static final int MAX_MARKS = 9;
	private static final Pattern YES = Pattern.compile("\\b(ya|iya|yes|y|ok|oke|okay|gas|gass|gaskan|lanjut|lanjutkan|bangun|setuju|boleh|mulai|sip)\\b");
	private static final Pattern NO = Pattern.compile("\\b(batal|batalkan|tidak|nggak|gak|enggak|jangan|no|cancel|stop|ga)\\b");

	/** One design: what was asked, the AI's JSON (to change it later), where it stands and the owner's marks on it. */
	public static final class Design {
		final UUID owner;
		final String request;
		final String json;
		final BlockPos origin;
		final Direction facing;
		final Blueprint.Plan plan;
		boolean waiting = true;
		final List<BlockPos> marks = new ArrayList<>();

		Design(UUID owner, String request, String json, BlockPos origin, Direction facing, Blueprint.Plan plan) {
			this.owner = owner;
			this.request = request;
			this.json = json;
			this.origin = origin;
			this.facing = facing;
			this.plan = plan;
		}
	}

	private static final Map<UUID, Design> BY_BOT = new HashMap<>();
	/** The bot whose design the owner last saw: marks go there. */
	private static final Map<UUID, UUID> LAST_BOT = new HashMap<>();

	public static Design of(ButterBotEntity bot) {
		return BY_BOT.get(bot.getUUID());
	}

	public static boolean isWaiting(ButterBotEntity bot) {
		Design design = of(bot);
		return design != null && design.waiting;
	}

	/** Shows a new design as a hologram and asks the owner; nothing is built until they say yes. */
	static void propose(ButterBotEntity bot, ServerPlayer player, Design design) {
		BY_BOT.put(bot.getUUID(), design);
		LAST_BOT.put(player.getUUID(), bot.getUUID());
		int breaks = BuildAction.countBreaks((ServerLevel) bot.level(), design.plan.placements());
		Component ask = Component.translatable("butterbot.build.confirm", design.plan.name(), design.plan.placements().size(), breaks);
		bot.say(ask);
		player.sendSystemMessage(Component.literal("<" + bot.getName().getString() + "> ").withColor(0xFF9AC8).append(ask));
		bot.emote(ButterBotEntity.EMOTE_QUESTION);
		sendView(player, design);
	}

	/** A short yes or no to a waiting design; null when the line is about something else (a change, a question). */
	public static Boolean yesOrNo(String text) {
		if (text.trim().split("\\s+").length > 4) {
			return null;
		}
		return NO.matcher(text).find() ? Boolean.FALSE : YES.matcher(text).find() ? Boolean.TRUE : null;
	}

	/** Handles the owner's yes or no to a waiting design; true if the line was that answer. */
	static boolean answer(ButterBotEntity bot, ServerPlayer player, String lowerText) {
		Design design = of(bot);
		Boolean agreed = design == null || !design.waiting ? null : yesOrNo(lowerText);
		if (agreed == null) {
			return false;
		}
		if (agreed) {
			design.waiting = false;
			design.marks.clear();
			bot.assignBuildTask(design.plan);
			bot.emote(ButterBotEntity.EMOTE_CHECK);
			sendView(player, design);
		} else {
			BY_BOT.remove(bot.getUUID());
			bot.say(Component.translatable("butterbot.build.cancelled"));
			sendView(player, null);
		}
		return true;
	}

	/** The owner marked (or unmarked) a spot, on the hologram or on the built building. */
	public static void mark(ServerPlayer player, Optional<BlockPos> spot) {
		UUID botId = LAST_BOT.get(player.getUUID());
		Design design = botId == null ? null : BY_BOT.get(botId);
		if (spot.isPresent() && spot.get().distSqr(player.blockPosition()) > 128 * 128) {
			return;
		}
		if (design == null || !design.owner.equals(player.getUUID())) {
			player.sendOverlayMessage(Component.translatable("butterbot.build.no_design"));
			return;
		}
		if (spot.isEmpty()) {
			design.marks.clear();
			player.sendOverlayMessage(Component.translatable("butterbot.build.marks_cleared"));
		} else if (design.marks.remove(spot.get())) {
			player.sendOverlayMessage(Component.translatable("butterbot.build.unmarked"));
		} else if (design.marks.size() >= MAX_MARKS) {
			player.sendOverlayMessage(Component.translatable("butterbot.build.too_many_marks", MAX_MARKS));
			return;
		} else {
			design.marks.add(spot.get());
			player.sendSystemMessage(Component.translatable("butterbot.build.marked", design.marks.size(),
					blockName(design, player.level(), spot.get())));
		}
		sendView(player, design);
	}

	/** For the chat AI: the bot's last design and the owner's marks on it. */
	static String describe(ButterBotEntity bot) {
		Design design = of(bot);
		if (design == null) {
			return "";
		}
		StringBuilder out = new StringBuilder("Rancangan terakhirmu: \"" + design.plan.name() + "\" ("
				+ (design.waiting ? "menunggu persetujuan pemilik, pemilik sedang melihat hologramnya" : "sudah/sedang kamu bangun") + ").");
		if (!design.marks.isEmpty()) {
			out.append(" Pemilik menandai: ").append(marks(design, bot.level())).append('.');
		}
		out.append("\nKalau pemilik minta mengubah rancangan/bangunan itu (termasuk bagian yang ditandai), pakai [ubah: instruksi lengkap,")
				.append(" sebut nomor tandanya], bukan [bangun:].");
		return out.toString();
	}

	/** "#1 di (3,2,0) oak_planks, #2 ..." in the design's own coordinates, for the AI. */
	static String marks(Design design, net.minecraft.world.level.Level level) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < design.marks.size(); i++) {
			BlockPos local = toLocal(design, design.marks.get(i));
			out.append(i == 0 ? "" : ", ").append('#').append(i + 1).append(" di (").append(local.getX()).append(',').append(local.getY())
					.append(',').append(local.getZ()).append(") ").append(blockName(design, level, design.marks.get(i)));
		}
		return out.toString();
	}

	/** What is at a marked spot: the planned block while it is still a hologram, else the real one. */
	private static String blockName(Design design, net.minecraft.world.level.Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (design.waiting) {
			for (Blueprint.Placement placement : design.plan.placements()) {
				if (placement.pos().equals(pos)) {
					state = placement.state();
					break;
				}
			}
		}
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	private static BlockPos toLocal(Design design, BlockPos world) {
		Rotation back = switch (Blueprint.rotationFor(design.facing)) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> Blueprint.rotationFor(design.facing);
		};
		return world.subtract(design.origin).rotate(back);
	}

	/** Sends the owner the hologram (while the design waits) and their marks; null clears both. */
	static void sendView(ServerPlayer player, Design design) {
		if (!ServerPlayNetworking.canSend(player, Payloads.BuildView.TYPE)) {
			return;
		}
		List<Blueprint.Placement> ghosts = design == null || !design.waiting ? List.of() : design.plan.placements();
		long[] positions = new long[ghosts.size()];
		int[] states = new int[ghosts.size()];
		for (int i = 0; i < ghosts.size(); i++) {
			positions[i] = ghosts.get(i).pos().asLong();
			states[i] = Block.getId(ghosts.get(i).state());
		}
		long[] marks = design == null ? new long[0] : design.marks.stream().mapToLong(BlockPos::asLong).toArray();
		ServerPlayNetworking.send(player, new Payloads.BuildView(positions, states, marks));
	}

	private BuildReview() {
	}
}
