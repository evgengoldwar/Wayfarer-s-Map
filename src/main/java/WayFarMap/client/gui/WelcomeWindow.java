package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.Tags;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Flags;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * The window shown over the world map the first time it is opened, and again after the mod is updated. A starry top
 * with the glowing logo, the name and the version stays put (a click on the version opens what's new; after an
 * update it shows the version before and the new one); under it four pages slide sideways: what the mod can do (a
 * card per feature), the first things to know (numbered steps on key caps), who made and tested it with the author's
 * pages, and
 * the choice of the mod's language. The dots, Back and Next, the arrow keys and the mouse wheel flip the pages; the
 * last page's button (What's new: the changelog opens after it) and Enter on it finish it, the cross or Esc close it,
 * and Help closes it and opens the help.
 */
final class WelcomeWindow {

    /** What a click on the window did. */
    enum Click {
        NONE,
        /** Closed before the end: the cross or Esc. */
        CLOSE,
        /** Closed by the last page's button. */
        FINISH,
        HELP,
        /** The version was clicked: what's new, the window staying open under it. */
        CHANGELOG
    }

    private static final int WIDTH = 400, PAD = 14;
    private static final int HERO_HEIGHT = 100, SECTION_TITLE = 14, FOOTER_HEIGHT = 40;
    private static final int CARD_HEIGHT = 30, CARD_GAP = 6, FEATURE_COLUMNS = 2;
    private static final int STEP_HEIGHT = 24, CHIP_HEIGHT = 22, LINK_HEIGHT = 22, BUTTON_HEIGHT = 20;
    private static final int LANGUAGE_HEIGHT = 32, LANGUAGE_COLUMNS = 2;
    private static final int PAGES = 4, LANGUAGE_PAGE = PAGES - 1;
    /** How long a part takes to slide in, and how much later each part starts than the one before (ms). */
    private static final long SLIDE_MS = 280, STAGGER_MS = 60;
    /** Color of a key cap: the yellow of §e, as in the help. */
    private static final int KEY_CAP_COLOR = 0xFFFF55;
    private static final String[] ARROW_RIGHT = { "#..", "##.", "###", "##.", "#.." };
    private static final String[] ARROW_LEFT = { "..#", ".##", "###", ".##", "..#" };

    /** What the mod can do: {icon, translation key, color}. */
    private static final Object[][] FEATURES = { { Icons.ISO, "map", 0xFF4C9AFF },
        { Icons.WAYPOINTS, "waypoints", 0xFFE5534B }, { Icons.CAVES, "caves", 0xFFA371F7 },
        { Icons.TOPO, "topo", 0xFF3FB950 }, { Icons.ORE, "mods", 0xFFE3B341 }, { Icons.TEAM, "team", 0xFFDB61A2 } };
    /** The author's pages: {name, url, icon, color}. */
    private static final Object[][] LINKS = { { "GitHub", GuiAbout.GITHUB_URL, Icons.GITHUB, 0xFFE6EAF0 },
        { "Boosty", GuiAbout.BOOSTY_URL, Icons.BOOSTY, 0xFFF15F2C },
        { "Telegram", GuiAbout.TELEGRAM_URL, Icons.TELEGRAM, 0xFF2AABEE } };

    /** The stars twinkling over the top: {x, y} as parts of its size, and the phase of their twinkle. */
    private static final double[][] STARS = new double[34][3];

    static {
        Random random = new Random(11);
        for (double[] star : STARS) {
            star[0] = random.nextDouble();
            star[1] = 0.05 + random.nextDouble() * 0.85;
            star[2] = random.nextDouble() * Math.PI * 2;
        }
    }

    private final FontRenderer font;
    /** The version the window was last closed in when the mod has been updated since, null otherwise. */
    private final String updatedFrom;
    private final long openedAt = System.currentTimeMillis();
    /** The page shown (0 the first), where the slide between pages has got to, and when the page was turned to. */
    private int page;
    private final Smooth pagePosition = new Smooth(0);
    private long pageShownAt = openedAt + 2 * STAGGER_MS;

    /** Where things were drawn last, for the clicks: {x0, y0, x1, y1}. */
    private int[] nextRect = new int[4], backRect = new int[4], helpRect = new int[4], closeRect = new int[4],
        versionRect = new int[4];
    private final int[][] makerRects = new int[GuiAbout.MAKERS.length][4];
    private final int[][] linkRects = new int[LINKS.length][4], dotRects = new int[PAGES][4],
        languageRects = new int[Lang.CODES.length][4];
    /** How lit each thing is by the mouse, and how wide each page's dot is (the shown one is a long pill). */
    private final Smooth nextLight = new Smooth(0), backLight = new Smooth(0), helpLight = new Smooth(0),
        closeLight = new Smooth(0), backShown = new Smooth(0), versionLight = new Smooth(0);
    /** The panel's inside, which everything is cut to: {x0, y0, x1, y1}. */
    private int[] panelClip = new int[4];
    private final Smooth[] linkLight = smooths(LINKS.length, 0), cardLight = smooths(FEATURES.length, 0),
        dotWidth = smooths(PAGES, 6), chipLight = smooths(GuiAbout.TESTERS.length + 1, 0),
        makerLight = smooths(GuiAbout.MAKERS.length, 0), languageLight = smooths(Lang.CODES.length, 0),
        languageChosen = smooths(Lang.CODES.length, 0);

    /** @param updatedFrom the version the window was last closed in if the mod was updated since, else null */
    WelcomeWindow(FontRenderer font, String updatedFrom) {
        this.font = font;
        this.updatedFrom = updatedFrom;
        dotWidth[0].set(18);
        languageChosen[language()].set(1);
    }

    /** The version the window was last closed in, null on the first run. */
    String updatedFrom() {
        return updatedFrom;
    }

