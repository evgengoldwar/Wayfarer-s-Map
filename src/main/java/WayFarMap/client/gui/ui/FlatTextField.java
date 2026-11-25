package WayFarMap.client.gui.ui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

import WayFarMap.Config;

/**
 * Text field drawn as a flat box, with an optional grey hint while it is empty. A right click on it clears its text
 * (see {@link Config#rightClickClearsText}).
 */
public class FlatTextField extends GuiTextField {

    private final FontRenderer font;
    public final int boxX, boxY, boxWidth, boxHeight;
    private String hint = "";
    private Runnable onCleared;

    public FlatTextField(FontRenderer font, int x, int y, int width, int height) {
        // The vanilla field only draws the text; the box around it is drawn here.
        super(font, x + 4, y + (height - 8) / 2, width - 8, 8);
        this.font = font;
        this.boxX = x;
        this.boxY = y;
        this.boxWidth = width;
        this.boxHeight = height;
        setEnableBackgroundDrawing(false);
    }

    public FlatTextField setHint(String hint) {
        this.hint = hint;
        return this;
    }

    /** Called after a right click cleared the text, so the screen can follow it (a search, a check). */
    public FlatTextField setOnCleared(Runnable onCleared) {
        this.onCleared = onCleared;
        return this;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return Theme.inside(mouseX, mouseY, boxX, boxY, boxX + boxWidth, boxY + boxHeight);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (isMouseOver(mouseX, mouseY)) {
            // Clamp into the text area so clicks anywhere in the box focus the field.
            int textY = boxY + (boxHeight - 8) / 2 + 4;
            super.mouseClicked(Math.max(boxX + 4, Math.min(boxX + boxWidth - 5, mouseX)), textY, button);
            if (button == 1 && Config.rightClickClearsText && !getText().isEmpty()) {
                setText("");
                setFocused(true);
                if (onCleared != null) {
                    onCleared.run();
                }
            }
        } else {
            super.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    public void drawTextBox() {
        int x1 = boxX + boxWidth, y1 = boxY + boxHeight;
        boolean drawn = isFocused() ? Theme.sprite(boxX, boxY, x1, y1, "field.focused", "field")
            : Theme.sprite(boxX, boxY, x1, y1, "field");
        if (!drawn) {
            Theme.fill(boxX, boxY, x1, y1, Theme.FIELD);
            Theme.outline(boxX, boxY, x1, y1, isFocused() ? Theme.ACCENT : Theme.BORDER);
        }
        if (getText().isEmpty() && !isFocused() && !hint.isEmpty()) {
            font.drawString(
                Theme.ellipsize(font, hint, boxWidth - 8),
                boxX + 4,
                boxY + (boxHeight - 8) / 2,
                Theme.TEXT_DISABLED);
        }
        setTextColor(Theme.FIELD_TEXT & 0xFFFFFF);
        super.drawTextBox();
    }
}
