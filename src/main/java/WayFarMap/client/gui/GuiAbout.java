package WayFarMap.client.gui;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import WayFarMap.Tags;
import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;

/**
 * About the mod: its glowing logo over a starry top, the name and the version (a click copies it), what the mod is,
 * who made it (the author and the developer, a click opens their GitHub), who tested it, and the author's pages. Its
 * parts slide in one after
 * another when it opens; What's new at the bottom opens the changelog, Esc or Close closes it.
 */
public class GuiAbout extends ScaledScreen {

    /** Who made and who tested the mod, and the author's pages; the welcome window shows them too. */
    static final String AUTHOR = "EvgenWarGold";
    static final String[] TESTERS = { "Faotik", "Octo" };
    static final String GITHUB_URL = "https://github.com/evgengoldwar", BOOSTY_URL = "https://boosty.to/evgenwargold",
        TELEGRAM_URL = "https://t.me/Shaterplay4";
    /** Those who made the mod, a card each: {name, lang key of what they did, their GitHub}. */
    static final String[][] MAKERS = { { AUTHOR, "wayfarmap.about.author_role", GITHUB_URL },
        { "Navatusein", "wayfarmap.about.developer_role", "https://github.com/Navatusein" } };
    /** The mod's own page, for the GitHub link at the bottom. */
    static final String MOD_GITHUB_URL = "https://github.com/evgengoldwar/Wayfarer-s-Map";

    private static final int WIDTH = 300, PAD = 14;
    private static final int HERO_HEIGHT = 104, SECTION_TITLE = 14;
    private static final int CARD_HEIGHT = 32, CHIP_HEIGHT = 22, CHIP_GAP = 6, LINK_HEIGHT = 22, BUTTON_HEIGHT = 20;
    /** How long a part takes to slide in, and how much later each part starts than the one before (ms). */
    private static final long SLIDE_MS = 260, STAGGER_MS = 70;
    /** How long "Copied!" stays on the version after a click (ms). */
    private static final long COPIED_MS = 1500;
    /** The colors of the testers' avatars, one after another. */
    static final int[] AVATAR_COLORS = { 0xFF3FB950, 0xFFDB61A2, 0xFFE3B341, 0xFFA371F7 };

    /** The mod's and the author's pages: {name, url, icon, color}. */
    private static final Object[][] LINKS = { { "GitHub", MOD_GITHUB_URL, Icons.GITHUB, 0xFFE6EAF0 },
        { "Boosty", BOOSTY_URL, Icons.BOOSTY, 0xFFF15F2C }, { "Telegram", TELEGRAM_URL, Icons.TELEGRAM, 0xFF2AABEE } };

    /** A white circle with smooth edges, tinted for the avatars' rings and the glows. */
    static final ResourceLocation CIRCLE = new ResourceLocation("wayfarmap", "textures/gui/avatars/circle.png");

    /** The RU GTNH chat's icon. */
    static final ResourceLocation CHAT_ICON = new ResourceLocation("wayfarmap", "textures/gui/avatars/gtnh_chat.png");

    /** The stars twinkling over the top: {x, y} as parts of its size, and the phase of their twinkle. */
    private static final double[][] STARS = new double[22][3];

    static {
        Random random = new Random(7);
        for (double[] star : STARS) {
            star[0] = random.nextDouble();
            star[1] = 0.06 + random.nextDouble() * 0.6;
            star[2] = random.nextDouble() * Math.PI * 2;
        }
    }

    private final GuiScreen parent;
    private final long openedAt = System.currentTimeMillis();
    /** When the version was copied, 0 if it wasn't. */
    private long copiedAt;

    /** Where things were drawn last, for the clicks: {x0, y0, x1, y1}. */
    private int[] versionRect = new int[4], closeRect = new int[4], changelogRect = new int[4];
    private final int[][] makerRects = new int[MAKERS.length][4];
    private final int[][] linkRects = new int[LINKS.length][4];
    /** How lit each thing is by the mouse. */
    private final Smooth versionLight = new Smooth(0), closeLight = new Smooth(0), changelogLight = new Smooth(0);
    private final Smooth[] makerLight = { new Smooth(0), new Smooth(0) };
    private final Smooth[] linkLight = { new Smooth(0), new Smooth(0), new Smooth(0) };
    private final Smooth[] chipLight;

    public GuiAbout(GuiScreen parent) {
        this.parent = parent;
        chipLight = new Smooth[TESTERS.length + 1];
        for (int i = 0; i < chipLight.length; i++) {
            chipLight[i] = new Smooth(0);
        }
    }

    // ---------------------------------------------------------------- layout

