package WayFarMap.client.gui;

import java.awt.Color;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;

/**
 * Picks any color: a tall hue bar, a saturation/brightness square for that hue, fields for R, G, B, hex, H, S and V
 * (typed, or turned with the mouse wheel), and the old color next to the new one (a click on the old one goes back to
 * it).
 */
public class GuiColorPicker extends ScaledScreen {

    public interface Callback {

        void onPicked(int rgb);
    }

    private static final int PAD = 10, TITLE = WindowHeader.HEIGHT + 8;
    private static final int BAR_WIDTH = 12, SQUARE = 128;
    /** Height of the old and new color under the square. */
    private static final int PREVIEW_HEIGHT = 40;
    private static final int LABEL_WIDTH = 12, FIELD_WIDTH = 50, FIELD_HEIGHT = 14, FIELD_STEP = 18;
    private static final int ID_DONE = 0, ID_CANCEL = 1;
    /** Hue stops of the bar, top to bottom. */
    private static final int[] HUE_STOPS = { 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000 };

    /** The fields, top to bottom. */
    private static final int F_R = 0, F_G = 1, F_B = 2, F_HEX = 3, F_H = 4, F_S = 5, F_V = 6;
    private static final String[] LABELS = { "R", "G", "B", "#", "H", "S", "V" };
    /** Largest value of each number field (the hex field has none). */
    private static final int[] MAX = { 255, 255, 255, 0, 360, 100, 100 };

    private final GuiScreen parent;
    private final Callback callback;
    private final int oldColor;

    private float hue, saturation, brightness;
    private final FlatTextField[] fields = new FlatTextField[LABELS.length];
    private int left, top, panelWidth, panelHeight;
    /** 1 = dragging in the square, 2 = on the hue bar, 0 = none. */
    private int dragging;

    public GuiColorPicker(GuiScreen parent, int color, Callback callback) {
        this.parent = parent;
        this.callback = callback;
        this.oldColor = color & 0xFFFFFF;
        setRgb(oldColor);
    }

