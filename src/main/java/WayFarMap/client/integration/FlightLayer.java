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
import WayFarMap.share.Chargers;
import WayFarMap.share.FlightZones;
import WayFarMap.share.GtMeta;
import WayFarMap.share.MachinePoint;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Where players can fly: GregTech's MagLev Pylon (a square: its cube, whole at the player's height or not there) and
 * Automagy's Thaumostatic Spire (a circle at any height), see {@link FlightZones}; drawn like the AE2 coverage
 * ({@link CoverageDrawer}).
 * <p>
 * The client finds them in loaded chunks itself; only those it has seen are shown, kept in {@code flight.json} of the
 * world's map folder. A pylon's energy isn't known to the client: until a server with this mod tells it
 * ({@code share.MachineServer}), its square is the range it has without power, grayed. A spire is known to the
 * client but for its Aer boost, which the client counts down itself from what was last sent.
 */
public final class FlightLayer implements MachineScanner.Layer {

    public static final FlightLayer INSTANCE = new FlightLayer();

    private static final int MAGLEV_COLOR = 0xE040FB, SPIRE_COLOR = 0x00E5FF;
    private static final int INACTIVE_COLOR = 0xFF3030;
    private static final int UNKNOWN_COLOR = 0xB0BEC5;
    private static final long SAVE_DELAY_MS = 5000;
    /** How long the server's word about a machine stands over what the client sees. */
    private static final long SERVER_FRESH_MS = 5000;
    private static final long TICK_MS = 50;
    private static final String FILE_NAME = "flight.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap flight saver");
        thread.setDaemon(true);
        return thread;
    });

    private FlightLayer() {}

    /**
     * A machine the client saw. A pylon: its tier, switched on ({@code active}) and powered (-1 not known, 0 or 1,
     * from the server). A spire: its pylons as {@code tier}, built ({@code active}), refuelling ({@code working}) and
     * until when its Aer boost lasts.
     */
    private static final class Seen {

        final int kind, x, y, z;
        int tier, powered = -1;
        boolean active = true, working = true;
        long boostUntil;
        /** The boost ticks the controller last sent; a new value restarts the countdown. */
        int lastBoost = -1;
        /** When the server last told about it. */
        long serverAt;

        Seen(int kind, int x, int y, int z) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Queue<ShareNetwork.MachineList> inbox = new ConcurrentLinkedQueue<>();
    /** The server has the mod and answers; what it was last asked. */
    private boolean serverAnswers;
    private String asked;
    private final Map<Integer, Map<Long, Seen>> seen = new HashMap<>();
    private File worldDirectory;
    private boolean inWorld, dirty;
    private long dirtySince;

    /** From the network thread. */
    public void receive(ShareNetwork.MachineList message) {
        inbox.add(message);
    }

    // ---------------------------------------------------------------- client tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Mods.isFlightAvailable()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            if (inWorld) {
                inWorld = false;
                saveNow();
                seen.clear();
                serverAnswers = false;
                asked = null;
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
        try {
            ShareNetwork.MachineList message;
            while ((message = inbox.poll()) != null) {
                apply(message);
            }
        } catch (Throwable t) {
            Mods.fail(Mods.Addon.FLIGHT, t);
        }
        if (dirty && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
            saveNow();
        }
    }

    // ---------------------------------------------------------------- MachineScanner

    private final Set<Long> present = new HashSet<>();
    /** Spire controllers met in the sweep: read at its end, as the spire on top is looked up in the world. */
    private final List<TileEntity> controllers = new ArrayList<>();

    @Override
    public Mods.Addon addon() {
        return Mods.Addon.FLIGHT;
    }

    @Override
    public boolean scans() {
        return inWorld && Mods.isFlightAvailable();
    }

    @Override
    public void sweepStart() {
        present.clear();
        controllers.clear();
    }

    @Override
    public void see(int dimension, TileEntity tile, Object gtMachine) {
        Object pylon = FlightZones.magLevOf(gtMachine);
        if (pylon != null) {
            present.add(pack(tile.xCoord, tile.yCoord, tile.zCoord));
            Seen point = seenAt(dimension, FlightZones.KIND_MAGLEV, tile.xCoord, tile.yCoord, tile.zCoord);
            if (System.currentTimeMillis() - point.serverAt < SERVER_FRESH_MS) {
                return;
            }
            int tier = FlightZones.magLevTier(pylon);
            boolean active = Chargers.gtAllowedToWork(tile);
            if (point.tier != tier || point.active != active) {
                if (point.tier != tier) {
                    point.powered = -1;
                }
                point.tier = tier;
                point.active = active;
                markDirty();
            }
        } else if (FlightZones.isController(tile)) {
            present.add(pack(tile.xCoord, tile.yCoord, tile.zCoord));
            controllers.add(tile);
        }
    }

    /**
     * Reads the spire controllers met, drops the machines whose loaded chunk no longer has them, and asks the server
     * about those seen.
     */
    @Override
    public void sweepEnd(World world, int dimension) {
        long now = System.currentTimeMillis();
        for (TileEntity tile : controllers) {
            if (!tile.isInvalid()) {
                readSpire(dimension, tile, now);
            }
        }
        controllers.clear();
        Map<Long, Seen> points = seen.get(dimension);
        if (points != null) {
            for (Iterator<Map.Entry<Long, Seen>> it = points.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<Long, Seen> entry = it.next();
                Seen point = entry.getValue();
                if (present.contains(entry.getKey()) || !isLoaded(world, point.x, point.z)) {
                    continue;
                }
                TileEntity tile = world.getTileEntity(point.x, point.y, point.z);
                if (kindAt(tile) != point.kind && !(point.kind == FlightZones.KIND_MAGLEV && GtMeta.isPending(tile))) {
                    it.remove();
                    markDirty();
                }
            }
        }
        ask(Minecraft.getMinecraft());
    }

    private void readSpire(int dimension, TileEntity tile, long now) {
        Seen point = seenAt(dimension, FlightZones.KIND_SPIRE, tile.xCoord, tile.yCoord, tile.zCoord);
        int boost = FlightZones.boostTicks(tile);
        if (boost != point.lastBoost) {
            point.lastBoost = boost;
            point.boostUntil = boost > 0 ? now + boost * TICK_MS : 0;
            markDirty();
        }
        if (now - point.serverAt < SERVER_FRESH_MS) {
            return;
        }
        int pylons = FlightZones.pylons(tile);
        boolean active = FlightZones.controllerActive(tile);
        boolean working = active && FlightZones.spirePowered(tile) && FlightZones.potentia(tile) > 0;
        if (point.tier != pylons || point.active != active || point.working != working) {
            point.tier = pylons;
            point.active = active;
            point.working = working;
            markDirty();
        }
    }

    private static int kindAt(TileEntity tile) {
        return FlightZones.magLev(tile) != null ? FlightZones.KIND_MAGLEV
            : FlightZones.isController(tile) ? FlightZones.KIND_SPIRE : -1;
    }

    private static boolean isLoaded(World world, int x, int z) {
        Chunk chunk = world.getChunkFromChunkCoords(x >> 4, z >> 4);
        return !(chunk instanceof EmptyChunk) && chunk.isChunkLoaded;
    }

    private Seen seenAt(int dimension, int kind, int x, int y, int z) {
        Map<Long, Seen> points = seen.computeIfAbsent(dimension, d -> new HashMap<>());
        long key = pack(x, y, z);
        Seen point = points.get(key);
        if (point == null || point.kind != kind) {
            point = new Seen(kind, x, y, z);
            points.put(key, point);
            markDirty();
        }
        return point;
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
    }

    // ---------------------------------------------------------------- the server

    /** What the server says about the machines seen; it is told only about those. */
    private void apply(ShareNetwork.MachineList message) {
        boolean first = !serverAnswers;
        serverAnswers = true;
        long now = System.currentTimeMillis();
        for (MachinePoint p : message.points) {
            Seen point = seenAt(p.dimension, p.kind, p.x, p.y, p.z);
            if (p.kind == FlightZones.KIND_MAGLEV) {
                int powered = p.active ? p.mode : point.powered;
                if (point.tier != p.tier || point.active != p.active || point.powered != powered) {
                    point.tier = p.tier;
                    point.active = p.active;
                    point.powered = powered;
                    markDirty();
                }
            } else {
                // Answers come every couple of seconds while it lasts.
                point.boostUntil = p.mode == 1 ? Math.max(point.boostUntil, now + SERVER_FRESH_MS) : 0;
                if (point.tier != p.tier || point.working != p.active) {
                    point.tier = p.tier;
                    point.working = p.active;
                    markDirty();
                }
            }
            point.serverAt = now;
        }
        if (first) {
            ask(Minecraft.getMinecraft());
        }
    }

    /** Tells the server where the machines seen are, the current dimension's first, when that changed. */
    private void ask(Minecraft mc) {
        if (!serverAnswers || mc.theWorld == null) {
            return;
        }
        int current = mc.theWorld.provider.dimensionId;
        List<int[]> positions = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (boolean here : new boolean[] { true, false }) {
            for (Map.Entry<Integer, Map<Long, Seen>> dimension : seen.entrySet()) {
                if ((dimension.getKey() == current) != here) {
                    continue;
                }
                for (Seen p : dimension.getValue()
                    .values()) {
                    if (positions.size() < ShareNetwork.MachineAsk.MAX) {
                        positions.add(new int[] { dimension.getKey(), p.x, p.y, p.z });
                        key.append(dimension.getKey())
                            .append(',')
                            .append(p.x)
                            .append(',')
                            .append(p.y)
                            .append(',')
                            .append(p.z)
                            .append(';');
                    }
                }
            }
        }
        if (!key.toString()
            .equals(asked)) {
            asked = key.toString();
            ShareNetwork.sendToServer(new ShareNetwork.MachineAsk(ShareNetwork.MachineList.FLIGHT, positions));
        }
    }

    // ---------------------------------------------------------------- storage

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }

    /**
     * The file: {@code {"machines": [{kind, dimension, x, y, z, tier, powered, active, working}, ...]}}, kind "maglev"
     * or "spire", tier a spire's pylons, powered -1 not known. The Aer boost isn't kept: it runs out meanwhile.
     */
    private static final class Saved {

        List<Entry> machines = new ArrayList<>();

        static final class Entry {

            String kind;
            int dimension, x, y, z, tier;
            int powered = -1;
            boolean active = true, working = true;
        }
    }

    private void load() {
        seen.clear();
        File file = new File(worldDirectory, FILE_NAME);
        if (file.isFile()) {
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Saved saved = GSON.fromJson(in, Saved.class);
                if (saved != null && saved.machines != null) {
                    for (Saved.Entry e : saved.machines) {
                        if (e == null) {
                            continue;
                        }
                        int kind = "spire".equals(e.kind) ? FlightZones.KIND_SPIRE : FlightZones.KIND_MAGLEV;
                        Seen point = seenAt(e.dimension, kind, e.x, e.y, e.z);
                        point.tier = e.tier;
                        point.powered = e.powered == 0 || e.powered == 1 ? e.powered : -1;
                        point.active = e.active;
                        point.working = e.working;
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
                e.kind = p.kind == FlightZones.KIND_SPIRE ? "spire" : "maglev";
                e.dimension = dimension.getKey();
                e.x = p.x;
                e.y = p.y;
                e.z = p.z;
                e.tier = p.tier;
                e.powered = p.powered;
                e.active = p.active;
                e.working = p.working;
                saved.machines.add(e);
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

    /**
     * One machine to draw around its block's centre. A pylon: a cube of half side {@code reach} ({@code known}: its
     * power is known). A spire: a vertical cylinder of radius {@code reach}.
     */
    private static final class Shown {

        final int kind, x, y, z, tier, powered;
        final double reach;
        final boolean known, active, working, boosted;

        Shown(Seen p, long now) {
            kind = p.kind;
            x = p.x;
            y = p.y;
            z = p.z;
            tier = p.tier;
            powered = p.powered;
            active = p.active;
            if (p.kind == FlightZones.KIND_MAGLEV) {
                known = p.powered >= 0;
                working = p.active && p.powered != 0;
                boosted = false;
                // gtnhlib's cube reaches half a block past the range, from the block's centre.
                reach = FlightZones.magLevRange(p.tier, p.powered == 1) + 0.5;
            } else {
                known = true;
                working = p.active && p.working;
                boosted = now < p.boostUntil;
                reach = p.active ? FlightZones.spireRange(p.tier, boosted) : 0;
            }
        }

        boolean square() {
            return kind == FlightZones.KIND_MAGLEV;
        }

        /** The half side or radius {@code dy} above or below the centre, 0 out of reach. */
        double slice(double dy) {
            return square() && Math.abs(dy) >= reach ? 0 : reach;
        }
    }

    private List<Shown> shown(int dimension) {
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        List<Shown> result = new ArrayList<>();
        for (Seen p : points.values()) {
            result.add(new Shown(p, now));
        }
        return result;
    }

    // ---------------------------------------------------------------- drawing

    private static Shown hovered;
    /** The machine whose zone the mouse is in (the smallest one, across the layers), and that zone. */
    private static Shown areaHovered;
    private static double[] areaZone;
    private static int hoveredDimension;

    /**
     * Draws the flight zones of the dimension into the map rectangle, centered on (centerX, centerZ) at {@code scale}
     * pixels per block, and remembers the machine under the mouse (world map only).
     */
    public static void draw(int dimension, double centerX, double centerZ, double scale, int x, int y, int width,
        int height, boolean minimap, int mouseX, int mouseY) {
        if (!minimap) {
            hovered = null;
            areaHovered = null;
            areaZone = null;
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

        List<CoverageDrawer.Circle> coverage = new ArrayList<>();
        List<Shown> marked = new ArrayList<>();
        List<double[]> marks = new ArrayList<>();
        for (Shown p : points) {
            double sx = x + (p.x + 0.5 - left) * scale;
            double sy = y + (p.z + 0.5 - top) * scale;
            double full = p.reach * scale;
            if (sx + full + 4 < x || sy + full + 4 < y || sx - full - 4 > x + width || sy - full - 4 > y + height) {
                continue;
            }
            if (full > 0) {
                double slice = here ? p.slice(p.y + 0.5 - feet) * scale : full;
                coverage
                    .add(new CoverageDrawer.Circle(sx, sy, full, slice, color(p), fill(p), priority(p), p.square()));
                String key = "flight:" + dimension + ":" + p.x + ":" + p.y + ":" + p.z;
                double zone = Config.flightFullRange ? full : slice;
                if (!minimap && (p.square() ? CoverageHover.trackSquare(key, sx, sy, zone, mouseX, mouseY)
                    : CoverageHover.track(key, sx, sy, zone, mouseX, mouseY))) {
                    areaHovered = p;
                    areaZone = new double[] { sx, sy, full, slice };
                }
            }
            marked.add(p);
            marks.add(new double[] { sx, sy });
            if (!minimap && Math.abs(mouseX - sx) <= 6 && Math.abs(mouseY - sy) <= 6) {
                hovered = p;
                hoveredDimension = dimension;
            }
        }
        if (marked.isEmpty()) {
            return;
        }
        CoverageDrawer.draw(coverage, minimap, Config.flightFullRange);
        if (!minimap && areaZone != null) {
            CoverageDrawer.highlight(
                areaZone[0],
                areaZone[1],
                areaZone[2],
                areaZone[3],
                color(areaHovered),
                Config.flightFullRange,
                minimap,
                areaHovered.square());
        }

        for (int i = 0; i < marked.size(); i++) {
            double[] c = marks.get(i);
            Shown p = marked.get(i);
            CoverageDrawer.mark(
                c[0],
                c[1],
                icon(p.kind),
                p.known ? kindColor(p.kind) : UNKNOWN_COLOR,
                !p.working,
                !minimap && (p == hovered || p == areaHovered),
                minimap);
        }
    }

    public static String[] icon(int kind) {
        return kind == FlightZones.KIND_SPIRE ? Icons.SPIRE : Icons.MAGLEV;
    }

    private static int kindColor(int kind) {
        return kind == FlightZones.KIND_SPIRE ? SPIRE_COLOR : MAGLEV_COLOR;
    }

    private static int color(Shown p) {
        return !p.working && (p.kind == FlightZones.KIND_SPIRE || !p.active) ? INACTIVE_COLOR
            : !p.known ? UNKNOWN_COLOR : kindColor(p.kind);
    }

    private static int fill(Shown p) {
        return color(p) == INACTIVE_COLOR ? 40 : !p.known ? 32 : 48;
    }

    /** One layer per color: working ones over unknown and broken ones. */
    private static int priority(Shown p) {
        int c = color(p);
        int state = c == INACTIVE_COLOR ? 0 : c == UNKNOWN_COLOR ? 1 : 2;
        return state * 2 + p.kind - FlightZones.KIND_MAGLEV;
    }

    // ---------------------------------------------------------------- tooltip

    private static String title(Shown p) {
        if (p.kind == FlightZones.KIND_SPIRE) {
            return "§b" + Lang.format("wayfarmap.flight.spire");
        }
        return "§d" + Lang.format("wayfarmap.flight.maglev", tierName(p.tier));
    }

    /**
     * Tooltip of the machine under the mouse on the world map, or null: of its mark, or with {@code area} of its zone
     * (only what it is).
     */
    public static List<String> getHoveredTooltip(boolean area) {
        Shown p = area ? areaHovered : hovered;
        if (area || p == null) {
            return p == null ? null : Collections.singletonList(title(p));
        }
        List<String> lines = new ArrayList<>();
        lines.add(title(p));
        lines.add("§7" + p.x + ", " + p.y + ", " + p.z);
        Minecraft mc = Minecraft.getMinecraft();
        boolean here = mc.theWorld != null && mc.thePlayer != null
            && mc.theWorld.provider.dimensionId == hoveredDimension;
        if (p.square()) {
            int range = FlightZones.magLevRange(p.tier, p.powered == 1);
            lines.add("§7" + Lang.format("wayfarmap.flight.range_cube", blocks(range)));
            if (here) {
                lines.add(
                    "§7" + Lang.format(
                        p.slice(p.y + 0.5 - mc.thePlayer.boundingBox.minY) > 0 ? "wayfarmap.flight.in_height"
                            : "wayfarmap.wireless.out_of_height"));
            }
            String powered = blocks(FlightZones.magLevRange(p.tier, true));
            if (!p.active) {
                lines.add("§c" + Lang.format("wayfarmap.flight.switched_off"));
            } else if (p.powered == 1) {
                lines.add("§a" + Lang.format("wayfarmap.guards.powered"));
            } else if (p.powered == 0) {
                lines.add("§c" + Lang.format("wayfarmap.guards.unpowered", powered));
            } else {
                lines.add("§7" + Lang.format("wayfarmap.guards.power_unknown", powered));
            }
            lines.add("§8" + Lang.format("wayfarmap.flight.maglev_note"));
        } else {
            if (!p.active) {
                lines.add("§c" + Lang.format("wayfarmap.flight.spire_not_built"));
            } else {
                lines.add("§7" + Lang.format("wayfarmap.flight.range_cylinder", blocks(p.reach)));
                lines.add("§7" + Lang.format("wayfarmap.flight.pylons", p.tier));
                lines.add(
                    p.boosted
                        ? "§a" + Lang.format("wayfarmap.flight.boosted", blocks(FlightZones.spireRange(p.tier, false)))
                        : "§7" + Lang
                            .format("wayfarmap.flight.not_boosted", blocks(FlightZones.spireRange(p.tier, true))));
                if (!p.working) {
                    lines.add("§c" + Lang.format("wayfarmap.flight.spire_idle"));
                }
            }
            lines.add("§8" + Lang.format("wayfarmap.flight.spire_note"));
        }
        return lines;
    }

    private static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    private static String tierName(int tier) {
        return tier >= 0 && tier < TIERS.length ? TIERS[tier] : String.valueOf(tier);
    }

    private static String blocks(double range) {
        return Math.abs(range - Math.rint(range)) < 0.05 ? String.format(Locale.ROOT, "%,d", (long) Math.rint(range))
            .replace(',', ' ') : String.format(Locale.ROOT, "%.1f", range);
    }
}
