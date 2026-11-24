package WayFarMap.client.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import mcp.mobius.opis.api.IMessageHandler;
import mcp.mobius.opis.api.MessageHandlerRegistrar;
import mcp.mobius.opis.data.holders.ISerializable;
import mcp.mobius.opis.data.holders.basetypes.CoordinatesChunk;
import mcp.mobius.opis.data.holders.basetypes.SerialInt;
import mcp.mobius.opis.data.holders.stats.StatsChunk;
import mcp.mobius.opis.network.PacketBase;
import mcp.mobius.opis.network.PacketManager;
import mcp.mobius.opis.network.enums.Message;
import mcp.mobius.opis.network.packets.client.PacketReqData;

/**
 * Opis' chunks, as its own Navigator map layers show them: the slowest chunks of the last Opis run, blue (fast) to red
 * (slow), and the chunks the server has loaded. Asked of the server the way Opis' layers do; it answers only players
 * with Opis rights (operators, its privileged list, cheats in single player) and stays silent otherwise, so whoever
 * gets an answer has them: the layers are offered only then (see {@link #isAllowed}).
 */
public final class OpisLayer {

    /** Least time between two requests of one kind; Opis drops those closer together than 200 ms. */
    private static final long MIN_REQUEST_MS = 1000;
    /**
     * Without any answer for this long, the rights to Opis were taken away. Long enough not to be taken for that when
     * the server hangs: the layers are there to find out why.
     */
    private static final long NO_ANSWER_MS = 20_000;
    /** How often to ask for the rights while there are none, and how old the last answer may get while there are. */
    private static final long PROBE_MS = 15_000, RECHECK_MS = 60_000;
    /** Dimension no server has: asking for its loaded chunks tells the rights, with nothing to go through or send. */
    private static final int PROBE_DIMENSION = Integer.MIN_VALUE;
    /** How long an answer is given to come before the player is told there is none. */
    private static final long ASKING_MS = 3000;

    private static final int TIME_ALPHA = 150, LOADED_ALPHA = 60, BORDER_ALPHA = 170;
    private static final int LOADED_COLOR = 0x00FF00, FORCED_COLOR = 0x3060FF;
    /** Size of a chunk on the map (in GUI pixels) from which its border is drawn. */
    private static final double MIN_BORDERED_CELL = 4;
    /** Sides of a loaded chunk where its area ends. */
    private static final int WEST = 1, EAST = 2, NORTH = 4, SOUTH = 8;

    /** Nanoseconds in a microsecond and a millisecond. */
    private static final double MICRO = 1000.0, MILLI = 1_000_000.0;

    private static final Request TIMES_REQUEST = new Request(Message.LIST_TIMING_CHUNK_DIM);
    private static final Request LOADED_REQUEST = new Request(Message.LIST_CHUNK_LOADED);

    /** What the server last sent, whole and never changed afterwards; null if nothing yet. */
    private static volatile Times times;
    private static volatile Loaded loaded;

    /** Whether the server answers: the player has Opis rights there. */
    private static volatile boolean allowed;
    /** When the server last answered, and since when an answer is waited for (0 when none is). */
    private static volatile long lastAnswer, waitingSince;

    private static final Listener LISTENER = new Listener();

    private OpisLayer() {}

    public static void register() {
        for (Message message : new Message[] { Message.LIST_TIMING_CHUNK_DIM, Message.LIST_CHUNK_LOADED,
            Message.LIST_CHUNK_LOADED_CLEAR, Message.STATUS_STOP }) {
            MessageHandlerRegistrar.INSTANCE.registerHandler(message, LISTENER);
        }
    }

    // ---------------------------------------------------------------- rights

