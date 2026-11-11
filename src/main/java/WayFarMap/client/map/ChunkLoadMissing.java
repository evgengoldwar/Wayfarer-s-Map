package WayFarMap.client.map;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

import WayFarMap.client.Lang;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.share.ShareNetwork;

/**
 * "Load only the missing chunks" of the area loading view: the chunks saved in the world that the map has neither on
 * the flat nor on the 3D map (those shown grey) are queued to be loaded, as if picked, and no other. The server tells
 * which chunks of every region file are saved ({@link ShareNetwork.SavedScan}); the map's regions are then looked at
 * a few a tick, read from disk without being kept, and the chunks found are picked. Render thread.
 */
public final class ChunkLoadMissing {

    private static final int IDLE = 0, ASKING = 1, LOOKING = 2;
    /** How long to wait for the server's answers before giving up. */
    private static final long ASK_TIMEOUT_MS = 120_000;
    /** Time per tick for reading the map's regions. */
    private static final long LOOK_NANOS = 4_000_000L;

    private static int state = IDLE;
    private static int dimension;
    private static long askedAt;
    private static int regionCount;
    private static ArrayDeque<Map.Entry<Long, long[]>> left;
    private static Set<Long> found;
    /** The server knows {@link ShareNetwork.SavedScan} (an older one would not). */
    private static boolean serverAnswers;

    private ChunkLoadMissing() {}

    public static void setServerAnswers(boolean value) {
        serverAnswers = value;
    }

    /** Whether a look can be started now: the server can answer and none is going on. */
    public static boolean canStart() {
        return serverAnswers && state == IDLE;
    }

    /** Starts looking for the missing chunks of the player's dimension; nothing if a look is going on. */
    public static void start(int dimensionId) {
        if (!canStart()) {
            return;
        }
        dimension = dimensionId;
        askedAt = System.currentTimeMillis();
        state = ASKING;
        ShareNetwork.sendToServer(new ShareNetwork.SavedScan(dimensionId, 0));
    }

    public static boolean isRunning() {
        return state != IDLE;
    }

    /** The server answered every region file (their {@link ShareNetwork.SavedChunks} came before). */
    public static void scanned(ShareNetwork.SavedScan message) {
        if (state != ASKING || message.dimension != dimension) {
            return;
        }
        regionCount = message.regions;
        if (regionCount == 0) {
            state = IDLE;
            tell("wayfarmap.regionload.no_regions");
            return;
        }
        left = new ArrayDeque<>(
            ChunkLoadView.savedRegions(dimension)
                .entrySet());
        found = new LinkedHashSet<>();
        state = LOOKING;
    }

    /** A few of the map's regions each tick; once all are looked at, the missing chunks are picked. */
    public static void tick(Minecraft mc) {
        if (state == IDLE) {
            return;
        }
        if (mc.theWorld == null || mc.theWorld.provider.dimensionId != dimension) {
            // Left the world or the dimension: the look is dropped.
            state = IDLE;
            left = null;
            found = null;
            return;
        }
        if (state == ASKING) {
            if (System.currentTimeMillis() - askedAt > ASK_TIMEOUT_MS) {
                state = IDLE;
                tell("wayfarmap.gui.load_missing_no_answer");
            }
            return;
        }
        MapDimension surface = MapManager.INSTANCE.getSurfaceMap();
        if (surface == null) {
            return;
        }
        long end = System.nanoTime() + LOOK_NANOS;
        while (!left.isEmpty() && System.nanoTime() < end) {
            Map.Entry<Long, long[]> entry = left.poll();
            int rx = (int) (entry.getKey() >> 32), rz = (int) (long) entry.getKey();
            long[] bits = entry.getValue();
            MapRegion region = surface.peekRegion(rx, rz);
            for (int bit = 0; bit < 32 * 32; bit++) {
                if ((bits[bit >> 6] & 1L << (bit & 63)) == 0) {
                    continue;
                }
                int lx = bit & 31, lz = bit >> 5;
                int cx = rx * 32 + lx, cz = rz * 32 + lz;
                if (region != null && region.getChunkTime(lx, lz) > 0) {
                    continue;
                }
                if (IsoMap.INSTANCE.hasChunk(dimension, cx, cz) || ChunkLoadView.isQueued(dimension, cx, cz)) {
                    continue;
                }
                found.add(ChunkLoadView.pack(cx, cz));
            }
        }
        if (!left.isEmpty()) {
            return;
        }
        state = IDLE;
        Set<Long> chunks = found;
        left = null;
        found = null;
        if (chunks.isEmpty()) {
            tell("wayfarmap.gui.load_missing_none");
            return;
        }
        ChunkLoadView.pick(dimension, chunks, false, true, false);
        tell("wayfarmap.gui.load_missing_queued", chunks.size());
    }

    /** For the status on the map while looking, or null. */
    public static String statusText() {
        if (state == ASKING) {
            return Lang.format("wayfarmap.gui.load_missing_asking");
        }
        if (state == LOOKING) {
            int total = Math.max(1, regionCount);
            int done = Math.max(0, total - left.size());
            return Lang.format("wayfarmap.gui.load_missing_looking", done, total);
        }
        return null;
    }

    private static void tell(String key, Object... args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentTranslation(key, args));
        }
    }

    /** Forgets a look going on (leaving the world). */
    public static void reset() {
        state = IDLE;
        left = null;
        found = null;
    }
}
