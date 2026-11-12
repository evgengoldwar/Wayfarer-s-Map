package WayFarMap.client.map.iso;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;
import WayFarMap.Perf;
import WayFarMap.WayFarMap;
import WayFarMap.client.GlTextures;
import WayFarMap.client.MapDrawer;

/**
 * The tiles of the 3D map: squares of the projection plane, 128x128 pixels, at ten levels of detail (64 pixels per
 * block down to 1 pixel per 8 blocks), like Dynmap's tiles. The ones on screen are drawn by background threads,
 * nearest to the middle first; the coarser levels are kept on disk ({@code dim<id>/iso/}) and drawn again only when a
 * chunk they show changed. Changes while the map is open redraw the tiles they touch.
 */
final class IsoTiles {

    /** Tiles with pictures kept in video memory (two textures each: day and night, 128 KB together). */
    private static final int MAX_TILES = 800;
    /**
     * Tiles kept in all, most of them empty (nothing explored there: no textures, a few bytes): dropping those made
     * them read from their files again and again.
     */
    private static final int MAX_ALL_TILES = 20_000;
    /**
     * Levels from this one on (8 pixels per block and less) are saved to disk, so a part of the map seen before opens
     * at once; finer ones are quick to draw and would be too many files.
     */
    private static final int DISK_LEVEL = 3;
    private static final int MAGIC = 0x57465440; // "WFT@"
    /** Added to the priority of a tile only out of date, so every tile with nothing to show yet goes first. */
    private static final double STALE_AFTER_MISSING = 1e9;
    /**
     * A tile showing an older picture is drawn again at most this often (ms) at the finest levels, twice as seldom
     * every two coarser ones (up to 16 s): while new chunks keep coming, a coarse tile covering many of them took up
     * to two seconds and was drawn again for each, keeping the renderers from the tiles just zoomed to.
     */
    private static final long REFRESH_MS = 1000, REFRESH_MAX_MS = 16_000;
    /**
     * A tile with a hole (a chunk stored where it showed nothing: drawn while flying before the chunk got there) is
     * drawn again at most this often, as soon as tiles with nothing to show: waiting like a tile only out of date, the
     * hole stayed black for seconds (11 s on average at level 4 in a flight's log) while zooming queued hundreds of
     * new tiles ahead of it.
     */
    private static final long HOLE_REFRESH_MS = 500;

    /** Whether a tile shows a hole where a chunk was stored since it was drawn. */
    private static boolean hasHole(Tile tile) {
        return tile.ready && tile.holeSince != 0 && tile.holeSince > tile.renderedAt;
    }

    /** Whether a tile showing an older picture may be drawn again now. */
    private static boolean refreshDue(Tile tile, long now) {
        if (hasHole(tile)) {
            return now - tile.renderedAt >= HOLE_REFRESH_MS;
        }
        long interval = Math.min(REFRESH_MAX_MS, REFRESH_MS << Math.min(4, tile.key.level / 2));
        return now - tile.renderedAt >= interval;
    }

    /** Tiles uploaded to the graphics card per frame. */
    private static final int UPLOADS_PER_FRAME = 12;
    /**
     * A queued tile that has been off screen this long is not drawn: zooming through the levels queues hundreds of
     * tiles each, which kept the ones on screen waiting for seconds.
     */
    private static final long UNWANTED_MS = 400;
    private static final int PIXELS = IsoProjection.TILE_PIXELS;

    /** Which tile: dimension, view side, level and position on the projection plane. */
    static final class Key {

        final int dimension, rotation, level, tu, tv;

        Key(int dimension, int rotation, int level, int tu, int tv) {
            this.dimension = dimension;
            this.rotation = rotation;
            this.level = level;
            this.tu = tu;
            this.tv = tv;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return k.dimension == dimension && k.rotation == rotation && k.level == level && k.tu == tu && k.tv == tv;
        }

        @Override
        public int hashCode() {
            return (((dimension * 31 + rotation) * 31 + level) * 92821 + tu) * 31 + tv;
        }
    }

    private static final class Tile {

        final Key key;
        int texture = -1;
        /** The tile at night: moonlight, and warm light around torches and lamps. */
        int nightTexture = -1;
        boolean empty;
        boolean ready;
        /** Height and side of what each pixel shows, for finding the block under the mouse. */
        short[] hits;
        /** Height where each pixel stops being see-through ({@link #solidCode}), for hiding mobs behind it. */
        short[] solid;
        long renderedAt;
        /** Last time a chunk it shows changed. */
        volatile long dirtyAt;
        volatile long wantedAt;
        /** Frame it was last on screen in. */
        volatile long wantedFrame;
        boolean queued;
        long lastDrawn;
        /**
         * For the log: the oldest chunk change not shown yet (ms, 0 if none), and when it was first queued with
         * nothing to show (nanos, 0 once shown).
         */
        long changedSince, firstQueued;
        /** The oldest chunk stored where it showed nothing since it was drawn (ms), 0 if none. */
        volatile long holeSince;
        /** The job that draws it ({@link Job#order}); older ones still queued are left out. */
        volatile long job;
        /** Its job was queued as only out of date (behind the tiles with nothing to show). */
        boolean queuedLate;

        Tile(Key key) {
            this.key = key;
        }

        boolean stale() {
            return ready && dirtyAt > renderedAt;
        }
    }

    private static final class Job implements Comparable<Job> {

        final Tile tile;
        final IsoMap.Dimension dimension;
        final double priority;
        final long order;
        final long created = System.nanoTime();

        Job(Tile tile, IsoMap.Dimension dimension, double priority, long order) {
            this.tile = tile;
            this.dimension = dimension;
            this.priority = priority;
            this.order = order;
        }