    /**
     * Whether the player may use Opis on this server, as last found out; asks the server now and then, so it follows
     * the rights being given and taken. The requests of the layers shown tell it too, then nothing more is asked.
     */
    public static boolean isAllowed() {
        if (Minecraft.getMinecraft().thePlayer == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long since = waitingSince;
        if (since != 0 && now - since > NO_ANSWER_MS) {
            allowed = false;
        }
        long recheck = Math.max(RECHECK_MS, 2000L * Config.opisRefresh);
        if (!allowed || since == 0 && now - lastAnswer > recheck) {
            LOADED_REQUEST.poll(PROBE_DIMENSION, PROBE_MS);
        }
        return allowed;
    }

    /** Why {@link #isAllowed} is false, for the player. */
    public static String refusal() {
        long since = waitingSince;
        boolean asking = since == 0 || System.currentTimeMillis() - since < ASKING_MS;
        return Lang.format(asking ? "wayfarmap.opis.asking" : "wayfarmap.opis.no_answer");
    }

    // ---------------------------------------------------------------- data

    /** The update time of one chunk. */
    private static final class ChunkTime {

        final int chunkX, chunkZ, tileEntities, entities, rank;
        /** Nanoseconds per tick, of all its tile entities and entities. */
        final double nanos;

        ChunkTime(StatsChunk stats, int rank) {
            chunkX = stats.getChunk().chunkX;
            chunkZ = stats.getChunk().chunkZ;
            tileEntities = stats.tileEntities;
            entities = stats.entities;
            nanos = stats.getDataSum();
            this.rank = rank;
        }
    }

    /** The slowest chunks of a dimension, the slowest first. */
    private static final class Times {

        final int dimension;
        final Map<Long, ChunkTime> chunks = new HashMap<>();
        final double slowest;

        Times(int dimension, Collection<ISerializable> data) {
            this.dimension = dimension;
            double max = 0;
            for (ISerializable element : data) {
                ChunkTime time = new ChunkTime((StatsChunk) element, chunks.size() + 1);
                chunks.put(pack(time.chunkX, time.chunkZ), time);
                max = Math.max(max, time.nanos);
            }
            slowest = max;
        }

        ChunkTime get(int dimension, int chunkX, int chunkZ) {
            return this.dimension == dimension ? chunks.get(pack(chunkX, chunkZ)) : null;
        }
    }

    /** A chunk the server has loaded. */
    private static final class LoadedChunk {

        final int chunkX, chunkZ;
        /** Kept loaded by a ticket (a chunk loader) rather than by a player nearby. */
        final boolean forced;
        /** Sides its area ends on. */
        int borders;

        LoadedChunk(CoordinatesChunk chunk) {
            chunkX = chunk.chunkX;
            chunkZ = chunk.chunkZ;
            forced = chunk.metadata != 0;
        }
    }

    /** The chunks of a dimension the server has loaded. */
    private static final class Loaded {

        final int dimension, forcedCount;
        final Map<Long, LoadedChunk> chunks = new HashMap<>();

        Loaded(int dimension, Collection<CoordinatesChunk> data) {
            this.dimension = dimension;
            int forced = 0;
            for (CoordinatesChunk element : data) {
                LoadedChunk chunk = new LoadedChunk(element);
                LoadedChunk before = chunks.put(pack(chunk.chunkX, chunk.chunkZ), chunk);
                forced += (chunk.forced ? 1 : 0) - (before != null && before.forced ? 1 : 0);
            }
            forcedCount = forced;
            for (LoadedChunk chunk : chunks.values()) {
                chunk.borders = border(chunk, -1, 0, WEST) | border(chunk, 1, 0, EAST)
                    | border(chunk, 0, -1, NORTH)
                    | border(chunk, 0, 1, SOUTH);
            }
        }

        private int border(LoadedChunk chunk, int dx, int dz, int side) {
            LoadedChunk next = chunks.get(pack(chunk.chunkX + dx, chunk.chunkZ + dz));
            return next != null && next.forced == chunk.forced ? 0 : side;
        }

        LoadedChunk get(int dimension, int chunkX, int chunkZ) {
            return this.dimension == dimension ? chunks.get(pack(chunkX, chunkZ)) : null;
        }
    }

    private static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }

    /** Takes the server's answers, also those to Opis' own layers and window. */
    private static final class Listener implements IMessageHandler {

        /** The server sends the loaded chunks in parts. */
        private final List<CoordinatesChunk> pending = new ArrayList<>();

        /** Always true: Opis logs a warning for messages that aren't taken. */
        @Override
        public synchronized boolean handleMessage(Message message, PacketBase data) {
            try {
                take(message, data);
            } catch (Throwable t) {
                Mods.fail(Mods.Addon.OPIS, t);
            }
            return true;
        }

        /** The server is left: the rest of the parts won't come. */
        synchronized void reset() {
            pending.clear();
        }

        private void take(Message message, PacketBase data) {
            if (message != Message.STATUS_STOP) {
                // Only players with Opis rights get these.
                lastAnswer = System.currentTimeMillis();
                waitingSince = 0;
                allowed = true;
            }
            switch (message) {
                case LIST_TIMING_CHUNK_DIM: {
                    // An empty list doesn't tell its dimension.
                    int dimension = data.array.isEmpty() ? TIMES_REQUEST.dimension
                        : ((StatsChunk) data.array.get(0)).getChunk().dim;
                    times = new Times(dimension, data.array);
                    break;
                }
                case LIST_CHUNK_LOADED:
                    for (ISerializable chunk : data.array) {
                        pending.add((CoordinatesChunk) chunk);
                    }
                    break;
                case LIST_CHUNK_LOADED_CLEAR: {
                    // Sent after the last part.
                    int dimension = pending.isEmpty() ? LOADED_REQUEST.dimension : pending.get(0).dim;
                    if (dimension != PROBE_DIMENSION) {
                        loaded = new Loaded(dimension, pending);
                    }
                    pending.clear();
                    break;
                }
                case STATUS_STOP:
                    // An Opis run ended (told to those with its window open): there are new times.
                    TIMES_REQUEST.refresh = true;
                    break;
                default:
                    break;
            }
        }
    }

