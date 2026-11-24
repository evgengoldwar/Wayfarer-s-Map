package WayFarMap.client.integration;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.map.MapManager;
import WayFarMap.share.ShareNetwork;
import WayFarMap.share.WirelessPoint;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * AE2 wireless coverage: a circle around each wireless access point, where a wireless terminal reaches it.
 * <p>
 * AE2 tests the distance in 3D ({@code dx² + dy² + dz² < range²}, from the block's corner), so the coverage is a
 * ball. In the player's dimension the filled circle is its slice at the player's feet, where the terminal works right
 * now, and a thin ring shows the full range.
 * <p>
 * Two sources. A server with this mod sends the access points of the networks the player's terminals are linked to,
 * with their range ({@code share.WirelessServer}). Elsewhere the client only knows what it sees: access points in
 * loaded chunks, whoever's they are, and whether they work. AE2 doesn't send the client the boosters, so the range is
 * learned when the player opens the access point's screen; until then the circle is the range without boosters,
 * grayed. Those are kept in {@code ae2_wireless.json} of the world's map folder.
 */
public final class WirelessLayer implements MachineScanner.Layer {

    public static final WirelessLayer INSTANCE = new WirelessLayer();

    private static final int ACTIVE_COLOR = 0x4FC3F7;
    private static final int INACTIVE_COLOR = 0xFF3030;
    private static final int UNKNOWN_COLOR = 0xB0BEC5;
    private static final long SAVE_DELAY_MS = 5000;
    private static final String FILE_NAME = "ae2_wireless.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap AE2 wireless saver");
        thread.setDaemon(true);
        return thread;
    });

    private WirelessLayer() {}

    /** An access point the client saw itself; range 0 until its screen was opened. */
    private static final class Seen {

        final int x, y, z;
        double range;
        boolean active;

        Seen(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Queue<ShareNetwork.Wireless> inbox = new ConcurrentLinkedQueue<>();
    /** From the server; null while it sent none (no mod, no AE2 there). */
    private List<WirelessPoint> fromServer;
    private boolean infinite;
    /** Seen by the client: dimension -> packed position -> access point. */
    private final Map<Integer, Map<Long, Seen>> seen = new HashMap<>();
    private File worldDirectory;
    private boolean inWorld, dirty;
    private long dirtySince;

    /** From the network thread. */
    public void receive(ShareNetwork.Wireless message) {
        inbox.add(message);
    }

    // ---------------------------------------------------------------- client tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Mods.isAe2Loaded()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            if (inWorld) {
                inWorld = false;
                saveNow();
                seen.clear();
                fromServer = null;
                infinite = false;
                worldDirectory = null;
                inbox.clear();
            }
            return;
        }
        inWorld = true;
        File directory = MapManager.INSTANCE.getWorldDirectory();
        if (directory != null && !directory.equals(worldDirectory)) {
            saveNow();
            worldDirectory = directory;
            load();
        }
        ShareNetwork.Wireless message;
        while ((message = inbox.poll()) != null) {
            fromServer = message.points;
            infinite = message.infinite;
        }
        try {
            learnOpenRange(mc.theWorld.provider.dimensionId, Ae2WirelessClient.openAccessPoint(mc.thePlayer));
        } catch (Throwable t) {
            Mods.fail(Mods.Addon.AE2_WIRELESS, t);
        }
        if (dirty && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
            saveNow();
        }
    }

    private void learnOpenRange(int dimension, double[] open) {
        if (open == null) {
            return;
        }
        Seen point = seenAt(dimension, (int) open[0], (int) open[1], (int) open[2], true);
        boolean active = open[4] != 0;
        if (point.range != open[3] || point.active != active) {
            point.range = open[3];
            point.active = active;
            markDirty();
        }
    }

    // ---------------------------------------------------------------- MachineScanner

    private final Set<Long> present = new HashSet<>();

    @Override
    public Mods.Addon addon() {
        return Mods.Addon.AE2_WIRELESS;
    }

    @Override
    public boolean scans() {
        return inWorld && Mods.isAe2Loaded();
    }

    @Override
    public void sweepStart() {
        present.clear();
    }

    @Override
    public void see(int dimension, TileEntity tile, Object gtMachine) {
        int[] found = Ae2WirelessClient.accessPoint(tile);
        if (found == null) {
            return;
        }
        Seen point = seenAt(dimension, found[0], found[1], found[2], true);
        present.add(pack(found[0], found[1], found[2]));
        boolean active = found[3] != 0;
        if (point.active != active) {
            point.active = active;
            markDirty();
        }
    }

    /** Drops the access points whose loaded chunk no longer has them. */
    @Override
    public void sweepEnd(World world, int dimension) {
        Map<Long, Seen> points = seen.get(dimension);
        if (points != null) {
            for (Iterator<Map.Entry<Long, Seen>> it = points.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<Long, Seen> entry = it.next();
                Seen point = entry.getValue();
                if (present.contains(entry.getKey()) || !isLoaded(world, point.x, point.z)) {
                    continue;
                }
                if (!Ae2WirelessClient.isAccessPoint(world, point.x, point.y, point.z)) {
                    it.remove();
                    markDirty();
                }
            }
        }
    }

    private static boolean isLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded;
    }

    private Seen seenAt(int dimension, int x, int y, int z, boolean create) {
        Map<Long, Seen> points = seen.computeIfAbsent(dimension, d -> new HashMap<>());
        long key = pack(x, y, z);
        Seen point = points.get(key);
        if (point == null && create) {
            point = new Seen(x, y, z);
            points.put(key, point);
            markDirty();
        }
        return point;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
    }

    // ---------------------------------------------------------------- storage

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }

    /** The file: {@code {"accessPoints": [{dimension, x, y, z, range, active}, ...]}}, range 0 when unknown. */
    private static final class Saved {

        List<Entry> accessPoints = new ArrayList<>();

        static final class Entry {

            int dimension, x, y, z;
            double range;
            boolean active;
        }
    }

    private void load() {
        seen.clear();
        File file = new File(worldDirectory, FILE_NAME);
        if (file.isFile()) {
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Saved saved = GSON.fromJson(in, Saved.class);
                if (saved != null && saved.accessPoints != null) {
                    for (Saved.Entry e : saved.accessPoints) {
                        if (e == null) {
                            continue;
                        }
                        Seen point = seenAt(e.dimension, e.x, e.y, e.z, true);
                        point.range = Math.max(0, e.range);
                        point.active = e.active;
                    }
                }
            } catch (IOException | RuntimeException e) {
                WayFarMap.LOG.warn("Could not read " + file, e);
            }
        }
        dirty = false;
    }

    private void saveNow() {
        if (!dirty || worldDirectory == null) {
            return;
        }
        dirty = false;
        Saved saved = new Saved();
        for (Map.Entry<Integer, Map<Long, Seen>> dimension : seen.entrySet()) {
            for (Seen p : dimension.getValue()
                .values()) {
                Saved.Entry e = new Saved.Entry();
                e.dimension = dimension.getKey();
                e.x = p.x;
                e.y = p.y;
                e.z = p.z;
                e.range = p.range;
                e.active = p.active;
                saved.accessPoints.add(e);
            }
        }
        File file = new File(worldDirectory, FILE_NAME);
        String content = GSON.toJson(saved);
        WRITER.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                out.write(content);
            } catch (IOException e) {
                WayFarMap.LOG.warn("Could not write " + file, e);
                return;
            }
            if (!(file.delete() || !file.exists()) || !tmp.renameTo(file)) {
                WayFarMap.LOG.warn("Could not replace " + file);
            }
        });
    }

    // ---------------------------------------------------------------- what is shown

    /** One circle to draw: the server's data or the client's own. */
    private static final class Shown {

        final int x, y, z;
        final double range;
        final boolean known, active;

        Shown(int x, int y, int z, double range, boolean known, boolean active) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.range = range;
            this.known = known;
            this.active = active;
        }
    }

    private List<Shown> shown(int dimension) {
        List<Shown> result = new ArrayList<>();
        if (fromServer != null) {
            for (WirelessPoint p : fromServer) {
                if (p.dimension == dimension) {
                    result.add(new Shown(p.x, p.y, p.z, p.range, true, p.active));
                }
            }
            return result;
        }
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return Collections.emptyList();
        }
        double base = -1;
        for (Seen p : points.values()) {
            boolean known = p.range > 0;
            if (!known && base < 0) {
                base = Ae2WirelessClient.baseRange();
            }
            result.add(new Shown(p.x, p.y, p.z, known ? p.range : base, known, p.active));
        }
        return result;
    }

    // ---------------------------------------------------------------- drawing

    private static Shown hovered;
    /** The machine whose circle the mouse is in (the smallest one, across the layers), and that circle. */
    private static Shown areaHovered;
    private static double[] areaCircle;
    private static int hoveredDimension;

    /**
     * Draws the coverage of the dimension's access points into the map rectangle, centered on (centerX, centerZ) at
     * {@code scale} pixels per block, and remembers the access point under the mouse (world map only).
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap, int mouseX, int mouseY) {
        if (!minimap) {
            hovered = null;
            areaHovered = null;
            areaCircle = null;
        }
        List<Shown> points = INSTANCE.shown(dimension);
        if (points.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean here = mc.theWorld != null && mc.thePlayer != null && mc.theWorld.provider.dimensionId == dimension;
        double feet = here ? mc.thePlayer.boundingBox.minY : 0;
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;

        List<Shown> visible = new ArrayList<>();
        List<double[]> circles = new ArrayList<>();
        for (Shown p : points) {
            double sx = x + (p.x - left) * scale;
            double sy = y + (p.z - top) * scale;
            double full = p.range * scale;
            if (sx + full < x || sy + full < y || sx - full > x + width || sy - full > y + height) {
                continue;
            }
            double slice = here ? Math.sqrt(Math.max(0, p.range * p.range - sq(p.y - feet))) * scale : full;
            visible.add(p);
            circles.add(new double[] { sx, sy, full, slice });
            if (!minimap && CoverageHover.track(
                "ae2:" + dimension + ":" + p.x + ":" + p.y + ":" + p.z,
                sx,
                sy,
                Config.wirelessFullRange ? full : slice,
                mouseX,
                mouseY)) {
                areaHovered = p;
                areaCircle = new double[] { sx, sy, full, slice };
            }
            if (!minimap && Math.abs(mouseX - sx) <= 6 && Math.abs(mouseY - sy) <= 6) {
                hovered = p;
                hoveredDimension = dimension;
            }
        }
        if (visible.isEmpty()) {
            return;
        }

        List<CoverageDrawer.Circle> coverage = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            double[] c = circles.get(i);
            Shown p = visible.get(i);
            coverage.add(
                new CoverageDrawer.Circle(
                    c[0],
                    c[1],
                    c[2],
                    c[3],
                    color(p),
                    !p.known ? 32 : p.active ? 48 : 40,
                    !p.known ? 0 : p.active ? 2 : 1));
        }
        CoverageDrawer.draw(coverage, minimap, Config.wirelessFullRange);
        if (!minimap && areaCircle != null) {
            CoverageDrawer.highlight(
                areaCircle[0],
                areaCircle[1],
                areaCircle[2],
                areaCircle[3],
                color(areaHovered),
                Config.wirelessFullRange,
                minimap);
        }

        for (int i = 0; i < visible.size(); i++) {
            double[] c = circles.get(i);
            Shown p = visible.get(i);
            CoverageDrawer.mark(
                c[0],
                c[1],
                Icons.WIRELESS,
                p.known ? ACTIVE_COLOR : UNKNOWN_COLOR,
                !p.active,
                !minimap && (p == hovered || p == areaHovered),
                minimap);
        }
    }

    private static int color(Shown p) {
        return !p.known ? UNKNOWN_COLOR : p.active ? ACTIVE_COLOR : INACTIVE_COLOR;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---------------------------------------------------------------- tooltip

    /**
     * Tooltip of the access point under the mouse on the world map, or null: of its mark, or with {@code area} of its
     * circle (only what it is).
     */
    public static List<String> getHoveredTooltip(boolean area) {
        Shown p = area ? areaHovered : hovered;
        if (area || p == null) {
            // In its circle, not on its mark: only what it is.
            return p == null ? null : Collections.singletonList("§b" + Lang.format("wayfarmap.wireless.title"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("§b" + Lang.format("wayfarmap.wireless.title"));
        lines.add("§7" + p.x + ", " + p.y + ", " + p.z);
        if (p.known) {
            lines.add("§7" + Lang.format("wayfarmap.wireless.range", blocks(p.range)));
        } else {
            lines.add("§7" + Lang.format("wayfarmap.wireless.range_unknown", blocks(p.range)));
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.thePlayer != null && mc.theWorld.provider.dimensionId == hoveredDimension) {
            double dy = p.y - mc.thePlayer.boundingBox.minY;
            double slice = Math.sqrt(Math.max(0, p.range * p.range - dy * dy));
            lines.add(
                "§7" + (slice > 0 ? Lang.format("wayfarmap.wireless.at_height", blocks(slice))
                    : Lang.format("wayfarmap.wireless.out_of_height")));
        }
        lines.add(
            p.active ? "§a" + Lang.format("wayfarmap.wireless.active")
                : "§c" + Lang.format("wayfarmap.wireless.inactive"));
        if (INSTANCE.infinite) {
            lines.add("§6" + Lang.format("wayfarmap.wireless.infinite"));
        }
        if (INSTANCE.fromServer == null) {
            lines.add("§8" + Lang.format("wayfarmap.wireless.local"));
        }
        return lines;
    }

    private static String blocks(double range) {
        return Math.abs(range - Math.rint(range)) < 0.05 ? String.valueOf((long) Math.rint(range))
            : String.format(Locale.ROOT, "%.1f", range);
    }
}
