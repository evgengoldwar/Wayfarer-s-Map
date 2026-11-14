package WayFarMap.client.integration;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;

import org.lwjgl.opengl.GL11;

import com.sinthoras.visualprospecting.Config;
import com.sinthoras.visualprospecting.Utils;
import com.sinthoras.visualprospecting.VP;
import com.sinthoras.visualprospecting.database.ClientCache;
import com.sinthoras.visualprospecting.database.OreVeinPosition;
import com.sinthoras.visualprospecting.database.UndergroundFluidPosition;
import com.sinthoras.visualprospecting.database.veintypes.VeinType;
import com.sinthoras.visualprospecting.database.veintypes.VeinTypeCaching;
import com.sinthoras.visualprospecting.integration.model.locations.OreVeinLocation;
import com.sinthoras.visualprospecting.integration.model.locations.UndergroundFluidLocation;
import com.sinthoras.visualprospecting.integration.model.render.DimensionStoneBackground;

import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.waypoint.WaypointRenderer;
import gregtech.api.interfaces.IIconContainer;

/**
 * VisualProspecting (GTNH fork) ore veins and underground fluids on the maps, drawn the way VisualProspecting's own
 * {@code OreVeinRenderStep} and {@code UndergroundFluidRenderStep} draw them for JourneyMap.
 * <p>
 * Only touch this class after {@link Mods#isVisualProspectingLoaded()}.
 */
public final class ProspectingLayer {

    private static final ResourceLocation DEPLETED_TEXTURE = new ResourceLocation(
        "visualprospecting",
        "textures/depleted.png");
    private static final long REFRESH_MS = 1000;
    private static final int LABEL_BACKGROUND = 0xB4000000;

    /** Dimension and time of the last rebuild of each list: veins and fluids are rebuilt only while shown. */
    private static int veinDimension = Integer.MIN_VALUE, fluidDimension = Integer.MIN_VALUE;
    private static long lastVeinRefresh, lastFluidRefresh;
    /**
     * Locations made so far, by VisualProspecting's entry (null for an entry that failed): a well prospected world
     * holds hundreds of thousands of veins, and making them all again every second froze the game.
     */
    private static Map<OreVeinPosition, OreVeinLocation> veinCache = new IdentityHashMap<>();
    private static Map<UndergroundFluidPosition, UndergroundFluidLocation> fluidCache = new IdentityHashMap<>();
    private static List<OreVeinLocation> veins = Collections.emptyList();
    /** Database entry of each vein location, for its height range. */
    private static Map<OreVeinLocation, OreVeinPosition> positions = new IdentityHashMap<>();
    private static List<UndergroundFluidLocation> fluids = Collections.emptyList();

    /** Vein under the mouse on the last drawn fullscreen map, or null. */
    private static OreVeinLocation hovered;

    /** The vein being tracked: shown in the world with its distance, framed in gold on the maps. */
    private static final class Tracked {

        final int dimension, chunkX, chunkZ;
        final double x, z;
        final int y;
        final OreVeinLocation location;

        Tracked(OreVeinLocation location, OreVeinPosition position) {
            this.location = location;
            this.dimension = position.dimensionId;
            this.chunkX = position.chunkX;
            this.chunkZ = position.chunkZ;
            this.x = location.getBlockX();
            this.z = location.getBlockZ();
            // Middle of the vein's height range, where the ores are.
            this.y = (position.veinType.minBlockY + position.veinType.maxBlockY) / 2;
        }

        boolean is(OreVeinPosition position) {
            return position.dimensionId == dimension && position.chunkX == chunkX && position.chunkZ == chunkZ;
        }
    }

    private static Tracked tracked;
    private static final int TRACKED_COLOR = 0xFFD700;

    /**
     * Forgets what was read of VisualProspecting and the tracked vein (the server left): kept, a well prospected
     * world's veins stayed in memory for the rest of the game unless the layer was shown again.
     */
    public static void reset() {
        veinCache = new IdentityHashMap<>();
        fluidCache = new IdentityHashMap<>();
        positions = new IdentityHashMap<>();
        veins = Collections.emptyList();
        veinGrid = new HashMap<>();
        fluids = Collections.emptyList();
        hovered = null;
        tracked = null;
        veinDimension = Integer.MIN_VALUE;
        fluidDimension = Integer.MIN_VALUE;
        lastVeinRefresh = 0;
        lastFluidRefresh = 0;
    }

    /** The thin frame right around a vein's icon. */
    private static final int FRAME_COLOR = 0x000000;