    /** One kind of data asked of the server. */
    private static final class Request {

        private final Message message;
        /** Dimension last asked for. */
        volatile int dimension;
        /** Ask again without waiting for the interval. */
        volatile boolean refresh = true;
        private volatile long sentAt;

        Request(Message message) {
            this.message = message;
        }

        /**
         * Asks for the dimension's data if the last request is {@code interval} old (a second when it was for another
         * dimension or a refresh is due).
         */
        void poll(int dimension, long interval) {
            long now = System.currentTimeMillis();
            boolean soon = this.dimension != dimension || refresh;
            if (now - sentAt < (soon ? MIN_REQUEST_MS : interval)) {
                return;
            }
            if (waitingSince == 0) {
                waitingSince = now;
            }
            this.dimension = dimension;
            refresh = false;
            sentAt = now;
            PacketManager.sendToServer(new PacketReqData(message, new SerialInt(dimension)));
        }

        void reset() {
            refresh = true;
            sentAt = 0;
        }
    }

    /** Called when a layer is turned on or the map opened: ask the server again right away. */
    public static void onShow() {
        TIMES_REQUEST.refresh = true;
        LOADED_REQUEST.refresh = true;
    }

    /** The server is left: its chunks aren't the next one's. */
    public static void reset() {
        times = null;
        loaded = null;
        allowed = false;
        lastAnswer = 0;
        waitingSince = 0;
        LISTENER.reset();
        TIMES_REQUEST.reset();
        LOADED_REQUEST.reset();
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws the chunks of the dimension into the map rectangle, centered on (centerX, centerZ) at {@code scale} pixels
     * per block, and asks the server for what is drawn. The server goes through everything it has loaded or timed for
     * each request, in its tick, so not more often than set.
     */
    public static void draw(int dimension, boolean showTimes, boolean showLoaded, double centerX, double centerZ,
        double scale, int x, int y, int width, int height) {
        if (Minecraft.getMinecraft().thePlayer == null) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double cell = 16 * scale;
        double pixel = 1.0 / ScaledScreen.currentFactor();
        long interval = Config.opisRefresh * 1000L;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator.instance.startDrawingQuads();
        if (showLoaded) {
            LOADED_REQUEST.poll(dimension, interval);
            Loaded shown = loaded;
            if (shown != null && shown.dimension == dimension) {
                for (LoadedChunk chunk : shown.chunks.values()) {
                    double sx = x + (chunk.chunkX * 16 - left) * scale;
                    double sy = y + (chunk.chunkZ * 16 - top) * scale;
                    drawLoaded(chunk, sx, sy, cell, pixel, x, y, width, height);
                }
            }
        }
        if (showTimes) {
            TIMES_REQUEST.poll(dimension, interval);
            Times shown = times;
            if (shown != null && shown.dimension == dimension && shown.slowest > 0) {
                for (ChunkTime chunk : shown.chunks.values()) {
                    double sx = x + (chunk.chunkX * 16 - left) * scale;
                    double sy = y + (chunk.chunkZ * 16 - top) * scale;
                    rect(sx, sy, cell, cell, heatColor(chunk.nanos / shown.slowest), TIME_ALPHA, x, y, width, height);
                    if (cell >= MIN_BORDERED_CELL) {
                        borders(sx, sy, cell, pixel, WEST | EAST | NORTH | SOUTH, x, y, width, height);
                    }
                }
            }
        }
        Tessellator.instance.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void drawLoaded(LoadedChunk chunk, double sx, double sy, double cell, double pixel, int x, int y,
        int width, int height) {
        int color = chunk.forced ? FORCED_COLOR : LOADED_COLOR;
        rect(sx, sy, cell, cell, color, LOADED_ALPHA, x, y, width, height);
        if (cell >= MIN_BORDERED_CELL) {
            borders(sx, sy, cell, pixel, chunk.borders, x, y, width, height);
        }
    }

    /** Blue for the fastest to red for the slowest (1), as in Opis' own layer. */
    private static int heatColor(double heat) {
        int red = (int) Math.ceil(heat * 255);
        return red << 16 | 255 - red;
    }

    private static void borders(double sx, double sy, double cell, double t, int sides, int x, int y, int width,
        int height) {
        if ((sides & NORTH) != 0) {
            rect(sx, sy, cell, t, 0, BORDER_ALPHA, x, y, width, height);
        }
        if ((sides & SOUTH) != 0) {
            rect(sx, sy + cell - t, cell, t, 0, BORDER_ALPHA, x, y, width, height);
        }
        if ((sides & WEST) != 0) {
            rect(sx, sy, t, cell, 0, BORDER_ALPHA, x, y, width, height);
        }
        if ((sides & EAST) != 0) {
            rect(sx + cell - t, sy, t, cell, 0, BORDER_ALPHA, x, y, width, height);
        }
    }

    /** Adds the part of the rectangle inside the map. */
    private static void rect(double rx, double ry, double w, double h, int rgb, int alpha, int x, int y, int width,
        int height) {
        double x0 = Math.max(rx, x), y0 = Math.max(ry, y);
        double x1 = Math.min(rx + w, x + width), y1 = Math.min(ry + h, y + height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorRGBA_I(rgb, alpha);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    // ---------------------------------------------------------------- world map interaction

    /** Tooltip of a chunk in the layers shown, or null if neither has it. */
    public static List<String> tooltip(int dimension, int chunkX, int chunkZ, boolean showTimes, boolean showLoaded) {
        ChunkTime time = showTimes && times != null ? times.get(dimension, chunkX, chunkZ) : null;
        LoadedChunk chunk = showLoaded && loaded != null ? loaded.get(dimension, chunkX, chunkZ) : null;
        if (time == null && chunk == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("§f" + Lang.format("wayfarmap.opis.chunk", chunkX, chunkZ));
        if (time != null) {
            lines.add("§e" + Lang.format("wayfarmap.opis.time", duration(time.nanos), time.rank));
            lines.add("§7" + Lang.format("wayfarmap.opis.contents", time.tileEntities, time.entities));
        }
        if (chunk != null) {
            lines.add(
                chunk.forced ? "§9" + Lang.format("wayfarmap.opis.forced")
                    : "§a" + Lang.format("wayfarmap.opis.loaded"));
        }
        lines.add("§8" + Lang.format("wayfarmap.opis.hint"));
        return lines;
    }

    /** Whether one of the layers shown has the chunk, for the right click menu. */
    public static boolean has(int dimension, int chunkX, int chunkZ, boolean showTimes, boolean showLoaded) {
        return showTimes && times != null && times.get(dimension, chunkX, chunkZ) != null
            || showLoaded && loaded != null && loaded.get(dimension, chunkX, chunkZ) != null;
    }

    /** Asks Opis to teleport the player to the chunk; it finds the ground there, in any dimension that is loaded. */
    public static void teleport(int dimension, int chunkX, int chunkZ) {
        PacketManager.sendToServer(
            new PacketReqData(Message.COMMAND_TELEPORT_CHUNK, new CoordinatesChunk(dimension, chunkX, chunkZ)));
    }

    /** What the layers shown have of the dimension, or why they are empty: for the bottom of the world map. */
    public static Status status(int dimension, boolean showTimes, boolean showLoaded) {
        Times time = times;
        Loaded chunks = loaded;
        boolean haveTimes = time != null && time.dimension == dimension;
        boolean haveLoaded = chunks != null && chunks.dimension == dimension;
        if (showTimes && !haveTimes || showLoaded && !haveLoaded) {
            return new Status(Lang.format("wayfarmap.opis.asking"), Theme.TEXT_MUTED);
        }
        List<String> parts = new ArrayList<>();
        int color = Theme.TEXT_MUTED;
        if (showTimes) {
            if (time.chunks.isEmpty()) {
                parts.add(Lang.format("wayfarmap.opis.no_run"));
                color = Theme.ACCENT;
            } else {
                parts.add(Lang.format("wayfarmap.opis.slowest", time.chunks.size(), duration(time.slowest)));
            }
        }
        if (showLoaded) {
            parts.add(Lang.format("wayfarmap.opis.counts", chunks.chunks.size(), chunks.forcedCount));
        }
        return new Status(String.join("  ·  ", parts), color);
    }

    /** A line of text and its color. */
    public static final class Status {

        public final String text;
        public final int color;

        Status(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static String duration(double nanos) {
        return nanos < MILLI ? String.format(Locale.ROOT, "%.1f µs", nanos / MICRO)
            : String.format(Locale.ROOT, "%.2f ms", nanos / MILLI);
    }
}
