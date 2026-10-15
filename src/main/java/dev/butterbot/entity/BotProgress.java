package dev.butterbot.entity;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Everything a bot has learned besides its level: the skill ranks it bought with skill points, how
 * experienced it is in each job (mastery), and lifetime counters for the statistics page.
 */
public final class BotProgress {

	/** Job XP needed for mastery ranks 1 to 5 (counted from zero). */
	public static final int[] MASTERY_XP = {100, 300, 700, 1500, 3000};
	public static final int MAX_MASTERY = MASTERY_XP.length;
	/** Miner, gatherer, guard and farmer. */
	public static final int JOBS = 5;
	private static final int FORMAT = 1;
	private static final int MAX_COUNT = 1_000_000_000;

	public enum Stat {MINED, KILLS, HARVESTED, CRAFTED}

	/** Why a skill can't be learned right now. */
	public enum Refusal {NO_POINTS, MAXED, LEVEL, PREVIOUS}

	private final int[] ranks = new int[BotSkill.values().length];
	private final int[] mastery = new int[JOBS];
	private final int[] stats = new int[Stat.values().length];

	// ------------------------------------------------------------------ skills

	public int rank(BotSkill skill) {
		return ranks[skill.ordinal()];
	}

	public int[] ranks() {
		return ranks.clone();
	}

	public int spentPoints() {
		int spent = 0;
		for (int rank : ranks) {
			spent += rank;
		}
		return spent;
	}

	/** Skill points earned so far: one for every level after the first, one more at mastery ranks 3 and 5 of each job. */
	public static int earnedPoints(int level, int[] masteryXp) {
		int points = Math.max(0, level - 1);
		for (int xp : masteryXp) {
			points += masteryPoints(masteryRank(xp));
		}
		return points;
	}

	public static int masteryPoints(int rank) {
		return (rank >= 3 ? 1 : 0) + (rank >= 5 ? 1 : 0);
	}

	public int availablePoints(int level) {
		return earnedPoints(level, mastery) - spentPoints();
	}

	/** Null if {@code skill} can be raised one rank, otherwise why not. Works on plain arrays so the panel can ask too. */
	@Nullable
	public static Refusal whyNot(BotSkill skill, int level, int[] ranks, int available) {
		int rank = rankIn(ranks, skill);
		if (rank >= skill.maxRank()) {
			return Refusal.MAXED;
		}
		if (level < skill.requiredLevel()) {
			return Refusal.LEVEL;
		}
		BotSkill previous = skill.previous();
		if (previous != null && rankIn(ranks, previous) <= 0) {
			return Refusal.PREVIOUS;
		}
		return available <= 0 ? Refusal.NO_POINTS : null;
	}

	public static int rankIn(int[] ranks, BotSkill skill) {
		return skill.ordinal() < ranks.length ? Math.max(0, ranks[skill.ordinal()]) : 0;
	}

	/** Raises {@code skill} one rank if allowed. Returns null on success, otherwise why it wasn't. */
	@Nullable
	public Refusal raise(BotSkill skill, int level) {
		Refusal refusal = whyNot(skill, level, ranks, availablePoints(level));
		if (refusal == null) {
			ranks[skill.ordinal()]++;
		}
		return refusal;
	}

	/** Forgets every skill; returns the points given back. */
	public int resetSkills() {
		int spent = spentPoints();
		Arrays.fill(ranks, 0);
		return spent;
	}

	/** Keeps spent points within what the bot has earned (it can only go wrong with edited data). */
	public boolean fixOverspent(int level) {
		if (availablePoints(level) >= 0) {
			return false;
		}
		Arrays.fill(ranks, 0);
		return true;
	}

	// ------------------------------------------------------------------ job mastery

	public static int masteryRank(int xp) {
		int rank = 0;
		while (rank < MAX_MASTERY && xp >= MASTERY_XP[rank]) {
			rank++;
		}
		return rank;
	}

	/** Index into the mastery array, or -1 for no job. */
	public static int jobIndex(BotJob job) {
		int index = job.ordinal() - 1;
		return index >= 0 && index < JOBS ? index : -1;
	}

	public int masteryXp(BotJob job) {
		int index = jobIndex(job);
		return index < 0 ? 0 : mastery[index];
	}

	public int masteryRank(BotJob job) {
		return masteryRank(masteryXp(job));
	}

	public int[] mastery() {
		return mastery.clone();
	}

	/** Adds job experience. Returns the new mastery rank when it went up, otherwise -1. */
	public int addMasteryXp(BotJob job, int amount) {
		int index = jobIndex(job);
		if (index < 0 || amount <= 0) {
			return -1;
		}
		int before = masteryRank(mastery[index]);
		mastery[index] = (int) Math.min(MAX_COUNT, (long) mastery[index] + amount);
		int after = masteryRank(mastery[index]);
		return after > before ? after : -1;
	}

	// ------------------------------------------------------------------ lifetime counters

	public void addStat(Stat stat, int amount) {
		if (amount > 0) {
			stats[stat.ordinal()] = (int) Math.min(MAX_COUNT, (long) stats[stat.ordinal()] + amount);
		}
	}

	public int stat(Stat stat) {
		return stats[stat.ordinal()];
	}

	public int[] stats() {
		return stats.clone();
	}

	// ------------------------------------------------------------------ saving

	/** Everything as one int array: format, then each part prefixed by its length (so parts can grow later). */
	public int[] save() {
		int[] data = new int[1 + 1 + ranks.length + 1 + mastery.length + 1 + stats.length];
		int i = 0;
		data[i++] = FORMAT;
		i = put(data, i, ranks);
		i = put(data, i, mastery);
		put(data, i, stats);
		return data;
	}

	private static int put(int[] data, int at, int[] part) {
		data[at++] = part.length;
		System.arraycopy(part, 0, data, at, part.length);
		return at + part.length;
	}

	public void load(int[] data) {
		Arrays.fill(ranks, 0);
		Arrays.fill(mastery, 0);
		Arrays.fill(stats, 0);
		if (data.length < 1 || data[0] != FORMAT) {
			return;
		}
		int at = take(data, 1, ranks);
		at = take(data, at, mastery);
		take(data, at, stats);
		BotSkill[] skills = BotSkill.values();
		for (int i = 0; i < ranks.length; i++) {
			ranks[i] = Math.max(0, Math.min(skills[i].maxRank(), ranks[i]));
		}
		for (int i = 0; i < mastery.length; i++) {
			mastery[i] = Math.max(0, mastery[i]);
		}
		for (int i = 0; i < stats.length; i++) {
			stats[i] = Math.max(0, stats[i]);
		}
	}

	/** Reads one length-prefixed part into {@code into}; missing or extra entries are ignored. Returns where the next part starts. */
	private static int take(int[] data, int at, int[] into) {
		if (at >= data.length) {
			return data.length;
		}
		int length = Math.max(0, data[at]);
		int start = at + 1;
		for (int i = 0; i < Math.min(length, into.length) && start + i < data.length; i++) {
			into[i] = data[start + i];
		}
		return (int) Math.min(data.length, (long) start + length);
	}
}
