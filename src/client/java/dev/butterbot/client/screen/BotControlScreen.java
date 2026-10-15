package dev.butterbot.client.screen;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import dev.butterbot.ai.OreFinder;
import dev.butterbot.ai.actions.FarmTaskAction;
import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.ai.actions.SortTaskAction;
import dev.butterbot.ai.job.ResourceCatalog;
import net.minecraft.world.entity.EntityType;
import dev.butterbot.ai.job.Livestock;
import dev.butterbot.client.BotKeys;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotJob;
import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.BotProgress;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.BotSkill;
import dev.butterbot.entity.BotStats;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.entity.JobConfig;
import dev.butterbot.entity.SmeltMode;
import dev.butterbot.lua.LuaProgram;
import dev.butterbot.network.Payloads;
import dev.butterbot.registry.ModItems;
import dev.butterbot.skin.SkinLimits;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The bot panel, styled like a vanilla inventory. On the left a card shows the selected bot (skin,
 * level, health, backpack, what it is doing; arrows switch bots). On top five tabs: Main (orders and
 * settings), Mining, Job, Style and More (level, crafting, tunnels, Lua). Opened with B, by
 * right-clicking a bot, or from a Bot Base. Everything is checked again on the server.
 */
public class BotControlScreen extends Screen {

	private enum Tab {
		HOME("home", Items.COMPASS), MINE("mine", Items.DIAMOND_PICKAXE), JOB("job", Items.BELL),
		STYLE("style", Items.ARMOR_STAND), MORE("more", Items.BOOK);

		final String key;
		final Item icon;

		Tab(String key, Item icon) {
			this.key = key;
			this.icon = icon;
		}

		Component label() {
			return Component.translatable("butterbot.ui.tab." + key);
		}
	}

	private enum MorePage {MAIN, CRAFT, TUNNEL, PROGRAM, SKILLS, STATS}

	private static final int PANEL_W = 356;
	private static final int BODY_H = 210;
	private static final int TAB_H = 22;
	private static final int FOOTER_H = 14;
	private static final int CARD_W = 96;
	private static final int CARD_H = BODY_H - 14;
	private static final int CW = 239;
	private static final int REFRESH_TICKS = 40;
	private static final int LIST_ROWS = 5;
	private static final int SKIN_ROWS = 5;
	private static final int[][] TUNNEL_SIZES = {{2, 2}, {3, 3}, {1, 2}, {3, 2}, {5, 3}};
	private static final int[] MINE_AMOUNTS = {16, 32, 64, 128, 256};
	private static final long RELEASE_CONFIRM_MILLIS = 3000L;
	private static final int SKILL_W = 56;
	private static final int SKILL_H = 34;
	private static final int SKILL_PITCH_X = 61;
	private static final int SKILL_PITCH_Y = 37;
	private static final int SKILL_TOP = 32;
	private static final BotJob[] MASTERY_JOBS = {BotJob.MINER, BotJob.GATHERER, BotJob.GUARD, BotJob.FARMER, BotJob.RANCHER};

	// Job tab grid.
	private static final int GRID_Y = 102;
	private static final int CELL_W = 77;
	private static final int CELL_H = 22;
	private static final int CELL_PITCH_X = 81;
	private static final int CELL_PITCH_Y = 24;
	private static final int PICK_PITCH = 20;
	private static final int PICK_COLUMNS = 11;
	/** Rancher: the kinds of animal as a 4-column grid of small cells. */
	private static final int HERD_Y = 124;
	/** Eight job tiles side by side. */
	private static final int JOB_TILE_PITCH = 30;
	/** Courier source rows. */
	private static final int SOURCE_Y = 122;
	private static final int SOURCE_PITCH = 17;
	/** Bodyguard: buttons for the owner's other bots. */
	private static final int WARD_Y = 98;
	private static final int WARD_PITCH_X = 80;
	private static final int WARD_PITCH_Y = 20;
	private static final int MAX_WARD_BUTTONS = 9;
	private static final int HERD_W = 57;
	private static final int HERD_H = 20;
	private static final int HERD_PITCH_X = 60;
	private static final int HERD_PITCH_Y = 22;

	// Remembered between openings so the panel feels persistent.
	@Nullable
	private static UUID selectedBotId;
	private static Tab activeTab = Tab.HOME;
	private static MorePage morePage = MorePage.MAIN;
	private static int resourceIndex;
	private static String mineCountText = "64";
	private static String craftItemText = "";
	private static String craftCountText = "1";
	private static String fromXText = "";
	private static String fromZText = "";
	private static String toXText = "";
	private static String toZText = "";
	private static int tunnelSizeIndex;
	private static boolean tunnelTorches = true;
	private static boolean jobPicker;
	@Nullable
	private static String selectedNeed;
	/** The kind of animal whose limit the rancher buttons change. */
	@Nullable
	private static String selectedHerd;

	private Optional<BlockPos> basePos;
	@Nullable
	private final UUID focusBot;
	private final List<Payloads.BotEntry> bots = new ArrayList<>();
	private boolean receivedList;
	private int refreshTimer;
	private boolean listOpen;
	private int listPage;
	private int lastBotCount = -1;
	private int lastJobType = -1;
	/** Courier sources the job tab was built for (each has its own remove button). */
	private int lastSourceCount = -1;
	private int skinPage;
	private int selectedSkinIndex = -1;
	private long releaseArmedUntil;
	private long resetArmedUntil;
	/** The skill ranks and level the skill page was built for (its tooltips are rebuilt when they change). */
	private int[] shownSkills = new int[0];
	private int shownSkillLevel = -1;
	@Nullable
	private String scriptDraft;
	@Nullable
	private UUID scriptDraftBot;
	@Nullable
	private Component flashMessage;
	private int flashTicks;
	private boolean flashIsError;

	private int left;
	private int top;
	private int cardX;
	private int cardY;
	private int cx;
	private int cy;

	@Nullable
	private NumberBox mineCountBox;
	@Nullable
	private EditBox craftItemBox;
	@Nullable
	private NumberBox craftCountBox;
	@Nullable
	private NumberBox fromXBox;
	@Nullable
	private NumberBox fromZBox;
	@Nullable
	private NumberBox toXBox;
	@Nullable
	private NumberBox toZBox;
	@Nullable
	private EditBox renameBox;
	@Nullable
	private MultiLineEditBox scriptEditor;
	private final List<Identifier> suggestions = new ArrayList<>();
	private boolean suppressSuggestions;

	public BotControlScreen(Optional<BlockPos> basePos, @Nullable UUID focusBot) {
		super(Component.translatable(basePos.isPresent() ? "butterbot.gui.title_base" : "butterbot.gui.title_bot"));
		this.basePos = basePos;
		this.focusBot = focusBot;
		if (focusBot != null) {
			selectedBotId = focusBot;
		}
	}

	// ================================================================ data

	public void setBots(Payloads.BotList list) {
		this.basePos = list.base();
		boolean firstList = !receivedList;
		receivedList = true;
		UUID previousSelection = selectedBotId;
		bots.clear();
		bots.addAll(list.bots());

		if (selectedBotId != null && bots.stream().noneMatch(b -> b.id().equals(selectedBotId))) {
			selectedBotId = null;
		}
		if (selectedBotId == null && !bots.isEmpty()) {
			selectedBotId = focusBot != null && bots.stream().anyMatch(b -> b.id().equals(focusBot)) ? focusBot : bots.getFirst().id();
		}
		boolean selectionChanged = !Objects.equals(previousSelection, selectedBotId);

		// Most of the panel reads the bot live; only rebuild when its layout has to change.
		Payloads.BotEntry bot = selected();
		boolean jobLayoutChanged = activeTab == Tab.JOB && bot != null
				&& (bot.jobView().job() != lastJobType || bot.jobView().sources().size() != lastSourceCount);
		boolean needGone = activeTab == Tab.JOB && selectedNeed != null && (bot == null || selectedNeedView(bot.jobView()) == null);
		if (needGone) {
			selectedNeed = null;
		}
		boolean skillsChanged = activeTab == Tab.MORE && morePage == MorePage.SKILLS && bot != null
				&& (bot.level() != shownSkillLevel || !Arrays.equals(bot.skills(), shownSkills));
		if (firstList || selectionChanged || jobLayoutChanged || needGone || skillsChanged || bots.size() != lastBotCount) {
			rebuildPreservingInput();
		}
	}

	@Nullable
	private Payloads.BotEntry selected() {
		if (selectedBotId == null) {
			return null;
		}
		for (Payloads.BotEntry entry : bots) {
			if (entry.id().equals(selectedBotId)) {
				return entry;
			}
		}
		return null;
	}

	@Nullable
	private Payloads.JobView jobView() {
		Payloads.BotEntry bot = selected();
		return bot == null ? null : bot.jobView();
	}

	private void requestList() {
		ClientPlayNetworking.send(new Payloads.RequestBots(basePos, Optional.ofNullable(focusBot)));
	}

	private void command(int action) {
		command(action, "", new int[0]);
	}

