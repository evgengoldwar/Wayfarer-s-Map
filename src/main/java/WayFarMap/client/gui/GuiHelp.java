package WayFarMap.client.gui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import WayFarMap.client.KeyHandler;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * In-game help: sections in a sidebar (scrolled when they don't all fit), the text of the selected one on the right,
 * like the settings screen; the arrow keys and the buttons under the text go to the section before and after it.
 * <p>
 * The text comes from {@code assets/wayfarmap/help/<language>.txt} (falling back to en_US): "# Title" starts a
 * section ("# {icon:name} Title" gives it an icon), "! " marks an important note, "> " a tip, "- " a list item, an
 * empty line a gap. Minecraft color codes (§) can be used anywhere; yellow (§e) text is a key or a mouse action and is
 * drawn on a key cap. {@code {key:open_map}} is the key the mod's binding of that name is set to now (from Minecraft's
 * controls, so a key changed there shows changed here), or "not set".
 */
public class GuiHelp extends ScaledScreen {

    private static final int SIDEBAR_WIDTH = 156;
    private static final int LINE_HEIGHT = 11;
    /** Height of a section's entry in the sidebar. */
    private static final int ITEM_HEIGHT = 18;
    /** Room for the icons left of the sections' names. */
    private static final int ICON_COLUMN = 14;
    /** How long the text slides in after another section is opened, in milliseconds. */
    private static final long SECTION_SLIDE_MS = 160;
    private static final int ID_CLOSE = 1000, ID_PREVIOUS = 1001, ID_NEXT = 1002;
    /** Color of a key cap: the yellow of §e. */
    private static final int KEY_COLOR = 0xFFFFFF55;

    private static final Pattern ICON = Pattern.compile("^\\{icon:([a-z_]+)\\}\\s*");
    private static final Pattern KEY = Pattern.compile("\\{key:([a-z_]+)\\}");

    /** A section of the help file: its title, icon and raw lines. */
    private static final class Section {

        final String title;
        final String[] icon;
        /** The icon's color (ARGB), shown while the section is open or under the mouse. */
        final int color;
        final List<String> lines = new ArrayList<>();

        Section(String title, String[] icon, int color) {
            this.color = color;
            this.title = title;
            this.icon = icon;
        }
    }

    /** A wrapped line ready to draw. */
    private static final class Line {

        final String text;
        /** 0 = normal, 1 = important, 2 = tip, 3 = gap. */
        final int kind;
        final boolean bullet;
        /** Wrapped continuation of a list item, indented like its first line. */
        final boolean continuation;
        /** First and last line of an important note or tip, for the band's padding. */
        final boolean first, last;

        Line(String text, int kind, boolean bullet, boolean continuation, boolean first, boolean last) {
            this.text = text;
            this.kind = kind;
            this.bullet = bullet;
            this.continuation = continuation;
            this.first = first;
            this.last = last;
        }
    }

    private static int selected;

    private final GuiScreen parent;
    private List<Section> sections;
    private final List<Line> lines = new ArrayList<>();
    private int left, top, right, bottom, contentLeft, contentRight, contentTop, contentBottom;
    /** The sidebar's list of sections: where it is and how far it is scrolled. */
    private int listTop, listBottom, listScroll;
    private int scroll;
    /** Where the sidebar's list and the text are drawn while they ease to their scroll. */
    private final Smooth shownListScroll = new Smooth(0), shownScroll = new Smooth(0);
    /** Top of the mark on the selected section (in the list, not scrolled), sliding to it when another is chosen. */
    private final Smooth selectionMark = new Smooth(-1);
    /** How lit each section's entry in the sidebar is by the mouse. */
    private Smooth[] sectionLight = new Smooth[0];
    /** When the shown section was opened, to slide its text in. */
    private long sectionOpenedAt;
    private FlatButton previousButton, nextButton;

