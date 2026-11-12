package WayFarMap.client.map;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import WayFarMap.Config;
import WayFarMap.client.GlTextures;

/**
 * Topographic view: land in flat bands of height (hypsometric tints, from green lowlands through yellow and brown
 * hills to gray rock and snowy peaks) with contour lines between them, a heavier one every
 * {@link #INDEX_CONTOUR}th; water in blues by its depth with a line along the shore; lava in orange. Made from the
 * ground the map without plants keeps (trees looked through, water down to its floor, which pixels are water or
 * lava), as one texture per region drawn over the map; rebuilt when the region or the settings change. In
 * dimensions under a ceiling (the Nether) the bands start at the lava sea and use the Nether's own colors.
 */
public final class Topography {

    /** Top block of the sea: land starts above it; on maps scanned before water was kept, water up to it. */
    private static final int SEA_LEVEL = 62;
    /** The Nether: its lava sea, and the height the last band color is reached at. */
    private static final int NETHER_SEA_LEVEL = 31, NETHER_TOP = 120;
    /** Height the last land color is reached at. */
    private static final int TOP = 170;
    /** Land from just above the sea up to the top. */
    private static final int[] LAND = { 0x5E8F45, 0x6C9B4B, 0x7BA652, 0x8DB15A, 0xA1BA63, 0xB5C16D, 0xC7C478, 0xD3BE7E,
        0xD6B07A, 0xCF9F6F, 0xC28D63, 0xB27C59, 0xA06E53, 0x8F6752, 0x8A7466, 0x968A82, 0xACA49F, 0xC5C0BC, 0xDDDAD8,
        0xF3F2F1 };
    /** Nether ground from just above the lava sea up. */
    private static final int[] NETHER_LAND = { 0x4A1D1A, 0x5A2420, 0x6B2C25, 0x7C352A, 0x8C4130, 0x9A4E38, 0xA75D41,
        0xB26D4C, 0xBC7E59, 0xC59068, 0xCDA27A, 0xD5B48D };
    /** Water from the shallows to the deep, one color per {@link #WATER_STEP} blocks of depth. */
    private static final int[] WATER = { 0x9CCBEA, 0x80B9E2, 0x67A6D8, 0x5193CB, 0x4080BC, 0x346EAA, 0x2A5D96, 0x214C80,
        0x1A3D6A };
    private static final int WATER_STEP = 4;
    /** Water of maps scanned before its depth was kept: along the shore and away from it. */
    private static final int OLD_SHALLOW = 0x67A6D8, OLD_DEEP = 0x346EAA;
    private static final int LAVA = 0xE0641E, LAVA_SHORE = 0xF29A3A;
    /** The line along the shore, on the water side. */
    private static final int SHORE_LINE = 0x1E3F66;
    /** How far from land water counts as along the shore, in pixels (maps scanned before water was kept). */
    private static final int SHORE = 2;
    /** Every so many contour lines one is drawn heavier. */
    private static final int INDEX_CONTOUR = 5;
    /** How dark contour lines are drawn over the band color: thin and heavy ones. */
    private static final float CONTOUR_SHADE = 0.72f, INDEX_CONTOUR_SHADE = 0.5f;
    /** Rebuilding a region's texture while it is being explored is throttled to this interval. */
    private static final long REBUILD_MS = 1000;
    /** Textures (re)built per frame, so turning the view on over many regions spreads over a few frames. */
    private static final int BUILDS_PER_FRAME = 4, LOD_BUILDS_PER_FRAME = 32;

    private static final class Overlay {

        int textureId = -1;
        int settings = -1;
        int regionChanges = -1;
        long builtAt;
    }

    private static final Map<PixelSource, Overlay> OVERLAYS = new WeakHashMap<>();
    private static int buildsLeft;
    private static IntBuffer buffer;

    private Topography() {}

    public static boolean isShown() {
        return isShown(false);
    }

    public static boolean isShown(boolean minimap) {
        return Config.displayMode(minimap) == Config.DISPLAY_TOPO;
    }

    /** How far the view has faded in on one map, so turning it on or off fades like a switch of layers. */
    private static final class Fade {

        float amount;
        boolean shown;
        /** When the view was last drawn and when it was turned on or off. */
        long lastFrame, switched;
        /** Surface it was last drawn from: fading out goes on over it while the map under it shows another one. */
        MapDimension surface;
    }

