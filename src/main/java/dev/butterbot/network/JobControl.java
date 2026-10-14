package dev.butterbot.network;

import dev.butterbot.ai.job.JobStorage;
import dev.butterbot.ai.job.ResourceCatalog;
import dev.butterbot.entity.BotJob;
import dev.butterbot.entity.ButterBotEntity;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Job settings changed from the control panel, the {@code /butterbot job} command or a shortcut key. */
public final class JobControl {

	/** How far away a chest or spot may be picked by looking at it. */
	private static final double PICK_RANGE = 64.0D;
	private static final double LOOK_RANGE = 8.0D;

	/**
	 * Gives {@code bot} a job. Guards and farmers work around the player's spot until another is
	 * picked; a chest the player is looking at becomes the job chest.
	 */
	public static void setJob(ServerPlayer player, ButterBotEntity bot, BotJob job) {
		bot.setJob(job);
		if (job == BotJob.NONE) {
			tell(player, Component.translatable("butterbot.msg.job_cleared", bot.getName()));
			return;
		}
		JobConfig jobs = bot.getJobs();
		ServerLevel level = player.level();
		if (job.usesArea() && (jobs.center() == null || !level.dimension().equals(jobs.dimension()))) {
			jobs.setCenter(level.dimension(), player.blockPosition());
		}
		BlockPos looked = lookedAtStorage(player);
		if (looked != null) {
			jobs.setChest(level.dimension(), looked);
		}
		tell(player, Component.translatable("butterbot.msg.job_given", bot.getName(), job.displayName()));
		explainMissing(player, bot);
	}

	/** Tells the player what the job still needs before it can start. */
	public static void explainMissing(ServerPlayer player, ButterBotEntity bot) {
		JobConfig jobs = bot.getJobs();
		if (jobs.job().usesList()) {
			if (jobs.chest() == null) {
				tell(player, Component.translatable("butterbot.msg.job_hint_chest").withStyle(ChatFormatting.GOLD));
			}
			if (jobs.needs().isEmpty()) {
				tell(player, Component.translatable("butterbot.msg.job_hint_list").withStyle(ChatFormatting.GOLD));
			}
		}
	}

	public static boolean setChest(ServerPlayer player, ButterBotEntity bot, @Nullable BlockPos pos) {
		JobConfig jobs = bot.getJobs();
		ServerLevel level = player.level();
		if (pos == null) {
			jobs.setChest(level.dimension(), null);
			tell(player, Component.translatable("butterbot.msg.job_chest_cleared", bot.getName()));
			return true;
		}
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) > PICK_RANGE * PICK_RANGE || !JobStorage.isStorage(level, pos)) {
			fail(player, Component.translatable("butterbot.msg.job_not_a_chest"));
			return false;
		}
		jobs.setChest(level.dimension(), pos);
		bot.say(Component.translatable("butterbot.msg.job_chest_set_say"));
		tell(player, Component.translatable("butterbot.msg.job_chest_set", bot.getName(), pos.toShortString()));
		return true;
	}

	public static void setCenter(ServerPlayer player, ButterBotEntity bot, BlockPos pos) {
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) > PICK_RANGE * PICK_RANGE) {
			fail(player, Component.translatable("butterbot.msg.job_spot_too_far"));
			return;
		}
		bot.getJobs().setCenter(player.level().dimension(), pos);
		bot.clearTaskForJob();
		tell(player, Component.translatable("butterbot.msg.job_center_set", bot.getName(), pos.toShortString(),
				bot.getJobs().radius()));
	}

	/** Puts {@code count} of {@code item} on the list (0 takes it off). */
	public static boolean setNeed(ServerPlayer player, ButterBotEntity bot, Item item, int count) {
		JobConfig jobs = bot.getJobs();
		if (!jobs.job().usesList()) {
			fail(player, Component.translatable("butterbot.msg.job_no_list"));
			return false;
		}
		if (count > 0 && !ResourceCatalog.canGet(jobs.job(), item)) {
			fail(player, Component.translatable("butterbot.msg.job_cant_get", new ItemStack(item).getHoverName(),
					jobs.job().displayName(), examples(jobs.job())));
			return false;
		}
		if (!jobs.setNeed(item, Math.min(count, JobConfig.MAX_NEED_COUNT))) {
			fail(player, Component.translatable("butterbot.msg.job_list_full", JobConfig.MAX_NEEDS));
			return false;
		}
		bot.clearTaskForJob();
		return true;
	}

	public static void setPaused(ButterBotEntity bot, boolean paused) {
		if (!bot.hasJob()) {
			return;
		}
		if (paused) {
			bot.pauseJob();
			bot.say(Component.translatable("butterbot.msg.job_paused"));
		} else {
			bot.resumeJob();
			bot.say(Component.translatable("butterbot.msg.job_resumed"));
		}
	}

	/** A few items the job can be asked for, for error messages. */
	private static Component examples(BotJob job) {
		MutableComponent list = Component.empty();
		List<ResourceCatalog.Entry> entries = ResourceCatalog.forJob(job);
		for (int i = 0; i < Math.min(6, entries.size()); i++) {
			if (i > 0) {
				list.append(", ");
			}
			list.append(new ItemStack(entries.get(i).item()).getHoverName());
		}
		return list;
	}

	/** The chest, barrel or Bot Base the player is looking at, if any. */
	@Nullable
	public static BlockPos lookedAtStorage(ServerPlayer player) {
		HitResult hit = player.pick(LOOK_RANGE, 0.0F, false);
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
				&& JobStorage.isStorage(player.level(), blockHit.getBlockPos())) {
			return blockHit.getBlockPos();
		}
		return null;
	}

	/** Chat summary of a bot's job: chest, list with progress, area. */
	public static void sendInfo(ServerPlayer player, ButterBotEntity bot) {
		JobConfig jobs = bot.getJobs();
		if (!jobs.hasJob()) {
			tell(player, Component.translatable("butterbot.msg.job_info_none", bot.getName()));
			return;
		}
		tell(player, Component.translatable("butterbot.msg.job_info", bot.getName(), jobs.job().displayName(),
				bot.describeShortStatus()));
		if (jobs.job().usesList()) {
			tell(player, Component.translatable("butterbot.msg.job_info_chest",
					jobs.chest() == null ? "-" : jobs.chest().toShortString(), jobs.zone()));
			for (JobConfig.Need need : jobs.needs()) {
				int have = jobs.knownChestCount(need.item());
				tell(player, Component.translatable("butterbot.msg.job_info_need", new ItemStack(need.item()).getHoverName(),
						have < 0 ? "?" : String.valueOf(have), need.count()));
			}
		} else {
			tell(player, Component.translatable("butterbot.msg.job_info_area",
					jobs.center() == null ? "-" : jobs.center().toShortString(), jobs.radius(), jobs.stat()));
		}
	}

	static void tell(ServerPlayer player, Component message) {
		player.sendSystemMessage(Msg.prefixed(message));
	}

	static void fail(ServerPlayer player, Component message) {
		player.sendSystemMessage(Msg.prefixed(message.copy().withStyle(ChatFormatting.RED)));
	}

	private JobControl() {
	}
}
