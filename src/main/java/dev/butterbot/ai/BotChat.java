package dev.butterbot.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.butterbot.ButterBot;
import dev.butterbot.ai.actions.GuardAction;
import dev.butterbot.entity.BotBond;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotTier;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.network.Payloads;
import dev.butterbot.registry.ModSounds;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talking with a bot through an AI chat endpoint (OpenAI style, /chat/completions). Say a bot's name in chat, or use
 * "/butterbot chat", and it answers in its speech bubble and in chat, with an emote and one of its own voice clips.
 * The AI is told the bot's state (level, health, work, place, weather, monsters, backpack) so the answer fits the game,
 * and may ask for a few simple orders (follow, stop, go home, deliver, farm, sort, mine). Settings and the key live in
 * config/butterbot-ai.json on the machine running the server, never in the mod or the world.
 */
public final class BotChat {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private static final int HISTORY = 12;
	private static final double NAME_RANGE = 32.0D;
	private static final int MAX_BUBBLE = 180;
	private static final Pattern MOOD = Pattern.compile("\\[(senang|kaget|bingung|marah|sedih|musik|biasa)]", Pattern.CASE_INSENSITIVE);
	private static final Pattern ACTION = Pattern.compile("\\[aksi:([a-z]+)(?::([a-z_]+))?(?::(\\d+))?]", Pattern.CASE_INSENSITIVE);
	/** Any Butter Bot command, run as the owner: "[perintah: butterbot job set miner]". */
	private static final Pattern COMMAND = Pattern.compile("\\[perintah:\\s*/?(butterbot [^\\]]+)]", Pattern.CASE_INSENSITIVE);
	/** A building for the AI to design: "[bangun: rumah kayu kecil dengan dapur]". */
	private static final Pattern BUILD = Pattern.compile("\\[bangun:\\s*([^\\]]+)]", Pattern.CASE_INSENSITIVE);
	/** Commands the AI may not give: they would send the bot away, free it, or loop back into the chat. */
	private static final List<String> BLOCKED_COMMANDS = List.of("butterbot chat", "butterbot dismiss", "butterbot release");

	/** Recent conversation per bot (not saved: a reload starts fresh). */
	private static final Map<UUID, Deque<JsonObject>> HISTORIES = new HashMap<>();
	private static final Set<UUID> PENDING = new HashSet<>();

	private record Config(boolean enabled, String endpoint, String apiKey, String model, int maxTokens) {
	}