    private static final Fade MINIMAP_FADE = new Fade(), WORLD_MAP_FADE = new Fade();

    /** Longest wait for the textures to be built before the view fades in anyway. */
    private static final long FADE_WAIT_MS = 1000;

    /**
     * Draws the topography in the given rectangle (same geometry as MapDrawer#drawMap), from the surface map without
     * plants (or the surface itself if it has no such map). Called whether the view is on or not: it fades in and out
     * over {@link Config#layerFadeMs}, as the map under it does between its layers.
     *
     * @param minimap drawn by the minimap (it fades apart from the world map)
     */
    public static void draw(MapDimension surface, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap) {
        Fade fade = minimap ? MINIMAP_FADE : WORLD_MAP_FADE;
        long now = System.currentTimeMillis();
        long elapsed = now - fade.lastFrame;
        fade.lastFrame = now;
        boolean shown = isShown(minimap);
        if (shown != fade.shown) {
            fade.shown = shown;
            fade.switched = now;
        }
        long duration = Config.layerFadeMs;
        float before = fade.amount;
        if (duration <= 0 || elapsed >= 500) {
            // No fading, or the map was just opened: straight to where it should be.
            fade.amount = shown ? 1f : 0f;
        } else {
            float step = elapsed / (float) duration;
            fade.amount = shown ? Math.min(1f, fade.amount + step) : Math.max(0f, fade.amount - step);
        }
        if (surface != null && surface.plantless() != null) {
            surface = surface.plantless();
        }
        if (shown) {
            fade.surface = surface;
        } else if (fade.surface != null && surface != null && fade.surface.dimensionId == surface.dimensionId) {
            // The map under it may already be a cave layer or the biomes: the topography fading out stays the ground.
            surface = fade.surface;
        }
        if (fade.amount <= 0f || surface == null) {
            if (fade.amount <= 0f) {
                fade.surface = null;
            }
            return;
        }
        int waiting = drawTextures(surface, centerX, centerZ, scale, x, y, width, height, fade.amount);
        if (shown && waiting > 0 && fade.amount < 1f && now - fade.switched < FADE_WAIT_MS) {
            // Its textures are still being built: it comes in once they are there, not region by region.
            fade.amount = before;
        }
    }

    /** Draws the textures at the given opacity; returns how many regions in view wait for theirs. */
    private static int drawTextures(MapDimension surface, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, float alpha) {
        boolean nether = surface.dimensionId == -1;
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;
        int rx0 = (int) Math.floor(left) >> MapRegion.SHIFT;
        int rz0 = (int) Math.floor(top) >> MapRegion.SHIFT;
        int rx1 = (int) Math.floor(right) >> MapRegion.SHIFT;
        int rz1 = (int) Math.floor(bottom) >> MapRegion.SHIFT;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        Tessellator tessellator = Tessellator.instance;
        // Same resolution as the map under it.
        boolean lod = WayFarMap.client.MapDrawer.useLod(scale);
        buildsLeft = lod ? LOD_BUILDS_PER_FRAME : BUILDS_PER_FRAME;
        int waiting = 0;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                PixelSource region = lod ? surface.requestLod(rx, rz) : surface.getLoadedRegion(rx, rz);
                if (region == null) {
                    continue;
                }
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }
                int texture = overlayTexture(region, nether);
                if (texture == -1) {
                    waiting++;
                    continue;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
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
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
        return waiting;
    }

    /** Frees all textures (e.g. when leaving the world). */
    public static void clear() {
        MINIMAP_FADE.surface = WORLD_MAP_FADE.surface = null;
        Iterator<Overlay> it = OVERLAYS.values()
            .iterator();
        while (it.hasNext()) {
            Overlay overlay = it.next();
            if (overlay.textureId != -1) {
                GlTextures.delete(overlay.textureId);
            }
            it.remove();
        }
    }

    /** Frees the texture of a region or reduced copy whose own texture is freed. */
    static void forget(PixelSource source) {
        Overlay overlay = OVERLAYS.remove(source);
        if (overlay != null && overlay.textureId != -1) {
            GlTextures.delete(overlay.textureId);
        }
    }

    /** The settings the textures are built with, as one number: a change rebuilds them. */
    private static int settings() {
        return (Config.topoContours ? 1 : 0) | Config.topoContourInterval << 1;
    }

