package WayFarMap.client.map;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import WayFarMap.WayFarMap;

/** All map regions of one dimension of one world or server. */
public class MapDimension {

    public final int dimensionId;
    /** A cave layer (dimN/caves/L), not the surface or the biome map. */
    public final boolean cave;
    private final File directory;
    /**
     * Folder read instead where this map has no file of a region yet (the surface, for the map without plants), or
     * null. What is read from it is saved here once it changes.
     */
    private final File fallbackDirectory;
    /** The surface without grass and flowers, written along with this surface map; null for other maps. */
    private MapDimension plantless;
    private final ExecutorService loadExecutor;
    private final Map<Long, MapRegion> regions = new HashMap<>();
    /** Regions being read from disk in the background. */
    private final Map<Long, Future<MapRegion>> loading = new HashMap<>();
    /** Regions known to have no file on disk, so we don't probe the file system every frame. */
    private final Set<Long> missing = new HashSet<>();
    /** Reduced copies for drawing zoomed out, and those being built from files in the background. */
    private final Map<Long, LodTile> lods = new HashMap<>();
    private final Map<Long, Future<LodTile>> lodLoading = new HashMap<>();

    /** Which regions {@link #retain} keeps, by region coordinates. */
    public interface RegionFilter {

        boolean keep(int rx, int rz);
    }

    /** Short name of the map for the flat map log (surface, biomes, cave layer folder). */
    private final String label;
    /** For the log: region reads and saves queued or running, of every map (the loader's and saver's backlog). */
    private static final AtomicInteger READS_QUEUED = new AtomicInteger(), SAVES_QUEUED = new AtomicInteger();

    public MapDimension(int dimensionId, File directory, ExecutorService loadExecutor) {
        this(dimensionId, directory, loadExecutor, null);
    }

    /** @param fallbackDirectory read where this map has no file of a region yet (see {@link #fallbackDirectory}) */
    public MapDimension(int dimensionId, File directory, ExecutorService loadExecutor, File fallbackDirectory) {
        this.dimensionId = dimensionId;
        this.directory = directory;
        this.fallbackDirectory = fallbackDirectory;
        this.loadExecutor = loadExecutor;
        // dimN, dimN/bare, dimN/biomes, dimN/caves/L, dimN/caves/L/bare
        File parent = directory.getParentFile();
        String name = directory.getName();
        // A cave layer's map without plants is a cave layer too (drawn dark, lit by its own lights).
        File layer = name.equals(PLANTLESS_FOLDER) ? parent : directory;
        File layers = layer == null ? null : layer.getParentFile();
        this.cave = layers != null && layers.getName()
            .equals("caves");
        if (cave) {
            File dim = layers.getParentFile();
            this.label = (dim == null ? "" : dim.getName() + "/") + "cave"
                + layer.getName()
                + (layer == directory ? "" : "/" + name);
        } else if (parent != null && (name.equals("biomes") || name.equals(PLANTLESS_FOLDER))) {
            this.label = parent.getName() + "/" + name;
        } else {
            this.label = name;
        }
    }

    /** Short name of the map for the log. */
    String label() {
        return label;
    }

