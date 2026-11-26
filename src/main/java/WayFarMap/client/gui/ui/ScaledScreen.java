package WayFarMap.client.gui.ui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;

/**
 * Base of the mod's screens: they are laid out at their own scale instead of Minecraft's GUI scale, so they look the
 * same whatever that is set to. "Auto" picks the largest whole scale that still leaves about 800x450 GUI pixels, so
 * the world map's header fits and nothing ends up tiny. Subclasses draw in {@link #drawScaled}.
 */
public abstract class ScaledScreen extends GuiScreen {

    private static final int MIN_WIDTH = 800, MIN_HEIGHT = 450;
    /** How long a screen takes to slide into place when it opens, and how far it comes from (GUI pixels). */
    private static final long OPEN_MS = 180;
    private static final int OPEN_DISTANCE = 8;
    /**
     * Not drawn for this long: the screen is being opened again (e.g. after playing a while), even if no other of
     * the mod's screens was drawn meanwhile.
     */
    private static final long REOPEN_MS = 5000;
    /**
     * Frames of the mod's screens drawn so far. A screen not drawn in the frame before (on top or behind another)
     * is being opened or came back: counted in frames, not time, so a slow frame (a picture loaded, the game
     * stuttering) doesn't make it slide in again as if it had just been opened.
     */
    private static long frame;

    /** Scale the screen being drawn uses (screen pixels per GUI pixel), 0 when none of ours is drawing. */
    private static int activeFactor;
    /** How far down the screen being drawn is moved while it slides in (GUI pixels). */
    private static int activeOffset;

    /** A screen behind this one is being drawn: it gets no mouse, so nothing on it lights up. */
    private static boolean drawingBehind;
    /** Screens drawn behind each other at most: a guard against a screen ending up behind itself. */
    private static final int MAX_BEHIND = 4;
    private static int behindDepth;

    private int appliedFactor;
    /** The mod's language the screen was laid out in: its texts are set then. */
    private int appliedLanguage;
    /** When the screen was opened, and when it was last drawn (milliseconds). */
    private long openedAt, lastDrawn;
    /** The last of {@link #frame} it was drawn in. */
    private long lastFrame = -2;
    /**
     * The mod's screen that was open when this one was made (e.g. the world map under its settings): it stays
     * drawn under this one, dimmed by it, instead of the game. Null when this one was opened from the game.
     */
    private final ScaledScreen behind;

    /** The screen a new one was last opened over, and when: it closes only to stay drawn under it. */
    private static java.lang.ref.WeakReference<ScaledScreen> lastCovered = new java.lang.ref.WeakReference<>(null);
    private static long lastCoveredAt;

    protected ScaledScreen() {
        GuiScreen open = Minecraft.getMinecraft().currentScreen;
        boolean over = open instanceof ScaledScreen && open != this;
        // Kept only by a screen that draws it: one filling the window (the world map) kept the whole chain of screens
        // opened before it, map over list over map, for as long as it was open.
        behind = over && showsScreenBehind() ? (ScaledScreen) open : null;
        if (over) {
            lastCovered = new java.lang.ref.WeakReference<>((ScaledScreen) open);
            lastCoveredAt = System.currentTimeMillis();
        }
    }

    /**
     * Whether the screen is being closed only because another of the mod's screens is opening over it, which keeps
     * it drawn behind (for {@link GuiScreen#onGuiClosed}: it should not let go of what it shows).
     */
    public static boolean isBeingCovered(ScaledScreen screen) {
        return lastCovered.get() == screen && System.currentTimeMillis() - lastCoveredAt < 2000;
    }

