package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;

/**
 * One behaviour of the bot. Every tick the {@link BotBrain} picks the runnable action with the
 * highest priority, like vanilla goals but with full control over what "finished" means.
 */
public interface BotAction {

	int getPriority(ButterBotEntity bot);

	boolean canRun(ButterBotEntity bot);

	void start(ButterBotEntity bot);

	void tick(ButterBotEntity bot);

	boolean isFinished(ButterBotEntity bot);

	void stop(ButterBotEntity bot);
}
