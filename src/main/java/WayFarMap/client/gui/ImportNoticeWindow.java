package WayFarMap.client.gui;

import java.util.List;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.opengl.GL11;

import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.Theme;

/**
 * The window over the world map telling what was copied from JourneyMap: a header with where it came from, a card
 * for the map's regions and one for the waypoints, a line that this map's own exploring was kept, a warning that only
 * the Overworld is copied, and a button to close it.
 */
final class ImportNoticeWindow {

    static final int WIDTH = 300;
    private static final int PAD = 12, HEADER_HEIGHT = 40, TILE = 26, CARD_HEIGHT = 40, CARD_GAP = 6;
    private static final int LINE = 10, BUTTON_WIDTH = 96, BUTTON_HEIGHT = 20, SECTION_GAP = 10;
    /** The warning's color: amber, on every theme. */
    private static final int WARN = 0xFFE3B341;

    private final FontRenderer font;
    private final int regions, waypoints;
    private final String folder, group;

    ImportNoticeWindow(FontRenderer font, int regions, int waypoints, String folder, String group) {
        this.font = font;
        this.regions = regions;
        this.waypoints = waypoints;
        this.folder = folder;
        this.group = group;
    }

    private List<String> keptLines() {
        return font.listFormattedStringToWidth(Lang.format("wayfarmap.journeymap.notice_kept"), WIDTH - 2 * PAD - 14);
    }

    private List<String> warnLines() {
        return font
            .listFormattedStringToWidth(Lang.format("wayfarmap.journeymap.notice_warn_text"), WIDTH - 2 * PAD - 26);
    }

    private int warnHeight() {
        return 8 + LINE + 2 + warnLines().size() * LINE + 6;
    }

    private int height() {
        return HEADER_HEIGHT + SECTION_GAP
            + CARD_HEIGHT
            + SECTION_GAP
            + keptLines().size() * LINE
            + SECTION_GAP
            + warnHeight()
            + SECTION_GAP + 2
            + BUTTON_HEIGHT
            + PAD;
    }

    /** {left, top} of the window, in the middle of the screen. */
    private int[] origin(int screenWidth, int screenHeight) {
        return new int[] { (screenWidth - WIDTH) / 2, Math.max(4, (screenHeight - height()) / 2) };
    }

    /** {x0, y0, x1, y1} of the button. */
    private int[] button(int screenWidth, int screenHeight) {
        int[] o = origin(screenWidth, screenHeight);
        int y = o[1] + height() - PAD - BUTTON_HEIGHT;
        int x = o[0] + (WIDTH - BUTTON_WIDTH) / 2;
        return new int[] { x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT };
    }

    /** Whether a click closes it (on its button). */
    boolean click(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int[] b = button(screenWidth, screenHeight);
        return Theme.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
    }