    private static int textWidth() {
        return WIDTH - 2 * PAD;
    }

    private List<?> tagline() {
        return fontRendererObj.listFormattedStringToWidth(Lang.format("wayfarmap.welcome.text"), textWidth());
    }

    /** The testers' chips: the testers, then the chat. */
    private String[] chips() {
        String[] chips = new String[TESTERS.length + 1];
        System.arraycopy(TESTERS, 0, chips, 0, TESTERS.length);
        chips[TESTERS.length] = Lang.format("wayfarmap.about.tester_chat");
        return chips;
    }

    private int chipWidth(String name) {
        // An avatar (or the chat's icon), a gap, the name and the padding.
        return 3 + 16 + 6 + fontRendererObj.getStringWidth(name) + 8;
    }

    /** Where each chip goes, wrapping to new rows, relative to the top left of the chips: {x, y, width}. */
    private List<int[]> chipPlaces() {
        List<int[]> places = new ArrayList<>();
        int x = 0, y = 0;
        for (String chip : chips()) {
            int w = Math.min(textWidth(), chipWidth(chip));
            if (x > 0 && x + w > textWidth()) {
                x = 0;
                y += CHIP_HEIGHT + 4;
            }
            places.add(new int[] { x, y, w });
            x += w + CHIP_GAP;
        }
        return places;
    }

    private int chipsHeight() {
        List<int[]> places = chipPlaces();
        return places.get(places.size() - 1)[1] + CHIP_HEIGHT;
    }

    /** Height of the whole window. */
    private int windowHeight() {
        return HERO_HEIGHT + 10
            + tagline().size() * 10
            + 8
            + SECTION_TITLE
            + CARD_HEIGHT
            + 10
            + SECTION_TITLE
            + chipsHeight()
            + 10
            + SECTION_TITLE
            + LINK_HEIGHT
            + 14
            + BUTTON_HEIGHT
            + PAD;
    }

