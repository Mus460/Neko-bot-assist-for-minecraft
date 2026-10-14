package dev.butterbot.entity;

/** Where a bot brings its loot when a job is finished or its backpack is full. */
public enum DeliveryMode {
	/** Walk to the owner and hand everything over. */
	OWNER,
	/** Walk to the linked Bot Base and store everything in the base storage. */
	BASE;

	public static DeliveryMode byOrdinal(int ordinal) {
		return ordinal == 1 ? BASE : OWNER;
	}

	public DeliveryMode next() {
		return this == OWNER ? BASE : OWNER;
	}
}
