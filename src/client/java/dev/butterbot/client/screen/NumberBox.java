package dev.butterbot.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** An edit box that only accepts digits (and optionally a leading minus sign). */
public class NumberBox extends EditBox {

	private final boolean allowNegative;

	public NumberBox(Font font, int x, int y, int width, int height, Component narration, boolean allowNegative, int maxLength) {
		super(font, x, y, width, height, narration);
		this.allowNegative = allowNegative;
		setMaxLength(maxLength);
	}

	@Override
	public void insertText(String input) {
		StringBuilder filtered = new StringBuilder();
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (Character.isDigit(c)) {
				filtered.append(c);
			} else if (c == '-' && allowNegative && getCursorPosition() == 0 && !getValue().startsWith("-") && filtered.isEmpty()) {
				filtered.append(c);
			}
		}
		if (!filtered.isEmpty()) {
			super.insertText(filtered.toString());
		}
	}

	public int intValue(int fallback) {
		try {
			return Integer.parseInt(getValue().trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public boolean hasNumber() {
		String value = getValue().trim();
		return !value.isEmpty() && !value.equals("-");
	}
}