    private static int language() {
        return Math.max(0, Math.min(Lang.CODES.length - 1, Config.modLanguage));
    }

    private static Smooth[] smooths(int count, double value) {
        Smooth[] smooths = new Smooth[count];
        for (int i = 0; i < count; i++) {
            smooths[i] = new Smooth(value);
        }
        return smooths;
    }

    // ---------------------------------------------------------------- layout

    private static int textWidth() {
        return WIDTH - 2 * PAD;
    }

    private List<?> tagline() {
        return font.listFormattedStringToWidth(Lang.format("wayfarmap.welcome.text"), textWidth());
    }

    private static int featureRows() {
        return (FEATURES.length + FEATURE_COLUMNS - 1) / FEATURE_COLUMNS;
    }

    /** The first things to know: {key, what it does}. */
    private static String[][] steps() {
        String waypointKey = KeyHandler.keyName("new_waypoint");
        List<String[]> steps = new ArrayList<>();
        steps.add(new String[] { Lang.format("wayfarmap.welcome.key_drag"), Lang.format("wayfarmap.welcome.drag") });
        steps.add(new String[] { Lang.format("wayfarmap.welcome.key_menu"), Lang.format("wayfarmap.welcome.menu") });
        if (waypointKey != null) {
            steps.add(new String[] { waypointKey, Lang.format("wayfarmap.welcome.waypoint") });
        }
        steps.add(new String[] { "?", Lang.format("wayfarmap.welcome.help") });
        return steps.toArray(new String[0][]);
    }

    /** Width of the key caps' column: the widest cap. */
    private int keyColumn(String[][] steps) {
        int widest = 0;
        for (String[] step : steps) {
            widest = Math.max(widest, font.getStringWidth(step[0]) + 8);
        }
        return widest;
    }

    /** The testers' chips: the testers, then the chat. */
    private static String[] chips() {
        String[] chips = new String[GuiAbout.TESTERS.length + 1];
        System.arraycopy(GuiAbout.TESTERS, 0, chips, 0, GuiAbout.TESTERS.length);
        chips[GuiAbout.TESTERS.length] = Lang.format("wayfarmap.about.tester_chat");
        return chips;
    }

    private static int languageRows() {
        return (Lang.CODES.length + LANGUAGE_COLUMNS - 1) / LANGUAGE_COLUMNS;
    }

    private int pageHeight(int page) {
        switch (page) {
            case 0:
                return tagline().size() * 10 + 8
                    + SECTION_TITLE
                    + featureRows() * CARD_HEIGHT
                    + (featureRows() - 1) * CARD_GAP;
            case 1:
                return SECTION_TITLE + steps().length * STEP_HEIGHT;
            case 2:
                return 30 + SECTION_TITLE + 2 * CHIP_HEIGHT + 4 + 10 + LINK_HEIGHT;
            default:
                return SECTION_TITLE + 14
                    + languageRows() * LANGUAGE_HEIGHT
                    + (languageRows() - 1) * CARD_GAP
                    + 10
                    + 10;
        }
    }

    /** Height of the pages' area: the tallest page, so the window doesn't jump as they turn. */
    private int pagesHeight() {
        int height = 0;
        for (int i = 0; i < PAGES; i++) {
            height = Math.max(height, pageHeight(i));
        }
        return height;
    }

    private int height() {
        return HERO_HEIGHT + 12 + pagesHeight() + 12 + FOOTER_HEIGHT;
    }

    /** How far a part is still below its place, sliding up into it, {@code part} staggers later after the start. */
    private static int slide(long start, int part) {
        double t = (System.currentTimeMillis() - start - part * STAGGER_MS) / (double) SLIDE_MS;
        t = Math.max(0, Math.min(1, t));
        return (int) Math.round(Math.pow(1 - t, 3) * 12);
    }

    // ---------------------------------------------------------------- drawing