	private void command(int action, String text, int... args) {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			flash(Component.translatable("butterbot.gui.no_bot_selected"), true);
			return;
		}
		ClientPlayNetworking.send(new Payloads.BotCommand(basePos, bot.id(), action, text, args));
	}

	/** Sends an order and says so at the bottom of the panel. */
	private void order(int action, String text, int... args) {
		command(action, text, args);
		flash(Component.translatable("butterbot.gui.order_sent"), false);
	}

	private void flash(Component message, boolean error) {
		flashMessage = message;
		flashTicks = 80;
		flashIsError = error;
	}

	private void cycleBot(int step) {
		if (bots.isEmpty()) {
			return;
		}
		int index = 0;
		for (int i = 0; i < bots.size(); i++) {
			if (bots.get(i).id().equals(selectedBotId)) {
				index = i;
			}
		}
		selectBot(bots.get(Math.floorMod(index + step, bots.size())).id());
	}

	private void selectBot(UUID id) {
		selectedBotId = id;
		selectedSkinIndex = -1;
		selectedNeed = null;
		jobPicker = false;
		releaseArmedUntil = 0L;
		rebuildPreservingInput();
	}

	// ================================================================ layout

	@Override
	protected void init() {
		int totalHeight = TAB_H + BODY_H + FOOTER_H;
		left = (width - PANEL_W) / 2;
		top = Math.max(TAB_H + 2, (height - totalHeight) / 2 + TAB_H);
		cardX = left + 7;
		cardY = top + 7;
		cx = left + 110;
		cy = top + 8;
		resetWidgetRefs();
		lastBotCount = bots.size();

		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			boolean current = tab == activeTab && !listOpen;
			McButton button = new McButton(left + 4 + i * 70, top - TAB_H, 69, current ? TAB_H + 3 : TAB_H, tab.label(),
					new ItemStack(tab.icon), McButton.Layout.TAB, b -> switchTab(tab)).selected(() -> tab == activeTab && !listOpen);
			button.active = receivedList && selected() != null;
			addRenderableWidget(button);
		}

		if (!receivedList) {
			// Wait for the bot list before building the rest; the remembered tab is kept.
			requestList();
			return;
		}
		initCard();
		if (selected() == null) {
			return;
		}
		if (listOpen) {
			initBotList();
			return;
		}
		switch (activeTab) {
			case HOME -> initHome();
			case MINE -> initMine();
			case JOB -> initJob();
			case STYLE -> initStyle();
			case MORE -> initMore();
		}
	}

	private void resetWidgetRefs() {
		mineCountBox = null;
		craftItemBox = null;
		craftCountBox = null;
		fromXBox = null;
		fromZBox = null;
		toXBox = null;
		toZBox = null;
		renameBox = null;
		scriptEditor = null;
		suggestions.clear();
	}

	private void switchTab(Tab tab) {
		if (tab == activeTab && !listOpen) {
			if (tab == Tab.MORE && morePage != MorePage.MAIN) {
				saveInput();
				morePage = MorePage.MAIN;
				rebuildWidgets();
			}
			return;
		}
		saveInput();
		activeTab = tab;
		listOpen = false;
		rebuildWidgets();
	}

	private void rebuildPreservingInput() {
		saveInput();
		rebuildWidgets();
	}

	/** Copies text box contents into fields before widgets are rebuilt. */
	private void saveInput() {
		if (mineCountBox != null) {
			mineCountText = mineCountBox.getValue();
		}
		if (craftItemBox != null) {
			craftItemText = craftItemBox.getValue();
		}
		if (craftCountBox != null) {
			craftCountText = craftCountBox.getValue();
		}
		if (fromXBox != null) {
			fromXText = fromXBox.getValue();
			fromZText = fromZBox.getValue();
			toXText = toXBox.getValue();
			toZText = toZBox.getValue();
		}
		if (scriptEditor != null && selectedBotId != null) {
			scriptDraft = scriptEditor.getValue();
			scriptDraftBot = selectedBotId;
		}
	}

	// ---------------------------------------------------------------- the bot card

	private void initCard() {
		if (bots.size() > 1) {
			addRenderableWidget(McButton.text(cardX + 3, cardY + 3, 13, 13, Component.literal("<"), b -> cycleBot(-1))
					.tip(Component.translatable("butterbot.ui.prev_bot")));
			addRenderableWidget(McButton.text(cardX + CARD_W - 16, cardY + 3, 13, 13, Component.literal(">"), b -> cycleBot(1))
					.tip(Component.translatable("butterbot.ui.next_bot")));
		}
		if (!bots.isEmpty()) {
			addRenderableWidget(McButton.text(cardX + 4, cardY + CARD_H - 20, CARD_W - 8, 16, Component.empty(), b -> {
				saveInput();
				listOpen = !listOpen;
				listPage = 0;
				rebuildWidgets();
			}).label(() -> Component.translatable(listOpen ? "butterbot.ui.close_list" : "butterbot.ui.all_bots", bots.size()))
					.selected(() -> listOpen));
		}
	}

	private void renderCard(GuiGraphicsExtractor g) {
		Payloads.BotEntry bot = selected();
		int middle = cardX + CARD_W / 2;
		if (bot == null) {
			Component none = Component.translatable(receivedList ? "butterbot.ui.no_bot" : "butterbot.gui.loading");
			List<FormattedCharSequence> lines = font.split(none, CARD_W - 10);
			for (int i = 0; i < lines.size(); i++) {
				g.text(font, lines.get(i), cardX + 5, cardY + 40 + i * 10, McStyle.TEXT_LIGHT_DIM, true);
			}
			return;
		}
		String name = font.plainSubstrByWidth(bot.name(), CARD_W - 36);
		g.text(font, name, middle - font.width(name) / 2, cardY + 6, McStyle.TEXT_LIGHT, true);

		int level = bot.level();
		Component levelText = Component.translatable("butterbot.ui.level", level);
		g.text(font, levelText, cardX + 5, cardY + 20, McStyle.GOLD_LIGHT, true);
		int points = skillPoints(bot);
		if (points > 0) {
			Component badge = Component.translatable("butterbot.ui.points_badge", points);
			g.text(font, badge, cardX + CARD_W - 5 - font.width(badge), cardY + 20, McStyle.GOOD_LIGHT, true);
		}
		float xp = level >= BotLevels.MAX_LEVEL ? 1.0F : bot.workXp() / (float) BotLevels.xpToNext(level);
		McStyle.bar(g, cardX + 5, cardY + 30, CARD_W - 10, 4, xp, McStyle.XP);

		ClientSkinManager.SkinInfo skin = cardSkin(bot);
		if (skin != null) {
			drawPaperDoll(g, skin, middle - 16, cardY + 38, 2);
		}
		if (isPreviewingSkin()) {
			Component preview = Component.translatable("butterbot.ui.preview");
			g.text(font, preview, middle - font.width(preview) / 2, cardY + 103, McStyle.GOLD_LIGHT, true);
		}

		int maxHealth = Math.round(bot.maxHealth());
		int health = Math.round(bot.health() * maxHealth);
		g.text(font, Component.translatable("butterbot.ui.health", health, maxHealth), cardX + 5, cardY + 113, McStyle.TEXT_LIGHT, true);
		McStyle.bar(g, cardX + 5, cardY + 123, CARD_W - 10, 4, bot.health(), McStyle.HEALTH);
		int capacity = bot.backpackSlots() * 64;
		g.text(font, Component.translatable("butterbot.ui.bag", bot.itemCount(), capacity), cardX + 5, cardY + 131,
				bot.itemCount() >= capacity ? McStyle.BAD_LIGHT : McStyle.TEXT_LIGHT, true);

		List<FormattedCharSequence> lines = font.split(bot.status(), CARD_W - 10);
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			g.text(font, lines.get(i), cardX + 5, cardY + 144 + i * 10, McStyle.TEXT_LIGHT_DIM, true);
		}
	}

	private boolean isPreviewingSkin() {
		return activeTab == Tab.STYLE && !listOpen && selectedSkinIndex >= 0
				&& selectedSkinIndex < ClientSkinManager.localSkins().size();
	}

	/** The skin shown on the card: the one being previewed in the Style tab, otherwise the bot's own. */
	@Nullable
	private ClientSkinManager.SkinInfo cardSkin(Payloads.BotEntry bot) {
		if (isPreviewingSkin()) {
			ClientSkinManager.SkinInfo preview = ClientSkinManager.skinFor(ClientSkinManager.localSkins().get(selectedSkinIndex).skinId());
			if (preview != null) {
				return preview;
			}
		}
		return botSkin(bot);
	}

	@Nullable
	private static ClientSkinManager.SkinInfo botSkin(Payloads.BotEntry bot) {
		ClientSkinManager.SkinInfo skin = bot.skinId().isEmpty() ? null : ClientSkinManager.skinFor(bot.skinId());
		return skin != null ? skin : ClientSkinManager.defaultSkinFor(bot.skinVariant());
	}

	// ---------------------------------------------------------------- all bots

	private void initBotList() {
		int pages = Math.max(1, (bots.size() + LIST_ROWS - 1) / LIST_ROWS);
		listPage = Math.min(listPage, pages - 1);
		if (pages > 1) {
			int py = cy + 14 + LIST_ROWS * 30 + 4;
			addRenderableWidget(McButton.text(cx, py, 30, 16, Component.literal("<"), b -> {
				listPage = Math.max(0, listPage - 1);
				rebuildWidgets();
			}).enabled(listPage > 0));
			addRenderableWidget(McButton.text(cx + CW - 30, py, 30, 16, Component.literal(">"), b -> {
				listPage = Math.min(pages - 1, listPage + 1);
				rebuildWidgets();
			}).enabled(listPage < pages - 1));
		}
	}

	private void renderBotList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, Component.translatable("butterbot.ui.bot_list", bots.size()), cx, cy + 2, McStyle.TEXT, false);
		int start = listPage * LIST_ROWS;
		for (int i = start; i < Math.min(bots.size(), start + LIST_ROWS); i++) {
			Payloads.BotEntry bot = bots.get(i);
			int y = cy + 14 + (i - start) * 30;
			boolean hovered = inside(mouseX, mouseY, cx, y, CW, 28);
			boolean isSelected = bot.id().equals(selectedBotId);
			McStyle.inset(g, cx, y, CW, 28, hovered ? 0xFFD6D6D6 : isSelected ? 0xFFBFD8BF : 0xFFB4B4B4);
			ClientSkinManager.SkinInfo skin = botSkin(bot);
			if (skin != null) {
				PlayerFaceExtractor.extractRenderState(g, skin.texture(), cx + 5, y + 6, 16, true, false, -1);
			}
			String line = font.plainSubstrByWidth(bot.name() + "  Lv " + bot.level(), CW - 80);
			g.text(font, line, cx + 27, y + 5, McStyle.TEXT, false);
			String status = font.plainSubstrByWidth(Component.empty().append(bot.status()).getString(), CW - 32);
			g.text(font, status, cx + 27, y + 16, McStyle.TEXT_DIM, false);
			if (minecraft != null && minecraft.player != null) {
				double distance = Math.sqrt(minecraft.player.blockPosition().distSqr(bot.pos()));
				String meters = (int) Math.round(distance) + " m";
				g.text(font, meters, cx + CW - 5 - font.width(meters), y + 5, McStyle.TEXT_DIM, false);
			}
		}
	}

	private boolean botListClicked(double mouseX, double mouseY) {
		int start = listPage * LIST_ROWS;
		for (int i = start; i < Math.min(bots.size(), start + LIST_ROWS); i++) {
			int y = cy + 14 + (i - start) * 30;
			if (inside(mouseX, mouseY, cx, y, CW, 28)) {
				listOpen = false;
				selectBot(bots.get(i).id());
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- Main tab

	private void initHome() {
		Payloads.BotEntry bot = selected();
		String[] keys = {"follow", "stop", "home", "deliver", "backpack", "light", "farm", "sort"};
		Item[] icons = {Items.LEAD, Items.BARRIER, Items.BED.pick(DyeColor.RED), Items.HOPPER, Items.BUNDLE, Items.TORCH, Items.WHEAT, Items.CHEST};
		int[] actions = {Payloads.BotCommand.FOLLOW, Payloads.BotCommand.STOP, Payloads.BotCommand.SUMMON, Payloads.BotCommand.DELIVER,
				Payloads.BotCommand.BACKPACK, Payloads.BotCommand.LIGHT, Payloads.BotCommand.FARM, Payloads.BotCommand.SORT};
		Object[][] tipArgs = {{}, {}, {}, {}, {}, {LightAreaAction.DEFAULT_RADIUS}, {FarmTaskAction.RADIUS}, {SortTaskAction.RADIUS}};
		for (int i = 0; i < keys.length; i++) {
			int action = actions[i];
			McButton tile = McButton.tile(cx + (i % 4) * 60, cy + 12 + (i / 4) * 39, 58, 36,
					Component.translatable("butterbot.ui." + keys[i]), new ItemStack(icons[i]), b -> {
						if (action == Payloads.BotCommand.LIGHT) {
							order(action, "", LightAreaAction.DEFAULT_RADIUS);
						} else if (action == Payloads.BotCommand.BACKPACK) {
							command(action);
						} else {
							order(action, "");
						}
					}).tip(Component.translatable("butterbot.ui." + keys[i] + "_tip", tipArgs[i]));
			if (action == Payloads.BotCommand.SUMMON && bot != null && !bot.hasBase() && basePos.isEmpty()) {
				tile.enabled(false).tip(Component.translatable("butterbot.ui.home_none_tip"));
			}
			if (action == Payloads.BotCommand.STOP) {
				tile.danger();
			}
			addRenderableWidget(tile);
		}

		int y = cy + 104;
		toggle(cx, y, "guard", Items.IRON_SWORD, () -> live(Payloads.BotEntry::guard), Payloads.BotCommand.GUARD, "butterbot.gui.guard_tooltip");
		toggle(cx + 121, y, "torches", Items.TORCH, () -> live(Payloads.BotEntry::autoTorch), Payloads.BotCommand.AUTO_TORCH,
				"butterbot.gui.torches_auto_tooltip");
		toggle(cx, y + 22, "feed", Items.BREAD, () -> live(Payloads.BotEntry::feedOwner), Payloads.BotCommand.FEED, "butterbot.gui.feed_tooltip");
		toggle(cx + 121, y + 22, "refill", Items.ARROW, () -> live(Payloads.BotEntry::autoRefill), Payloads.BotCommand.REFILL,
				"butterbot.gui.refill_tooltip");
		addRenderableWidget(McButton.icon(cx, y + 44, 118, 20, Component.empty(), new ItemStack(Items.FURNACE), b -> {
			Payloads.BotEntry current = selected();
			if (current != null) {
				command(Payloads.BotCommand.SMELT_MODE, "", SmeltMode.byOrdinal(current.smeltMode()).next().ordinal());
			}
		}).label(() -> {
			Payloads.BotEntry current = selected();
			SmeltMode mode = current == null ? SmeltMode.OFF : SmeltMode.byOrdinal(current.smeltMode());
			return Component.translatable("butterbot.ui.smelt", Component.translatable(mode.key()));
		}).tip(Component.translatable("butterbot.gui.smelt_tooltip")));
		addRenderableWidget(McButton.icon(cx + 121, y + 44, 118, 20, Component.empty(), new ItemStack(Items.CHEST_MINECART), b -> {
			Payloads.BotEntry current = selected();
			if (current != null) {
				command(Payloads.BotCommand.DELIVERY_MODE, "", DeliveryMode.byOrdinal(current.deliveryMode()).next().ordinal());
			}
		}).label(() -> {
			Payloads.BotEntry current = selected();
			boolean toBase = current != null && DeliveryMode.byOrdinal(current.deliveryMode()) == DeliveryMode.BASE;
			return Component.translatable("butterbot.ui.delivery",
					Component.translatable(toBase ? "butterbot.ui.delivery.base" : "butterbot.ui.delivery.owner"));
		}).tip(Component.translatable("butterbot.gui.delivery_tooltip")));
	}

	private interface BotFlag {
		boolean get(Payloads.BotEntry bot);
	}

	private boolean live(BotFlag flag) {
		Payloads.BotEntry bot = selected();
		return bot != null && flag.get(bot);
	}

	private void toggle(int x, int y, String key, Item icon, BooleanSupplier on, int action, String tooltipKey) {
		addRenderableWidget(McButton.toggle(x, y, 118, 20, Component.translatable("butterbot.ui." + key), new ItemStack(icon), on,
				b -> command(action, "", on.getAsBoolean() ? 0 : 1)).tip(Component.translatable(tooltipKey)));
	}

	private void renderHome(GuiGraphicsExtractor g) {
		g.text(font, Component.translatable("butterbot.ui.orders"), cx, cy + 2, McStyle.TEXT, false);
		g.text(font, Component.translatable("butterbot.ui.settings"), cx, cy + 94, McStyle.TEXT, false);
		g.text(font, Component.translatable("butterbot.ui.wheel_hint", BotKeys.panelKeyName()), cx, cy + 174, McStyle.TEXT_DIM, false);
	}

	// ---------------------------------------------------------------- Mining tab

	private void initMine() {
		BotResource[] resources = BotResource.values();
		resourceIndex = Math.floorMod(resourceIndex, resources.length);
		for (int i = 0; i < resources.length; i++) {
			int index = i;
			addRenderableWidget(new McButton(cx + (i % 6) * 40, cy + 12 + (i / 6) * 34, 38, 32, resources[i].displayName(),
					new ItemStack(iconFor(resources[i])), McButton.Layout.SLOT, b -> resourceIndex = index)
					.selected(() -> resourceIndex == index)
					.tip(resources[i].displayName()));
		}
		int y = cy + 96;
		mineCountBox = new NumberBox(font, cx + 45, y + 1, 40, 18, Component.translatable("butterbot.gui.amount"), false, 4);
		mineCountBox.setValue(mineCountText);
		addRenderableWidget(mineCountBox);
		for (int i = 0; i < MINE_AMOUNTS.length; i++) {
			int amount = MINE_AMOUNTS[i];
			addRenderableWidget(McButton.text(cx + 91 + i * 30, y, 28, 20, Component.literal(String.valueOf(amount)), b -> {
				if (mineCountBox != null) {
					mineCountBox.setValue(String.valueOf(amount));
				}
			}).selected(() -> mineCountBox != null && mineCountBox.intValue(-1) == amount));
		}
		addRenderableWidget(McButton.icon(cx, cy + 124, 158, 22, Component.translatable("butterbot.ui.start_mining"),
				new ItemStack(Items.IRON_PICKAXE), b -> {
					int count = mineCountBox == null ? 64 : mineCountBox.intValue(64);
					order(Payloads.BotCommand.MINE, BotResource.values()[resourceIndex].id(), Math.max(1, count));
				}));
		addRenderableWidget(McButton.icon(cx + 161, cy + 124, 78, 22, Component.translatable("butterbot.gui.find"),
				new ItemStack(Items.SPYGLASS), b -> {
					command(Payloads.BotCommand.FIND, BotResource.values()[resourceIndex].id());
					flash(Component.translatable("butterbot.gui.find_sent"), false);
				}).tip(Component.translatable("butterbot.gui.find_tooltip", OreFinder.RADIUS)));
	}

	private static Item iconFor(BotResource resource) {
		return switch (resource) {
			case DIAMOND -> Items.DIAMOND;
			case IRON -> Items.RAW_IRON;
			case GOLD -> Items.RAW_GOLD;
			case COPPER -> Items.RAW_COPPER;
			case COAL -> Items.COAL;
			case REDSTONE -> Items.REDSTONE;
			case LAPIS -> Items.LAPIS_LAZULI;
			case EMERALD -> Items.EMERALD;
			case STONE -> Items.COBBLESTONE;
			case WOOD -> Items.OAK_LOG;
			case SAND -> Items.SAND;
			case NETHERITE -> Items.ANCIENT_DEBRIS;
		};
	}

	private void renderMine(GuiGraphicsExtractor g) {
		g.text(font, Component.translatable("butterbot.ui.pick_resource"), cx, cy + 2, McStyle.TEXT, false);
		BotResource resource = BotResource.values()[Math.floorMod(resourceIndex, BotResource.values().length)];
		Component where = resource.isNether()
				? Component.translatable("butterbot.ui.dug_at_nether", resource.displayName(), resource.optimalY())
				: resource.isUnderground()
				? Component.translatable("butterbot.ui.dug_at", resource.displayName(), resource.optimalY())
				: Component.translatable("butterbot.ui.on_surface", resource.displayName());
		g.text(font, where, cx, cy + 83, McStyle.TEXT, false);
		g.text(font, Component.translatable("butterbot.gui.amount_label"), cx, cy + 102, McStyle.TEXT, false);
		g.textWithWordWrap(font, Component.translatable("butterbot.ui.mine_help"), cx, cy + 152, CW, McStyle.TEXT_DIM, false);
	}

	// ---------------------------------------------------------------- Job tab

	private void initJob() {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			return;
		}
		Payloads.JobView view = bot.jobView();
		BotJob current = BotJob.byOrdinal(view.job());
		lastJobType = view.job();
		lastSourceCount = view.sources().size();
		BotJob[] values = BotJob.values();
		Item[] icons = {ModItems.BUTTER_BOT, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_HOE, Items.WHEAT,
				Items.CHEST_MINECART, Items.SHIELD};
		for (int i = 0; i < values.length; i++) {
			BotJob value = values[i];
			addRenderableWidget(McButton.tile(cx + i * JOB_TILE_PITCH, cy, JOB_TILE_PITCH - 1, 34, Component.translatable(value.key() + ".short"),
					new ItemStack(icons[i]), b -> {
						Payloads.JobView now = jobView();
						if (now != null && now.job() != value.ordinal()) {
							jobPicker = false;
							selectedNeed = null;
							command(Payloads.BotCommand.JOB_SET, "", value.ordinal());
						}
					}).selected(() -> {
						Payloads.JobView now = jobView();
						return now != null && now.job() == value.ordinal();
					}).tip(Component.translatable(value.key() + ".tooltip")));
		}
		if (current == BotJob.NONE) {
			jobPicker = false;
			return;
		}

		int right = cx + CW;
		addRenderableWidget(McButton.text(right - 52, cy + 38, 52, 16, Component.empty(), b -> {
			Payloads.JobView now = jobView();
			if (now != null) {
				command(Payloads.BotCommand.JOB_PAUSE, "", now.paused() ? 0 : 1);
			}
		}).label(() -> {
			Payloads.JobView now = jobView();
			return Component.translatable(now != null && now.paused() ? "butterbot.gui.job_resume" : "butterbot.gui.job_pause");
		}).tip(Component.translatable("butterbot.gui.job_pause_tooltip")));

		if (current == BotJob.COURIER) {
			initCourier(view);
			return;
		}
		if (current == BotJob.BODYGUARD) {
			initBodyguard(bot);
			return;
		}
		if (current.usesList()) {
			addRenderableWidget(McButton.icon(right - 84, cy + 58, 84, 18, Component.translatable("butterbot.ui.pick_chest"),
					new ItemStack(Items.CHEST), b -> startPick(BotKeys.PickKind.CHEST))
					.tip(Component.translatable("butterbot.gui.job_pick_chest_tooltip")));
			addRenderableWidget(McButton.text(right - 40, cy + 80, 19, 18, Component.literal("-"), b -> {
				Payloads.JobView now = jobView();
				if (now != null) {
					command(Payloads.BotCommand.JOB_ZONE, "", Math.max(0, now.zone() - JobConfig.ZONE_STEP));
				}
			}).tip(Component.translatable("butterbot.gui.job_zone_tooltip")));
			addRenderableWidget(McButton.text(right - 19, cy + 80, 19, 18, Component.literal("+"), b -> {
				Payloads.JobView now = jobView();
				if (now != null) {
					command(Payloads.BotCommand.JOB_ZONE, "", Math.min(JobConfig.MAX_ZONE, now.zone() + JobConfig.ZONE_STEP));
				}
			}).tip(Component.translatable("butterbot.gui.job_zone_tooltip")));
			int rowY = cy + 176;
			if (jobPicker) {
				addRenderableWidget(McButton.text(cx, rowY, 70, 16, Component.translatable("butterbot.gui.job_picker_close"), b -> {
					jobPicker = false;
					rebuildPreservingInput();
				}));
				return;
			}
			if (selectedNeedView(view) == null) {
				return;
			}
			int[] deltas = {-64, -16, 16, 64};
			for (int i = 0; i < deltas.length; i++) {
				int delta = deltas[i];
				addRenderableWidget(McButton.text(cx + i * 46, rowY, 44, 16, Component.literal(delta > 0 ? "+" + delta : String.valueOf(delta)),
						b -> adjustNeed(delta)));
			}
			addRenderableWidget(McButton.text(cx + 184, rowY, 55, 16, Component.translatable("butterbot.gui.job_remove"), b -> {
				String item = selectedNeed;
				selectedNeed = null;
				if (item != null) {
					command(Payloads.BotCommand.JOB_NEED, item, 0);
				}
				rebuildPreservingInput();
			}).danger());
		} else {
			addRenderableWidget(McButton.text(right - 98, cy + 58, 48, 18, Component.translatable("butterbot.gui.job_here"),
					b -> command(Payloads.BotCommand.JOB_CENTER)).tip(Component.translatable("butterbot.gui.job_here_tooltip")));
			addRenderableWidget(McButton.text(right - 48, cy + 58, 48, 18, Component.translatable("butterbot.gui.job_pick_spot"),
					b -> startPick(BotKeys.PickKind.CENTER)).tip(Component.translatable("butterbot.gui.job_pick_spot_tooltip")));
			int max = JobConfig.maxRadiusFor(current);
			addRenderableWidget(McButton.text(right - 40, cy + 80, 19, 18, Component.literal("-"), b -> {
				Payloads.JobView now = jobView();
				if (now != null) {
					command(Payloads.BotCommand.JOB_RADIUS, "", Math.max(JobConfig.MIN_RADIUS, now.radius() - 2));
				}
			}));
			addRenderableWidget(McButton.text(right - 19, cy + 80, 19, 18, Component.literal("+"), b -> {
				Payloads.JobView now = jobView();
				if (now != null) {
					command(Payloads.BotCommand.JOB_RADIUS, "", Math.min(max, now.radius() + 2));
				}
			}));
			addRenderableWidget(McButton.text(right - 86, cy + 102, 48, 18, Component.translatable("butterbot.gui.job_pick_chest_short"),
					b -> startPick(BotKeys.PickKind.CHEST)).tip(Component.translatable("butterbot.gui.job_area_chest_tooltip")));
			addRenderableWidget(McButton.text(right - 36, cy + 102, 36, 18, Component.translatable("butterbot.gui.job_auto"),
					b -> command(Payloads.BotCommand.JOB_CHEST)).tip(Component.translatable("butterbot.gui.job_auto_tooltip")));
			if (current == BotJob.RANCHER) {
				if (selectedHerdView(view) != null) {
					int[] deltas = {-10, -1, 1, 10};
					for (int i = 0; i < deltas.length; i++) {
						int delta = deltas[i];
						addRenderableWidget(McButton.text(cx + i * 31, cy + 170, 29, 18,
								Component.literal(delta > 0 ? "+" + delta : String.valueOf(delta)), b -> adjustHerd(delta))
								.tip(Component.translatable("butterbot.gui.job_herd_tooltip")));
					}
				}
				addRenderableWidget(McButton.toggle(cx + 128, cy + 170, CW - 128, 18, Component.translatable("butterbot.gui.job_butcher"),
						new ItemStack(Items.IRON_SWORD), () -> {
							Payloads.JobView now = jobView();
							return now != null && now.butcher();
						}, b -> {
							Payloads.JobView now = jobView();
							if (now != null) {
								command(Payloads.BotCommand.JOB_BUTCHER, "", now.butcher() ? 0 : 1);
							}
						}).tip(Component.translatable("butterbot.gui.job_butcher_tooltip")));
			}
		}
	}

	/** Courier: its chest, smelting on the way, and up to four source chests (each with a remove button). */
	private void initCourier(Payloads.JobView view) {
		int right = cx + CW;
		addRenderableWidget(McButton.text(right - 48, cy + 58, 48, 18, Component.translatable("butterbot.gui.job_pick_chest_short"),
				b -> startPick(BotKeys.PickKind.CHEST)).tip(Component.translatable("butterbot.gui.job_courier_chest_tooltip")));
		addRenderableWidget(McButton.toggle(cx, cy + 80, CW, 18, Component.translatable("butterbot.gui.job_smelt"),
				new ItemStack(Items.FURNACE), () -> {
					Payloads.JobView now = jobView();
					return now != null && now.smelt();
				}, b -> {
					Payloads.JobView now = jobView();
					if (now != null) {
						command(Payloads.BotCommand.JOB_SMELT, "", now.smelt() ? 0 : 1);
					}
				}).tip(Component.translatable("butterbot.gui.job_smelt_tooltip")));
		if (view.sources().size() < JobConfig.MAX_SOURCES) {
			addRenderableWidget(McButton.icon(right - 92, cy + 102, 92, 18, Component.translatable("butterbot.gui.job_add_source"),
					new ItemStack(Items.CHEST), b -> startPick(BotKeys.PickKind.SOURCE))
					.tip(Component.translatable("butterbot.gui.job_add_source_tooltip")));
		}
		for (int i = 0; i < view.sources().size(); i++) {
			int index = i;
			addRenderableWidget(McButton.text(right - 18, cy + SOURCE_Y + i * SOURCE_PITCH, 18, 16, Component.literal("x"),
					b -> command(Payloads.BotCommand.JOB_SOURCE, "", index)).danger()
					.tip(Component.translatable("butterbot.gui.job_remove_source_tooltip")));
		}
	}

	/** Bodyguard: one button for each of the owner's other bots; the one it guards is highlighted. */
	private void initBodyguard(Payloads.BotEntry self) {
		int shown = 0;
		for (Payloads.BotEntry other : bots) {
			if (other.id().equals(self.id()) || shown >= MAX_WARD_BUTTONS) {
				continue;
			}
			UUID id = other.id();
			int x = cx + (shown % 3) * WARD_PITCH_X;
			int y = cy + WARD_Y + (shown / 3) * WARD_PITCH_Y;
			addRenderableWidget(McButton.text(x, y, WARD_PITCH_X - 1, 18, Component.literal(other.name()),
					b -> command(Payloads.BotCommand.JOB_WARD, id.toString())).selected(() -> {
						Payloads.JobView now = jobView();
						return now != null && now.ward().isPresent() && now.ward().get().equals(id);
					}).tip(Component.translatable("butterbot.gui.job_ward_pick_tooltip", other.name())));
			shown++;
		}
	}

	/** Changes the limit of the selected kind of animal by {@code delta}, starting from what the server says now. */
	private void adjustHerd(int delta) {
		Payloads.JobView view = jobView();
		Payloads.HerdView herd = view == null ? null : selectedHerdView(view);
		if (herd != null) {
			command(Payloads.BotCommand.JOB_HERD, herd.kind(), Math.clamp(herd.limit() + delta, JobConfig.MIN_HERD, JobConfig.MAX_HERD));
		}
	}

	@Nullable
	private static Payloads.HerdView selectedHerdView(Payloads.JobView view) {
		if (selectedHerd == null) {
			return null;
		}
		for (Payloads.HerdView herd : view.herds()) {
			if (herd.kind().equals(selectedHerd)) {
				return herd;
			}
		}
		return null;
	}

	/** Each kind of animal: how many are in the area / its limit (red when over, green when full). */
	private void renderHerds(GuiGraphicsExtractor g, Payloads.JobView view, int mouseX, int mouseY) {
		List<Payloads.HerdView> herds = view.herds();
		for (int i = 0; i < herds.size(); i++) {
			Payloads.HerdView herd = herds.get(i);
			int x = cx + (i % 4) * HERD_PITCH_X;
			int y = cy + HERD_Y + (i / 4) * HERD_PITCH_Y;
			boolean hovered = inside(mouseX, mouseY, x, y, HERD_W, HERD_H);
			boolean isSelected = herd.kind().equals(selectedHerd);
			McStyle.inset(g, x, y, HERD_W, HERD_H, hovered ? 0xFFD6D6D6 : isSelected ? 0xFFBFD8BF : 0xFFB4B4B4);
			if (isSelected) {
				g.outline(x - 1, y - 1, HERD_W + 2, HERD_H + 2, McStyle.WHITE);
			}
			EntityType<?> kind = Livestock.byId(herd.kind());
			if (kind != null) {
				g.item(new ItemStack(Livestock.icon(kind)), x + 2, y + 2);
			}
			String count = herd.count() < 0 ? "?" : String.valueOf(herd.count());
			int color = herd.count() > herd.limit() ? McStyle.BAD : herd.count() == herd.limit() ? McStyle.GOOD : McStyle.TEXT;
			g.text(font, font.plainSubstrByWidth(count + "/" + herd.limit(), HERD_W - 21), x + 20, y + 6, color, false);
			if (hovered && kind != null) {
				g.setComponentTooltipForNextFrame(font, List.of(kind.getDescription().copy().withStyle(ChatFormatting.AQUA),
						Component.translatable("butterbot.gui.herd_count", count, herd.limit()),
						Component.translatable(herd.custom() ? "butterbot.gui.herd_custom" : "butterbot.gui.herd_default")
								.withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
			}
		}
		if (selectedHerdView(view) == null) {
			g.text(font, font.plainSubstrByWidth(Component.translatable("butterbot.gui.herd_hint").getString(), 124), cx, cy + 175,
					McStyle.TEXT_DIM, false);
		}
	}

	/** Changes the selected list item by {@code delta}, starting from what the server says now. */
	private void adjustNeed(int delta) {
		Payloads.JobView view = jobView();
		Payloads.NeedView need = view == null ? null : selectedNeedView(view);
		if (need != null) {
			command(Payloads.BotCommand.JOB_NEED, need.item(), Math.max(0, need.target() + delta));
		}
	}

	/** Closes the panel so the player can look at a chest or spot and press the context key. */
	private void startPick(BotKeys.PickKind kind) {
		Payloads.BotEntry bot = selected();
		if (bot != null) {
			BotKeys.beginPick(bot.id(), basePos, kind);
			onClose();
		}
	}

	@Nullable
	private static Payloads.NeedView selectedNeedView(Payloads.JobView view) {
		if (selectedNeed == null) {
			return null;
		}
		for (Payloads.NeedView need : view.needs()) {
			if (need.item().equals(selectedNeed)) {
				return need;
			}
		}
		return null;
	}

	private void renderJob(GuiGraphicsExtractor g, Payloads.BotEntry bot, int mouseX, int mouseY) {
		Payloads.JobView view = bot.jobView();
		BotJob job = BotJob.byOrdinal(view.job());
		if (job == BotJob.NONE) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.job_none_help"), cx, cy + 42, CW, McStyle.TEXT_DIM, false);
			return;
		}
		String status = font.plainSubstrByWidth(Component.empty().append(bot.status()).getString(), CW - 58);
		g.text(font, status, cx, cy + 42, view.paused() ? McStyle.BAD : McStyle.TEXT, false);

		Component chest = view.chest().isEmpty()
				? Component.translatable(job.usesList() ? "butterbot.gui.job_chest_none" : "butterbot.gui.job_area_chest_auto")
				: Component.translatable(view.chestOk() ? "butterbot.gui.job_chest_ok" : "butterbot.gui.job_chest_bad",
				view.chest().get().toShortString());
		int chestColor = view.chest().isPresent() ? (view.chestOk() ? McStyle.TEXT : McStyle.BAD) : (job.usesList() ? McStyle.BAD : McStyle.TEXT);
		if (job == BotJob.COURIER) {
			renderCourier(g, view);
			return;
		}
		if (job == BotJob.BODYGUARD) {
			renderBodyguard(g, bot, view);
			return;
		}
		if (job.usesList()) {
			g.text(font, font.plainSubstrByWidth(chest.getString(), CW - 88), cx, cy + 63, chestColor, false);
			g.text(font, Component.translatable("butterbot.gui.job_zone", view.zone()), cx, cy + 85, McStyle.TEXT, false);
			if (jobPicker) {
				renderJobPicker(g, job, view, mouseX, mouseY);
			} else {
				renderNeeds(g, view, mouseX, mouseY);
			}
			return;
		}
		Component spot = view.center().isEmpty() ? Component.translatable("butterbot.gui.job_spot_none")
				: Component.translatable("butterbot.gui.job_spot", view.center().get().toShortString());
		g.text(font, font.plainSubstrByWidth(spot.getString(), CW - 102), cx, cy + 63, view.center().isPresent() ? McStyle.TEXT : McStyle.BAD, false);
		g.text(font, Component.translatable("butterbot.gui.job_radius", view.radius()), cx, cy + 85, McStyle.TEXT, false);
		g.text(font, font.plainSubstrByWidth(chest.getString(), CW - 90), cx, cy + 107, chestColor, false);
		if (job == BotJob.RANCHER) {
			renderHerds(g, view, mouseX, mouseY);
			return;
		}
		String info = job == BotJob.GUARD ? "butterbot.gui.job_guard_info" : "butterbot.gui.job_farmer_info";
		g.textWithWordWrap(font, Component.translatable(info, view.stat(), view.radius()), cx, cy + 128, CW, McStyle.TEXT_DIM, false);
	}

	private void renderCourier(GuiGraphicsExtractor g, Payloads.JobView view) {
		Component chest = view.chest().isEmpty() ? Component.translatable("butterbot.gui.job_courier_to_none")
				: Component.translatable("butterbot.gui.job_courier_to", view.chest().get().toShortString());
		g.text(font, font.plainSubstrByWidth(chest.getString(), CW - 52), cx, cy + 63,
				view.chest().isPresent() && view.chestOk() ? McStyle.TEXT : McStyle.BAD, false);
		List<BlockPos> sources = view.sources();
		g.text(font, Component.translatable("butterbot.gui.job_sources", sources.size(), JobConfig.MAX_SOURCES), cx, cy + 107,
				sources.isEmpty() ? McStyle.BAD : McStyle.TEXT, false);
		for (int i = 0; i < sources.size(); i++) {
			g.text(font, (i + 1) + ". " + sources.get(i).toShortString(), cx + 4, cy + SOURCE_Y + i * SOURCE_PITCH + 4, McStyle.TEXT, false);
		}
		int y = cy + SOURCE_Y + sources.size() * SOURCE_PITCH + 4;
		Component info = sources.isEmpty() ? Component.translatable("butterbot.gui.job_sources_hint")
				: Component.translatable("butterbot.gui.job_courier_info", view.stat());
		if (y + 9 <= cy + CARD_H) {
			g.textWithWordWrap(font, info, cx, y, CW, McStyle.TEXT_DIM, false);
		}
	}

	private void renderBodyguard(GuiGraphicsExtractor g, Payloads.BotEntry self, Payloads.JobView view) {
		boolean known = view.ward().isPresent() && !view.wardName().isEmpty();
		Component ward = view.ward().isEmpty() ? Component.translatable("butterbot.gui.job_ward_none")
				: Component.translatable("butterbot.gui.job_ward", known ? view.wardName() : "?");
		g.text(font, font.plainSubstrByWidth(ward.getString(), CW), cx, cy + 63, known ? McStyle.TEXT : McStyle.BAD, false);
		long others = bots.stream().filter(other -> !other.id().equals(self.id())).count();
		g.text(font, Component.translatable(others == 0 ? "butterbot.gui.job_ward_no_bots" : "butterbot.gui.job_ward_pick"),
				cx, cy + 85, others == 0 ? McStyle.BAD : McStyle.TEXT, false);
		int rows = (int) Math.min(MAX_WARD_BUTTONS, others + 2) / 3;
		int y = cy + WARD_Y + Math.max(1, rows) * WARD_PITCH_Y + 4;
		g.textWithWordWrap(font, Component.translatable("butterbot.gui.job_bodyguard_info", view.stat()), cx, y, CW, McStyle.TEXT_DIM, false);
	}

	/** The list as a 3x3 grid of slots with "in chest / wanted" and a progress bar; the last cell adds an item. */
	private void renderNeeds(GuiGraphicsExtractor g, Payloads.JobView view, int mouseX, int mouseY) {
		List<Payloads.NeedView> needs = view.needs();
		int cells = Math.min(JobConfig.MAX_NEEDS, needs.size() + 1);
		for (int i = 0; i < cells; i++) {
			int x = cx + (i % 3) * CELL_PITCH_X;
			int y = cy + GRID_Y + (i / 3) * CELL_PITCH_Y;
			boolean hovered = inside(mouseX, mouseY, x, y, CELL_W, CELL_H);
			if (i >= needs.size()) {
				McStyle.inset(g, x, y, CELL_W, CELL_H, hovered ? 0xFFD6D6D6 : 0xFFB4B4B4);
				Component add = Component.translatable("butterbot.gui.job_add");
				g.text(font, add, x + (CELL_W - font.width(add)) / 2, y + 7, McStyle.GOOD, false);
				if (hovered) {
					g.setTooltipForNextFrame(Component.translatable("butterbot.gui.job_add_hint"), mouseX, mouseY);
				}
				continue;
			}
			Payloads.NeedView need = needs.get(i);
			ItemStack stack = stackOf(need.item());
			boolean done = need.have() >= need.target();
			boolean isSelected = need.item().equals(selectedNeed);
			McStyle.inset(g, x, y, CELL_W, CELL_H, hovered ? 0xFFD6D6D6 : isSelected ? 0xFFBFD8BF : 0xFFB4B4B4);
			if (isSelected) {
				g.outline(x - 1, y - 1, CELL_W + 2, CELL_H + 2, McStyle.WHITE);
			}
			McStyle.slot(g, x + 2, y + 2);
			g.item(stack, x + 3, y + 3);
			String count = (need.have() < 0 ? "?" : compact(need.have())) + "/" + compact(need.target());
			g.text(font, font.plainSubstrByWidth(count, CELL_W - 24), x + 23, y + 5, done ? McStyle.GOOD : McStyle.TEXT, false);
			if (need.target() > 0) {
				float progress = Math.max(0, need.have()) / (float) need.target();
				McStyle.bar(g, x + 23, y + 15, CELL_W - 27, 4, progress, done ? 0xFF3FB83F : McStyle.XP);
			}
			if (hovered) {
				g.setTooltipForNextFrame(Component.translatable("butterbot.gui.job_need_label", stack.getHoverName(),
						need.have() < 0 ? "?" : String.valueOf(need.have()), need.target()), mouseX, mouseY);
			}
		}
		if (selectedNeed == null) {
			Component hint = Component.translatable(needs.isEmpty() ? "butterbot.gui.job_list_empty" : "butterbot.gui.job_list_hint");
			g.text(font, font.plainSubstrByWidth(hint.getString(), CW), cx, cy + 180, McStyle.TEXT_DIM, false);
		}
	}

	/** Every item the job can collect; a green frame means it is already on the list. */
	private void renderJobPicker(GuiGraphicsExtractor g, BotJob job, Payloads.JobView view, int mouseX, int mouseY) {
		List<ResourceCatalog.Entry> entries = ResourceCatalog.forJob(job);
		int offset = (CW - PICK_COLUMNS * PICK_PITCH) / 2;
		for (int i = 0; i < entries.size(); i++) {
			int x = cx + offset + (i % PICK_COLUMNS) * PICK_PITCH;
			int y = cy + GRID_Y + (i / PICK_COLUMNS) * PICK_PITCH;
			ItemStack stack = new ItemStack(entries.get(i).item());
			McStyle.slot(g, x, y);
			if (isOnList(view, itemId(stack.getItem()))) {
				g.outline(x, y, 18, 18, 0xFF3FB83F);
			}
			if (inside(mouseX, mouseY, x, y, 18, 18)) {
				g.fill(x + 1, y + 1, x + 17, y + 17, 0x80FFFFFF);
				g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
			}
			g.item(stack, x + 1, y + 1);
		}
		g.text(font, Component.translatable("butterbot.gui.job_picker_hint"), cx + 76, cy + 180, McStyle.TEXT_DIM, false);
	}

	/** Clicks on the list grid or the item picker (they are drawn, not widgets). */
	private boolean jobTabClicked(double mouseX, double mouseY) {
		Payloads.JobView view = jobView();
		if (view == null) {
			return false;
		}
		BotJob job = BotJob.byOrdinal(view.job());
		if (job == BotJob.RANCHER) {
			List<Payloads.HerdView> herds = view.herds();
			for (int i = 0; i < herds.size(); i++) {
				int x = cx + (i % 4) * HERD_PITCH_X;
				int y = cy + HERD_Y + (i / 4) * HERD_PITCH_Y;
				if (inside(mouseX, mouseY, x, y, HERD_W, HERD_H)) {
					String kind = herds.get(i).kind();
					selectedHerd = kind.equals(selectedHerd) ? null : kind;
					rebuildPreservingInput();
					return true;
				}
			}
			return false;
		}
		if (!job.usesList()) {
			return false;
		}
		if (jobPicker) {
			List<ResourceCatalog.Entry> entries = ResourceCatalog.forJob(job);
			int offset = (CW - PICK_COLUMNS * PICK_PITCH) / 2;
			for (int i = 0; i < entries.size(); i++) {
				int x = cx + offset + (i % PICK_COLUMNS) * PICK_PITCH;
				int y = cy + GRID_Y + (i / PICK_COLUMNS) * PICK_PITCH;
				if (inside(mouseX, mouseY, x, y, 18, 18)) {
					String id = itemId(entries.get(i).item());
					if (!isOnList(view, id)) {
						command(Payloads.BotCommand.JOB_NEED, id, 64);
					}
					selectedNeed = id;
					jobPicker = false;
					rebuildPreservingInput();
					return true;
				}
			}
			return false;
		}
		List<Payloads.NeedView> needs = view.needs();
		int cells = Math.min(JobConfig.MAX_NEEDS, needs.size() + 1);
		for (int i = 0; i < cells; i++) {
			int x = cx + (i % 3) * CELL_PITCH_X;
			int y = cy + GRID_Y + (i / 3) * CELL_PITCH_Y;
			if (!inside(mouseX, mouseY, x, y, CELL_W, CELL_H)) {
				continue;
			}
			if (i >= needs.size()) {
				jobPicker = true;
				selectedNeed = null;
			} else {
				String id = needs.get(i).item();
				selectedNeed = id.equals(selectedNeed) ? null : id;
			}
			rebuildPreservingInput();
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- Style tab

	private void initStyle() {
		Payloads.BotEntry bot = selected();
		renameBox = new EditBox(font, cx + 1, cy + 13, 168, 18, Component.translatable("butterbot.gui.rename"));
		renameBox.setMaxLength(32);
		renameBox.setHint(Component.translatable("butterbot.gui.rename_hint"));
		if (bot != null) {
			renameBox.setValue(bot.name());
		}
		addRenderableWidget(renameBox);
		addRenderableWidget(McButton.text(cx + 174, cy + 12, 65, 20, Component.translatable("butterbot.gui.save"), b -> {
			if (renameBox != null) {
				order(Payloads.BotCommand.RENAME, renameBox.getValue());
			}
		}));

		addRenderableWidget(McButton.toggle(cx, cy + 38, 118, 20, Component.translatable("butterbot.ui.neko"), new ItemStack(Items.COD),
				() -> live(Payloads.BotEntry::neko), b -> command(Payloads.BotCommand.SET_NEKO, "", live(Payloads.BotEntry::neko) ? 0 : 1))
				.tip(Component.translatable("butterbot.gui.neko_tooltip")));
		addRenderableWidget(McButton.icon(cx + 121, cy + 38, 118, 20, Component.empty(), new ItemStack(Items.LEATHER_CHESTPLATE), b -> {
			Payloads.BotEntry current = selected();
			if (current != null) {
				command(Payloads.BotCommand.DEFAULT_SKIN, "", nextBuiltInSkin(current));
			}
		}).label(() -> {
			Payloads.BotEntry current = selected();
			int next = current == null ? 0 : nextBuiltInSkin(current);
			return Component.translatable("butterbot.gui.default_skin_next", Component.translatable("butterbot.skin.default." + next));
		}).tip(Component.translatable("butterbot.gui.default_skin_tooltip", ClientSkinManager.defaultSkinCount())));

		List<ClientSkinManager.LocalSkin> skins = ClientSkinManager.rescan();
		int pages = Math.max(1, (skins.size() + SKIN_ROWS - 1) / SKIN_ROWS);
		skinPage = Math.min(skinPage, pages - 1);
		if (selectedSkinIndex >= skins.size()) {
			selectedSkinIndex = -1;
		}
		String currentSkin = bot == null ? "" : bot.skinId();
		int start = skinPage * SKIN_ROWS;
		for (int i = start; i < Math.min(skins.size(), start + SKIN_ROWS); i++) {
			ClientSkinManager.LocalSkin skin = skins.get(i);
			int index = i;
			Component label = Component.literal(skin.displayName());
			if (skin.skinId().equals(currentSkin)) {
				label = Component.literal("✔ ").append(label);
			}
			addRenderableWidget(McButton.text(cx, cy + 76 + (i - start) * 20, CW, 18, label,
					b -> selectedSkinIndex = selectedSkinIndex == index ? -1 : index)
					.selected(() -> selectedSkinIndex == index)
					.tip(Component.literal(skin.fileName() + " (" + skin.width() + "x" + skin.height() + ")")));
		}
		if (pages > 1) {
			addRenderableWidget(McButton.text(cx + CW - 30, cy + 62, 14, 12, Component.literal("<"), b -> {
				skinPage = Math.max(0, skinPage - 1);
				rebuildPreservingInput();
			}).enabled(skinPage > 0));
			addRenderableWidget(McButton.text(cx + CW - 14, cy + 62, 14, 12, Component.literal(">"), b -> {
				skinPage = Math.min(pages - 1, skinPage + 1);
				rebuildPreservingInput();
			}).enabled(skinPage < pages - 1));
		}

		int by = cy + 176;
		addRenderableWidget(McButton.text(cx, by, 78, 18, Component.translatable("butterbot.ui.apply"), b -> {
			List<ClientSkinManager.LocalSkin> list = ClientSkinManager.localSkins();
			Payloads.BotEntry current = selected();
			if (current == null || selectedSkinIndex < 0 || selectedSkinIndex >= list.size()) {
				flash(Component.translatable("butterbot.gui.pick_skin"), true);
				return;
			}
			ClientSkinManager.LocalSkin chosen = list.get(selectedSkinIndex);
			if (ClientSkinManager.apply(basePos, current.id(), chosen)) {
				flash(Component.translatable(ClientSkinManager.looksLikeDefault(chosen.skinId())
						? "butterbot.gui.skin_same_as_default" : "butterbot.gui.skin_sent"), false);
				selectedSkinIndex = -1;
			} else {
				flash(Component.translatable("butterbot.skin.error.corrupt"), true);
			}
		}));
		addRenderableWidget(McButton.text(cx + 80, by, 78, 18, Component.translatable("butterbot.gui.open_folder"), b -> {
			ClientSkinManager.ensureSetup();
			Blaze3D.openPath(ClientSkinManager.SKIN_DIR);
		}).tip(Component.translatable("butterbot.gui.drop_hint")));
		addRenderableWidget(McButton.text(cx + 160, by, 79, 18, Component.translatable("butterbot.gui.refresh"), b -> rebuildPreservingInput()));
	}

	private static int nextBuiltInSkin(Payloads.BotEntry bot) {
		return bot.skinId().isEmpty() ? (bot.skinVariant() + 1) % ClientSkinManager.defaultSkinCount() : bot.skinVariant();
	}

	private void renderStyle(GuiGraphicsExtractor g, Payloads.BotEntry bot) {
		g.text(font, Component.translatable("butterbot.ui.name"), cx, cy + 2, McStyle.TEXT, false);
		List<ClientSkinManager.LocalSkin> skins = ClientSkinManager.localSkins();
		g.text(font, Component.translatable("butterbot.ui.my_skins", skins.size()), cx, cy + 64, McStyle.TEXT, false);
		ClientSkinManager.skinFor(bot.skinId());
		ClientSkinManager.Status status = ClientSkinManager.statusOf(bot.skinId());
		int color = switch (status) {
			case READY, DEFAULT -> McStyle.GOOD;
			case LOADING -> McStyle.TEXT_DIM;
			case FAILED -> McStyle.BAD;
		};
		String text = font.plainSubstrByWidth(Component.translatable("butterbot.gui.skin_status." + status.name().toLowerCase(Locale.ROOT),
				Component.translatable("butterbot.skin.default." + bot.skinVariant())).getString(), 110);
		int statusRight = skins.size() > SKIN_ROWS ? cx + CW - 34 : cx + CW;
		g.text(font, text, statusRight - font.width(text), cy + 64, color, false);
		if (skins.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.no_skins"), cx, cy + 80, CW, McStyle.TEXT_DIM, false);
		}
	}

	// ---------------------------------------------------------------- More tab

	private void initMore() {
		switch (morePage) {
			case MAIN -> initMoreMain();
			case CRAFT -> initCrafting();
			case TUNNEL -> initTunnel();
			case PROGRAM -> initProgram();
			case SKILLS -> initSkills();
			case STATS -> {
			}
		}
		if (morePage != MorePage.MAIN) {
			addRenderableWidget(McButton.text(cx, cy, 64, 16, Component.translatable("butterbot.ui.back"), b -> {
				saveInput();
				morePage = MorePage.MAIN;
				rebuildWidgets();
			}));
		}
	}

	private void initMoreMain() {
		addRenderableWidget(McButton.icon(cx, cy + 34, 118, 20, Component.empty(), new ItemStack(Items.ENCHANTED_BOOK), b -> {
			saveInput();
			morePage = MorePage.SKILLS;
			rebuildWidgets();
		}).label(() -> {
			Payloads.BotEntry now = selected();
			int points = now == null ? 0 : skillPoints(now);
			return points > 0 ? Component.translatable("butterbot.ui.skills_with_points", points) : Component.translatable("butterbot.ui.skills");
		}).tip(Component.translatable("butterbot.ui.skills_tip")));
		addRenderableWidget(McButton.icon(cx + 121, cy + 34, 118, 20, Component.translatable("butterbot.ui.stats"),
				new ItemStack(Items.SPYGLASS), b -> {
					saveInput();
					morePage = MorePage.STATS;
					rebuildWidgets();
				}).tip(Component.translatable("butterbot.ui.stats_tip")));
		String[] keys = {"crafting", "tunnel", "program"};
		Item[] icons = {Items.CRAFTING_TABLE, Items.RAIL, Items.WRITABLE_BOOK};
		MorePage[] pages = {MorePage.CRAFT, MorePage.TUNNEL, MorePage.PROGRAM};
		for (int i = 0; i < keys.length; i++) {
			MorePage page = pages[i];
			addRenderableWidget(McButton.tile(cx + i * 81, cy + 86, 77, 36, Component.translatable("butterbot.ui." + keys[i]),
					new ItemStack(icons[i]), b -> {
						saveInput();
						morePage = page;
						rebuildWidgets();
					}).tip(Component.translatable("butterbot.ui." + keys[i] + "_tip")));
		}
		addRenderableWidget(McButton.icon(cx, cy + 128, 118, 20, Component.translatable("butterbot.gui.link_base"),
				new ItemStack(Items.LODESTONE), b -> order(Payloads.BotCommand.LINK_BASE, ""))
				.enabled(basePos.isPresent())
				.tip(Component.translatable(basePos.isPresent() ? "butterbot.gui.link_base_tooltip" : "butterbot.ui.link_base_off")));
		addRenderableWidget(McButton.icon(cx + 121, cy + 128, 118, 20, Component.translatable("butterbot.gui.status"),
				new ItemStack(Items.PAPER), b -> command(Payloads.BotCommand.STATUS)));
		addRenderableWidget(McButton.icon(cx, cy + 152, 118, 20, Component.empty(), new ItemStack(Items.BARRIER), b -> {
			long now = System.currentTimeMillis();
			if (now < releaseArmedUntil) {
				command(Payloads.BotCommand.RELEASE);
				selectedBotId = null;
				releaseArmedUntil = 0L;
			} else {
				releaseArmedUntil = now + RELEASE_CONFIRM_MILLIS;
			}
		}).label(() -> Component.translatable(System.currentTimeMillis() < releaseArmedUntil
				? "butterbot.ui.release_confirm" : "butterbot.ui.release")).danger()
				.tip(Component.translatable("butterbot.gui.release_tooltip")));
	}

	private void renderMore(GuiGraphicsExtractor g, Payloads.BotEntry bot, int mouseX, int mouseY) {
		switch (morePage) {
			case MAIN -> renderLevel(g, bot);
			case CRAFT -> renderCrafting(g, mouseX, mouseY);
			case TUNNEL -> renderTunnel(g);
			case PROGRAM -> {
			}
			case SKILLS -> renderSkills(g, bot);
			case STATS -> renderStats(g, bot, mouseX, mouseY);
		}
		if (morePage != MorePage.MAIN) {
			String key = switch (morePage) {
				case CRAFT -> "butterbot.ui.crafting";
				case TUNNEL -> "butterbot.ui.tunnel";
				case SKILLS -> "butterbot.ui.skills";
				case STATS -> "butterbot.ui.stats";
				default -> "butterbot.ui.program";
			};
			g.text(font, Component.translatable(key), cx + 70, cy + 4, McStyle.TEXT, false);
		}
	}

	private void renderLevel(GuiGraphicsExtractor g, Payloads.BotEntry bot) {
		int level = bot.level();
		g.text(font, Component.translatable("butterbot.gui.level_title", level, BotLevels.MAX_LEVEL), cx, cy + 2, McStyle.TEXT, false);
		Component forYou = Component.translatable("butterbot.gui.stored_xp", bot.storedXp());
		g.text(font, forYou, cx + CW - font.width(forYou), cy + 2, McStyle.GOOD, false);
		boolean max = level >= BotLevels.MAX_LEVEL;
		float progress = max ? 1.0F : Math.min(1.0F, bot.workXp() / (float) BotLevels.xpToNext(level));
		McStyle.bar(g, cx, cy + 13, CW, 5, progress, McStyle.XP);
		Component xpText = max ? Component.translatable("butterbot.gui.level_max")
				: Component.translatable("butterbot.gui.xp_progress", bot.workXp(), BotLevels.xpToNext(level));
		g.text(font, xpText, cx, cy + 21, McStyle.TEXT_DIM, false);
		int points = skillPoints(bot);
		Component pointsText = Component.translatable("butterbot.ui.skill_points", points);
		g.text(font, pointsText, cx + CW - font.width(pointsText), cy + 21, points > 0 ? McStyle.GOOD : McStyle.TEXT_DIM, false);

		BotStats stats = statsOf(bot);
		String attack = String.format(Locale.ROOT, "%.1f", stats.attackDamage() + 5 + BotLevels.toolTier(level));
		Component first = Component.translatable("butterbot.ui.summary_1", stats.digSpeedPercent(), attack, Math.round(stats.maxHealth()));
		Component second = Component.translatable("butterbot.ui.summary_2", stats.fortune(), stats.veinSize(), bot.backpackSlots());
		g.text(font, font.plainSubstrByWidth(first.getString(), CW), cx, cy + 60, McStyle.TEXT, false);
		g.text(font, font.plainSubstrByWidth(second.getString(), CW), cx, cy + 71, McStyle.TEXT, false);
	}

	// ---------------------------------------------------------------- More: skills and statistics

	private static int skillPoints(Payloads.BotEntry bot) {
		int spent = 0;
		for (int rank : bot.skills()) {
			spent += Math.max(0, rank);
		}
		return BotProgress.earnedPoints(bot.level(), bot.mastery()) - spent;
	}

	private static BotStats statsOf(Payloads.BotEntry bot) {
		return BotStats.of(bot.level(), bot.skills(), bot.mastery());
	}

	/** Four branches side by side, one tile per skill; a click spends a point on it. */
	private void initSkills() {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			return;
		}
		shownSkills = bot.skills().clone();
		shownSkillLevel = bot.level();
		BotSkill.Branch[] branches = BotSkill.Branch.values();
		for (int b = 0; b < branches.length; b++) {
			for (int row = 0; row < BotSkill.ROW_LEVEL.length; row++) {
				BotSkill skill = BotSkill.at(branches[b], row);
				addRenderableWidget(McButton.tile(cx + b * SKILL_PITCH_X, cy + SKILL_TOP + row * SKILL_PITCH_Y, SKILL_W, SKILL_H,
								Component.empty(), new ItemStack(skill.icon()), button -> command(Payloads.BotCommand.SKILL_UP, "", skill.ordinal()))
						.label(() -> skillLabel(skill))
						.selected(() -> {
							Payloads.BotEntry now = selected();
							return now != null && BotProgress.rankIn(now.skills(), skill) >= skill.maxRank();
						})
						.activeWhen(() -> skillOpen(skill))
						.tip(skillTooltip(bot, skill)));
			}
		}
		addRenderableWidget(McButton.text(cx + CW - 72, cy + 181, 72, 16, Component.empty(), b -> {
			long now = System.currentTimeMillis();
			if (now < resetArmedUntil) {
				command(Payloads.BotCommand.SKILL_RESET);
				resetArmedUntil = 0L;
			} else {
				resetArmedUntil = now + RELEASE_CONFIRM_MILLIS;
			}
		}).label(() -> Component.translatable(System.currentTimeMillis() < resetArmedUntil
				? "butterbot.ui.skill_reset_confirm" : "butterbot.ui.skill_reset")).danger()
				.tip(Component.translatable("butterbot.ui.skill_reset_tip")));
	}

	private Component skillLabel(BotSkill skill) {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			return Component.empty();
		}
		int rank = BotProgress.rankIn(bot.skills(), skill);
		if (rank >= skill.maxRank()) {
			return Component.translatable("butterbot.ui.skill_max");
		}
		if (bot.level() < skill.requiredLevel()) {
			return Component.translatable("butterbot.ui.skill_locked", skill.requiredLevel());
		}
		return Component.literal(rank + "/" + skill.maxRank());
	}

	/** Open for learning (the level is high enough and the skill above it is learned); points are checked by the server. */
	private boolean skillOpen(BotSkill skill) {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			return false;
		}
		BotProgress.Refusal refusal = BotProgress.whyNot(skill, bot.level(), bot.skills(), skillPoints(bot));
		return refusal != BotProgress.Refusal.LEVEL && refusal != BotProgress.Refusal.PREVIOUS;
	}

	private static Component skillTooltip(Payloads.BotEntry bot, BotSkill skill) {
		int rank = BotProgress.rankIn(bot.skills(), skill);
		MutableComponent tip = Component.empty().append(skill.displayName().copy().withStyle(ChatFormatting.GOLD))
				.append(Component.literal(" " + rank + "/" + skill.maxRank()).withStyle(ChatFormatting.GRAY));
		tip.append("\n").append(Component.translatable("butterbot.skill." + skill.id() + ".desc").withStyle(ChatFormatting.GRAY));
		if (rank > 0) {
			tip.append("\n").append(Component.translatable("butterbot.ui.skill_now", skill.effect(rank)));
		}
		if (rank < skill.maxRank()) {
			tip.append("\n").append(Component.translatable("butterbot.ui.skill_next", skill.effect(rank + 1)).withStyle(ChatFormatting.GREEN));
		}
		if (bot.level() < skill.requiredLevel()) {
			tip.append("\n").append(Component.translatable("butterbot.ui.skill_needs_level", skill.requiredLevel()).withStyle(ChatFormatting.RED));
		}
		BotSkill previous = skill.previous();
		if (previous != null && BotProgress.rankIn(bot.skills(), previous) <= 0) {
			tip.append("\n").append(Component.translatable("butterbot.ui.skill_needs_previous", previous.displayName()).withStyle(ChatFormatting.RED));
		}
		return tip;
	}

	private void renderSkills(GuiGraphicsExtractor g, Payloads.BotEntry bot) {
		int points = skillPoints(bot);
		Component pointsText = Component.translatable("butterbot.ui.skill_points", points);
		g.text(font, pointsText, cx + CW - font.width(pointsText), cy + 4, points > 0 ? McStyle.GOOD : McStyle.TEXT_DIM, false);
		BotSkill.Branch[] branches = BotSkill.Branch.values();
		for (int b = 0; b < branches.length; b++) {
			String name = font.plainSubstrByWidth(branches[b].displayName().getString(), SKILL_W);
			g.text(font, name, cx + b * SKILL_PITCH_X + (SKILL_W - font.width(name)) / 2, cy + 21, McStyle.TEXT, false);
		}
		String hint = font.plainSubstrByWidth(Component.translatable("butterbot.ui.skill_hint").getString(), CW - 78);
		g.text(font, hint, cx, cy + 185, McStyle.TEXT_DIM, false);
	}

	/** Everything the bot can do, its job mastery, the level abilities and lifetime counters. */
	private void renderStats(GuiGraphicsExtractor g, Payloads.BotEntry bot, int mouseX, int mouseY) {
		BotStats stats = statsOf(bot);
		String attack = String.format(Locale.ROOT, "%.1f", stats.attackDamage() + 5 + BotLevels.toolTier(bot.level()));
		Component[] left = {
				Component.translatable("butterbot.ui.stat_dig", stats.digSpeedPercent()),
				Component.translatable("butterbot.ui.stat_attack", attack),
				Component.translatable("butterbot.ui.stat_health", Math.round(stats.maxHealth())),
				Component.translatable("butterbot.ui.stat_armor", Math.round(stats.armor())),
				Component.translatable("butterbot.ui.stat_speed", Math.round((stats.speedMultiplier() - 1.0D) * 100.0D)),
		};
		Component[] right = {
				Component.translatable("butterbot.ui.stat_bag", bot.backpackSlots()),
				Component.translatable("butterbot.ui.stat_fortune", stats.fortune()),
				Component.translatable("butterbot.ui.stat_vein", stats.veinSize()),
				Component.translatable("butterbot.ui.stat_radar", stats.scanRadius()),
				stats.magnetRadius() > 0 ? Component.translatable("butterbot.ui.stat_magnet", stats.magnetRadius())
						: Component.translatable("butterbot.ui.stat_magnet_off"),
		};
		for (int i = 0; i < left.length; i++) {
			g.text(font, font.plainSubstrByWidth(left[i].getString(), 116), cx, cy + 20 + i * 10, McStyle.TEXT, false);
			g.text(font, font.plainSubstrByWidth(right[i].getString(), 116), cx + 122, cy + 20 + i * 10, McStyle.TEXT, false);
		}

		g.text(font, Component.translatable("butterbot.ui.mastery_title").withStyle(ChatFormatting.BOLD), cx, cy + 74, McStyle.TEXT, false);
		int[] mastery = bot.mastery();
		for (int i = 0; i < MASTERY_JOBS.length; i++) {
			BotJob job = MASTERY_JOBS[i];
			int index = BotProgress.jobIndex(job);
			int xp = index >= 0 && index < mastery.length ? mastery[index] : 0;
			int rank = BotProgress.masteryRank(xp);
			int y = cy + 84 + i * 10;
			g.text(font, font.plainSubstrByWidth(job.displayName().getString(), 58), cx, y, McStyle.TEXT, false);
			String rankName = font.plainSubstrByWidth(Component.translatable("butterbot.mastery.rank." + rank).getString(), 62);
			g.text(font, rankName, cx + 60, y, rank >= BotProgress.MAX_MASTERY ? McStyle.GOOD : McStyle.TEXT, false);
			float progress;
			String amount;
			if (rank >= BotProgress.MAX_MASTERY) {
				progress = 1.0F;
				amount = Component.translatable("butterbot.ui.skill_max").getString();
			} else {
				int from = rank == 0 ? 0 : BotProgress.MASTERY_XP[rank - 1];
				int to = BotProgress.MASTERY_XP[rank];
				progress = Math.max(0.0F, Math.min(1.0F, (xp - from) / (float) (to - from)));
				amount = compact(xp) + "/" + compact(to);
			}
			McStyle.bar(g, cx + 124, y + 2, 60, 4, progress, McStyle.XP);
			g.text(font, amount, cx + CW - font.width(amount), y, McStyle.TEXT_DIM, false);
			if (inside(mouseX, mouseY, cx, y - 1, CW, 10)) {
				g.setComponentTooltipForNextFrame(font, List.of(
						job.displayName().copy().append(" - ").append(Component.translatable("butterbot.mastery.rank." + rank)).withStyle(ChatFormatting.AQUA),
						Component.translatable("butterbot.mastery.bonus." + job.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY),
						Component.translatable("butterbot.ui.mastery_points").withStyle(ChatFormatting.DARK_GRAY)), mouseX, mouseY);
			}
		}

		g.text(font, Component.translatable("butterbot.ui.abilities_title").withStyle(ChatFormatting.BOLD), cx, cy + 135, McStyle.TEXT, false);
		BotStats.Ability[] abilities = BotStats.Ability.values();
		for (int i = 0; i < abilities.length; i++) {
			BotStats.Ability ability = abilities[i];
			int x = cx + (i % 2) * 122;
			int y = cy + 145 + (i / 2) * 10;
			boolean unlocked = stats.has(ability);
			Component line = unlocked ? Component.translatable("butterbot.ui.ability_on", ability.displayName())
					: Component.translatable("butterbot.ui.ability_off", ability.level(), ability.displayName());
			g.text(font, font.plainSubstrByWidth(line.getString(), 118), x, y, unlocked ? McStyle.GOOD : McStyle.TEXT_DIM, false);
			if (inside(mouseX, mouseY, x, y - 1, 118, 10)) {
				g.setComponentTooltipForNextFrame(font, List.of(ability.displayName().copy().withStyle(ChatFormatting.GOLD),
						ability.description().copy().withStyle(ChatFormatting.GRAY),
						Component.translatable("butterbot.ui.ability_level", ability.level()).withStyle(ChatFormatting.DARK_GRAY)), mouseX, mouseY);
			}
		}

		int[] lifetime = bot.lifetime();
		Component life = Component.translatable("butterbot.ui.lifetime", compact(lifetimeAt(lifetime, BotProgress.Stat.MINED)),
				compact(lifetimeAt(lifetime, BotProgress.Stat.KILLS)), compact(lifetimeAt(lifetime, BotProgress.Stat.HARVESTED)),
				compact(lifetimeAt(lifetime, BotProgress.Stat.CRAFTED)));
		g.text(font, font.plainSubstrByWidth(life.getString(), CW), cx, cy + 186, McStyle.TEXT_DIM, false);
	}

	private static int lifetimeAt(int[] lifetime, BotProgress.Stat stat) {
		return stat.ordinal() < lifetime.length ? lifetime[stat.ordinal()] : 0;
	}

	// ---------------------------------------------------------------- More: crafting

	private void initCrafting() {
		craftItemBox = new EditBox(font, cx + 1, cy + 25, 170, 18, Component.translatable("butterbot.gui.item"));
		craftItemBox.setMaxLength(64);
		craftItemBox.setHint(Component.translatable("butterbot.gui.item_hint"));
		craftItemBox.setValue(craftItemText);
		craftItemBox.setResponder(this::updateSuggestions);
		addRenderableWidget(craftItemBox);

		craftCountBox = new NumberBox(font, cx + 178, cy + 25, 60, 18, Component.translatable("butterbot.gui.amount"), false, 4);
		craftCountBox.setValue(craftCountText);
		addRenderableWidget(craftCountBox);

		addRenderableWidget(McButton.icon(cx, cy + 172, CW, 20, Component.translatable("butterbot.gui.craft"),
				new ItemStack(Items.CRAFTING_TABLE), b -> {
					String item = craftItemBox == null ? "" : craftItemBox.getValue().trim();
					if (item.isEmpty()) {
						flash(Component.translatable("butterbot.gui.enter_item"), true);
						return;
					}
					int count = craftCountBox == null ? 1 : craftCountBox.intValue(1);
					order(Payloads.BotCommand.CRAFT, item, Math.max(1, count));
				}));
	}

	private void updateSuggestions(String query) {
		suggestions.clear();
		if (suppressSuggestions) {
			return;
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.length() < 2) {
			return;
		}
		List<Identifier> matches = new ArrayList<>();
		for (Identifier key : BuiltInRegistries.ITEM.keySet()) {
			Item item = BuiltInRegistries.ITEM.getValue(key);
			if (item == Items.AIR) {
				continue;
			}
			String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
			if (key.getPath().contains(q) || key.toString().contains(q) || name.contains(q)) {
				matches.add(key);
			}
		}
		matches.sort(Comparator
				.comparing((Identifier key) -> !key.getPath().startsWith(q))
				.thenComparing(key -> !key.getNamespace().equals("minecraft"))
				.thenComparingInt(key -> key.getPath().length())
				.thenComparing(Identifier::toString));
		for (int i = 0; i < Math.min(5, matches.size()); i++) {
			suggestions.add(matches.get(i));
		}
		if (suggestions.size() == 1 && suggestions.getFirst().getPath().equals(q)) {
			suggestions.clear();
		}
	}

	private void applySuggestion(Identifier key) {
		if (craftItemBox == null) {
			return;
		}
		suppressSuggestions = true;
		craftItemBox.setValue(key.getNamespace().equals("minecraft") ? key.getPath() : key.toString());
		suppressSuggestions = false;
		suggestions.clear();
	}

	private void renderCrafting(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, Component.translatable("butterbot.gui.item_amount"), cx, cy + 15, McStyle.TEXT, false);
		if (suggestions.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.craft_hint"), cx, cy + 50, CW, McStyle.TEXT_DIM, false);
			return;
		}
		int sx = cx;
		int sy = cy + 45;
		int w = 172;
		int h = suggestions.size() * 18;
		g.nextStratum();
		McStyle.inset(g, sx - 1, sy - 1, w + 2, h + 2, McStyle.PANEL);
		for (int i = 0; i < suggestions.size(); i++) {
			int rowY = sy + i * 18;
			if (inside(mouseX, mouseY, sx, rowY, w, 18)) {
				g.fill(sx, rowY, sx + w, rowY + 18, 0xFFE2E2E2);
			}
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(suggestions.get(i)));
			g.item(stack, sx + 1, rowY + 1);
			g.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w - 24), sx + 21, rowY + 5, McStyle.TEXT, false);
		}
	}

	private boolean suggestionClicked(double mouseX, double mouseY) {
		for (int i = 0; i < suggestions.size(); i++) {
			if (inside(mouseX, mouseY, cx, cy + 45 + i * 18, 172, 18)) {
				applySuggestion(suggestions.get(i));
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- More: tunnel

	private void initTunnel() {
		int y1 = cy + 34;
		int y2 = cy + 70;
		fromXBox = coordBox(cx, y1, fromXText, "X");
		fromZBox = coordBox(cx + 64, y1, fromZText, "Z");
		toXBox = coordBox(cx, y2, toXText, "X");
		toZBox = coordBox(cx + 64, y2, toZText, "Z");
		addRenderableWidget(McButton.text(cx + 130, y1 - 1, 80, 20, Component.translatable("butterbot.gui.here"), b -> fillHere(true)));
		addRenderableWidget(McButton.text(cx + 130, y2 - 1, 80, 20, Component.translatable("butterbot.gui.here"), b -> fillHere(false)));

		tunnelSizeIndex = Math.floorMod(tunnelSizeIndex, TUNNEL_SIZES.length);
		addRenderableWidget(McButton.text(cx, cy + 98, 118, 20, Component.empty(),
				b -> tunnelSizeIndex = (tunnelSizeIndex + 1) % TUNNEL_SIZES.length)
				.label(() -> Component.translatable("butterbot.gui.size", TUNNEL_SIZES[tunnelSizeIndex][0] + "x" + TUNNEL_SIZES[tunnelSizeIndex][1])));
		addRenderableWidget(McButton.toggle(cx + 121, cy + 98, 118, 20, Component.translatable("butterbot.ui.tunnel_torches"),
				new ItemStack(Items.TORCH), () -> tunnelTorches, b -> tunnelTorches = !tunnelTorches));

		addRenderableWidget(McButton.icon(cx, cy + 172, CW, 20, Component.translatable("butterbot.gui.dig_tunnel"),
				new ItemStack(Items.IRON_PICKAXE), b -> {
					if (fromXBox == null || !fromXBox.hasNumber() || !fromZBox.hasNumber() || !toXBox.hasNumber() || !toZBox.hasNumber()) {
						flash(Component.translatable("butterbot.gui.enter_coords"), true);
						return;
					}
					int[] size = TUNNEL_SIZES[tunnelSizeIndex];
					order(Payloads.BotCommand.TUNNEL, "", fromXBox.intValue(0), fromZBox.intValue(0), toXBox.intValue(0), toZBox.intValue(0),
							size[0], size[1], tunnelTorches ? 1 : 0);
				}));
	}

	private NumberBox coordBox(int x, int y, String value, String hint) {
		NumberBox box = new NumberBox(font, x + 1, y, 58, 18, Component.literal(hint), true, 8);
		box.setHint(Component.literal(hint).withStyle(ChatFormatting.GRAY));
		box.setValue(value);
		addRenderableWidget(box);
		return box;
	}

	private void fillHere(boolean from) {
		if (minecraft == null || minecraft.player == null) {
			return;
		}
		BlockPos pos = minecraft.player.blockPosition();
		if (from && fromXBox != null) {
			fromXBox.setValue(String.valueOf(pos.getX()));
			fromZBox.setValue(String.valueOf(pos.getZ()));
		} else if (!from && toXBox != null) {
			toXBox.setValue(String.valueOf(pos.getX()));
			toZBox.setValue(String.valueOf(pos.getZ()));
		}
	}

	private void renderTunnel(GuiGraphicsExtractor g) {
		g.text(font, Component.translatable("butterbot.gui.tunnel_from"), cx, cy + 23, McStyle.TEXT, false);
		g.text(font, Component.translatable("butterbot.gui.tunnel_to"), cx, cy + 59, McStyle.TEXT, false);
		if (fromXBox != null && fromXBox.hasNumber() && fromZBox.hasNumber() && toXBox.hasNumber() && toZBox.hasNumber()) {
			int dx = toXBox.intValue(0) - fromXBox.intValue(0);
			int dz = toZBox.intValue(0) - fromZBox.intValue(0);
			String dir;
			int length;
			if (Math.abs(dx) >= Math.abs(dz)) {
				dir = dx >= 0 ? "east" : "west";
				length = Math.abs(dx) + 1;
			} else {
				dir = dz >= 0 ? "south" : "north";
				length = Math.abs(dz) + 1;
			}
			Component preview = Component.translatable("butterbot.gui.tunnel_preview", length, Component.translatable("butterbot.direction." + dir));
			g.text(font, preview, cx, cy + 128, length > 512 ? McStyle.BAD : McStyle.TEXT, false);
		}
		g.text(font, Component.translatable("butterbot.gui.tunnel_hint"), cx, cy + 142, McStyle.TEXT_DIM, false);
	}

	// ---------------------------------------------------------------- More: Lua program

	private void initProgram() {
		Payloads.BotEntry bot = selected();
		scriptEditor = MultiLineEditBox.builder()
				.setX(cx)
				.setY(cy + 20)
				.setPlaceholder(Component.translatable("butterbot.gui.script_placeholder"))
				.build(font, CW, 118, Component.translatable("butterbot.ui.program"));
		scriptEditor.setCharacterLimit(LuaProgram.MAX_SCRIPT_LENGTH);
		String text;
		if (scriptDraft != null && bot != null && bot.id().equals(scriptDraftBot)) {
			text = scriptDraft;
		} else {
			text = bot == null || bot.script().isBlank() ? exampleScript() : bot.script();
		}
		scriptEditor.setValue(text, true);
		addRenderableWidget(scriptEditor);

		int y = cy + 144;
		addRenderableWidget(McButton.text(cx, y, 78, 20, Component.translatable("butterbot.gui.save_run"), b -> sendScript(true)));
		addRenderableWidget(McButton.text(cx + 80, y, 78, 20, Component.translatable("butterbot.gui.save"), b -> sendScript(false)));
		addRenderableWidget(McButton.text(cx + 160, y, 79, 20, Component.translatable("butterbot.gui.stop_program"),
				b -> command(Payloads.BotCommand.STOP)).danger());
		addRenderableWidget(McButton.text(cx, y + 24, 118, 20, Component.translatable("butterbot.gui.example"), b -> {
			if (scriptEditor != null) {
				scriptEditor.setValue(exampleScript(), true);
			}
		}));
		addRenderableWidget(McButton.text(cx + 121, y + 24, 118, 20, Component.translatable("butterbot.gui.api_help"), b -> {
		}).tip(Component.translatable("butterbot.gui.api_help_text")));
	}

	private void sendScript(boolean run) {
		Payloads.BotEntry bot = selected();
		if (bot == null || scriptEditor == null) {
			return;
		}
		String script = scriptEditor.getValue();
		scriptDraft = script;
		scriptDraftBot = bot.id();
		ClientPlayNetworking.send(new Payloads.SaveScript(basePos, bot.id(), script, run));
		flash(Component.translatable(run ? "butterbot.gui.program_sent" : "butterbot.gui.program_saved"), false);
	}

	private static String exampleScript() {
		return """
				-- Butter Bot program (Lua). Example: chop the nearest log, then come back.
				bot.say("What is my purpose?")
				local x, y, z = bot.scan("log", 16)
				if x then
				  bot.goTo(x, y, z)
				  bot.dig(x, y, z)
				  bot.say("Got wood!")
				else
				  bot.say("No trees nearby.")
				end
				bot.deliver()
				""";
	}

	// ================================================================ helpers

	private static ItemStack stackOf(String id) {
		Identifier identifier = Identifier.tryParse(id);
		return identifier == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(identifier));
	}

	private static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static boolean isOnList(Payloads.JobView view, String id) {
		for (Payloads.NeedView need : view.needs()) {
			if (need.item().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private static String compact(int value) {
		return value < 1000 ? String.valueOf(value) : String.format(Locale.ROOT, "%.1fk", value / 1000.0F);
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	// ================================================================ ticking and input

	@Override
	public void onFilesDrop(List<Path> files) {
		int added = 0;
		String lastError = null;
		for (Path file : files) {
			String error = ClientSkinManager.importFile(file);
			if (error == null) {
				added++;
			} else {
				lastError = error;
			}
		}
		if (added > 0) {
			flash(Component.translatable("butterbot.gui.skins_imported", added), false);
			saveInput();
			activeTab = Tab.STYLE;
			listOpen = false;
			rebuildWidgets();
		} else if (lastError != null) {
			flash(Component.translatable(lastError), true);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (++refreshTimer >= REFRESH_TICKS) {
			refreshTimer = 0;
			requestList();
		}
		if (flashTicks > 0 && --flashTicks == 0) {
			flashMessage = null;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (listOpen && botListClicked(event.x(), event.y())) {
				return true;
			}
			if (!listOpen && activeTab == Tab.JOB && jobTabClicked(event.x(), event.y())) {
				return true;
			}
			if (!listOpen && activeTab == Tab.MORE && morePage == MorePage.CRAFT && suggestionClicked(event.x(), event.y())) {
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_TAB && craftItemBox != null && craftItemBox.isFocused() && !suggestions.isEmpty()) {
			applySuggestion(suggestions.getFirst());
			return true;
		}
		// The panel key closes the panel again (unless the player is typing).
		if (BotKeys.isPanelKey(event) && !(getFocused() instanceof EditBox) && !(getFocused() instanceof MultiLineEditBox)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		saveInput();
		super.removed();
	}

	// ================================================================ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		renderForeground(graphics, mouseX, mouseY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		McStyle.panel(graphics, left, top, PANEL_W, BODY_H);
		McStyle.inset(graphics, cardX, cardY, CARD_W, CARD_H, McStyle.BLACK);
	}

	private void renderForeground(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		renderCard(g);
		Payloads.BotEntry bot = selected();
		if (!receivedList) {
			g.text(font, Component.translatable("butterbot.gui.loading"), cx, cy + 4, McStyle.TEXT_DIM, false);
		} else if (bots.isEmpty() || bot == null) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.no_bots"), cx, cy + 4, CW, McStyle.TEXT, false);
		} else if (listOpen) {
			renderBotList(g, mouseX, mouseY);
		} else {
			switch (activeTab) {
				case HOME -> renderHome(g);
				case MINE -> renderMine(g);
				case JOB -> renderJob(g, bot, mouseX, mouseY);
				case STYLE -> renderStyle(g, bot);
				case MORE -> renderMore(g, bot, mouseX, mouseY);
			}
		}

		int footerY = top + BODY_H + 4;
		if (flashMessage != null) {
			g.text(font, flashMessage, left + 4, footerY, flashIsError ? McStyle.BAD_LIGHT : McStyle.GOOD_LIGHT, true);
		} else if (basePos.isPresent()) {
			g.text(font, Component.translatable("butterbot.gui.base_at", basePos.get().toShortString()), left + 4, footerY,
					McStyle.TEXT_LIGHT_DIM, true);
		}
	}

	/** Front view of a player skin (base layer plus the outer layer), {@code scale} screen pixels per skin pixel. */
	private static void drawPaperDoll(GuiGraphicsExtractor g, ClientSkinManager.SkinInfo skin, int x, int y, int scale) {
		int arm = skin.slim() ? 3 : 4;
		// {u, v, width, height, x, y} in skin pixels; overlay parts come right after their base part.
		int[][] parts = {
				{8, 8, 8, 8, 4, 0}, {40, 8, 8, 8, 4, 0},
				{20, 20, 8, 12, 4, 8}, {20, 36, 8, 12, 4, 8},
				{44, 20, arm, 12, 4 - arm, 8}, {44, 36, arm, 12, 4 - arm, 8},
				{36, 52, arm, 12, 12, 8}, {52, 52, arm, 12, 12, 8},
				{4, 20, 4, 12, 4, 20}, {4, 36, 4, 12, 4, 20},
				{20, 52, 4, 12, 8, 20}, {4, 52, 4, 12, 8, 20},
		};
		for (int[] part : parts) {
			g.blit(RenderPipelines.GUI_TEXTURED, skin.texture(), x + part[4] * scale, y + part[5] * scale, part[0], part[1],
					part[2] * scale, part[3] * scale, part[2], part[3], SkinLimits.BASE_SIZE, SkinLimits.BASE_SIZE);
		}
	}
}