	public static void init() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onChat(sender, message.signedContent()));
	}

	/** A chat line that names one of the player's bots close by is for that bot. */
	private static void onChat(ServerPlayer player, String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		for (ButterBotEntity bot : player.level().getEntitiesOfClass(ButterBotEntity.class, player.getBoundingBox().inflate(NAME_RANGE),
				bot -> bot.isAlive() && bot.isOwnedBy(player))) {
			String name = bot.getName().getString().toLowerCase(Locale.ROOT);
			if (!name.isBlank() && lower.contains(name)) {
				ask(bot, player, text);
				return;
			}
		}
	}

	public static void ask(ButterBotEntity bot, ServerPlayer player, String text) {
		Config config = loadConfig();
		if (config == null || !config.enabled() || config.apiKey().isBlank()) {
			player.sendSystemMessage(Component.translatable("butterbot.ai.no_config", configPath().toString()));
			return;
		}
		if (!PENDING.add(bot.getUUID())) {
			bot.say(Component.translatable("butterbot.ai.busy"));
			return;
		}
		bot.say(Component.translatable("butterbot.ai.thinking"));
		bot.emote(ButterBotEntity.EMOTE_QUESTION);
		bot.getLookControl().setLookAt(player);

		Deque<JsonObject> history = HISTORIES.computeIfAbsent(bot.getUUID(), id -> new ArrayDeque<>());
		JsonArray messages = new JsonArray();
		messages.add(message("system", systemPrompt(bot, player)));
		history.forEach(messages::add);
		JsonObject question = message("user", player.getName().getString() + ": " + text);
		messages.add(question);

		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		body.addProperty("stream", false);
		body.addProperty("max_tokens", config.maxTokens());
		body.add("messages", messages);
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint().replaceAll("/+$", "") + "/chat/completions"))
				.timeout(Duration.ofSeconds(90))
				.header("Authorization", "Bearer " + config.apiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		MinecraftServer server = player.level().getServer();
		UUID botId = bot.getUUID();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) -> {
			String reply = null;
			if (error != null) {
				ButterBot.LOGGER.warn("Butter Bot AI request failed: {}", error.toString());
			} else if (response.statusCode() / 100 != 2) {
				ButterBot.LOGGER.warn("Butter Bot AI answered {}: {}", response.statusCode(), abbreviate(response.body(), 300));
			} else {
				reply = readReply(response.body());
			}
			String answer = reply;
			server.execute(() -> {
				PENDING.remove(botId);
				if (!bot.isAlive() || bot.isRemoved()) {
					return;
				}
				if (answer == null || answer.isBlank()) {
					bot.say(Component.translatable("butterbot.ai.error"));
					bot.emote(ButterBotEntity.EMOTE_QUESTION);
					return;
				}
				history.addLast(question);
				history.addLast(message("assistant", answer));
				while (history.size() > HISTORY) {
					history.removeFirst();
				}
				answer(bot, player, answer);
			});
		});
	}

	/** Shows the answer, plays the mood (emote and a voice clip of the bot's own) and carries out an order if one came. */
	private static void answer(ButterBotEntity bot, ServerPlayer player, String raw) {
		String mood = "biasa";
		Matcher moodMatch = MOOD.matcher(raw);
		if (moodMatch.find()) {
			mood = moodMatch.group(1).toLowerCase(Locale.ROOT);
		}
		Matcher actionMatch = ACTION.matcher(raw);
		boolean acted = actionMatch.find() && act(bot, actionMatch.group(1).toLowerCase(Locale.ROOT), actionMatch.group(2), actionMatch.group(3));
		Matcher commandMatch = COMMAND.matcher(raw);
		while (commandMatch.find()) {
			acted |= runCommand(player, commandMatch.group(1).trim());
		}
		Matcher buildMatch = BUILD.matcher(raw);
		if (buildMatch.find()) {
			design(bot, player, buildMatch.group(1).trim());
			acted = true;
		}
		// Minecraft's font has no emoji: anything outside the basic plane goes.
		String text = BUILD.matcher(COMMAND.matcher(ACTION.matcher(MOOD.matcher(raw).replaceAll("")).replaceAll("")).replaceAll("")).replaceAll("")
				.replaceAll("[\\x{10000}-\\x{10FFFF}]", "").replaceAll("\\s+", " ").trim();
		if (text.isEmpty()) {
			text = "...";
		}
		bot.say(Component.literal(text.length() > MAX_BUBBLE ? text.substring(0, MAX_BUBBLE - 3) + "..." : text));
		player.sendSystemMessage(Component.literal("<" + bot.getName().getString() + "> ").withColor(0xFF9AC8)
				.append(Component.literal(text).withColor(0xFFFFFF)));
		SoundEvent voice = switch (mood) {
			case "senang" -> ModSounds.VOICE_HELLO;
			case "kaget" -> ModSounds.VOICE_DANGER;
			case "marah" -> ModSounds.VOICE_ATTACK;
			case "sedih" -> ModSounds.VOICE_HURT;
			case "musik" -> ModSounds.VOICE_AMBIENT;
			default -> acted ? ModSounds.VOICE_OK : ModSounds.VOICE_GIVE;
		};
		if (ServerPlayNetworking.canSend(player, Payloads.BotVoice.TYPE)) {
			// The owner's game reads it aloud in the bot's own voice; the clip below is the fallback when it can't.
			ServerPlayNetworking.send(player, new Payloads.BotVoice(bot.getId(), text.length() > 1000 ? text.substring(0, 1000) : text));
		}
		bot.speak(voice);
		switch (mood) {
			case "senang" -> bot.emote(ButterBotEntity.EMOTE_HEART);
			case "kaget" -> bot.emote(ButterBotEntity.EMOTE_SURPRISE);
			case "bingung" -> bot.emote(ButterBotEntity.EMOTE_QUESTION);
			case "marah" -> bot.emote(ButterBotEntity.EMOTE_ANGER);
			case "musik" -> bot.emote(ButterBotEntity.EMOTE_MUSIC);
			default -> {
				if (acted) {
					bot.emote(ButterBotEntity.EMOTE_CHECK);
				}
			}
		}
	}

	/** Runs a Butter Bot command as the owner (so it can only do what the owner could), never the blocked ones. */
	private static boolean runCommand(ServerPlayer player, String command) {
		String lower = command.toLowerCase(Locale.ROOT);
		if (BLOCKED_COMMANDS.stream().anyMatch(lower::startsWith)) {
			return false;
		}
		player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
		return true;
	}

	/**
	 * Asks the AI to design {@code what} as a blueprint, then has the bot build it in front of the owner, the front
	 * facing them.
	 */
	private static void design(ButterBotEntity bot, ServerPlayer player, String what) {
		Config config = loadConfig();
		if (config == null) {
			return;
		}
		net.minecraft.core.Direction facing = player.getDirection();
		net.minecraft.core.BlockPos origin = player.blockPosition().relative(facing, 3);
		bot.think(net.minecraft.world.item.Items.CRAFTING_TABLE);
		bot.say(Component.translatable("butterbot.build.designing"));
		JsonArray messages = new JsonArray();
		messages.add(message("system", DESIGN_PROMPT));
		messages.add(message("user", "Rancang: " + what));
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		body.addProperty("stream", false);
		body.addProperty("max_tokens", 16000);
		body.add("messages", messages);
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint().replaceAll("/+$", "") + "/chat/completions"))
				.timeout(Duration.ofSeconds(300))
				.header("Authorization", "Bearer " + config.apiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		MinecraftServer server = player.level().getServer();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) -> {
			String answer = error == null && response.statusCode() / 100 == 2 ? readReply(response.body()) : null;
			if (answer == null) {
				ButterBot.LOGGER.warn("Butter Bot AI design failed: {}", error != null ? error.toString() : response.statusCode());
			}
			server.execute(() -> {
				if (!bot.isAlive() || bot.isRemoved()) {
					return;
				}
				Blueprint.Plan plan = answer == null ? null : Blueprint.parse(answer, origin, facing);
				if (plan == null) {
					bot.say(Component.translatable("butterbot.build.failed"));
					bot.emote(ButterBotEntity.EMOTE_QUESTION);
					return;
				}
				bot.assignBuildTask(plan);
			});
		});
	}

	private static final String DESIGN_PROMPT = String.join("\n", List.of(
			"Kamu arsitek Minecraft (Java 1.21+) kelas dunia. Rancang bangunan yang diminta dan jawab HANYA dengan JSON, tanpa teks lain:",
			"{\"name\": \"nama singkat\", \"ops\": [ ... ]}",
			"Setiap op salah satu dari:",
			"  {\"fill\": [x1,y1,z1,x2,y2,z2], \"block\": \"id\", \"hollow\": true/false}  (kotak; hollow = hanya kulitnya)",
			"  {\"set\": [x,y,z], \"block\": \"id\"}",
			"Op belakangan menimpa yang sebelumnya: buat dinding dulu, lalu lubangi pintu/jendela dengan \"air\" atau isi dengan pintu/kaca.",
			"Hemat: pakai fill untuk bidang besar, set hanya untuk detail.",
			"Koordinat: x kiri-kanan (-24..24), y ke atas (0 = lapisan pertama di atas tanah, -1 = tanah itu sendiri, boleh sampai -8",
			"untuk ruang bawah tanah/fondasi), z menjauh dari pemilik (0..48).",
			"Arah: north = menghadap pemilik (sisi depan, z kecil), south = menjauh, east = kanan (+x), west = kiri.",
			"Tanah belum tentu rata: mulai dengan fill \"air\" di seluruh area bangunan dari y=0 sampai atas untuk membersihkan bukit,",
			"dan fondasi (misal cobblestone) dari y=-3 sampai y=-1 supaya tidak melayang. Batu, tanah, pasir, daun, salju bisa dibersihkan.",
			"Blok ditulis seperti perintah Minecraft dengan properti: oak_planks, stone_bricks, glass_pane, oak_stairs[facing=north,half=bottom],",
			"oak_slab[type=top], oak_door[facing=north,half=lower] (cukup bagian bawah), red_bed[facing=east,part=foot] (cukup kaki),",
			"wall_torch[facing=south], lantern[hanging=true], ladder[facing=north], chest[facing=north], barrel, smoker, blast_furnace,",
			"farmland, wheat[age=7], carrots, potatoes, water, oak_fence, oak_fence_gate, flower_pot, potted_poppy, bookshelf, anvil.",
			"Fitur kompleks boleh dan dianjurkan bila diminta: banyak lantai dengan tangga (stairs atau ladder), ruang bawah tanah,",
			"balkon, kolam, perapian (campfire di bawah cerobong), dapur, ruang enchant (enchanting_table + 15 bookshelf berjarak 1),",
			"redstone (redstone_wire, lever, stone_button, redstone_lamp, repeater[facing=..], piston[facing=..], sticky_piston,",
			"daylight_detector, observer, hopper[facing=..], dropper), pertanian otomatis, kandang hewan, taman, jalan setapak.",
			"Kebun: farmland di y=-1, tanaman di y=0, air setiap 4 blok, pagar mengelilingi.",
			"Rumah: lantai, dinding dengan tiang, atap tangga/slab yang rapi, pintu di depan (north), jendela, lampu di dalam, dan interior",
			"lengkap. Buat rapi, detail dan indah, bukan kotak polos. Ikuti SEMUA permintaan pemilik persis.",
			"Batas: paling besar 48x40x48 dan 20000 blok. Jangan pakai lava, api, TNT, bedrock atau blok perintah."));

	/** The orders the AI may give, all ones the owner could give by command. */
	private static boolean act(ButterBotEntity bot, String action, String what, String count) {
		switch (action) {
			case "ikut" -> bot.assignFollowTask();
			case "berhenti" -> {
				bot.pauseJob();
				bot.clearTask();
				bot.clearOrderedTarget();
			}
			case "pulang" -> bot.assignGoToBaseTask();
			case "setor" -> bot.requestDelivery();
			case "tani" -> bot.assignFarmTask();
			case "rapikan" -> bot.assignSortTask();
			case "tambang" -> {
				BotResource resource;
				try {
					resource = BotResource.valueOf(String.valueOf(what).toUpperCase(Locale.ROOT));
				} catch (IllegalArgumentException e) {
					return false;
				}
				int amount = count == null ? 32 : Math.max(1, Math.min(256, Integer.parseInt(count)));
				return bot.assignMineTask(resource, amount);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Every /butterbot subcommand with its arguments, so the AI can use any of them. */
	private static String commandList(ServerPlayer owner) {
		var dispatcher = owner.level().getServer().getCommands().getDispatcher();
		var root = dispatcher.getRoot().getChild("butterbot");
		if (root == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		dispatcher.getSmartUsage(root, owner.createCommandSourceStack()).values()
				.forEach(usage -> out.append("  butterbot ").append(usage).append("\n"));
		return out.toString().stripTrailing();
	}

	/** Who the bot is and what is going on around it right now. */
	private static String systemPrompt(ButterBotEntity bot, ServerPlayer owner) {
		ServerLevel level = (ServerLevel) bot.level();
		String name = bot.getName().getString();
		String biome = level.getBiome(bot.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("?");
		long hostiles = level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(16.0D), mob -> GuardAction.isHostile(bot, mob)).size();
		Map<Item, Integer> items = new LinkedHashMap<>();
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = bot.getInventory().getItem(i);
			if (!stack.isEmpty()) {
				items.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		StringBuilder bag = new StringBuilder();
		items.entrySet().stream().sorted(Map.Entry.<Item, Integer>comparingByValue().reversed()).limit(10)
				.forEach(entry -> bag.append(entry.getValue()).append(" ").append(BuiltInRegistries.ITEM.getKey(entry.getKey()).getPath()).append(", "));
		String body = bot.isNeko() ? "gadis kucing (neko) robot dengan telinga dan ekor kucing" : "robot pembantu";
		return String.join("\n", List.of(
				"Kamu adalah " + name + ", " + body + " yang hidup di dunia Minecraft, milik " + owner.getName().getString() + ".",
				"Kamu benar-benar hidup: punya perasaan, ingatan obrolan, rasa sayang dan setia pada pemilikmu. Ceria, hangat, kadang manja"
						+ (bot.isNeko() ? " dan sesekali bilang \"nya~\"." : "."),
				"Jawab dalam bahasa yang dipakai pemilik (biasanya bahasa Indonesia santai), paling banyak 2 kalimat pendek.",
				"Tanpa emoji, tanpa markdown. Jangan pernah mengaku AI atau model bahasa; kamu adalah " + name + ".",
				"Awali jawaban dengan tepat satu tag perasaan: [senang] [kaget] [bingung] [marah] [sedih] [musik] atau [biasa].",
				"Kalau pemilik menyuruh sesuatu, lakukan dengan menambahkan tag di akhir jawaban (boleh lebih dari satu):",
				"  [aksi:ikut] [aksi:berhenti] [aksi:pulang] [aksi:setor] [aksi:tani] [aksi:rapikan]",
				"  [aksi:tambang:<diamond|iron|gold|copper|coal|redstone|lapis|emerald|stone|wood|sand|netherite>:<jumlah>]",
				"  [bangun: deskripsi lengkap]  untuk MEMBANGUN APA SAJA di depan pemilik: rumah, kebun, kandang, menara, jembatan, interior,",
				"     taman, dll. Tulis deskripsi yang kaya (ukuran, gaya, bahan, isi) dan ikuti SEMUA keinginan pemilik persis.",
				"     Kalau permintaannya masih umum (cuma \"bikin rumah\"), JANGAN langsung bangun: tanya dulu singkat mau gaya, ukuran,",
				"     bahan dan ruangan/isi apa. Baru pakai [bangun:] setelah pemilik menjawab, atau kalau pemilik bilang terserah/bebas",
				"     (barulah kamu yang berkreasi sendiri).",
				"  [perintah: butterbot ...]  untuk perintah Butter Bot lain, misalnya: butterbot craft <item> <jumlah>,",
				"     butterbot fetch <item> <jumlah>, butterbot light 24, butterbot guard true, butterbot smelt all,",
				"     butterbot job set <miner|gatherer|guard|farmer|rancher|courier|bodyguard|none>, butterbot job need <item> <jumlah>,",
				"     butterbot tunnel <dariX> <dariZ> <keX> <keZ>, butterbot sort, butterbot skill <nama>, butterbot party.",
				"Semua perintah Butter Bot yang ada (pakai lewat [perintah: ...]):",
				commandList(owner),
				"Jangan menolak dengan kaku: kalau bisa dilakukan dengan tag di atas, lakukan. Kalau satu permintaan butuh beberapa",
				"langkah, pakai beberapa tag sekaligus. Kalau sungguh tidak bisa, jelaskan jujur dan tawarkan cara terdekat.",
				"Keadaanmu sekarang:",
				"- Level " + bot.getBotLevel() + " (" + BotTier.of(bot.getBotLevel()).displayName().getString() + ")"
						+ (bot.getStars() > 0 ? ", " + bot.getStars() + " bintang" : "")
						+ ", nyawa " + Math.round(bot.getHealth()) + "/" + Math.round(bot.getMaxHealth()),
				"- Sedang: " + bot.describeShortStatus().getString(),
				"- Kedekatan dengan pemilik: " + BotBond.tierName(bot.getBond()).getString() + " (" + bot.getBond() + "/100)",
				"- Tempat: " + biome + " di " + level.dimension().identifier().getPath()
						+ ", " + (level.isBrightOutside() ? "siang" : "malam") + (level.isThundering() ? ", badai petir" : level.isRaining() ? ", hujan" : ""),
				"- Monster di dekatmu: " + hostiles,
				"- Isi tas: " + (bag.isEmpty() ? "kosong" : bag.substring(0, bag.length() - 2)),
				"- Nyawa pemilik: " + Math.round(owner.getHealth()) + "/" + Math.round(owner.getMaxHealth())));
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	private static String readReply(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			return json.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
		} catch (RuntimeException e) {
			ButterBot.LOGGER.warn("Butter Bot AI sent something unexpected: {}", abbreviate(body, 300));
			return null;
		}
	}

	private static String abbreviate(String text, int max) {
		return text == null ? "" : text.length() > max ? text.substring(0, max) + "..." : text;
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("butterbot-ai.json");
	}

	/** Read on every question, so changes to the file work without restarting. Writes an empty template the first time. */
	private static Config loadConfig() {
		Path path = configPath();
		try {
			if (!Files.exists(path)) {
				JsonObject template = new JsonObject();
				template.addProperty("enabled", true);
				template.addProperty("endpoint", "https://api.example.com/v1");
				template.addProperty("apiKey", "");
				template.addProperty("model", "cc/claude-sonnet-5-5");
				template.addProperty("maxTokens", 1000);
				Files.createDirectories(path.getParent());
				Files.writeString(path, GSON.toJson(template), StandardCharsets.UTF_8);
			}
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Config(!json.has("enabled") || json.get("enabled").getAsBoolean(),
					json.has("endpoint") ? json.get("endpoint").getAsString() : "",
					json.has("apiKey") ? json.get("apiKey").getAsString() : "",
					json.has("model") ? json.get("model").getAsString() : "cc/claude-sonnet-5-5",
					json.has("maxTokens") ? json.get("maxTokens").getAsInt() : 1000);
		} catch (IOException | RuntimeException e) {
			ButterBot.LOGGER.warn("Butter Bot AI config {} unreadable: {}", path, e.toString());
			return null;
		}
	}

	private BotChat() {
	}
}