    /** Whether a screen of the type is drawn behind this one (directly or further back). */
    public boolean showsBehind(Class<?> type) {
        for (ScaledScreen screen = behind; screen != null; screen = screen.behind) {
            if (type.isInstance(screen)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the screen is drawn now only as the background of another one, and gets no input. */
    protected static boolean isDrawnBehind() {
        return drawingBehind;
    }

    /** Screen pixels per GUI pixel of the mod's screens. */
    public static int factor(Minecraft mc) {
        if (Config.uiScale > 0) {
            return Config.uiScale;
        }
        int fit = Math.min(mc.displayWidth / MIN_WIDTH, mc.displayHeight / MIN_HEIGHT);
        return Math.max(1, fit);
    }

    /**
     * Screen pixels per GUI pixel of what is being drawn right now: the mod's screen scale inside its screens,
     * Minecraft's GUI scale elsewhere (the HUD).
     */
    public static int currentFactor() {
        if (activeFactor > 0) {
            return activeFactor;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /** How far down the screen being drawn is moved while it slides in, for drawing that uses screen pixels. */
    public static int currentOffset() {
        return activeOffset;
    }

    /** Whether the screen slides in when it opens; not for screens covering the whole window. */
    protected boolean slidesIn() {
        return true;
    }

    /** Whether the screen it was opened over is drawn behind it; not for screens covering the whole window. */
    protected boolean showsScreenBehind() {
        return true;
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        appliedFactor = factor(mc);
        appliedLanguage = Config.modLanguage;
        super.setWorldAndResolution(
            mc,
            (mc.displayWidth + appliedFactor - 1) / appliedFactor,
            (mc.displayHeight + appliedFactor - 1) / appliedFactor);
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Theme.sync();
        int expectedWidth = (mc.displayWidth + appliedFactor - 1) / Math.max(1, appliedFactor);
        if (factor(mc) != appliedFactor || width != expectedWidth || Config.modLanguage != appliedLanguage) {
            // The scale option changed, the window or the mod's language: lay the screen out again.
            setWorldAndResolution(mc, 0, 0);
        }
        if (!drawingBehind) {
            // A new frame: the screens behind are drawn in it from here.
            frame++;
        }
        if (behind != null && showsScreenBehind() && behind.mc != null && behindDepth < MAX_BEHIND) {
            // The screen this one was opened over, as it is now, under this one's dimmed background.
            boolean was = drawingBehind;
            drawingBehind = true;
            behindDepth++;
            try {
                behind.drawScreen(mouseX, mouseY, partialTicks);
            } finally {
                drawingBehind = was;
                behindDepth--;
            }
        }
        int minecraftFactor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        float scale = (float) appliedFactor / minecraftFactor;
        // Minecraft passes the mouse in its own GUI pixels; ours are different. A screen drawn behind another one
        // gets it far away.
        int x = drawingBehind ? -10000 : Mouse.getX() * width / mc.displayWidth;
        int y = drawingBehind ? -10000 : height - Mouse.getY() * height / mc.displayHeight - 1;
        long now = System.currentTimeMillis();
        if (lastFrame < frame - 1 || now - lastDrawn > REOPEN_MS) {
            // Opened, or back from another screen.
            openedAt = now;
        }
        lastDrawn = now;
        lastFrame = frame;
        int offset = 0;
        if (slidesIn()) {
            // Eases out: fast at first, settling into place.
            double t = Math.min(1, (now - openedAt) / (double) OPEN_MS);
            offset = (int) Math.round(Math.pow(1 - t, 3) * OPEN_DISTANCE);
        }
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1f);
        if (offset > 0) {
            // The strip the screen leaves at the top while lower down is dimmed like the rest.
            Theme.dimScreen(0, 0, width, offset);
            GL11.glTranslatef(0f, offset, 0f);
        }
        activeFactor = appliedFactor;
        activeOffset = offset;
        try {
            drawScaled(x, y - offset, partialTicks);
        } finally {
            activeFactor = 0;
            activeOffset = 0;
            GL11.glPopMatrix();
        }
    }

    /** Draws the screen in its own GUI pixels; the default draws the buttons. */
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * Minecraft's tooltip, in the theme's picture of a tooltip when it has one (Minecraft's own purple box
     * otherwise), with the theme's tooltip text color for lines without a color of their own.
     */
    @Override
    @SuppressWarnings("rawtypes")
    protected void drawHoveringText(List lines, int mouseX, int mouseY, FontRenderer font) {
        if (lines == null || lines.isEmpty() || Skin.get("tooltip") == null) {
            super.drawHoveringText(lines, mouseX, mouseY, font);
            return;
        }
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        int textWidth = 0;
        for (Object line : lines) {
            textWidth = Math.max(textWidth, font.getStringWidth(String.valueOf(line)));
        }
        int x = mouseX + 12, y = mouseY - 12;
        int textHeight = 8 + (lines.size() > 1 ? 2 + (lines.size() - 1) * 10 : 0);
        if (x + textWidth > width) {
            x -= 28 + textWidth;
        }
        if (y + textHeight + 6 > height) {
            y = height - textHeight - 6;
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 300);
        Theme.sprite(x - 4, y - 4, x + textWidth + 4, y + textHeight + 4, "tooltip");
        for (int i = 0; i < lines.size(); i++) {
            font.drawStringWithShadow(String.valueOf(lines.get(i)), x, y, Theme.TOOLTIP_TEXT);
            y += i == 0 ? 12 : 10;
        }
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
    }
}