    void draw(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int[] o = origin(screenWidth, screenHeight);
        int left = o[0], top = o[1], right = left + WIDTH, bottom = top + height();
        Theme.fill(0, 0, screenWidth, screenHeight, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);

        // Header: the icon on an accent tile, the title, and the folder it came from.
        Theme.fill(left + 1, top + 1, right - 1, top + HEADER_HEIGHT, Theme.HEADER);
        Theme.fill(left + 1, top + HEADER_HEIGHT, right - 1, top + HEADER_HEIGHT + 1, Theme.BORDER);
        int tileX = left + PAD, tileY = top + (HEADER_HEIGHT - TILE) / 2;
        Theme.fill(tileX, tileY, tileX + TILE, tileY + TILE, Theme.ACCENT_DIM);
        Theme.outline(tileX, tileY, tileX + TILE, tileY + TILE, Theme.ACCENT);
        Icons.draw(
            Icons.REGIONLOAD,
            tileX + (TILE - Icons.width(Icons.REGIONLOAD)) / 2,
            tileY + (TILE - Icons.REGIONLOAD.length) / 2,
            Theme.TEXT_BRIGHT);
        int textX = tileX + TILE + 9;
        font.drawStringWithShadow(Lang.format("wayfarmap.journeymap.notice_title"), textX, top + 10, Theme.TEXT_BRIGHT);
        String source = Theme.ellipsize(font, "journeymap/data/sp/" + folder, right - PAD - textX);
        Theme.text(font, source, textX, top + 23, Theme.TEXT_MUTED);

        // The two cards: what was copied, each with its icon and its number large.
        int y = top + HEADER_HEIGHT + SECTION_GAP;
        int cardWidth = (WIDTH - 2 * PAD - CARD_GAP) / 2;
        drawCard(
            left + PAD,
            y,
            cardWidth,
            Icons.MAP2D,
            regions,
            Lang.format("wayfarmap.journeymap.notice_regions"),
            Lang.format("wayfarmap.journeymap.notice_regions_sub"));
        drawCard(
            left + PAD + cardWidth + CARD_GAP,
            y,
            cardWidth,
            Icons.WAYPOINTS,
            waypoints,
            Lang.format("wayfarmap.journeymap.notice_waypoints"),
            waypoints > 0 ? Lang.format("wayfarmap.journeymap.notice_waypoints_sub", group)
                : Lang.format("wayfarmap.journeymap.notice_none"));
        y += CARD_HEIGHT + SECTION_GAP;

        // This map's own exploring was kept.
        Icons.draw(Icons.SMALL_CHECK, left + PAD, y, Theme.SUCCESS);
        for (String line : keptLines()) {
            Theme.text(font, line, left + PAD + 14, y, Theme.TEXT_SOFT);
            y += LINE;
        }
        y += SECTION_GAP;

        // The warning: only the Overworld.
        int warnBottom = y + warnHeight();
        Theme.fill(left + PAD, y, right - PAD, warnBottom, Theme.alpha(WARN, 0x24));
        Theme.outline(left + PAD, y, right - PAD, warnBottom, Theme.alpha(WARN, 0x70));
        Theme.fill(left + PAD, y, left + PAD + 2, warnBottom, WARN);
        drawWarnSign(left + PAD + 8, y + 7);
        int warnX = left + PAD + 26;
        Theme.text(font, Lang.format("wayfarmap.journeymap.notice_warn_title"), warnX, y + 8, WARN);
        int ty = y + 8 + LINE + 2;
        for (String line : warnLines()) {
            Theme.text(font, line, warnX, ty, Theme.TEXT);
            ty += LINE;
        }

        // The button.
        int[] b = button(screenWidth, screenHeight);
        boolean hover = Theme.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
        Theme.fill(b[0], b[1], b[2], b[3], hover ? Theme.ACCENT_SOFT : Theme.ACCENT);
        String ok = Lang.format("wayfarmap.journeymap.notice_ok");
        font.drawStringWithShadow(
            ok,
            (b[0] + b[2]) / 2 - font.getStringWidth(ok) / 2,
            b[1] + (BUTTON_HEIGHT - 8) / 2,
            0xFFFFFFFF);
    }

    /** A card: the icon on the left, the label above, the number large under it, a note beside the number. */
    private void drawCard(int x, int y, int width, String[] icon, int count, String label, String note) {
        boolean any = count > 0;
        Theme.fill(x, y, x + width, y + CARD_HEIGHT, Theme.CARD);
        Theme.outline(x, y, x + width, y + CARD_HEIGHT, any ? Theme.alpha(Theme.ACCENT, 0x80) : Theme.BORDER);
        int iconColor = any ? Theme.ACCENT : Theme.TEXT_DISABLED;
        Icons.draw(icon, x + 9, y + (CARD_HEIGHT - icon.length) / 2, iconColor);
        int textX = x + 9 + Icons.width(icon) + 8;
        Theme.text(font, label, textX, y + 7, Theme.TEXT_MUTED);
        String number = String.valueOf(count);
        GL11.glPushMatrix();
        GL11.glTranslatef(textX, y + 19, 0f);
        GL11.glScalef(2f, 2f, 1f);
        font.drawStringWithShadow(number, 0, 0, any ? Theme.TEXT_BRIGHT : Theme.TEXT_DISABLED);
        GL11.glPopMatrix();
        int noteX = textX + font.getStringWidth(number) * 2 + 5;
        Theme.text(font, Theme.ellipsize(font, note, x + width - 6 - noteX), noteX, y + 26, Theme.TEXT_MUTED);
    }

    /** A warning triangle with an exclamation mark, 13 x 11. */
    private static void drawWarnSign(int x, int y) {
        for (int row = 0; row < 11; row++) {
            int half = row * 6 / 10;
            Theme.fill(x + 6 - half, y + row, x + 7 + half, y + row + 1, WARN);
        }
        Theme.fill(x + 6, y + 3, x + 7, y + 7, 0xFF1A1400);
        Theme.fill(x + 6, y + 8, x + 7, y + 9, 0xFF1A1400);
    }
}
