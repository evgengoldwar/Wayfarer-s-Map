package WayFarMap.client.gui.ui;

import net.minecraft.client.Minecraft;

/**
 * Square flat button with a small pixel icon instead of text; its name shows as a tooltip. A colored dot in the
 * corner can show a state (e.g. which mobs are shown).
 */
public class IconButton extends FlatButton {

    public String tooltip;
    /** Color of the state dot in the lower right corner, 0 for none. */
    public int badge;
    /** Drawn dimmed, for a feature that is off. */
    public boolean dim;

    public IconButton(int id, int x, int y, String[] icon, String tooltip) {
        super(id, x, y, 20, 16, "");
        this.icon = icon;
        this.tooltip = tooltip;
    }

    @Override
    protected String skinPart() {
        return "icon_button";
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        boolean hovered = enabled && isMouseOver(mouseX, mouseY);
        drawBackground(hovered);
        int color;
        if (iconColor != 0 && enabled) {
            color = Theme.iconShade(iconColor, hovered, active, dim);
        } else {
            color = !enabled || dim && !hovered ? Theme.TEXT_MUTED : Theme.TEXT;
        }
        Icons.draw(icon, xPosition + (width - Icons.width(icon)) / 2, yPosition + (height - icon.length) / 2, color);
        if (badge != 0) {
            int bx = xPosition + width - 5, by = yPosition + height - 5;
            Theme.fill(bx - 1, by - 1, bx + 3, by + 3, Theme.PANEL);
            Theme.fill(bx, by, bx + 2, by + 2, badge);
        }
    }
}
