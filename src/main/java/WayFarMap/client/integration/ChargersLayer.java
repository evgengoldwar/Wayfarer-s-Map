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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.oredict.OreDictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.map.MapManager;
import WayFarMap.share.Chargers;
import WayFarMap.share.GtMeta;
import WayFarMap.share.MachinePoint;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Wireless chargers of GregTech and EnderIO: a circle around each where it charges the player's items, drawn like the
 * AE2 coverage ({@link CoverageDrawer}): the slice at the player's height and a ring of the full range.
 * <p>
 * The client finds the chargers in loaded chunks itself; only those it has seen are shown. EnderIO's range is the
 * client's own config value; a GregTech charger's mode is known to the server only, so the circle is the range reached
 * in every mode ({@code tier * 10}), grayed, until the player right clicks the charger: its answer in
 * the chat holds its range, and the numbers (not the words, which are in the server's language) tell the mode. A
 * click with a screwdriver in hand turns a known mode to the next one, in the order GregTech does. Those are kept in
 * {@code chargers.json} of the world's map folder.
 * <p>
 * A server with this mod is asked about the chargers seen ({@code share.MachineServer}) and tells, for those in its
 * loaded chunks, the real mode, the energy, and which locked ones don't charge this player (they are hidden then).
 * Its word is kept over the client's own look for a few seconds, as the two differ (the client doesn't know the
 * energy).
 * <p>
 * A GregTech long range of thousands of blocks covers most of the dimension: such a charger gets no circle, only its
 * mark and the range in its tooltip.
 */
public final class ChargersLayer implements MachineScanner.Layer {

    public static final ChargersLayer INSTANCE = new ChargersLayer();

