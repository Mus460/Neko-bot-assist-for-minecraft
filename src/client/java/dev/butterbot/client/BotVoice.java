package dev.butterbot.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.butterbot.ButterBot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Reads AI answers aloud in the bot's own voice, client side. The text goes to a small voice program on this machine
 * (butterbot-voice: a Indonesian speech voice turned into the bot's voice with OpenVoice), which sends back a WAV that
 * is played louder the closer the bot is. The program is started on the first answer and stopped with the game.
 * Settings live in config/butterbot-voice.json; without the program the bot simply keeps its usual voice clip.
 */
public final class BotVoice {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private static final double HEAR_RANGE = 32.0D;
	private static Process server;

	/** {@code style}: "jepang" (a Japanese voice speaking Indonesian) or "indonesia". */
	/** {@code enabled}: answers read aloud; {@code mic}: push to talk. Both use the same voice program. */
	private record Config(boolean enabled, boolean mic, String url, String python, String script, String style) {
	}

	public static void speak(int entityId, String text) {
		Config config = loadConfig();
		if (config == null || !config.enabled()) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			byte[] wav = request(config, text, true);
			if (wav != null) {
				Minecraft.getInstance().execute(() -> play(entityId, wav));
			}
		});
	}

	private static byte[] request(Config config, String text, boolean mayStart) {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		body.addProperty("style", config.style());
		return post(config, "/tts", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", mayStart);
	}

	/** POSTs to the voice program, starting it first if it is not running. Null on any failure (logged). */
	private static byte[] post(Config config, String path, byte[] data, String type, boolean mayStart) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.url().replaceAll("/+$", "") + path))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", type)
				.POST(HttpRequest.BodyPublishers.ofByteArray(data))
				.build();
		try {
			HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() == 200) {
				return response.body();
			}
			ButterBot.LOGGER.warn("Butter Bot voice server answered {}", response.statusCode());
		} catch (ConnectException e) {
			if (mayStart && startServer(config)) {
				return post(config, path, data, type, false);
			}
			ButterBot.LOGGER.warn("Butter Bot voice server not reachable at {}", config.url());
		} catch (Exception e) {
			ButterBot.LOGGER.warn("Butter Bot voice failed: {}", e.toString());
		}
		return null;
	}

	/** Starts the voice program and waits (up to a minute: loading the model takes a while) until it answers. */
	private static synchronized boolean startServer(Config config) {
		if (server == null || !server.isAlive()) {
			if (!new File(config.python()).isFile() || !new File(config.script()).isFile()) {
				ButterBot.LOGGER.warn("Butter Bot voice program not found ({}, {})", config.python(), config.script());
				return false;
			}
			try {
				File dir = new File(config.script()).getParentFile();
				server = new ProcessBuilder(config.python(), config.script())
						.directory(dir)
						.redirectErrorStream(true)
						.redirectOutput(new File(dir, "server.log"))
						.start();
			} catch (Exception e) {
				ButterBot.LOGGER.warn("Butter Bot voice program failed to start: {}", e.toString());
				return false;
			}
		}
		for (int i = 0; i < 60; i++) {
			try {
				Thread.sleep(1000L);
				HttpResponse<String> ping = HTTP.send(HttpRequest.newBuilder(URI.create(config.url().replaceAll("/+$", "") + "/ping"))
						.timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
				if (ping.statusCode() == 200) {
					return true;
				}
			} catch (Exception ignored) {
				if (!server.isAlive()) {
					return false;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ talking to the bot (push to talk)

	private static final AudioFormat MIC_FORMAT = new AudioFormat(16000.0F, 16, 1, true, false);
	/** Longest recording, in bytes (20 seconds). */
	private static final int MAX_RECORDING = 16000 * 2 * 20;
	private static volatile TargetDataLine mic;
	private static volatile boolean busy;
	/** True while a finished recording is being turned into text, so the HUD can show a spinner. */
	private static volatile boolean recognizing;
	/** Loudness of the last bit of recording (0..1), so the HUD's sound wave moves with the voice. */
	private static volatile float micLevel;

	/** For the HUD: 1 while recording, 2 while recognizing, 0 otherwise. */
	public static int talkState() {
		return mic != null ? 1 : recognizing ? 2 : 0;
	}

	public static float micLevel() {
		return micLevel;
	}

	/**
	 * Called every tick with whether the talk key is held: holding it records from the microphone, letting go turns the
	 * recording into text on this machine (Whisper in the voice program) and says it to the nearest bot.
	 */
	public static void tickTalk(Minecraft minecraft, boolean held) {
		if (minecraft.player == null) {
			return;
		}
		if (held && mic == null && !busy) {
			Config config = loadConfig();
			if (config == null || !config.mic()) {
				return;
			}
			startRecording(minecraft, config);
		} else if (!held && mic != null) {
			stopRecording(minecraft);
		}
	}

	private static void startRecording(Minecraft minecraft, Config config) {
		TargetDataLine line;
		try {
			line = AudioSystem.getTargetDataLine(MIC_FORMAT);
			line.open(MIC_FORMAT);
			line.start();
		} catch (Exception e) {
			minecraft.player.sendSystemMessage(Component.translatable("butterbot.voice.no_mic").withStyle(ChatFormatting.RED));
			ButterBot.LOGGER.warn("Butter Bot could not open the microphone: {}", e.toString());
			busy = true;
			// Don't try again every tick while the key is still held.
			CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS).execute(() -> busy = false);
			return;
		}
		mic = line;
		minecraft.player.sendOverlayMessage(Component.translatable("butterbot.voice.listening").withColor(0x7CFC5A));
		CompletableFuture.runAsync(() -> {
			ByteArrayOutputStream pcm = new ByteArrayOutputStream();
			byte[] buffer = new byte[3200];
			while (line.isOpen() && pcm.size() < MAX_RECORDING) {
				int read = line.read(buffer, 0, buffer.length);
				if (read <= 0) {
					break;
				}
				pcm.write(buffer, 0, read);
				int peak = 0;
				for (int i = 0; i + 1 < read; i += 2) {
					peak = Math.max(peak, Math.abs((short) (buffer[i] & 0xFF | buffer[i + 1] << 8)));
				}
				micLevel = peak / 32768.0F;
			}
			line.close();
			micLevel = 0.0F;
			busy = true;
			recognizing = true;
			try {
				transcribeAndSend(config, pcm.toByteArray());
			} finally {
				recognizing = false;
				busy = false;
			}
		});
	}

	private static void stopRecording(Minecraft minecraft) {
		TargetDataLine line = mic;
		mic = null;
		line.stop();
		line.close();
		minecraft.player.sendOverlayMessage(Component.translatable("butterbot.voice.recognizing").withColor(0xFFE680));
	}

	private static void transcribeAndSend(Config config, byte[] pcm) {
		Minecraft minecraft = Minecraft.getInstance();
		// Under half a second is a slip of the finger.
		if (pcm.length < 16000) {
			return;
		}
		String text = null;
		try {
			ByteArrayOutputStream wav = new ByteArrayOutputStream();
			AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), MIC_FORMAT, pcm.length / 2), AudioFileFormat.Type.WAVE, wav);
			byte[] answer = post(config, "/stt", wav.toByteArray(), "audio/wav", true);
			if (answer != null) {
				text = JsonParser.parseString(new String(answer, StandardCharsets.UTF_8)).getAsJsonObject().get("text").getAsString().trim();
			}
		} catch (Exception e) {
			ButterBot.LOGGER.warn("Butter Bot speech recognition failed: {}", e.toString());
		}
		String heard = text;
		minecraft.execute(() -> {
			if (minecraft.player == null || minecraft.getConnection() == null) {
				return;
			}
			if (heard == null || heard.isEmpty()) {
				minecraft.player.sendOverlayMessage(Component.translatable("butterbot.voice.not_heard").withColor(0xFF7070));
				return;
			}
			String said = heard.length() > 230 ? heard.substring(0, 230) : heard;
			minecraft.player.sendSystemMessage(Component.translatable("butterbot.voice.you", said).withColor(0xA8C8FF));
			minecraft.getConnection().sendCommand("butterbot chat " + said);
		});
	}

	public static synchronized void shutdown() {
		if (server != null && server.isAlive()) {
			server.destroy();
		}
	}

	/** Plays the WAV, quieter with distance (and with the game's voice volume); silent when the bot is out of hearing. */
	private static void play(int entityId, byte[] wav) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		Entity bot = minecraft.level.getEntity(entityId);
		double distance = bot == null ? 0.0D : bot.distanceTo(minecraft.player);
		float volume = (float) Math.max(0.0D, 1.0D - distance / HEAR_RANGE)
				* minecraft.options.getSoundSourceVolume(SoundSource.MASTER) * minecraft.options.getSoundSourceVolume(SoundSource.VOICE);
		if (volume <= 0.01F) {
			return;
		}
		try {
			AudioInputStream stream = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(wav)));
			Clip clip = AudioSystem.getClip();
			clip.open(stream);
			if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
				gain.setValue(Math.max(gain.getMinimum(), (float) (20.0D * Math.log10(volume))));
			}
			clip.addLineListener(event -> {
				if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
					clip.close();
				}
			});
			clip.start();
		} catch (Exception e) {
			ButterBot.LOGGER.warn("Butter Bot voice could not play: {}", e.toString());
		}
	}

	/** Read on every answer. Written with the defaults (the voice program in Downloads\butterbot-voice) the first time. */
	private static Config loadConfig() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("butterbot-voice.json");
		try {
			if (!Files.exists(path)) {
				String home = System.getProperty("user.home").replace('\\', '/');
				JsonObject template = new JsonObject();
				template.addProperty("enabled", true);
				template.addProperty("mic", true);
				template.addProperty("url", "http://127.0.0.1:47321");
				template.addProperty("python", home + "/Downloads/butterbot-voice/venv/Scripts/python.exe");
				template.addProperty("script", home + "/Downloads/butterbot-voice/server.py");
				template.addProperty("style", "jepang");
				Files.createDirectories(path.getParent());
				Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(template), StandardCharsets.UTF_8);
			}
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Config(!json.has("enabled") || json.get("enabled").getAsBoolean(), !json.has("mic") || json.get("mic").getAsBoolean(),
					json.get("url").getAsString(),
					json.get("python").getAsString(), json.get("script").getAsString(),
					json.has("style") ? json.get("style").getAsString() : "jepang");
		} catch (Exception e) {
			ButterBot.LOGGER.warn("Butter Bot voice config {} unreadable: {}", path, e.toString());
			return null;
		}
	}

	private BotVoice() {
	}
}
