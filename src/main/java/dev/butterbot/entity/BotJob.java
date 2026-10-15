package dev.butterbot.entity;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * A bot's standing job. Unlike a task, a job never finishes: between orders the bot goes back to it.
 * Miners and gatherers fill one chest until a list of items is in it, guards patrol an area and
 * farmers keep a field going and ranchers look after the animals.
 */
public enum BotJob {
	NONE,
	MINER,
	GATHERER,
	GUARD,
	FARMER,
	RANCHER;

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String key() {
		return "butterbot.job." + id();
	}

	public Component displayName() {
		return Component.translatable(key());
	}

	/** Jobs that work through a list of items and put them in the job chest. */
	public boolean usesList() {
		return this == MINER || this == GATHERER;
	}

	/** Jobs that work around a spot the owner picked. */
	public boolean usesArea() {
		return this == GUARD || this == FARMER || this == RANCHER;
	}

	public static BotJob byOrdinal(int ordinal) {
		BotJob[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
	}

	public static BotJob byId(String id) {
		for (BotJob job : values()) {
			if (job.id().equalsIgnoreCase(id) || job.name().equalsIgnoreCase(id)) {
				return job;
			}
		}
		return NONE;
	}
}