    /** How far a part (0 the first) is still below its place, sliding up into it. */
    private int slide(int part) {
        double t = (System.currentTimeMillis() - openedAt - part * STAGGER_MS) / (double) SLIDE_MS;
        t = Math.max(0, Math.min(1, t));
        return (int) Math.round(Math.pow(1 - t, 3) * 12);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        int windowHeight = windowHeight();
        int left = (width - WIDTH) / 2, top = Math.max(4, (height - windowHeight) / 2);
        int right = left + WIDTH, bottom = top + windowHeight, centerX = left + WIDTH / 2;
        // A soft shadow under the window, so it stands off the map.
        Theme.fill(left - 2, top + 2, right + 2, bottom + 4, 0x40000000);
        Theme.fill(left - 1, top + 1, right + 1, bottom + 2, 0x40000000);
        Theme.panel(left, top, right, bottom);
        // Parts slide in under the panel's edges, so they are cut to it.
        Theme.clip(left + 1, top + 1, right - 1, bottom - 1);
        List<String> tooltip = null;

        // The top: stars, the logo glowing softly, the name and the version.
        int y = top + slide(0);
        drawHero(left, right, y);
        String version = Lang.format("wayfarmap.about.version", Tags.VERSION);
        boolean copied = copiedAt > 0 && System.currentTimeMillis() - copiedAt < COPIED_MS;
        String pillText = copied ? Lang.format("wayfarmap.about.copied")
            : Theme.ellipsize(fontRendererObj, version, WIDTH - 60);
        int pillWidth = fontRendererObj.getStringWidth(pillText) + 12 + (copied ? 0 : 10);
        int pillLeft = centerX - pillWidth / 2;
        versionRect = new int[] { pillLeft, y + 82, pillLeft + pillWidth, y + 95 };
        boolean versionHovered = inside(mouseX, mouseY, versionRect);
        double versionLit = versionLight.update(versionHovered ? 1 : 0, 22);
        int pillColor = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, versionLit);
        Theme.fill(pillLeft, y + 82, pillLeft + pillWidth, y + 95, pillColor);
        Theme.outline(
            pillLeft,
            y + 82,
            pillLeft + pillWidth,
            y + 95,
            copied ? Theme.SUCCESS : Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, versionLit));
        if (copied) {
            Theme.text(fontRendererObj, pillText, pillLeft + 6, y + 85, Theme.SUCCESS);
        } else {
            int textColor = Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, versionLit);
            Theme.text(fontRendererObj, pillText, pillLeft + 6, y + 85, textColor);
            Icons.draw(
                Icons.SMALL_COPY,
                pillLeft + pillWidth - 13,
                y + 85,
                Theme.blend(Theme.TEXT_MUTED, Theme.ACCENT, versionLit));
        }
        if (versionHovered && !copied) {
            tooltip = new ArrayList<>();
            if (!pillText.equals(version)) {
                tooltip.add(version);
            }
            tooltip.add(Lang.format("wayfarmap.about.copy_hint"));
        }

        // What it is, in a line or two.
        y = top + HERO_HEIGHT + 10 + slide(1);
        for (Object line : tagline()) {
            Theme.centered(fontRendererObj, String.valueOf(line), centerX, y, Theme.TEXT);
            y += 10;
        }
        int base = top + HERO_HEIGHT + 10 + tagline().size() * 10 + 8;

        // Who made it: a card each, side by side, with their avatar; a click opens their GitHub.
        y = base + slide(2);
        sectionTitle(Lang.format("wayfarmap.about.author"), left, right, y);
        y += SECTION_TITLE;
        int cardGap = 6, cardWidth = (textWidth() - cardGap * (MAKERS.length - 1)) / MAKERS.length;
        for (int i = 0; i < MAKERS.length; i++) {
            int x0 = left + PAD + i * (cardWidth + cardGap);
            makerRects[i] = new int[] { x0, y, i == MAKERS.length - 1 ? right - PAD : x0 + cardWidth, y + CARD_HEIGHT };
            boolean hovered = inside(mouseX, mouseY, makerRects[i]);
            drawMakerCard(i, makerRects[i], makerLight[i].update(hovered ? 1 : 0, 22));
            if (hovered) {
                tooltip = Collections.singletonList(MAKERS[i][2]);
            }
        }
        base += SECTION_TITLE + CARD_HEIGHT + 10;

        // Who tested it: a chip for each tester and one for the chat.
        y = base + slide(3);
        sectionTitle(Lang.format("wayfarmap.about.testers"), left, right, y);
        y += SECTION_TITLE;
        String[] chips = chips();
        List<int[]> places = chipPlaces();
        for (int i = 0; i < chips.length; i++) {
            int[] place = places.get(i);
            int[] r = { left + PAD + place[0], y + place[1], left + PAD + place[0] + place[2],
                y + place[1] + CHIP_HEIGHT };
            boolean hovered = inside(mouseX, mouseY, r);
            drawChip(r, chips[i], i, chipLight[i].update(hovered ? 1 : 0, 22));
        }
        base += SECTION_TITLE + chipsHeight() + 10;

        // The author's pages.
        y = base + slide(4);
        sectionTitle(Lang.format("wayfarmap.about.links_title"), left, right, y);
        y += SECTION_TITLE;
        int gap = 6, linkWidth = (textWidth() - 2 * gap) / 3;
        for (int i = 0; i < LINKS.length; i++) {
            int x0 = left + PAD + i * (linkWidth + gap);
            linkRects[i] = new int[] { x0, y, i == LINKS.length - 1 ? right - PAD : x0 + linkWidth, y + LINK_HEIGHT };
            boolean hovered = inside(mouseX, mouseY, linkRects[i]);
            drawLink(i, linkRects[i], linkLight[i].update(hovered ? 1 : 0, 22));
            if (hovered) {
                tooltip = Collections.singletonList((String) LINKS[i][1]);
            }
        }
        base += SECTION_TITLE + LINK_HEIGHT + 14;

        // What's new, and Close.
        y = base + slide(5);
        int half = (textWidth() - 6) / 2;
        changelogRect = new int[] { left + PAD, y, left + PAD + half, y + BUTTON_HEIGHT };
        double changelogLit = changelogLight.update(inside(mouseX, mouseY, changelogRect) ? 1 : 0, 22);
        int[] n = changelogRect;
        Theme.fill(n[0], n[1], n[2], n[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, changelogLit));
        Theme.outline(n[0], n[1], n[2], n[3], Theme.blend(Theme.BORDER, Theme.ACCENT, changelogLit));
        String changelog = Lang.format("wayfarmap.about.changelog");
        String[] page = Icons.SMALL_PAGE;
        int contentX = (n[0] + n[2] - Icons.width(page) - 5 - fontRendererObj.getStringWidth(changelog)) / 2;
        Icons.draw(
            page,
            contentX,
            y + (BUTTON_HEIGHT - page.length) / 2,
            Theme.blend(Theme.TEXT_MUTED, Theme.ACCENT, changelogLit));
        Theme.text(
            fontRendererObj,
            changelog,
            contentX + Icons.width(page) + 5,
            y + (BUTTON_HEIGHT - 8) / 2,
            Theme.TEXT);

        closeRect = new int[] { right - PAD - half, y, right - PAD, y + BUTTON_HEIGHT };
        double closeLit = closeLight.update(inside(mouseX, mouseY, closeRect) ? 1 : 0, 22);
        int[] c = closeRect;
        Theme.fill(c[0], c[1], c[2], c[3], Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, closeLit));
        Theme.outline(c[0], c[1], c[2], c[3], Theme.ACCENT);
        String close = Lang.format("wayfarmap.help.close");
        Theme.centered(fontRendererObj, close, (c[0] + c[2]) / 2, y + (BUTTON_HEIGHT - 8) / 2, Theme.TEXT);

        Theme.unclip();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    /** The top of the window: a darker band with twinkling stars, the glowing logo and the name. */
    private void drawHero(int left, int right, int y) {
        int centerX = (left + right) / 2;
        long now = System.currentTimeMillis();
        Theme.fill(left + 1, y + 1, right - 1, y + HERO_HEIGHT, Theme.PANEL_ALT);
        Theme.fill(left + 1, y + 1, right - 1, y + 3, Theme.ACCENT);
        Theme.fill(left + 1, y + HERO_HEIGHT, right - 1, y + HERO_HEIGHT + 1, Theme.BORDER);
        int heroWidth = right - left - 2;
        for (double[] star : STARS) {
            double twinkle = 0.5 + 0.5 * Math.sin(now / 700.0 + star[2]);
            int sx = left + 1 + (int) (star[0] * heroWidth), sy = y + 4 + (int) (star[1] * HERO_HEIGHT);
            if (Math.abs(sx - centerX) < 40 && sy < y + 76) {
                // Not over the logo and the name.
                continue;
            }
            int alpha = (int) (0x18 + 0x70 * twinkle);
            Theme.fill(sx, sy, sx + 1, sy + 1, alpha << 24 | (Theme.TEXT & 0xFFFFFF));
        }
        double pulse = 0.5 + 0.5 * Math.sin(now / 600.0);
        int logoCenterY = y + 32;
        int glow = Theme.ACCENT & 0xFFFFFF;
        drawRound(CIRCLE, centerX, logoCenterY, 32, (int) (0x0C + 0x08 * pulse) << 24 | glow);
        drawRound(CIRCLE, centerX, logoCenterY, 25, (int) (0x10 + 0x0C * pulse) << 24 | glow);
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - Icons.LOGO_SIZE * 3 / 2f, logoCenterY - Icons.LOGO_SIZE * 3 / 2f, 0f);
        GL11.glScalef(3f, 3f, 1f);
        Icons.drawLogo(0, 0);
        GL11.glPopMatrix();
        String title = "Wayfarer's Map";
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX - fontRendererObj.getStringWidth(title), y + 60, 0f);
        GL11.glScalef(2f, 2f, 1f);
        fontRendererObj.drawStringWithShadow(title, 0, 0, Theme.TEXT);
        GL11.glPopMatrix();
    }

    /** A small title in the accent color with a line after it, like the settings' sections. */
    private void sectionTitle(String title, int left, int right, int y) {
        Theme.text(fontRendererObj, title, left + PAD, y, Theme.ACCENT);
        int lineX = left + PAD + fontRendererObj.getStringWidth(title) + 6;
        Theme.fill(lineX, y + 4, right - PAD, y + 5, Theme.BORDER);
    }

    /** A round picture of the person (cut to a circle in the file) in a thin ring of the color. */
    static void drawAvatar(int centerX, int centerY, int radius, String name, int color) {
        drawRound(CIRCLE, centerX, centerY, radius + 1, color);
        String file = "textures/gui/avatars/" + name.toLowerCase(Locale.ROOT) + ".png";
        drawRound(new ResourceLocation("wayfarmap", file), centerX, centerY, radius, 0xFFFFFFFF);
    }

    /**
     * Draws a round picture tinted with the color: a texture with smooth edges, so the circle is round at any scale,
     * not stepped like one drawn of rectangles.
     */
    static void drawRound(ResourceLocation picture, double centerX, double centerY, double radius, int color) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(picture);
        // Smooth, not blocky: the picture is drawn at another size than it is.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(
            (color >> 16 & 0xFF) / 255f,
            (color >> 8 & 0xFF) / 255f,
            (color & 0xFF) / 255f,
            (color >>> 24) / 255f);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(centerX - radius, centerY + radius, 0, 0, 1);
        tessellator.addVertexWithUV(centerX + radius, centerY + radius, 0, 1, 1);
        tessellator.addVertexWithUV(centerX + radius, centerY - radius, 0, 1, 0);
        tessellator.addVertexWithUV(centerX - radius, centerY - radius, 0, 0, 0);
        tessellator.draw();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** One who made the mod: an avatar, the name and what they did, and the GitHub logo; lit under the mouse. */
    private void drawMakerCard(int i, int[] r, double lit) {
        String name = MAKERS[i][0];
        Theme.fill(r[0], r[1], r[2], r[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(r[0], r[1], r[2], r[3], Theme.blend(Theme.BORDER, Theme.ACCENT, lit));
        // The accent along the left edge.
        Theme.fill(r[0] + 1, r[1] + 1, r[0] + 3, r[3] - 1, Theme.ACCENT);
        int avatarX = r[0] + 19, avatarY = (r[1] + r[3]) / 2;
        drawRound(CIRCLE, avatarX, avatarY, 13, (int) (0x30 + 0x40 * lit) << 24 | (Theme.ACCENT & 0xFFFFFF));
        drawAvatar(avatarX, avatarY, 11, name, Theme.ACCENT);
        String[] icon = Icons.GITHUB;
        int room = r[2] - 14 - Icons.width(icon) - (r[0] + 38);
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, name, room), r[0] + 38, r[1] + 6, Theme.TEXT);
        String role = Theme.ellipsize(fontRendererObj, Lang.format(MAKERS[i][1]), room);
        Theme.text(fontRendererObj, role, r[0] + 38, r[1] + 17, Theme.TEXT_MUTED);
        Icons.draw(
            icon,
            r[2] - 10 - Icons.width(icon),
            avatarY - icon.length / 2,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    /** A tester (an avatar and the name) or the chat (its icon and name); lit under the mouse. */
    private void drawChip(int[] r, String name, int i, double lit) {
        boolean chat = i >= TESTERS.length;
        int color = chat ? 0xFFF2C14E : AVATAR_COLORS[i % AVATAR_COLORS.length];
        Theme.fill(r[0], r[1], r[2], r[3], Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        Theme.outline(r[0], r[1], r[2], r[3], Theme.blend(Theme.BORDER, color, lit));
        int iconCenterX = r[0] + 11, centerY = (r[1] + r[3]) / 2;
        if (chat) {
            drawRound(CHAT_ICON, iconCenterX, centerY, 8, 0xFFFFFFFF);
        } else {
            drawAvatar(iconCenterX, centerY, 8, name, color);
        }
        String shown = Theme.ellipsize(fontRendererObj, name, r[2] - r[0] - 33);
        int textColor = Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.5 + lit / 2);
        Theme.text(fontRendererObj, shown, r[0] + 25, r[1] + 7, textColor);
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
        int contentWidth = Icons.width(icon) + 5 + fontRendererObj.getStringWidth(name);
        int x = (r[0] + r[2] - contentWidth) / 2;
        // The content lifts a pixel under the mouse.
        int lift = (int) Math.round(lit);
        Icons.draw(icon, x, r[1] + (LINK_HEIGHT - 1 - icon.length) / 2 - lift, brand);
        Theme.text(
            fontRendererObj,
            name,
            x + Icons.width(icon) + 5,
            r[1] + (LINK_HEIGHT - 9) / 2 - lift,
            Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit));
    }

    private static boolean inside(int mouseX, int mouseY, int[] r) {
        return Theme.inside(mouseX, mouseY, r[0], r[1], r[2], r[3]);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        if (inside(mouseX, mouseY, closeRect)) {
            close();
        } else if (inside(mouseX, mouseY, changelogRect)) {
            mc.displayGuiScreen(new GuiChangelog(this, null));
        } else if (inside(mouseX, mouseY, versionRect)) {
            setClipboardString(Tags.VERSION);
            copiedAt = System.currentTimeMillis();
        } else {
            for (int i = 0; i < MAKERS.length; i++) {
                if (inside(mouseX, mouseY, makerRects[i])) {
                    openLink(MAKERS[i][2]);
                    return;
                }
            }
            for (int i = 0; i < LINKS.length; i++) {
                if (inside(mouseX, mouseY, linkRects[i])) {
                    openLink((String) LINKS[i][1]);
                    return;
                }
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
        }
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    /** Opens the page in the system browser. */
    static void openLink(String url) {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop");
            Object desktop = desktopClass.getMethod("getDesktop")
                .invoke(null);
            desktopClass.getMethod("browse", URI.class)
                .invoke(desktop, new URI(url));
            return;
        } catch (Throwable ignored) {
            // No AWT desktop (common on Linux): LWJGL knows the platform's own way.
        }
        try {
            Sys.openURL(url);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Couldn't open " + url, t);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