    public GuiHelp(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (sections == null) {
            sections = load();
        }
        selected = Math.max(0, Math.min(selected, sections.size() - 1));
        int panelWidth = Math.min(width - 16, 600);
        int panelHeight = Math.min(height - 16, 360);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentLeft = left + SIDEBAR_WIDTH + 14;
        contentRight = right - 14;
        contentTop = top + 34;
        contentBottom = bottom - 34;
        listTop = top + 26;
        listBottom = bottom - 34;

        buttonList.clear();
        // The close button stays at the bottom of the sidebar, under the list of sections, whatever their number.
        String close = Lang.format("wayfarmap.help.close");
        buttonList.add(new FlatButton(ID_CLOSE, left + 6, bottom - 26, SIDEBAR_WIDTH - 12, 18, close));
        // Under the text: the sections before and after this one.
        int half = (contentRight - contentLeft - 6) / 2;
        previousButton = new FlatButton(ID_PREVIOUS, contentLeft, bottom - 26, half, 18, "");
        nextButton = new FlatButton(ID_NEXT, contentRight - half, bottom - 26, half, 18, "");
        buttonList.add(previousButton);
        buttonList.add(nextButton);
        select(selected);
    }

    /** Shows a section: its text from the top, its entry in the sidebar in view. */
    private void select(int index) {
        if (sections.isEmpty()) {
            previousButton.visible = nextButton.visible = false;
            return;
        }
        int before = selected;
        selected = Math.max(0, Math.min(index, sections.size() - 1));
        if (selected != before) {
            sectionOpenedAt = System.currentTimeMillis();
        }
        int itemTop = selected * ITEM_HEIGHT;
        int visible = listBottom - listTop;
        if (itemTop < listScroll) {
            listScroll = itemTop;
        } else if (itemTop + ITEM_HEIGHT > listScroll + visible) {
            listScroll = itemTop + ITEM_HEIGHT - visible;
        }
        clampListScroll();
        previousButton.enabled = selected > 0;
        nextButton.enabled = selected < sections.size() - 1;
        int labelWidth = previousButton.getWidth() - 20;
        previousButton.displayString = "";
        nextButton.displayString = "";
        if (selected > 0) {
            String title = sections.get(selected - 1).title;
            previousButton.displayString = "<  " + Theme.ellipsize(fontRendererObj, title, labelWidth);
        }
        if (selected < sections.size() - 1) {
            String title = sections.get(selected + 1).title;
            nextButton.displayString = Theme.ellipsize(fontRendererObj, title, labelWidth) + "  >";
        }
        layout();
    }

