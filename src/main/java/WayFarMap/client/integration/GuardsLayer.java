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
import net.minecraft.util.MathHelper;
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
import WayFarMap.share.MachinePoint;
import WayFarMap.share.MobGuards;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Where monsters don't spawn: GregTech's Monster Repellator and Extra Utilities' Magnum Torch and Chandelier (see
 * {@link MobGuards} for their shapes), drawn like the AE2 coverage ({@link CoverageDrawer}): the slice at the
 * player's height and a ring of the full range.
 * <p>
 * The client finds them in loaded chunks itself; only those it has seen are shown, kept in {@code guards.json} of the
 * world's map folder. Everything about a torch is known to the client. A repellator's energy isn't: until a server
 * with this mod tells it ({@code share.MachineServer}), its circle is the range it has without power, grayed.
 */
public final class GuardsLayer {

    public static final GuardsLayer INSTANCE = new GuardsLayer();

    private static final int REPELLATOR_COLOR = 0x76FF03, TORCH_COLOR = 0xFF6D00, CHANDELIER_COLOR = 0xFFEA00;
    private static final int INACTIVE_COLOR = 0xFF3030;
    private static final int UNKNOWN_COLOR = 0xB0BEC5;
    private static final int SCAN_TICKS = 20;
    private static final long SAVE_DELAY_MS = 5000;
    /** How long the server's word about a repellator stands over what the client sees. */
    private static final long SERVER_FRESH_MS = 5000;
    private static final String FILE_NAME = "guards.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap mob protection saver");
        thread.setDaemon(true);
        return thread;
    });

    private GuardsLayer() {}

    /**
     * A guard the client saw. A repellator: its tier, switched on ({@code active}) and powered (-1 not known, 0 or
     * 1, from the server). A torch: the radii of its ellipsoid.
     */
    private static final class Seen {

        final int kind, x, y, z;
        int tier, powered = -1;
        double horizontal, vertical;
        boolean active = true;
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
    private int tick;

    /** From the network thread. */
    public void receive(ShareNetwork.MachineList message) {
        inbox.add(message);
    }

    // ---------------------------------------------------------------- client tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Mods.isGuardsAvailable()) {
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
            if (++tick >= SCAN_TICKS) {
                tick = 0;
                scan(mc, mc.theWorld.provider.dimensionId);
                ask(mc);
            }
        } catch (Throwable t) {
            Mods.fail(Mods.Addon.GUARDS, t);
        }
        if (dirty && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
            saveNow();
        }
    }

    /** Adds the guards in loaded chunks and drops those whose loaded chunk no longer has them. */
    private void scan(Minecraft mc, int dimension) {
        World world = mc.theWorld;
        int radius = Math.min(32, Math.max(12, mc.gameSettings.renderDistanceChunks) + 2);
        int chunkX = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int chunkZ = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        Set<Long> present = new HashSet<>();
        for (int cx = chunkX - radius; cx <= chunkX + radius; cx++) {
            for (int cz = chunkZ - radius; cz <= chunkZ + radius; cz++) {
                Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
                if (chunk instanceof EmptyChunk || !chunk.isChunkLoaded) {
                    continue;
                }
                for (Object o : chunk.chunkTileEntityMap.values()) {
                    TileEntity tile = (TileEntity) o;
                    if (!tile.isInvalid() && see(dimension, tile)) {
                        present.add(pack(tile.xCoord, tile.yCoord, tile.zCoord));
                    }
                }
            }
        }
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return;
        }
        for (Iterator<Map.Entry<Long, Seen>> it = points.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<Long, Seen> entry = it.next();
            Seen point = entry.getValue();
            if (present.contains(entry.getKey()) || !isLoaded(world, point.x, point.z)) {
                continue;
            }
            TileEntity tile = world.getTileEntity(point.x, point.y, point.z);
            if (kindAt(tile) != point.kind
                && !(point.kind == MobGuards.KIND_REPELLATOR && Chargers.isGtMachinePending(tile))) {
                it.remove();
                markDirty();
            }
        }
    }

    /** Records the tile entity if it is a guard; false if it isn't one. */
    private boolean see(int dimension, TileEntity tile) {
        Object repellator = MobGuards.repellator(tile);
        int kind = repellator != null ? MobGuards.KIND_REPELLATOR : MobGuards.torchKind(tile);
        if (kind < 0) {
            return false;
        }
        Seen point = seenAt(dimension, kind, tile.xCoord, tile.yCoord, tile.zCoord);
        if (repellator != null) {
            if (System.currentTimeMillis() - point.serverAt < SERVER_FRESH_MS) {
                return true;
            }
            int tier = MobGuards.tier(repellator);
            boolean active = Chargers.gtAllowedToWork(tile);
            if (point.tier != tier || point.active != active) {
                if (point.tier != tier) {
                    point.powered = -1;
                }
                point.tier = tier;
                point.active = active;
                markDirty();
            }
        } else {
            double[] radii = MobGuards.torchRadii(tile);
            if (point.horizontal != radii[0] || point.vertical != radii[1]) {
                point.horizontal = radii[0];
                point.vertical = radii[1];
                markDirty();
            }
        }
        return true;
    }

    private static int kindAt(TileEntity tile) {
        return MobGuards.repellator(tile) != null ? MobGuards.KIND_REPELLATOR : MobGuards.torchKind(tile);
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

    /** What the server says about the repellators seen; it is told only about those. */
    private void apply(ShareNetwork.MachineList message) {
        boolean first = !serverAnswers;
        serverAnswers = true;
        long now = System.currentTimeMillis();
        for (MachinePoint p : message.points) {
            Seen point = seenAt(p.dimension, MobGuards.KIND_REPELLATOR, p.x, p.y, p.z);
            int powered = p.active ? p.mode : point.powered;
            if (point.tier != p.tier || point.active != p.active || point.powered != powered) {
                point.tier = p.tier;
                point.active = p.active;
                point.powered = powered;
                markDirty();
            }
            point.serverAt = now;
        }
        if (first) {
            ask(Minecraft.getMinecraft());
        }
    }

    /** Tells the server where the repellators seen are, the current dimension's first, when that changed. */
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
                    if (p.kind == MobGuards.KIND_REPELLATOR && positions.size() < ShareNetwork.MachineAsk.MAX) {
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
            ShareNetwork.sendToServer(new ShareNetwork.MachineAsk(ShareNetwork.MachineList.GUARDS, positions));
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
     * The file: {@code {"guards": [{kind, dimension, x, y, z, tier, powered, active, horizontal, vertical}, ...]}},
     * kind "repellator", "magnum_torch" or "chandelier", powered -1 not known.
     */
    private static final class Saved {

        List<Entry> guards = new ArrayList<>();

        static final class Entry {

            String kind;
            int dimension, x, y, z, tier;
            int powered = -1;
            boolean active = true;
            double horizontal, vertical;
        }
    }

    private static String kindName(int kind) {
        return kind == MobGuards.KIND_MAGNUM_TORCH ? "magnum_torch"
            : kind == MobGuards.KIND_CHANDELIER ? "chandelier" : "repellator";
    }

    private void load() {
        seen.clear();
        File file = new File(worldDirectory, FILE_NAME);
        if (file.isFile()) {
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Saved saved = GSON.fromJson(in, Saved.class);
                if (saved != null && saved.guards != null) {
                    for (Saved.Entry e : saved.guards) {
                        if (e == null) {
                            continue;
                        }
                        int kind = "magnum_torch".equals(e.kind) ? MobGuards.KIND_MAGNUM_TORCH
                            : "chandelier".equals(e.kind) ? MobGuards.KIND_CHANDELIER : MobGuards.KIND_REPELLATOR;
                        Seen point = seenAt(e.dimension, kind, e.x, e.y, e.z);
                        point.tier = e.tier;
                        point.powered = e.powered == 0 || e.powered == 1 ? e.powered : -1;
                        point.active = e.active;
                        point.horizontal = Math.max(0, e.horizontal);
                        point.vertical = Math.max(0, e.vertical);
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
                e.kind = kindName(p.kind);
                e.dimension = dimension.getKey();
                e.x = p.x;
                e.y = p.y;
                e.z = p.z;
                e.tier = p.tier;
                e.powered = p.powered;
                e.active = p.active;
                e.horizontal = p.horizontal;
                e.vertical = p.vertical;
                saved.guards.add(e);
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
     * One guard to draw: an ellipsoid of the radii around the block's centre (a sphere when they are equal).
     * {@code known}: a repellator's power is known.
     */
    private static final class Shown {

        final int kind, x, y, z, tier, powered;
        final double horizontal, vertical;
        final boolean known, active;

        Shown(Seen p) {
            kind = p.kind;
            x = p.x;
            y = p.y;
            z = p.z;
            tier = p.tier;
            powered = p.powered;
            active = p.active;
            if (p.kind == MobGuards.KIND_REPELLATOR) {
                known = p.powered >= 0;
                // gtnhlib's check adds half a block to the radius.
                horizontal = vertical = MobGuards.repellatorRange(p.tier, p.powered == 1) + 0.5;
            } else {
                known = true;
                horizontal = p.horizontal;
                vertical = p.vertical;
            }
        }

        /** The radius of the slice {@code dy} above or below the centre, 0 out of reach. */
        double slice(double dy) {
            if (vertical <= 0 || Math.abs(dy) >= vertical) {
                return 0;
            }
            return horizontal * Math.sqrt(1 - dy * dy / (vertical * vertical));
        }
    }

    private List<Shown> shown(int dimension) {
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return Collections.emptyList();
        }
        List<Shown> result = new ArrayList<>();
        for (Seen p : points.values()) {
            result.add(new Shown(p));
        }
        return result;
    }

    // ---------------------------------------------------------------- drawing

    private static Shown hovered;
    /** The guard whose circle the mouse is in (the smallest one, across the layers), and that circle. */
    private static Shown areaHovered;
    private static double[] areaCircle;
    private static int hoveredDimension;

    /**
     * Draws the guards of the dimension into the map rectangle, centered on (centerX, centerZ) at {@code scale}
     * pixels per block, and remembers the guard under the mouse (world map only).
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

        List<CoverageDrawer.Circle> coverage = new ArrayList<>();
        List<Shown> marked = new ArrayList<>();
        List<double[]> marks = new ArrayList<>();
        for (Shown p : points) {
            double sx = x + (p.x + 0.5 - left) * scale;
            double sy = y + (p.z + 0.5 - top) * scale;
            double full = p.horizontal * scale;
            if (sx + full + 4 < x || sy + full + 4 < y || sx - full - 4 > x + width || sy - full - 4 > y + height) {
                continue;
            }
            if (full > 0) {
                double slice = here ? p.slice(p.y + 0.5 - feet) * scale : full;
                coverage.add(new CoverageDrawer.Circle(sx, sy, full, slice, color(p), fill(p), priority(p)));
                if (!minimap && CoverageHover.track(
                    "guard:" + dimension + ":" + p.x + ":" + p.y + ":" + p.z,
                    sx,
                    sy,
                    Config.guardsFullRange ? full : slice,
                    mouseX,
                    mouseY)) {
                    areaHovered = p;
                    areaCircle = new double[] { sx, sy, full, slice };
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
        CoverageDrawer.draw(coverage, minimap, Config.guardsFullRange);
        if (!minimap && areaCircle != null) {
            CoverageDrawer.highlight(
                areaCircle[0],
                areaCircle[1],
                areaCircle[2],
                areaCircle[3],
                color(areaHovered),
                Config.guardsFullRange,
                minimap);
        }

        for (int i = 0; i < marked.size(); i++) {
            double[] c = marks.get(i);
            Shown p = marked.get(i);
            CoverageDrawer.mark(
                c[0],
                c[1],
                icon(p.kind),
                p.known ? kindColor(p.kind) : UNKNOWN_COLOR,
                !p.active || p.powered == 0,
                !minimap && (p == hovered || p == areaHovered),
                minimap);
        }
    }

    public static String[] icon(int kind) {
        return kind == MobGuards.KIND_MAGNUM_TORCH ? Icons.MAGNUM_TORCH
            : kind == MobGuards.KIND_CHANDELIER ? Icons.CHANDELIER : Icons.REPELLATOR;
    }

    private static int kindColor(int kind) {
        return kind == MobGuards.KIND_MAGNUM_TORCH ? TORCH_COLOR
            : kind == MobGuards.KIND_CHANDELIER ? CHANDELIER_COLOR : REPELLATOR_COLOR;
    }

    private static int color(Shown p) {
        return !p.active ? INACTIVE_COLOR : !p.known ? UNKNOWN_COLOR : kindColor(p.kind);
    }

    private static int fill(Shown p) {
        return !p.active ? 40 : !p.known ? 32 : 48;
    }

    /** One layer per color: working ones over unknown and switched off ones. */
    private static int priority(Shown p) {
        int state = !p.active ? 0 : !p.known ? 1 : 2;
        return state * 3 + p.kind - MobGuards.KIND_REPELLATOR;
    }

    // ---------------------------------------------------------------- tooltip

    private static String title(Shown p) {
        if (p.kind == MobGuards.KIND_MAGNUM_TORCH) {
            return "§6" + Lang.format("wayfarmap.guards.magnum_torch");
        }
        if (p.kind == MobGuards.KIND_CHANDELIER) {
            return "§e" + Lang.format("wayfarmap.guards.chandelier");
        }
        return "§a" + Lang.format("wayfarmap.guards.repellator", tierName(p.tier));
    }

    /**
     * Tooltip of the guard under the mouse on the world map, or null: of its mark, or with {@code area} of its circle
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
        if (p.horizontal == p.vertical) {
            // A repellator's own range, without the half block the check adds.
            double range = p.kind == MobGuards.KIND_REPELLATOR ? p.horizontal - 0.5 : p.horizontal;
            lines.add("§7" + Lang.format("wayfarmap.wireless.range", blocks(range)));
        } else {
            lines.add("§7" + Lang.format("wayfarmap.guards.range_ellipsoid", blocks(p.horizontal), blocks(p.vertical)));
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null && mc.thePlayer != null && mc.theWorld.provider.dimensionId == hoveredDimension) {
            double slice = p.slice(p.y + 0.5 - mc.thePlayer.boundingBox.minY);
            lines.add(
                "§7" + (slice > 0 ? Lang.format("wayfarmap.wireless.at_height", blocks(slice))
                    : Lang.format("wayfarmap.wireless.out_of_height")));
        }
        if (p.kind == MobGuards.KIND_REPELLATOR) {
            String powered = blocks(MobGuards.repellatorRange(p.tier, true));
            if (!p.active) {
                lines.add("§c" + Lang.format("wayfarmap.guards.switched_off"));
            } else if (p.powered == 1) {
                lines.add("§a" + Lang.format("wayfarmap.guards.powered"));
            } else if (p.powered == 0) {
                lines.add("§c" + Lang.format("wayfarmap.guards.unpowered", powered));
            } else {
                lines.add("§7" + Lang.format("wayfarmap.guards.power_unknown", powered));
            }
        }
        lines.add("§8" + Lang.format("wayfarmap.guards.monsters_only"));
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