        @Override
        public int compareTo(Job o) {
            int c = Double.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }

    private static final class Result {

        final Tile tile;
        final int[] pixels;
        final int[] nightPixels;
        final short[] hits;
        short[] solid;
        final long renderedAt;
        /** Not drawn (no longer on screen): the tile is only free to be queued again. */
        final boolean skipped;
        /** Read from the tile's file, not traced (for the log). */
        boolean fromDisk;
        /** For the log: when its job was queued, and when the renderer finished it (nanos). */
        long queuedAt, finishedAt;
        /** Read from its file: how far toward the viewer its rays went ({@link IsoTracer#minToward}). */
        double minToward;
        /** Made from the four finer tiles' files instead of traced (for the log). */
        boolean composed;
        /** Why it is drawn again, for the log; null if it isn't. */
        String retryWhy;
        /**
         * Drawn with some blocks not as they look (the game didn't give a block's look in time, a sprite couldn't be
         * read): not saved, drawn again, and not shown over a good picture of the tile.
         */
        boolean retry;

        Result(Tile tile, int[] pixels, int[] nightPixels, short[] hits, long renderedAt) {
            this(tile, pixels, nightPixels, hits, renderedAt, false);
        }

        Result(Tile tile, int[] pixels, int[] nightPixels, short[] hits, long renderedAt, boolean skipped) {
            this.tile = tile;
            this.pixels = pixels;
            this.nightPixels = nightPixels;
            this.hits = hits;
            this.renderedAt = renderedAt;
            this.skipped = skipped;
        }
    }

    private final IsoMap map;
    private final Map<Key, Tile> tiles = new HashMap<>();
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final Queue<Result> done = new ConcurrentLinkedQueue<>();
    private final AtomicLong order = new AtomicLong();
    private final Thread[] workers;
    private volatile boolean running = true;
    private IntBuffer uploadBuffer;
    private volatile long frame;
    /** {@link Config#isoSmooth} the tiles in memory were drawn with. */
    private boolean smooth = Config.isoSmooth;

    IsoTiles(IsoMap map) {
        this.map = map;
        // Up to 3 on most machines; more where there are cores to spare (8 on 24 cores), so a zoom's new tiles
        // aren't waiting behind each other.
        int cores = Runtime.getRuntime()
            .availableProcessors();
        int threads = Math.max(1, Math.max(Math.min(3, cores / 2), Math.min(8, (cores - 4) / 2)));
        workers = new Thread[threads];
        IsoLog.renderers = threads;
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(this::work, "WayFarMap 3D renderer " + (i + 1));
            thread.setDaemon(true);
            // Below the game's threads, but not at the bottom: busy, the system gave the lowest ones next to no
            // time.
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            thread.start();
            workers[i] = thread;
        }
    }

    // ---------------------------------------------------------------- drawing (render thread)

    /**
     * Draws the 3D map into the screen rectangle.
     *
     * @param centerU,centerV point of the projection plane in the middle of the rectangle
     * @param scale           screen (GUI) pixels per block
     * @param factor          real pixels per GUI pixel
     */
    void draw(IsoMap.Dimension dimension, int rotation, double centerU, double centerV, double scale, int factor, int x,
        int y, int width, int height) {
        frame++;
        if (smooth != Config.isoSmooth) {
            // Tiles drawn with the other smoothing are drawn again.
            smooth = Config.isoSmooth;
            invalidateAll();
        }
        long perf = Perf.start();
        int uploaded = uploadResults();
        Perf.end(Perf.Part.ISO_UPLOAD, perf);
        long tilesPerf = Perf.start();
        quads = 0;
        int level = IsoProjection.levelFor(scale * factor, Config.isoPixelsPerBlock());
        int onScreen = 0, ready = 0, empty = 0, fromCoarser = 0, holes = 0;
        int blocks = IsoProjection.tileBlocks(level);
        double left = centerU - width / 2.0 / scale, top = centerV - height / 2.0 / scale;
        int tu0 = (int) Math.floor(left / blocks), tv0 = (int) Math.floor(top / blocks);
        int tu1 = (int) Math.floor((left + width / scale) / blocks);
        int tv1 = (int) Math.floor((top + height / scale) / blocks);
        long now = System.currentTimeMillis();
        double middleU = centerU / blocks, middleV = centerV / blocks;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // By night the night tiles are laid over the day ones, fading in and out with the time of day (always night
        // where there is no sky, like the Nether).
        float night = MapDrawer.isoNightAmount(Minecraft.getMinecraft(), dimension.id);
        for (int tv = tv0; tv <= tv1; tv++) {
            for (int tu = tu0; tu <= tu1; tu++) {
                Key key = new Key(dimension.id, rotation, level, tu, tv);
                Tile tile = tiles.get(key);
                if (tile == null) {
                    tile = new Tile(key);
                    tiles.put(key, tile);
                }
                tile.wantedAt = now;
                tile.wantedFrame = frame;
                tile.lastDrawn = frame;
                boolean due = !tile.ready || tile.stale() && refreshDue(tile, now);
                // Queued late as only out of date, and now with a hole: queued again ahead (the old job is left out).
                boolean ahead = tile.queued && tile.queuedLate && hasHole(tile) && refreshDue(tile, now);
                if (due && !tile.queued || ahead) {
                    double du = tu + 0.5 - middleU, dv = tv + 0.5 - middleV;
                    tile.queued = true;
                    if (!tile.ready && tile.firstQueued == 0) {
                        tile.firstQueued = System.nanoTime();
                    }
                    // Tiles with nothing to show yet before ones only out of date (those still show their old picture),
                    // the nearest to the middle first.
                    // A tile with a hole as one with nothing to show.
                    tile.queuedLate = tile.ready && !hasHole(tile);
                    double priority = du * du + dv * dv + (tile.queuedLate ? STALE_AFTER_MISSING : 0);
                    long id = order.incrementAndGet();
                    tile.job = id;
                    queue.add(new Job(tile, dimension, priority, id));
                    IsoLog.tileQueued(key, !tile.ready ? "new" : tile.queuedLate ? "stale" : "hole", queue.size());
                }
                double sx = x + width / 2.0 + ((double) tu * blocks - centerU) * scale;
                double sy = y + height / 2.0 + ((double) tv * blocks - centerV) * scale;
                double size = blocks * scale;
                onScreen++;
                if (tile.ready) {
                    ready++;
                    if (!tile.empty) {
                        drawTile(tile, sx, sy, size, 0, 0, 1, night);
                    } else {
                        empty++;
                    }
                } else if (drawFromCoarser(key, sx, sy, size, night) || drawFromFiner(key, sx, sy, size, night)) {
                    fromCoarser++;
                } else {
                    holes++;
                }
            }
        }
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Perf.end(Perf.Part.ISO_TILES, tilesPerf);
        long evictPerf = Perf.start();
        int textured = evict();
        Perf.end(Perf.Part.ISO_EVICT, evictPerf);
        if (IsoLog.on()) {
            IsoLog.frameDrawn(quads, textured, done.size());
            IsoLog.view(
                dimension.id,
                rotation,
                level,
                onScreen,
                ready,
                empty,
                fromCoarser,
                holes,
                queue.size(),
                done.size(),
                uploaded,
                tiles.size());
        }
    }