    /** Wraps the selected section to the content width. */
    private void layout() {
        lines.clear();
        scroll = 0;
        // Another section's text starts at its top at once.
        shownScroll.set(0);
        if (sections.isEmpty()) {
            return;
        }
        int textWidth = contentRight - 8 - contentLeft;
        List<String> raw = sections.get(selected).lines;
        // Gaps at the start and the end of a section only push the text around.
        int from = 0, to = raw.size();
        while (from < to && raw.get(from)
            .trim()
            .isEmpty()) {
            from++;
        }
        while (to > from && raw.get(to - 1)
            .trim()
            .isEmpty()) {
            to--;
        }
        for (int n = from; n < to; n++) {
            String text = withKeys(raw.get(n));
            if (text.trim()
                .isEmpty()) {
                lines.add(new Line("", 3, false, false, false, false));
                continue;
            }
            int kind = 0;
            boolean bullet = false;
            if (text.startsWith("! ")) {
                kind = 1;
                text = text.substring(2);
            } else if (text.startsWith("> ")) {
                kind = 2;
                text = text.substring(2);
            } else if (text.startsWith("- ")) {
                bullet = true;
                text = text.substring(2);
            }
            if (kind != 0 && !lines.isEmpty() && lines.get(lines.size() - 1).kind != 3) {
                // A note or tip right after other text or another note: a little room so their bands don't touch.
                lines.add(new Line("", 3, false, false, false, false));
            }
            int indent = kind != 0 ? 18 : bullet ? 10 : 0;
            List<?> wrapped = fontRendererObj.listFormattedStringToWidth(text, textWidth - indent);
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(
                    new Line(
                        String.valueOf(wrapped.get(i)),
                        kind,
                        bullet && i == 0,
                        bullet && i > 0,
                        i == 0,
                        i == wrapped.size() - 1));
            }
        }
    }

    /** The text with each {@code {key:name}} put as the key set now, a gray "not set" for a binding without one. */
    private static String withKeys(String text) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        Matcher matcher = KEY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = KeyHandler.keyName(matcher.group(1));
            String shown = key != null ? key : "§7" + Lang.format("wayfarmap.help.key_none") + "§e";
            matcher.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static List<Section> load() {
        List<Section> sections = read(Lang.code());
        if (sections.isEmpty()) {
            sections = read("en_US");
        }
        return sections;
    }

    private static List<Section> read(String language) {
        List<Section> sections = new ArrayList<>();
        ResourceLocation location = new ResourceLocation("wayfarmap", "help/" + language + ".txt");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location)
                    .getInputStream(),
                StandardCharsets.UTF_8))) {
            Section current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("# ")) {
                    String title = line.substring(2)
                        .trim();
                    String[] icon = Icons.HELP;
                    int color = sectionColor("");
                    Matcher matcher = ICON.matcher(title);
                    if (matcher.find()) {
                        icon = sectionIcon(matcher.group(1));
                        color = sectionColor(matcher.group(1));
                        title = title.substring(matcher.end());
                    }
                    current = new Section(title, icon, color);
                    sections.add(current);
                } else if (current != null) {
                    current.lines.add(line);
                }
            }
        } catch (Exception e) {
            // Missing translation: the caller falls back to English.
        }
        return sections;
    }

    /** The color of a section's icon, by the icon's name; the accent for the help's own. */
    private static int sectionColor(String name) {
        switch (name) {
            case "keys":
                return 0xFFE8D44D;
            case "buttons":
                return 0xFF6CC24A;
            case "navigation":
                return 0xFFFF7B72;
            case "waypoints":
                return 0xFFE5534B;
            case "groups":
                return 0xFF5BD6E0;
            case "teleport":
                return 0xFFC08CFF;
            case "caves":
                return 0xFFC8A070;
            case "biomes":
                return 0xFF8BD450;
            case "topo":
                return 0xFF4FC3A8;
            case "view":
                return 0xFFE8A040;
            case "ores":
                return 0xFF5BD6E0;
            case "power":
                return 0xFFFFD23F;
            case "nodes":
                return 0xFFC08CFF;
            case "wireless":
                return 0xFF4FC3F7;
            case "chargers":
                return 0xFFFFB300;
            case "drones":
                return 0xFF1DE9B6;
            case "guards":
                return 0xFF76FF03;
            case "claims":
                return 0xFFF2C14E;
            case "team":
                return 0xFF4C9AFF;
            case "chunkload":
                return 0xFFC08CFF;
            case "settings":
                return 0xFFAAB4C3;
            default:
                return Theme.ACCENT;
        }
    }

    /** The icon named in a section's title ({@code {icon:name}}); the help's own for an unknown name. */
    private static String[] sectionIcon(String name) {
        switch (name) {
            case "keys":
                return Icons.KEYS;
            case "buttons":
                return Icons.FLAT;
            case "navigation":
                return Icons.FOLLOW;
            case "waypoints":
                return Icons.WAYPOINTS;
            case "groups":
                return Icons.ADDONS;
            case "teleport":
                return Icons.MARKER;
            case "caves":
                return Icons.CAVES;
            case "biomes":
                return Icons.BIOMES;
            case "topo":
                return Icons.TOPO;
            case "view":
                return Icons.ISO;
            case "ores":
                return Icons.ORE;
            case "power":
                return Icons.POWER;
            case "nodes":
                return Icons.NODE;
            case "wireless":
                return Icons.WIRELESS;
            case "chargers":
                return Icons.CHARGER;
            case "drones":
                return Icons.DRONE;
            case "guards":
                return Icons.REPELLATOR;
            case "claims":
                return Icons.CLAIM;
            case "team":
                return Icons.TEAM;
            case "chunkload":
                return Icons.CHUNKLOAD;
            case "settings":
                return Icons.SETTINGS;
            default:
                return Icons.HELP;
        }
    }

    /** Height of a line: gaps are half a line. */
    private static int height(Line line) {
        return line.kind == 3 ? LINE_HEIGHT / 2 + 1 : LINE_HEIGHT;
    }

    private int textHeight() {
        int sum = 0;
        for (Line line : lines) {
            sum += height(line);
        }
        return sum;
    }

    private int maxScroll() {
        return Math.max(0, textHeight() - (contentBottom - contentTop));
    }

    private int maxListScroll() {
        return Math.max(0, sections.size() * ITEM_HEIGHT - (listBottom - listTop));
    }

    private void clampListScroll() {
        listScroll = Math.max(0, Math.min(maxListScroll(), listScroll));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(parent);
        } else if (button.id == ID_PREVIOUS) {
            select(selected - 1);
        } else if (button.id == ID_NEXT) {
            select(selected + 1);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        int index = sectionAt(mouseX, mouseY);
        if (button == 0 && index >= 0) {
            select(index);
        }
    }

    /** The section whose entry in the sidebar is under the mouse, or -1. */
    private int sectionAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, left + 6, listTop, left + SIDEBAR_WIDTH - 6, listBottom)) {
            return -1;
        }
        int index = (mouseY - listTop + (int) Math.round(shownListScroll.get())) / ITEM_HEIGHT;
        return index < sections.size() ? index : -1;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        } else if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_LEFT) {
            select(selected - 1);
        } else if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_RIGHT) {
            select(selected + 1);
        } else if (keyCode == Keyboard.KEY_PRIOR || keyCode == Keyboard.KEY_NEXT) {
            int page = contentBottom - contentTop - LINE_HEIGHT * 2;
            scroll += keyCode == Keyboard.KEY_NEXT ? page : -page;
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
        } else if (keyCode == Keyboard.KEY_HOME || keyCode == Keyboard.KEY_END) {
            scroll = keyCode == Keyboard.KEY_HOME ? 0 : maxScroll();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getX() * width / mc.displayWidth;
        if (mouseX < left + SIDEBAR_WIDTH) {
            listScroll -= Integer.signum(wheel) * ITEM_HEIGHT * 2;
            clampListScroll();
        } else {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(wheel) * LINE_HEIGHT * 3));
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.fill(left + 1, top + 1, left + SIDEBAR_WIDTH, bottom - 1, Theme.PANEL_ALT);
        Theme.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, bottom - 1, Theme.BORDER);
        Icons.drawLogo(left + 7, top + 5);
        Theme.text(fontRendererObj, "Wayfarer's Map", left + 8 + Icons.LOGO_SIZE + 4, top + 9, Theme.ACCENT);
        drawSections(mouseX, mouseY);
        // The close button is set apart from the list.
        Theme.fill(left + 6, bottom - 32, left + SIDEBAR_WIDTH - 6, bottom - 31, Theme.BORDER);

        double shown = shownScroll.update(scroll, 16);
        if (!sections.isEmpty()) {
            drawHeader(sections.get(selected), shown);
        }
        drawText(shown);
        super.drawScaled(mouseX, mouseY, partialTicks);
    }

    /** Over the text: the section's icon, title and number, and a line filling up as the text is read. */
    private void drawHeader(Section section, double shown) {
        int iconY = top + 13 - section.icon.length / 2;
        Icons.draw(section.icon, contentLeft, iconY, section.color);
        int titleX = contentLeft + Icons.width(section.icon) + 6;
        String counter = (selected + 1) + " / " + sections.size();
        int counterWidth = fontRendererObj.getStringWidth(counter);
        int titleWidth = contentRight - counterWidth - 18 - titleX;
        String title = Theme.ellipsize(fontRendererObj, section.title, titleWidth);
        fontRendererObj.drawStringWithShadow(title, titleX, top + 9, Theme.TEXT);
        // The number of the section in a small pill on the right.
        int pillLeft = contentRight - counterWidth - 8;
        Theme.fill(pillLeft, top + 7, contentRight, top + 19, Theme.CONTROL);
        Theme.outline(pillLeft, top + 7, contentRight, top + 19, Theme.BORDER);
        Theme.text(fontRendererObj, counter, pillLeft + 4, top + 9, Theme.TEXT_MUTED);

        Theme.fill(contentLeft, top + 24, contentRight, top + 25, Theme.BORDER);
        int maxScroll = maxScroll();
        double read = maxScroll > 0 ? shown / maxScroll : 1;
        int readRight = contentLeft + (int) Math.round((contentRight - contentLeft) * read);
        Theme.fill(contentLeft, top + 24, readRight, top + 25, maxScroll > 0 ? Theme.ACCENT : Theme.ACCENT_DIM);
    }

    /** The sidebar's list: icons and names, the selected section marked, the one under the mouse lit. */
    private void drawSections(int mouseX, int mouseY) {
        int hovered = sectionAt(mouseX, mouseY);
        int maxScroll = maxListScroll();
        int x0 = left + 6, x1 = left + SIDEBAR_WIDTH - (maxScroll > 0 ? 9 : 6);
        if (sectionLight.length != sections.size()) {
            sectionLight = new Smooth[sections.size()];
            for (int i = 0; i < sectionLight.length; i++) {
                sectionLight[i] = new Smooth(0);
            }
        }
        double shown = shownListScroll.update(listScroll, 16);
        int scrolled = (int) Math.round(shown);
        Theme.clip(x0, listTop, x1, listBottom);
        if (!sections.isEmpty()) {
            // The selected section's background and bar slide to it when another is chosen.
            int target = selected * ITEM_HEIGHT;
            if (selectionMark.get() < 0) {
                selectionMark.set(target);
            }
            int markY = listTop + (int) Math.round(selectionMark.update(target, 18)) - scrolled;
            Theme.fill(x0, markY, x1, markY + ITEM_HEIGHT - 1, Theme.CONTROL_HOVER);
            Theme.fill(x0, markY, x0 + 2, markY + ITEM_HEIGHT - 1, Theme.ACCENT);
        }
        for (int i = 0; i < sections.size(); i++) {
            int y = listTop + i * ITEM_HEIGHT - scrolled;
            boolean current = i == selected;
            double lit = sectionLight[i].update(i == hovered && !current ? 1 : 0, 20);
            if (y + ITEM_HEIGHT <= listTop || y >= listBottom) {
                continue;
            }
            if (lit > 0.02) {
                int light = Theme.blend(Theme.ROW_HOVER & 0xFFFFFF, Theme.ROW_HOVER, lit);
                Theme.fill(x0, y, x1, y + ITEM_HEIGHT - 1, light);
            }
            Section section = sections.get(i);
            // The open section's icon in its color, the others a darker shade of theirs, lit up under the mouse.
            int dimmed = Theme.iconShade(section.color, false, false, true);
            int iconColor = current ? section.color : Theme.blend(dimmed, section.color, lit);
            int iconX = x0 + 6 + (ICON_COLUMN - Icons.width(section.icon)) / 2;
            Icons.draw(section.icon, iconX, y + (ITEM_HEIGHT - 1 - section.icon.length) / 2, iconColor);
            int textX = x0 + 6 + ICON_COLUMN + 4;
            String title = Theme.ellipsize(fontRendererObj, section.title, x1 - textX - 4);
            int color = current ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Theme.text(fontRendererObj, title, textX, y + (ITEM_HEIGHT - 8) / 2, color);
        }
        Theme.unclip();
        if (maxScroll > 0) {
            boolean over = Theme.inside(mouseX, mouseY, left, listTop, left + SIDEBAR_WIDTH, listBottom);
            int total = sections.size() * ITEM_HEIGHT;
            int x = left + SIDEBAR_WIDTH - 6;
            Theme.scrollbar(x, listTop, listBottom, listBottom - listTop, total, shown / maxScroll, over);
        }
    }

    /**
     * The section's text: notes and tips on a tinted band with their mark, list items with a dot, keys on key caps.
     * It slides in a little when the section is opened.
     */
    private void drawText(double shown) {
        float opened = Math.min(1f, (System.currentTimeMillis() - sectionOpenedAt) / (float) SECTION_SLIDE_MS);
        int slide = Math.round((1 - opened) * (1 - opened) * 8);
        int y = contentTop - (int) Math.round(shown) + slide;
        // Cut a little above the top, where a note's band starts over its first line.
        Theme.clip(contentLeft, contentTop - 3, contentRight, contentBottom + 1);
        for (Line line : lines) {
            int h = height(line);
            if (y + h > contentTop - 3 && y < contentBottom + 1 && line.kind != 3) {
                int x = contentLeft;
                if (line.kind == 1 || line.kind == 2) {
                    // Important notes and tips: a tinted band with a colored bar and a mark on the left.
                    int color = line.kind == 1 ? Theme.DANGER : Theme.SUCCESS;
                    int bandTop = y - (line.first ? 3 : 1), bandBottom = y + LINE_HEIGHT - (line.last ? -1 : 1);
                    Theme.fill(x, bandTop, contentRight, bandBottom, (color & 0xFFFFFF) | 0x26000000);
                    Theme.fill(x, bandTop, x + 2, bandBottom, color);
                    if (line.first) {
                        String[] mark = line.kind == 1 ? Icons.NOTE : Icons.TIP;
                        Icons.draw(mark, x + 6 + (5 - Icons.width(mark)) / 2, y, color);
                    }
                    x += 18;
                } else if (line.bullet) {
                    Icons.draw(Icons.BULLET, x + 2, y + 2, Theme.ACCENT);
                    x += 10;
                } else if (line.continuation) {
                    x += 10;
                }
                drawKeyCaps(line.text, x, y);
                fontRendererObj.drawString(line.text, x, y, Theme.TEXT);
            }
            y += h;
        }
        Theme.unclip();
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int visible = contentBottom - contentTop;
            double position = shown / maxScroll;
            Theme.scrollbar(right - 8, contentTop, contentBottom, visible, visible + maxScroll, position, false);
        }
    }

    /** Under each yellow (§e) part of the line, a key or a mouse action: a key cap. */
    private void drawKeyCaps(String text, int x, int y) {
        int from = text.indexOf("§e");
        while (from >= 0) {
            // The key ends where the next color or format code starts, or with the line.
            int end = text.indexOf('§', from + 2);
            if (end < 0) {
                end = text.length();
            }
            String key = text.substring(from + 2, end);
            if (!key.trim()
                .isEmpty()) {
                int keyLeft = x + fontRendererObj.getStringWidth(text.substring(0, from));
                int keyRight = keyLeft + fontRendererObj.getStringWidth(key);
                Theme.fill(keyLeft - 2, y - 1, keyRight + 1, y + 9, (KEY_COLOR & 0xFFFFFF) | 0x1E000000);
                // A darker edge at the bottom, like a key.
                Theme.fill(keyLeft - 2, y + 9, keyRight + 1, y + 10, (KEY_COLOR & 0xFFFFFF) | 0x50000000);
            }
            from = text.indexOf("§e", end);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