    /** Working: GregTech amber, EnderIO violet, so the two tell apart; broken and unknown are as for AE2. */
    private static final int GT_COLOR = 0xFFB300, ENDERIO_COLOR = 0xC77DFF;
    private static final int INACTIVE_COLOR = 0xFF3030;
    private static final int UNKNOWN_COLOR = 0xB0BEC5;
    /** Ranges above this are not drawn as circles. */
    private static final double MAX_CIRCLE = 4096;
    private static final long SAVE_DELAY_MS = 5000;
    /** How long after a right click the charger's chat answer is waited for. */
    private static final long ANSWER_MS = 2000;
    /** How long the server's word about a charger stands over what the client sees. */
    private static final long SERVER_FRESH_MS = 5000;
    private static final String FILE_NAME = "chargers.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final Pattern NUMBER = Pattern.compile("\\d(?:[\\d.,\\u00a0\\u202f ]*\\d)?");

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap chargers saver");
        thread.setDaemon(true);
        return thread;
    });

    private ChargersLayer() {}

    /** A charger the client saw itself. */
    private static final class Seen {

        final int kind, x, y, z;
        int tier, mode = Chargers.MODE_UNKNOWN;
        boolean active, foreign;
        /** When the server last told about it. */
        long serverAt;

        Seen(int kind, int x, int y, int z) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A right click on a GregTech charger, waiting for its answer in the chat. */
    private static final class Click {

        final int dimension, x, y, z;
        final boolean sneaking, screwdriver;
        final long at = System.currentTimeMillis();
        boolean answered;
        int messages;

        Click(int dimension, int x, int y, int z, boolean sneaking, boolean screwdriver) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sneaking = sneaking;
            this.screwdriver = screwdriver;
        }
    }

    private final Queue<ShareNetwork.MachineList> inbox = new ConcurrentLinkedQueue<>();
    /** The server has the mod and answers; what it was last asked. */
    private boolean serverAnswers;
    private String asked;
    private final Map<Integer, Map<Long, Seen>> seen = new HashMap<>();
    private Click click;
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
        if (event.phase != TickEvent.Phase.END || !Mods.isChargersAvailable()) {
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
                click = null;
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
            finishClick();
        } catch (Throwable t) {
            Mods.fail(Mods.Addon.CHARGERS, t);
        }
        if (dirty && System.currentTimeMillis() - dirtySince > SAVE_DELAY_MS) {
            saveNow();
        }
    }

    // ---------------------------------------------------------------- MachineScanner

    private final Set<Long> present = new HashSet<>();

    @Override
    public Mods.Addon addon() {
        return Mods.Addon.CHARGERS;
    }

    @Override
    public boolean scans() {
        return inWorld && Mods.isChargersAvailable();
    }

    @Override
    public void sweepStart() {
        present.clear();
    }

    @Override
    public void see(int dimension, TileEntity tile, Object gtMachine) {
        if (record(dimension, tile, Chargers.gtChargerOf(gtMachine))) {
            present.add(pack(tile.xCoord, tile.yCoord, tile.zCoord));
        }
    }

    /** Drops the chargers whose loaded chunk no longer has them, and asks the server about those seen. */
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
                TileEntity tile = world.getTileEntity(point.x, point.y, point.z);
                if (kindAt(tile) != point.kind && !(point.kind == Chargers.KIND_GT && GtMeta.isPending(tile))) {
                    it.remove();
                    markDirty();
                }
            }
        }
        ask(Minecraft.getMinecraft());
    }

    /** Records the tile entity if it is a charger; false if it isn't one. */
    private boolean record(int dimension, TileEntity tile, Object gt) {
        int kind = gt != null ? Chargers.KIND_GT : Chargers.isEnderIoCharger(tile) ? Chargers.KIND_ENDERIO : -1;
        if (kind < 0) {
            return false;
        }
        Seen point = seenAt(dimension, kind, tile.xCoord, tile.yCoord, tile.zCoord);
        if (System.currentTimeMillis() - point.serverAt < SERVER_FRESH_MS) {
            return true;
        }
        int tier = gt != null ? Chargers.gtTier(gt) : 0;
        boolean active = gt != null ? Chargers.gtAllowedToWork(tile) : Chargers.enderIoActive(tile);
        if (point.tier != tier || point.active != active) {
            if (point.tier != tier) {
                point.mode = Chargers.MODE_UNKNOWN;
            }
            point.tier = tier;
            point.active = active;
            markDirty();
        }
        return true;
    }

    private static int kindAt(TileEntity tile) {
        return Chargers.gtCharger(tile) != null ? Chargers.KIND_GT
            : Chargers.isEnderIoCharger(tile) ? Chargers.KIND_ENDERIO : -1;
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

    /** What the server says about the chargers seen; it is told only about those. */
    private void apply(ShareNetwork.MachineList message) {
        boolean first = !serverAnswers;
        serverAnswers = true;
        long now = System.currentTimeMillis();
        for (MachinePoint p : message.points) {
            Seen point = seenAt(p.dimension, p.kind, p.x, p.y, p.z);
            int mode = p.kind == Chargers.KIND_GT && p.mode != Chargers.MODE_UNKNOWN ? p.mode : point.mode;
            if (point.tier != p.tier || point.mode != mode || point.active != p.active || point.foreign) {
                point.tier = p.tier;
                point.mode = mode;
                point.active = p.active;
                point.foreign = false;
                markDirty();
            }
            point.serverAt = now;
        }
        for (MachinePoint p : message.foreign) {
            Seen point = seenAt(p.dimension, p.kind, p.x, p.y, p.z);
            if (!point.foreign) {
                point.foreign = true;
                markDirty();
            }
            point.serverAt = now;
        }
        if (first) {
            ask(Minecraft.getMinecraft());
        }
    }

    /** Tells the server where the chargers seen are, the current dimension's first, when that changed. */
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
            ShareNetwork.sendToServer(new ShareNetwork.MachineAsk(ShareNetwork.MachineList.CHARGERS, positions));
        }
    }

    // ---------------------------------------------------------------- learning a GregTech charger's mode

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || event.entityPlayer == null
            || event.entityPlayer.worldObj == null
            || !event.entityPlayer.worldObj.isRemote
            || !Mods.isChargersAvailable()) {
            return;
        }
        World world = event.entityPlayer.worldObj;
        if (Chargers.gtCharger(world.getTileEntity(event.x, event.y, event.z)) != null) {
            click = new Click(
                world.provider.dimensionId,
                event.x,
                event.y,
                event.z,
                event.entityPlayer.isSneaking(),
                isScrewdriver(event.entityPlayer.getHeldItem()));
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        Click current = click;
        if (current == null || current.answered
            || System.currentTimeMillis() - current.at > ANSWER_MS
            || event.message == null) {
            return;
        }
        Seen point = seenPoint(current);
        if (point == null) {
            return;
        }
        Set<Long> numbers = new HashSet<>();
        Matcher m = NUMBER.matcher(event.message.getUnformattedText());
        while (m.find()) {
            String digits = m.group()
                .replaceAll("\\D", "");
            if (!digits.isEmpty() && digits.length() < 12) {
                numbers.add(Long.parseLong(digits));
            }
        }
        int mode = Chargers.gtModeFromNumbers(point.tier, numbers);
        if (mode != Chargers.MODE_UNKNOWN) {
            current.answered = true;
            if (point.mode != mode) {
                point.mode = mode;
                markDirty();
            }
        } else {
            current.messages++;
        }
    }

    /** GregTech registers every screwdriver under this ore name. */
    private static boolean isScrewdriver(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        for (int id : OreDictionary.getOreIDs(stack)) {
            if ("craftingToolScrewdriver".equals(OreDictionary.getOreName(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Once the answer time is over: chat messages without the range after a screwdriver click without sneaking were
     * its mode change (sneaking with one locks or unlocks it instead), so a known mode moves on.
     */
    private void finishClick() {
        Click current = click;
        if (current == null || System.currentTimeMillis() - current.at <= ANSWER_MS) {
            return;
        }
        click = null;
        if (current.answered || current.sneaking || !current.screwdriver || current.messages == 0) {
            return;
        }
        Seen point = seenPoint(current);
        if (point != null && point.mode != Chargers.MODE_UNKNOWN) {
            point.mode = (point.mode + 1) % 3;
            markDirty();
        }
    }

    private Seen seenPoint(Click c) {
        Map<Long, Seen> points = seen.get(c.dimension);
        Seen point = points == null ? null : points.get(pack(c.x, c.y, c.z));
        return point != null && point.kind == Chargers.KIND_GT ? point : null;
    }

    // ---------------------------------------------------------------- storage

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }

    /**
     * The file: {@code {"chargers": [{kind, dimension, x, y, z, tier, mode, active, foreign}, ...]}}, mode -1 unknown,
     * foreign: locked by another player, as the server said.
     */
    private static final class Saved {

        List<Entry> chargers = new ArrayList<>();

        static final class Entry {

            String kind;
            int dimension, x, y, z, tier;
            int mode = Chargers.MODE_UNKNOWN;
            boolean active, foreign;
        }
    }

    private void load() {
        seen.clear();
        File file = new File(worldDirectory, FILE_NAME);
        if (file.isFile()) {
            try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Saved saved = GSON.fromJson(in, Saved.class);
                if (saved != null && saved.chargers != null) {
                    for (Saved.Entry e : saved.chargers) {
                        if (e == null) {
                            continue;
                        }
                        int kind = "enderio".equals(e.kind) ? Chargers.KIND_ENDERIO : Chargers.KIND_GT;
                        Seen point = seenAt(e.dimension, kind, e.x, e.y, e.z);
                        point.tier = e.tier;
                        point.mode = e.mode >= 0 && e.mode <= 2 ? e.mode : Chargers.MODE_UNKNOWN;
                        point.active = e.active;
                        point.foreign = e.foreign;
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
                e.kind = p.kind == Chargers.KIND_ENDERIO ? "enderio" : "gregtech";
                e.dimension = dimension.getKey();
                e.x = p.x;
                e.y = p.y;
                e.z = p.z;
                e.tier = p.tier;
                e.mode = p.mode;
                e.active = p.active;
                e.foreign = p.foreign;
                saved.chargers.add(e);
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

    /** One charger to draw. {@code known}: the range is its mode's, not the smallest one. */
    private static final class Shown {

        final int kind, x, y, z, tier, mode;
        final double range;
        final boolean known, active;

        Shown(int kind, int x, int y, int z, int tier, int mode, double range, boolean known, boolean active) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
            this.mode = mode;
            this.range = range;
            this.known = known;
            this.active = active;
        }
    }

    private List<Shown> shown(int dimension) {
        List<Shown> result = new ArrayList<>();
        Map<Long, Seen> points = seen.get(dimension);
        if (points == null) {
            return Collections.emptyList();
        }
        for (Seen p : points.values()) {
            if (p.foreign) {
                continue;
            }
            if (p.kind == Chargers.KIND_ENDERIO) {
                result.add(new Shown(p.kind, p.x, p.y, p.z, 0, p.mode, Chargers.enderIoRange(), true, p.active));
            } else {
                boolean known = p.mode != Chargers.MODE_UNKNOWN;
                double range = known ? Chargers.gtRange(p.tier, p.mode) : Chargers.gtMinRange(p.tier);
                result.add(new Shown(p.kind, p.x, p.y, p.z, p.tier, p.mode, range, known, p.active));
            }
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
     * Draws the chargers of the dimension into the map rectangle, centered on (centerX, centerZ) at {@code scale}
     * pixels per block, and remembers the charger under the mouse (world map only).
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
            double sx = x + (p.x - left) * scale;
            double sy = y + (p.z - top) * scale;
            boolean circle = p.range <= MAX_CIRCLE;
            double full = circle ? p.range * scale : 0;
            if (sx + full + 4 < x || sy + full + 4 < y || sx - full - 4 > x + width || sy - full - 4 > y + height) {
                continue;
            }
            if (circle) {
                double slice = here ? Math.sqrt(Math.max(0, p.range * p.range - sq(p.y - feet))) * scale : full;
                coverage.add(
                    new CoverageDrawer.Circle(
                        sx,
                        sy,
                        full,
                        slice,
                        color(p),
                        !p.known ? 32 : p.active ? 48 : 40,
                        // One layer per color: working ones over broken ones, whichever mod.
                        (!p.known ? 0 : p.active ? 2 : 1) * 2 + p.kind));
                if (!minimap && CoverageHover.track(
                    "charger:" + dimension + ":" + p.x + ":" + p.y + ":" + p.z,
                    sx,
                    sy,
                    Config.chargersFullRange ? full : slice,
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
        CoverageDrawer.draw(coverage, minimap, Config.chargersFullRange);
        if (!minimap && areaCircle != null) {
            CoverageDrawer.highlight(
                areaCircle[0],
                areaCircle[1],
                areaCircle[2],
                areaCircle[3],
                color(areaHovered),
                Config.chargersFullRange,
                minimap);
        }

        for (int i = 0; i < marked.size(); i++) {
            double[] c = marks.get(i);
            Shown p = marked.get(i);
            int rgb = !p.known ? UNKNOWN_COLOR : p.kind == Chargers.KIND_ENDERIO ? ENDERIO_COLOR : GT_COLOR;
            CoverageDrawer.mark(
                c[0],
                c[1],
                Icons.CHARGER,
                rgb,
                !p.active,
                !minimap && (p == hovered || p == areaHovered),
                minimap);
        }
    }

    private static int color(Shown p) {
        return !p.known ? UNKNOWN_COLOR
            : !p.active ? INACTIVE_COLOR : p.kind == Chargers.KIND_ENDERIO ? ENDERIO_COLOR : GT_COLOR;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---------------------------------------------------------------- tooltip

    /**
     * Tooltip of the charger under the mouse on the world map, or null: of its mark, or with {@code area} of its circle
     * (only what it is).
     */
    public static List<String> getHoveredTooltip(boolean area) {
        Shown p = area ? areaHovered : hovered;
        if (area || p == null) {
            // In its circle, not on its mark: only what it is.
            return p == null ? null
                : Collections.singletonList(
                    p.kind == Chargers.KIND_ENDERIO ? "§d" + Lang.format("wayfarmap.chargers.enderio")
                        : "§6" + Lang.format("wayfarmap.chargers.gregtech", tierName(p.tier)));
        }
        List<String> lines = new ArrayList<>();
        if (p.kind == Chargers.KIND_ENDERIO) {
            lines.add("§d" + Lang.format("wayfarmap.chargers.enderio"));
        } else {
            lines.add("§6" + Lang.format("wayfarmap.chargers.gregtech", tierName(p.tier)));
        }
        lines.add("§7" + p.x + ", " + p.y + ", " + p.z);
        if (p.kind == Chargers.KIND_GT) {
            if (p.known) {
                lines.add("§7" + Lang.format("wayfarmap.chargers.mode." + modeKey(p.mode)));
            } else {
                lines.add("§7" + Lang.format("wayfarmap.chargers.mode_unknown"));
            }
        }
        lines.add(
            "§7" + Lang.format(p.known ? "wayfarmap.chargers.range" : "wayfarmap.chargers.range_min", blocks(p.range)));
        if (p.kind == Chargers.KIND_GT && p.mode == Chargers.MODE_MIXED) {
            lines.add(
                "§7" + Lang
                    .format("wayfarmap.chargers.range_local", blocks(Chargers.gtMixedLocalRange(p.tier, p.mode))));
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (p.range <= MAX_CIRCLE && mc.theWorld != null
            && mc.thePlayer != null
            && mc.theWorld.provider.dimensionId == hoveredDimension) {
            double dy = p.y - mc.thePlayer.boundingBox.minY;
            double slice = Math.sqrt(Math.max(0, p.range * p.range - dy * dy));
            lines.add(
                "§7" + (slice > 0 ? Lang.format("wayfarmap.wireless.at_height", blocks(slice))
                    : Lang.format("wayfarmap.wireless.out_of_height")));
        }
        lines.add(
            p.active ? "§a" + Lang.format("wayfarmap.wireless.active")
                : "§c" + Lang.format(
                    p.kind == Chargers.KIND_ENDERIO ? "wayfarmap.chargers.inactive_enderio"
                        : "wayfarmap.chargers.inactive_gregtech"));
        if (!INSTANCE.serverAnswers) {
            if (p.kind == Chargers.KIND_GT && !p.known) {
                lines.add("§8" + Lang.format("wayfarmap.chargers.learn_mode"));
            }
            lines.add("§8" + Lang.format("wayfarmap.chargers.local"));
        }
        return lines;
    }

    private static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    private static String tierName(int tier) {
        return tier >= 0 && tier < TIERS.length ? TIERS[tier] : String.valueOf(tier);
    }

    private static String modeKey(int mode) {
        return mode == Chargers.MODE_LOCAL ? "local" : mode == Chargers.MODE_MIXED ? "mixed" : "long";
    }

    private static String blocks(double range) {
        return Math.abs(range - Math.rint(range)) < 0.05 ? String.format(Locale.ROOT, "%,d", (long) Math.rint(range))
            .replace(',', ' ') : String.format(Locale.ROOT, "%.1f", range);
    }
}
