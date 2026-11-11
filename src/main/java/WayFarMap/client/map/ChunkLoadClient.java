package WayFarMap.client.map;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.MathHelper;
import net.minecraft.world.chunk.Chunk;

import WayFarMap.Config;
import WayFarMap.Perf;
import WayFarMap.WayFarMap;
import WayFarMap.client.Lang;
import WayFarMap.client.Teleport;
import WayFarMap.client.map.iso.IsoLog;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * The client's part of {@code /wf chunkload}: the server sends a batch of chunks as the game sends chunks, then says
 * which they are. Once they are all here, the inner ones are mapped (flat map, and the 3D map for {@code 3d}), a few
 * milliseconds per tick; then the chunks the game doesn't need are let go and the server is asked for the next batch.
 * The world map shows how far it got.
 */
public final class ChunkLoadClient {

    public static final ChunkLoadClient INSTANCE = new ChunkLoadClient();

    /** How long to wait for a batch's chunks before mapping those that came. */
    private static final long WAIT_MS = 30_000;
    /**
     * For the 3D map, after the batch's chunks came: what the mods send for their blocks once the player watches the
     * chunk (ForgeMultipart's parts at the end of the server's tick, GregTech's covers at its next update) comes
     * after the chunk; copied before, those blocks were empty or bare.
     */
    private static final long SETTLE_MS = 1000;
    /** When the batch's chunks had all come, 0 until then. */
    private long arrivedAt;

    private final Queue<IMessage> inbox = new ConcurrentLinkedQueue<>();
    /** Whether the last tick was in a world (leaving it resets what the server allowed). */
    private boolean wasInWorld;
    private ShareNetwork.LoadBatch batch;
    private long batchSince;
    /** Next inner chunk of the batch to map (row by row), and whether its flat map is done. */
    private int at;
    private boolean scanned;
    /** Cave layers of the chunk mapped so far (picked with its caves). */
    private int caveAt;
    private boolean lettingGo;
    // Progress, for the world map.
    private long done, total;
    private boolean with3d;
    private long startedAt;
    /** Chunks done when {@link #startedAt} was set: the speed (and the time left) is worked out from there. */
    private long doneAtStart;
    /** When the last chunk of the area was mapped, 0 while it isn't done. */
    private long finishedAt;
    private long lastBatchAt;
    /** For the log: the batch's chunks mapped and those that never came, the time mapping them, when it began. */
    private int mappedCount, skippedCount;
    private long workNanos, firstWorkAt;
    private StringBuilder skippedList = new StringBuilder();

    private ChunkLoadClient() {}

    /** From the network thread. */
    public void receive(IMessage message) {
        inbox.add(message);
    }

    /** While chunks of a batch are let go (the map's usual handling of chunks let go is skipped). */
    public boolean isLettingGo() {
        return lettingGo;
    }

    /**
     * What the world map shows while an area is being loaded, null when none is (or it ended over a minute ago).
     */
    public String statusText() {
        if (!isShown()) {
            return null;
        }
        long percent = done * 100 / total;
        String elapsed = Lang
            .format("wayfarmap.chunkload.elapsed", String.format(Locale.US, "%,d", elapsedMs() / 1000));
        return Lang.format("wayfarmap.chunkload.progress", with3d ? "3D" : "2D", done, total, percent, elapsed);
    }

    /** Whether there is a loading to show: going on, or ended less than a minute ago. */
    public boolean isShown() {
        return total > 0 && (batch != null || System.currentTimeMillis() - lastBatchAt <= 60_000);
    }

    /** Whether an area is being loaded now (not done, not stopped). */
    public boolean isRunning() {
        return isShown() && finishedAt == 0;
    }

    /** Whether the area was loaded to the end (shown for a minute after). */
    public boolean isFinished() {
        return isShown() && finishedAt != 0;
    }

    public boolean isWith3d() {
        return with3d;
    }

    public long done() {
        return done;
    }

    public long total() {
        return total;
    }

    /** How far it got, 0 to 1. */
    public double fraction() {
        return total <= 0 ? 0 : Math.min(1, (double) done / total);
    }