    /** Until a tile is ready, the matching part of a coarser one that is; false if none is. */
    private boolean drawFromCoarser(Key key, double sx, double sy, double size, float night) {
        for (int up = 1; up <= 4 && key.level + up < IsoProjection.LEVELS; up++) {
            int shift = up;
            Key parent = new Key(
                key.dimension,
                key.rotation,
                key.level + up,
                Math.floorDiv(key.tu, 1 << shift),
                Math.floorDiv(key.tv, 1 << shift));
            Tile tile = tiles.get(parent);
            if (tile == null || !tile.ready) {
                continue;
            }
            tile.lastDrawn = frame;
            if (tile.empty) {
                return true;
            }
            double part = 1.0 / (1 << shift);
            double u0 = Math.floorMod(key.tu, 1 << shift) * part, v0 = Math.floorMod(key.tv, 1 << shift) * part;
            drawTile(tile, sx, sy, size, u0, v0, part, night);
            return true;
        }
        return false;
    }

    /**
     * Until a tile is ready and no coarser one is (zoomed out), the four finer ones in its place that are: zooming
     * out left nothing at all on screen until the coarser tiles were made. False if none of them is ready.
     */
    private boolean drawFromFiner(Key key, double sx, double sy, double size, float night) {
        if (key.level == 0) {
            return false;
        }
        boolean any = false;
        double half = size / 2;
        for (int i = 0; i < 4; i++) {
            Key child = new Key(
                key.dimension,
                key.rotation,
                key.level - 1,
                key.tu * 2 + (i & 1),
                key.tv * 2 + (i >> 1));
            Tile tile = tiles.get(child);
            if (tile == null || !tile.ready) {
                continue;
            }
            tile.lastDrawn = frame;
            any = true;
            if (!tile.empty) {
                drawTile(tile, sx + (i & 1) * half, sy + (i >> 1) * half, half, 0, 0, 1, night);
            }
        }
        return any;
    }

    /** Quads drawn this frame (for the log): one per tile by day, another by night. */
    private static int quads;

