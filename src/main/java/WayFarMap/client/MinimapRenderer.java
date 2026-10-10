package WayFarMap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.Perf;
import WayFarMap.client.gui.GuiMinimapPosition;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.PowerfailLayer;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.Topography;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Draws the minimap on the HUD, where it was put ({@link Config#minimapX}, {@link Config#minimapY}). */
public class MinimapRenderer {

    /** Room kept between the minimap and the edge of the screen (its frame is 2 pixels outside). */
    public static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    /** Lines of text under the minimap the last time it was drawn. */
    private static int shownLines = 2;
    /** How fast the zoom eases to a new level (higher is faster), as on the world map. */
    private static final double ZOOM_SPEED = 12;
    /** How long the new zoom is shown on the minimap after it changes, and how long it takes to fade (ms). */
    private static final long ZOOM_LABEL_MS = 1500, ZOOM_LABEL_FADE_MS = 300;
    /** Scale the minimap is drawn at while it eases to its zoom level; 0 before the first frame. */
    private static double shownScale;
    private static long lastZoomFrame;
    /** Zoom level the label was last shown for, and when it changed. */
    private static int labelZoom = -1;
    private static long zoomChangedAt;
    /** The minimap was already drawn this frame, under the player list. */
    private boolean drawnUnderPlayerList;

    /** Height of the minimap with the lines of text under it. */
    public static int boxHeight() {
        // The text starts its gap under the map; the last line's height is its font's, not a whole line's.
        return Config.minimapSize + (shownLines > 0 ? Config.minimapTextGap + shownLines * lineHeight() - 3 : 0);
    }

    /** Height of a line of text under the minimap, at its size. */
    private static int lineHeight() {
        return Math.max(1, (int) Math.round(LINE_HEIGHT * Config.minimapTextScale));
    }

    /** Left of the minimap on a screen this wide (GUI pixels of the HUD). */
    public static int left(int screenWidth) {
        return MARGIN + (int) Math.round(Config.minimapX * Math.max(0, screenWidth - 2 * MARGIN - Config.minimapSize));
    }

    /** Top of the minimap on a screen this high. */
    public static int top(int screenHeight) {
        return MARGIN + (int) Math.round(Config.minimapY * Math.max(0, screenHeight - 2 * MARGIN - boxHeight()));
    }

    /**
     * While the player list (Tab) is open, the minimap is drawn right before it so the list covers it; this frame's
     * end of the HUD then skips it. If the list is hidden by another mod (its event canceled), this never runs.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerList(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.PLAYER_LIST || !Config.minimapEnabled) {
            return;
        }
        // The HUD is in another state here than at its end (blend on, alpha test off): the minimap gets the state it
        // has there, and the list gets its own back.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        long perf = Perf.start();
        try {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            drawMinimap(event.resolution, event.partialTicks);
        } finally {
            Perf.end(Perf.Part.MINIMAP, perf);
            GL11.glPopAttrib();
        }
        drawnUnderPlayerList = true;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (drawnUnderPlayerList) {
            drawnUnderPlayerList = false;
            return;
        }
        if (!Config.minimapEnabled) {
            return;
        }
        long perf = Perf.start();
        try {
            drawMinimap(event.resolution, event.partialTicks);
        } finally {
            Perf.end(Perf.Part.MINIMAP, perf);
        }
    }

    private void drawMinimap(ScaledResolution resolution, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GuiMinimapPosition) {
            // Being dragged: moved to the mouse right before it is drawn, so it keeps up with the cursor.
            ((GuiMinimapPosition) mc.currentScreen).updateDrag();
        }
        MapDimension dimension = shownDimension();
        if (mc.thePlayer == null || mc.theWorld == null
            || dimension == null
            || mc.gameSettings.showDebugInfo
            || mc.currentScreen instanceof GuiWorldMap) {
            return;
        }

        List<String> lines = lines(mc);
        shownLines = lines.size();
        int x = left(resolution.getScaledWidth());
        int y = top(resolution.getScaledHeight());
        int factor = resolution.getScaleFactor();
        draw(mc, dimension, lines, x, y, partialTicks, factor, x * factor, y * factor, false);
    }

    /**
     * The minimap exactly as the HUD draws it, for the settings' preview: at its size on the screen (in screen
     * pixels, whatever the screen's own scale), shrunk only when it doesn't fit in the box ({@code x}, {@code y},
     * {@code width}, {@code height}) of the screen being drawn, and centered in it. Only the map is shrunk: the text
     * under it keeps its size on the screen, as a bigger minimap leaves it on the HUD, unless it is too wide for the
     * box itself.
     *
     * @return how much the map was shrunk (1 at its real size), or 0 when there is no world to draw it from
     */
    public static double drawPreview(int x, int y, int width, int height, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        MapDimension dimension = shownDimension();
        if (mc.thePlayer == null || mc.theWorld == null || dimension == null) {
            return 0;
        }
        List<String> lines = lines(mc);
        shownLines = lines.size();
        int size = Config.minimapSize;
        int frame = Config.minimapFrame ? Config.minimapFrameWidth : 0;
        double textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, mc.fontRenderer.getStringWidth(line) * Config.minimapTextScale);
        }
        int screenFactor = ScaledScreen.currentFactor();
        int hudFactor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        double real = hudFactor / (double) screenFactor;
        // The text at its size on the screen; smaller only when it is wider than the box, or would leave the map
        // less than half of its height.
        double textHeight = lines.isEmpty() ? 0 : Config.minimapTextGap + lines.size() * lineHeight() - 3;
        double textUnit = real;
        if (textWidth > 0) {
            textUnit = Math.min(textUnit, width / textWidth);
        }
        if (textHeight > 0) {
            textUnit = Math.min(textUnit, height / 2.0 / textHeight);
        }
        double mapBox = size + 2 * frame;
        double shrink = Math.min(real, Math.min(width / mapBox, (height - textHeight * textUnit) / mapBox));
        if (shrink <= 0) {
            return 0;
        }
        // Where the map's top left lands, on whole screen pixels so the map is as sharp as on the HUD.
        double left = x + (width - size * shrink) / 2;
        double top = y + (height - mapBox * shrink - textHeight * textUnit) / 2 + frame * shrink;
        left = Math.round(left * screenFactor) / (double) screenFactor;
        top = Math.round(top * screenFactor) / (double) screenFactor;
        GL11.glPushMatrix();
        GL11.glTranslated(left, top, 0);
        GL11.glScaled(shrink, shrink, 1);
        try {
            draw(
                mc,
                dimension,
                Collections.emptyList(),
                0,
                0,
                partialTicks,
                shrink * screenFactor,
                left * screenFactor,
                (top + ScaledScreen.currentOffset()) * screenFactor,
                true);
        } finally {
            GL11.glPopMatrix();
        }
        drawLines(
            mc.fontRenderer,
            lines,
            left + size * shrink / 2,
            top + size * shrink + Config.minimapTextGap * textUnit,
            textUnit);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return Math.min(1, shrink / real);
    }

    /** The map the minimap shows: the world map's, without plants when that one is. */
    private static MapDimension shownDimension() {
        MapDimension dimension = MapManager.INSTANCE.getDimension(true);
        if (dimension != null && (!Config.showPlants(true) || Topography.isShown(true))
            && dimension.plantless() != null) {
            // The world map shows the surface without grass and flowers: the minimap too. The topography is drawn
            // from the ground that map keeps.
            dimension = dimension.plantless();
        }
        return dimension;
    }

    /** The lines of text under the minimap. */
    private static List<String> lines(Minecraft mc) {
        EntityClientPlayerMP player = mc.thePlayer;
        List<String> lines = new ArrayList<>();
        int blockX = MathHelper.floor_double(player.posX);
        int blockZ = MathHelper.floor_double(player.posZ);
        if (Config.minimapShowCoordinates) {
            lines.add(blockX + ", " + MathHelper.floor_double(player.boundingBox.minY) + ", " + blockZ);
        }
        int caveLayer = MapManager.INSTANCE.getActiveCaveLayer();
        if (caveLayer >= 0 && Config.displayMode(true) == Config.DISPLAY_BLOCKS) {
            lines.add(Lang.format("wayfarmap.gui.cave_layer", caveLayer * 16, caveLayer * 16 + 15));
        }
        if (Config.minimapShowBiome) {
            lines.add(mc.theWorld.getBiomeGenForCoords(blockX, blockZ).biomeName);
        }
        return lines;
    }

    /**
     * The minimap with its top left at ({@code x}, {@code y}), and the text under it. {@code pixelsPerUnit} is how
     * many screen pixels a unit of the current drawing is, and ({@code screenLeft}, {@code screenTop}) the screen
     * pixel (x, y) ends up at, from the window's top left.
     */
    private static void draw(Minecraft mc, MapDimension dimension, List<String> lines, int x, int y, float partialTicks,
        double pixelsPerUnit, double screenLeft, double screenTop, boolean preview) {
        EntityClientPlayerMP player = mc.thePlayer;
        double px = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double pz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        int size = Config.minimapSize;
        int zoom = Math.max(0, Math.min(Config.MINIMAP_ZOOMS.length - 1, Config.minimapZoom));
        double scale = easedScale(Config.MINIMAP_ZOOMS[zoom]);
        if (zoom != labelZoom) {
            // Not on the first frame: the minimap only just appeared, nothing was changed.
            zoomChangedAt = labelZoom < 0 ? 0 : System.currentTimeMillis();
            labelZoom = zoom;
        }
        boolean round = Config.minimapShape == Config.SHAPE_ROUND;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        // Turning with the player: the view direction (yaw + 90 degrees on the map) ends up pointing up.
        float rotation = Config.minimapRotate ? MathHelper.wrapAngleTo180_float(-180f - yaw) : 0f;
        double half = size / 2.0;
        double centerX = x + half, centerY = y + half;

        GL11.glPushMatrix();
        // The frame: a line of its color right around the map, as see-through and thick as it is set.
        float opacity = Config.minimapFrameOpacity / 100f;
        int frameColor = Math.round(255 * opacity) << 24 | Config.minimapFrameColor;
        int frameWidth = Config.minimapFrameWidth;
        if (round) {
            if (Config.minimapFrame) {
                fillRing(centerX, centerY, half, half + frameWidth, frameColor);
            }
            fillCircle(centerX, centerY, half, 0xFF0C0E11);
        } else {
            if (Config.minimapFrame) {
                frameRect(
                    x - frameWidth,
                    y - frameWidth,
                    x + size + frameWidth,
                    y + size + frameWidth,
                    frameWidth,
                    frameColor);
            }
            Gui.drawRect(x, y, x + size, y + size, 0xFF0C0E11);
        }

        // The map is drawn into an offscreen buffer the size of the minimap, which cuts it exactly to the square
        // (a turned map sticks out otherwise), and the buffer is then put on screen as a square or a circle.
        if (OpenGlHelper.isFramebufferEnabled()) {
            int pixels = Math.max(1, (int) Math.ceil(size * pixelsPerUnit - 1e-6));
            // The preview has its own, so neither is made again at the other's size every frame.
            Framebuffer buffer = preview ? previewBuffer : hudBuffer;
            if (buffer == null) {
                buffer = new Framebuffer(pixels, pixels, false);
                buffer.setFramebufferColor(0f, 0f, 0f, 0f);
                if (preview) {
                    previewBuffer = buffer;
                } else {
                    hudBuffer = buffer;
                }
            } else if (buffer.framebufferWidth != pixels || buffer.framebufferHeight != pixels) {
                buffer.createBindFramebuffer(pixels, pixels);
            }
            buffer.framebufferClear();
            buffer.bindFramebuffer(true);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, size, size, 0, 1000, 3000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0f, 0f, -2000f);
            try {
                drawLayers(mc, dimension, px, pz, scale, size, rotation, partialTicks);
            } finally {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                mc.getFramebuffer()
                    .bindFramebuffer(true);
            }
            drawBuffer(buffer, x, y, size, round);
        } else {
            // Offscreen buffers are off in the video settings: cut to the square only.
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0f);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            int pixels = (int) Math.round(size * pixelsPerUnit);
            GL11.glScissor(
                (int) Math.round(screenLeft),
                mc.displayHeight - (int) Math.round(screenTop) - pixels,
                pixels,
                pixels);
            try {
                drawLayers(mc, dimension, px, pz, scale, size, rotation, partialTicks);
            } finally {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glPopMatrix();
            }
        }

        if (Config.waypointsOnMinimap) {
            drawWaypoints(mc, px, pz, scale, x, y, size, round, rotation);
        }
        MapDrawer.drawPlayerArrow(centerX, centerY, yaw + rotation, 3.5f);
        if (Config.minimapCompass) {
            drawCompass(mc.fontRenderer, centerX, centerY, half, round, rotation);
        }

        drawZoomLabel(mc.fontRenderer, x, y, size, Config.MINIMAP_ZOOMS[zoom]);

        drawLines(mc.fontRenderer, lines, x + size / 2.0, y + size + Config.minimapTextGap, 1);

        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopMatrix();
    }

    /**
     * The lines of text under the minimap, centered on {@code centerX} from {@code top} down, at their size times
     * {@code unit}.
     */
    private static void drawLines(FontRenderer font, List<String> lines, double centerX, double top, double unit) {
        double textScale = Config.minimapTextScale * unit;
        double textY = top;
        for (String line : lines) {
            GL11.glPushMatrix();
            GL11.glTranslated(centerX - font.getStringWidth(line) * textScale / 2, textY, 0);
            GL11.glScaled(textScale, textScale, 1);
            font.drawStringWithShadow(line, 0, 0, 0xFFFFFF);
            GL11.glPopMatrix();
            textY += lineHeight() * unit;
        }
    }

    /** Moves the drawn scale toward {@code target}, in log space so every zoom step feels equally fast. */
    private static double easedScale(double target) {
        long now = System.nanoTime();
        double seconds = lastZoomFrame == 0 ? 0 : Math.min(0.1, (now - lastZoomFrame) / 1.0e9);
        lastZoomFrame = now;
        if (shownScale <= 0) {
            shownScale = target;
        }
        double t = 1.0 - Math.exp(-ZOOM_SPEED * seconds);
        shownScale = Math.exp(Math.log(shownScale) + (Math.log(target) - Math.log(shownScale)) * t);
        if (Math.abs(shownScale - target) < target * 0.002) {
            shownScale = target;
        }
        return shownScale;
    }

    /** For a moment after the zoom changes: the new zoom ("1:4", "2:1") at the bottom of the minimap, fading out. */
    private static void drawZoomLabel(FontRenderer font, int x, int y, int size, double scale) {
        long shown = System.currentTimeMillis() - zoomChangedAt;
        if (shown >= ZOOM_LABEL_MS) {
            return;
        }
        double fade = Math.min(1, (ZOOM_LABEL_MS - shown) / (double) ZOOM_LABEL_FADE_MS);
        String text = scale >= 1 ? Math.round(scale) + ":1" : "1:" + Math.round(1 / scale);
        int textWidth = font.getStringWidth(text);
        int left = x + (size - textWidth) / 2 - 4, top = y + size - 16;
        int background = (int) Math.round(fade * 0xC0) << 24 | 0x101418;
        Gui.drawRect(left, top, left + textWidth + 8, top + 12, background);
        int alpha = (int) Math.round(fade * 0xFF);
        // Text below 4 alpha would be drawn opaque by the font renderer.
        if (alpha >= 4) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            font.drawStringWithShadow(text, left + 4, top + 2, alpha << 24 | 0xFFFFFF);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Turns the offset (dx, dz) by the map's rotation in degrees, like glRotatef does on screen. */
    private static double[] rotate(double dx, double dz, float degrees) {
        if (degrees == 0f) {
            return new double[] { dx, dz };
        }
        double r = Math.toRadians(degrees);
        double cos = Math.cos(r), sin = Math.sin(r);
        return new double[] { dx * cos - dz * sin, dx * sin + dz * cos };
    }

    /** Offscreen buffers the minimap is drawn into, on the HUD and in the settings' preview. */
    private static Framebuffer hudBuffer, previewBuffer;

    /** The map and everything on it, into the square (0, 0, size, size), turned by {@code rotation} degrees. */
    private static void drawLayers(Minecraft mc, MapDimension dimension, double px, double pz, double scale, int size,
        float rotation, float partialTicks) {
        Gui.drawRect(0, 0, size, size, 0xFF0C0E11);
        // A turned map needs a bigger square under it to fill the corners.
        int inner = Config.minimapRotate ? (int) Math.ceil(size * Math.sqrt(2)) + 2 : size;
        GL11.glPushMatrix();
        GL11.glTranslated(size / 2.0, size / 2.0, 0);
        GL11.glRotatef(rotation, 0f, 0f, 1f);
        GL11.glTranslated(-inner / 2.0, -inner / 2.0, 0);
        MapDrawer.iconRotation = rotation;
        MapDrawer.minimapPass = true;
        try {
            MapDrawer.drawMap(dimension, px, pz, scale, 0, 0, inner, inner, true);
            // Drawn with the view off too: it fades out.
            Topography.draw(dimension, px, pz, scale, 0, 0, inner, inner, true);
            if (Config.chunkGrid(true)) {
                MapDrawer.drawChunkGrid(px, pz, scale, 0, 0, inner, inner);
            }
            if (Config.claims(true) && Mods.isClaimsAvailable()) {
                // Claims as on the world map, when they are shown there.
                Mods.draw(
                    Mods.Addon.CLAIMS,
                    () -> ClaimsLayer
                        .draw(mc.theWorld.provider.dimensionId, px, pz, scale, 0, 0, inner, inner, null, 0));
            }
            if (Mods.isVisualProspectingLoaded()) {
                int dimensionId = mc.theWorld.provider.dimensionId;
                if (Config.undergroundFluids(true)) {
                    Mods.draw(
                        Mods.Addon.VISUAL_PROSPECTING,
                        () -> ProspectingLayer.drawFluids(dimensionId, px, pz, scale, 0, 0, inner, inner, true));
                }
                if (Config.oreVeins(true) && Mods.isVisualProspectingLoaded()) {
                    Mods.draw(
                        Mods.Addon.VISUAL_PROSPECTING,
                        () -> ProspectingLayer
                            .drawOreVeins(dimensionId, px, pz, scale, 0, 0, inner, inner, true, 0, 0));
                }
            }
            if (Config.thaumcraftNodes(true) && Mods.isThaumcraftNodesAvailable()) {
                Mods.draw(
                    Mods.Addon.THAUMCRAFT_NODES,
                    () -> ThaumcraftNodes
                        .draw(mc.theWorld.provider.dimensionId, px, pz, scale, 0, 0, inner, inner, true, 0, 0));
            }
            if (Config.powerfails(true) && Mods.isPowerfailsAvailable()) {
                Mods.draw(
                    Mods.Addon.POWERFAILS,
                    () -> PowerfailLayer
                        .draw(mc.theWorld.provider.dimensionId, px, pz, scale, 0, 0, inner, inner, true, 0, 0));
            }
            PlayerTrail.draw(mc.theWorld.provider.dimensionId, px, pz, scale, 0, 0, inner, inner, px, pz);
            MapDrawer.drawEntities(mc, px, pz, scale, 0, 0, inner, inner, partialTicks, 6f, false);
            MapDrawer.drawTeammates(
                mc,
                mc.theWorld.provider.dimensionId,
                px,
                pz,
                scale,
                0,
                0,
                inner,
                inner,
                partialTicks,
                6f,
                false);
        } finally {
            MapDrawer.iconRotation = 0f;
            MapDrawer.minimapPass = false;
            GL11.glPopMatrix();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }

    /** Puts the offscreen buffer on screen as a square or a circle (its image is upside down, v = 0 at the bottom). */
    private static void drawBuffer(Framebuffer buffer, int x, int y, int size, boolean round) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // The buffer's alpha is meaningless after blending into it; the map inside is opaque anyway.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        buffer.bindFramebufferTexture();
        Tessellator tessellator = Tessellator.instance;
        if (round) {
            double half = size / 2.0;
            tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
            tessellator.addVertexWithUV(x + half, y + half, 0, 0.5, 0.5);
            for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
                double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
                double cos = Math.cos(a), sin = Math.sin(a);
                tessellator
                    .addVertexWithUV(x + half + cos * half, y + half + sin * half, 0, 0.5 + cos * 0.5, 0.5 - sin * 0.5);
            }
            tessellator.draw();
        } else {
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(x, y + size, 0, 0, 0);
            tessellator.addVertexWithUV(x + size, y + size, 0, 1, 0);
            tessellator.addVertexWithUV(x + size, y, 0, 1, 1);
            tessellator.addVertexWithUV(x, y, 0, 0, 1);
            tessellator.draw();
        }
        buffer.unbindFramebufferTexture();
        GL11.glEnable(GL11.GL_BLEND);
    }

    private static final String[] COMPASS_LETTERS = { "N", "E", "S", "W" };
    private static final double[][] COMPASS_DIRECTIONS = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };

    /** N, E, S and W on the edge of the minimap, in white, turning with it. */
    private static void drawCompass(FontRenderer font, double cx, double cy, double half, boolean round,
        float rotation) {
        double scale = Config.minimapCompassScale;
        // Bigger letters sit further in from the edge.
        double edge = half - 5 * scale;
        for (int i = 0; i < COMPASS_LETTERS.length; i++) {
            double[] direction = rotate(COMPASS_DIRECTIONS[i][0], COMPASS_DIRECTIONS[i][1], rotation);
            // On a square the letter slides along the border, on a circle along the rim.
            double reach = round ? edge : edge / Math.max(Math.abs(direction[0]), Math.abs(direction[1]));
            String letter = COMPASS_LETTERS[i];
            // Placed at sub-pixel positions: rounding to GUI pixels made the letters jump while turning.
            GL11.glPushMatrix();
            GL11.glTranslated(
                cx + direction[0] * reach - (font.getStringWidth(letter) / 2.0 - 1) * scale,
                cy + direction[1] * reach - 3 * scale,
                0);
            GL11.glScaled(scale, scale, 1);
            font.drawStringWithShadow(letter, 0, 0, 0xFFFFFF);
            GL11.glPopMatrix();
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static final int CIRCLE_SEGMENTS = 64;

    /** A band {@code thickness} wide inside the rectangle's edges, in four pieces that don't overlap. */
    private static void frameRect(int x0, int y0, int x1, int y1, int thickness, int color) {
        Gui.drawRect(x0, y0, x1, y0 + thickness, color);
        Gui.drawRect(x0, y1 - thickness, x1, y1, color);
        Gui.drawRect(x0, y0 + thickness, x0 + thickness, y1 - thickness, color);
        Gui.drawRect(x1 - thickness, y0 + thickness, x1, y1 - thickness, color);
    }

    /**
     * A ring between two radii. Wound the same way as {@link #fillCircle}: the HUD culls back faces, and a ring wound
     * the other way wasn't drawn at all.
     */
    private static void fillRing(double cx, double cy, double inner, double outer, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            double cos = Math.cos(a), sin = Math.sin(a);
            tessellator.addVertex(cx + cos * inner, cy + sin * inner, 0);
            tessellator.addVertex(cx + cos * outer, cy + sin * outer, 0);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void fillCircle(double cx, double cy, double radius, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_FAN);
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(cx, cy, 0);
        for (int i = CIRCLE_SEGMENTS; i >= 0; i--) {
            double a = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            tessellator.addVertex(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, 0);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Waypoints outside the minimap stick to its border, so their direction stays visible. */
    private static void drawWaypoints(Minecraft mc, double px, double pz, double scale, int x, int y, int size,
        boolean round, float rotation) {
        double half = size / 2.0;
        float markerSize = Config.minimapWaypointSize;
        // Markers at the edge stay whole inside it, with their outline.
        double limit = half - markerSize / 2 - 1;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getVisibleWaypoints(mc.theWorld.provider.dimensionId)) {
            double[] offset = rotate((waypoint.x + 0.5 - px) * scale, (waypoint.z + 0.5 - pz) * scale, rotation);
            double dx = offset[0], dz = offset[1];
            double outside = round ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
            if (outside > limit) {
                dx *= limit / outside;
                dz *= limit / outside;
            }
            WaypointRenderer.drawMapMarker(waypoint, x + half + dx, y + half + dz, markerSize, false);
        }
    }
}