    void draw(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        int windowHeight = height();
        // The whole window rises into its place as it opens.
        int left = (screenWidth - WIDTH) / 2;
        int top = Math.max(4, (screenHeight - windowHeight) / 2) + slide(openedAt, 0) / 2;
        int right = left + WIDTH, bottom = top + windowHeight;
        // A soft shadow under the window, and a faint glow of the accent around it.
        Theme.fill(left - 3, top + 3, right + 3, bottom + 5, 0x30000000);
        Theme.fill(left - 1, top + 1, right + 1, bottom + 2, 0x40000000);
        Theme.outline(left - 1, top - 1, right + 1, bottom + 1, 0x304C9AFF);
        Theme.panel(left, top, right, bottom);
        panelClip = new int[] { left + 1, top + 1, right - 1, bottom - 1 };
        restoreClip();

        drawHero(left, right, top, mouseX, mouseY);

        // The pages, sliding sideways: each is drawn while any of it is in the window.
        double position = pagePosition.update(page, 14);
        int pagesTop = top + HERO_HEIGHT + 12;
        for (int i = 0; i < PAGES; i++) {
            double offset = i - position;
            if (Math.abs(offset) >= 1) {
                continue;
            }
            int x = left + (int) Math.round(offset * WIDTH);
            long start = i == page ? pageShownAt : 0;
            switch (i) {
                case 0:
                    drawFeatures(x, pagesTop, start, mouseX, mouseY);
                    break;
                case 1:
                    drawSteps(x, pagesTop, start);
                    break;
                case 2:
                    drawCredits(x, pagesTop, start, mouseX, mouseY);
                    break;
                default:
                    drawLanguages(x, pagesTop, start, mouseX, mouseY);
            }
        }

        drawFooter(left, right, bottom - FOOTER_HEIGHT, mouseX, mouseY);
        Theme.unclip();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** The top: a darker band with drifting glows and twinkling stars, the orbited logo, the name and the version. */
    private void drawHero(int left, int right, int top, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        int centerX = (left + right) / 2;
        int y = top + slide(openedAt, 0);
        // From the dark of the night at the top to the panel at the bottom.
        for (int band = 0; band < HERO_HEIGHT; band += 4) {
            int color = Theme.blend(0xF00E1218, Theme.PANEL_ALT, band / (double) HERO_HEIGHT);
            Theme.fill(left + 1, y + band, right - 1, Math.min(y + HERO_HEIGHT, y + band + 4), color);
        }
        // Two big soft glows drifting slowly in the corners.
        double drift = now / 4000.0;
        GuiAbout.drawRound(
            GuiAbout.CIRCLE,
            left + 60 + 14 * Math.sin(drift),
            y + 30 + 8 * Math.cos(drift * 1.3),
            70,
            0x104C9AFF);
        GuiAbout.drawRound(
            GuiAbout.CIRCLE,
            right - 70 + 12 * Math.cos(drift * 0.8),
            y + 70 + 10 * Math.sin(drift * 1.1),
            60,
            0x0EA371F7);
        int heroWidth = right - left - 2;
        for (double[] star : STARS) {
            double twinkle = 0.5 + 0.5 * Math.sin(now / 700.0 + star[2]);
            int sx = left + 1 + (int) (star[0] * heroWidth), sy = y + 4 + (int) (star[1] * (HERO_HEIGHT - 8));
            if (Math.abs(sx - centerX) < 80 && sy > y + 54 || Math.abs(sx - centerX) < 40) {
                // Not over the logo, the name and the version.
                continue;
            }
            int alpha = (int) (0x18 + 0x80 * twinkle);
            Theme.fill(sx, sy, sx + 1, sy + 1, alpha << 24 | (Theme.TEXT & 0xFFFFFF));
            if (twinkle > 0.92) {
                // The brightest ones sparkle into a little cross.
                int glint = (int) (0x60 * (twinkle - 0.92) / 0.08) << 24 | 0xFFFFFF;
                Theme.fill(sx - 1, sy, sx, sy + 1, glint);
                Theme.fill(sx + 1, sy, sx + 2, sy + 1, glint);
                Theme.fill(sx, sy - 1, sx + 1, sy, glint);
                Theme.fill(sx, sy + 1, sx + 1, sy + 2, glint);
            }
        }
        Theme.fill(left + 1, y + 1, right - 1, y + 3, Theme.ACCENT);
        Theme.fill(left + 1, y + HERO_HEIGHT, right - 1, y + HERO_HEIGHT + 1, Theme.BORDER);

        // The logo, glowing and breathing, with three sparks circling it like a compass needle's path.
        double pulse = 0.5 + 0.5 * Math.sin(now / 600.0);
        int logoCenterY = y + 32;
        int glow = Theme.ACCENT & 0xFFFFFF;
        GuiAbout.drawRound(GuiAbout.CIRCLE, centerX, logoCenterY, 34, (int) (0x0C + 0x08 * pulse) << 24 | glow);
        GuiAbout.drawRound(GuiAbout.CIRCLE, centerX, logoCenterY, 27, (int) (0x12 + 0x0E * pulse) << 24 | glow);
        double angle = now / 900.0;
        for (int i = 0; i < 3; i++) {
            double a = angle + i * Math.PI * 2 / 3;
            double sx = centerX + Math.cos(a) * 30, sy = logoCenterY + Math.sin(a) * 30;
            GuiAbout.drawRound(GuiAbout.CIRCLE, sx, sy, 3.5, 0x304C9AFF);
            GuiAbout.drawRound(GuiAbout.CIRCLE, sx, sy, 1.5, i == 0 ? 0xFFF2C14E : 0xC0E6EAF0);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - Icons.LOGO_SIZE * 3 / 2f, logoCenterY - Icons.LOGO_SIZE * 3 / 2f, 0f);
        GL11.glScalef(3f, 3f, 1f);
        Icons.drawLogo(0, 0);
        GL11.glPopMatrix();

        // The name, twice as big, "Map" in the accent, with a shine sweeping over it now and then.
        String first = "Wayfarer's ", second = "Map";
        int titleWidth = 2 * font.getStringWidth(first + second);
        int titleX = centerX - titleWidth / 2, titleY = y + 58;
        drawTitle(first, second, titleX, titleY, Theme.TEXT, Theme.ACCENT);
        double sweep = (now % 4500) / 900.0;
        if (sweep < 1) {
            int shineX = titleX - 20 + (int) ((titleWidth + 40) * sweep);
            Theme.clip(Math.max(left + 1, shineX), titleY, Math.min(right - 1, shineX + 10), titleY + 18);
            drawTitle(first, second, titleX, titleY, 0xFFFFFFFF, 0xFFB5D4FF);
            restoreClip();
        }

        drawVersion(centerX, y + 81, mouseX, mouseY);

        // The cross in the corner: close it right away.
        closeRect = new int[] { right - 20, top + 6, right - 6, top + 20 };
        double lit = closeLight.update(inside(mouseX, mouseY, closeRect) ? 1 : 0, 22);
        if (lit > 0) {
            roundRect(
                closeRect[0],
                closeRect[1],
                closeRect[2],
                closeRect[3],
                Theme.blend(0x00222831, Theme.CONTROL_HOVER, lit));
        }
        String[] cross = Icons.CLOSE;
        Icons.draw(
            cross,
            closeRect[0] + (14 - Icons.width(cross)) / 2,
            closeRect[1] + (14 - cross.length) / 2,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    /**
     * The version in a pill under the name, with the page icon of what's new; after an update the version before,
     * an arrow and the new one in green. Lit under the mouse: a click opens what's new.
     */
    private void drawVersion(int centerX, int y, int mouseX, int mouseY) {
        String current = Lang.format("wayfarmap.about.version", Tags.VERSION);
        String before = updatedFrom == null ? null : updatedFrom.isEmpty() ? "?" : updatedFrom;
        String[] arrow = ARROW_RIGHT, icon = Icons.SMALL_PAGE;
        int arrowWidth = before == null ? 0 : font.getStringWidth(before) + 5 + Icons.width(arrow) + 5;
        String shown = Theme.ellipsize(font, current, WIDTH - 100 - arrowWidth);
        int pillWidth = 6 + arrowWidth + font.getStringWidth(shown) + 6 + Icons.width(icon) + 6;
        int pillLeft = centerX - pillWidth / 2;
        versionRect = new int[] { pillLeft, y, pillLeft + pillWidth, y + 12 };
        double lit = versionLight.update(inside(mouseX, mouseY, versionRect) ? 1 : 0, 22);
        roundRect(pillLeft, y, pillLeft + pillWidth, y + 12, Theme.blend(0xC0222831, Theme.CONTROL_HOVER, lit));
        if (lit > 0) {
            Theme.outline(pillLeft, y, pillLeft + pillWidth, y + 12, Theme.blend(0x002B5A96, Theme.ACCENT, lit));
        }
        int x = pillLeft + 6;
        if (before != null) {
            Theme.text(font, before, x, y + 2, Theme.TEXT_MUTED);
            x += font.getStringWidth(before) + 5;
            Icons.draw(arrow, x, y + (12 - arrow.length) / 2, Theme.TEXT_MUTED);
            x += Icons.width(arrow) + 5;
        }
        int versionColor = before != null ? Theme.SUCCESS : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
        Theme.text(font, shown, x, y + 2, versionColor);
        x += font.getStringWidth(shown) + 6;
        Icons.draw(icon, x, y + (12 - icon.length) / 2, Theme.blend(Theme.TEXT_MUTED, Theme.ACCENT, lit));
    }

    private void drawTitle(String first, String second, int x, int y, int firstColor, int secondColor) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        GL11.glScalef(2f, 2f, 1f);
        font.drawStringWithShadow(first, 0, 0, firstColor);
        font.drawStringWithShadow(second, font.getStringWidth(first), 0, secondColor);
        GL11.glPopMatrix();
    }

    /** The first page: what it is, and a card for each thing it can do, lit in the feature's color. */
    private void drawFeatures(int left, int top, long start, int mouseX, int mouseY) {
        int centerX = left + WIDTH / 2;
        int y = top + slide(start, 0);
        for (Object line : tagline()) {
            Theme.centered(font, String.valueOf(line), centerX, y, Theme.TEXT);
            y += 10;
        }
        int base = top + tagline().size() * 10 + 8;
        sectionTitle(Lang.format("wayfarmap.welcome.features_title"), left, base + slide(start, 1));
        base += SECTION_TITLE;
        int cardWidth = (textWidth() - (FEATURE_COLUMNS - 1) * CARD_GAP) / FEATURE_COLUMNS;
        for (int i = 0; i < FEATURES.length; i++) {
            int column = i % FEATURE_COLUMNS, row = i / FEATURE_COLUMNS;
            int x0 = left + PAD + column * (cardWidth + CARD_GAP);
            int y0 = base + row * (CARD_HEIGHT + CARD_GAP) + slide(start, 2 + i);
            int[] r = { x0, y0, x0 + cardWidth, y0 + CARD_HEIGHT };
            double lit = cardLight[i].update(start != 0 && inside(mouseX, mouseY, r) ? 1 : 0, 18);
            drawFeatureCard(i, r, lit);
        }
    }

    /** A feature: its icon on a tile of its color, its name and a line on it; the card lifts under the mouse. */
    private void drawFeatureCard(int i, int[] r, double lit) {
        int color = (Integer) FEATURES[i][2];
        int lift = (int) Math.round(lit);
        int x0 = r[0], y0 = r[1] - lift, x1 = r[2], y1 = r[3] - lift;
        Theme.fill(x0, y0, x1, y1, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(x0, y0, x1, y1, Theme.blend(Theme.BORDER, color, lit));
        // The feature's color along the left edge.
        Theme.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, Theme.blend(color & 0x80FFFFFF, color, lit));
        int tile = 20, tileX = x0 + 6, tileY = y0 + (CARD_HEIGHT - tile) / 2;
        roundRect(tileX, tileY, tileX + tile, tileY + tile, (int) (0x28 + 0x28 * lit) << 24 | (color & 0xFFFFFF));
        String[] icon = (String[]) FEATURES[i][0];
        Icons.draw(icon, tileX + (tile - Icons.width(icon)) / 2, tileY + (tile - icon.length) / 2, color);
        int textX = tileX + tile + 7, textWidth = x1 - 6 - textX;
        String key = "wayfarmap.welcome.feature." + FEATURES[i][1];
        Theme.text(font, Theme.ellipsize(font, Lang.format(key), textWidth), textX, y0 + 5, Theme.TEXT);
        String description = Theme.ellipsize(font, Lang.format(key + ".desc"), textWidth);
        Theme.text(font, description, textX, y0 + 16, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit * 0.5));
    }

    /** The second page: the first things to know, numbered along a line, each on its key cap. */
    private void drawSteps(int left, int top, long start) {
        sectionTitle(Lang.format("wayfarmap.welcome.start_title"), left, top + slide(start, 0));
        String[][] steps = steps();
        int keyColumn = keyColumn(steps);
        int base = top + SECTION_TITLE;
        int badgeX = left + PAD + 8;
        // The line the numbers stand on.
        Theme.fill(
            badgeX,
            base + STEP_HEIGHT / 2 - 2,
            badgeX + 1,
            base + (steps.length - 1) * STEP_HEIGHT + STEP_HEIGHT / 2 - 2,
            Theme.BORDER);
        for (int i = 0; i < steps.length; i++) {
            int y = base + i * STEP_HEIGHT + slide(start, 1 + i);
            int centerY = y + STEP_HEIGHT / 2 - 2;
            GuiAbout.drawRound(GuiAbout.CIRCLE, badgeX + 0.5, centerY, 8, Theme.PANEL | 0xFF000000);
            GuiAbout.drawRound(GuiAbout.CIRCLE, badgeX + 0.5, centerY, 7, Theme.ACCENT_DIM);
            Theme.centered(font, String.valueOf(i + 1), badgeX + 1, centerY - 3, Theme.TEXT);
            int keyX = badgeX + 16;
            drawKeyCap(steps[i][0], keyX, centerY - 7);
            int textX = keyX + keyColumn + 10;
            String text = Theme.ellipsize(font, steps[i][1], left + WIDTH - PAD - textX);
            Theme.text(font, text, textX, centerY - 4, Theme.TEXT);
        }
    }

    /** The third page: ready to go, who made and tested it, and the author's pages. */
    private void drawCredits(int left, int top, long start, int mouseX, int mouseY) {
        int y = top + slide(start, 0);
        // A green tick in a circle, then the title and the wish.
        int tickX = left + PAD + 11, tickY = y + 11;
        GuiAbout.drawRound(GuiAbout.CIRCLE, tickX, tickY, 11, 0x303FB950);
        GuiAbout.drawRound(GuiAbout.CIRCLE, tickX, tickY, 8, Theme.SUCCESS);
        String[] tick = Icons.SMALL_CHECK;
        Icons.draw(tick, tickX - Icons.width(tick) / 2, tickY - tick.length / 2, 0xFF0E1A10);
        int textX = left + PAD + 30;
        Theme.text(font, Lang.format("wayfarmap.welcome.ready_title"), textX, y + 2, Theme.SUCCESS);
        String wish = Theme.ellipsize(font, Lang.format("wayfarmap.welcome.ready_text"), WIDTH - PAD - 30 - PAD);
        Theme.text(font, wish, textX, y + 13, Theme.TEXT_MUTED);

        y = top + 30 + slide(start, 1);
        sectionTitle(Lang.format("wayfarmap.welcome.credits_title"), left, y);
        y += SECTION_TITLE;
        // Those who made it first, in the accent, then the testers and the chat: in a line, going on in a second one.
        boolean interactive = start != 0;
        int x = left + PAD;
        for (int i = 0; i < GuiAbout.MAKERS.length; i++) {
            String maker = GuiAbout.MAKERS[i][0];
            int w = 3 + 16 + 6 + font.getStringWidth(maker) + 8 + Icons.width(Icons.GITHUB) + 6;
            if (x > left + PAD && x + w > left + WIDTH - PAD) {
                x = left + PAD;
                y += CHIP_HEIGHT + 4;
            }
            makerRects[i] = new int[] { x, y, x + w, y + CHIP_HEIGHT };
            boolean hovered = interactive && inside(mouseX, mouseY, makerRects[i]);
            drawChip(makerRects[i], maker, -1, makerLight[i].update(hovered ? 1 : 0, 22));
            x += w + 6;
        }
        String[] chips = chips();
        for (int i = 0; i < chips.length; i++) {
            int w = 3 + 16 + 6 + font.getStringWidth(chips[i]) + 8;
            if (x > left + PAD && x + w > left + WIDTH - PAD) {
                x = left + PAD;
                y += CHIP_HEIGHT + 4;
            }
            int[] r = { x, y, x + w, y + CHIP_HEIGHT };
            drawChip(r, chips[i], i, chipLight[i].update(interactive && inside(mouseX, mouseY, r) ? 1 : 0, 22));
            x += w + 6;
        }

        y = top + 30 + SECTION_TITLE + 2 * CHIP_HEIGHT + 4 + 10 + slide(start, 2);
        int gap = 6, linkWidth = (textWidth() - 2 * gap) / 3;
        for (int i = 0; i < LINKS.length; i++) {
            int x0 = left + PAD + i * (linkWidth + gap);
            linkRects[i] = new int[] { x0, y, i == LINKS.length - 1 ? left + WIDTH - PAD : x0 + linkWidth,
                y + LINK_HEIGHT };
            boolean hovered = interactive && inside(mouseX, mouseY, linkRects[i]);
            drawLink(i, linkRects[i], linkLight[i].update(hovered ? 1 : 0, 22));
        }
    }

    /**
     * The last page: the mod's language, a card for each with its flag and its name, the chosen one lit in the
     * accent with a tick; a click switches all of the mod's texts at once. Under them, where to change it later.
     */
    private void drawLanguages(int left, int top, long start, int mouseX, int mouseY) {
        sectionTitle(Lang.format("wayfarmap.welcome.language_title"), left, top + slide(start, 0));
        int y = top + SECTION_TITLE + slide(start, 1);
        String text = Theme.ellipsize(font, Lang.format("wayfarmap.welcome.language_text"), textWidth());
        Theme.text(font, text, left + PAD, y, Theme.TEXT_MUTED);
        int base = top + SECTION_TITLE + 14;
        int cardWidth = (textWidth() - (LANGUAGE_COLUMNS - 1) * CARD_GAP) / LANGUAGE_COLUMNS;
        boolean interactive = start != 0;
        for (int i = 0; i < Lang.CODES.length; i++) {
            int column = i % LANGUAGE_COLUMNS, row = i / LANGUAGE_COLUMNS;
            int x0 = left + PAD + column * (cardWidth + CARD_GAP);
            int y0 = base + row * (LANGUAGE_HEIGHT + CARD_GAP) + slide(start, 2 + i);
            languageRects[i] = new int[] { x0, y0, x0 + cardWidth, y0 + LANGUAGE_HEIGHT };
            double lit = languageLight[i].update(interactive && inside(mouseX, mouseY, languageRects[i]) ? 1 : 0, 18);
            double chosen = languageChosen[i].update(i == language() ? 1 : 0, 16);
            drawLanguageCard(i, languageRects[i], lit, chosen);
        }
        y = base + languageRows() * (LANGUAGE_HEIGHT + CARD_GAP) - CARD_GAP + 10 + slide(start, 2 + Lang.CODES.length);
        String[] gear = Icons.SMALL_GEAR;
        String hint = Theme.ellipsize(
            font,
            Lang.format("wayfarmap.welcome.language_hint", settingsPath()),
            textWidth() - Icons.width(gear) - 5);
        Icons.draw(gear, left + PAD, y, Theme.TEXT_MUTED);
        Theme.text(font, hint, left + PAD + Icons.width(gear) + 5, y, Theme.TEXT_MUTED);
    }

    /** Where the language is in the settings: "Settings → World map → Mod language", in the mod's language. */
    private static String settingsPath() {
        return Lang.format("wayfarmap.gui.settings") + " → "
            + Lang.format("wayfarmap.settings.map")
            + " → "
            + Lang.format("wayfarmap.settings.group.language");
    }

    /** A language: its flag twice as big in a frame, its name, and a round check, filled when it is chosen. */
    private void drawLanguageCard(int i, int[] r, double lit, double chosen) {
        int lift = (int) Math.round(lit);
        int x0 = r[0], y0 = r[1] - lift, x1 = r[2], y1 = r[3] - lift;
        int background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
        Theme.fill(x0, y0, x1, y1, Theme.blend(background, 0xFF1E3350, chosen));
        Theme.outline(
            x0,
            y0,
            x1,
            y1,
            Theme.blend(Theme.blend(Theme.BORDER, Theme.TEXT_MUTED, lit), Theme.ACCENT, chosen));
        // The accent along the left edge of the chosen one.
        if (chosen > 0.01) {
            Theme.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, (int) (0xFF * chosen) << 24 | (Theme.ACCENT & 0xFFFFFF));
        }
        int flagWidth = Flags.WIDTH * 2, flagHeight = Flags.HEIGHT * 2;
        int flagX = x0 + 6, flagY = y0 + (LANGUAGE_HEIGHT - flagHeight) / 2;
        Theme.fill(flagX - 1, flagY - 1, flagX + flagWidth + 1, flagY + flagHeight + 1, 0x60000000);
        GL11.glPushMatrix();
        GL11.glTranslatef(flagX, flagY, 0f);
        GL11.glScalef(2f, 2f, 1f);
        Flags.draw(Lang.CODES[i], 0, 0);
        GL11.glPopMatrix();
        if (chosen < 1 && lit < 1) {
            // The languages not chosen a little dimmed, as in the settings.
            int dim = (int) (0x50 * (1 - Math.max(chosen, lit))) << 24;
            Theme.fill(flagX, flagY, flagX + flagWidth, flagY + flagHeight, dim);
        }
        String name = Lang.format("wayfarmap.option.map.language." + Lang.CODES[i]);
        int textX = flagX + flagWidth + 9;
        int nameColor = Theme.blend(Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.6 + 0.4 * lit), Theme.TEXT, chosen);
        name = Theme.ellipsize(font, name, x1 - 26 - textX);
        Theme.text(font, name, textX, y0 + (LANGUAGE_HEIGHT - 8) / 2, nameColor);
        // The round check on the right: a ring, filled with a tick when chosen.
        int checkX = x1 - 13, checkY = y0 + LANGUAGE_HEIGHT / 2;
        int ring = Theme.blend(Theme.BORDER, Theme.ACCENT, Math.max(lit * 0.5, chosen));
        GuiAbout.drawRound(GuiAbout.CIRCLE, checkX, checkY, 6, ring);
        GuiAbout.drawRound(GuiAbout.CIRCLE, checkX, checkY, 5, Theme.blend(Theme.CONTROL, Theme.ACCENT, chosen));
        if (chosen > 0.5) {
            String[] tick = Icons.SMALL_CHECK;
            Icons.draw(tick, checkX - Icons.width(tick) / 2, checkY - tick.length / 2, Theme.TEXT);
        }
    }

    /** Chooses the mod's language: every text of the mod, this window too, switches to it at once. */
    private static void chooseLanguage(int i) {
        if (Config.modLanguage != i) {
            Config.modLanguage = i;
            Config.save();
        }
    }

    /** A person (an avatar and the name) or the chat (its icon and name); the makers' have the GitHub logo too. */
    private void drawChip(int[] r, String name, int i, double lit) {
        boolean author = i < 0, chat = i >= GuiAbout.TESTERS.length;
        int color = author ? Theme.ACCENT
            : chat ? 0xFFF2C14E : GuiAbout.AVATAR_COLORS[i % GuiAbout.AVATAR_COLORS.length];
        Theme.fill(r[0], r[1], r[2], r[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(r[0], r[1], r[2], r[3], Theme.blend(author ? Theme.ACCENT_DIM : Theme.BORDER, color, lit));
        int iconCenterX = r[0] + 11, centerY = (r[1] + r[3]) / 2;
        if (chat) {
            GuiAbout.drawRound(GuiAbout.CHAT_ICON, iconCenterX, centerY, 8, 0xFFFFFFFF);
        } else {
            GuiAbout.drawAvatar(iconCenterX, centerY, 8, name, color);
        }
        int textColor = author ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5 + lit / 2);
        Theme.text(font, name, r[0] + 25, r[1] + 7, textColor);
        if (author) {
            String[] icon = Icons.GITHUB;
            Icons.draw(
                icon,
                r[2] - 6 - Icons.width(icon),
                centerY - icon.length / 2,
                Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
        }
    }

    /** One of the author's pages: its logo in the site's color and its name, lit in that color under the mouse. */
    private void drawLink(int i, int[] r, double lit) {
        int brand = (Integer) LINKS[i][3];
        Theme.fill(r[0], r[1], r[2], r[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(r[0], r[1], r[2], r[3], Theme.blend(Theme.BORDER, brand, lit));
        // The site's color as a strip along the bottom, growing from the middle under the mouse.
        int half = (int) Math.round((r[2] - r[0] - 2) / 2.0 * (0.3 + 0.7 * lit));
        int middle = (r[0] + r[2]) / 2;
        Theme.fill(middle - half, r[3] - 2, middle + half, r[3] - 1, brand);
        String[] icon = (String[]) LINKS[i][2];
        String name = (String) LINKS[i][0];
        int contentWidth = Icons.width(icon) + 5 + font.getStringWidth(name);
        int x = (r[0] + r[2] - contentWidth) / 2;
        int lift = (int) Math.round(lit);
        Icons.draw(icon, x, r[1] + (LINK_HEIGHT - 1 - icon.length) / 2 - lift, brand);
        Theme.text(
            font,
            name,
            x + Icons.width(icon) + 5,
            r[1] + (LINK_HEIGHT - 9) / 2 - lift,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    /** The bottom: Help on the left, the pages' dots in the middle, Back and Next (or Let's go) on the right. */
    private void drawFooter(int left, int right, int top, int mouseX, int mouseY) {
        Theme.fill(left + 1, top, right - 1, top + 1, Theme.BORDER);
        Theme.fill(left + 1, top + 1, right - 1, top + FOOTER_HEIGHT - 1, 0x30000000);
        int y = top + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
        boolean last = page == PAGES - 1;

        String help = Lang.format("wayfarmap.welcome.open_help");
        int helpWidth = font.getStringWidth(help) + 30;
        helpRect = new int[] { left + PAD, y, left + PAD + helpWidth, y + BUTTON_HEIGHT };
        double helpLit = helpLight.update(inside(mouseX, mouseY, helpRect) ? 1 : 0, 22);
        drawButton(helpRect, help, Icons.HELP, false, false, helpLit);

        String next = Lang.format(!last ? "wayfarmap.welcome.next" : "wayfarmap.welcome.whats_new");
        int nextWidth = Math.max(90, font.getStringWidth(next) + 30);
        nextRect = new int[] { right - PAD - nextWidth, y, right - PAD, y + BUTTON_HEIGHT };
        if (last) {
            // A ring breathing around the last button: here is the way in.
            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0);
            int alpha = (int) (0x30 + 0x70 * pulse) << 24;
            Theme.outline(nextRect[0] - 2, y - 2, nextRect[2] + 2, y + BUTTON_HEIGHT + 2, alpha | 0x4C9AFF);
        }
        double nextLit = nextLight.update(inside(mouseX, mouseY, nextRect) ? 1 : 0, 22);
        drawButton(nextRect, next, ARROW_RIGHT, true, true, nextLit);

        // Back slides out from under Next on the pages after the first.
        double shown = backShown.update(page > 0 ? 1 : 0, 16);
        String back = Lang.format("wayfarmap.welcome.back");
        int backWidth = font.getStringWidth(back) + 26;
        if (shown > 0.02) {
            int x1 = nextRect[0] - 6 + (int) Math.round((1 - shown) * (backWidth + 6));
            backRect = new int[] { x1 - backWidth, y, x1, y + BUTTON_HEIGHT };
            Theme.clip(helpRect[2] + 4, top + 1, nextRect[0], top + FOOTER_HEIGHT - 1);
            double backLit = backLight.update(page > 0 && inside(mouseX, mouseY, backRect) ? 1 : 0, 22);
            drawButton(backRect, back, ARROW_LEFT, false, false, backLit);
            restoreClip();
        } else {
            backRect = new int[4];
        }

        // The dots, centered between Help and Back: the page shown is a long pill in the accent.
        int dotsRight = nextRect[0] - 6 - backWidth - 6;
        int dotsCenter = (helpRect[2] + dotsRight) / 2;
        double total = 0;
        for (int i = 0; i < PAGES; i++) {
            total += dotWidth[i].update(i == page ? 18 : 6, 16) + (i > 0 ? 5 : 0);
        }
        double x = dotsCenter - total / 2;
        int dotY = top + FOOTER_HEIGHT / 2 - 3;
        for (int i = 0; i < PAGES; i++) {
            double w = dotWidth[i].get();
            int x0 = (int) Math.round(x), x1 = (int) Math.round(x + w);
            boolean hovered = Theme.inside(mouseX, mouseY, x0 - 2, dotY - 4, x1 + 2, dotY + 10);
            double active = (w - 6) / 12;
            int color = Theme.blend(hovered ? Theme.TEXT_MUTED : Theme.BORDER, Theme.ACCENT, active);
            roundRect(x0, dotY, x1, dotY + 6, color);
            dotRects[i] = new int[] { x0 - 2, dotY - 4, x1 + 2, dotY + 10 };
            x += w + 5;
        }
    }

    /** A button: the accent one is filled, the others plain; an icon before the text, or an arrow after it. */
    private void drawButton(int[] r, String label, String[] icon, boolean primary, boolean iconAfter, double lit) {
        int background = primary ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
            : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
        Theme.fill(r[0], r[1], r[2], r[3], background);
        Theme.outline(r[0], r[1], r[2], r[3], primary ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        if (primary) {
            // A lighter top edge, so it stands out like a pressable key.
            Theme.fill(r[0] + 1, r[1] + 1, r[2] - 1, r[1] + 2, 0x30FFFFFF);
        }
        int textWidth = font.getStringWidth(label);
        int iconWidth = icon == null ? 0 : Icons.width(icon) + 5;
        int x = (r[0] + r[2] - textWidth - iconWidth) / 2;
        int textY = r[1] + (BUTTON_HEIGHT - 8) / 2;
        int iconColor = primary ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.ACCENT, lit);
        if (icon != null && !iconAfter) {
            Icons.draw(icon, x, r[1] + (BUTTON_HEIGHT - icon.length) / 2, iconColor);
            x += iconWidth;
        }
        Theme.text(font, label, x, textY, Theme.TEXT);
        if (icon != null && iconAfter) {
            // The arrow nudges forward under the mouse.
            int nudge = (int) Math.round(lit * 2);
            Icons.draw(icon, x + textWidth + 5 + nudge, r[1] + (BUTTON_HEIGHT - icon.length) / 2, iconColor);
        }
    }

    /** A small title in the accent color with a line after it, like the settings' sections. */
    private void sectionTitle(String title, int left, int y) {
        Theme.text(font, title, left + PAD, y, Theme.ACCENT);
        int lineX = left + PAD + font.getStringWidth(title) + 6;
        Theme.fill(lineX, y + 4, left + WIDTH - PAD, y + 5, Theme.BORDER);
    }

    /** A key cap with the text on it, as in the help. */
    private void drawKeyCap(String key, int x, int y) {
        int w = font.getStringWidth(key) + 8;
        roundRect(x, y, x + w, y + 13, 0x60000000 | KEY_CAP_COLOR);
        // The darker edge at the bottom shows under the cap, like a key.
        roundRect(x, y, x + w, y + 12, 0xFF2A2A1A);
        roundRect(x, y, x + w, y + 12, 0x30000000 | KEY_CAP_COLOR);
        Theme.text(font, key, x + 4, y + 2, 0xFF000000 | KEY_CAP_COLOR);
    }

    /** A rectangle with its corners cut by a pixel, which reads as rounded at this size. */
    private static void roundRect(int x0, int y0, int x1, int y1, int color) {
        Theme.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
        Theme.fill(x0, y0 + 1, x1, y1 - 1, color);
        Theme.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
    }

    private void restoreClip() {
        Theme.clip(panelClip[0], panelClip[1], panelClip[2], panelClip[3]);
    }

    private static boolean inside(int mouseX, int mouseY, int[] r) {
        return Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
    }

    // ---------------------------------------------------------------- input

    private void turnTo(int newPage) {
        newPage = Math.max(0, Math.min(PAGES - 1, newPage));
        if (newPage != page) {
            page = newPage;
            pageShownAt = System.currentTimeMillis();
        }
    }

    /**
     * Handles a click: Next turns the page (finishes on the last), Back and the dots turn it, Help and the cross
     * close the window (Help also opens the help), the version opens what's new, the author and the links open their
     * page, a language card chooses it.
     */
    Click click(int mouseX, int mouseY) {
        if (inside(mouseX, mouseY, closeRect)) {
            return Click.CLOSE;
        }
        if (inside(mouseX, mouseY, versionRect)) {
            return Click.CHANGELOG;
        }
        if (inside(mouseX, mouseY, helpRect)) {
            return Click.HELP;
        }
        if (inside(mouseX, mouseY, nextRect)) {
            if (page == PAGES - 1) {
                return Click.FINISH;
            }
            turnTo(page + 1);
            return Click.NONE;
        }
        if (page > 0 && inside(mouseX, mouseY, backRect)) {
            turnTo(page - 1);
            return Click.NONE;
        }
        for (int i = 0; i < PAGES; i++) {
            if (inside(mouseX, mouseY, dotRects[i])) {
                turnTo(i);
                return Click.NONE;
            }
        }
        if (page == LANGUAGE_PAGE) {
            for (int i = 0; i < Lang.CODES.length; i++) {
                if (inside(mouseX, mouseY, languageRects[i])) {
                    chooseLanguage(i);
                    return Click.NONE;
                }
            }
        }
        if (page == 2) {
            for (int i = 0; i < GuiAbout.MAKERS.length; i++) {
                if (inside(mouseX, mouseY, makerRects[i])) {
                    GuiAbout.openLink(GuiAbout.MAKERS[i][2]);
                    return Click.NONE;
                }
            }
            for (int i = 0; i < LINKS.length; i++) {
                if (inside(mouseX, mouseY, linkRects[i])) {
                    GuiAbout.openLink((String) LINKS[i][1]);
                    return Click.NONE;
                }
            }
        }
        return Click.NONE;
    }

    /**
     * Handles a key: the arrows turn the page, Enter turns it (finishes on the last), Esc closes; on the language
     * page the number keys and up and down choose the language.
     */
    Click key(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                return Click.CLOSE;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
            case Keyboard.KEY_SPACE:
                if (page == PAGES - 1) {
                    return Click.FINISH;
                }
                turnTo(page + 1);
                return Click.NONE;
            case Keyboard.KEY_RIGHT:
            case Keyboard.KEY_D:
                turnTo(page + 1);
                return Click.NONE;
            case Keyboard.KEY_LEFT:
            case Keyboard.KEY_A:
                turnTo(page - 1);
                return Click.NONE;
            case Keyboard.KEY_UP:
            case Keyboard.KEY_W:
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_S:
                if (page == LANGUAGE_PAGE) {
                    int step = keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_W ? -1 : 1;
                    chooseLanguage((language() + step + Lang.CODES.length) % Lang.CODES.length);
                }
                return Click.NONE;
            default:
                int number = keyCode - Keyboard.KEY_1;
                if (page == LANGUAGE_PAGE && number >= 0 && number < Lang.CODES.length) {
                    chooseLanguage(number);
                }
                return Click.NONE;
        }
    }

    /** The mouse wheel turns the pages: down to the next one, up to the one before. */
    void scroll(int wheel) {
        turnTo(page + (wheel < 0 ? 1 : -1));
    }
}
