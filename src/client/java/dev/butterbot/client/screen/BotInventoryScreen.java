package dev.butterbot.client.screen;

import dev.butterbot.entity.GearSlot;
import dev.butterbot.menu.BotInventoryMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A bot's gear row and backpack, drawn from the vanilla chest texture: the title bar, one slot row for
 * gear, a small gap, the backpack rows and the player's inventory.
 */
public class BotInventoryScreen extends AbstractContainerScreen<BotInventoryMenu> {
	private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final int LABEL_COLOR = 0xFF404040;

	private final int rows;

	public BotInventoryScreen(BotInventoryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, BotInventoryMenu.imageHeight(menu.rows()));
		this.rows = menu.rows();
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		int gearTop = BotInventoryMenu.GEAR_Y - 1;
		int backpackTop = BotInventoryMenu.BACKPACK_Y - 1;
		// Title bar, the gear row, a plain strip between gear and backpack, the backpack, the player's inventory.
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0.0F, 0.0F, this.imageWidth, gearTop, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y + gearTop, 0.0F, 17.0F, this.imageWidth, 18, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y + gearTop + 18, 0.0F, 8.0F, this.imageWidth,
				backpackTop - gearTop - 18, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y + backpackTop, 0.0F, 17.0F, this.imageWidth, rows * 18, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y + backpackTop + rows * 18, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		super.extractLabels(graphics, xm, ym);
		Entity entity = this.minecraft.level == null ? null : this.minecraft.level.getEntity(this.menu.botId());
		if (entity instanceof LivingEntity bot) {
			Component armor = Component.translatable("butterbot.gui.gear_armor", bot.getArmorValue());
			graphics.text(this.font, armor, this.imageWidth - 8 - this.font.width(armor), this.titleLabelY, LABEL_COLOR, false);
		}
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		Slot slot = this.hoveredSlot;
		GearSlot gear = slot == null ? null : BotInventoryMenu.gearSlot(slot);
		if (gear != null && !slot.hasItem() && this.menu.getCarried().isEmpty()) {
			List<Component> lines = List.of(
					Component.translatable("butterbot.gear." + gear.key()),
					Component.translatable("butterbot.gear." + gear.key() + ".hint").withStyle(ChatFormatting.GRAY));
			graphics.setTooltipForNextFrame(this.font, lines, Optional.empty(), mouseX, mouseY);
		}
	}

	@Override
	protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
		List<Component> lines = super.getTooltipFromContainerItem(stack);
		GearSlot gear = this.hoveredSlot == null ? null : BotInventoryMenu.gearSlot(this.hoveredSlot);
		if (gear == null || gear.isArmor() || !GearSlot.isResting(stack)) {
			return lines;
		}
		List<Component> withNote = new ArrayList<>(lines);
		withNote.add(Math.min(1, withNote.size()), Component.translatable("butterbot.gui.gear_resting").withStyle(ChatFormatting.RED));
		return withNote;
	}
}
