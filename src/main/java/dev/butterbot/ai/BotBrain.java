package dev.butterbot.ai;

import dev.butterbot.entity.ButterBotEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Local priority-based behaviour selector. No external services, no LLM. */
public final class BotBrain {

	private final List<BotAction> actions = new ArrayList<>();
	@Nullable
	private BotAction currentAction;

	public void register(BotAction action) {
		actions.add(action);
	}

	public void tick(ButterBotEntity bot) {
		BotAction best = null;
		int bestPriority = Integer.MIN_VALUE;
		for (BotAction action : actions) {
			if (!action.canRun(bot)) {
				continue;
			}
			int priority = action.getPriority(bot);
			if (priority > bestPriority) {
				best = action;
				bestPriority = priority;
			}
		}

		if (best != currentAction) {
			if (currentAction != null) {
				currentAction.stop(bot);
			}
			currentAction = best;
			if (currentAction != null) {
				currentAction.start(bot);
			}
		}

		if (currentAction != null) {
			currentAction.tick(bot);
			if (currentAction != null && currentAction.isFinished(bot)) {
				currentAction.stop(bot);
				currentAction = null;
			}
		}
	}

	public void reset(ButterBotEntity bot) {
		if (currentAction != null) {
			currentAction.stop(bot);
			currentAction = null;
		}
	}
}
