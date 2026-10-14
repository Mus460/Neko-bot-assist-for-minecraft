package dev.butterbot.client.screen;

import com.mojang.blaze3d.Blaze3D;
import dev.butterbot.ai.OreFinder;
import dev.butterbot.ai.actions.LightAreaAction;
import dev.butterbot.ai.actions.FarmTaskAction;
import dev.butterbot.ai.actions.SortTaskAction;
import dev.butterbot.client.skin.ClientSkinManager;
import dev.butterbot.entity.BotLevels;
import dev.butterbot.entity.BotResource;
import dev.butterbot.entity.DeliveryMode;
import dev.butterbot.entity.SmeltMode;
import dev.butterbot.lua.LuaProgram;
import dev.butterbot.network.Payloads;
import dev.butterbot.skin.SkinLimits;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The Bot control panel, opened from a Bot Base (manages every bot nearby) or by right-clicking a
 * bot (focused on that bot). Everything is validated again on the server.
 */
public class BotControlScreen extends Screen {

	private enum Tab {
		BOTS("bots"), CONTROL("control"), MINING("mining"), FARM("farm"), CRAFTING("crafting"), TUNNEL("tunnel"),
		PROGRAM("program"), SKIN("skin"), LEVEL("level");

		final String key;

		Tab(String key) {
			this.key = key;
		}

		Component label() {
			return Component.translatable("butterbot.gui.tab." + key);
		}
	}

	private static final int PANEL_W = 320;
	private static final int PANEL_H = 224;
	private static final int CONTENT_W = 210;
	private static final int ROWS_PER_PAGE = 5;
	private static final int SKIN_ROWS = 4;
	private static final int REFRESH_TICKS = 40;
	private static final int[][] TUNNEL_SIZES = {{2, 2}, {3, 3}, {1, 2}, {3, 2}, {5, 3}};

	private static final int COLOR_COVER = 0xFF3B2417;
	private static final int COLOR_COVER_EDGE = 0xFF1F120A;
	private static final int COLOR_PARCHMENT = 0xFFF1E5C4;
	private static final int COLOR_PARCHMENT_DARK = 0xFFDCCB9F;
	private static final int COLOR_FRAME = 0xFF7C5B39;
	private static final int COLOR_TEXT = 0xFF3F2A18;
	private static final int COLOR_TEXT_DIM = 0xFF6E5A3E;
	private static final int COLOR_GOOD = 0xFF2E6B1F;
	private static final int COLOR_BAD = 0xFF8A2B1C;
	private static final int COLOR_XP = 0xFF7FB838;

	// Remembered between openings so the panel feels persistent.
	@Nullable
	private static UUID selectedBotId;
	private static Tab activeTab = Tab.BOTS;
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

	private Optional<BlockPos> basePos;
	@Nullable
	private final UUID focusBot;
	private final List<Payloads.BotEntry> bots = new ArrayList<>();
	private boolean receivedList;
	private int refreshTimer;
	private int botsPage;
	private int skinPage;
	private int selectedSkinIndex = -1;
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
	private int contentX;
	private int contentY;

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
	@Nullable
	private ParchmentButton deliveryButton;
	private final List<Identifier> suggestions = new ArrayList<>();
	private boolean suppressSuggestions;

