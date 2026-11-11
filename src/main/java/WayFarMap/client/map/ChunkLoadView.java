package WayFarMap.client.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.share.ShareNetwork;

/**
 * The world map's area loading view: the chunks on the flat map only in green, on the 3D map only in blue, on both in
 * purple, the chunks saved in the world (its region files)
 * that the map doesn't have in grey, as the server tells, and the chunks picked to be loaded in red until the map has
 * them (then they turn green and join the rest), each area with one border around it like claims. Chunks are picked
 * by dragging with Ctrl; the server loads them through {@code /wf chunkload}'s batches.
 * <p>
 * Picked "saved only", only the grey chunks are taken, loaded from the world as they are, nothing generated
 * ({@code /wf regionload}'s way); picked "generate new", chunks never made are generated too.
 * <p>
 * Picked with Shift too, every cave layer of the chunks is mapped as well, not only the surface.
 * <p>
 * With the view's 3D switch on ({@link Config#chunkload3d}), the picked chunks go onto the 3D map too (shown in
 * orange while queued), whether or not blocks are recorded while playing.
 */
public final class ChunkLoadView {

    /** Chunk states: MAPPED on the flat map only, MAPPED_3D on the 3D map only, MAPPED_BOTH on both. */
    private static final int NONE = 0, MAPPED = 1, PENDING = 2, SAVED = 3, PENDING_3D = 4, MAPPED_3D = 5,
        MAPPED_BOTH = 6;
    private static final int SAVED_FILL = 0x8B949E, SAVED_BORDER = 0x6E7681;
    private static final int MAPPED_FILL = 0x3FB950, MAPPED_BORDER = 0x2EA043;
    private static final int MAPPED_3D_FILL = 0x388BFD, MAPPED_3D_BORDER = 0x58A6FF;
    private static final int MAPPED_BOTH_FILL = 0xA371F7, MAPPED_BOTH_BORDER = 0xBC8CFF;
    private static final int PENDING_FILL = 0xE5534B, PENDING_BORDER = 0xFF5050;
    private static final int PENDING_3D_FILL = 0xF0883E, PENDING_3D_BORDER = 0xFFA657;
    private static final int FILL_ALPHA = 80, PENDING_ALPHA = 120, BORDER_ALPHA = 230;
    /** Chunks a drag can pick at most (a square of this side). */
    public static final int MAX_SIDE = 128;
    /**
     * Below this many GUI pixels a chunk, the states kept for each region are drawn (the screen has too many chunks to
     * look at each of them every frame).
     */
    private static final double MIN_CELL = 3;

    /**
     * Picked chunks loaded for the 3D map too (blocks recorded): red until the loading has both, not as soon as the
     * flat map has them.
     */
    private static final Set<Long> WITH_3D = new java.util.HashSet<>();
    /** Picked chunks whose cave layers are mapped too: red until the loading has mapped them all. */
    private static final Set<Long> WITH_CAVES = new java.util.HashSet<>();
    private static final int CAVES_BORDER = 0x58A6FF;
    /** Border of the chunks of a drag deleting them from the map. */
    private static final int DELETE_BORDER = 0xF85149;

    /** Region loading view: which chunks of each region are saved in the world, by dimension and region key. */
    private static final Map<Integer, Map<Long, long[]>> SAVED_CHUNKS = new HashMap<>();
    /** When each region was last asked about (by dimension and region key). */
    private static final Map<Integer, Map<Long, Long>> ASKED = new HashMap<>();
    /** Regions asked about again after this long; not asked again sooner while no answer came. */
    private static final long SAVED_REFRESH_MS = 30_000, ASK_AGAIN_MS = 5000;
    private static long lastAsk;
    /** Whether the server lets the player load chunks from the map (it says so; servers without the mod never do). */
    private static boolean allowed;

    /** Colors of the view's legend: on the 2D, 3D or both maps, saved in the world, picked. */
    public static final int LEGEND_MAPPED = 0xFF000000 | MAPPED_FILL, LEGEND_MAPPED_3D = 0xFF000000 | MAPPED_3D_FILL,
        LEGEND_MAPPED_BOTH = 0xFF000000 | MAPPED_BOTH_FILL, LEGEND_SAVED = 0xFF000000 | SAVED_FILL,
        LEGEND_PENDING = 0xFF000000 | PENDING_FILL, LEGEND_PENDING_3D = 0xFF000000 | PENDING_3D_FILL;
    /** The states of the chunks drawn, kept from frame to frame so they aren't allocated each time. */
    private static int[] grid = new int[0];

