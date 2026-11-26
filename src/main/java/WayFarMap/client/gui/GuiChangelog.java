package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Tags;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * What's new: the versions of {@code assets/wayfarmap/changelog/<language>.md}, the newest first, each a card on a
 * timeline with its number, date and what changed, the points marked new, fixed, changed or removed. After an update
 * the versions newer than the one before are marked new and their dots glow. The list scrolls with the wheel, the
 * keys and the scrollbar; Esc, the cross or Close go back to the screen it was opened from.
 */
public class GuiChangelog extends ScaledScreen {

    private static final int WIDTH = 400, PAD = 14;
    private static final int HEADER_HEIGHT = 58, FOOTER_HEIGHT = 40, BUTTON_HEIGHT = 20;
    /** Where the cards start right of the timeline, and the padding inside a card. */
    private static final int TIMELINE = 18, CARD_PAD = 9;
    private static final int CARD_HEADER = 20, LINE = 10, RELEASE_GAP = 10;
    /** How far a notch of the wheel and an arrow key scroll (GUI pixels). */
    private static final int WHEEL_STEP = 36, KEY_STEP = 20;
    /** How long a card takes to slide in, and how much later each one starts than the one before (ms). */
    private static final long SLIDE_MS = 280, STAGGER_MS = 60;
    private static final String GITHUB_RELEASES = GuiAbout.MOD_GITHUB_URL + "/releases";

    /** The stars twinkling over the top: {x, y} as parts of its size, and the phase of their twinkle. */
    private static final double[][] STARS = new double[20][3];

    static {
        Random random = new Random(5);
        for (double[] star : STARS) {
            star[0] = random.nextDouble();
            star[1] = 0.1 + random.nextDouble() * 0.8;
            star[2] = random.nextDouble() * Math.PI * 2;
        }
    }

    /** A laid out part of a card: what it is, its tag, its lines wrapped, and its top in the card's list. */
    private static final class Block {

        final Changelog.Kind kind;
        final Changelog.Tag tag;
        final List<String> lines;
        int y;

        Block(Changelog.Kind kind, Changelog.Tag tag, List<String> lines) {
            this.kind = kind;
            this.tag = tag;
            this.lines = lines;
        }

        int height() {
            switch (kind) {
                case HEADING:
                    return 16;
                case NOTE:
                    return lines.size() * LINE + 6;
                default:
                    return lines.size() * LINE + 3;
            }
        }
    }

    /** A version's card laid out: its blocks, and its top and height in the list. */
    private static final class Card {

        final Changelog.Release release;
        final boolean fresh, installed;
        final List<Block> blocks = new ArrayList<>();
        int y, height;

        Card(Changelog.Release release, boolean fresh, boolean installed) {
            this.release = release;
            this.fresh = fresh;
            this.installed = installed;
        }
    }

    private final GuiScreen parent;
    /** The version before the update: the versions after it are new. Null when the screen was opened by hand. */
    private final String updatedFrom;
    private final long openedAt = System.currentTimeMillis();

    private List<Card> cards = new ArrayList<>();
    private int contentHeight;
    /** How far the list is scrolled, eased toward where it should be. */
    private double scrollTarget;
    private final Smooth scroll = new Smooth(0);
    /** The scrollbar is being dragged: where on the thumb it was taken. */
    private boolean draggingBar;
    private int dragOffset;

    /** Where things were drawn last, for the clicks: {x0, y0, x1, y1}. */
    private int[] closeRect = new int[4], crossRect = new int[4], githubRect = new int[4], barRect = new int[4];
    private final Smooth closeLight = new Smooth(0), crossLight = new Smooth(0), githubLight = new Smooth(0);

    /**
     * @param updatedFrom the version the mod was updated from, whose later versions are marked new; null for none
     */
    public GuiChangelog(GuiScreen parent, String updatedFrom) {
        this.parent = parent;
        this.updatedFrom = updatedFrom;
    }

    @Override
    public void initGui() {
        super.initGui();
        // Again on each layout: the mod's language may have changed.
        layOut();
    }

    // ---------------------------------------------------------------- layout

    private static int cardWidth() {
        return WIDTH - 2 * PAD - TIMELINE - 6;
    }