    private ProspectingLayer() {}

    /** Search text of the world map, lower case; empty when not searching. */
    private static String search = "";
    private static final int SEARCH_COLOR = 0xFFD34D;

    /** Like VisualProspecting's onOpenMap: dims the veins that don't match the NEI search. */
    public static void onOpenMap() {
        setSearch("");
        forceRefresh();
    }

    public static boolean isSearchActive() {
        return !search.isEmpty();
    }

    /**
     * Searches ore veins (with VisualProspecting's own search, by vein and ore names) and underground fluids (by
     * fluid name). An empty text goes back to NEI's search, like VisualProspecting does.
     */
    public static void setSearch(String text) {
        search = text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
        try {
            if (search.isEmpty()) {
                VeinTypeCaching.recalculateSearch(Utils.getNEISearchPattern(), Utils.getNEISearchItemFilter());
            } else {
                VeinTypeCaching.recalculateSearch(Utils.getSearchPattern(search), Utils.getItemFilter(search));
            }
        } catch (Throwable t) {
            // Search highlighting is optional.
        }
    }

    private static boolean fluidMatches(Fluid fluid) {
        if (fluid == null) {
            return false;
        }
        for (String name : new String[] { fluid.getLocalizedName(), fluid.getUnlocalizedName(), fluid.getName() }) {
            if (name != null && name.toLowerCase(Locale.ROOT)
                .contains(search)) {
                return true;
            }
        }
        return false;
    }

    private static void forceRefresh() {
        lastVeinRefresh = 0;
        lastFluidRefresh = 0;
    }

    /** Rebuilds the dimension's ore veins now and then; only new entries get a new location. */
    private static void refreshVeins(int dimension) {
        long now = System.currentTimeMillis();
        if (dimension == veinDimension && now - lastVeinRefresh < REFRESH_MS) {
            return;
        }
        boolean sameDimension = dimension == veinDimension;
        veinDimension = dimension;
        lastVeinRefresh = now;
        if (sameDimension && !veinsChanged(dimension)) {
            // Nothing new prospected: the lists stay. Making them again every second over a well prospected world
            // (hundreds of thousands of veins) cost a hitch and a load of garbage each time.
            return;
        }

        // One bad entry (e.g. a vein type whose ore has no texture) is skipped instead of hiding everything.
        List<OreVeinLocation> newVeins = new ArrayList<>();
        Map<OreVeinLocation, OreVeinPosition> newPositions = new IdentityHashMap<>();
        Map<OreVeinPosition, OreVeinLocation> newCache = new IdentityHashMap<>();
        try {
            for (OreVeinPosition vein : ClientCache.instance.getAllOreVeins()) {
                if (vein.dimensionId != dimension) {
                    continue;
                }
                OreVeinLocation location;
                if (veinCache.containsKey(vein)) {
                    location = veinCache.get(vein);
                } else {
                    location = null;
                    try {
                        if (vein.veinType != null && vein.veinType != VeinType.NO_VEIN) {
                            location = new OreVeinLocation(vein);
                        }
                    } catch (Throwable t) {
                        warnOnce("vein " + (vein.veinType != null ? vein.veinType.name : "?"), t);
                    }
                }
                newCache.put(vein, location);
                if (location != null) {
                    newVeins.add(location);
                    newPositions.put(location, vein);
                }
            }
        } catch (Throwable t) {
            warnOnce("ore veins", t);
        }
        veinCache = newCache;
        veins = newVeins;
        positions = newPositions;
        veinGrid = grid(newVeins);
    }

    /** Whether VisualProspecting has veins of the dimension not in the lists, or no longer has some of them. */
    private static boolean veinsChanged(int dimension) {
        int count = 0;
        try {
            for (OreVeinPosition vein : ClientCache.instance.getAllOreVeins()) {
                if (vein.dimensionId != dimension) {
                    continue;
                }
                if (!veinCache.containsKey(vein)) {
                    return true;
                }
                count++;
            }
        } catch (Throwable t) {
            return true;
        }
        return count != veinCache.size();
    }