    /** Who asked for a region from the render thread, for the log (not the scanner's own frames). */
    private static String caller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 2; i < stack.length && i < 8; i++) {
            String cls = stack[i].getClassName();
            if (!cls.endsWith("MapDimension")) {
                return cls.substring(cls.lastIndexOf('.') + 1) + "." + stack[i].getMethodName();
            }
        }
        return "?";
    }

    /** Folder of the region files. */
    public File getDirectory() {
        return directory;
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Pixel of a block from what is in memory, the full region or else its reduced copy (zoomed out); 0 if neither
     * is loaded. Never reads from disk.
     */
    public int peekPixel(int x, int z) {
        long key = key(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        int lx = x & (MapRegion.SIZE - 1), lz = z & (MapRegion.SIZE - 1);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region.getPixel(lx, lz);
        }
        LodTile tile = lods.get(key);
        return tile == null ? 0 : tile.getPixel(lx / LodTile.FACTOR, lz / LodTile.FACTOR);
    }

    /**
     * Whether the chunk is on the map, from what is in memory: when it was mapped (0 = never) from the full region;
     * else 1 if the reduced copy shows it and 0 if not; 0 if the region has no file; -1 if nothing is in memory.
     */
    public long chunkTimeInMemory(int chunkX, int chunkZ) {
        long key = key(chunkX >> 5, chunkZ >> 5);
        int lx = chunkX & 31, lz = chunkZ & 31;
        MapRegion region = regions.get(key);
        if (region != null) {
            return region.getChunkTime(lx, lz);
        }
        LodTile tile = lods.get(key);
        if (tile != null) {
            // The middle of the chunk in the reduced copy (4 pixels a chunk).
            return (tile.getPixel(lx * 4 + 2, lz * 4 + 2) >>> 24) != 0 ? 1 : 0;
        }
        return missing.contains(key) ? 0 : -1;
    }

    /** Whether the full region or its reduced copy is in memory. */
    public boolean isInMemory(int rx, int rz) {
        long key = key(rx, rz);
        return regions.containsKey(key) || lods.containsKey(key);
    }

    /** Like {@link #peekPixel}, for the extra byte (height or biome); 0 if unknown. */
    public int peekExtra(int x, int z) {
        long key = key(x >> MapRegion.SHIFT, z >> MapRegion.SHIFT);
        int lx = x & (MapRegion.SIZE - 1), lz = z & (MapRegion.SIZE - 1);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region.getExtra(lx, lz);
        }
        LodTile tile = lods.get(key);
        return tile == null ? 0 : tile.getExtra(lx / LodTile.FACTOR, lz / LodTile.FACTOR);
    }

    /** Folder of the surface map without grass and flowers, inside the surface's folder. */
    public static final String PLANTLESS_FOLDER = "bare";

    /** Surface map: makes its map without grass and flowers, kept along with it (scans write both). */
    public MapDimension withPlantless() {
        plantless = new MapDimension(dimensionId, new File(directory, PLANTLESS_FOLDER), loadExecutor, directory);
        return this;
    }

    /** The surface without grass and flowers, or null if this map has none. */
    public MapDimension plantless() {
        return plantless;
    }

    /** Folder read where this map has no file of a region yet, or null. */
    public File getFallbackDirectory() {
        return fallbackDirectory;
    }

    /** Whether the region is known to have no file (and none was made): nothing to draw there. */
    public boolean isKnownMissing(int rx, int rz) {
        return missing.contains(key(rx, rz));
    }

    /** Region that is already in memory, or null. */
    public MapRegion getLoadedRegion(int rx, int rz) {
        return regions.get(key(rx, rz));
    }

    /**
     * The region to look at which chunks it has: from memory, else read from its file without keeping it (a look over
     * the whole dimension must not hold every region); null if there is none. Blocks while reading.
     */
    public MapRegion peekRegion(int rx, int rz) {
        long key = key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null || missing.contains(key)) {
            return region;
        }
        return readFile(directory, fallbackDirectory, rx, rz, label, "peek", 0);
    }

    /**
     * Returns the region, loading it from disk if needed. Blocks while the region is being read.
     *
     * @param create create an empty region if none exists on disk
     * @return the region, or null if it doesn't exist and {@code create} is false
     */
    public MapRegion getRegion(int rx, int rz, boolean create) {
        long key = key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null) {
            return region;
        }
        Future<MapRegion> pending = loading.remove(key);
        boolean log = FlatLog.on();
        if (pending != null) {
            boolean done = pending.isDone();
            long start = System.nanoTime();
            region = await(pending, rx, rz);
            if (log) {
                if (!done) {
                    FlatLog.blockingRead(label, rx, rz, System.nanoTime() - start, true, caller());
                }
                FlatLog.picked(label, rx, rz, region != null, caller() + (done ? "" : "(waited)"));
            }
        } else if (!missing.contains(key)) {
            long start = System.nanoTime();
            String why = log ? "blocking:" + caller() : "blocking";
            region = readFile(directory, fallbackDirectory, rx, rz, label, why, 0);
            if (log) {
                FlatLog.blockingRead(label, rx, rz, System.nanoTime() - start, false, caller());
            }
        }
        if (region == null) {
            missing.add(key);
            if (!create) {
                return null;
            }
            region = new MapRegion(rx, rz);
            missing.remove(key);
            FlatLog.made(label, rx, rz);
        }
        regions.put(key, region);
        return region;
    }

    /**
     * Non-blocking lookup for rendering: returns the region if it is in memory, otherwise starts reading it in the
     * background and returns null until it is ready.
     */
    public MapRegion requestRegion(int rx, int rz) {
        long key = key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null || missing.contains(key)) {
            return region;
        }
        Future<MapRegion> pending = loading.get(key);
        if (pending == null) {
            startRead(key, rx, rz, "draw");
            return null;
        }
        if (!pending.isDone()) {
            return null;
        }
        loading.remove(key);
        region = await(pending, rx, rz);
        FlatLog.picked(label, rx, rz, region != null, "draw");
        if (region == null) {
            missing.add(key);
        } else {
            regions.put(key, region);
        }
        return region;
    }

    /**
     * Makes the region ready to be written to without blocking the game: true if it is in memory (or doesn't exist
     * yet and will be created), false while it is still being read from disk in the background.
     */
    public boolean prepareRegion(int rx, int rz) {
        long key = key(rx, rz);
        // The map without plants is written along with the surface: both are read at once.
        boolean plantlessReady = plantless == null || plantless.prepareRegion(rx, rz);
        if (regions.containsKey(key) || missing.contains(key)) {
            return plantlessReady;
        }
        Future<MapRegion> pending = loading.get(key);
        if (pending == null) {
            startRead(key, rx, rz, "scan");
            return false;
        }
        // A finished read is picked up by getRegion without waiting.
        return pending.isDone() && plantlessReady;
    }

    private void startRead(long key, int rx, int rz, String why) {
        final File dir = directory, fallback = fallbackDirectory;
        final String name = label;
        final long asked = System.nanoTime();
        READS_QUEUED.incrementAndGet();
        loading.put(key, loadExecutor.submit(() -> {
            try {
                return readFile(dir, fallback, rx, rz, name, why, asked);
            } finally {
                READS_QUEUED.decrementAndGet();
            }
        }));
        FlatLog.readAsked(label, rx, rz, why, loading.size() + lodLoading.size());
    }

    /** How often a reduced copy of a region that keeps changing (around the player) is rebuilt. */
    private static final long LOD_REBUILD_MS = 1000;

    /**
     * Non-blocking lookup of the reduced copy for drawing zoomed out: built from the region if it is in memory,
     * otherwise read from its file in the background (only the reduced copy is kept). Null until ready or if the
     * region doesn't exist.
     */
    public LodTile requestLod(int rx, int rz) {
        long key = key(rx, rz);
        LodTile tile = lods.get(key);
        MapRegion region = regions.get(key);
        if (region != null) {
            if (tile == null) {
                long start = System.nanoTime();
                tile = new LodTile();
                tile.update(region);
                lods.put(key, tile);
                FlatLog.lod(label, rx, rz, "region in memory", System.nanoTime() - start);
            } else if (tile.sourceChanges != region.getChanges()
                && System.currentTimeMillis() - tile.builtAt >= LOD_REBUILD_MS) {
                    long start = System.nanoTime();
                    tile.update(region);
                    FlatLog.lod(label, rx, rz, "region changed", System.nanoTime() - start);
                }
            return tile;
        }
        if (tile != null || missing.contains(key)) {
            return tile;
        }
        Future<LodTile> pending = lodLoading.get(key);
        if (pending == null) {
            final File dir = directory, fallback = fallbackDirectory;
            final String name = label;
            final long asked = System.nanoTime();
            READS_QUEUED.incrementAndGet();
            lodLoading.put(key, loadExecutor.submit(() -> {
                try {
                    MapRegion read = readFile(dir, fallback, rx, rz, name, "reduced", asked);
                    if (read == null) {
                        return null;
                    }
                    long start = System.nanoTime();
                    LodTile built = LodTile.of(read.pixelArray(), read.extraArray(), read.lightArray());
                    FlatLog.lod(name, rx, rz, "file", System.nanoTime() - start);
                    return built;
                } finally {
                    READS_QUEUED.decrementAndGet();
                }
            }));
            FlatLog.readAsked(label, rx, rz, "reduced", loading.size() + lodLoading.size());
            return null;
        }
        if (!pending.isDone()) {
            return null;
        }
        lodLoading.remove(key);
        try {
            tile = pending.get();
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region", e);
            FlatLog.problem("READ_FAILED", label, rx, rz, "reduced copy's read threw " + e);
            tile = null;
        }
        FlatLog.picked(label, rx, rz, tile != null, "reduced");
        if (tile == null) {
            missing.add(key);
        } else {
            lods.put(key, tile);
        }
        return tile;
    }

    private MapRegion await(Future<MapRegion> future, int rx, int rz) {
        try {
            return future.get();
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region", e);
            FlatLog.problem("READ_FAILED", label, rx, rz, "background read threw " + e);
            return null;
        }
    }

    /**
     * Reads the region from the folder, or from the fallback folder (if any) where the folder has no file of it.
     *
     * @param why   what the read is for (draw, scan, reduced, blocking:caller), for the log
     * @param asked when it was queued (System.nanoTime), 0 for a read on the caller's thread
     */
    private static MapRegion readFile(File directory, File fallback, int rx, int rz, String label, String why,
        long asked) {
        if (fallback != null && !MapRegion.getFile(directory, rx, rz)
            .isFile()) {
            return readFile(fallback, null, rx, rz, label + "(fallback)", why, asked);
        }
        return readFile(directory, rx, rz, label, why, asked);
    }

    private static MapRegion readFile(File directory, int rx, int rz, String label, String why, long asked) {
        long start = System.nanoTime();
        File file = MapRegion.getFile(directory, rx, rz);
        // Time waiting for a loader thread, and how many reads were queued or running then.
        String queue = !FlatLog.on() ? ""
            : " for=" + why
                + (asked != 0 ? " queuedMs=" + FlatLog.ms(start - asked) : "")
                + " loaderBacklog="
                + READS_QUEUED.get();
        if (asked != 0) {
            FlatLog.readQueued(start - asked);
        }
        if (!file.isFile()) {
            if (FlatLog.on()) {
                FlatLog.read(label, rx, rz, System.nanoTime() - start, 0, "-", "NO_FILE" + queue);
            }
            return null;
        }
        MapRegion.IoTrace trace = new MapRegion.IoTrace();
        try {
            MapRegion region = MapRegion.read(file, rx, rz, trace);
            if (FlatLog.on()) {
                FlatLog.png(false, trace.pngNanos());
                FlatLog.read(
                    label,
                    rx,
                    rz,
                    System.nanoTime() - start,
                    MapRegion.bytesOnDisk(file),
                    trace.toString(),
                    (trace.warnings() > 0 ? "PARTIAL" : "OK") + queue);
                if (trace.warnings() > 0) {
                    FlatLog.problem("READ_WARN", label, rx, rz, trace.warnings() + "x, first: " + trace.firstWarning());
                }
            }
            return region;
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not load map region " + file, e);
            if (FlatLog.on()) {
                FlatLog.read(
                    label,
                    rx,
                    rz,
                    System.nanoTime() - start,
                    MapRegion.bytesOnDisk(file),
                    trace + " onDisk=" + MapRegion.partsOnDisk(file),
                    "FAILED " + e + queue);
                FlatLog.problem(
                    "READ_FAILED",
                    label,
                    rx,
                    rz,
                    e + " (the region counts as missing: a new one is made over it and saved, losing the old)");
            }
            return null;
        }
    }

    /** Queues all modified regions for writing on the given executor. */
    public List<Future<?>> save(ExecutorService executor) {
        List<Future<?>> futures = new ArrayList<>();
        long copyTotal = 0;
        final String name = label;
        for (MapRegion region : regions.values()) {
            if (!region.isSaveDirty()) {
                continue;
            }
            final int changed = region.getChanges() - region.changesAtSave;
            region.changesAtSave = region.getChanges();
            // How long its changes waited for this save, and whether the save before is still being written.
            final long dirtyMs = region.dirtyForMs();
            final boolean stillSaving = region.isSaving();
            FlatLog.unsavedFor(dirtyMs);
            long copyStart = System.nanoTime();
            final MapRegion.Snapshot data = region.snapshotForSave();
            long copy = System.nanoTime() - copyStart;
            copyTotal += copy;
            FlatLog.saveCopy(label, region.rx, region.rz, copy);
            final File file = MapRegion.getFile(directory, region.rx, region.rz);
            final long queued = System.nanoTime();
            final int backlog = SAVES_QUEUED.incrementAndGet();
            futures.add(executor.submit(() -> {
                long start = System.nanoTime();
                String result = "OK";
                MapRegion.IoTrace trace = new MapRegion.IoTrace();
                try {
                    MapRegion.write(file, data, trace);
                } catch (Exception e) {
                    WayFarMap.LOG.warn("Could not save map region " + file, e);
                    result = "FAILED " + e;
                } finally {
                    region.onSaved();
                    SAVES_QUEUED.decrementAndGet();
                }
                if (FlatLog.on()) {
                    FlatLog.saveQueued(start - queued);
                    FlatLog.png(true, trace.pngNanos());
                    FlatLog.saved(
                        name,
                        region.rx,
                        region.rz,
                        System.nanoTime() - start,
                        MapRegion.bytesOnDisk(file),
                        trace.toString(),
                        result + " queuedMs="
                            + FlatLog.ms(start - queued)
                            + " saverBacklogWhenQueued="
                            + backlog
                            + " unsavedForMs="
                            + dirtyMs
                            + (stillSaving ? " (queued while the save before was still being written)" : "")
                            + " changesSinceLastSave="
                            + changed);
                    if (result.startsWith("FAILED")) {
                        FlatLog.problem(
                            "SAVE_FAILED",
                            name,
                            region.rx,
                            region.rz,
                            result.substring(7) + " (changes stay in memory only until the next save)");
                    } else if (trace.warnings() > 0) {
                        FlatLog.problem(
                            "SAVE_WARN",
                            name,
                            region.rx,
                            region.rz,
                            trace.warnings() + "x, first: " + trace.firstWarning());
                    }
                }
            }));
        }
        FlatLog.saveRound(label, futures.size(), copyTotal);
        return futures;
    }

    /**
     * Forgets a region and deletes its files (render thread). A save of it under way is waited for first, or it would
     * write the region back; changes not saved yet are dropped with it.
     */
    public void deleteRegion(int rx, int rz) {
        long key = key(rx, rz);
        MapRegion region = regions.remove(key);
        if (region != null) {
            region.deleteTexture();
            long until = System.currentTimeMillis() + 3000;
            while (region.isSaving() && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                    break;
                }
            }
        }
        LodTile tile = lods.remove(key);
        if (tile != null) {
            tile.deleteTexture();
        }
        Future<MapRegion> read = loading.remove(key);
        if (read != null) {
            read.cancel(false);
        }
        Future<LodTile> lodRead = lodLoading.remove(key);
        if (lodRead != null) {
            lodRead.cancel(false);
        }
        MapRegion.deleteFiles(directory, rx, rz);
        missing.add(key);
        FlatLog.log(
            "DELETE " + label
                + " r."
                + rx
                + "."
                + rz
                + (region == null ? " (not in memory)"
                    : region.isSaving() ? " (a save of it was still running after 3 s: it may write it back)" : ""));
    }

    /** Releases everything farther than {@code radius} regions from the given one (see {@link #retain}). */
    public void trim(int centerRx, int centerRz, int radius) {
        retain((rx, rz) -> Math.abs(rx - centerRx) <= radius && Math.abs(rz - centerRz) <= radius);
    }

    /**
     * Frees everything the filter doesn't keep: textures and reduced copies, regions that are saved (they are read
     * again when needed) and background reads that nobody waits for anymore. Changed regions stay until saved.
     */
    public void retain(RegionFilter filter) {
        int freed = 0, keptUnsaved = 0, textures = 0, lodsFreed = 0;
        int cancelled = loading.size() + lodLoading.size();
        Iterator<MapRegion> it = regions.values()
            .iterator();
        while (it.hasNext()) {
            MapRegion region = it.next();
            if (filter.keep(region.rx, region.rz)) {
                continue;
            }
            if (region.hasTexture()) {
                textures++;
            }
            region.deleteTexture();
            if (!region.isSaveDirty() && !region.isSaving()) {
                it.remove();
                freed++;
            } else {
                keptUnsaved++;
            }
        }
        Iterator<Map.Entry<Long, LodTile>> lodIt = lods.entrySet()
            .iterator();
        while (lodIt.hasNext()) {
            Map.Entry<Long, LodTile> entry = lodIt.next();
            if (!filter.keep(rx(entry.getKey()), rz(entry.getKey()))) {
                entry.getValue()
                    .deleteTexture();
                lodIt.remove();
                lodsFreed++;
            }
        }
        dropPending(loading, filter);
        dropPending(lodLoading, filter);
        // Regions known to have no file, outside too: one for every region the map was moved over, all game long.
        missing.removeIf(key -> !filter.keep(rx(key), rz(key)));
        cancelled -= loading.size() + lodLoading.size();
        FlatLog.retained(label, freed, keptUnsaved, textures, lodsFreed, cancelled, regions.size());
    }

    /** Cancels background reads outside the filter; a read already running just finishes and is dropped. */
    private static <T> void dropPending(Map<Long, Future<T>> pending, RegionFilter filter) {
        Iterator<Map.Entry<Long, Future<T>>> it = pending.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Future<T>> entry = it.next();
            if (!filter.keep(rx(entry.getKey()), rz(entry.getKey()))) {
                entry.getValue()
                    .cancel(false);
                it.remove();
            }
        }
    }

    private static int rx(long key) {
        return (int) (key >> 32);
    }

    private static int rz(long key) {
        return (int) key;
    }

    /** For the log's statistics: regions and reduced copies in memory, textures, background reads. */
    int regionCount() {
        return regions.size();
    }

    /** Regions with changes not queued for saving. */
    int dirtyCount() {
        int count = 0;
        for (MapRegion region : regions.values()) {
            if (region.isSaveDirty()) {
                count++;
            }
        }
        return count;
    }

    int lodCount() {
        return lods.size();
    }

    int textureCount() {
        int count = 0;
        for (MapRegion region : regions.values()) {
            if (region.hasTexture()) {
                count++;
            }
        }
        for (LodTile tile : lods.values()) {
            if (tile.hasTexture()) {
                count++;
            }
        }
        return count;
    }

    int pendingCount() {
        return loading.size() + lodLoading.size();
    }

    public void deleteTextures() {
        for (MapRegion region : regions.values()) {
            region.deleteTexture();
        }
        for (LodTile tile : lods.values()) {
            tile.deleteTexture();
        }
    }
}