    /** Draws (part of) a tile by day, and its night look over it as much as it is night. */
    private static void drawTile(Tile tile, double sx, double sy, double size, double u0, double v0, double part,
        float night) {
        if (night < 1f || tile.nightTexture == -1) {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.texture);
            quad(sx, sy, sx + size, sy + size, u0, v0, u0 + part, v0 + part);
            quads++;
        }
        if (night > 0.01f && tile.nightTexture != -1) {
            GL11.glColor4f(1f, 1f, 1f, night);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.nightTexture);
            quad(sx, sy, sx + size, sy + size, u0, v0, u0 + part, v0 + part);
            quads++;
        }
    }

    private static void quad(double x0, double y0, double x1, double y1, double u0, double v0, double u1, double v1) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x0, y1, 0, u0, v1);
        tessellator.addVertexWithUV(x1, y1, 0, u1, v1);
        tessellator.addVertexWithUV(x1, y0, 0, u1, v0);
        tessellator.addVertexWithUV(x0, y0, 0, u0, v0);
        tessellator.draw();
    }

    /** Puts the tiles the renderers finished on the graphics card, a few per frame; returns how many it took. */
    private int uploadResults() {
        int n = 0;
        while (n < UPLOADS_PER_FRAME) {
            Result result = done.poll();
            if (result == null) {
                return n;
            }
            Tile tile = result.tile;
            tile.queued = false;
            // Skipped tiles cost nothing here: they used to take the uploads of a frame from the tiles on screen.
            if (result.skipped || tiles.get(tile.key) != tile) {
                continue;
            }
            n++;
            if (result.retry) {
                // Drawn again soon; a good picture it had stays until then.
                tile.dirtyAt = Math.max(tile.dirtyAt, result.renderedAt + 1);
                if (tile.ready) {
                    continue;
                }
            }
            tile.renderedAt = result.renderedAt;
            if (tile.holeSince != 0 && result.renderedAt >= tile.holeSince) {
                tile.holeSince = 0;
            }
            tile.hits = result.hits;
            tile.solid = result.solid;
            tile.empty = result.pixels == null;
            tile.ready = true;
            long uploadStart = System.nanoTime();
            if (tile.empty) {
                deleteTexture(tile);
            } else {
                tile.texture = upload(tile.texture, result.pixels);
                tile.nightTexture = upload(tile.nightTexture, result.nightPixels);
            }
            if (IsoLog.on()) {
                long uploadEnd = System.nanoTime();
                // Shown now: how long the oldest chunk change it shows waited, or the tile since it was wanted.
                long changeMs = -1, newNanos = -1;
                if (tile.changedSince != 0 && result.renderedAt >= tile.changedSince) {
                    changeMs = System.currentTimeMillis() - tile.changedSince;
                    tile.changedSince = 0;
                }
                if (tile.firstQueued != 0) {
                    newNanos = uploadEnd - tile.firstQueued;
                    tile.firstQueued = 0;
                }
                IsoLog.tileShown(
                    tile.key,
                    result.composed ? "composed" : result.fromDisk ? "disk" : "trace",
                    tile.empty,
                    changeMs,
                    newNanos,
                    result.finishedAt == 0 ? -1 : uploadStart - result.finishedAt,
                    result.queuedAt == 0 ? -1 : uploadStart - result.queuedAt,
                    uploadEnd - uploadStart);
            }
        }
        return n;
    }

    /** Puts the pixels into the texture (made if -1); returns the texture. */
    private int upload(int texture, int[] pixels) {
        if (texture == -1) {
            texture = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } else {
            GlTextures.bindForUpload(texture);
        }
        if (uploadBuffer == null) {
            uploadBuffer = BufferUtils.createIntBuffer(PIXELS * PIXELS);
        }
        uploadBuffer.clear();
        uploadBuffer.put(pixels);
        uploadBuffer.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            PIXELS,
            PIXELS,
            0,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            uploadBuffer);
        return texture;
    }

    /**
     * Frees the tiles not drawn lately while too many have pictures (or too many are kept in all). Tiles waiting for
     * a renderer stay: dropped, they were queued again as new ones and drawn twice at once. Returns the tiles with
     * pictures (for the log).
     */
    private int evict() {
        int textured = 0;
        for (Tile tile : tiles.values()) {
            if (tile.texture != -1) {
                textured++;
            }
        }
        if (textured <= MAX_TILES && tiles.size() <= MAX_ALL_TILES) {
            return textured;
        }
        List<Tile> old = new ArrayList<>();
        for (Tile tile : tiles.values()) {
            if (tile.lastDrawn < frame && !tile.queued) {
                old.add(tile);
            }
        }
        old.sort((a, b) -> Long.compare(a.lastDrawn, b.lastDrawn));
        for (int i = 0; i < old.size(); i++) {
            boolean texturesOver = textured > MAX_TILES * 3 / 4;
            boolean countOver = tiles.size() > MAX_ALL_TILES * 3 / 4;
            if (!texturesOver && !countOver) {
                break;
            }
            Tile tile = old.get(i);
            if (tile.texture == -1 && !countOver) {
                // Empty or not drawn yet: cheap to keep.
                continue;
            }
            if (tile.texture != -1) {
                textured--;
            }
            deleteTexture(tile);
            tiles.remove(tile.key);
        }
        return textured;
    }

    private static void deleteTexture(Tile tile) {
        if (tile.nightTexture != -1) {
            GlTextures.delete(tile.nightTexture);
            tile.nightTexture = -1;
        }
        if (tile.texture != -1) {
            GlTextures.delete(tile.texture);
            tile.texture = -1;
        }
    }

    /**
     * The block seen at the point (u, v) of the projection plane, from the tiles drawn: {x, y, z}, or null if nothing
     * is known there.
     */
    int[] pick(int dimension, int rotation, double u, double v, double scale, int factor) {
        IsoProjection projection = IsoProjection.of(rotation);
        int finest = IsoProjection.levelFor(scale * factor, Config.isoPixelsPerBlock());
        for (int level = finest; level < IsoProjection.LEVELS; level++) {
            int blocks = IsoProjection.tileBlocks(level);
            int tu = (int) Math.floor(u / blocks), tv = (int) Math.floor(v / blocks);
            Tile tile = tiles.get(new Key(dimension, rotation, level, tu, tv));
            if (tile == null || !tile.ready) {
                continue;
            }
            if (tile.empty || tile.hits == null) {
                return null;
            }
            double pixelsPerBlock = IsoProjection.pixelsPerBlock(level);
            int px = Math.min(PIXELS - 1, (int) ((u - (double) tu * blocks) * pixelsPerBlock));
            int py = Math.min(PIXELS - 1, (int) ((v - (double) tv * blocks) * pixelsPerBlock));
            int hit = tile.hits[py * PIXELS + px] & 0xFFFF;
            int side = (hit >>> 13) - 1;
            if (side < 0) {
                return null;
            }
            double y = (hit & 0x1FFF) / 32.0;
            double[] ground = projection.unproject(u, v, y);
            // Step into the block from the side that was hit.
            double nx = side == 4 ? -1 : side == 5 ? 1 : 0;
            double ny = side == 0 ? -1 : side == 1 ? 1 : 0;
            double nz = side == 2 ? -1 : side == 3 ? 1 : 0;
            return new int[] { (int) Math.floor(ground[0] - nx * 0.02), (int) Math.floor(y - ny * 0.02),
                (int) Math.floor(ground[1] - nz * 0.02), side };
        }
        return null;
    }

    /**
     * Blocks around a changed one whose look may change with it, in blocks: sprites reaching past their cell,
     * connected textures.
     */
    private static final int CHANGE_MARGIN = 2;

    /**
     * A chunk changed: the tiles in memory that show it are drawn again (render thread).
     *
     * @param box   the blocks that changed, in the chunk's coordinates ({@link ChunkBlocks#changedBox}); null for the
     *              whole chunk
     * @param added the chunk had no blocks before: tiles drawn before show a hole there
     */
    int chunkChanged(int dimension, int chunkX, int chunkZ, int top, long time, int[] box, boolean added) {
        int marked = 0;
        double x0 = chunkX * 16.0, z0 = chunkZ * 16.0;
        // Only the rays through the changed blocks: a few blocks of a chunk redrew every tile over its whole column.
        double bx0 = x0, by0 = 0, bz0 = z0, bx1 = x0 + 16, by1 = top + 1, bz1 = z0 + 16;
        if (box != null) {
            bx0 = x0 + box[0] - CHANGE_MARGIN;
            by0 = Math.max(0, box[1] - CHANGE_MARGIN);
            bz0 = z0 + box[2] - CHANGE_MARGIN;
            bx1 = x0 + box[3] + 1 + CHANGE_MARGIN;
            by1 = Math.min(256, box[4] + 1 + CHANGE_MARGIN);
            bz1 = z0 + box[5] + 1 + CHANGE_MARGIN;
        }
        double[][] boxes = new double[4][];
        for (Tile tile : tiles.values()) {
            Key key = tile.key;
            if (key.dimension != dimension) {
                continue;
            }
            double[] area = boxes[key.rotation];
            if (area == null) {
                area = IsoProjection.of(key.rotation)
                    .projectBox(bx0, by0, bz0, bx1, by1, bz1);
                boxes[key.rotation] = area;
            }
            int blocks = IsoProjection.tileBlocks(key.level);
            double u0 = (double) key.tu * blocks, v0 = (double) key.tv * blocks;
            if (area[0] < u0 + blocks && area[2] > u0 && area[1] < v0 + blocks && area[3] > v0) {
                tile.dirtyAt = Math.max(tile.dirtyAt, time);
                if (tile.changedSince == 0) {
                    tile.changedSince = time;
                }
                if (added && tile.holeSince == 0) {
                    tile.holeSince = time;
                }
                marked++;
            }
        }
        return marked;
    }

    /** Tiles in memory (for the log). */
    int size() {
        return tiles.size();
    }

    /** Everything must be drawn again (resource packs changed). */
    void invalidateAll() {
        long now = System.currentTimeMillis();
        for (Tile tile : tiles.values()) {
            tile.dirtyAt = now;
        }
    }

    /** Tiles waiting to be drawn. */
    int queued() {
        return queue.size();
    }

    /** Stops the renderers and frees the textures (render thread). */
    void shutdown() {
        running = false;
        queue.clear();
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Tile tile : tiles.values()) {
            deleteTexture(tile);
        }
        tiles.clear();
        done.clear();
    }

    // ---------------------------------------------------------------- rendering (background threads)

    private void work() {
        while (running) {
            Job job;
            try {
                job = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (job == null) {
                continue;
            }
            Tile tile = job.tile;
            if (job.order != tile.job) {
                // Queued again ahead since (a hole): that job draws it.
                continue;
            }
            // Off screen for a while and for a few frames (at a few frames a second the time alone would be).
            if (System.currentTimeMillis() - tile.wantedAt > UNWANTED_MS && frame - tile.wantedFrame > 2) {
                // Scrolled or zoomed away before its turn.
                done.add(new Result(tile, null, null, null, 0, true));
                IsoLog.tileSkipped(tile.key);
                continue;
            }
            try {
                long start = System.nanoTime();
                IsoLog.tileStart();
                Result result = produce(job);
                result.queuedAt = job.created;
                result.finishedAt = System.nanoTime();
                IsoLog.tileDone(
                    tile.key,
                    start - job.created,
                    System.nanoTime() - start,
                    result.composed ? "composed" : result.fromDisk ? "disk" : "trace",
                    result.retryWhy,
                    result.pixels,
                    result.nightPixels);
                if (running) {
                    done.add(result);
                }
            } catch (Throwable t) {
                WayFarMap.LOG.warn("Could not draw a 3D map tile", t);
                IsoLog.log(
                    "TILE_WARN FAILED rot" + tile.key.rotation
                        + " L"
                        + tile.key.level
                        + " "
                        + tile.key.tu
                        + ","
                        + tile.key.tv
                        + " "
                        + t);
                done.add(new Result(tile, null, null, null, System.currentTimeMillis()));
            }
        }
    }

    private Result produce(Job job) {
        Tile tile = job.tile;
        Key key = tile.key;
        IsoMap.Dimension dimension = job.dimension;
        File file = key.level >= DISK_LEVEL ? tileFile(dimension, key) : null;
        long dirtyAt = tile.dirtyAt;
        IsoLog.TileWork work = IsoLog.work();
        if (file != null && file.isFile()) {
            long readStart = System.nanoTime();
            Result cached = readCached(tile, file, dimension, dirtyAt);
            if (work != null) {
                work.diskNanos = System.nanoTime() - readStart;
                work.diskBytes = file.length();
            }
            if (cached != null) {
                cached.fromDisk = true;
                return cached;
            }
        } else if (work != null) {
            work.disk = file == null ? "not-kept (level " + key.level + ")" : "none";
        }
        if (file != null && key.level > DISK_LEVEL) {
            Result composed = compose(tile, dimension, dirtyAt, file);
            if (composed != null) {
                return composed;
            }
        }
        long start = System.currentTimeMillis();
        FacePalette palette = map.palette();
        if (work != null) {
            work.noPalette = palette == null;
        }
        IsoTracer tracer = new IsoTracer(dimension.store, palette, dimension.noSky);
        BlockLooks.takeMissed();
        IsoProjection projection = IsoProjection.of(key.rotation);
        tracer.reset(projection, key.level);
        int blocks = IsoProjection.tileBlocks(key.level);
        double pixelsPerBlock = IsoProjection.pixelsPerBlock(key.level);
        double u0 = (double) key.tu * blocks, v0 = (double) key.tv * blocks;
        int[] pixels = new int[PIXELS * PIXELS];
        int[] nightPixels = new int[PIXELS * PIXELS];
        short[] hits = new short[PIXELS * PIXELS];
        short[] solid = new short[PIXELS * PIXELS];
        boolean any = false;
        // Zoomed far out several blocks share a pixel: four rays per pixel keep it from looking noisy.
        boolean supersample = pixelsPerBlock < 1 && Config.isoSmooth;
        double q = 0.25 / pixelsPerBlock;
        double[] offsetU = { -q, q, -q }, offsetV = { -q, -q, q };
        int[] day = new int[4], night = new int[4];
        long traceStart = System.nanoTime();
        // Column by column: the rays of one column pass through nearly the same chunks, the rays of a row don't.
        for (int px = 0; px < PIXELS && running; px++) {
            for (int py = 0; py < PIXELS; py++) {
                double u = u0 + (px + 0.5) / pixelsPerBlock, v = v0 + (py + 0.5) / pixelsPerBlock;
                int color = tracer.trace(u, v);
                int nightColor = tracer.nightColor;
                hits[py * PIXELS + px] = hitCode(tracer);
                solid[py * PIXELS + px] = solidCode(tracer.solidY);
                if (supersample) {
                    day[0] = color;
                    night[0] = nightColor;
                    for (int i = 0; i < 3; i++) {
                        day[i + 1] = tracer.trace(u + offsetU[i], v + offsetV[i]);
                        night[i + 1] = tracer.nightColor;
                    }
                    color = average(day);
                    nightColor = average(night);
                }
                pixels[py * PIXELS + px] = color;
                nightPixels[py * PIXELS + px] = nightColor;
                any |= color != 0;
            }
        }
        if (work != null) {
            work.traceNanos = System.nanoTime() - traceStart;
            work.rays = PIXELS * PIXELS * (supersample ? 4 : 1);
        }
        Result result = new Result(tile, any ? pixels : null, any ? nightPixels : null, any ? hits : null, start);
        result.solid = any ? solid : null;
        BlockDiag.tracerFallbacks(tracer.fallbacks);
        boolean looksMissed = BlockLooks.takeMissed();
        result.retry = looksMissed | tracer.incomplete;
        if (result.retry) {
            result.retryWhy = (looksMissed ? "block looks not ready" : "")
                + (looksMissed && tracer.incomplete ? " + " : "")
                + (tracer.incomplete ? "sprites not readable yet" : "");
        }
        if (file != null && running && !result.retry) {
            long saveStart = System.nanoTime();
            boolean ok = writeCached(file, result, tracer.minToward);
            if (work != null) {
                work.saved = !ok ? "FAILED" : result.pixels == null ? "saved-empty" : "saved";
                work.savedNanos = System.nanoTime() - saveStart;
            }
        } else if (work != null && file != null) {
            work.saved = result.retry ? "not (drawn again)" : "not";
        }
        return result;
    }

    /**
     * The tile made from the four finer tiles under it, from their files, if they are all saved and up to date: a
     * pixel is the four pixels under it averaged (with {@link Config#isoSmooth}, else the first of them), which is
     * what tracing it with four rays a pixel gives. Reading four files takes a few milliseconds, tracing the tile 15 to
     * 30: zooming out over a part of the map seen closer, its coarser tiles come at once. Null if a finer tile is
     * missing or out of date (then it is traced).
     */
    private Result compose(Tile tile, IsoMap.Dimension dimension, long dirtyAt, File file) {
        Key key = tile.key;
        IsoLog.TileWork work = IsoLog.work();
        String disk = work == null ? null : work.disk;
        long start = System.nanoTime();
        File[] files = new File[4];
        for (int i = 0; i < 4; i++) {
            Key child = new Key(
                key.dimension,
                key.rotation,
                key.level - 1,
                key.tu * 2 + (i & 1),
                key.tv * 2 + (i >> 1));
            files[i] = tileFile(dimension, child);
            if (!files[i].isFile()) {
                return null;
            }
        }
        Result[] children = new Result[4];
        long renderedAt = Long.MAX_VALUE;
        double minToward = Double.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            // Out of date also against what the coarse tile was told changed since it was drawn.
            Result child = readCached(new Tile(key), files[i], dimension, dirtyAt);
            if (child == null) {
                if (work != null) {
                    work.disk = disk;
                }
                return null;
            }
            children[i] = child;
            renderedAt = Math.min(renderedAt, child.renderedAt);
            minToward = Math.min(minToward, child.minToward);
            any |= child.pixels != null;
        }
        Result result;
        if (!any) {
            result = new Result(tile, null, null, null, renderedAt);
        } else {
            int[] pixels = new int[PIXELS * PIXELS], nightPixels = new int[PIXELS * PIXELS];
            short[] hits = new short[PIXELS * PIXELS], solid = new short[PIXELS * PIXELS];
            int half = PIXELS / 2;
            boolean smooth = Config.isoSmooth;
            int[] day = new int[4], night = new int[4], under = new int[4];
            for (int i = 0; i < 4; i++) {
                Result child = children[i];
                if (child.pixels == null) {
                    // Nothing there: the quarter stays clear.
                    continue;
                }
                int ox = (i & 1) * half, oy = (i >> 1) * half;
                for (int py = 0; py < half; py++) {
                    for (int px = 0; px < half; px++) {
                        int at = (oy + py) * PIXELS + ox + px;
                        int from = py * 2 * PIXELS + px * 2;
                        under[0] = from;
                        under[1] = from + 1;
                        under[2] = from + PIXELS;
                        under[3] = from + PIXELS + 1;
                        if (smooth) {
                            for (int n = 0; n < 4; n++) {
                                day[n] = child.pixels[under[n]];
                                night[n] = child.nightPixels[under[n]];
                            }
                            pixels[at] = average(day);
                            nightPixels[at] = average(night);
                        } else {
                            pixels[at] = child.pixels[from];
                            nightPixels[at] = child.nightPixels[from];
                        }
                        // The block under the mouse and where mobs hide: the first of the four that has one.
                        for (int n : under) {
                            if (child.hits[n] != 0) {
                                hits[at] = child.hits[n];
                                break;
                            }
                        }
                        if (child.solid != null) {
                            for (int n : under) {
                                if (child.solid[n] != 0) {
                                    solid[at] = child.solid[n];
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            result = new Result(tile, pixels, nightPixels, hits, renderedAt);
            result.solid = solid;
        }
        result.fromDisk = true;
        result.composed = true;
        if (running) {
            long saveStart = System.nanoTime();
            boolean ok = writeCached(file, result, minToward);
            if (work != null) {
                work.saved = !ok ? "FAILED" : result.pixels == null ? "saved-empty" : "saved";
                work.savedNanos = System.nanoTime() - saveStart;
            }
        }
        if (work != null) {
            work.disk = "composed (from 4 finer tiles in " + (System.nanoTime() - start) / 100_000 / 10.0 + "ms)";
        }
        return result;
    }

    private static short hitCode(IsoTracer tracer) {
        if (tracer.hitSide < 0) {
            return 0;
        }
        int y = (int) Math.round(Math.max(0, Math.min(255.9, tracer.hitY)) * 32);
        return (short) ((tracer.hitSide + 1) << 13 | Math.min(0x1FFF, y));
    }

    /** A height where a pixel stops being see-through, in 1/32 block, plus 1; 0 for none. */
    private static short solidCode(double y) {
        if (Double.isNaN(y)) {
            return 0;
        }
        return (short) (1 + Math.round(Math.max(0, Math.min(255.9, y)) * 32));
    }

    /**
     * How far toward the viewer (see {@link IsoProjection#toward}) the map stops being see-through at each point of a
     * grid on the projection plane: {@code NaN} where no tile is drawn yet, negative infinity where nothing solid is
     * seen. The points are (u0 + i * step, v0 + j * step), row by row into {@code out}.
     */
    void solidToward(int dimension, int rotation, double scale, int factor, double u0, double v0, double step,
        int columns, int rows, float[] out) {
        int finest = IsoProjection.levelFor(scale * factor, Config.isoPixelsPerBlock());
        Tile last = null;
        Key lastKey = null;
        for (int j = 0; j < rows; j++) {
            double v = v0 + j * step;
            for (int i = 0; i < columns; i++) {
                double u = u0 + i * step;
                float toward = Float.NaN;
                for (int level = finest; level < IsoProjection.LEVELS; level++) {
                    int blocks = IsoProjection.tileBlocks(level);
                    int tu = (int) Math.floor(u / blocks), tv = (int) Math.floor(v / blocks);
                    Tile tile;
                    if (lastKey != null && lastKey.level == level && lastKey.tu == tu && lastKey.tv == tv) {
                        tile = last;
                    } else {
                        lastKey = new Key(dimension, rotation, level, tu, tv);
                        tile = last = tiles.get(lastKey);
                    }
                    if (tile == null || !tile.ready) {
                        continue;
                    }
                    if (tile.empty || tile.solid == null) {
                        toward = Float.NEGATIVE_INFINITY;
                        break;
                    }
                    double pixelsPerBlock = IsoProjection.pixelsPerBlock(level);
                    int px = Math.min(PIXELS - 1, (int) ((u - (double) tu * blocks) * pixelsPerBlock));
                    int py = Math.min(PIXELS - 1, (int) ((v - (double) tv * blocks) * pixelsPerBlock));
                    int code = tile.solid[py * PIXELS + px] & 0xFFFF;
                    toward = code == 0 ? Float.NEGATIVE_INFINITY
                        : (float) ((v + (code - 1) / 32.0 * IsoProjection.COS) / IsoProjection.SIN);
                    break;
                }
                out[j * columns + i] = toward;
            }
        }
    }

    /** Average of colors with straight alpha, weighted by alpha. */
    private static int average(int... colors) {
        long a = 0, r = 0, g = 0, b = 0;
        for (int c : colors) {
            int alpha = c >>> 24;
            a += alpha;
            r += ((c >> 16) & 0xFF) * alpha;
            g += ((c >> 8) & 0xFF) * alpha;
            b += (c & 0xFF) * alpha;
        }
        if (a == 0) {
            return 0;
        }
        return (int) (a / colors.length) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
    }

    private File tileFile(IsoMap.Dimension dimension, Key key) {
        File directory = new File(
            new File(
                new File(new File(dimension.directory, "iso"), map.cacheId() + (Config.isoSmooth ? "" : "-fast")),
                String.valueOf(key.rotation)),
            String.valueOf(key.level));
        return new File(directory, key.tu + "." + key.tv + ".wft");
    }

    /** The tile from disk if nothing it shows changed since it was drawn, else null. */
    private Result readCached(Tile tile, File file, IsoMap.Dimension dimension, long dirtyAt) {
        IsoLog.TileWork work = IsoLog.work();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 15))) {
            if (in.readInt() != MAGIC) {
                if (work != null) {
                    work.disk = "bad-magic";
                }
                return null;
            }
            long renderedAt = in.readLong();
            double minToward = in.readDouble();
            boolean empty = in.readBoolean();
            if (renderedAt < dirtyAt) {
                if (work != null) {
                    work.disk = "stale (changed while open " + (dirtyAt - renderedAt) / 1000 + "s after it was drawn)";
                }
                return null;
            }
            long checkStart = System.nanoTime();
            long newest = newestChange(dimension, tile.key, minToward);
            if (work != null) {
                work.checkNanos = System.nanoTime() - checkStart;
            }
            if (newest > renderedAt) {
                if (work != null) {
                    work.disk = "stale (a chunk changed " + (newest - renderedAt) / 1000 + "s after it was drawn)";
                }
                return null;
            }
            if (work != null) {
                work.disk = (empty ? "ok-empty" : "ok") + " (drawn "
                    + (System.currentTimeMillis() - renderedAt) / 60000
                    + " min ago)";
            }
            if (empty) {
                Result result = new Result(tile, null, null, null, renderedAt);
                result.minToward = minToward;
                return result;
            }
            byte[] raw = new byte[PIXELS * PIXELS * 12];
            DataInputStream data = new DataInputStream(new InflaterInputStream(in, new Inflater(), 1 << 15));
            data.readFully(raw);
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            int[] pixels = new int[PIXELS * PIXELS];
            int[] nightPixels = new int[PIXELS * PIXELS];
            short[] hits = new short[PIXELS * PIXELS];
            short[] solid = new short[PIXELS * PIXELS];
            buffer.asIntBuffer()
                .get(pixels);
            buffer.position(pixels.length * 4);
            buffer.asIntBuffer()
                .get(nightPixels);
            buffer.position(pixels.length * 8);
            buffer.asShortBuffer()
                .get(hits);
            buffer.position(pixels.length * 10);
            buffer.asShortBuffer()
                .get(solid);
            Result result = new Result(tile, pixels, nightPixels, hits, renderedAt);
            result.solid = solid;
            result.minToward = minToward;
            return result;
        } catch (IOException e) {
            if (work != null) {
                work.disk = "io-error " + e;
            }
            return null;
        }
    }

    /** Saves the tile to its file; false if it couldn't. */
    private static boolean writeCached(File file, Result result, double minToward) {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        // Its own temporary file per thread, so two renderers saving the same tile don't write into one.
        File tmp = new File(file.getPath() + "." + System.identityHashCode(Thread.currentThread()) + ".tmp");
        Deflater deflater = new Deflater(4);
        try (
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 15))) {
            out.writeInt(MAGIC);
            out.writeLong(result.renderedAt);
            out.writeDouble(minToward);
            out.writeBoolean(result.pixels == null);
            if (result.pixels != null) {
                ByteBuffer buffer = ByteBuffer.allocate(PIXELS * PIXELS * 12);
                buffer.asIntBuffer()
                    .put(result.pixels);
                buffer.position(result.pixels.length * 4);
                buffer.asIntBuffer()
                    .put(result.nightPixels);
                buffer.position(result.pixels.length * 8);
                buffer.asShortBuffer()
                    .put(result.hits);
                buffer.position(result.pixels.length * 10);
                buffer.asShortBuffer()
                    .put(result.solid);
                DeflaterOutputStream compressed = new DeflaterOutputStream(out, deflater, 1 << 15);
                compressed.write(buffer.array());
                compressed.finish();
            }
        } catch (IOException e) {
            WayFarMap.LOG.debug("Could not save a 3D map tile {}", file);
            return false;
        } finally {
            deflater.end();
        }
        if ((file.exists() && !file.delete()) || !tmp.renameTo(file)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    /**
     * Newest change of any chunk the tile's rays can pass through: the strip of the ground under the tile, from
     * where rays start above the world to the farthest point they reached.
     */
    private static long newestChange(IsoMap.Dimension dimension, Key key, double minToward) {
        IsoProjection projection = IsoProjection.of(key.rotation);
        int blocks = IsoProjection.tileBlocks(key.level);
        double u0 = (double) key.tu * blocks, u1 = u0 + blocks;
        double t0 = minToward - 1;
        double t1 = ((double) key.tv * blocks + blocks + IsoProjection.TOP * IsoProjection.COS) / IsoProjection.SIN;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double u : new double[] { u0, u1 }) {
            for (double t : new double[] { t0, t1 }) {
                double[] p = projection.ground(u, t);
                minX = Math.min(minX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxZ = Math.max(maxZ, p[1]);
            }
        }
        long newest = 0;
        // A chunk's center within this distance of the strip can have a part in it.
        double reach = 12;
        for (int cx = (int) Math.floor(minX) >> 4; cx <= (int) Math.floor(maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(minZ) >> 4; cz <= (int) Math.floor(maxZ) >> 4; cz++) {
                double centerX = cx * 16 + 8, centerZ = cz * 16 + 8;
                double u = projection.u(centerX, centerZ), t = projection.toward(centerX, centerZ);
                if (u < u0 - reach || u > u1 + reach || t < t0 - reach || t > t1 + reach) {
                    continue;
                }
                newest = Math.max(newest, dimension.store.time(cx, cz));
            }
        }
        return newest;
    }
}
