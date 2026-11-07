package dev.butterbot.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.butterbot.ButterBot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import javax.sound.sampled.AudioInputStream;
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

	private record Config(boolean enabled, String url, String python, String script) {
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
		HttpRequest request = HttpRequest.newBuilder(URI.create(config.url().replaceAll("/+$", "") + "/tts"))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
		try {
			HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() == 200) {
				return response.body();
			}
			ButterBot.LOGGER.warn("Butter Bot voice server answered {}", response.statusCode());
		} catch (ConnectException e) {
			if (mayStart && startServer(config)) {
				return request(config, text, false);
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
				template.addProperty("url", "http://127.0.0.1:47321");
				template.addProperty("python", home + "/Downloads/butterbot-voice/venv/Scripts/python.exe");
				template.addProperty("script", home + "/Downloads/butterbot-voice/server.py");
				Files.createDirectories(path.getParent());
				Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(template), StandardCharsets.UTF_8);
			}
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			return new Config(!json.has("enabled") || json.get("enabled").getAsBoolean(), json.get("url").getAsString(),
					json.get("python").getAsString(), json.get("script").getAsString());
		} catch (Exception e) {
			ButterBot.LOGGER.warn("Butter Bot voice config {} unreadable: {}", path, e.toString());
			return null;
		}
	}

	private BotVoice() {
	}
}