    /** Texture of the region, or -1 while it waits for its turn to be built. */
    private static int overlayTexture(PixelSource region, boolean nether) {
        Overlay overlay = OVERLAYS.get(region);
        if (overlay == null) {
            overlay = new Overlay();
            OVERLAYS.put(region, overlay);
        }
        long now = System.currentTimeMillis();
        boolean stale = overlay.settings != settings()
            || (overlay.regionChanges != region.getChanges() && now - overlay.builtAt >= REBUILD_MS);
        if (stale && buildsLeft <= 0) {
            // Out of budget this frame: an outdated texture is still better than a flash of the plain map.
            return overlay.settings == -1 || overlay.textureId == -1 ? -1 : overlay.textureId;
        }
        if (stale) {
            buildsLeft--;
        }
        if (overlay.textureId == -1) {
            overlay.textureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            stale = true;
        }
        if (stale) {
            build(region, nether);
            GlTextures.bindForUpload(overlay.textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                region.size(),
                region.size(),
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                buffer);
            overlay.settings = settings();
            overlay.regionChanges = region.getChanges();
            overlay.builtAt = now;
        }
        return overlay.textureId;
    }

    /** Kinds of pixel. */
    private static final int UNKNOWN = 0, LAND_PIXEL = 1, WATER_PIXEL = 2, LAVA_PIXEL = 3;

    /** Work arrays of one build: the render thread keeps its own, a picture being saved makes new ones. */
    private static final class Scratch {

        final int[] kinds = new int[MapRegion.SIZE * MapRegion.SIZE];
        final int[] heights = new int[MapRegion.SIZE * MapRegion.SIZE];
        final int[] smooth = new int[MapRegion.SIZE * MapRegion.SIZE];
        final int[] pixels = new int[MapRegion.SIZE * MapRegion.SIZE];
    }

    private static Scratch renderScratch;

    /** Fills {@link #buffer} with the topography of the region (render thread). */
    private static void build(PixelSource region, boolean nether) {
        if (buffer == null) {
            buffer = BufferUtils.createIntBuffer(MapRegion.SIZE * MapRegion.SIZE);
            renderScratch = new Scratch();
        }
        build(region, nether, renderScratch);
        buffer.clear();
        buffer.put(renderScratch.pixels, 0, region.size() * region.size());
        buffer.flip();
    }

    /**
     * The topography of a whole region as ARGB pixels, transparent where the ground is unknown: for a picture of the
     * map, on any thread.
     *
     * @param nether the bands start at the Nether's lava sea, in its colors
     */
    public static int[] picture(MapRegion region, boolean nether) {
        Scratch scratch = new Scratch();
        build(region, nether, scratch);
        return scratch.pixels;
    }

