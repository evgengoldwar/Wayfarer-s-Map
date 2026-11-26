package WayFarMap;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the mod's time goes, for the 3D log: each part of it timed per frame (minimap, world map, markers in the
 * world...), per client tick (scanning, copying blocks) and per server tick (chunk loading, the team map), next to how
 * long frames and ticks take in all. Cheap when off ({@link #on} is false): a part costs one check. No client classes
 * here, so the server's parts can use it too.
 */
public final class Perf {

    /** A part of the mod that is timed: where it runs (frame, tick or server) and what it is called in the log. */
    public enum Part {

        MINIMAP(Where.FRAME, "minimap", null),
        WORLD_MAP(Where.FRAME, "worldMap", null),
        ISO_DRAW(Where.FRAME, "3dDraw", WORLD_MAP),
        ISO_UPLOAD(Where.FRAME, "3dUpload", ISO_DRAW),
        /** Block looks worked out for the tile renderers, on frames while the 3D view is open. */
        ISO_LOOKS(Where.FRAME, "3dLooks", ISO_DRAW),
        /** Drawing the tiles on screen (and queueing the missing ones). */
        ISO_TILES(Where.FRAME, "3dTiles", ISO_DRAW),
        /** Looking for tiles to free, every frame. */
        ISO_EVICT(Where.FRAME, "3dEvict", ISO_DRAW),
        MARKERS(Where.FRAME, "worldMarkers", null),
        ITEM_PICTURES(Where.FRAME, "itemPictures", null),
        SCAN_2D(Where.TICK, "scan2d", null),
        /** Mapping the chunks sent for /wf chunkload on the client (their surface, caves, and 3D copies). */
        CHUNKLOAD_CLIENT(Where.TICK, "chunkloadClient", null),
        CAPTURE_3D(Where.TICK, "capture3d", null),
        /** Block looks of the chunks copied, worked out ahead a little each tick. */
        LOOKS_TICK(Where.TICK, "3dLooksAhead", CAPTURE_3D),
        /** Block pictures drawn the tick before, read back from the graphics card and stored. */
        PICTURES_READ(Where.TICK, "3dPicturesRead", CAPTURE_3D),
        /** Looking for the machines of the coverage layers (AE2, chargers, drone centres, mob guards). */
        COVERAGE_SCAN(Where.TICK, "coverageScan", null),
        CHUNKLOAD(Where.SERVER, "chunkload", null),
        TEAM_MAP(Where.SERVER, "teamMap", null);

        public final Where where;
        public final String label;
        /** The part this one is timed inside of (its time is in that one's too), or null. */
        public final Part inside;
        private final AtomicLong nanos = new AtomicLong(), count = new AtomicLong(), max = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();

        Part(Where where, String label, Part inside) {
            this.where = where;
            this.label = label;
            this.inside = inside;
        }

        void add(long time) {
            nanos.addAndGet(time);
            totalNanos.addAndGet(time);
            count.incrementAndGet();
            max.accumulateAndGet(time, Math::max);
        }
    }

    /** Frames of the game, its client ticks, or its integrated server's ticks. */
    public enum Where {
        FRAME,
        TICK,
        SERVER
    }

    /** Timing is on (the 3D log is written). */
    public static volatile boolean on;

    /** How long a frame, a client tick and a server tick took, since the last {@link #take}. */
    private static final Span FRAMES = new Span(), TICKS = new Span(), SERVER_TICKS = new Span();
    private static long frameStart, tickStart, serverTickStart;

    private Perf() {}

    /** Start of a timed part: pass what it gives to {@link #end}. */
    public static long start() {
        return on ? System.nanoTime() : 0L;
    }

    public static void end(Part part, long start) {
        if (start != 0L) {
            part.add(System.nanoTime() - start);
        }
    }

    /** A part's time measured elsewhere. */
    public static void add(Part part, long nanos) {
        if (on) {
            part.add(nanos);
        }
    }

    /** A frame begins: the one before took from its start to now. */
    public static void frame() {
        long now = System.nanoTime();
        if (on && frameStart != 0L) {
            FRAMES.add(now - frameStart);
        }
        frameStart = on ? now : 0L;
    }

    public static void tickStart() {
        tickStart = on ? System.nanoTime() : 0L;
    }

    public static void tickEnd() {
        if (tickStart != 0L) {
            TICKS.add(System.nanoTime() - tickStart);
            tickStart = 0L;
        }
    }

    public static void serverTickStart() {
        serverTickStart = on ? System.nanoTime() : 0L;
    }

    public static void serverTickEnd() {
        if (serverTickStart != 0L) {
            SERVER_TICKS.add(System.nanoTime() - serverTickStart);
            serverTickStart = 0L;
        }
    }

    /** Durations of frames or ticks: how many, their sum and the slowest, and the last ones for percentiles. */
    public static final class Span {

        private static final int KEPT = 8192;
        private long count, sum, max;
        private long[] times = new long[KEPT];
        private int kept;
        private long totalCount, totalSum;

        synchronized void add(long nanos) {
            count++;
            sum += nanos;
            max = Math.max(max, nanos);
            totalCount++;
            totalSum += nanos;
            if (kept < KEPT) {
                times[kept++] = nanos;
            }
        }

        /** {count, sum, max, p50, p95, p99} since the last time, then starts again. */
        synchronized long[] take() {
            long[] sorted = Arrays.copyOf(times, kept);
            Arrays.sort(sorted);
            long[] result = { count, sum, max, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99) };
            count = sum = max = 0;
            kept = 0;
            return result;
        }

        /** {count, sum} since the log started. */
        synchronized long[] totals() {
            return new long[] { totalCount, totalSum };
        }

        synchronized void reset() {
            count = sum = max = totalCount = totalSum = 0;
            kept = 0;
        }
    }

    private static long percentile(long[] sorted, int percent) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, sorted.length * percent / 100)];
    }

    /** What was measured since the last time, then starts again. */
    public static final class Sample {

        /** {count, sum, max, p50, p95, p99} of frames, client ticks and server ticks (nanoseconds). */
        public final long[] frames, ticks, serverTicks;
        /** Per part (by ordinal): {nanos, count, max}. */
        public final long[][] parts = new long[Part.values().length][];

        Sample(long[] frames, long[] ticks, long[] serverTicks) {
            this.frames = frames;
            this.ticks = ticks;
            this.serverTicks = serverTicks;
        }
    }

    public static Sample take() {
        Sample sample = new Sample(FRAMES.take(), TICKS.take(), SERVER_TICKS.take());
        for (Part part : Part.values()) {
            sample.parts[part.ordinal()] = new long[] { part.nanos.getAndSet(0), part.count.getAndSet(0),
                part.max.getAndSet(0) };
        }
        return sample;
    }

    /** Since the log started: {frames, client ticks, server ticks} each as {count, nanos}, and each part's nanos. */
    public static long[][] totals() {
        long[] parts = new long[Part.values().length];
        for (Part part : Part.values()) {
            parts[part.ordinal()] = part.totalNanos.get();
        }
        return new long[][] { FRAMES.totals(), TICKS.totals(), SERVER_TICKS.totals(), parts };
    }

    /** Starts counting from nothing (a new log). */
    public static void reset() {
        FRAMES.reset();
        TICKS.reset();
        SERVER_TICKS.reset();
        for (Part part : Part.values()) {
            part.nanos.set(0);
            part.count.set(0);
            part.max.set(0);
            part.totalNanos.set(0);
        }
        frameStart = tickStart = serverTickStart = 0L;
    }
}