    /** Cells of 512 blocks a side, for the veins near what is drawn. */
    private static final int GRID_SHIFT = 9;
    /** The veins by cell ({@link #cellKey}), so drawing looks only at those near the view, not at all of them. */
    private static Map<Long, List<OreVeinLocation>> veinGrid = new HashMap<>();

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static Map<Long, List<OreVeinLocation>> grid(List<OreVeinLocation> all) {
        Map<Long, List<OreVeinLocation>> grid = new HashMap<>();
        for (OreVeinLocation vein : all) {
            // Its block position is a double (the middle of the vein).
            int cellX = (int) Math.floor(vein.getBlockX()) >> GRID_SHIFT;
            int cellZ = (int) Math.floor(vein.getBlockZ()) >> GRID_SHIFT;
            grid.computeIfAbsent(cellKey(cellX, cellZ), k -> new ArrayList<>())
                .add(vein);
        }
        return grid;
    }

    /** The veins in the cells over blocks x0..x1, z0..z1; all of them when that is about as many cells as veins. */
    private static List<OreVeinLocation> veinsNear(double x0, double z0, double x1, double z1) {
        int cx0 = (int) Math.floor(x0) >> GRID_SHIFT, cx1 = (int) Math.floor(x1) >> GRID_SHIFT;
        int cz0 = (int) Math.floor(z0) >> GRID_SHIFT, cz1 = (int) Math.floor(z1) >> GRID_SHIFT;
        long cells = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        if (cells >= veinGrid.size()) {
            return veins;
        }
        List<OreVeinLocation> near = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<OreVeinLocation> cell = veinGrid.get(cellKey(cx, cz));
                if (cell != null) {
                    near.addAll(cell);
                }
            }
        }
        return near;
    }

    /** Rebuilds the dimension's underground fluids now and then; only new entries get a new location. */
    private static void refreshFluids(int dimension) {
        long now = System.currentTimeMillis();
        if (dimension == fluidDimension && now - lastFluidRefresh < REFRESH_MS) {
            return;
        }
        boolean sameDimension = dimension == fluidDimension;
        fluidDimension = dimension;
        lastFluidRefresh = now;
        if (sameDimension && !fluidsChanged(dimension)) {
            // Nothing new prospected: the list stays (made again every second, it was a load of garbage).
            return;
        }

        List<UndergroundFluidLocation> newFluids = new ArrayList<>();
        Map<UndergroundFluidPosition, UndergroundFluidLocation> newCache = new IdentityHashMap<>();
        try {
            for (UndergroundFluidPosition fluid : ClientCache.instance.getAllUndergroundFluids()) {
                if (fluid.dimensionId != dimension) {
                    continue;
                }
                UndergroundFluidLocation location;
                if (fluidCache.containsKey(fluid)) {
                    location = fluidCache.get(fluid);
                } else {
                    location = null;
                    try {
                        if (fluid.isProspected()) {
                            location = new UndergroundFluidLocation(fluid);
                            location.setActive(true);
                        }
                    } catch (Throwable t) {
                        warnOnce("fluid " + (fluid.fluid != null ? fluid.fluid.getName() : "?"), t);
                    }
                }
                newCache.put(fluid, location);
                if (location != null) {
                    newFluids.add(location);
                }
            }
        } catch (Throwable t) {
            warnOnce("underground fluids", t);
        }
        fluidCache = newCache;
        fluids = newFluids;
    }

    /** Whether VisualProspecting has fluid fields of the dimension not in the list, or no longer has some. */
    private static boolean fluidsChanged(int dimension) {
        int count = 0;
        try {
            for (UndergroundFluidPosition fluid : ClientCache.instance.getAllUndergroundFluids()) {
                if (fluid.dimensionId != dimension) {
                    continue;
                }
                if (!fluidCache.containsKey(fluid)) {
                    return true;
                }
                count++;
            }
        } catch (Throwable t) {
            return true;
        }
        return count != fluidCache.size();
    }

    /**
     * Everything found so far, for the statistics: per dimension {ore veins, prospected underground fluids}.
     * Render thread (VisualProspecting's cache is kept there).
     */
    public static Map<Integer, int[]> countFound() {
        Map<Integer, int[]> byDimension = new HashMap<>();
        try {
            for (OreVeinPosition vein : ClientCache.instance.getAllOreVeins()) {
                if (vein.veinType != null && vein.veinType != VeinType.NO_VEIN) {
                    byDimension.computeIfAbsent(vein.dimensionId, k -> new int[2])[0]++;
                }
            }
        } catch (Throwable t) {
            warnOnce("ore veins", t);
        }
        try {
            for (UndergroundFluidPosition fluid : ClientCache.instance.getAllUndergroundFluids()) {
                if (fluid.isProspected()) {
                    byDimension.computeIfAbsent(fluid.dimensionId, k -> new int[2])[1]++;
                }
            }
        } catch (Throwable t) {
            warnOnce("underground fluids", t);
        }
        return byDimension;
    }

    private static final Set<String> WARNED = new HashSet<>();

    /** Logs a problem with VisualProspecting data once per kind, so a broken entry doesn't flood the log. */
    private static void warnOnce(String what, Throwable t) {
        if (WARNED.add(what)) {
            WayFarMap.LOG.warn("Skipping VisualProspecting " + what + " on the map", t);
        }
    }

    /** JourneyMap-style zoom level: 2^zoom screen pixels per block. */
    private static double zoomLevel(double guiScale) {
        Minecraft mc = Minecraft.getMinecraft();
        int factor = ScaledScreen.currentFactor();
        return Math.log(guiScale * factor) / Math.log(2);
    }

    // ---------------------------------------------------------------- underground fluids

    public static void drawFluids(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap) {
        refreshFluids(dimension);
        if (fluids.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double zoom = zoomLevel(scale);
        int fieldBlocks = VP.undergroundFluidSizeChunkX * VP.chunkWidth;
        double fieldSize = fieldBlocks * scale;
        Minecraft mc = Minecraft.getMinecraft();
        double pixel = 1.0 / ScaledScreen.currentFactor();

        // While searching (the world map's search, on the minimap too), fields of other fluids fade out like in
        // VisualProspecting.
        boolean searching = isSearchActive();
        begin();
        for (UndergroundFluidLocation location : fluids) {
            double fx = x + (Math.floor(location.getBlockX()) - left) * scale;
            double fy = y + (Math.floor(location.getBlockZ()) - top) * scale;
            if (fx + fieldSize < x || fy + fieldSize < y || fx > x + width || fy > y + height) {
                continue;
            }
            int max = location.getMaxProduction();
            boolean active = !searching || (max > 0 && fluidMatches(location.getFluid()));
            if (active && zoom >= Config.minZoomLevelForUndergroundFluidDetails - 1 && max > 0) {
                drawFluidChunks(location, fx, fy, scale, zoom, x, y, width, height, minimap);
            }
            if (max > 0) {
                int alpha = active ? 255 : 74;
                hollowRect(fx, fy, fieldSize, fieldSize, 2 * pixel, location.getColor(), alpha, x, y, width, height);
            } else {
                hollowRect(fx, fy, fieldSize, fieldSize, 2 * pixel, 0xFFFFFF, 74, x, y, width, height);
            }
            if (searching && active) {
                // Search hits get an outline around the field.
                hollowRect(
                    fx - 2 * pixel,
                    fy - 2 * pixel,
                    fieldSize + 4 * pixel,
                    fieldSize + 4 * pixel,
                    2 * pixel,
                    SEARCH_COLOR,
                    255,
                    x,
                    y,
                    width,
                    height);
            }
        }
        end();

        if (minimap || zoom < Config.minZoomLevelForUndergroundFluidDetails - 3) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        for (UndergroundFluidLocation location : fluids) {
            double fx = x + (Math.floor(location.getBlockX()) - left) * scale;
            double fy = y + (Math.floor(location.getBlockZ()) - top) * scale;
            if (fieldSize < 40 || fx + fieldSize < x || fy + fieldSize < y || fx > x + width || fy > y + height) {
                continue;
            }
            int max = location.getMaxProduction();
            String title = Lang.format("visualprospecting.empty");
            String values = null;
            if (max > 0) {
                title = location.getFluid()
                    .getLocalizedName();
                values = MessageFormat
                    .format("{0}-{1} L/Op", formatAmount(location.getMinProduction() >> 1), formatAmount(max >> 1));
            }
            int maxWidth = (int) fieldSize - 6;
            double labelX = fx + fieldSize / 2;
            int textColor = !isSearchActive() ? 0xFFFFFF
                : max > 0 && fluidMatches(location.getFluid()) ? 0xFFFF00 : 0x444444;
            if (values != null && zoom >= Config.minZoomLevelForUndergroundFluidDetails) {
                // With the amounts in the chunks, the range goes on the name's line, clear of the first row of them.
                label(font, ellipsize(font, title + ": " + values, maxWidth), labelX, fy + 2, textColor);
                continue;
            }
            label(font, ellipsize(font, title, maxWidth), labelX, fy + 3, textColor);
            if (values != null) {
                label(font, ellipsize(font, values, maxWidth), labelX, fy + 14, textColor);
            }
        }
    }

    private static void drawFluidChunks(UndergroundFluidLocation location, double fx, double fy, double scale,
        double zoom, int x, int y, int width, int height, boolean minimap) {
        int min = location.getMinProduction();
        int max = location.getMaxProduction();
        int color = location.getColor();
        int[][] chunks = location.getChunks();
        float range = max - min + 1;
        boolean highlightPeak = max >= 10;
        boolean labels = !minimap && zoom >= Config.minZoomLevelForUndergroundFluidDetails;
        double cell = VP.chunkWidth * scale;
        List<Object[]> cellLabels = labels ? new ArrayList<>() : null;
        for (int cx = 0; cx < VP.undergroundFluidSizeChunkX; cx++) {
            for (int cz = 0; cz < VP.undergroundFluidSizeChunkZ; cz++) {
                int amount = chunks[cx][cz];
                if (amount <= 0) {
                    continue;
                }
                double cellX = fx + cx * cell;
                double cellY = fy + cz * cell;
                int alpha = range > 1 ? (int) ((amount - min) / range * 255) : 10;
                rect(cellX, cellY, cell, cell, color, alpha, x, y, width, height);
                if (highlightPeak && amount >= max) {
                    hollowRect(cellX, cellY, cell, cell, Math.max(1, cell / 16), 0xFFD700, 204, x, y, width, height);
                }
                if (labels) {
                    cellLabels.add(new Object[] { formatAmount(amount >> 1), cellX + cell / 2, cellY + cell / 2 });
                }
            }
        }
        if (labels && !cellLabels.isEmpty()) {
            end();
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            for (Object[] cellLabel : cellLabels) {
                double lx = (Double) cellLabel[1], ly = (Double) cellLabel[2];
                if (lx > x && ly > y && lx < x + width && ly < y + height) {
                    // Shrunk to fit the chunk (with its background), so neighbours don't run into each other; when
                    // that would make it too small to read, the amount alone.
                    String text = MessageFormat.format("{0} L/Op", cellLabel[0]);
                    double fit = (cell - 2) / (font.getStringWidth(text) + 4);
                    if (fit < 0.6) {
                        text = (String) cellLabel[0];
                        fit = (cell - 2) / (font.getStringWidth(text) + 4);
                    }
                    float textScale = (float) Math.min(1, fit);
                    GL11.glPushMatrix();
                    GL11.glTranslated(lx, ly, 0);
                    GL11.glScalef(textScale, textScale, 1f);
                    label(font, text, 0, -4, 0xFFFFFF);
                    GL11.glPopMatrix();
                }
            }
            begin();
        }
    }

    /** Same formatting as VisualProspecting: 950, 1.2k, 3k. */
    private static String formatAmount(int amount) {
        if (amount < 1000) {
            return String.valueOf(amount);
        }
        int roundedTenths = (amount + 50) / 100;
        if (roundedTenths % 10 == 0) {
            return (roundedTenths / 10) + "k";
        }
        return (roundedTenths / 10) + "." + (roundedTenths % 10) + "k";
    }

    // ---------------------------------------------------------------- ore veins

    /**
     * Draws the ore vein icons (and their names when zoomed in on the fullscreen map).
     *
     * @return nothing; the vein under the mouse is remembered for {@link #getHoveredTooltip()} and the right click menu
     */
    public static void drawOreVeins(int dimension, double centerX, double centerZ, double scale, int x, int y,
        int width, int height, boolean minimap, int mouseX, int mouseY) {
        refreshVeins(dimension);
        if (!minimap) {
            hovered = null;
        }
        if (veins.isEmpty()) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double zoom = zoomLevel(scale);
        double veinSpacing = VP.oreVeinSizeChunkX * VP.chunkWidth * scale;
        // On the world map the icons keep growing when zooming in past 1:1 (up to 64 pixels), so the ores can be told
        // apart up close instead of staying 16 pixels on blocks of 16 or more.
        double size = minimap ? Math.max(5, Math.min(10, veinSpacing * 0.6))
            : Math.max(6, Math.min(Math.min(64, 16 * Math.max(1, Math.sqrt(scale))), veinSpacing * 0.6));
        double half = size / 2;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;

        List<OreVeinLocation> visible = new ArrayList<>();
        // A vein a little outside still shows part of its icon: a margin of the icon's size around the view.
        double margin = size / scale + 16;
        for (OreVeinLocation vein : veinsNear(
            left - margin,
            top - margin,
            left + width / scale + margin,
            top + height / scale + margin)) {
            double sx = x + (vein.getBlockX() - left) * scale;
            double sy = y + (vein.getBlockZ() - top) * scale;
            if (sx - half < x || sy - half < y || sx + half > x + width || sy + half > y + height) {
                continue;
            }
            visible.add(vein);
            if (!minimap && mouseX >= sx - half && mouseX <= sx + half && mouseY >= sy - half && mouseY <= sy + half) {
                hovered = vein;
            }
        }

        // Around each icon a thin black frame, right against it, so the vein stands out on any map: in the Nether the
        // ores and their stone are the colors of the ground around them.
        double pixel = MapDrawer.screenPixel(x, y);
        // About one texture pixel of the icon, on whole screen pixels.
        double pad = Math.max(pixel, Math.round(size / 16 / pixel) * pixel);
        begin();
        OreVeinLocation trackedVein = null;
        for (OreVeinLocation vein : visible) {
            double sx = x + (vein.getBlockX() - left) * scale - half;
            double sy = y + (vein.getBlockZ() - top) * scale - half;
            hollowRect(sx - pad, sy - pad, size + 2 * pad, size + 2 * pad, pad, FRAME_COLOR, 255, x, y, width, height);
            if (trackedVein == null && tracked != null && isTrackedLocation(vein)) {
                trackedVein = vein;
            }
        }
        if (trackedVein != null) {
            // The tracked vein: a thick gold frame around the black one, seen on any map.
            double sx = x + (trackedVein.getBlockX() - left) * scale - half - pad;
            double sy = y + (trackedVein.getBlockZ() - top) * scale - half - pad;
            double gold = Math.max(2 * pixel, pad * 2);
            hollowRect(
                sx - gold,
                sy - gold,
                size + 2 * pad + 2 * gold,
                size + 2 * pad + 2 * gold,
                gold,
                TRACKED_COLOR,
                255,
                x,
                y,
                width,
                height);
        }
        end();

        // Drawn in a few batches (plates, icons, shading, depleted marks) rather than several draw calls per vein:
        // zoomed out over a well prospected area there can be thousands of veins on screen.
        Tessellator tessellator = Tessellator.instance;
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        tessellator.startDrawingQuads();
        for (OreVeinLocation vein : visible) {
            addVeinIcon(
                tessellator,
                vein,
                x + (vein.getBlockX() - left) * scale - half,
                y + (vein.getBlockZ() - top) * scale - half,
                size);
        }
        tessellator.draw();

        // Search misses and depleted veins are shaded.
        begin();
        boolean anyDepleted = false;
        for (OreVeinLocation vein : visible) {
            if (!vein.drawSearchHighlight() || vein.isDepleted()) {
                double sx = x + (vein.getBlockX() - left) * scale - half;
                double sy = y + (vein.getBlockZ() - top) * scale - half;
                rect(sx, sy, size, size, 0x000000, 150, x, y, width, height);
                anyDepleted |= vein.isDepleted();
            }
        }
        end();

        if (anyDepleted) {
            mc.getTextureManager()
                .bindTexture(DEPLETED_TEXTURE);
            tessellator.startDrawingQuads();
            tessellator.setColorOpaque_I(0xFFFFFF);
            for (OreVeinLocation vein : visible) {
                if (vein.isDepleted()) {
                    double sx = x + (vein.getBlockX() - left) * scale - half;
                    double sy = y + (vein.getBlockZ() - top) * scale - half;
                    addTextureQuad(tessellator, sx, sy, size, 0, 0, 1, 1);
                }
            }
            tessellator.draw();
        }

        GL11.glColor4f(1f, 1f, 1f, 1f);

        if (minimap || zoom < Config.minZoomLevelForOreLabel) {
            return;
        }
        // Names above the icons, cut to the set width, as waypoints' names are. The one under the mouse takes its place
        // first and is drawn last, on top; any other that would overlap a name already placed is left out (the one
        // under the mouse used to be drawn over its neighbours, its text running into theirs).
        boolean always = OreLabels.always();
        // Clear of the frame.
        double labelHalf = half + pad;
        List<double[]> placed = new ArrayList<>();
        List<Object[]> labels = new ArrayList<>();
        if (hovered != null && visible.contains(hovered)) {
            placeLabel(font, hovered, x, y, left, top, scale, labelHalf, placed, labels);
        }
        if (always) {
            for (OreVeinLocation vein : visible) {
                if (vein != hovered && !vein.isDepleted()) {
                    placeLabel(font, vein, x, y, left, top, scale, labelHalf, placed, labels);
                }
            }
        }
        // Back to front: the hovered one, placed first, ends on top.
        for (int i = labels.size() - 1; i >= 0; i--) {
            Object[] l = labels.get(i);
            label(font, (String) l[0], (Double) l[1], (Double) l[2], (Integer) l[3]);
        }
    }

    /** Places the vein's name above its icon unless it would overlap a name already placed: {text, x, y, color}. */
    private static void placeLabel(FontRenderer font, OreVeinLocation vein, int x, int y, double left, double top,
        double scale, double half, List<double[]> placed, List<Object[]> labels) {
        String name = ellipsize(font, vein.getName(), OreLabels.maxWidth());
        int w = font.getStringWidth(name);
        double lx = x + (vein.getBlockX() - left) * scale;
        double ly = y + (vein.getBlockZ() - top) * scale - half - 11;
        double[] box = { lx - w / 2.0 - 2, ly - 1, lx + w / 2.0 + 2, ly + 9 };
        if (overlaps(box, placed)) {
            return;
        }
        placed.add(box);
        int color = vein.isDepleted() || !vein.drawSearchHighlight() ? 0x7F7F7F : 0xFFFFFF;
        labels.add(new Object[] { name, lx, ly, color });
    }

    /** Tooltip lines of the vein under the mouse, or null. */
    public static List<String> getHoveredTooltip() {
        if (hovered == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (hovered.isDepleted()) {
            lines.add(hovered.getDepletedHint());
        }
        if (isTrackedLocation(hovered)) {
            lines.add("\u00a76" + Lang.format("wayfarmap.gui.vein_tracked"));
        }
        lines.add(hovered.getName());
        if (!hovered.isDepleted()) {
            lines.addAll(hovered.getMaterialNames());
        }
        lines.add("§8" + Lang.format("wayfarmap.gui.vein_toggle_hint"));
        return lines;
    }

    /**
     * The vein under the mouse, as an opaque handle for the other methods; null if none. Callers keep it (e.g. while a
     * menu is open) instead of asking again, since the vein under the mouse changes as the mouse moves.
     */
    public static Object getHoveredVein() {
        OreVeinPosition position = hovered == null ? null : positions.get(hovered);
        return position == null ? null : new Handle(hovered, position);
    }

    /** A vein kept by a caller; stays valid when the vein list is rebuilt. */
    private static final class Handle {

        final OreVeinLocation location;
        final OreVeinPosition position;

        Handle(OreVeinLocation location, OreVeinPosition position) {
            this.location = location;
            this.position = position;
        }
    }

    public static boolean isDepleted(Object vein) {
        return ((Handle) vein).location.isDepleted();
    }

    /** Marks the vein depleted, or not depleted anymore. */
    public static void toggleDepleted(Object vein) {
        ((Handle) vein).location.toggleOreVein();
        forceRefresh();
    }

    public static boolean isTracked(Object vein) {
        return tracked != null && tracked.is(((Handle) vein).position);
    }

    private static boolean isTrackedLocation(OreVeinLocation location) {
        OreVeinPosition position = positions.get(location);
        return tracked != null && position != null && tracked.is(position);
    }

    /** Starts tracking the vein, or stops if it is the tracked one. */
    public static void toggleTracked(Object vein) {
        Handle handle = (Handle) vein;
        tracked = isTracked(vein) ? null : new Tracked(handle.location, handle.position);
    }

    /**
     * Draws the tracked vein in the world: its ore icon, name and distance, like a waypoint, and a beam over it if the
     * tracked style asks for one.
     */
    public static void renderTrackedInWorld(Minecraft mc, int dimension, float partialTicks) {
        final Tracked target = tracked;
        if (target == null || target.dimension != dimension) {
            return;
        }
        if (WaypointRenderer.trackedBeams()) {
            WaypointRenderer.renderBeam(
                mc,
                (int) Math.floor(target.x),
                (int) Math.floor(target.z),
                TRACKED_COLOR,
                partialTicks,
                1f);
        }
        String name = Lang.format("wayfarmap.gui.vein_tracked_name", strip(target.location.getName()));
        WaypointRenderer.renderBillboard(mc, target.x, target.y, target.z, name, TRACKED_COLOR, (cx, cy, size) -> {
            drawVeinIcon(target.location, cx - size / 2, cy - size / 2, size);
            return true;
        });
    }

    private static String strip(String text) {
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return plain != null ? plain : text;
    }

    /** Stone background and ore icon of the vein, with the top-left corner at (sx, sy). */
    private static void drawVeinIcon(OreVeinLocation vein, double sx, double sy, double size) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        addVeinIcon(tessellator, vein, sx, sy, size);
        tessellator.draw();
    }

    /**
     * Adds the stone background and ore icon of the vein, with the top-left corner at (sx, sy), to the quads being
     * drawn with the block texture atlas bound.
     */
    private static void addVeinIcon(Tessellator tessellator, OreVeinLocation vein, double sx, double sy, double size) {
        try {
            addIconQuad(
                tessellator,
                DimensionStoneBackground.getBackgroundIcon(vein.getDimensionId()),
                sx,
                sy,
                size,
                0xFFFFFF);
            IIconContainer ore = vein.getIconFromPrimaryOre();
            addIconQuad(tessellator, ore.getIcon(), sx, sy, size, vein.getColor());
            IIcon overlay = ore.getOverlayIcon();
            if (overlay != null) {
                addIconQuad(tessellator, overlay, sx, sy, size, 0xFFFFFF);
            }
        } catch (Throwable t) {
            // Missing textures only cost the icon.
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    /** True between {@link #begin()} and {@link #end()}: rectangles go into one batch of quads. */
    private static boolean batching;

    /** Starts a batch of untextured rectangles, drawn together by {@link #end()}. */
    private static void begin() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator.instance.startDrawingQuads();
        batching = true;
    }

    private static void end() {
        if (batching) {
            batching = false;
            Tessellator.instance.draw();
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** Filled rectangle clipped to the map rectangle. */
    private static void rect(double rx, double ry, double w, double h, int rgb, int alpha, int x, int y, int width,
        int height) {
        double x0 = Math.max(rx, x), y0 = Math.max(ry, y);
        double x1 = Math.min(rx + w, x + width), y1 = Math.min(ry + h, y + height);
        if (x1 <= x0 || y1 <= y0 || alpha <= 0) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        if (!batching) {
            tessellator.startDrawingQuads();
        }
        tessellator.setColorRGBA_I(rgb & 0xFFFFFF, Math.min(255, alpha));
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
        if (!batching) {
            tessellator.draw();
        }
    }

    private static void hollowRect(double rx, double ry, double w, double h, double thickness, int rgb, int alpha,
        int x, int y, int width, int height) {
        rect(rx, ry, w, thickness, rgb, alpha, x, y, width, height);
        rect(rx, ry + h - thickness, w, thickness, rgb, alpha, x, y, width, height);
        rect(rx, ry + thickness, thickness, h - 2 * thickness, rgb, alpha, x, y, width, height);
        rect(rx + w - thickness, ry + thickness, thickness, h - 2 * thickness, rgb, alpha, x, y, width, height);
    }

    private static void addIconQuad(Tessellator tessellator, IIcon icon, double sx, double sy, double size, int rgb) {
        if (icon != null) {
            tessellator.setColorOpaque_I(rgb & 0xFFFFFF);
            addTextureQuad(tessellator, sx, sy, size, icon.getMinU(), icon.getMinV(), icon.getMaxU(), icon.getMaxV());
        }
    }

    private static void addTextureQuad(Tessellator tessellator, double sx, double sy, double size, double u0, double v0,
        double u1, double v1) {
        tessellator.addVertexWithUV(sx, sy + size, 0, u0, v1);
        tessellator.addVertexWithUV(sx + size, sy + size, 0, u1, v1);
        tessellator.addVertexWithUV(sx + size, sy, 0, u1, v0);
        tessellator.addVertexWithUV(sx, sy, 0, u0, v0);
    }

    /** Text centered on {@code cx} with a dark background, like VisualProspecting's labels. */
    private static void label(FontRenderer font, String text, double cx, double ty, int color) {
        int w = font.getStringWidth(text);
        int lx = (int) Math.round(cx - w / 2.0);
        int ly = (int) Math.round(ty);
        begin();
        rect(
            lx - 2,
            ly - 1,
            w + 4,
            10,
            LABEL_BACKGROUND & 0xFFFFFF,
            LABEL_BACKGROUND >>> 24,
            lx - 2,
            ly - 1,
            w + 4,
            10);
        end();
        font.drawString(text, lx, ly, color);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth("..."))) + "...";
    }

    private static boolean overlaps(double[] box, List<double[]> others) {
        for (double[] other : others) {
            if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) {
                return true;
            }
        }
        return false;
    }
}