    /**
     * Fills the scratch's pixels with the topography of the region; transparent where the ground is unknown.
     *
     * @param nether the bands start at the Nether's lava sea, in its colors
     */
    private static void build(PixelSource region, boolean nether, Scratch scratch) {
        int size = region.size();
        int interval = Math.max(1, Config.topoContourInterval);
        int sea = nether ? NETHER_SEA_LEVEL : SEA_LEVEL;
        int[] kinds = scratch.kinds, heights = scratch.heights, smooth = scratch.smooth, out = scratch.pixels;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                int h = height(region, x, z);
                int flags = region.getLight(x, z);
                int kind;
                if (h < 0) {
                    kind = UNKNOWN;
                } else if ((flags & MapRegion.TOPO_KNOWN) != 0) {
                    kind = (flags & MapRegion.TOPO_LAVA) != 0 ? LAVA_PIXEL
                        : (flags & MapRegion.TOPO_WATER) != 0 ? WATER_PIXEL : LAND_PIXEL;
                } else {
                    // Scanned before water was kept: below the sea is water, except under a ceiling (no sea there).
                    kind = !nether && h <= SEA_LEVEL ? WATER_PIXEL : LAND_PIXEL;
                    flags = 0;
                }
                kinds[i] = kind | (flags & MapRegion.TOPO_KNOWN);
                heights[i] = h;
            }
        }
        // Land heights evened out over their 3x3 land neighbors: single blocks (a boulder, a pit, a house) don't
        // ring themselves with contour lines, so the lines follow the shape of the land.
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if ((kinds[i] & 3) != LAND_PIXEL) {
                    smooth[i] = heights[i];
                    continue;
                }
                int sum = 0, count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                            continue;
                        }
                        int n = nz * size + nx;
                        if ((kinds[n] & 3) == LAND_PIXEL) {
                            sum += heights[n];
                            count++;
                        }
                    }
                }
                smooth[i] = Math.round((float) sum / count);
            }
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                int kind = kinds[i] & 3;
                int rgb;
                if (kind == UNKNOWN) {
                    out[i] = 0;
                    continue;
                } else if (kind == WATER_PIXEL) {
                    rgb = waterColor(scratch, x, z, size, sea, (kinds[i] & MapRegion.TOPO_KNOWN) != 0);
                } else if (kind == LAVA_PIXEL) {
                    rgb = touches(kinds, x, z, size, LAND_PIXEL, 1) ? LAVA_SHORE : LAVA;
                } else {
                    int band = band(smooth[i], sea, interval);
                    rgb = landColor(band, interval, sea, nether);
                    if (Config.topoContours) {
                        // A line where a neighbour is in a lower band: drawn once, on the upper side of the step.
                        int lower = Integer.MAX_VALUE;
                        for (int[] d : NEIGHBOURS) {
                            int nx = x + d[0], nz = z + d[1];
                            if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                                continue;
                            }
                            int n = nz * size + nx;
                            if ((kinds[n] & 3) == LAND_PIXEL) {
                                lower = Math.min(lower, band(smooth[n], sea, interval));
                            }
                        }
                        if (lower < band) {
                            // The heavy line of every few: if any of the steps crossed is one.
                            boolean index = false;
                            for (int b = lower + 1; b <= band; b++) {
                                index |= Math.floorMod(b, INDEX_CONTOUR) == 0;
                            }
                            rgb = shade(rgb, index ? INDEX_CONTOUR_SHADE : CONTOUR_SHADE);
                        }
                    }
                }
                out[i] = 0xFF000000 | rgb;
            }
        }
    }

    private static final int[][] NEIGHBOURS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

    /**
     * Water by its depth below the sea: lighter in the shallows, with a dark line along the shore. Maps scanned
     * before the depth was kept: lighter along the shore.
     */
    private static int waterColor(Scratch scratch, int x, int z, int size, int sea, boolean known) {
        if (touches(scratch.kinds, x, z, size, LAND_PIXEL, 1)) {
            return SHORE_LINE;
        }
        if (!known) {
            return touches(scratch.kinds, x, z, size, LAND_PIXEL, SHORE) ? OLD_SHALLOW : OLD_DEEP;
        }
        // The water keeps the height of its floor.
        int depth = Math.max(0, sea - scratch.heights[z * size + x]);
        return WATER[Math.min(WATER.length - 1, depth / WATER_STEP)];
    }

    /** A pixel of the kind within {@code reach} pixels. */
    private static boolean touches(int[] kinds, int x, int z, int size, int kind, int reach) {
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < size && nz < size && (kinds[nz * size + nx] & 3) == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Band of a land height: 0 from just above the sea, one more every {@code interval} blocks. */
    private static int band(int h, int sea, int interval) {
        return Math.floorDiv(h - sea - 1, interval);
    }

    /** Flat color of a band: the palette at the band's middle height, so the step only changes how fine it is. */
    private static int landColor(int band, int interval, int sea, boolean nether) {
        int[] palette = nether ? NETHER_LAND : LAND;
        int top = nether ? NETHER_TOP : TOP;
        double middle = band * interval + interval / 2.0;
        int index = (int) Math.round(middle / (top - sea - 1) * (palette.length - 1));
        return palette[Math.max(0, Math.min(palette.length - 1, index))];
    }

    private static int shade(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return r << 16 | g << 8 | b;
    }

    /** Height of the ground at the pixel, -1 if unknown or outside the region. */
    private static int height(PixelSource region, int x, int z) {
        int size = region.size();
        if (x < 0 || z < 0 || x >= size || z >= size || (region.getPixel(x, z) >>> 24) == 0) {
            return -1;
        }
        // The map keeps the height one above the ground (or above the top block on older maps), 0 when unknown.
        int extra = region.getExtra(x, z);
        return extra == 0 ? -1 : extra - 1;
    }
}