	public BotControlScreen(Optional<BlockPos> basePos, @Nullable UUID focusBot) {
		super(Component.translatable(basePos.isPresent() ? "butterbot.gui.title_base" : "butterbot.gui.title_bot"));
		this.basePos = basePos;
		this.focusBot = focusBot;
		if (focusBot != null) {
			selectedBotId = focusBot;
			if (activeTab == Tab.BOTS) {
				activeTab = Tab.CONTROL;
			}
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
		boolean selectionChanged = previousSelection == null ? selectedBotId != null : !previousSelection.equals(selectedBotId);

		if (firstList || selectionChanged || activeTab == Tab.BOTS || activeTab == Tab.LEVEL) {
			rebuildPreservingInput();
		} else if (deliveryButton != null) {
			deliveryButton.setMessage(deliveryLabel());
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

	private void flash(Component message, boolean error) {
		flashMessage = message;
		flashTicks = 80;
		flashIsError = error;
	}

	// ================================================================ layout

	@Override
	protected void init() {
		left = (width - PANEL_W) / 2;
		top = (height - PANEL_H) / 2;
		contentX = left + 100;
		contentY = top + 30;
		resetWidgetRefs();

		if (receivedList && selected() == null && activeTab != Tab.BOTS) {
			activeTab = Tab.BOTS;
		}

		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			ParchmentButton button = ParchmentButton.ribbon(left + 10, top + 28 + i * 20, 80, 18, tab.label(), b -> switchTab(tab));
			button.selected(tab == activeTab);
			button.active = receivedList && (tab == Tab.BOTS || selected() != null);
			addRenderableWidget(button);
		}

		if (!receivedList) {
			// Wait for the bot list before building the tab contents; the remembered tab is kept.
			requestList();
			return;
		}

		switch (activeTab) {
			case BOTS -> initBotsTab();
			case CONTROL -> initControlTab();
			case MINING -> initMiningTab();
			case FARM -> initFarmTab();
			case CRAFTING -> initCraftingTab();
			case TUNNEL -> initTunnelTab();
			case PROGRAM -> initProgramTab();
			case SKIN -> initSkinTab();
			case LEVEL -> initLevelTab();
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
		deliveryButton = null;
		suggestions.clear();
	}

	private void switchTab(Tab tab) {
		if (tab == activeTab) {
			return;
		}
		saveInput();
		activeTab = tab;
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

	// ---------------------------------------------------------------- Bots tab

	private void initBotsTab() {
		int pages = Math.max(1, (bots.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		botsPage = Math.min(botsPage, pages - 1);
		int y = contentY + 14;
		int start = botsPage * ROWS_PER_PAGE;
		for (int i = start; i < Math.min(bots.size(), start + ROWS_PER_PAGE); i++) {
			Payloads.BotEntry bot = bots.get(i);
			boolean isSelected = bot.id().equals(selectedBotId);
			Component label = Component.literal(bot.name());
			ParchmentButton row = ParchmentButton.parchment(contentX, y, 185, 18, label, b -> {
				selectedBotId = bot.id();
				rebuildPreservingInput();
			}).selected(isSelected);
			row.setTooltip(Tooltip.create(bot.status()));
			addRenderableWidget(row);

			ParchmentButton release = ParchmentButton.danger(contentX + 188, y, 22, 18, Component.literal("✖"), b -> {
				selectedBotId = bot.id();
				command(Payloads.BotCommand.RELEASE);
				selectedBotId = null;
			});
			release.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.release_tooltip")));
			addRenderableWidget(release);
			y += 22;
		}

		if (pages > 1) {
			int py = contentY + 14 + ROWS_PER_PAGE * 22;
			ParchmentButton prev = ParchmentButton.parchment(contentX, py, 24, 16, Component.literal("<"), b -> {
				botsPage = Math.max(0, botsPage - 1);
				rebuildWidgets();
			});
			prev.active = botsPage > 0;
			addRenderableWidget(prev);
			ParchmentButton next = ParchmentButton.parchment(contentX + 186, py, 24, 16, Component.literal(">"), b -> {
				botsPage = Math.min(pages - 1, botsPage + 1);
				rebuildWidgets();
			});
			next.active = botsPage < pages - 1;
			addRenderableWidget(next);
		}
	}

	// ---------------------------------------------------------------- Control tab

	private void initControlTab() {
		Payloads.BotEntry bot = selected();
		int colW = 102;
		int x2 = contentX + colW + 6;
		int y = contentY + 14;

		addRenderableWidget(ParchmentButton.parchment(contentX, y, colW, 20, Component.translatable("butterbot.gui.summon"),
				b -> command(Payloads.BotCommand.SUMMON)));
		addRenderableWidget(ParchmentButton.parchment(x2, y, colW, 20, Component.translatable("butterbot.gui.follow"),
				b -> command(Payloads.BotCommand.FOLLOW)));
		y += 24;
		addRenderableWidget(ParchmentButton.danger(contentX, y, colW, 20, Component.translatable("butterbot.gui.stop"),
				b -> command(Payloads.BotCommand.STOP)));
		addRenderableWidget(ParchmentButton.parchment(x2, y, colW, 20, Component.translatable("butterbot.gui.deliver"),
				b -> command(Payloads.BotCommand.DELIVER)));
		y += 24;
		addRenderableWidget(ParchmentButton.parchment(contentX, y, colW, 20, Component.translatable("butterbot.gui.backpack"),
				b -> command(Payloads.BotCommand.BACKPACK)));
		addRenderableWidget(ParchmentButton.parchment(x2, y, colW, 20, Component.translatable("butterbot.gui.status"),
				b -> command(Payloads.BotCommand.STATUS)));
		y += 24;
		deliveryButton = ParchmentButton.parchment(contentX, y, colW, 20, deliveryLabel(), b -> {
			Payloads.BotEntry current = selected();
			if (current != null) {
				command(Payloads.BotCommand.DELIVERY_MODE, "", DeliveryMode.byOrdinal(current.deliveryMode()).next().ordinal());
			}
		});
		deliveryButton.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.delivery_tooltip")));
		addRenderableWidget(deliveryButton);
		ParchmentButton link = ParchmentButton.parchment(x2, y, colW, 20, Component.translatable("butterbot.gui.link_base"),
				b -> command(Payloads.BotCommand.LINK_BASE));
		link.active = basePos.isPresent();
		link.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.link_base_tooltip")));
		addRenderableWidget(link);
		y += 30;

		renameBox = new EditBox(font, contentX + 1, y + 1, 140, 18, Component.translatable("butterbot.gui.rename"));
		renameBox.setMaxLength(32);
		renameBox.setHint(Component.translatable("butterbot.gui.rename_hint"));
		if (bot != null) {
			renameBox.setValue(bot.name());
		}
		addRenderableWidget(renameBox);
		addRenderableWidget(ParchmentButton.parchment(contentX + 146, y, 64, 20, Component.translatable("butterbot.gui.rename"), b -> {
			if (renameBox != null) {
				command(Payloads.BotCommand.RENAME, renameBox.getValue());
			}
		}));
	}

	private Component deliveryLabel() {
		Payloads.BotEntry bot = selected();
		DeliveryMode mode = bot == null ? DeliveryMode.OWNER : DeliveryMode.byOrdinal(bot.deliveryMode());
		return Component.translatable(mode == DeliveryMode.BASE ? "butterbot.gui.delivery_base" : "butterbot.gui.delivery_owner");
	}

	// ---------------------------------------------------------------- Mining tab

	private void initMiningTab() {
		BotResource[] resources = BotResource.values();
		resourceIndex = Math.floorMod(resourceIndex, resources.length);
		for (int i = 0; i < resources.length; i++) {
			BotResource resource = resources[i];
			int col = i % 3;
			int row = i / 3;
			int index = i;
			addRenderableWidget(ParchmentButton.parchment(contentX + col * 71, contentY + 16 + row * 21, 68, 18,
					resource.displayName(), b -> {
						resourceIndex = index;
						rebuildPreservingInput();
					}).selected(i == resourceIndex));
		}
		int y = contentY + 16 + 4 * 21 + 8;
		mineCountBox = new NumberBox(font, contentX + 61, y + 1, 44, 18, Component.translatable("butterbot.gui.amount"), false, 4);
		mineCountBox.setValue(mineCountText);
		addRenderableWidget(mineCountBox);
		int[] quick = {16, 64, 256};
		for (int i = 0; i < quick.length; i++) {
			int amount = quick[i];
			addRenderableWidget(ParchmentButton.parchment(contentX + 112 + i * 33, y, 30, 20, Component.literal(String.valueOf(amount)), b -> {
				if (mineCountBox != null) {
					mineCountBox.setValue(String.valueOf(amount));
				}
			}));
		}
		addRenderableWidget(ParchmentButton.accent(contentX, y + 28, 138, 20, Component.translatable("butterbot.gui.start_mining"), b -> {
			int count = mineCountBox == null ? 64 : mineCountBox.intValue(64);
			BotResource resource = BotResource.values()[resourceIndex];
			command(Payloads.BotCommand.MINE, resource.id(), Math.max(1, count));
			flash(Component.translatable("butterbot.gui.order_sent"), false);
		}));
		ParchmentButton find = ParchmentButton.parchment(contentX + 142, y + 28, 68, 20, Component.translatable("butterbot.gui.find"), b -> {
			command(Payloads.BotCommand.FIND, BotResource.values()[resourceIndex].id());
			flash(Component.translatable("butterbot.gui.find_sent"), false);
		});
		find.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.find_tooltip", OreFinder.RADIUS)));
		addRenderableWidget(find);
	}

	// ---------------------------------------------------------------- Farm tab

	private void initFarmTab() {
		addRenderableWidget(ParchmentButton.accent(contentX, contentY + 70, CONTENT_W, 20, Component.translatable("butterbot.gui.start_farming"), b -> {
			command(Payloads.BotCommand.FARM);
			flash(Component.translatable("butterbot.gui.order_sent"), false);
		}));
		addRenderableWidget(ParchmentButton.parchment(contentX, contentY + 148, 103, 20, Component.translatable("butterbot.gui.sort_chests"), b -> {
			command(Payloads.BotCommand.SORT);
			flash(Component.translatable("butterbot.gui.order_sent"), false);
		}));
		ParchmentButton light = ParchmentButton.parchment(contentX + 107, contentY + 148, 103, 20, Component.translatable("butterbot.gui.light_area"), b -> {
			command(Payloads.BotCommand.LIGHT, "", LightAreaAction.DEFAULT_RADIUS);
			flash(Component.translatable("butterbot.gui.order_sent"), false);
		});
		light.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.light_tooltip", LightAreaAction.DEFAULT_RADIUS)));
		addRenderableWidget(light);
	}

	private void renderFarmTab(GuiGraphicsExtractor g) {
		g.textWithWordWrap(font, Component.translatable("butterbot.gui.farm_help", FarmTaskAction.RADIUS), contentX, contentY + 14,
				CONTENT_W, COLOR_TEXT_DIM, false);
		g.textWithWordWrap(font, Component.translatable("butterbot.gui.sort_help", SortTaskAction.RADIUS), contentX, contentY + 98,
				CONTENT_W, COLOR_TEXT_DIM, false);
	}

	// ---------------------------------------------------------------- Level tab

	private void initLevelTab() {
		Payloads.BotEntry bot = selected();
		if (bot == null) {
			return;
		}
		int colW = 102;
		int x2 = contentX + colW + 6;
		int y = contentY + 94;
		toggleButton(contentX, y, colW, bot.guard(), "butterbot.gui.guard", Payloads.BotCommand.GUARD, "butterbot.gui.guard_tooltip");
		toggleButton(x2, y, colW, bot.autoTorch(), "butterbot.gui.torches_auto", Payloads.BotCommand.AUTO_TORCH,
				"butterbot.gui.torches_auto_tooltip");
		y += 23;
		toggleButton(contentX, y, colW, bot.feedOwner(), "butterbot.gui.feed", Payloads.BotCommand.FEED, "butterbot.gui.feed_tooltip");
		toggleButton(x2, y, colW, bot.autoRefill(), "butterbot.gui.refill", Payloads.BotCommand.REFILL, "butterbot.gui.refill_tooltip");
		y += 23;
		SmeltMode mode = SmeltMode.byOrdinal(bot.smeltMode());
		ParchmentButton smelt = ParchmentButton.parchment(contentX, y, colW, 20,
				Component.translatable("butterbot.gui.smelt", Component.translatable(mode.key())), b -> {
					Payloads.BotEntry current = selected();
					if (current != null) {
						command(Payloads.BotCommand.SMELT_MODE, "", SmeltMode.byOrdinal(current.smeltMode()).next().ordinal());
					}
				}).selected(mode != SmeltMode.OFF);
		smelt.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.smelt_tooltip")));
		addRenderableWidget(smelt);
	}

	private void toggleButton(int x, int y, int width, boolean on, String key, int action, String tooltipKey) {
		ParchmentButton button = ParchmentButton.parchment(x, y, width, 20,
				Component.translatable(key, Component.translatable(on ? "butterbot.gui.on" : "butterbot.gui.off")),
				b -> command(action, "", on ? 0 : 1)).selected(on);
		button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
		addRenderableWidget(button);
	}

	private void renderLevelTab(GuiGraphicsExtractor g, @Nullable Payloads.BotEntry bot) {
		if (bot == null) {
			return;
		}
		int level = bot.level();
		int y = contentY + 14;
		g.text(font, Component.translatable("butterbot.gui.level_title", level, BotLevels.MAX_LEVEL), contentX, y, COLOR_TEXT, false);
		Component forYou = Component.translatable("butterbot.gui.stored_xp", bot.storedXp());
		g.text(font, forYou, contentX + CONTENT_W - font.width(forYou), y, COLOR_GOOD, false);

		int barY = y + 12;
		boolean max = level >= BotLevels.MAX_LEVEL;
		float progress = max ? 1.0F : Math.min(1.0F, bot.workXp() / (float) BotLevels.xpToNext(level));
		g.fill(contentX, barY, contentX + CONTENT_W, barY + 6, COLOR_FRAME);
		g.fill(contentX + 1, barY + 1, contentX + CONTENT_W - 1, barY + 5, COLOR_PARCHMENT_DARK);
		g.fill(contentX + 1, barY + 1, contentX + 1 + Math.round((CONTENT_W - 2) * progress), barY + 5, COLOR_XP);
		Component xpText = max ? Component.translatable("butterbot.gui.level_max")
				: Component.translatable("butterbot.gui.xp_progress", bot.workXp(), BotLevels.xpToNext(level));
		g.text(font, xpText, contentX, barY + 9, COLOR_TEXT_DIM, false);

		int tier = BotLevels.toolTier(level);
		int fortune = BotLevels.fortune(level);
		int vein = BotLevels.veinSize(level);
		String attack = String.format(Locale.ROOT, "%.1f", BotLevels.attackDamage(level) + 5 + tier);
		Component[] perks = {
				Component.translatable("butterbot.gui.perk_speed", BotLevels.digSpeedPercent(level)),
				Component.translatable("butterbot.gui.perk_body", BotLevels.backpackSize(level), (int) BotLevels.maxHealth(level)),
				Component.translatable("butterbot.gui.perk_attack", attack, Component.translatable("butterbot.gui.tier." + tier)),
				fortune > 0 ? Component.translatable("butterbot.gui.perk_fortune", fortune)
						: Component.translatable("butterbot.gui.perk_fortune_locked", 5),
				vein > 0 ? Component.translatable("butterbot.gui.perk_vein", vein)
						: Component.translatable("butterbot.gui.perk_vein_locked", 3),
		};
		int py = contentY + 46;
		for (Component perk : perks) {
			g.text(font, perk, contentX, py, COLOR_TEXT, false);
			py += 9;
		}
		g.textWithWordWrap(font, Component.translatable("butterbot.gui.level_hint"), contentX + 108, contentY + 141, CONTENT_W - 108, COLOR_TEXT_DIM, false);
	}

	// ---------------------------------------------------------------- Crafting tab

	private void initCraftingTab() {
		craftItemBox = new EditBox(font, contentX + 1, contentY + 21, 150, 18, Component.translatable("butterbot.gui.item"));
		craftItemBox.setMaxLength(64);
		craftItemBox.setHint(Component.translatable("butterbot.gui.item_hint"));
		craftItemBox.setValue(craftItemText);
		craftItemBox.setResponder(this::updateSuggestions);
		addRenderableWidget(craftItemBox);

		craftCountBox = new NumberBox(font, contentX + 157, contentY + 21, 52, 18, Component.translatable("butterbot.gui.amount"), false, 4);
		craftCountBox.setValue(craftCountText);
		addRenderableWidget(craftCountBox);

		addRenderableWidget(ParchmentButton.accent(contentX, contentY + 152, CONTENT_W, 20, Component.translatable("butterbot.gui.craft"), b -> {
			String item = craftItemBox == null ? "" : craftItemBox.getValue().trim();
			if (item.isEmpty()) {
				flash(Component.translatable("butterbot.gui.enter_item"), true);
				return;
			}
			int count = craftCountBox == null ? 1 : craftCountBox.intValue(1);
			command(Payloads.BotCommand.CRAFT, item, Math.max(1, count));
			flash(Component.translatable("butterbot.gui.order_sent"), false);
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

	// ---------------------------------------------------------------- Tunnel tab

	private void initTunnelTab() {
		int y1 = contentY + 24;
		int y2 = contentY + 62;
		fromXBox = coordBox(contentX, y1, fromXText, "X");
		fromZBox = coordBox(contentX + 64, y1, fromZText, "Z");
		toXBox = coordBox(contentX, y2, toXText, "X");
		toZBox = coordBox(contentX + 64, y2, toZText, "Z");
		addRenderableWidget(ParchmentButton.parchment(contentX + 130, y1, 80, 18, Component.translatable("butterbot.gui.here"), b -> fillHere(true)));
		addRenderableWidget(ParchmentButton.parchment(contentX + 130, y2, 80, 18, Component.translatable("butterbot.gui.here"), b -> fillHere(false)));

		int y3 = contentY + 92;
		tunnelSizeIndex = Math.floorMod(tunnelSizeIndex, TUNNEL_SIZES.length);
		addRenderableWidget(ParchmentButton.parchment(contentX, y3, 102, 20,
				Component.translatable("butterbot.gui.size", TUNNEL_SIZES[tunnelSizeIndex][0] + "x" + TUNNEL_SIZES[tunnelSizeIndex][1]), b -> {
					tunnelSizeIndex = (tunnelSizeIndex + 1) % TUNNEL_SIZES.length;
					rebuildPreservingInput();
				}));
		addRenderableWidget(ParchmentButton.parchment(contentX + 108, y3, 102, 20,
				Component.translatable(tunnelTorches ? "butterbot.gui.torches_on" : "butterbot.gui.torches_off"), b -> {
					tunnelTorches = !tunnelTorches;
					rebuildPreservingInput();
				}).selected(tunnelTorches));

		addRenderableWidget(ParchmentButton.accent(contentX, contentY + 152, CONTENT_W, 20, Component.translatable("butterbot.gui.dig_tunnel"), b -> {
			if (fromXBox == null || !fromXBox.hasNumber() || !fromZBox.hasNumber() || !toXBox.hasNumber() || !toZBox.hasNumber()) {
				flash(Component.translatable("butterbot.gui.enter_coords"), true);
				return;
			}
			int[] size = TUNNEL_SIZES[tunnelSizeIndex];
			command(Payloads.BotCommand.TUNNEL, "", fromXBox.intValue(0), fromZBox.intValue(0), toXBox.intValue(0), toZBox.intValue(0),
					size[0], size[1], tunnelTorches ? 1 : 0);
			flash(Component.translatable("butterbot.gui.order_sent"), false);
		}));
	}

	private NumberBox coordBox(int x, int y, String value, String hint) {
		NumberBox box = new NumberBox(font, x + 1, y + 1, 58, 16, Component.literal(hint), true, 8);
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
		} else if (toXBox != null) {
			toXBox.setValue(String.valueOf(pos.getX()));
			toZBox.setValue(String.valueOf(pos.getZ()));
		}
	}

	// ---------------------------------------------------------------- Program tab

	private void initProgramTab() {
		Payloads.BotEntry bot = selected();
		scriptEditor = MultiLineEditBox.builder()
				.setX(contentX)
				.setY(contentY + 12)
				.setPlaceholder(Component.translatable("butterbot.gui.script_placeholder"))
				.build(font, CONTENT_W, 110, Component.translatable("butterbot.gui.tab.program"));
		scriptEditor.setCharacterLimit(LuaProgram.MAX_SCRIPT_LENGTH);
		String text;
		if (scriptDraft != null && bot != null && bot.id().equals(scriptDraftBot)) {
			text = scriptDraft;
		} else {
			text = bot == null || bot.script().isBlank() ? exampleScript() : bot.script();
		}
		scriptEditor.setValue(text, true);
		addRenderableWidget(scriptEditor);

		int y = contentY + 128;
		addRenderableWidget(ParchmentButton.accent(contentX, y, 70, 20, Component.translatable("butterbot.gui.save_run"), b -> sendScript(true)));
		addRenderableWidget(ParchmentButton.parchment(contentX + 73, y, 64, 20, Component.translatable("butterbot.gui.save"), b -> sendScript(false)));
		addRenderableWidget(ParchmentButton.danger(contentX + 140, y, 70, 20, Component.translatable("butterbot.gui.stop_program"),
				b -> command(Payloads.BotCommand.STOP)));
		ParchmentButton example = ParchmentButton.parchment(contentX, y + 23, 103, 18, Component.translatable("butterbot.gui.example"), b -> {
			if (scriptEditor != null) {
				scriptEditor.setValue(exampleScript(), true);
			}
		});
		addRenderableWidget(example);
		ParchmentButton help = ParchmentButton.parchment(contentX + 107, y + 23, 103, 18, Component.translatable("butterbot.gui.api_help"), b -> {
		});
		help.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.api_help_text")));
		addRenderableWidget(help);
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

	// ---------------------------------------------------------------- Skin tab

	private void initSkinTab() {
		List<ClientSkinManager.LocalSkin> skins = ClientSkinManager.rescan();
		int pages = Math.max(1, (skins.size() + SKIN_ROWS - 1) / SKIN_ROWS);
		skinPage = Math.min(skinPage, pages - 1);
		if (selectedSkinIndex >= skins.size()) {
			selectedSkinIndex = -1;
		}
		Payloads.BotEntry bot = selected();
		String currentSkin = bot == null ? "" : bot.skinId();

		int y = contentY + 14;
		int start = skinPage * SKIN_ROWS;
		for (int i = start; i < Math.min(skins.size(), start + SKIN_ROWS); i++) {
			ClientSkinManager.LocalSkin skin = skins.get(i);
			int index = i;
			Component label = Component.literal(skin.displayName());
			if (skin.skinId().equals(currentSkin)) {
				label = Component.literal("✔ ").append(label);
			}
			ParchmentButton row = ParchmentButton.parchment(contentX, y, 132, 18, label, b -> {
				selectedSkinIndex = index;
				rebuildPreservingInput();
			}).selected(index == selectedSkinIndex);
			row.setTooltip(Tooltip.create(Component.literal(skin.fileName() + " (" + skin.width() + "x" + skin.height() + ")")));
			addRenderableWidget(row);
			y += 21;
		}

		int py = contentY + 14 + SKIN_ROWS * 21;
		if (pages > 1) {
			ParchmentButton prev = ParchmentButton.parchment(contentX, py, 24, 14, Component.literal("<"), b -> {
				skinPage = Math.max(0, skinPage - 1);
				rebuildPreservingInput();
			});
			prev.active = skinPage > 0;
			addRenderableWidget(prev);
			ParchmentButton next = ParchmentButton.parchment(contentX + 108, py, 24, 14, Component.literal(">"), b -> {
				skinPage = Math.min(pages - 1, skinPage + 1);
				rebuildPreservingInput();
			});
			next.active = skinPage < pages - 1;
			addRenderableWidget(next);
		}

		int by = contentY + 113;
		ParchmentButton neko = ParchmentButton.parchment(contentX, by, CONTENT_W, 18,
				Component.translatable(bot != null && bot.neko() ? "butterbot.gui.neko_on" : "butterbot.gui.neko_off"), b -> {
					Payloads.BotEntry current = selected();
					if (current != null) {
						command(Payloads.BotCommand.SET_NEKO, "", current.neko() ? 0 : 1);
					}
				}).selected(bot != null && bot.neko());
		neko.setTooltip(Tooltip.create(Component.translatable("butterbot.gui.neko_tooltip")));
		addRenderableWidget(neko);

		by += 21;
		ParchmentButton apply = ParchmentButton.accent(contentX, by, 103, 20, Component.translatable("butterbot.gui.apply_skin"), b -> {
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
			} else {
				flash(Component.translatable("butterbot.skin.error.corrupt"), true);
			}
		});
		apply.active = selectedSkinIndex >= 0;
		addRenderableWidget(apply);
		addRenderableWidget(ParchmentButton.parchment(contentX + 107, by, 103, 20, Component.translatable("butterbot.gui.default_skin"),
				b -> command(Payloads.BotCommand.SET_SKIN, "")));
		addRenderableWidget(ParchmentButton.parchment(contentX, by + 23, 103, 18, Component.translatable("butterbot.gui.open_folder"), b -> {
			ClientSkinManager.ensureSetup();
			Blaze3D.openPath(ClientSkinManager.SKIN_DIR);
		}));
		addRenderableWidget(ParchmentButton.parchment(contentX + 107, by + 23, 103, 18, Component.translatable("butterbot.gui.refresh"),
				b -> rebuildPreservingInput()));
	}

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
			if (activeTab != Tab.SKIN) {
				saveInput();
				activeTab = Tab.SKIN;
			}
			rebuildWidgets();
		} else if (lastError != null) {
			flash(Component.translatable(lastError), true);
		}
	}

	// ================================================================ ticking and input

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
		if (activeTab == Tab.CRAFTING && !suggestions.isEmpty() && event.button() == 0) {
			int sx = contentX;
			int sy = contentY + 41;
			for (int i = 0; i < suggestions.size(); i++) {
				int rowY = sy + i * 18;
				if (event.x() >= sx && event.x() < sx + 176 && event.y() >= rowY && event.y() < rowY + 18) {
					applySuggestion(suggestions.get(i));
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 258 && activeTab == Tab.CRAFTING && craftItemBox != null && craftItemBox.isFocused() && !suggestions.isEmpty()) {
			applySuggestion(suggestions.getFirst());
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
		renderBook(graphics);
	}

	private void renderBook(GuiGraphicsExtractor g) {
		g.fill(left - 2, top - 2, left + PANEL_W + 2, top + PANEL_H + 2, COLOR_COVER_EDGE);
		g.fill(left, top, left + PANEL_W, top + PANEL_H, COLOR_COVER);
		g.fill(left + 6, top + 22, left + PANEL_W - 6, top + PANEL_H - 6, COLOR_PARCHMENT);
		g.fill(left + 6, top + 22, left + 94, top + PANEL_H - 6, COLOR_PARCHMENT_DARK);
		g.outline(left + 6, top + 22, PANEL_W - 12, PANEL_H - 28, COLOR_FRAME);
		g.verticalLine(left + 94, top + 22, top + PANEL_H - 7, COLOR_FRAME);
		int stud = 0xFFC9A227;
		g.fill(left + 2, top + 2, left + 5, top + 5, stud);
		g.fill(left + PANEL_W - 5, top + 2, left + PANEL_W - 2, top + 5, stud);
		g.fill(left + 2, top + PANEL_H - 5, left + 5, top + PANEL_H - 2, stud);
		g.fill(left + PANEL_W - 5, top + PANEL_H - 5, left + PANEL_W - 2, top + PANEL_H - 2, stud);
	}

	private void renderForeground(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.centeredText(font, title, left + PANEL_W / 2, top + 7, 0xFFEEDFA9);

		Payloads.BotEntry bot = selected();
		if (!receivedList) {
			g.text(font, Component.translatable("butterbot.gui.loading"), contentX, contentY, COLOR_TEXT_DIM, false);
			return;
		}
		if (bots.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.no_bots"), contentX, contentY + 4, CONTENT_W, COLOR_TEXT, false);
			return;
		}

		if (bot != null && activeTab != Tab.BOTS) {
			g.text(font, Component.literal(bot.name()), contentX, contentY, COLOR_TEXT, false);
		}

		switch (activeTab) {
			case BOTS -> renderBotsTab(g, bot);
			case CONTROL -> renderControlTab(g, bot);
			case MINING -> g.text(font, Component.translatable("butterbot.gui.amount_label"), contentX, contentY + 16 + 4 * 21 + 14, COLOR_TEXT, false);
			case FARM -> renderFarmTab(g);
			case LEVEL -> renderLevelTab(g, bot);
			case CRAFTING -> renderCraftingTab(g, mouseX, mouseY);
			case TUNNEL -> renderTunnelTab(g, bot);
			case PROGRAM -> {
			}
			case SKIN -> renderSkinTab(g, bot);
		}

		if (flashMessage != null) {
			int color = flashIsError ? COLOR_BAD : COLOR_GOOD;
			g.text(font, flashMessage, contentX, top + PANEL_H - 17, color, false);
		} else if (activeTab == Tab.SKIN) {
			g.text(font, Component.translatable("butterbot.gui.drop_hint"), contentX, top + PANEL_H - 17, COLOR_TEXT_DIM, false);
		} else if (basePos.isPresent()) {
			g.text(font, Component.translatable("butterbot.gui.base_at", basePos.get().toShortString()), contentX, top + PANEL_H - 17, COLOR_TEXT_DIM, false);
		}
	}

	private void renderBotsTab(GuiGraphicsExtractor g, @Nullable Payloads.BotEntry bot) {
		g.text(font, Component.translatable("butterbot.gui.bots_header", bots.size()), contentX, contentY, COLOR_TEXT, false);
		if (bot == null) {
			return;
		}
		int y = contentY + 14 + ROWS_PER_PAGE * 22 + (bots.size() > ROWS_PER_PAGE ? 18 : 2);
		g.text(font, Component.empty().append(bot.status()), contentX, y, COLOR_TEXT_DIM, false);
		Component details = Component.translatable("butterbot.gui.details", bot.pos().toShortString(), bot.itemCount(),
				Math.round(bot.health() * 100.0F));
		g.text(font, details, contentX, y + 11, COLOR_TEXT_DIM, false);
	}

	private void renderControlTab(GuiGraphicsExtractor g, @Nullable Payloads.BotEntry bot) {
		if (bot == null) {
			return;
		}
		int y = contentY + 14 + 4 * 24 + 30 + 24;
		g.text(font, Component.empty().append(bot.status()), contentX, y, COLOR_TEXT_DIM, false);
		if (!bot.hasBase()) {
			g.text(font, Component.translatable("butterbot.gui.no_base_hint"), contentX, y + 11, COLOR_BAD, false);
		}
	}

	private void renderCraftingTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, Component.translatable("butterbot.gui.item_amount"), contentX, contentY + 11, COLOR_TEXT_DIM, false);
		g.textWithWordWrap(font, Component.translatable("butterbot.gui.craft_hint"), contentX, contentY + 70, CONTENT_W, COLOR_TEXT_DIM, false);
		if (suggestions.isEmpty()) {
			return;
		}
		int sx = contentX;
		int sy = contentY + 41;
		int w = 176;
		int h = suggestions.size() * 18;
		g.nextStratum();
		g.fill(sx - 1, sy - 1, sx + w + 1, sy + h + 1, COLOR_FRAME);
		g.fill(sx, sy, sx + w, sy + h, COLOR_PARCHMENT);
		for (int i = 0; i < suggestions.size(); i++) {
			int rowY = sy + i * 18;
			if (mouseX >= sx && mouseX < sx + w && mouseY >= rowY && mouseY < rowY + 18) {
				g.fill(sx, rowY, sx + w, rowY + 18, COLOR_PARCHMENT_DARK);
			}
			Item item = BuiltInRegistries.ITEM.getValue(suggestions.get(i));
			ItemStack stack = new ItemStack(item);
			g.item(stack, sx + 1, rowY + 1);
			String name = font.plainSubstrByWidth(stack.getHoverName().getString(), w - 24);
			g.text(font, name, sx + 21, rowY + 5, COLOR_TEXT, false);
		}
	}

	private void renderTunnelTab(GuiGraphicsExtractor g, @Nullable Payloads.BotEntry bot) {
		g.text(font, Component.translatable("butterbot.gui.tunnel_from"), contentX, contentY + 14, COLOR_TEXT_DIM, false);
		g.text(font, Component.translatable("butterbot.gui.tunnel_to"), contentX, contentY + 52, COLOR_TEXT_DIM, false);
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
			Component preview = Component.translatable("butterbot.gui.tunnel_preview", length,
					Component.translatable("butterbot.direction." + dir));
			g.text(font, preview, contentX, contentY + 120, length > 512 ? COLOR_BAD : COLOR_TEXT, false);
		}
		g.text(font, Component.translatable("butterbot.gui.tunnel_hint"), contentX, contentY + 134, COLOR_TEXT_DIM, false);
	}

	private void renderSkinTab(GuiGraphicsExtractor g, @Nullable Payloads.BotEntry bot) {
		List<ClientSkinManager.LocalSkin> skins = ClientSkinManager.localSkins();
		int tx = contentX + 138;
		int ty = contentY + 14;
		int size = 72;
		g.fill(tx - 1, ty - 1, tx + size + 1, ty + size + 1, COLOR_FRAME);
		g.fill(tx, ty, tx + size, ty + size, 0xFF8FB8D8);

		ClientSkinManager.SkinInfo skin;
		String caption;
		if (selectedSkinIndex >= 0 && selectedSkinIndex < skins.size()) {
			ClientSkinManager.LocalSkin local = skins.get(selectedSkinIndex);
			skin = ClientSkinManager.skinFor(local.skinId());
			caption = local.width() + "x" + local.height();
		} else if (bot != null && !bot.skinId().isEmpty()) {
			skin = ClientSkinManager.skinFor(bot.skinId());
			caption = Component.translatable("butterbot.gui.current_skin").getString();
		} else {
			skin = bot == null ? null : ClientSkinManager.defaultSkinFor(bot.id());
			caption = Component.translatable("butterbot.gui.default_skin_caption").getString();
		}
		if (skin != null) {
			drawPaperDoll(g, skin, tx + 20, ty + 4, 2);
			caption = caption + " · " + Component.translatable(skin.slim() ? "butterbot.gui.arms_slim" : "butterbot.gui.arms_wide").getString();
		}
		g.text(font, font.plainSubstrByWidth(caption, size + 2), tx, ty + size + 4, COLOR_TEXT_DIM, false);
		if (bot != null) {
			// Also starts loading the bot's skin if this computer hasn't seen it yet.
			ClientSkinManager.skinFor(bot.skinId());
			ClientSkinManager.Status status = ClientSkinManager.statusOf(bot.skinId());
			int color = switch (status) {
				case READY, DEFAULT -> COLOR_GOOD;
				case LOADING -> COLOR_TEXT_DIM;
				case FAILED -> COLOR_BAD;
			};
			String text = Component.translatable("butterbot.gui.skin_status." + status.name().toLowerCase(Locale.ROOT)).getString();
			g.text(font, font.plainSubstrByWidth(text, size + 4), tx, ty + size + 14, color, false);
		}

		if (skins.isEmpty()) {
			g.textWithWordWrap(font, Component.translatable("butterbot.gui.no_skins"), contentX, contentY + 18, 130, COLOR_TEXT_DIM, false);
		}
		if (bot != null && !bot.skinId().isEmpty() && !SkinLimits.isValidSkinId(bot.skinId())) {
			g.text(font, "?", tx, ty, COLOR_BAD, false);
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
