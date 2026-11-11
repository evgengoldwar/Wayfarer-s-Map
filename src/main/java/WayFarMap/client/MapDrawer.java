package WayFarMap.client;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.INpc;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.map.FlatLog;
import WayFarMap.client.map.LodTile;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;

/** Shared drawing code of the minimap and the fullscreen map. */
public final class MapDrawer {

    private MapDrawer() {}

    /**
     * Degrees the drawing is turned by (the minimap turning with the player); entity icons and player heads are
     * turned back so they stay upright.
     */
    public static float iconRotation;

    /** Turns the next drawing around (sx, sy) so it stays upright on a turned map; pair with glPopMatrix. */
    private static void pushUpright(double sx, double sy) {
        GL11.glPushMatrix();
        if (iconRotation != 0f) {
            GL11.glTranslated(sx, sy, 0);
            GL11.glRotatef(-iconRotation, 0f, 0f, 1f);
            GL11.glTranslated(-sx, -sy, 0);
        }
    }

    /** New region textures created per frame; the rest pop in over the next frames instead of one long stall. */
    private static final int NEW_TEXTURES_PER_FRAME = 4;
    private static final int NEW_LOD_TEXTURES_PER_FRAME = 32;

    /**
     * Whether the map is drawn from reduced region copies (one pixel per 4x4 blocks): when a block is at most half a
     * screen pixel wide the full resolution can't be seen anyway, and it would need far more memory. The minimap shows
     * only a few regions: it takes the reduced copies only once a whole pixel of them (4 blocks) fits in a screen
     * pixel, so at 1:2 it keeps the full regions instead of coarse copies rebuilt every second around the player.
     */
    public static boolean useLod(double scale) {
        int factor = ScaledScreen.currentFactor();
        return scale * factor <= (minimapPass ? 0.25 : 0.5);
    }

