package dev.butterbot.entity;

/** What the bot's built-in furnace smelts while it carries fuel. */
public enum SmeltMode {
	/** Never smelt. */
	OFF,
	/** Raw metals and ancient debris become ingots/scrap, raw food gets cooked. */
	ORES,
	/** Everything that has a furnace recipe (sand to glass, logs to charcoal, cobblestone to stone...). */
	ALL;

	public static SmeltMode byOrdinal(int ordinal) {
		SmeltMode[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ORES;
	}

	public SmeltMode next() {
		return byOrdinal((ordinal() + 1) % values().length);
	}

	public String key() {
		return "butterbot.smelt." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