    private int color() {
        return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

    /** Takes the color, keeping the hue where the color has none (grey, black). */
    private void setRgb(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        if (hsb[1] > 0f && hsb[2] > 0f) {
            hue = hsb[0];
        }
        saturation = hsb[1];
        brightness = hsb[2];
    }

    // Layout: the hue bar on the left at full height, the square next to it and the fields on the right, the old and
    // new color under both.

    private int barX() {
        return left + PAD;
    }

    private int squareX() {
        return barX() + BAR_WIDTH + PAD;
    }

    private int squareY() {
        return top + TITLE;
    }

    private int fieldsX() {
        return squareX() + SQUARE + PAD + LABEL_WIDTH;
    }

    private int fieldY(int field) {
        // Gaps between R/G/B, the hex field, and H/S/V.
        int gap = field >= F_H ? 2 * 8 : field >= F_HEX ? 8 : 0;
        return squareY() + field * FIELD_STEP + gap;
    }

    /** Top of the old and new color: under the square and the last field, with room for the frame around it. */
    private int lowerY() {
        int fieldsBottom = fieldY(F_V) + FIELD_HEIGHT;
        return Math.max(squareY() + SQUARE, fieldsBottom) + PAD + 3;
    }

    private int barBottom() {
        return lowerY() + PREVIEW_HEIGHT;
    }

    /** Old and new color side by side, the same size: {x0, y0, x1, y1, x where the old one ends}. */
    private int[] previewBox() {
        int x0 = squareX(), x1 = left + panelWidth - PAD - 3;
        // A 2 pixel gap in the middle.
        return new int[] { x0, lowerY(), x1, barBottom(), x0 + (x1 - x0 - 2) / 2 };
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        panelWidth = PAD + BAR_WIDTH + PAD + SQUARE + PAD + LABEL_WIDTH + FIELD_WIDTH + 14 + PAD;
        top = 0;
        // Everything down to the old and new color, then the buttons under it.
        panelHeight = lowerY() + PREVIEW_HEIGHT + 3 + PAD + 18 + PAD;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        for (int i = 0; i < fields.length; i++) {
            int w = i == F_HEX ? FIELD_WIDTH + 14 : FIELD_WIDTH;
            fields[i] = new FlatTextField(fontRendererObj, fieldsX(), fieldY(i), w, FIELD_HEIGHT);
            fields[i].setMaxStringLength(i == F_HEX ? 6 : 3);
        }
        updateFields(-1);

        buttonList.clear();
        int buttonY = top + panelHeight - PAD - 18;
        int w = (panelWidth - 2 * PAD - 4) / 2;
        FlatButton done = new FlatButton(ID_DONE, left + PAD, buttonY, w, 18, Lang.format("gui.done"));
        done.active = true;
        buttonList.add(done);
        buttonList
            .add(new FlatButton(ID_CANCEL, left + panelWidth - PAD - w, buttonY, w, 18, Lang.format("gui.cancel")));
        buttonList.add(WindowHeader.closeButton(ID_CANCEL, left + panelWidth, top));
    }

    /** Shows the color in every field but {@code except} (the one being typed in). */
    private void updateFields(int except) {
        int rgb = color();
        String[] values = { String.valueOf((rgb >> 16) & 0xFF), String.valueOf((rgb >> 8) & 0xFF),
            String.valueOf(rgb & 0xFF), String.format("%06x", rgb), String.valueOf(Math.round(hue * 360) % 360),
            String.valueOf(Math.round(saturation * 100)), String.valueOf(Math.round(brightness * 100)) };
        for (int i = 0; i < fields.length; i++) {
            if (i != except) {
                fields[i].setText(values[i]);
                fields[i].setCursorPositionZero();
            }
        }
    }

    /** Applies what is typed in the field, if it is a whole value. */
    private void applyField(int field) {
        String text = fields[field].getText()
            .trim();
        if (field == F_HEX) {
            if (text.length() == 6) {
                try {
                    setRgb(Integer.parseInt(text, 16));
                } catch (NumberFormatException ignored) {
                    return;
                }
            } else {
                return;
            }
        } else {
            if (text.isEmpty()) {
                return;
            }
            setNumber(field, Integer.parseInt(text));
        }
        updateFields(field);
    }

    /** Sets one number of the color (clamped to its range). */
    private void setNumber(int field, int value) {
        value = Math.max(0, Math.min(MAX[field], value));
        int rgb = color();
        switch (field) {
            case F_R:
                setRgb(value << 16 | rgb & 0x00FFFF);
                break;
            case F_G:
                setRgb(value << 8 | rgb & 0xFF00FF);
                break;
            case F_B:
                setRgb(value | rgb & 0xFFFF00);
                break;
            case F_H:
                hue = (value % 360) / 360f;
                break;
            case F_S:
                saturation = value / 100f;
                break;
            default:
                brightness = value / 100f;
                break;
        }
    }

    private int numberOf(int field) {
        int rgb = color();
        switch (field) {
            case F_R:
                return (rgb >> 16) & 0xFF;
            case F_G:
                return (rgb >> 8) & 0xFF;
            case F_B:
                return rgb & 0xFF;
            case F_H:
                return Math.round(hue * 360) % 360;
            case F_S:
                return Math.round(saturation * 100);
            default:
                return Math.round(brightness * 100);
        }
    }

    private int focusedField() {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isFocused()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DONE) {
            callback.onPicked(color());
            mc.displayGuiScreen(parent);
        } else if (button.id == ID_CANCEL) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            callback.onPicked(color());
            mc.displayGuiScreen(parent);
            return;
        }
        int focused = focusedField();
        if (keyCode == Keyboard.KEY_TAB) {
            // To the next field (back with Shift), its text selected to type over.
            int step = isShiftKeyDown() ? fields.length - 1 : 1;
            int next = focused < 0 ? 0 : (focused + step) % fields.length;
            for (FlatTextField field : fields) {
                field.setFocused(false);
            }
            updateFields(-1);
            fields[next].setFocused(true);
            fields[next].setCursorPositionEnd();
            fields[next].setSelectionPos(0);
            return;
        }
        if (focused < 0) {
            return;
        }
        // Numbers only (hex digits in the hex field); keys that edit or move go through.
        boolean allowed = typedChar < ' ' || Character.isDigit(typedChar)
            || focused == F_HEX && Character.digit(typedChar, 16) >= 0;
        if (allowed && fields[focused].textboxKeyTyped(typedChar, keyCode)) {
            applyField(focused);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        for (int i = 0; i < fields.length; i++) {
            if (i != F_HEX && fields[i].isMouseOver(mouseX, mouseY)) {
                // The wheel turns the number: by 1, by 10 with Shift.
                int step = (wheel > 0 ? 1 : -1) * (isShiftKeyDown() ? 10 : 1);
                setNumber(i, numberOf(i) + step);
                updateFields(-1);
                return;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        int wasFocused = focusedField();
        for (FlatTextField field : fields) {
            field.mouseClicked(mouseX, mouseY, button);
        }
        if (wasFocused >= 0 && !fields[wasFocused].isFocused()) {
            // Left a half typed field: show the color in it again.
            updateFields(focusedField());
        }
        if (button != 0) {
            return;
        }
        int[] preview = previewBox();
        if (Theme.inside(mouseX, mouseY, preview[0], preview[1], preview[4], preview[3])) {
            // The old color goes back to it.
            setRgb(oldColor);
            updateFields(-1);
            return;
        }
        int sx = squareX(), sy = squareY();
        if (Theme.inside(mouseX, mouseY, sx, sy, sx + SQUARE, sy + SQUARE)) {
            dragging = 1;
        } else if (Theme.inside(mouseX, mouseY, barX() - 3, sy, barX() + BAR_WIDTH + 3, barBottom())) {
            dragging = 2;
        }
        updateDrag(mouseX, mouseY);
    }

    /** Applies the mouse position while a button is held; done every frame for smooth dragging. */
    private void updateDrag(int mouseX, int mouseY) {
        if (dragging == 0) {
            return;
        }
        if (dragging == 1) {
            saturation = Math.max(0f, Math.min(1f, (mouseX - squareX()) / (float) SQUARE));
            brightness = 1f - Math.max(0f, Math.min(1f, (mouseY - squareY()) / (float) SQUARE));
        } else {
            float ty = (mouseY - squareY()) / (float) (barBottom() - squareY());
            hue = Math.max(0f, Math.min(0.9999f, ty));
        }
        updateFields(-1);
    }

    @Override
    public void updateScreen() {
        for (FlatTextField field : fields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (dragging != 0) {
            if (Mouse.isButtonDown(0)) {
                updateDrag(mouseX, mouseY);
            } else {
                dragging = 0;
            }
        }

        Theme.dimScreen(0, 0, width, height);
        Theme.panel(left, top, left + panelWidth, top + panelHeight);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            left + panelWidth,
            left + panelWidth - WindowHeader.CLOSE_ROOM,
            Icons.PALETTE,
            Lang.format("wayfarmap.gui.pick_color"),
            Lang.format("wayfarmap.gui.pick_color_hint"),
            Theme.TEXT_MUTED,
            String.format("#%06X", color()));

        int sx = squareX(), sy = squareY();
        int bx = barX(), barBottom = barBottom();
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);

        // The square: white to the pure hue left to right, fading to black downwards (exactly HSV).
        Theme.outline(sx - 1, sy - 1, sx + SQUARE + 1, sy + SQUARE + 1, Theme.BORDER);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        tessellator.setColorOpaque_I(0x000000);
        tessellator.addVertex(sx, sy + SQUARE, 0);
        tessellator.addVertex(sx + SQUARE, sy + SQUARE, 0);
        tessellator.setColorOpaque_I(Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF);
        tessellator.addVertex(sx + SQUARE, sy, 0);
        tessellator.setColorOpaque_I(0xFFFFFF);
        tessellator.addVertex(sx, sy, 0);
        tessellator.draw();

        // The hue bar, as tall as the square and the colors under it.
        Theme.outline(bx - 1, sy - 1, bx + BAR_WIDTH + 1, barBottom + 1, Theme.BORDER);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        double segment = (barBottom - sy) / (double) (HUE_STOPS.length - 1);
        tessellator.startDrawingQuads();
        for (int i = 0; i < HUE_STOPS.length - 1; i++) {
            double y0 = sy + i * segment, y1 = sy + (i + 1) * segment;
            tessellator.setColorOpaque_I(HUE_STOPS[i + 1]);
            tessellator.addVertex(bx, y1, 0);
            tessellator.addVertex(bx + BAR_WIDTH, y1, 0);
            tessellator.setColorOpaque_I(HUE_STOPS[i]);
            tessellator.addVertex(bx + BAR_WIDTH, y0, 0);
            tessellator.addVertex(bx, y0, 0);
        }
        tessellator.draw();

        // Marker in the square: a ring, white on dark colors and black on light ones.
        double mx = sx + saturation * SQUARE, my = sy + (1f - brightness) * SQUARE;
        boolean light = brightness > 0.6f && saturation < 0.4f;
        ring(tessellator, mx, my, 3.5, 5.5, light ? 0x000000 : 0xFFFFFF);
        ring(tessellator, mx, my, 5.5, 6.5, light ? 0xFFFFFF : 0x000000);
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        // Handle on the bar: a frame around the hue, wider than the bar.
        int hy = sy + Math.round(hue * (barBottom - sy));
        Theme.outline(bx - 4, hy - 4, bx + BAR_WIDTH + 4, hy + 4, 0xFF000000);
        Theme.outline(bx - 3, hy - 3, bx + BAR_WIDTH + 3, hy + 3, 0xFFFFFFFF);
        Theme.fill(bx - 2, hy - 2, bx + BAR_WIDTH + 2, hy + 2, 0xFF000000 | Color.HSBtoRGB(hue, 1f, 1f));

        // The fields with their letters.
        for (int i = 0; i < fields.length; i++) {
            FlatTextField field = fields[i];
            Theme.text(
                fontRendererObj,
                LABELS[i],
                fieldsX() - LABEL_WIDTH,
                field.boxY + (FIELD_HEIGHT - 8) / 2,
                field.isFocused() ? Theme.TEXT : Theme.TEXT_MUTED);
            field.drawTextBox();
        }

        // The old color next to the new one, each with its name and hex code; the old one can be clicked to go back.
        int[] p = previewBox();
        int x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3], split = p[4];
        boolean overOld = Theme.inside(mouseX, mouseY, x0, y0, split, y1);
        Theme.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF000000);
        Theme.outline(x0 - 3, y0 - 3, x1 + 3, y1 + 3, Theme.BORDER);
        Theme.fill(x0, y0, split, y1, 0xFF000000 | oldColor);
        int newX = x1 - (split - x0);
        Theme.fill(newX, y0, x1, y1, 0xFF000000 | color());
        // A soft shine along the top, so the two read as one glossy piece.
        Theme.fill(x0, y0, split, y0 + PREVIEW_HEIGHT / 3, 0x18FFFFFF);
        Theme.fill(newX, y0, x1, y0 + PREVIEW_HEIGHT / 3, 0x18FFFFFF);
        swatchText(Lang.format("wayfarmap.gui.color_old"), oldColor, x0 + 5, y0 + 5);
        swatchText(String.format("#%06X", oldColor), oldColor, x0 + 5, y1 - 13);
        swatchText(Lang.format("wayfarmap.gui.color_new"), color(), newX + 5, y0 + 5);
        swatchText(String.format("#%06X", color()), color(), newX + 5, y1 - 13);
        if (overOld) {
            Theme.outline(x0, y0, split, y1, 0xC0FFFFFF);
        }

        super.drawScaled(mouseX, mouseY, partialTicks);

        if (overOld) {
            drawHoveringText(
                java.util.Collections.singletonList(Lang.format("wayfarmap.gui.color_old_hint")),
                mouseX,
                mouseY,
                fontRendererObj);
        }
    }

    /** Text on a color swatch: dark on light colors, white on dark ones. */
    private void swatchText(String text, int rgb, int x, int y) {
        double luminance = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
        if (luminance > 150) {
            fontRendererObj.drawString(text, x, y, 0xD0101418);
        } else {
            fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFFFFFF);
        }
    }

    /** A ring between two radii around the point. */
    private static void ring(Tessellator tessellator, double cx, double cy, double inner, double outer, int rgb) {
        int segments = 24;
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.setColorOpaque_I(rgb);
        for (int i = 0; i <= segments; i++) {
            double a = 2 * Math.PI * i / segments;
            double cos = Math.cos(a), sin = Math.sin(a);
            tessellator.addVertex(cx + cos * outer, cy + sin * outer, 0);
            tessellator.addVertex(cx + cos * inner, cy + sin * inner, 0);
        }
        tessellator.draw();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
