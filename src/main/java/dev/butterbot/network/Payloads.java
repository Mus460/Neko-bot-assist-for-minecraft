package dev.butterbot.network;

import dev.butterbot.ButterBot;
import dev.butterbot.lua.LuaProgram;
import dev.butterbot.skin.SkinLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All custom packets used by the mod. */
public final class Payloads {

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
		return new CustomPacketPayload.Type<>(ButterBot.id(name));
	}

	private static Optional<BlockPos> readOptionalPos(RegistryFriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(buf.readBlockPos()) : Optional.empty();
	}

	private static void writeOptionalPos(RegistryFriendlyByteBuf buf, Optional<BlockPos> pos) {
		buf.writeBoolean(pos.isPresent());
		pos.ifPresent(buf::writeBlockPos);
	}

	private static Optional<UUID> readOptionalUuid(RegistryFriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
	}

	private static void writeOptionalUuid(RegistryFriendlyByteBuf buf, Optional<UUID> uuid) {
		buf.writeBoolean(uuid.isPresent());
		uuid.ifPresent(buf::writeUUID);
	}

	// ================================================================ client -> server

	/** Asks for the list of the player's bots, around a Bot Base or around the player. */
	public record RequestBots(Optional<BlockPos> base, Optional<UUID> focus) implements CustomPacketPayload {
		public static final Type<RequestBots> TYPE = typeOf("request_bots");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestBots> CODEC = StreamCodec.of(
				(buf, p) -> {
					writeOptionalPos(buf, p.base);
					writeOptionalUuid(buf, p.focus);
				},
				buf -> new RequestBots(readOptionalPos(buf), readOptionalUuid(buf)));

		@Override
		public Type<RequestBots> type() {
			return TYPE;
		}
	}

	/** A command for one bot. Interpretation of {@code text} and {@code args} depends on the action. */
	public record BotCommand(Optional<BlockPos> base, UUID bot, int action, String text, int[] args) implements CustomPacketPayload {
		public static final int SUMMON = 0;
		public static final int STOP = 1;
		public static final int STATUS = 2;
		public static final int MINE = 3;
		public static final int CRAFT = 4;
		public static final int TUNNEL = 5;
		public static final int RELEASE = 6;
		public static final int SET_NEKO = 7;
		public static final int FOLLOW = 8;
		public static final int DELIVER = 9;
		public static final int BACKPACK = 10;
		public static final int DELIVERY_MODE = 11;
		public static final int RENAME = 12;
		public static final int SET_SKIN = 13;
		public static final int LINK_BASE = 14;
		public static final int GUARD = 15;
		public static final int AUTO_TORCH = 16;
		public static final int FEED = 17;
		public static final int SMELT_MODE = 18;
		public static final int FARM = 19;
		public static final int SORT = 20;
		public static final int FIND = 21;
		public static final int LIGHT = 22;
		public static final int REFILL = 23;
		/** Takes off the custom skin and puts on built-in skin {@code args[0]}. */
		public static final int DEFAULT_SKIN = 24;
		/** Job {@code args[0]} (a {@code BotJob} ordinal). */
		public static final int JOB_SET = 25;
		/** {@code args[0]} 1 pauses the job, 0 resumes it. */
		public static final int JOB_PAUSE = 26;
		/** Keep {@code args[0]} of item {@code text} in the job chest (0 takes it off the list). */
		public static final int JOB_NEED = 27;
		public static final int JOB_CLEAR_LIST = 28;
		/** No-dig zone radius {@code args[0]}. */
		public static final int JOB_ZONE = 29;
		/** Patrol/farm radius {@code args[0]}. */
		public static final int JOB_RADIUS = 30;
		/** Work spot at {@code args[0..2]}, or the player's spot without args. */
		public static final int JOB_CENTER = 31;
		/** Job chest at {@code args[0..2]}, or no chest without args. */
		public static final int JOB_CHEST = 32;
		/** Spend a skill point on skill {@code args[0]} (a {@code BotSkill} ordinal). */
		public static final int SKILL_UP = 33;
		/** Forget all skills and get the points back. */
		public static final int SKILL_RESET = 34;
		/** Rancher: keep {@code args[0]} of the kind of animal {@code text} (an entity type id; empty: every kind). */
		public static final int JOB_HERD = 35;
		/** Rancher: {@code args[0]} 1 lets it cull animals over the limit, 0 doesn't. */
		public static final int JOB_BUTCHER = 36;
		/** Courier: adds the source chest at {@code args[0..2]}, removes source number {@code args[0]}, or (no args) all. */
		public static final int JOB_SOURCE = 37;
		/** Courier: {@code args[0]} 1 smelts raw ores on the way, 0 doesn't. */
		public static final int JOB_SMELT = 38;
		/** Bodyguard: guards the bot with the UUID in {@code text} (empty: no one). */
		public static final int JOB_WARD = 39;

		public static final Type<BotCommand> TYPE = typeOf("bot_command");
		public static final StreamCodec<RegistryFriendlyByteBuf, BotCommand> CODEC = StreamCodec.of(
				(buf, p) -> {
					writeOptionalPos(buf, p.base);
					buf.writeUUID(p.bot);
					buf.writeVarInt(p.action);
					buf.writeUtf(p.text, 256);
					buf.writeVarIntArray(p.args);
				},
				buf -> new BotCommand(readOptionalPos(buf), buf.readUUID(), buf.readVarInt(), buf.readUtf(256),
						buf.readVarIntArray(16)));

		public static BotCommand of(Optional<BlockPos> base, UUID bot, int action) {
			return new BotCommand(base, bot, action, "", new int[0]);
		}

		public int arg(int index, int fallback) {
			return index < args.length ? args[index] : fallback;
		}

		@Override
		public Type<BotCommand> type() {
			return TYPE;
		}
	}

	/** Saves a Lua program on a bot and optionally starts it. */
	public record SaveScript(Optional<BlockPos> base, UUID bot, String script, boolean run) implements CustomPacketPayload {
		public static final Type<SaveScript> TYPE = typeOf("save_script");
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveScript> CODEC = StreamCodec.of(
				(buf, p) -> {
					writeOptionalPos(buf, p.base);
					buf.writeUUID(p.bot);
					buf.writeUtf(p.script, LuaProgram.MAX_SCRIPT_LENGTH);
					buf.writeBoolean(p.run);
				},
				buf -> new SaveScript(readOptionalPos(buf), buf.readUUID(), buf.readUtf(LuaProgram.MAX_SCRIPT_LENGTH), buf.readBoolean()));

		@Override
		public Type<SaveScript> type() {
			return TYPE;
		}
	}

	/** Uploads a PNG skin; the id is the SHA-1 of the bytes. Sent as a large (split) payload. */
	public record SkinUpload(String skinId, byte[] data) implements CustomPacketPayload {
		public static final Type<SkinUpload> TYPE = typeOf("skin_upload");
		public static final StreamCodec<RegistryFriendlyByteBuf, SkinUpload> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.skinId, 64);
					buf.writeByteArray(p.data);
				},
				buf -> new SkinUpload(buf.readUtf(64), buf.readByteArray(SkinLimits.MAX_BYTES)));

		@Override
		public Type<SkinUpload> type() {
			return TYPE;
		}
	}

	/** Asks the server for a skin this client has not seen yet. */
	public record SkinFetch(String skinId) implements CustomPacketPayload {
		public static final Type<SkinFetch> TYPE = typeOf("skin_fetch");
		public static final StreamCodec<RegistryFriendlyByteBuf, SkinFetch> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeUtf(p.skinId, 64),
				buf -> new SkinFetch(buf.readUtf(64)));

		@Override
		public Type<SkinFetch> type() {
			return TYPE;
		}
	}

	/**
	 * A shortcut key was pressed. {@code block} and {@code entity} describe what the player was looking
	 * at for {@link #CONTEXT} (entity is -1 when it is not an entity).
	 */
	public record Hotkey(int action, Optional<BlockPos> block, int entity) implements CustomPacketPayload {
		public static final int CALL_ALL = 1;
		public static final int HOME_ALL = 2;
		public static final int STOP_ALL = 3;
		public static final int DELIVER_ALL = 4;
		public static final int BACKPACK = 5;
		public static final int GUARD_TOGGLE = 6;
		public static final int CONTEXT = 7;
		public static final int LIGHT = 8;
		public static final int FETCH_HELD = 9;
		/** Pauses every working bot, or puts them all back to work if none is working. */
		public static final int JOB_TOGGLE = 10;
		/** Shows what the bots around are doing, with their targets and dig routes (or hides it again). */
		public static final int DEBUG = 11;

		public static final Type<Hotkey> TYPE = typeOf("hotkey");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hotkey> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.action);
					writeOptionalPos(buf, p.block);
					buf.writeVarInt(p.entity + 1);
				},
				buf -> new Hotkey(buf.readVarInt(), readOptionalPos(buf), buf.readVarInt() - 1));

		@Override
		public Type<Hotkey> type() {
			return TYPE;
		}
	}

	// ================================================================ server -> client

	/**
	 * One line of the on-screen bot list. {@code icon} is the id of an item that stands for what the bot
	 * is doing (empty when idle); {@code distance} is -1 when the bot is in another dimension.
	 */
	public record HudBot(String name, float health, float maxHealth, Component status, String icon, int distance,
						 int items, int capacity) {

		static void write(RegistryFriendlyByteBuf buf, HudBot bot) {
			buf.writeUtf(bot.name, 64);
			buf.writeFloat(bot.health);
			buf.writeFloat(bot.maxHealth);
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, bot.status);
			buf.writeUtf(bot.icon, 128);
			buf.writeVarInt(bot.distance + 1);
			buf.writeVarInt(bot.items);
			buf.writeVarInt(bot.capacity);
		}

		static HudBot read(RegistryFriendlyByteBuf buf) {
			return new HudBot(buf.readUtf(64), buf.readFloat(), buf.readFloat(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
					buf.readUtf(128), buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt());
		}
	}

	/** The player's bots for the on-screen list, sent once a second. */
	public record HudUpdate(List<HudBot> bots) implements CustomPacketPayload {
		public static final Type<HudUpdate> TYPE = typeOf("hud_update");
		public static final StreamCodec<RegistryFriendlyByteBuf, HudUpdate> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.bots.size());
					for (HudBot bot : p.bots) {
						HudBot.write(buf, bot);
					}
				},
				buf -> {
					int size = Math.min(buf.readVarInt(), 16);
					List<HudBot> bots = new ArrayList<>(size);
					for (int i = 0; i < size; i++) {
						bots.add(HudBot.read(buf));
					}
					return new HudUpdate(bots);
				});

		@Override
		public Type<HudUpdate> type() {
			return TYPE;
		}
	}

	/** One line of a job's list: {@code have} is -1 while the chest hasn't been looked at. */
	public record NeedView(String item, int target, int have) {
	}

	/** One kind of animal on a rancher's panel: {@code count} in the area (-1 while it isn't loaded) and its limit. */
	public record HerdView(String kind, int count, int limit, boolean custom) {
	}

	/** A bot's job for the control panel. */
	public record JobView(int job, boolean paused, Optional<BlockPos> chest, boolean chestOk, Optional<BlockPos> center,
						  int zone, int radius, int stat, int herd, boolean butcher, List<NeedView> needs, List<HerdView> herds,
						  List<BlockPos> sources, boolean smelt, Optional<UUID> ward, String wardName) {
		public static final JobView NONE = new JobView(0, false, Optional.empty(), false, Optional.empty(), 200, 30, 0, 10, false,
				List.of(), List.of(), List.of(), true, Optional.empty(), "");

		static void write(RegistryFriendlyByteBuf buf, JobView v) {
			buf.writeVarInt(v.job);
			buf.writeBoolean(v.paused);
			writeOptionalPos(buf, v.chest);
			buf.writeBoolean(v.chestOk);
			writeOptionalPos(buf, v.center);
			buf.writeVarInt(v.zone);
			buf.writeVarInt(v.radius);
			buf.writeVarInt(v.stat);
			buf.writeVarInt(v.herd);
			buf.writeBoolean(v.butcher);
			buf.writeVarInt(v.needs.size());
			for (NeedView need : v.needs) {
				buf.writeUtf(need.item, 128);
				buf.writeVarInt(need.target);
				buf.writeVarInt(need.have + 1);
			}
			buf.writeVarInt(v.herds.size());
			for (HerdView herd : v.herds) {
				buf.writeUtf(herd.kind, 64);
				buf.writeVarInt(herd.count + 1);
				buf.writeVarInt(herd.limit);
				buf.writeBoolean(herd.custom);
			}
			buf.writeVarInt(v.sources.size());
			for (BlockPos source : v.sources) {
				buf.writeBlockPos(source);
			}
			buf.writeBoolean(v.smelt);
			buf.writeBoolean(v.ward.isPresent());
			v.ward.ifPresent(buf::writeUUID);
			buf.writeUtf(v.wardName, 64);
		}

		static JobView read(RegistryFriendlyByteBuf buf) {
			int job = buf.readVarInt();
			boolean paused = buf.readBoolean();
			Optional<BlockPos> chest = readOptionalPos(buf);
			boolean chestOk = buf.readBoolean();
			Optional<BlockPos> center = readOptionalPos(buf);
			int zone = buf.readVarInt();
			int radius = buf.readVarInt();
			int stat = buf.readVarInt();
			int herd = buf.readVarInt();
			boolean butcher = buf.readBoolean();
			int size = Math.min(buf.readVarInt(), 16);
			List<NeedView> needs = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				needs.add(new NeedView(buf.readUtf(128), buf.readVarInt(), buf.readVarInt() - 1));
			}
			int kinds = Math.min(buf.readVarInt(), 16);
			List<HerdView> herds = new ArrayList<>(kinds);
			for (int i = 0; i < kinds; i++) {
				herds.add(new HerdView(buf.readUtf(64), buf.readVarInt() - 1, buf.readVarInt(), buf.readBoolean()));
			}
			int sourceCount = Math.min(buf.readVarInt(), 16);
			List<BlockPos> sources = new ArrayList<>(sourceCount);
			for (int i = 0; i < sourceCount; i++) {
				sources.add(buf.readBlockPos());
			}
			boolean smelt = buf.readBoolean();
			Optional<UUID> ward = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
			String wardName = buf.readUtf(64);
			return new JobView(job, paused, chest, chestOk, center, zone, radius, stat, herd, butcher, needs, herds,
					sources, smelt, ward, wardName);
		}
	}

	/** Snapshot of one bot for the control panel. */
	public record BotEntry(UUID id, String name, Component status, boolean neko, int task, String skinId,
						   String script, int deliveryMode, int itemCount, BlockPos pos, boolean hasBase, float health,
						   int level, int workXp, int storedXp, boolean guard, boolean autoTorch, boolean feedOwner,
						   int smeltMode, boolean autoRefill, int skinVariant, JobView jobView, float maxHealth, int backpackSlots,
						   int[] skills, int[] mastery, int[] lifetime) {

		private static final int MAX_ARRAY = 64;

		static void write(RegistryFriendlyByteBuf buf, BotEntry e) {
			buf.writeUUID(e.id);
			buf.writeUtf(e.name, 64);
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, e.status);
			buf.writeBoolean(e.neko);
			buf.writeVarInt(e.task);
			buf.writeUtf(e.skinId, 64);
			buf.writeUtf(e.script, LuaProgram.MAX_SCRIPT_LENGTH);
			buf.writeVarInt(e.deliveryMode);
			buf.writeVarInt(e.itemCount);
			buf.writeBlockPos(e.pos);
			buf.writeBoolean(e.hasBase);
			buf.writeFloat(e.health);
			buf.writeVarInt(e.level);
			buf.writeVarInt(e.workXp);
			buf.writeVarInt(e.storedXp);
			buf.writeBoolean(e.guard);
			buf.writeBoolean(e.autoTorch);
			buf.writeBoolean(e.feedOwner);
			buf.writeVarInt(e.smeltMode);
			buf.writeBoolean(e.autoRefill);
			buf.writeVarInt(e.skinVariant);
			JobView.write(buf, e.jobView);
			buf.writeFloat(e.maxHealth);
			buf.writeVarInt(e.backpackSlots);
			buf.writeVarIntArray(e.skills);
			buf.writeVarIntArray(e.mastery);
			buf.writeVarIntArray(e.lifetime);
		}

		static BotEntry read(RegistryFriendlyByteBuf buf) {
			return new BotEntry(buf.readUUID(), buf.readUtf(64), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf),
					buf.readBoolean(), buf.readVarInt(), buf.readUtf(64), buf.readUtf(LuaProgram.MAX_SCRIPT_LENGTH),
					buf.readVarInt(), buf.readVarInt(), buf.readBlockPos(), buf.readBoolean(), buf.readFloat(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
					buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), JobView.read(buf), buf.readFloat(), buf.readVarInt(),
					buf.readVarIntArray(MAX_ARRAY), buf.readVarIntArray(MAX_ARRAY), buf.readVarIntArray(MAX_ARRAY));
		}
	}

	public record BotList(Optional<BlockPos> base, List<BotEntry> bots) implements CustomPacketPayload {
		public static final Type<BotList> TYPE = typeOf("bot_list");
		public static final StreamCodec<RegistryFriendlyByteBuf, BotList> CODEC = StreamCodec.of(
				(buf, p) -> {
					writeOptionalPos(buf, p.base);
					buf.writeVarInt(p.bots.size());
					for (BotEntry entry : p.bots) {
						BotEntry.write(buf, entry);
					}
				},
				buf -> {
					Optional<BlockPos> base = readOptionalPos(buf);
					int size = Math.min(buf.readVarInt(), 256);
					List<BotEntry> bots = new ArrayList<>(size);
					for (int i = 0; i < size; i++) {
						bots.add(BotEntry.read(buf));
					}
					return new BotList(base, bots);
				});

		@Override
		public Type<BotList> type() {
			return TYPE;
		}
	}

	/** Tells the client to open the control panel focused on one bot (after right-clicking it). */
	public record OpenBotScreen(UUID bot) implements CustomPacketPayload {
		public static final Type<OpenBotScreen> TYPE = typeOf("open_bot_screen");
		public static final StreamCodec<ByteBuf, OpenBotScreen> CODEC = StreamCodec.of(
				(buf, p) -> net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, p.bot),
				buf -> new OpenBotScreen(net.minecraft.core.UUIDUtil.STREAM_CODEC.decode(buf)));

		@Override
		public Type<OpenBotScreen> type() {
			return TYPE;
		}
	}

	/** Delivers a skin PNG to a client. Sent as a large (split) payload. */
	public record SkinData(String skinId, byte[] data) implements CustomPacketPayload {
		public static final Type<SkinData> TYPE = typeOf("skin_data");
		public static final StreamCodec<RegistryFriendlyByteBuf, SkinData> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.skinId, 64);
					buf.writeByteArray(p.data);
				},
				buf -> new SkinData(buf.readUtf(64), buf.readByteArray(SkinLimits.MAX_BYTES)));

		@Override
		public Type<SkinData> type() {
			return TYPE;
		}
	}

	private Payloads() {
	}
}
