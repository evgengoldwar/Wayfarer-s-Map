package WayFarMap.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/** Flat button without the vanilla stone texture. */
public class FlatButton extends GuiButton {

    /** Highlighted with the accent color, e.g. a toggle that is on or the selected tab. */
    public boolean active;
    /** Red text, for destructive actions. */
    public boolean danger;
    /** Drawn left of the text, which then starts after it instead of being centered; null for none. */
    public String[] icon;
    /** The icon's own color (ARGB), 0 to draw it in the text colors. */
    public int iconColor;
    /**
     * Width kept for the icon, 0 for its own: buttons in a column with icons of different widths give them all the
     * widest one's, so the icons are centered on one line and the text starts at the same place.
     */
    public int iconSlot;
    /** How lit the button is by the mouse, fading in and out. */
    private final Smooth hover = new Smooth(0);

    /** The part of the theme's atlas the button is drawn with: "button", or "icon_button" for one of icons. */
    protected String skinPart() {
        return "button";
    }

    public FlatButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return visible && Theme.inside(mouseX, mouseY, xPosition, yPosition, xPosition + width, yPosition + height);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        boolean hovered = enabled && isMouseOver(mouseX, mouseY);
        drawBackground(hovered);

        int color = !enabled ? Theme.TEXT_DISABLED : danger ? Theme.DANGER : Theme.BUTTON_TEXT;
        if (icon != null) {
            int shade;
            if (!enabled) {
                shade = Theme.TEXT_DISABLED;
            } else if (iconColor != 0) {
                shade = Theme.iconShade(iconColor, hovered, active, false);
            } else {
                shade = hovered || active ? Theme.BUTTON_TEXT : Theme.BEVEL ? 0xFFE0E0E0 : Theme.TEXT_MUTED;
            }
            int iconWidth = Icons.width(icon);
            int slot = Math.max(iconSlot, iconWidth);
            Icons.draw(icon, xPosition + 6 + (slot - iconWidth) / 2, yPosition + (height - icon.length) / 2, shade);
            int textX = xPosition + 6 + slot + 5;
            // Room left on the right for a mark the screen may put there.
            String text = Theme.ellipsize(mc.fontRenderer, displayString, xPosition + width - 10 - textX);
            label(mc, text, textX, yPosition + (height - 8) / 2, color);
            return;
        }
        String text = Theme.ellipsize(mc.fontRenderer, displayString, width - 6);
        int textX = xPosition + width / 2 - mc.fontRenderer.getStringWidth(text) / 2;
        label(mc, text, textX, yPosition + (height - 8) / 2, color);
    }

    /** The button's text: with a shadow on raised buttons, like Minecraft's. */
    private static void label(Minecraft mc, String text, int x, int y, int color) {
        if (Theme.BEVEL) {
            mc.fontRenderer.drawStringWithShadow(text, x, y, color);
        } else {
            Theme.text(mc.fontRenderer, text, x, y, color);
        }
    }

    /** The box: lit by the mouse with a short fade, in the accent color when active. */
    protected void drawBackground(boolean hovered) {
        double lit = hover.update(hovered ? 1 : 0, 22);
        if (drawSprite(lit)) {
            return;
        }
        int background, border;
        if (!enabled) {
            background = Theme.CONTROL_DISABLED;
            border = Theme.BORDER;
        } else if (active) {
            background = Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit);
            border = Theme.ACCENT;
        } else {
            background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
            border = Theme.blend(Theme.BORDER, Theme.ACCENT, lit);
        }
        Theme.fill(xPosition, yPosition, xPosition + width, yPosition + height, background);
        if (Theme.BEVEL) {
            // Like Minecraft's stone buttons: a black edge, lighter at the top and left, darker at the others.
            Theme.outline(xPosition, yPosition, xPosition + width, yPosition + height, 0xFF000000);
            Theme.bevel(
                xPosition + 1,
                yPosition + 1,
                xPosition + width - 1,
                yPosition + height - 1,
                0x70FFFFFF,
                0x60000000,
                1);
            return;
        }
        Theme.outline(xPosition, yPosition, xPosition + width, yPosition + height, border);
    }

    /**
     * The button in the theme's pictures: its state's picture ({@code .disabled}, {@code .active},
     * {@code .danger}), and the lit one ({@code .hover}) fading in over it; false when the theme has none.
     */
    private boolean drawSprite(double lit) {
        String part = skinPart();
        Skin.Sprite base;
        Skin.Sprite over = null;
        if (!enabled) {
            base = Skin.first(part + ".disabled", "button.disabled", part, "button");
        } else if (active) {
            base = Skin.first(part + ".active", "button.active", part, "button");
            over = Skin.first(part + ".active.hover", "button.active.hover");
        } else if (danger) {
            base = Skin.first(part + ".danger", "button.danger", part, "button");
            over = Skin.first(part + ".danger.hover", "button.danger.hover", part + ".hover", "button.hover");
        } else {
            base = Skin.first(part, "button");
            over = Skin.first(part + ".hover", "button.hover");
        }
        if (base == null) {
            return false;
        }
        int x1 = xPosition + width, y1 = yPosition + height;
        Skin.draw(base, xPosition, yPosition, x1, y1, 0xFFFFFFFF, 1);
        if (over != null && lit > 0) {
            Skin.draw(over, xPosition, yPosition, x1, y1, 0xFFFFFFFF, lit);
        }
        return true;
    }
}
