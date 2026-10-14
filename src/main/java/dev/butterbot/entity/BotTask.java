package dev.butterbot.entity;

public enum BotTask {
	NONE,
	MINE,
	TUNNEL,
	CRAFT,
	GO_TO_BASE,
	FOLLOW,
	LUA,
	FARM,
	SORT,
	LIGHT,
	FETCH,
	GOTO;

	public static BotTask byOrdinal(int ordinal) {
		BotTask[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
	}
}