    /** Milliseconds since the area was started (or taken up again), stopped once it is done. */
    public long elapsedMs() {
        long end = finishedAt != 0 ? finishedAt : System.currentTimeMillis();
        return Math.max(0, end - startedAt);
    }

    /** Milliseconds left at the speed so far, or -1 while it is too early to tell. */
    public long remainingMs() {
        long elapsed = elapsedMs(), since = done - doneAtStart;
        if (finishedAt != 0) {
            return 0;
        }
        if (elapsed < 3000 || since <= 0) {
            return -1;
        }
        return (long) ((total - done) * (double) elapsed / since);
    }

    /** Chunks a second at the speed so far, 0 while it is too early to tell. */
    public double chunksPerSecond() {
        long elapsed = elapsedMs(), since = done - doneAtStart;
        return elapsed < 1000 || since <= 0 ? 0 : since * 1000.0 / elapsed;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world == null || mc.thePlayer == null) {
            if (wasInWorld) {
                // Out of the world: the next server says again whether loading from the map is allowed.
                wasInWorld = false;
                ChunkLoadView.setAllowed(false);
                Teleport.setServerAllowed(false);
                ChunkLoadMissing.setServerAnswers(false);
            }
            // That word may come while joining, before the world is there: it is kept.
            inbox.removeIf(m -> !(m instanceof ShareNetwork.LoadAllowed) && !(m instanceof ShareNetwork.LoadEnded));
            batch = null;
            total = 0;
            ChunkLoadMissing.reset();
            return;
        }
        wasInWorld = true;
        IMessage message;
        while ((message = inbox.poll()) != null) {
            if (message instanceof ShareNetwork.LoadBatch) {
                start((ShareNetwork.LoadBatch) message);
            } else if (message instanceof ShareNetwork.SavedChunks) {
                ChunkLoadView.saved((ShareNetwork.SavedChunks) message);
            } else if (message instanceof ShareNetwork.SavedScan) {
                ChunkLoadMissing.scanned((ShareNetwork.SavedScan) message);
            } else if (message instanceof ShareNetwork.LoadAllowed) {
                ChunkLoadView.setAllowed(((ShareNetwork.LoadAllowed) message).allowed);
                Teleport.setServerAllowed(((ShareNetwork.LoadAllowed) message).teleport);
                ChunkLoadMissing.setServerAnswers(((ShareNetwork.LoadAllowed) message).scan);
            } else if (message instanceof ShareNetwork.LoadEnded) {
                ended(world, (ShareNetwork.LoadEnded) message, mc);
            }
        }
        ChunkLoadMissing.tick(mc);
        ShareNetwork.LoadBatch b = batch;
        if (b == null) {
            return;
        }
        if (world.provider.dimensionId != b.dimension) {
            // The server pauses it; this batch is sent again when the player is back.
            batch = null;
            return;
        }
        if (!arrived(world, b) && System.currentTimeMillis() - batchSince < WAIT_MS) {
            return;
        }
        if (arrivedAt == 0) {
            arrivedAt = System.currentTimeMillis();
        }
        if (b.with3d && System.currentTimeMillis() - arrivedAt < SETTLE_MS) {
            return;
        }
        if (firstWorkAt == 0) {
            firstWorkAt = System.currentTimeMillis();
        }
        long start = System.nanoTime();
        try {
            mapBatch(world, b, mc, start + Math.max(1, Config.chunkloadClientMs) * 1_000_000L);
        } finally {
            long nanos = System.nanoTime() - start;
            workNanos += nanos;
            Perf.add(Perf.Part.CHUNKLOAD_CLIENT, nanos);
        }
    }

    private void mapBatch(WorldClient world, ShareNetwork.LoadBatch b, Minecraft mc, long end) {
        int width = b.innerX1 - b.innerX0 + 1, count = width * (b.innerZ1 - b.innerZ0 + 1);
        while (at < count) {
            int cx = b.innerX0 + at % width, cz = b.innerZ0 + at / width;
            if (b.picked != null && (b.picked[at >> 6] & 1L << (at & 63)) == 0) {
                // Only loaded for a picked chunk next to it: not put on the map.
                at++;
                continue;
            }
            // The client's chunk provider says every chunk exists: one not received is empty.
            Chunk chunk = ChunkScanner.isChunkReady(world, cx, cz) ? world.getChunkFromChunkCoords(cx, cz) : null;
            if (chunk == null) {
                // Never came (waited for above): a hole in the map. No longer waited for on the map either, or it
                // would stay queued (red) for good.
                ChunkLoadView.mapped(b.dimension, cx, cz);
                skippedCount++;
                if (skippedList.length() < 600) {
                    skippedList.append(' ')
                        .append(cx)
                        .append(',')
                        .append(cz);
                }
            }
            if (chunk != null && !scanned) {
                if (System.nanoTime() >= end) {
                    return;
                }
                if (!MapManager.INSTANCE.scanForLoad(chunk, b.with3d)) {
                    // Its regions are being read: next tick.
                    return;
                }
                mappedCount++;
                scanned = true;
            }
            if (chunk != null && ChunkLoadView.withCaves(cx, cz)) {
                // Picked with Shift: every cave layer too, as many per tick as the time allows.
                int layers = MapManager.caveLayersOf(chunk);
                while (caveAt < layers) {
                    if (System.nanoTime() >= end) {
                        return;
                    }
                    if (!MapManager.INSTANCE.scanCaveForLoad(chunk, caveAt)) {
                        // The layer's region is being read: next tick.
                        return;
                    }
                    caveAt++;
                }
            }
            if (chunk != null && b.with3d) {
                // Asked for the 3D map (the view's 3D switch): recorded whether or not blocks are while playing.
                // The 3D map's own time per tick; a chunk with many pictures takes several ticks.
                long deadline = System.nanoTime() + Math.max(2, Config.isoCaptureMs) * 1_000_000L;
                if (!IsoMap.INSTANCE.captureForLoad(world, chunk, deadline)) {
                    return;
                }
            }
            if (chunk != null) {
                // On the flat map, and on the 3D map too if it was loaded for it: no longer waiting.
                ChunkLoadView.mapped(b.dimension, cx, cz);
            }
            at++;
            scanned = false;
            caveAt = 0;
            done = b.doneBefore + (b.picked == null ? at : pickedBefore(b, at));
            if (b.with3d && System.nanoTime() >= end) {
                return;
            }
        }
        finish(world, b, mc);
    }

    /** Picked chunks among the batch's first {@code n} inner chunks (the progress of a picked chunks job). */
    private static int pickedBefore(ShareNetwork.LoadBatch b, int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            if ((b.picked[i >> 6] & 1L << (i & 63)) != 0) {
                count++;
            }
        }
        return count;
    }

    private void start(ShareNetwork.LoadBatch b) {
        if (total <= 0 || b.doneBefore < done - 64 || b.total != total || b.with3d != with3d) {
            // A new area (or one taken up again): the time left is worked out from here.
            startedAt = System.currentTimeMillis();
            doneAtStart = b.doneBefore;
            finishedAt = 0;
        }
        batch = b;
        arrivedAt = 0;
        at = 0;
        firstWorkAt = 0;
        scanned = false;
        caveAt = 0;
        mappedCount = 0;
        skippedCount = 0;
        workNanos = 0;
        skippedList = new StringBuilder();
        done = b.doneBefore;
        total = b.total;
        with3d = b.with3d;
        String text = "CHUNKLOAD batch " + b.index
            + " inner "
            + b.innerX0
            + ","
            + b.innerZ0
            + ".."
            + b.innerX1
            + ","
            + b.innerZ1
            + " done "
            + b.doneBefore
            + "/"
            + b.total
            + (b.with3d ? " 3D" : " 2D")
            + " server[sent="
            + b.sent
            + " loadedAgain="
            + b.reloaded
            + " couldNotLoad="
            + b.missing
            + " batchMs="
            + b.serverMs
            + " workMs="
            + b.workMs
            + " watched="
            + b.watched
            + " alreadyWatched="
            + b.alreadyWatched
            + "] sinceLastBatchMs="
            + (lastBatchAt == 0 ? -1 : System.currentTimeMillis() - lastBatchAt);
        batchSince = System.currentTimeMillis();
        lastBatchAt = batchSince;
        IsoLog.log(text);
        FlatLog.log(text);
    }

    /**
     * Whether every chunk sent in the batch is here (the client's chunk provider says every chunk exists; one not
     * received yet is empty). Chunks the server could not give are not waited for past {@link #WAIT_MS}.
     */
    private static boolean arrived(WorldClient world, ShareNetwork.LoadBatch b) {
        int ready = 0;
        for (int z = b.outerZ0; z <= b.outerZ1; z++) {
            for (int x = b.outerX0; x <= b.outerX1; x++) {
                if (ChunkScanner.isChunkReady(world, x, z)) {
                    ready++;
                }
            }
        }
        return ready >= b.sent;
    }

    /**
     * The server says the loading ended. Stopped (or replaced): the batch being mapped is dropped (its chunks let
     * go, the server doesn't wait for it anymore) and the progress goes away. Finished: shown done for a while. Either
     * way the chunks the map shows queued for it are no longer waited for.
     */
    private void ended(WorldClient world, ShareNetwork.LoadEnded message, Minecraft mc) {
        ChunkLoadView.ended(message.seq);
        ShareNetwork.LoadBatch b = batch;
        if (b != null && !message.finished) {
            letGo(world, b, mc);
            batch = null;
            IsoLog.log("CHUNKLOAD_STOPPED batch " + b.index + " dropped");
            FlatLog.log("CHUNKLOAD_STOPPED batch " + b.index + " dropped");
        }
        if (message.finished) {
            if (total > 0 && finishedAt == 0) {
                done = total;
                finishedAt = System.currentTimeMillis();
            }
        } else if (batch == null) {
            total = 0;
            done = 0;
        }
    }

    /** Lets go of the batch's chunks the game doesn't need. */
    private void letGo(WorldClient world, ShareNetwork.LoadBatch b, Minecraft mc) {
        int pcx = MathHelper.floor_double(mc.thePlayer.posX) >> 4;
        int pcz = MathHelper.floor_double(mc.thePlayer.posZ) >> 4;
        int keep = b.viewDistance + 1;
        lettingGo = true;
        try {
            for (int z = b.outerZ0; z <= b.outerZ1; z++) {
                for (int x = b.outerX0; x <= b.outerX1; x++) {
                    long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                    IsoMap.INSTANCE.forgetLoaded(key);
                    if (Math.abs(x - pcx) <= keep && Math.abs(z - pcz) <= keep) {
                        // The game's own chunk around the player.
                        continue;
                    }
                    if (world.getChunkProvider()
                        .chunkExists(x, z)) {
                        world.doPreChunk(x, z, false);
                    }
                }
            }
        } catch (RuntimeException e) {
            WayFarMap.LOG.warn("Could not let go of chunks loaded for /wf chunkload", e);
        } finally {
            lettingGo = false;
        }
    }

    /** Lets go of the batch's chunks the game doesn't need, and asks for the next batch. */
    private void finish(WorldClient world, ShareNetwork.LoadBatch b, Minecraft mc) {
        letGo(world, b, mc);
        long innerCount = (long) (b.innerX1 - b.innerX0 + 1) * (b.innerZ1 - b.innerZ0 + 1);
        // Picked chunks: only those count (the others were loaded for them, not mapped).
        done = b.doneBefore + (b.picked == null ? innerCount : pickedBefore(b, (int) innerCount));
        if (done >= total && finishedAt == 0) {
            finishedAt = System.currentTimeMillis();
        }
        String text = "CHUNKLOAD_DONE batch " + b.index
            + " mapped="
            + mappedCount
            + " skipped(never came)="
            + skippedCount
            + (skippedCount > 0 ? " [" + skippedList.toString()
                .trim() + "]" : "")
            + " waitedForChunksMs="
            + Math.max(0, firstWorkAt - batchSince)
            + " clientMs="
            + String.format(java.util.Locale.ROOT, "%.1f", workNanos / 1e6)
            + " batchOnClientMs="
            + (System.currentTimeMillis() - batchSince);
        IsoLog.log(text);
        FlatLog.log(text);
        batch = null;
        ShareNetwork.sendToServer(new ShareNetwork.LoadDone(b.job, b.index));
    }
}