    public static boolean isAllowed() {
        return allowed;
    }

    public static void setAllowed(boolean value) {
        allowed = value;
    }

    /** Whether the view being drawn is the region loading one. */
    private static boolean regionsMode;

    /** Picked chunks of each dimension, with when they were sent: red until mapped after that. */
    private static final Map<Integer, Map<Long, Long>> PENDING_CHUNKS = new HashMap<>();
    /** The pick each queued chunk came with ({@link ShareNetwork.LoadChunks#seq}), to know which ended with it. */
    private static final Map<Long, Integer> PENDING_SEQ = new HashMap<>();
    /**
     * Number of the last pick sent. Started from the clock in seconds so it keeps growing across games: the server
     * tells back the picks a loading took when it ends.
     */
    private static int seq;

    private ChunkLoadView() {}

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    /**
     * Sends the picked chunks to the server to be loaded (or taken off the queue) and marks them.
     *
     * @param regions from the region loading view: only chunks saved in the world are taken, loaded as they are
     * @param caves   every cave layer of the chunks is mapped too
     */
    public static void pick(int dimension, Set<Long> chunks, boolean remove, boolean regions, boolean caves) {
        if (regions && !remove) {
            Set<Long> saved = new java.util.LinkedHashSet<>();
            for (long chunk : chunks) {
                if (isSaved(dimension, unpackX(chunk), unpackZ(chunk))) {
                    saved.add(chunk);
                }
            }
            chunks = saved;
        }
        if (chunks.isEmpty()) {
            return;
        }
        Map<Long, Long> pending = PENDING_CHUNKS.computeIfAbsent(dimension, d -> new HashMap<>());
        long now = System.currentTimeMillis();
        seq = Math.max(seq + 1, (int) (now / 1000));
        boolean with3d = Config.chunkload3d;
        // The far view shows the change at once.
        REGION_STATES.clear();
        for (long chunk : chunks) {
            if (remove) {
                pending.remove(chunk);
                PENDING_SEQ.remove(chunk);
                WITH_3D.remove(chunk);
                WITH_CAVES.remove(chunk);
            } else {
                pending.put(chunk, now);
                PENDING_SEQ.put(chunk, seq);
                if (with3d) {
                    WITH_3D.add(chunk);
                } else {
                    WITH_3D.remove(chunk);
                }
                if (caves) {
                    WITH_CAVES.add(chunk);
                } else {
                    WITH_CAVES.remove(chunk);
                }
            }
        }
        long[] all = new long[chunks.size()];
        int n = 0;
        for (long chunk : chunks) {
            all[n++] = chunk;
        }
        for (int from = 0; from < all.length; from += ShareNetwork.LoadChunks.MAX) {
            long[] part = Arrays.copyOfRange(all, from, Math.min(all.length, from + ShareNetwork.LoadChunks.MAX));
            ShareNetwork.sendToServer(new ShareNetwork.LoadChunks(remove, with3d, regions, seq, part));
        }
    }