    /**
     * Draws the map into the screen rectangle ({@code x}, {@code y}, {@code width}, {@code height}).
     *
     * @param centerX world X shown at the center of the rectangle
     * @param centerZ world Z shown at the center of the rectangle
     * @param scale   screen pixels per block
     */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height) {
        drawMap(dimension, centerX, centerZ, scale, x, y, width, height, false);
    }

    /** A switch from one map to another (a cave layer, the surface, the biomes) being faded over. */
    private static final class Fade {

        /** Map drawn last, and the one it fades in over (null when no fade is going on). */
        MapDimension shown, previous;
        /** When the fade started, when the switch was made, and when the map was last drawn. */
        long start, switched, lastFrame;
    }

    private static final Fade MINIMAP_FADE = new Fade(), WORLD_MAP_FADE = new Fade();

    /** Set while the minimap is drawn: its lighting and mobs may be its own ({@link Config#minimapOwnView}). */
    public static boolean minimapPass;

    /** Forgets the maps drawn last, so none of a world that was left is faded from (or drawn) again. */
    public static void forgetShownMaps() {
        MINIMAP_FADE.shown = MINIMAP_FADE.previous = null;
        WORLD_MAP_FADE.shown = WORLD_MAP_FADE.previous = null;
    }

    /** Longest wait for the new map's regions to be read from disk before it fades in anyway. */
    private static final long FADE_WAIT_MS = 1000;

    /**
     * @param minimap drawn by the minimap (kept apart in the flat map log). A switch to another map of the same
     *                dimension (a cave layer, the surface) fades over {@link Config#layerFadeMs}.
     */
    public static void drawMap(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, boolean minimap) {
        Fade fade = minimap ? MINIMAP_FADE : WORLD_MAP_FADE;
        long now = System.currentTimeMillis();
        long duration = Config.layerFadeMs;
        if (dimension != fade.shown) {
            if (dimension == fade.previous && duration > 0) {
                // Switched back in the middle of a fade: it turns around from where it was.
                float done = Math.max(0f, Math.min(1f, (now - fade.start) / (float) duration));
                fade.previous = fade.shown;
                fade.start = now - (long) ((1f - done) * duration);
                fade.switched = now - FADE_WAIT_MS;
            } else {
                // Only within the same dimension, and not when the map was just opened.
                boolean fades = duration > 0 && fade.shown != null
                    && now - fade.lastFrame < 500
                    && fade.shown.dimensionId == dimension.dimensionId;
                fade.previous = fades ? fade.shown : null;
                fade.start = fade.switched = now;
            }
            fade.shown = dimension;
        }
        fade.lastFrame = now;
        float progress = fade.previous == null || duration <= 0 ? 1f : (now - fade.start) / (float) duration;
        if (progress >= 1f) {
            fade.previous = null;
            drawLayer(dimension, centerX, centerZ, scale, x, y, width, height, minimap, 1f, true);
            return;
        }
        // The old map goes away in the second half, the new one comes in over it in the first half: where both are
        // explored one turns into the other with no darker moment between them.
        drawLayer(
            fade.previous,
            centerX,
            centerZ,
            scale,
            x,
            y,
            width,
            height,
            minimap,
            Math.min(1f, 2f * (1f - progress)),
            true);
        int waiting = drawLayer(
            dimension,
            centerX,
            centerZ,
            scale,
            x,
            y,
            width,
            height,
            minimap,
            Math.min(1f, 2f * progress),
            false);
        if (waiting > 0 && now - fade.switched < FADE_WAIT_MS) {
            // The new map is still being read: the old one stays until it is there.
            fade.start = now;
        }
    }

    /**
     * Draws one map at the given opacity, with the unexplored land's pattern under it if asked. Returns how many of its
     * regions in view aren't drawn yet (still being read, or waiting for their texture).
     */
    private static int drawLayer(MapDimension dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, boolean minimap, float alpha, boolean pattern) {
        long frameStart = System.nanoTime();
        int drawnCount = 0, loadingCount = 0, missingCount = 0, textureLimited = 0;
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;

        int rx0 = floor(left) >> MapRegion.SHIFT;
        int rz0 = floor(top) >> MapRegion.SHIFT;
        int rx1 = floor(right) >> MapRegion.SHIFT;
        int rz1 = floor(bottom) >> MapRegion.SHIFT;

        if (pattern && Config.unexploredPattern != Config.UNEXPLORED_NONE) {
            // Under the map: it only shows where nothing was explored (the map is transparent there).
            drawUnexploredPattern(left, top, scale, x, y, width, height);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Dark caves and the Nether are drawn as at night with their own tint: only lava, torches and other lights
        // show them in full color.
        boolean darkCave = isDarkCave(dimension);
        boolean dimNether = isDimNether(dimension);
        // The layer's own light, not the shown cave mode's: the surface faded out on the way into a cave keeps the
        // night it was drawn with.
        float night = darkCave || dimNether ? 1f : nightAmount(Minecraft.getMinecraft(), !dimension.cave);
        float[] tint = darkCave ? CAVE_TINT : dimNether ? NETHER_TINT : tint(night);
        GL11.glColor4f(tint[0], tint[1], tint[2], alpha);

        Tessellator tessellator = Tessellator.instance;
        boolean lod = useLod(scale);
        int newTextures = lod ? NEW_LOD_TEXTURES_PER_FRAME : NEW_TEXTURES_PER_FRAME;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                // Regions on disk are read in the background; they pop in once loaded.
                MapRegion region = null;
                LodTile tile = null;
                if (lod) {
                    tile = dimension.requestLod(rx, rz);
                    if (tile == null) {
                        if (dimension.isKnownMissing(rx, rz)) {
                            missingCount++;
                        } else {
                            loadingCount++;
                        }
                        continue;
                    }
                    if (!tile.hasTexture() && newTextures-- <= 0) {
                        textureLimited++;
                        continue;
                    }
                } else {
                    region = dimension.requestRegion(rx, rz);
                    if (region == null) {
                        if (dimension.isKnownMissing(rx, rz)) {
                            missingCount++;
                        } else {
                            loadingCount++;
                        }
                        continue;
                    }
                    if (!region.hasTexture() && newTextures-- <= 0) {
                        textureLimited++;
                        continue;
                    }
                }
                drawnCount++;

                // Part of the region that is inside the view, in block coordinates.
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }

                if (lod) {
                    tile.bindTexture();
                } else {
                    region.bindTexture();
                }
                double u0 = (bx0 - regionX) / MapRegion.SIZE;
                double v0 = (bz0 - regionZ) / MapRegion.SIZE;
                double u1 = (bx1 - regionX) / MapRegion.SIZE;
                double v1 = (bz1 - regionZ) / MapRegion.SIZE;
                double sx0 = x + (bx0 - left) * scale;
                double sy0 = y + (bz0 - top) * scale;
                double sx1 = x + (bx1 - left) * scale;
                double sy1 = y + (bz1 - top) * scale;

                tessellator.startDrawingQuads();
                tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                tessellator.draw();
                if (night > 0.01f && (lod ? tile.hasLight() : region.hasLight())) {
                    // At night, torches and lamps light up the map around them (zoomed out too).
                    if (lod) {
                        tile.bindGlowTexture();
                    } else {
                        region.bindGlowTexture();
                    }
                    GL11.glColor4f(1f, 1f, 1f, night * alpha);
                    tessellator.startDrawingQuads();
                    tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                    tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                    tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                    tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                    tessellator.draw();
                    GL11.glColor4f(tint[0], tint[1], tint[2], alpha);
                }
                if (!lod && Config.edgeShadow) {
                    // Over the map: a soft shadow along the edge of the explored land.
                    region.bindShadowTexture(missingNeighbors(dimension, rx, rz));
                    GL11.glColor4f(1f, 1f, 1f, alpha);
                    tessellator.startDrawingQuads();
                    tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                    tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                    tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                    tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                    tessellator.draw();
                    GL11.glColor4f(tint[0], tint[1], tint[2], alpha);
                }
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
        if (FlatLog.on()) {
            FlatLog.frame(
                minimap,
                lod,
                (rx1 - rx0 + 1) * (rz1 - rz0 + 1),
                drawnCount,
                loadingCount,
                missingCount,
                textureLimited,
                System.nanoTime() - frameStart);
        }
        return loadingCount + textureLimited;
    }

    /** Which neighbors of the region don't exist (bits 1 west, 2 east, 4 north, 8 south), for its edge shadow. */
    private static int missingNeighbors(MapDimension dimension, int rx, int rz) {
        int missing = 0;
        if (dimension.isKnownMissing(rx - 1, rz)) {
            missing |= 1;
        }
        if (dimension.isKnownMissing(rx + 1, rz)) {
            missing |= 2;
        }
        if (dimension.isKnownMissing(rx, rz - 1)) {
            missing |= 4;
        }
        if (dimension.isKnownMissing(rx, rz + 1)) {
            missing |= 8;
        }
        return missing;
    }

    /** Room between two lines or dots of the unexplored land's pattern, in GUI pixels. */
    private static final int PATTERN_LINE_STEP = 7, PATTERN_DOT_STEP = 8;
    private static final int PATTERN_LINE_COLOR = 0xFF181D24, PATTERN_DOT_COLOR = 0xFF222933;

    /**
     * The pattern of the unexplored land ({@link Config#unexploredPattern}) over the map rectangle: diagonal lines or
     * dots, lined up with the world's origin so they move with the map instead of sliding under it.
     */
    private static void drawUnexploredPattern(double left, double top, double scale, int x, int y, int width,
        int height) {
        // Where the world's origin is on screen: the pattern starts there.
        double originX = x - left * scale, originY = y - top * scale;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        if (Config.unexploredPattern == Config.UNEXPLORED_LINES) {
            // Lines where screen x + y is the same, one every few pixels.
            int step = PATTERN_LINE_STEP;
            double first = originX + originY + Math.ceil((x + y - originX - originY) / step) * step;
            tessellator.setColorRGBA_I(PATTERN_LINE_COLOR & 0xFFFFFF, PATTERN_LINE_COLOR >>> 24);
            for (double c = first; c < x + width + y + height; c += step) {
                // Where the line enters the rectangle at the bottom or left, and leaves it at the top or right.
                double fromX = Math.max(x, c - (y + height)), toX = Math.min(x + width, c - y);
                if (toX <= fromX) {
                    continue;
                }
                // One GUI pixel wide, as a thin parallelogram along the line.
                tessellator.addVertex(fromX, c - fromX, 0);
                tessellator.addVertex(Math.min(fromX + 1, x + width), c - fromX, 0);
                tessellator.addVertex(Math.min(toX + 1, x + width), c - toX, 0);
                tessellator.addVertex(toX, c - toX, 0);
            }
        } else {
            int step = PATTERN_DOT_STEP;
            double firstX = originX + Math.ceil((x - originX) / step) * step;
            double firstY = originY + Math.ceil((y - originY) / step) * step;
            for (double dy = firstY; dy < y + height; dy += step) {
                for (double dx = firstX; dx < x + width; dx += step) {
                    addRect(tessellator, dx, dy, dx + 1, dy + 1, PATTERN_DOT_COLOR);
                }
            }
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * Draws chunk borders (every 16 blocks) over the map rectangle, with region borders (every 512 blocks) as the
     * stronger lines. Chunk lines are left out when zoomed out so far that they would be closer than a few pixels.
     * Every pixel of the grid is drawn once:
     * where lines cross, see-through lines drawn over each other made the crossings brighter.
     */
    public static void drawChunkGrid(double centerX, double centerZ, double scale, int x, int y, int width,
        int height) {
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        // Chunks only when they are far enough apart to tell, and not when only the regions are asked for. With
        // chunks only, they are drawn at any zoom and region borders are no stronger than the rest.
        boolean chunksOnly = Config.gridStyle == Config.GRID_CHUNKS;
        boolean chunks = chunksOnly || Config.gridStyle != Config.GRID_REGIONS && 16 * scale >= 6;
        int step = chunks ? 16 : MapRegion.SIZE;

        // Lines are placed and sized in real screen pixels: snapping to GUI pixels (2-4 screen pixels each) made them
        // jump behind the smoothly moving map.
        double pixel = 1.0 / ScaledScreen.currentFactor();
        // Thick lines never fill more than half the room between two of them.
        double room = Math.max(1, Math.floor(step * scale / 2 / pixel)) * pixel;
        double thickness = Math.min(pixel * Config.gridLineWidth, room);
        int chunkLine = (Math.round(Config.gridChunkOpacity * 2.55f) << 24) | Config.gridChunkColor;
        int strongLine = (Math.round(Config.gridRegionOpacity * 2.55f) << 24) | Config.gridRegionColor;

        // Lines as {from, to, strong ? 1 : 0}, left to right and top to bottom.
        List<double[]> columns = new ArrayList<>();
        int firstX = (int) Math.floor(left / step) * step;
        for (int bx = firstX;; bx += step) {
            double sx = x + (bx - left) * scale;
            if (sx > x + width) {
                break;
            }
            boolean strong = !chunksOnly && Math.floorMod(bx, MapRegion.SIZE) == 0;
            if (sx >= x && (chunks || strong)) {
                double lx = Math.floor(sx / pixel) * pixel;
                columns.add(new double[] { lx, Math.min(lx + thickness, x + width), strong ? 1 : 0 });
            }
        }
        List<double[]> rows = new ArrayList<>();
        int firstZ = (int) Math.floor(top / step) * step;
        for (int bz = firstZ;; bz += step) {
            double sy = y + (bz - top) * scale;
            if (sy > y + height) {
                break;
            }
            boolean strong = !chunksOnly && Math.floorMod(bz, MapRegion.SIZE) == 0;
            if (sy >= y && (chunks || strong)) {
                double ly = Math.floor(sy / pixel) * pixel;
                rows.add(new double[] { ly, Math.min(ly + thickness, y + height), strong ? 1 : 0 });
            }
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        // Columns between the rows, rows between the columns, then the crossings: a strong line wins there.
        for (double[] column : columns) {
            double from = y;
            for (double[] row : rows) {
                addRect(tessellator, column[0], from, column[1], row[0], column[2] > 0 ? strongLine : chunkLine);
                from = Math.max(from, row[1]);
            }
            addRect(tessellator, column[0], from, column[1], y + height, column[2] > 0 ? strongLine : chunkLine);
        }
        for (double[] row : rows) {
            if (chunksOnly) {
                // All lines alike: a row is one quad through the crossings, which the columns left out.
                addRect(tessellator, x, row[0], x + width, row[1], chunkLine);
                continue;
            }
            double from = x;
            for (double[] column : columns) {
                addRect(tessellator, from, row[0], column[0], row[1], row[2] > 0 ? strongLine : chunkLine);
                from = Math.max(from, column[1]);
            }
            addRect(tessellator, from, row[0], x + width, row[1], row[2] > 0 ? strongLine : chunkLine);
            for (double[] column : columns) {
                boolean strong = row[2] > 0 || column[2] > 0;
                addRect(tessellator, column[0], row[0], column[1], row[1], strong ? strongLine : chunkLine);
            }
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A rectangle into quads being drawn; nothing if it is empty. */
    private static void addRect(Tessellator tessellator, double x0, double y0, double x1, double y1, int color) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    /** Map color multiplier for night. */
    private static final float[] NIGHT_TINT = { 0.28f, 0.32f, 0.5f };

    /** Map color multiplier for caves: no sun or moon down there, only torches light them up. */
    private static final float[] CAVE_TINT = { 0.2f, 0.2f, 0.23f };

    /** Map color multiplier for the Nether: dim and warm, as its own murky light; lava and glowstone light it up. */
    private static final float[] NETHER_TINT = { 0.5f, 0.36f, 0.32f };

    /** RGB multiplier for the map according to {@link Config#mapLightMode} and the time of day. */
    public static float[] lightTint(Minecraft mc) {
        return tint(nightAmount(mc));
    }

    private static float[] tint(float night) {
        float day = 1f - night;
        return new float[] { NIGHT_TINT[0] + (1f - NIGHT_TINT[0]) * day, NIGHT_TINT[1] + (1f - NIGHT_TINT[1]) * day,
            NIGHT_TINT[2] + (1f - NIGHT_TINT[2]) * day };
    }

    /**
     * Cave layers of dimensions with a sky are dark at any time of day, lit only where torches and other lights are
     * (unless the map is fixed to day). Dimensions without a sky, like the Nether, get {@link #isDimNether} instead.
     */
    private static boolean isDarkCave(MapDimension dimension) {
        return dimension.cave && Config.lightMode(minimapPass) != Config.LIGHT_DAY
            && !MapManager.INSTANCE.hasNoSky(dimension.dimensionId);
    }

    /**
     * Dimensions without a sky, like the Nether, are dim at any time of day, surface and caves alike, with their lights
     * glowing (unless the map is fixed to day). Not the End: it is lit as by day, like JourneyMap shows it.
     */
    private static boolean isDimNether(MapDimension dimension) {
        return Config.lightMode(minimapPass) != Config.LIGHT_DAY && MapManager.INSTANCE.isDark(dimension.dimensionId);
    }

    /**
     * How much the 3D map of the dimension shows night: as {@link #nightAmount}, but a dimension without a sky, like
     * the Nether, is dim at any time of day, lit only by its lava and lamps (unless the map is fixed to day).
     */
    public static float isoNightAmount(Minecraft mc, int dimensionId) {
        if (Config.lightMode(minimapPass) != Config.LIGHT_DAY && MapManager.INSTANCE.isDark(dimensionId)) {
            return 1f;
        }
        return nightAmount(mc);
    }

    /** RGB multiplier for models on the 3D map of the dimension, as {@link #isoNightAmount} lights it. */
    public static float[] isoLightTint(Minecraft mc, int dimensionId) {
        if (Config.lightMode(minimapPass) != Config.LIGHT_DAY && MapManager.INSTANCE.isDark(dimensionId)) {
            return NETHER_TINT.clone();
        }
        return lightTint(mc);
    }

    /** How much the map shows night: 0 at day, 1 at night (fixed by the day/night buttons, else the sun). */
    public static float nightAmount(Minecraft mc) {
        return nightAmount(mc, false);
    }

    /** As {@link #nightAmount(Minecraft)}; {@code surface}: for the surface map, which follows the sun in any mode. */
    private static float nightAmount(Minecraft mc, boolean surface) {
        float day;
        if (Config.lightMode(minimapPass) == Config.LIGHT_DAY) {
            day = 1f;
        } else if (Config.lightMode(minimapPass) == Config.LIGHT_NIGHT) {
            day = 0f;
        } else if (mc.theWorld == null || mc.theWorld.provider.hasNoSky
            || !surface && MapManager.INSTANCE.getActiveCaveLayer() >= 0 && !MapManager.INSTANCE.isSurfaceView()) {
                // No sunlight underground: caves look the same at any time of day (the surface shown whatever the
                // cave mode follows the sun even with the player underground).
                day = 1f;
            } else {
                // Sun brightness goes from about 0.2 at midnight to 1.0 at noon.
                float sun = mc.theWorld.getSunBrightness(1f);
                day = Math.max(0f, Math.min(1f, (sun - 0.2f) / 0.8f));
            }
        return 1f - day;
    }

    /**
     * Draws the player's marker at the given screen position, pointing where the player looks, in the look, size and
     * color of the settings; {@code size} is the marker's size at 100%.
     */
    public static void drawPlayerArrow(double sx, double sy, float yaw, float size) {
        if (!Config.playerMarkerShown) {
            return;
        }
        drawPlayerMarker(
            sx,
            sy,
            yaw,
            size * Config.playerMarkerScale / 100f,
            Config.playerMarkerStyle,
            0xFF000000 | Config.playerMarkerColor,
            Config.playerMarkerOutline ? 0xFF000000 | Config.playerMarkerOutlineColor : 0);
    }

    /** Draws a player marker of the given look; yaw 180 points up. An outline color of 0 draws none. */
    public static void drawPlayerMarker(double sx, double sy, float yaw, float size, int style, int color,
        int outline) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        // The soft edge of the texture would be cut off by the alpha test.
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glTranslated(sx, sy, 0);
        // Yaw 180 faces north, which is "up" on the map.
        GL11.glRotatef(yaw + 180f, 0f, 0f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Opacity comes from the vertex color: the texture is the same at any opacity.
        PlayerMarkerTexture.bind(style, color, outline == 0 ? 0 : outline | 0xFF000000);

        double half = size * PlayerMarkerTexture.EXTENT;
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertexWithUV(-half, half, 0, 0, 1);
        tessellator.addVertexWithUV(half, half, 0, 1, 1);
        tessellator.addVertexWithUV(half, -half, 0, 1, 0);
        tessellator.addVertexWithUV(-half, -half, 0, 0, 0);
        tessellator.draw();

        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Draws a filled square marker centered on the given position. */
    public static void drawDot(double sx, double sy, float radius, int color) {
        drawDot(sx, sy, radius, color, 1f);
    }

    /** Same, with its dark border at the given opacity too. */
    private static void drawDot(double sx, double sy, float radius, int color, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        fillRect(
            tessellator,
            sx - radius - 1,
            sy - radius - 1,
            sx + radius + 1,
            sy + radius + 1,
            withAlpha(0xFF000000, alpha));
        fillRect(tessellator, sx - radius, sy - radius, sx + radius, sy + radius, color);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void fillRect(Tessellator tessellator, double x0, double y0, double x1, double y1, int color) {
        tessellator.startDrawingQuads();
        tessellator.setColorRGBA_I(color & 0xFFFFFF, (color >>> 24) & 0xFF);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        tessellator.draw();
    }

    /** Frame colors of the kinds of mobs. */
    static final int HOSTILE_COLOR = 0xFFFF4040;
    static final int NEUTRAL_COLOR = 0xFFA8ADB4;
    static final int AMBIENT_COLOR = 0xFFB57EDC;
    static final int FRIENDLY_COLOR = 0xFF50D050;
    static final int PET_COLOR = 0xFF4C9AFF;
    /**
     * Mobs within this many blocks above or below the player are drawn in full; farther ones fade out to the height
     * range.
     */
    private static final double FADE_START = 2;

    /** How many times their usual size mob icons and player heads grow to at most when zooming in. */
    private static final float ZOOM_GROWTH = 6f;

    /**
     * Draws mobs (their face in a frame colored by their kind, or a dot) and other players (their face) that are
     * within the rectangle.
     *
     * @param playerSize size of player heads in GUI pixels
     * @param showNames  draw player names under their heads
     */
    public static void drawEntities(Minecraft mc, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, float partialTicks, float playerSize, boolean showNames) {
        // Once for all the mobs' icons (fitted to the screen's pixels).
        readMatrices();
        double playerY = mc.thePlayer.posY;
        List<EntityPlayer> players = new ArrayList<>();
        List<EntityLivingBase> mobs = new ArrayList<>();
        for (Object o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityLivingBase) || o == mc.thePlayer) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) o;
            if (entity instanceof EntityPlayer) {
                // Teammates are drawn by drawTeammates, always.
                if (Config.otherPlayers(minimapPass) && !TeamMates.INSTANCE.isTeammate(entity.getUniqueID())) {
                    players.add((EntityPlayer) entity);
                }
                continue;
            }
            if (entity.isInvisible() || entity.isDead
            // Dying (its death animation), or a leftover whose chunk the client no longer has: the server never
            // said it left (a lost packet), and it stayed drawn where it was.
                || entity.getHealth() <= 0f
                || !mc.theWorld.getChunkProvider()
                    .chunkExists(MathHelper.floor_double(entity.posX) >> 4, MathHelper.floor_double(entity.posZ) >> 4)
                || Math.abs(entity.posY - playerY) > Config.entityVerticalRange
                || entityColor(entity) == 0
                || heightAlpha(entity, playerY) <= 0f) {
                continue;
            }
            double sx = x + width / 2.0 + (entity.posX - centerX) * scale;
            double sy = y + height / 2.0 + (entity.posZ - centerZ) * scale;
            if (sx < x + 2 || sy < y + 2 || sx > x + width - 2 || sy > y + height - 2) {
                continue;
            }
            mobs.add(entity);
        }

        // The nearest mobs get an icon, the rest a dot; far ones are drawn first so near ones end up on top.
        final double cx = centerX, cz = centerZ;
        mobs.sort((a, b) -> Double.compare(distanceSq(b, cx, cz), distanceSq(a, cx, cz)));
        int firstIcon = Config.entityIcons ? Math.max(0, mobs.size() - Config.entityIconLimit) : mobs.size();
        // Icons shrink when zooming out (like waypoints) so they don't cover the map, down to half their size.
        float zoomFactor = (float) Math.max(0.5, Math.min(1.0, Math.pow(scale, 0.4)));
        float baseIconSize = (playerSize + 2f) * Config.mobIconScale / 100f;
        float iconSize = Math.max(4f, baseIconSize * zoomFactor);
        // Zoomed in, they grow to cover the block the mob stands on (up to a few times their usual size), so they
        // aren't specks on huge blocks.
        iconSize = Math.max(iconSize, (float) Math.min(scale * Config.mobIconScale / 100f, baseIconSize * ZOOM_GROWTH));
        float nameScale = Math.max(4f, playerSize * zoomFactor) / 10f;
        playerSize = Math.max(Math.max(4f, playerSize * zoomFactor), (float) Math.min(scale, playerSize * ZOOM_GROWTH));
        FontRenderer font = mc.fontRenderer;
        for (int i = 0; i < mobs.size(); i++) {
            EntityLivingBase entity = mobs.get(i);
            double ex = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
            double ez = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
            double sx = x + width / 2.0 + (ex - centerX) * scale;
            double sy = y + height / 2.0 + (ez - centerZ) * scale;
            int color = entityColor(entity);
            float alpha = heightAlpha(entity, playerY);
            float half = iconSize / 2f;
            if (i >= firstIcon && sx >= x + half
                && sy >= y + half
                && sx <= x + width - half
                && sy <= y + height - half) {
                if (Config.mobFacing) {
                    // On the map itself (turning with the minimap), under the icon.
                    drawFacing(entity, sx, sy, half, color, alpha, partialTicks);
                }
                pushUpright(sx, sy);
                // Turned upright by the minimap's turn (pushUpright), from the matrices read before the mobs.
                drawEntityIcon(entity, sx, sy, iconSize, color, alpha, iconRotation);
                String name = mobName(entity);
                if (name != null) {
                    drawSmallName(font, name, sx, sy + half + frameOutside(sx, sy) + 1, nameScale, alpha);
                }
                GL11.glPopMatrix();
            } else {
                drawDot(sx, sy, 1f, withAlpha(color, alpha), alpha);
            }
        }

        // Players on top of mobs.
        float half = playerSize / 2f;
        for (EntityPlayer other : players) {
            double px = other.prevPosX + (other.posX - other.prevPosX) * partialTicks;
            double pz = other.prevPosZ + (other.posZ - other.prevPosZ) * partialTicks;
            double sx = x + width / 2.0 + (px - centerX) * scale;
            double sy = y + height / 2.0 + (pz - centerZ) * scale;
            if (sx < x + half || sy < y + half || sx > x + width - half || sy > y + height - half) {
                continue;
            }
            pushUpright(sx, sy);
            drawPlayerHead(mc, other, sx, sy, playerSize);
            GL11.glPopMatrix();
            if (showNames) {
                drawName(font, other.getCommandSenderName(), sx, sy, half, 0xFFFFFF);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static double distanceSq(EntityLivingBase entity, double x, double z) {
        double dx = entity.posX - x, dz = entity.posZ - z;
        return dx * dx + dz * dz;
    }

    /**
     * Opacity of a mob by its height: in full within a little of the player's height, then fading out the farther
     * above or below it is, gone at the height range. Climbing or going down, the mobs left behind fade away smoothly
     * (those above used to stay in full and vanish at once at the range).
     */
    private static float heightAlpha(EntityLivingBase entity, double playerY) {
        double away = Math.abs(playerY - entity.posY) - FADE_START;
        if (away <= 0) {
            return 1f;
        }
        double range = Math.max(1, Config.entityVerticalRange - FADE_START);
        double t = Math.min(1, away / range);
        // Eased: fades slowly at first, then quicker.
        return (float) (1 - t * t * (3 - 2 * t));
    }

    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    /**
     * The GL matrices and viewport, read once for a whole batch of icons ({@link #readMatrices}): asking GL for them
     * per icon (several times per mob, every frame on the minimap) can stall the drawing on some drivers.
     */
    private static final float[] MV = new float[16], PR = new float[16];
    private static final int[] VIEW = new int[4];
    /** Size of a screen pixel in the drawing space the matrices were read in. */
    private static double readPixel = 1;

    /** Reads the current GL matrices and viewport for {@link #pixelSquare} and {@link #frameOutside}. */
    private static void readMatrices() {
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        MODELVIEW.get(MV)
            .rewind();
        PROJECTION.get(PR)
            .rewind();
        for (int i = 0; i < 4; i++) {
            VIEW[i] = VIEWPORT.get(i);
        }
        double[] at = toWindow(0, 0), right = toWindow(1, 0);
        double length = Math.hypot(right[0] - at[0], right[1] - at[1]);
        readPixel = length < 1e-6 ? 1.0 / ScaledScreen.currentFactor() : 1 / length;
    }

    /**
     * Where a point of the drawing space the matrices were read in lands in the window, in screen pixels: {x, y}
     * (the GUI scale, the map screen's own scale, the minimap's turn).
     */
    private static double[] toWindow(double x, double y) {
        double ex = MV[0] * x + MV[4] * y + MV[12], ey = MV[1] * x + MV[5] * y + MV[13];
        double ez = MV[2] * x + MV[6] * y + MV[14], ew = MV[3] * x + MV[7] * y + MV[15];
        double cx = PR[0] * ex + PR[4] * ey + PR[8] * ez + PR[12] * ew;
        double cy = PR[1] * ex + PR[5] * ey + PR[9] * ez + PR[13] * ew;
        double cw = PR[3] * ex + PR[7] * ey + PR[11] * ez + PR[15] * ew;
        if (cw == 0) {
            cw = 1;
        }
        return new double[] { VIEW[0] + (cx / cw + 1) / 2 * VIEW[2], VIEW[1] + (cy / cw + 1) / 2 * VIEW[3] };
    }

    /**
     * Like {@link #toWindow}, for a point of an icon turned upright around (sx, sy) ({@link #pushUpright}: turned by
     * {@code -turn} degrees there): worked out from the matrices read before, not read again for each icon.
     */
    private static double[] toWindow(double x, double y, double sx, double sy, float turn) {
        if (turn == 0f) {
            return toWindow(x, y);
        }
        double angle = Math.toRadians(-turn), cos = Math.cos(angle), sin = Math.sin(angle);
        double dx = x - sx, dy = y - sy;
        return toWindow(sx + dx * cos - dy * sin, sy + dx * sin + dy * cos);
    }

    /**
     * The square of {@code size} centered on (sx, sy), its edges moved to the nearest screen pixel edges, and the size
     * of a screen pixel here: {x0, y0, x1, y1, pixel}. A frame of whole pixels around it is then as wide on every side
     * whatever the GUI scale and wherever the mob is between pixels. Left where it is if the drawing is turned or
     * slanted on the screen (no pixel grid to fit). From the matrices of the last {@link #readMatrices}, the icon
     * turned upright by {@code turn} degrees around its center since.
     */
    private static double[] pixelSquare(double sx, double sy, double size, float turn) {
        double[] at = toWindow(sx, sy, sx, sy, turn), right = toWindow(sx + 1, sy, sx, sy, turn),
            down = toWindow(sx, sy + 1, sx, sy, turn);
        // Screen pixels per unit along each axis, and how much each axis leans into the other.
        double a = right[0] - at[0], c = right[1] - at[1], b = down[0] - at[0], d = down[1] - at[1];
        double half = size / 2;
        double length = Math.sqrt(a * a + c * c);
        if (length < 1e-6) {
            return new double[] { sx - half, sy - half, sx + half, sy + half, 1.0 / ScaledScreen.currentFactor() };
        }
        double pixel = 1 / length;
        if (Math.abs(c) > Math.abs(a) * 1e-3 || Math.abs(b) > Math.abs(d) * 1e-3 || Math.abs(d) < 1e-6) {
            return new double[] { sx - half, sy - half, sx + half, sy + half, pixel };
        }
        // A whole number of pixels a side, the same both ways, its corner on a pixel corner.
        long n = Math.max(1, Math.round(size * Math.abs(a)));
        double winX0 = Math.floor(at[0] - n / 2.0 + 0.5), winY0 = Math.floor(at[1] - n / 2.0 + 0.5);
        double xa = sx + (winX0 - at[0]) / a, xb = sx + (winX0 + n - at[0]) / a;
        double ya = sy + (winY0 - at[1]) / d, yb = sy + (winY0 + n - at[1]) / d;
        return new double[] { Math.min(xa, xb), Math.min(ya, yb), Math.max(xa, xb), Math.max(ya, yb), pixel };
    }

    /** Size of one screen pixel at (x, y) in the current drawing space (of the minimap's buffer while it draws). */
    public static double screenPixel(double x, double y) {
        readMatrices();
        return readPixel;
    }

    /** How far a mob icon's frame (and its dark edge) reaches out of the tile here, in the drawing's units. */
    private static double frameOutside(double sx, double sy) {
        int frame = Math.max(0, Config.mobFrameWidth);
        if (frame == 0) {
            return 0;
        }
        return (frame + (frame > 1 ? 1 : 0)) * readPixel;
    }

    /** The band of width {@code w} just outside the rectangle, in four pieces that don't overlap. */
    private static void frameAround(Tessellator tessellator, double x0, double y0, double x1, double y1, double w,
        int color) {
        fillRect(tessellator, x0 - w, y0 - w, x1 + w, y0, color);
        fillRect(tessellator, x0 - w, y1, x1 + w, y1 + w, color);
        fillRect(tessellator, x0 - w, y0, x0, y1, color);
        fillRect(tessellator, x1, y0, x1 + w, y1, color);
    }

    private static int withAlpha(int color, float alpha) {
        return Math.round((color >>> 24) * alpha) << 24 | color & 0xFFFFFF;
    }

    /**
     * Frame color by kind of mob, or 0 if that kind is hidden: pets, hostile, friendly (villagers...), ambient
     * (bats...), neutral.
     */
    static int entityColor(EntityLivingBase entity) {
        if (isPet(entity)) {
            return Config.pets(minimapPass) ? PET_COLOR : 0;
        }
        if (entity instanceof IMob) {
            return Config.hostileMobs(minimapPass) ? HOSTILE_COLOR : 0;
        }
        if (isFriendly(entity)) {
            return Config.otherEntities(minimapPass) ? FRIENDLY_COLOR : 0;
        }
        if (entity instanceof EntityAmbientCreature) {
            return Config.ambientMobs(minimapPass) ? AMBIENT_COLOR : 0;
        }
        return Config.passiveMobs(minimapPass) ? NEUTRAL_COLOR : 0;
    }

    /** A tamed mob: a tamed wolf or cat, a tamed horse, or a modded mob with an owner. */
    private static boolean isPet(EntityLivingBase entity) {
        if (entity instanceof EntityTameable) {
            return ((EntityTameable) entity).isTamed();
        }
        if (entity instanceof EntityHorse) {
            return ((EntityHorse) entity).isTame();
        }
        return entity instanceof IEntityOwnable && ((IEntityOwnable) entity).getOwner() != null;
    }

    /** Whether each kind of mob is friendly, by class (the name test is slow to repeat every frame). */
    private static final Map<Class<?>, Boolean> FRIENDLY = new HashMap<>();

    /**
     * Villagers, traders and helpers: villagers and modded people (NPCs, merchants), golems, and modded traders such
     * as hobgoblins, found by their class name.
     */
    private static boolean isFriendly(EntityLivingBase entity) {
        if (entity instanceof INpc || entity instanceof IMerchant || entity instanceof EntityGolem) {
            return true;
        }
        Class<?> type = entity.getClass();
        Boolean friendly = FRIENDLY.get(type);
        if (friendly == null) {
            String name = type.getSimpleName()
                .toLowerCase(Locale.ROOT);
            friendly = name.contains("villager") || name.contains("trader")
                || name.contains("merchant")
                || name.contains("npc")
                || name.contains("goblin");
            FRIENDLY.put(type, friendly);
        }
        return friendly;
    }

    /** Name given to the mob with a name tag, or null: none, or the names of its kind are off. */
    private static String mobName(EntityLivingBase entity) {
        if (!(entity instanceof EntityLiving) || !((EntityLiving) entity).hasCustomNameTag() || !namesShown(entity)) {
            return null;
        }
        return ((EntityLiving) entity).getCustomNameTag();
    }

    /** Whether names are shown for the mob's kind, told apart as for its frame color. */
    private static boolean namesShown(EntityLivingBase entity) {
        if (isPet(entity)) {
            return Config.petNames;
        }
        if (entity instanceof IMob) {
            return Config.hostileNames;
        }
        if (isFriendly(entity)) {
            return Config.friendlyNames;
        }
        return entity instanceof EntityAmbientCreature ? Config.ambientNames : Config.neutralNames;
    }

    /** Small name centered under an icon, at {@code textScale} of the normal text size. */
    private static void drawSmallName(FontRenderer font, String name, double sx, double top, float textScale,
        float alpha) {
        GL11.glPushMatrix();
        GL11.glTranslated(sx, top, 0);
        GL11.glScalef(textScale, textScale, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // The font treats an alpha under 4 as opaque.
        int a = Math.max(8, Math.round(alpha * 255));
        font.drawStringWithShadow(name, -font.getStringWidth(name) / 2, 0, a << 24 | 0xFFFFFF);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /**
     * A small arrow just outside the mob's icon on the side it looks to, in its frame color: drawn smooth (like the
     * player's marker) and under the icon, so it seems to come out of it.
     */
    private static void drawFacing(EntityLivingBase entity, double sx, double sy, float half, int color, float alpha,
        float partialTicks) {
        float yaw = entity.prevRotationYawHead
            + MathHelper.wrapAngleTo180_float(entity.rotationYawHead - entity.prevRotationYawHead) * partialTicks;
        double rad = Math.toRadians(yaw);
        float arrow = Math.max(1.6f, half * 0.45f);
        double distance = half + frameOutside(sx, sy) + arrow * 0.55;
        // Yaw 0 looks south (+z, down on the map), 90 west (-x).
        double ax = sx - Math.sin(rad) * distance, ay = sy + Math.cos(rad) * distance;
        drawPlayerMarker(
            ax,
            ay,
            yaw,
            arrow,
            Config.MARKER_TRIANGLE,
            withAlpha(color, alpha),
            withAlpha(0xFF000000, alpha));
    }

    /**
     * One mob drawn the way the maps draw it, for the settings' preview: its icon with the arrow where it looks and a
     * pet's name, or a dot while icons are off. With no mob (no world to make one in) the tile is drawn with a dot.
     */
    static void drawMob(FontRenderer font, EntityLivingBase entity, double sx, double sy, float iconSize, int color,
        float alpha, float textScale) {
        if (!Config.entityIcons) {
            drawDot(sx, sy, 1.5f, withAlpha(color, alpha), alpha);
            return;
        }
        readMatrices();
        float half = iconSize / 2f;
        if (Config.mobFacing && entity != null) {
            drawFacing(entity, sx, sy, half, color, alpha, 1f);
        }
        drawEntityIcon(entity, sx, sy, iconSize, color, alpha, 0f);
        String name = entity == null ? null : mobName(entity);
        if (name != null) {
            drawSmallName(font, name, sx, sy + half + frameOutside(sx, sy) + 1, textScale, alpha);
        }
    }

    /**
     * Draws the mob as a flat icon: its face on a small tile framed in the color of its kind (pet, hostile, friendly,
     * neutral). Falls back to a dot when the mob's face can't be found.
     */
    private static void drawEntityIcon(EntityLivingBase entity, double sx, double sy, float size, int color,
        float alpha, float turn) {
        // The tile on whole screen pixels, so the frame around it is exactly as many pixels wide on every side (it
        // was in GUI pixels, 2 to 4 screen pixels, and between pixels it came out 1 on one side and 2 on another).
        double[] tile = pixelSquare(sx, sy, size, turn);
        double x0 = tile[0], y0 = tile[1], x1 = tile[2], y1 = tile[3], pixel = tile[4];
        // 0 = no frame: the face alone on its dark tile. In screen pixels.
        int frame = Math.max(0, Config.mobFrameWidth);
        double frameWidth = frame * pixel;
        float frameAlpha = alpha * Config.mobFrameOpacity / 100f;
        Tessellator tessellator = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (frame > 1) {
            // A dark pixel around a wide frame, so it shows on its own color.
            frameAround(
                tessellator,
                x0 - frameWidth,
                y0 - frameWidth,
                x1 + frameWidth,
                y1 + frameWidth,
                pixel,
                withAlpha(0xA0000000, frameAlpha));
        }
        if (frame > 0) {
            // A band around the tile, not a square under it: see-through, it must not darken the face.
            frameAround(tessellator, x0, y0, x1, y1, frameWidth, withAlpha(color, frameAlpha));
        }
        fillRect(tessellator, x0, y0, x1, y1, withAlpha(0xFF101418, alpha));
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // Exactly over the dark tile: a face a pixel smaller left half pixels of it that showed as a dark line on
        // one side.
        // Its icon drawn by the game; until it is made (or if it can't be), its face cut out of its skin.
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float shown = (float) (x1 - x0);
        if (entity == null
            || !MobIcons.draw(entity, cx, cy, shown, alpha) && !EntityIcons.drawFace(entity, cx, cy, shown, alpha)) {
            drawDot(cx, cy, 1f, withAlpha(color, alpha), alpha);
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Draws the face of the player's skin (with the hat layer), with a dark border. */
    private static void drawPlayerHead(Minecraft mc, EntityPlayer player, double sx, double sy, float size) {
        drawHead(
            mc,
            player instanceof AbstractClientPlayer ? ((AbstractClientPlayer) player).getLocationSkin() : null,
            sx,
            sy,
            size,
            0xFF000000);
    }

    /** Team color frame around teammates' heads. */
    private static final int TEAMMATE_FRAME = 0xFF4CB4FF;

    /**
     * Draws the online teammates in the given dimension (reported by the server, so even far away or in another
     * dimension than the player's), with a blue frame and, on the world map, their names.
     */
    public static void drawTeammates(Minecraft mc, int dimension, double centerX, double centerZ, double scale, int x,
        int y, int width, int height, float partialTicks, float playerSize, boolean showNames) {
        drawTeammates(
            mc,
            dimension,
            (px, py,
                pz) -> new double[] { x + width / 2.0 + (px - centerX) * scale,
                    y + height / 2.0 + (pz - centerZ) * scale },
            scale,
            x,
            y,
            width,
            height,
            partialTicks,
            playerSize,
            showNames);
    }

    /** Where a world point is drawn on the screen: {x, y}. */
    public interface Projection {

        double[] toScreen(double x, double y, double z);
    }

    /** Like the other drawTeammates, with any projection (the 3D map's). */
    public static void drawTeammates(Minecraft mc, int dimension, Projection projection, double scale, int x, int y,
        int width, int height, float partialTicks, float playerSize, boolean showNames) {
        drawTeammates(
            mc,
            dimension,
            projection,
            scale,
            x,
            y,
            width,
            height,
            partialTicks,
            playerSize,
            showNames,
            false);
    }

    /**
     * @param skipLoaded leave out teammates the client has nearby (the 3D map draws them as their model)
     */
    public static void drawTeammates(Minecraft mc, int dimension, Projection projection, double scale, int x, int y,
        int width, int height, float partialTicks, float playerSize, boolean showNames, boolean skipLoaded) {
        List<TeamMates.Mate> mates = TeamMates.INSTANCE.all();
        if (mates.isEmpty()) {
            return;
        }
        float zoomFactor = (float) Math.max(0.5, Math.min(1.0, Math.pow(scale, 0.4)));
        float size = Math.max(
            Math.max(5f, (playerSize + 1f) * zoomFactor),
            (float) Math.min(scale, (playerSize + 1f) * ZOOM_GROWTH));
        float half = size / 2f;
        FontRenderer font = mc.fontRenderer;
        for (TeamMates.Mate mate : mates) {
            if (mate.dimension != dimension) {
                continue;
            }
            if (skipLoaded && mc.theWorld != null
                && mc.theWorld.provider.dimensionId == dimension
                && mc.theWorld.func_152378_a(mate.id) != null) { // getPlayerEntityByUUID
                continue;
            }
            double[] position = TeamMates.INSTANCE.position(mate, partialTicks);
            double[] screen = projection.toScreen(position[0], position[1], position[2]);
            double sx = screen[0], sy = screen[1];
            if (sx < x + half || sy < y + half || sx > x + width - half || sy > y + height - half) {
                continue;
            }
            ResourceLocation skin = AbstractClientPlayer.getLocationSkin(mate.name);
            // Starts the skin download for players the client hasn't seen.
            AbstractClientPlayer.getDownloadImageSkin(skin, mate.name);
            pushUpright(sx, sy);
            drawHead(mc, skin, sx, sy, size, TEAMMATE_FRAME);
            GL11.glPopMatrix();
            if (showNames) {
                drawName(font, mate.name, sx, sy, half, 0x9FD4FF);
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** How big a player's name is on the minimap, to the game's font: its heads are small. */
    private static final float MINIMAP_NAME_SCALE = 0.5f;

    /**
     * A player's name under their head ({@code half} its half size): on the minimap smaller, and upright when the
     * minimap turns.
     */
    private static void drawName(FontRenderer font, String name, double sx, double sy, float half, int color) {
        if (!minimapPass) {
            font.drawStringWithShadow(name, (int) sx - font.getStringWidth(name) / 2, (int) (sy + half) + 2, color);
            return;
        }
        pushUpright(sx, sy);
        GL11.glTranslated(sx, sy + half + 1.5, 0);
        GL11.glScalef(MINIMAP_NAME_SCALE, MINIMAP_NAME_SCALE, 1f);
        font.drawStringWithShadow(name, -font.getStringWidth(name) / 2, 0, color);
        GL11.glPopMatrix();
    }

    private static void drawHead(Minecraft mc, ResourceLocation skin, double sx, double sy, float size, int frame) {
        double half = size / 2.0;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        fillRect(tessellator, sx - half - 1, sy - half - 1, sx + half + 1, sy + half + 1, frame);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (skin == null) {
            return;
        }
        mc.getTextureManager()
            .bindTexture(skin);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // The face is at (8, 8), the hat layer at (40, 8), both 8x8, in a skin 64 wide. 1.7.10 skins are 64x32, but
        // mods (and GTNH) load the newer 64x64 ones: the height is taken from the texture, or the face would be
        // read from the body below it.
        double height = skinHeight();
        drawSkinPart(tessellator, sx, sy, half, 8, 8, height);
        drawSkinPart(tessellator, sx, sy, half, 40, 8, height);
    }

    /** Height of the bound skin in skin pixels (64 wide): 32 or 64, also for HD skins. */
    private static double skinHeight() {
        try {
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width > 0 && height > 0) {
                return height * 64.0 / width;
            }
        } catch (RuntimeException ignored) {}
        return 32.0;
    }

    private static void drawSkinPart(Tessellator tessellator, double sx, double sy, double half, int u, int v,
        double skinHeight) {
        double u0 = u / 64.0, u1 = (u + 8) / 64.0, v0 = v / skinHeight, v1 = (v + 8) / skinHeight;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(sx - half, sy + half, 0, u0, v1);
        tessellator.addVertexWithUV(sx + half, sy + half, 0, u1, v1);
        tessellator.addVertexWithUV(sx + half, sy - half, 0, u1, v0);
        tessellator.addVertexWithUV(sx - half, sy - half, 0, u0, v0);
        tessellator.draw();
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