    /** Width of the tags' badges: the widest one, so the points' texts start in a line. */
    private int tagWidth() {
        int widest = 0;
        for (Changelog.Tag tag : Changelog.Tag.values()) {
            widest = Math.max(widest, fontRendererObj.getStringWidth(Lang.format(tag.key())));
        }
        return widest + 8;
    }

    /** Wraps the versions' texts to the cards and places the cards one under another. */
    private void layOut() {
        cards = new ArrayList<>();
        int textWidth = cardWidth() - 2 * CARD_PAD;
        int tagWidth = tagWidth();
        int y = 0;
        for (Changelog.Release release : Changelog.load()) {
            boolean fresh = updatedFrom != null && Changelog.compare(release.version, updatedFrom) > 0;
            boolean installed = Changelog.compare(release.version, Tags.VERSION) == 0;
            Card card = new Card(release, fresh, installed);
            int blockY = CARD_HEADER + 4;
            for (Changelog.Entry entry : release.entries) {
                int width;
                switch (entry.kind) {
                    case POINT:
                        width = textWidth - (entry.tag != null ? tagWidth + 5 : 9);
                        break;
                    case SUB_POINT:
                        width = textWidth - (entry.tag != null ? tagWidth + 5 : 0) - 18;
                        break;
                    case NOTE:
                        width = textWidth - 10;
                        break;
                    default:
                        width = textWidth;
                }
                Block block = new Block(entry.kind, entry.tag, wrap(entry.text, width));
                if (entry.kind == Changelog.Kind.HEADING && !card.blocks.isEmpty()) {
                    blockY += 4;
                }
                block.y = blockY;
                blockY += block.height();
                card.blocks.add(block);
            }
            card.y = y;
            card.height = blockY + CARD_PAD - 3;
            y += card.height + RELEASE_GAP;
            cards.add(card);
        }
        contentHeight = Math.max(0, y - RELEASE_GAP);
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (Object line : fontRendererObj.listFormattedStringToWidth(text, Math.max(20, width))) {
            lines.add(String.valueOf(line));
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private int windowHeight() {
        int wanted = HEADER_HEIGHT + 10 + Math.max(60, contentHeight) + 10 + FOOTER_HEIGHT;
        return Math.max(160, Math.min(wanted, height - 16));
    }

    private int listHeight() {
        return windowHeight() - HEADER_HEIGHT - FOOTER_HEIGHT - 20;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - listHeight());
    }

    private void scrollTo(double target) {
        scrollTarget = Math.max(0, Math.min(maxScroll(), target));
    }

    /** How far a card is still below its place, sliding up into it as the screen opens. */
    private int slide(int part) {
        double t = (System.currentTimeMillis() - openedAt - part * STAGGER_MS) / (double) SLIDE_MS;
        t = Math.max(0, Math.min(1, t));
        return (int) Math.round(Math.pow(1 - t, 3) * 12);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.dimScreen(0, 0, width, height);
        int windowHeight = windowHeight();
        int left = (width - WIDTH) / 2, top = Math.max(8, (height - windowHeight) / 2);
        int right = left + WIDTH, bottom = top + windowHeight;
        // A soft shadow under the window, and a faint glow of the accent around it.
        Theme.fill(left - 3, top + 3, right + 3, bottom + 5, 0x30000000);
        Theme.fill(left - 1, top + 1, right + 1, bottom + 2, 0x40000000);
        Theme.outline(left - 1, top - 1, right + 1, bottom + 1, Theme.alpha(Theme.ACCENT, 0x30));
        Theme.panel(left, top, right, bottom);
        Theme.clip(left + 1, top + 1, right - 1, bottom - 1);

        drawHeader(left, right, top, mouseX, mouseY);
        drawList(left, top + HEADER_HEIGHT + 10, mouseX, mouseY);
        Theme.clip(left + 1, top + 1, right - 1, bottom - 1);
        drawFooter(left, right, bottom - FOOTER_HEIGHT, mouseX, mouseY);

        Theme.unclip();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** The top: a darker band with twinkling stars, the logo, the title and the version (or the update). */
    private void drawHeader(int left, int right, int top, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        for (int band = 0; band < HEADER_HEIGHT; band += 4) {
            int color = Theme.blend(Theme.alpha(Theme.DEEP, 0xF0), Theme.PANEL_ALT, band / (double) HEADER_HEIGHT);
            Theme.fill(left + 1, top + band, right - 1, Math.min(top + HEADER_HEIGHT, top + band + 4), color);
        }
        int headerWidth = right - left - 2;
        for (double[] star : STARS) {
            double twinkle = 0.5 + 0.5 * Math.sin(now / 700.0 + star[2]);
            int sx = left + 1 + (int) (star[0] * headerWidth), sy = top + (int) (star[1] * HEADER_HEIGHT);
            if (sx < left + 230) {
                // Not over the logo and the title.
                continue;
            }
            int alpha = (int) (0x18 + 0x70 * twinkle);
            Theme.fill(sx, sy, sx + 1, sy + 1, alpha << 24 | (Theme.TEXT & 0xFFFFFF));
        }
        Theme.fill(left + 1, top + 1, right - 1, top + 3, Theme.ACCENT);
        Theme.fill(left + 1, top + HEADER_HEIGHT, right - 1, top + HEADER_HEIGHT + 1, Theme.BORDER);

        // The logo, glowing and breathing.
        double pulse = 0.5 + 0.5 * Math.sin(now / 600.0);
        int logoCenterX = left + PAD + 16, logoCenterY = top + HEADER_HEIGHT / 2 + 1;
        int glow = Theme.ACCENT & 0xFFFFFF;
        GuiAbout.drawRound(GuiAbout.CIRCLE, logoCenterX, logoCenterY, 22, (int) (0x0C + 0x08 * pulse) << 24 | glow);
        GuiAbout.drawRound(GuiAbout.CIRCLE, logoCenterX, logoCenterY, 17, (int) (0x12 + 0x0E * pulse) << 24 | glow);
        GL11.glPushMatrix();
        GL11.glTranslatef(logoCenterX - Icons.LOGO_SIZE, logoCenterY - Icons.LOGO_SIZE, 0f);
        GL11.glScalef(2f, 2f, 1f);
        Icons.drawLogo(0, 0);
        GL11.glPopMatrix();

        // The title twice as big, and under it the version or what it was updated from.
        int textX = logoCenterX + 26;
        GL11.glPushMatrix();
        GL11.glTranslatef(textX, top + 14, 0f);
        GL11.glScalef(2f, 2f, 1f);
        fontRendererObj.drawStringWithShadow(Lang.format("wayfarmap.changelog.title"), 0, 0, Theme.TEXT);
        GL11.glPopMatrix();
        String subtitle;
        if (updatedFrom != null) {
            String before = updatedFrom.isEmpty() ? "?" : updatedFrom;
            subtitle = Lang.format("wayfarmap.changelog.updated", "§7" + before + "§r", "§a" + Tags.VERSION + "§r");
        } else {
            subtitle = Lang.format("wayfarmap.changelog.subtitle", Tags.VERSION);
        }
        subtitle = Theme.ellipsize(fontRendererObj, subtitle, right - PAD - 24 - textX);
        Theme.text(fontRendererObj, subtitle, textX, top + 35, Theme.TEXT_MUTED);

        // The cross in the corner.
        crossRect = new int[] { right - 20, top + 6, right - 6, top + 20 };
        double lit = crossLight.update(inside(mouseX, mouseY, crossRect) ? 1 : 0, 22);
        if (lit > 0) {
            roundRect(
                crossRect[0],
                crossRect[1],
                crossRect[2],
                crossRect[3],
                Theme.blend(Theme.alpha(Theme.CONTROL, 0), Theme.CONTROL_HOVER, lit));
        }
        String[] cross = Icons.CLOSE;
        Icons.draw(
            cross,
            crossRect[0] + (14 - Icons.width(cross)) / 2,
            crossRect[1] + (14 - cross.length) / 2,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    /** The versions' cards along the timeline, scrolled and cut to the list; the scrollbar right of them. */
    private void drawList(int left, int top, int mouseX, int mouseY) {
        int listHeight = listHeight();
        int bottom = top + listHeight;
        double offset = scroll.update(scrollTarget, 16);
        Theme.clip(left + 1, top - 9, left + WIDTH - 1, bottom + 9);
        if (cards.isEmpty()) {
            String[] icon = Icons.SMALL_PAGE;
            int centerX = left + WIDTH / 2, centerY = top + listHeight / 2;
            Icons.draw(icon, centerX - Icons.width(icon) / 2, centerY - 14, Theme.TEXT_MUTED);
            String empty = Lang.format("wayfarmap.changelog.empty");
            Theme.centered(fontRendererObj, empty, centerX, centerY, Theme.TEXT_MUTED);
            return;
        }
        boolean listHovered = mouseY >= top - 9 && mouseY < bottom + 9;
        int lineX = left + PAD + 6;
        int first = top - (int) Math.round(offset);
        // The timeline, from the first card's dot to the last one's.
        Card last = cards.get(cards.size() - 1);
        Theme.fill(lineX, first + CARD_HEADER / 2, lineX + 1, first + last.y + CARD_HEADER / 2, Theme.BORDER);
        long now = System.currentTimeMillis();
        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            int y = first + card.y + (i < 8 ? slide(i) : 0);
            if (y > bottom + 9 || y + card.height < top - 9) {
                continue;
            }
            int x0 = left + PAD + TIMELINE, x1 = x0 + cardWidth();
            boolean hovered = listHovered && Theme.inside(mouseX, mouseY, x0, y, x1, y + card.height);
            drawCard(card, x0, y, x1, hovered);
            // The card's dot on the timeline: glowing for the new versions, in the accent for the installed one.
            int dotY = y + CARD_HEADER / 2;
            int color = card.fresh ? Theme.SUCCESS : card.installed || i == 0 ? Theme.ACCENT : Theme.TEXT_MUTED;
            if (card.fresh) {
                double pulse = 0.5 + 0.5 * Math.sin(now / 400.0 + i);
                int glow = (int) (0x30 + 0x30 * pulse) << 24 | (Theme.SUCCESS & 0xFFFFFF);
                GuiAbout.drawRound(GuiAbout.CIRCLE, lineX + 0.5, dotY, 7 + pulse, glow);
            }
            GuiAbout.drawRound(GuiAbout.CIRCLE, lineX + 0.5, dotY, 5, Theme.PANEL | 0xFF000000);
            GuiAbout.drawRound(GuiAbout.CIRCLE, lineX + 0.5, dotY, 3.5, color);
            // A short line from the dot to the card.
            Theme.fill(lineX + 6, dotY, x0, dotY + 1, Theme.blend(Theme.BORDER, color, 0.5));
        }

        // The list fades out at its edges while there is more to scroll to.
        if (offset > 0.5) {
            fade(left, top - 9, top + 3, true);
        }
        if (offset < maxScroll() - 0.5) {
            fade(left, bottom - 3, bottom + 9, false);
        }
        if (maxScroll() > 0) {
            int barX = left + WIDTH - 7;
            barRect = new int[] { barX - 2, top, barX + 4, bottom };
            boolean barLit = draggingBar || inside(mouseX, mouseY, barRect);
            Theme.scrollbar(barX, top, bottom, listHeight, contentHeight, offset / maxScroll(), barLit);
        } else {
            barRect = new int[4];
        }
    }

    /** The panel's color fading in over the list's edge: toward the top edge when {@code up}, else the bottom. */
    private static void fade(int left, int y0, int y1, boolean up) {
        int steps = y1 - y0;
        for (int i = 0; i < steps; i++) {
            double t = up ? 1 - i / (double) steps : i / (double) steps;
            int alpha = (int) Math.round(0xF0 * t * t);
            Theme.fill(left + 1, y0 + i, left + WIDTH - 9, y0 + i + 1, alpha << 24 | (Theme.PANEL & 0xFFFFFF));
        }
    }

    /** A version's card: its number, its badges and date on top, then what changed. */
    private void drawCard(Card card, int x0, int y0, int x1, boolean hovered) {
        int accent = card.fresh ? Theme.SUCCESS : Theme.ACCENT;
        int y1 = y0 + card.height;
        Theme.fill(x0, y0, x1, y1, hovered ? Theme.CARD_HOVER : Theme.CARD);
        Theme.outline(x0, y0, x1, y1, hovered || card.fresh ? Theme.blend(Theme.BORDER, accent, 0.6) : Theme.BORDER);
        // The top of the card a shade lighter, under the version.
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + CARD_HEADER, 0x14FFFFFF);
        Theme.fill(x0 + 1, y0 + CARD_HEADER, x1 - 1, y0 + CARD_HEADER + 1, Theme.BORDER);
        Theme.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, card.fresh || card.installed ? accent : 0x30FFFFFF);

        int x = x0 + CARD_PAD, textY = y0 + (CARD_HEADER - 8) / 2 + 1;
        String version = "§l" + card.release.version;
        fontRendererObj.drawStringWithShadow(version, x, textY, Theme.TEXT);
        x += fontRendererObj.getStringWidth(version) + 6;
        if (card.fresh) {
            x = badge(Lang.format("wayfarmap.changelog.badge_new"), x, y0 + 5, Theme.SUCCESS) + 4;
        }
        if (card.installed) {
            badge(Lang.format("wayfarmap.changelog.badge_installed"), x, y0 + 5, Theme.ACCENT);
        }
        if (!card.release.date.isEmpty()) {
            String date = card.release.date;
            int dateX = x1 - CARD_PAD - fontRendererObj.getStringWidth(date);
            Theme.text(fontRendererObj, date, dateX, textY, Theme.TEXT_MUTED);
        }

        int left = x0 + CARD_PAD, right = x1 - CARD_PAD, tagWidth = tagWidth();
        for (Block block : card.blocks) {
            int y = y0 + block.y;
            switch (block.kind) {
                case HEADING: {
                    String title = block.lines.get(0);
                    Theme.text(fontRendererObj, title, left, y + 3, accent);
                    int lineX = left + fontRendererObj.getStringWidth(title) + 6;
                    if (lineX < right) {
                        Theme.fill(lineX, y + 7, right, y + 8, Theme.BORDER);
                    }
                    break;
                }
                case POINT:
                case SUB_POINT: {
                    int textX = left + (block.kind == Changelog.Kind.SUB_POINT ? 18 : 0);
                    if (block.tag != null) {
                        drawTag(block.tag, textX, y, tagWidth);
                        textX += tagWidth + 5;
                    } else if (block.kind == Changelog.Kind.SUB_POINT) {
                        Theme.fill(textX - 7, y + 4, textX - 3, y + 5, Theme.TEXT_MUTED);
                    } else {
                        Theme.disc(textX + 2.5, y + 4.5, 2, accent);
                        textX += 9;
                    }
                    int color = block.kind == Changelog.Kind.SUB_POINT ? Theme.TEXT_MUTED : Theme.TEXT;
                    drawLines(block.lines, textX, y, color);
                    break;
                }
                case NOTE: {
                    int h = block.lines.size() * LINE + 2;
                    Theme.fill(left, y, right, y + h, Theme.alpha(Theme.ACCENT, 0x18));
                    Theme.fill(left, y, left + 2, y + h, Theme.ACCENT_DIM);
                    drawLines(block.lines, left + 7, y + 2, Theme.TEXT_MUTED);
                    break;
                }
                default:
                    drawLines(block.lines, left, y, Theme.TEXT_SOFT);
            }
        }
    }

    private void drawLines(List<String> lines, int x, int y, int color) {
        for (String line : lines) {
            Theme.text(fontRendererObj, line, x, y, color);
            y += LINE;
        }
    }

    /** A small pill with the text in its color on a tint of it; returns where it ends. */
    private int badge(String text, int x, int y, int color) {
        int w = fontRendererObj.getStringWidth(text) + 8;
        roundRect(x, y, x + w, y + 10, 0x30000000 | (color & 0xFFFFFF));
        Theme.text(fontRendererObj, text, x + 4, y + 1, color);
        return x + w;
    }

    /** A point's tag: its word on a tint of its color, all the tags as wide. */
    private void drawTag(Changelog.Tag tag, int x, int y, int width) {
        String text = Lang.format(tag.key());
        roundRect(x, y - 1, x + width, y + 9, 0x38000000 | (tag.color & 0xFFFFFF));
        Theme.centered(fontRendererObj, text, x + width / 2, y, tag.color);
    }

    /** The bottom: the mod's releases on GitHub on the left, Close on the right. */
    private void drawFooter(int left, int right, int top, int mouseX, int mouseY) {
        Theme.fill(left + 1, top, right - 1, top + 1, Theme.BORDER);
        Theme.fill(left + 1, top + 1, right - 1, top + FOOTER_HEIGHT - 1, 0x30000000);
        int y = top + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;

        String github = Lang.format("wayfarmap.changelog.github");
        int githubWidth = fontRendererObj.getStringWidth(github) + 30;
        githubRect = new int[] { left + PAD, y, left + PAD + githubWidth, y + BUTTON_HEIGHT };
        double githubLit = githubLight.update(inside(mouseX, mouseY, githubRect) ? 1 : 0, 22);
        drawButton(githubRect, github, Icons.GITHUB, false, githubLit);

        String close = Lang.format("wayfarmap.help.close");
        int closeWidth = Math.max(90, fontRendererObj.getStringWidth(close) + 30);
        closeRect = new int[] { right - PAD - closeWidth, y, right - PAD, y + BUTTON_HEIGHT };
        double closeLit = closeLight.update(inside(mouseX, mouseY, closeRect) ? 1 : 0, 22);
        drawButton(closeRect, close, null, true, closeLit);
    }

    /** A button: the accent one is filled, the other plain with an icon before the text. */
    private void drawButton(int[] r, String label, String[] icon, boolean primary, double lit) {
        int background = primary ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
            : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
        Theme.fill(r[0], r[1], r[2], r[3], background);
        Theme.outline(r[0], r[1], r[2], r[3], primary ? Theme.ACCENT : Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        if (primary) {
            Theme.fill(r[0] + 1, r[1] + 1, r[2] - 1, r[1] + 2, 0x30FFFFFF);
        }
        int iconWidth = icon == null ? 0 : Icons.width(icon) + 5;
        int x = (r[0] + r[2] - fontRendererObj.getStringWidth(label) - iconWidth) / 2;
        if (icon != null) {
            Icons.draw(
                icon,
                x,
                r[1] + (BUTTON_HEIGHT - icon.length) / 2,
                Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
            x += iconWidth;
        }
        Theme.text(fontRendererObj, label, x, r[1] + (BUTTON_HEIGHT - 8) / 2, Theme.TEXT);
    }

    /** A rectangle with its corners cut by a pixel, which reads as rounded at this size. */
    private static void roundRect(int x0, int y0, int x1, int y1, int color) {
        Theme.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
        Theme.fill(x0, y0 + 1, x1, y1 - 1, color);
        Theme.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
    }

    private static boolean inside(int mouseX, int mouseY, int[] r) {
        return Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
    }

    // ---------------------------------------------------------------- input

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollTo(scrollTarget + (wheel > 0 ? -WHEEL_STEP : WHEEL_STEP));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        if (inside(mouseX, mouseY, closeRect) || inside(mouseX, mouseY, crossRect)) {
            close();
        } else if (inside(mouseX, mouseY, githubRect)) {
            GuiAbout.openLink(GITHUB_RELEASES);
        } else if (inside(mouseX, mouseY, barRect)) {
            // Taken by the thumb: it keeps where it was taken; elsewhere on the track it jumps there first.
            int track = barRect[3] - barRect[1];
            int thumb = Math.max(12, track * listHeight() / Math.max(listHeight(), contentHeight));
            int thumbY = barRect[1] + (int) Math.round((track - thumb) * scroll.get() / Math.max(1, maxScroll()));
            dragOffset = mouseY >= thumbY && mouseY < thumbY + thumb ? mouseY - thumbY : thumb / 2;
            draggingBar = true;
            dragBar(mouseY);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (draggingBar) {
            dragBar(mouseY);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (button == 0) {
            draggingBar = false;
        }
    }

    private void dragBar(int mouseY) {
        int track = barRect[3] - barRect[1];
        int thumb = Math.max(12, track * listHeight() / Math.max(listHeight(), contentHeight));
        double t = (mouseY - dragOffset - barRect[1]) / (double) Math.max(1, track - thumb);
        scrollTo(t * maxScroll());
        scroll.set(scrollTarget);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                close();
                break;
            case Keyboard.KEY_UP:
            case Keyboard.KEY_W:
                scrollTo(scrollTarget - KEY_STEP);
                break;
            case Keyboard.KEY_DOWN:
            case Keyboard.KEY_S:
                scrollTo(scrollTarget + KEY_STEP);
                break;
            case Keyboard.KEY_PRIOR:
                scrollTo(scrollTarget - listHeight() + KEY_STEP);
                break;
            case Keyboard.KEY_NEXT:
            case Keyboard.KEY_SPACE:
                scrollTo(scrollTarget + listHeight() - KEY_STEP);
                break;
            case Keyboard.KEY_HOME:
                scrollTo(0);
                break;
            case Keyboard.KEY_END:
                scrollTo(maxScroll());
                break;
            default:
        }
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