    /**
     * Draws the view over the flat map.
     *
     * @param selection  chunks of the drag going on, or null
     * @param removing   the drag takes chunks off the queue
     * @param regions    the chunks saved in the world are shown (in grey)
     * @param caves      the drag picks the chunks with their cave layers
     * @param savedOnly  the drag takes only chunks saved in the world: the others in it are drawn faint
     * @param cancelling the drag takes chunks off the queue: only the queued ones in it are marked
     */
    public static void draw(MapDimension surface, int dimension, double centerX, double centerZ, double scale, int x,
        int y, int width, int height, Set<Long> selection, boolean removing, boolean regions, boolean caves,
        boolean savedOnly, boolean cancelling) {
        if (regions != regionsMode) {
            regionsMode = regions;
            REGION_STATES.clear();
        }
        double left = centerX - width / 2.0 / scale, top = centerZ - height / 2.0 / scale;
        int minX = (int) Math.floor(left) >> 4, maxX = (int) Math.floor(left + width / scale) >> 4;
        int minZ = (int) Math.floor(top) >> 4, maxZ = (int) Math.floor(top + height / scale) >> 4;
        double cell = 16 * scale;
        double pixel = 1.0 / ScaledScreen.currentFactor();
        double border = Math.max(pixel, Math.min(3, cell / 12));
        Map<Long, Long> pending = PENDING_CHUNKS.computeIfAbsent(dimension, d -> new HashMap<>());
        if (regions) {
            askSaved(dimension, minX >> 5, maxX >> 5, minZ >> 5, maxZ >> 5);
        }

        begin();
        if (surface != null) {
            // States of the chunks on screen and one around (for the borders).
            int w = maxX - minX + 3, h = maxZ - minZ + 3;
            if (grid.length < w * h) {
                grid = new int[w * h];
            }
            int[] state = grid;
            if (cell >= MIN_CELL) {
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        state[j * w + i] = state(surface, pending, dimension, minX - 1 + i, minZ - 1 + j);
                    }
                }
            } else {
                // Zoomed far out: too many chunks to look at every frame. The states of each region's chunks are
                // kept and refreshed now and then, and copied in.
                Arrays.fill(state, 0, w * h, NONE);
                long now = System.currentTimeMillis();
                int x0 = minX - 1, x1 = maxX + 1, z0 = minZ - 1, z1 = maxZ + 1;
                for (int rz = z0 >> 5; rz <= z1 >> 5; rz++) {
                    for (int rx = x0 >> 5; rx <= x1 >> 5; rx++) {
                        byte[] states = regionStates(surface, pending, dimension, rx, rz, now);
                        if (states == EMPTY) {
                            continue;
                        }
                        int cx0 = Math.max(rx * 32, x0), cx1 = Math.min(rx * 32 + 31, x1);
                        int cz0 = Math.max(rz * 32, z0), cz1 = Math.min(rz * 32 + 31, z1);
                        for (int cz = cz0; cz <= cz1; cz++) {
                            int row = (cz - z0) * w - x0, local = (cz & 31) * 32;
                            for (int cx = cx0; cx <= cx1; cx++) {
                                state[row + cx] = states[local + (cx & 31)];
                            }
                        }
                    }
                }
                if (REGION_STATES.size() > MAX_CACHED_REGIONS) {
                    REGION_STATES.clear();
                }
            }
            drawStates(state, w, h, minX - 1, minZ - 1, left, top, scale, cell, border, x, y, width, height);
        }
        if (selection != null && !selection.isEmpty()) {
            int color = removing ? DELETE_BORDER
                : caves ? CAVES_BORDER : Config.chunkload3d ? PENDING_3D_BORDER : PENDING_BORDER;
            for (long chunk : selection) {
                double sx = x + (unpackX(chunk) * 16 - left) * scale, sy = y + (unpackZ(chunk) * 16 - top) * scale;
                if (cancelling) {
                    // Taking off the queue: the queued (red) chunks it covers are outlined in white.
                    if (pending.containsKey(chunk)) {
                        rect(sx, sy, cell, cell, 0xFFFFFF, 60, x, y, width, height);
                        hollowRect(sx, sy, cell, cell, pixel, 0xFFFFFF, 0xFF, x, y, width, height);
                    } else {
                        rect(sx, sy, cell, cell, 0xFFFFFF, 10, x, y, width, height);
                    }
                    continue;
                }
                if (removing) {
                    // Deleting: the chunks darkened, as if wiped off.
                    rect(sx, sy, cell, cell, 0x0C0E11, 150, x, y, width, height);
                    hollowRect(sx, sy, cell, cell, pixel, color, 0xFF, x, y, width, height);
                    continue;
                }
                if (savedOnly && !removing && !isSaved(dimension, unpackX(chunk), unpackZ(chunk))) {
                    // Not saved in the world: left out of a "saved only" pick.
                    rect(sx, sy, cell, cell, 0xFFFFFF, 10, x, y, width, height);
                    continue;
                }
                rect(sx, sy, cell, cell, 0xFFFFFF, 33, x, y, width, height);
                hollowRect(sx, sy, cell, cell, pixel, color, 0xFF, x, y, width, height);
            }
        }
        end();
    }

    /**
     * Draws the chunk states of a {@code w} x {@code h} grid whose first cell is chunk ({@code gridX}, {@code gridZ});
     * the cells along its edges only tell the borders of the ones inside. Each area gets one border around it: runs
     * of cells along a row or a column with the same edge are drawn as one rectangle.
     */
    private static void drawStates(int[] state, int w, int h, int gridX, int gridZ, double left, double top,
        double scale, double cell, double border, int x, int y, int width, int height) {
        // Fill: runs of the same state along each row, as one rectangle.
        for (int j = 1; j < h - 1; j++) {
            double sy = y + ((gridZ + j) * 16 - top) * scale;
            int i = 1;
            while (i < w - 1) {
                int s = state[j * w + i];
                int start = i;
                while (i < w - 1 && state[j * w + i] == s) {
                    i++;
                }
                if (s != NONE) {
                    double sx = x + ((gridX + start) * 16 - left) * scale;
                    rect(
                        sx,
                        sy,
                        cell * (i - start),
                        cell,
                        fill(s),
                        isPending(s) ? PENDING_ALPHA : FILL_ALPHA,
                        x,
                        y,
                        width,
                        height);
                }
            }
        }
        // Top and bottom borders: runs along each row of cells of one state whose neighbour above (below) differs.
        for (int side = -1; side <= 1; side += 2) {
            for (int j = 1; j < h - 1; j++) {
                double sy = y + ((gridZ + j) * 16 - top) * scale + (side < 0 ? 0 : cell - border);
                int i = 1;
                while (i < w - 1) {
                    int s = state[j * w + i];
                    if (s == NONE || state[(j + side) * w + i] == s) {
                        i++;
                        continue;
                    }
                    int start = i;
                    while (i < w - 1 && state[j * w + i] == s && state[(j + side) * w + i] != s) {
                        i++;
                    }
                    double sx = x + ((gridX + start) * 16 - left) * scale;
                    rect(sx, sy, cell * (i - start), border, border(s), BORDER_ALPHA, x, y, width, height);
                }
            }
        }
        // Left and right borders: the same down each column.
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 1; i < w - 1; i++) {
                double sx = x + ((gridX + i) * 16 - left) * scale + (side < 0 ? 0 : cell - border);
                int j = 1;
                while (j < h - 1) {
                    int s = state[j * w + i];
                    if (s == NONE || state[j * w + i + side] == s) {
                        j++;
                        continue;
                    }
                    int start = j;
                    while (j < h - 1 && state[j * w + i] == s && state[j * w + i + side] != s) {
                        j++;
                    }
                    double sy = y + ((gridZ + start) * 16 - top) * scale;
                    rect(sx, sy, border, cell * (j - start), border(s), BORDER_ALPHA, x, y, width, height);
                }
            }
        }
        // The corners where an area turns inward.
        for (int j = 1; j < h - 1; j++) {
            for (int i = 1; i < w - 1; i++) {
                int s = state[j * w + i];
                if (s == NONE) {
                    continue;
                }
                for (int dx = -1; dx <= 1; dx += 2) {
                    for (int dz = -1; dz <= 1; dz += 2) {
                        if (state[j * w + i + dx] == s && state[(j + dz) * w + i] == s
                            && state[(j + dz) * w + i + dx] != s) {
                            double sx = x + ((gridX + i) * 16 - left) * scale;
                            double sy = y + ((gridZ + j) * 16 - top) * scale;
                            rect(
                                dx < 0 ? sx : sx + cell - border,
                                dz < 0 ? sy : sy + cell - border,
                                border,
                                border,
                                border(s),
                                BORDER_ALPHA,
                                x,
                                y,
                                width,
                                height);
                        }
                    }
                }
            }
        }
    }

    /** States of a region's chunks for the far view, and when they were worked out. */
    private static final Map<Long, Object[]> REGION_STATES = new HashMap<>();

    /**
     * Forgets the regions' states worked out (a world left): each keeps the map it was worked out from, which kept
     * the old world's whole surface map in memory until this view was used again.
     */
    public static void forgetRegionStates() {
        REGION_STATES.clear();
    }

    /** Forgets everything known of the server's chunks and loadings (the server left): it is another one's next. */
    public static void reset() {
        REGION_STATES.clear();
        SAVED_CHUNKS.clear();
        ASKED.clear();
        PENDING_CHUNKS.clear();
        PENDING_SEQ.clear();
        WITH_3D.clear();
        WITH_CAVES.clear();
    }

    /** How often the far view looks at a region's chunks again. */
    private static final long REGION_REFRESH_MS = 500;
    private static final int MAX_CACHED_REGIONS = 4096;

    /** The 32 x 32 chunk states of a region (row by row), kept for {@link #REGION_REFRESH_MS}. */
    private static byte[] regionStates(MapDimension surface, Map<Long, Long> pending, int dimension, int rx, int rz,
        long now) {
        long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL) ^ ((long) dimension << 52);
        Object[] kept = REGION_STATES.get(key);
        if (kept != null && now - (Long) kept[0] < REGION_REFRESH_MS && kept[2] == surface) {
            return (byte[]) kept[1];
        }
        boolean anySaved = false;
        if (regionsMode) {
            Map<Long, long[]> savedRegions = SAVED_CHUNKS.get(dimension);
            anySaved = savedRegions != null && savedRegions.containsKey(regionKey(rx, rz));
        }
        if (!surface.isInMemory(rx, rz) && !hasPending(pending, rx, rz)
            && !anySaved
            && !IsoMap.INSTANCE.hasRegion(dimension, rx, rz)) {
            // Nothing of it on the map in memory, nothing picked: no need to look at its 1024 chunks.
            REGION_STATES.put(key, new Object[] { now, EMPTY, surface });
            return EMPTY;
        }
        byte[] states = new byte[32 * 32];
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                states[lz * 32 + lx] = (byte) state(surface, pending, dimension, rx * 32 + lx, rz * 32 + lz);
            }
        }
        REGION_STATES.put(key, new Object[] { now, states, surface });
        return states;
    }

    private static final byte[] EMPTY = new byte[32 * 32];

    private static boolean hasPending(Map<Long, Long> pending, int rx, int rz) {
        for (long chunk : pending.keySet()) {
            if (unpackX(chunk) >> 5 == rx && unpackZ(chunk) >> 5 == rz) {
                return true;
            }
        }
        return false;
    }

    /**
     * NONE, SAVED, one of the MAPPED states or one of the PENDING ones; a picked chunk mapped since it was picked stops
     * being picked.
     */
    private static int state(MapDimension surface, Map<Long, Long> pending, int dimension, int chunkX, int chunkZ) {
        long time = surface.chunkTimeInMemory(chunkX, chunkZ);
        if (!pending.isEmpty()) {
            Long picked = pending.get(pack(chunkX, chunkZ));
            if (picked != null) {
                long key = pack(chunkX, chunkZ);
                boolean with3d = WITH_3D.contains(key);
                if (time > picked && !with3d && !WITH_CAVES.contains(key)) {
                    pending.remove(key);
                    PENDING_SEQ.remove(key);
                } else {
                    return with3d ? PENDING_3D : PENDING;
                }
            }
        }
        boolean iso = IsoMap.INSTANCE.hasChunk(dimension, chunkX, chunkZ);
        if (time > 0) {
            return iso ? MAPPED_BOTH : MAPPED;
        }
        if (iso) {
            return MAPPED_3D;
        }
        return regionsMode && isSaved(dimension, chunkX, chunkZ) ? SAVED : NONE;
    }

    private static int fill(int state) {
        switch (state) {
            case PENDING:
                return PENDING_FILL;
            case PENDING_3D:
                return PENDING_3D_FILL;
            case SAVED:
                return SAVED_FILL;
            case MAPPED_3D:
                return MAPPED_3D_FILL;
            case MAPPED_BOTH:
                return MAPPED_BOTH_FILL;
            default:
                return MAPPED_FILL;
        }
    }

    private static int border(int state) {
        switch (state) {
            case PENDING:
                return PENDING_BORDER;
            case PENDING_3D:
                return PENDING_3D_BORDER;
            case SAVED:
                return SAVED_BORDER;
            case MAPPED_3D:
                return MAPPED_3D_BORDER;
            case MAPPED_BOTH:
                return MAPPED_BOTH_BORDER;
            default:
                return MAPPED_BORDER;
        }
    }

    private static boolean isPending(int state) {
        return state == PENDING || state == PENDING_3D;
    }

    /** The regions of the dimension the server told which chunks are saved in (bit lz * 32 + lx), by region key. */
    public static Map<Long, long[]> savedRegions(int dimension) {
        Map<Long, long[]> regions = SAVED_CHUNKS.get(dimension);
        return regions == null ? new HashMap<>() : new HashMap<>(regions);
    }

    /** Whether the chunk is queued to be loaded. */
    public static boolean isQueued(int dimension, int chunkX, int chunkZ) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        return pending != null && pending.containsKey(pack(chunkX, chunkZ));
    }

    /** Whether the server said the chunk is saved in the world (region loading view); false if not known yet. */
    private static boolean isSaved(int dimension, int chunkX, int chunkZ) {
        Map<Long, long[]> regions = SAVED_CHUNKS.get(dimension);
        long[] bits = regions == null ? null : regions.get(regionKey(chunkX >> 5, chunkZ >> 5));
        if (bits == null) {
            return false;
        }
        int bit = (chunkZ & 31) * 32 + (chunkX & 31);
        return (bits[bit >> 6] & 1L << (bit & 63)) != 0;
    }

    private static long regionKey(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Asks the server which chunks of the regions on screen are saved: those never asked or not answered for a while,
     * nearest to the middle first, a message at most every quarter second.
     */
    private static void askSaved(int dimension, int minRx, int maxRx, int minRz, int maxRz) {
        long now = System.currentTimeMillis();
        if (now - lastAsk < 250) {
            return;
        }
        Map<Long, long[]> known = SAVED_CHUNKS.computeIfAbsent(dimension, d -> new HashMap<>());
        Map<Long, Long> asked = ASKED.computeIfAbsent(dimension, d -> new HashMap<>());
        List<int[]> wanted = new ArrayList<>();
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                long key = regionKey(rx, rz);
                Long when = asked.get(key);
                long again = known.containsKey(key) ? SAVED_REFRESH_MS : ASK_AGAIN_MS;
                if (when == null || now - when >= again) {
                    wanted.add(new int[] { rx, rz });
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        final int midX = (minRx + maxRx) / 2, midZ = (minRz + maxRz) / 2;
        wanted.sort(
            (a, b) -> Integer
                .compare(Math.abs(a[0] - midX) + Math.abs(a[1] - midZ), Math.abs(b[0] - midX) + Math.abs(b[1] - midZ)));
        int count = Math.min(wanted.size(), ShareNetwork.SavedRequest.MAX);
        int[] regions = new int[count * 2];
        for (int n = 0; n < count; n++) {
            regions[n * 2] = wanted.get(n)[0];
            regions[n * 2 + 1] = wanted.get(n)[1];
            asked.put(regionKey(wanted.get(n)[0], wanted.get(n)[1]), now);
        }
        lastAsk = now;
        ShareNetwork.sendToServer(new ShareNetwork.SavedRequest(regions));
    }

    /** The server's answer: which chunks of a region are saved in the world. */
    public static void saved(ShareNetwork.SavedChunks message) {
        SAVED_CHUNKS.computeIfAbsent(message.dimension, d -> new HashMap<>())
            .put(regionKey(message.regionX, message.regionZ), message.bits);
        REGION_STATES.clear();
    }

    /**
     * A chunk was mapped by the loading (on the 3D map and its cave layers too when it was loaded for them): it is
     * done, even if it looks the same as before (its time stays then).
     */
    public static void mapped(int dimension, int chunkX, int chunkZ) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        if (pending != null && pending.remove(pack(chunkX, chunkZ)) != null) {
            // The far view shows it green at once.
            REGION_STATES.clear();
        }
        PENDING_SEQ.remove(pack(chunkX, chunkZ));
        WITH_3D.remove(pack(chunkX, chunkZ));
        WITH_CAVES.remove(pack(chunkX, chunkZ));
    }

    /**
     * The loading of the picks up to {@code upTo} ended (finished, stopped or replaced): their chunks still queued
     * are no longer waited for, in every dimension. Chunks picked since (on their way to the server) stay.
     */
    public static void ended(int upTo) {
        for (Map<Long, Long> pending : PENDING_CHUNKS.values()) {
            java.util.Iterator<Long> it = pending.keySet()
                .iterator();
            while (it.hasNext()) {
                long chunk = it.next();
                Integer picked = PENDING_SEQ.get(chunk);
                if (picked == null || picked <= upTo) {
                    it.remove();
                    PENDING_SEQ.remove(chunk);
                    WITH_3D.remove(chunk);
                    WITH_CAVES.remove(chunk);
                }
            }
        }
        REGION_STATES.clear();
    }

    /** Whether the chunk was picked with its cave layers (they are mapped along with the surface). */
    public static boolean withCaves(int chunkX, int chunkZ) {
        return WITH_CAVES.contains(pack(chunkX, chunkZ));
    }

    /**
     * Forgets the chunks picked in a dimension: a new loading of the whole area ({@code /wf regionload full}) takes
     * the place of the one they were in.
     */
    public static void clearPending(int dimension) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        if (pending != null) {
            for (long chunk : pending.keySet()) {
                PENDING_SEQ.remove(chunk);
                WITH_3D.remove(chunk);
                WITH_CAVES.remove(chunk);
            }
            pending.clear();
        }
        REGION_STATES.clear();
    }

    /** Whether any of the chunks is queued to be loaded. */
    public static boolean anyPending(int dimension, Set<Long> chunks) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        for (long chunk : chunks) {
            if (pending.containsKey(chunk)) {
                return true;
            }
        }
        return false;
    }

    /** How many of the chunks are queued to be loaded. */
    public static int countPending(int dimension, Set<Long> chunks) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (long chunk : chunks) {
            if (pending.containsKey(chunk)) {
                count++;
            }
        }
        return count;
    }

    /** How many of the chunks are on the map (or may be: their region isn't read yet). */
    public static int countOnMap(MapDimension surface, Set<Long> chunks) {
        if (surface == null) {
            return 0;
        }
        int count = 0;
        for (long chunk : chunks) {
            if (surface.chunkTimeInMemory(unpackX(chunk), unpackZ(chunk)) != 0) {
                count++;
            }
        }
        return count;
    }

    /** Shows changes to the map at once in the far view (chunks deleted from it). */
    public static void refresh() {
        REGION_STATES.clear();
    }

    /** Picked chunks still waiting, for the footer and the toolbar. */
    public static int pendingCount(int dimension) {
        Map<Long, Long> pending = PENDING_CHUNKS.get(dimension);
        return pending == null ? 0 : pending.size();
    }

    /** Picked chunks still waiting in any dimension (the Stop button is offered while there are). */
    public static int pendingCountAll() {
        int count = 0;
        for (Map<Long, Long> pending : PENDING_CHUNKS.values()) {
            count += pending.size();
        }
        return count;
    }

    /** How many of the chunks a "saved only" pick would take (those saved in the world). */
    public static int countSaved(int dimension, Set<Long> chunks) {
        int count = 0;
        for (long chunk : chunks) {
            if (isSaved(dimension, unpackX(chunk), unpackZ(chunk))) {
                count++;
            }
        }
        return count;
    }

    /**
     * The end of a drag from {@code start} toward {@code end}, kept at most {@code side} chunks from it (both
     * included) in either direction: the start stays put and the far side stops at the limit, whichever way the drag
     * goes.
     */
    public static int clampEnd(int start, int end, int side) {
        return Math.max(start - (side - 1), Math.min(start + (side - 1), end));
    }

    /**
     * The chunks of the rectangle from the chunk a drag started at ({@code x0}, {@code z0}) to the one it is at,
     * capped to {@link #MAX_SIDE} a side from the start.
     */
    public static List<Long> rectangle(int x0, int z0, int x1, int z1) {
        x1 = clampEnd(x0, x1, MAX_SIDE);
        z1 = clampEnd(z0, z1, MAX_SIDE);
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
        List<Long> chunks = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                chunks.add(pack(cx, cz));
            }
        }
        return chunks;
    }

    // ---------------------------------------------------------------- drawing helpers (batched quads)

    private static void begin() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator.instance.startDrawingQuads();
    }

    private static void end() {
        Tessellator.instance.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void rect(double rx, double ry, double w, double h, int rgb, int alpha, int x, int y, int width,
        int height) {
        double x0 = Math.max(rx, x), y0 = Math.max(ry, y);
        double x1 = Math.min(rx + w, x + width), y1 = Math.min(ry + h, y + height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorRGBA_I(rgb & 0xFFFFFF, alpha);
        tessellator.addVertex(x0, y1, 0);
        tessellator.addVertex(x1, y1, 0);
        tessellator.addVertex(x1, y0, 0);
        tessellator.addVertex(x0, y0, 0);
    }

    private static void hollowRect(double rx, double ry, double w, double h, double t, int rgb, int alpha, int x, int y,
        int width, int height) {
        rect(rx, ry, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + h - t, w, t, rgb, alpha, x, y, width, height);
        rect(rx, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
        rect(rx + w - t, ry + t, t, h - 2 * t, rgb, alpha, x, y, width, height);
    }
}
