package WayFarMap.client.map;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;

import WayFarMap.Config;
import WayFarMap.WayFarMap;
import WayFarMap.client.TeamMates;
import WayFarMap.client.waypoint.TeamWaypoints;
import WayFarMap.share.ChunkRecord;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Client side of the team map. Every account keeps its own map; in a ServerUtilities team (on a server with this
 * mod) it is also the team's:
 * <ul>
 * <li>on joining the server the client says hello, and the server answers with the player's team (and again
 * whenever the player creates, joins or leaves one);</li>
 * <li>in a team, the client uploads in the background the whole map it already has (all dimensions and caves),
 * only what it hasn't given this team yet, and from then on every chunk it maps;</li>
 * <li>the server sends what the team mapped meanwhile in every dimension, and teammates' new chunks as they come;
 * chunks of other dimensions go straight into those dimensions' maps, to be looked at from the world map.</li>
 * </ul>
 * Every chunk carries the time it was mapped, so when maps merge the newer chunk wins on both sides.
 * <p>
 * The same channel carries the waypoints teammates share: see {@link TeamWaypoints}.
 */
public final class TeamMapClient {

    public static final TeamMapClient INSTANCE = new TeamMapClient();

    /** Received chunks written into the map per tick (each is 256 pixels). */
    private static final int APPLY_PER_TICK = 48;
    /** More than that per tick while there is time left. */
    private static final long APPLY_NANOS = 2_000_000L;
    /** Chunks waiting to be uploaded at most; the oldest are dropped (they get rescanned anyway). */
    private static final int MAX_OUTGOING = 2048;
    private static final int SENT_MEMORY = 8192;
    /** Old map chunks uploaded per tick: 160 a second, a large map takes minutes but costs little bandwidth. */
    private static final int BACKFILL_PER_TICK = 8;
    /**
     * Chunks uploaded per tick in all (new and old ones): 320 a second, under the server's limit of 400, which would
     * drop the rest.
     */
    private static final int UPLOADS_PER_TICK = ShareNetwork.MAX_UPLOAD_RECORDS;

    private final Queue<IMessage> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<ChunkRecord> outgoing = new ArrayDeque<>();
    private final ArrayDeque<Object[]> incoming = new ArrayDeque<>();
    /** What each recently uploaded chunk looked like, so unchanged rescans aren't sent again. */
    private final Map<Long, Integer> sent = new LinkedHashMap<Long, Integer>(1024, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > SENT_MEMORY;
        }
    };
    private boolean helloSent;
    private boolean serverShares;
    /** The player's team as the server last said; empty without one. */
    private String team = "";
    private int dimension = Integer.MIN_VALUE;
    private Backfill backfill;

    private TeamMapClient() {}

    /** True while connected to a server that shares team maps, in a team, with sharing on. */
    public boolean isActive() {
        return serverShares && !team.isEmpty() && Config.shareMapWithTeam;
    }

    /** From the network thread. */
    public void receive(IMessage message) {
        inbox.add(message);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            // Left the server: start over on the next one.
            if (helloSent || serverShares) {
                helloSent = false;
                serverShares = false;
                team = "";
                TeamMates.INSTANCE.clear();
                TeamWaypoints.INSTANCE.reset();
                stopBackfill();
                inbox.clear();
                outgoing.clear();
                incoming.clear();
                sent.clear();
            }
            return;
        }
        if (!helloSent) {
            helloSent = true;
            ShareNetwork.sendToServer(new ShareNetwork.Hello());
        }
        int currentDimension = mc.theWorld.provider.dimensionId;
        if (currentDimension != dimension) {
            dimension = currentDimension;
            outgoing.clear();
            sent.clear();
        }

        IMessage message;
        while ((message = inbox.poll()) != null) {
            if (message instanceof ShareNetwork.Hello) {
                onServerHello((ShareNetwork.Hello) message);
            } else if (message instanceof ShareNetwork.Teammates) {
                TeamMates.INSTANCE.update(((ShareNetwork.Teammates) message).mates);
            } else if (message instanceof ShareNetwork.Waypoints) {
                TeamWaypoints.INSTANCE.receive((ShareNetwork.Waypoints) message);
            } else if (message instanceof ShareNetwork.Chunks && Config.shareMapWithTeam) {
                ShareNetwork.Chunks chunks = (ShareNetwork.Chunks) message;
                for (ChunkRecord record : chunks.records) {
                    incoming.add(new Object[] { chunks.dimension, record });
                }
            }
        }
        TeamWaypoints.INSTANCE.tick();
        if (backfill == null && isActive()) {
            // Sharing was turned back on, or the world folder is ready now.
            startBackfill();
        } else if (backfill != null && !isActive()) {
            stopBackfill();
        }

        // Teammates' chunks: written as their regions are ready, without waiting for disk reads. By time: a burst
        // (many teammates exploring at once) is worked through quickly without costing frames.
        long applyEnd = System.nanoTime() + APPLY_NANOS;
        int size = incoming.size();
        for (int i = 0; i < size && !incoming.isEmpty() && (i < APPLY_PER_TICK || System.nanoTime() < applyEnd); i++) {
            Object[] next = incoming.peek();
            if (!MapManager.INSTANCE.applySharedChunk((Integer) next[0], (ChunkRecord) next[1])) {
                // Its region is still loading; move it to the back and go on with the others.
                incoming.add(incoming.poll());
                continue;
            }
            incoming.poll();
        }

        if (!isActive()) {
            return;
        }
        int budget = UPLOADS_PER_TICK;
        if (!outgoing.isEmpty()) {
            List<ChunkRecord> part = new ArrayList<>();
            while (part.size() < UPLOADS_PER_TICK && !outgoing.isEmpty()) {
                part.add(outgoing.poll());
            }
            ShareNetwork.sendToServer(new ShareNetwork.Chunks(dimension, part));
            budget -= part.size();
        }
        if (backfill != null) {
            backfill.sendSome(Math.min(BACKFILL_PER_TICK, budget));
        }
    }

    private void onServerHello(ShareNetwork.Hello hello) {
        if (!serverShares) {
            WayFarMap.LOG.info("This server shares the map between team members");
        }
        serverShares = true;
        TeamWaypoints.INSTANCE.onTeam(hello.protocol >= ShareNetwork.WAYPOINTS_PROTOCOL, hello.team);
        if (!hello.team.equals(team)) {
            team = hello.team;
            TeamMates.INSTANCE.clear();
            WayFarMap.LOG.info(team.isEmpty() ? "Team map: not in a team" : "Team map: in team " + team);
            stopBackfill();
            sent.clear();
        }
    }

    private void startBackfill() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null) {
            return;
        }
        // What was mapped so far may still be only in memory: saved first, and the upload reads the files after.
        backfill = new Backfill(worldDirectory, team, MapManager.INSTANCE.saveAll());
        Thread thread = new Thread(backfill, "WayFarMap team map upload");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void stopBackfill() {
        if (backfill != null) {
            backfill.cancelled = true;
            backfill = null;
        }
    }

    /** Called by the map right after it scanned a chunk: queues it for the teammates if it changed. */
    void onChunkScanned(MapDimension map, MapDimension biomeMap, int layer, int chunkX, int chunkZ) {
        if (!isActive() || map.dimensionId != dimension) {
            return;
        }
        int rx = chunkX >> (MapRegion.SHIFT - 4), rz = chunkZ >> (MapRegion.SHIFT - 4);
        MapRegion region = map.getLoadedRegion(rx, rz);
        if (region == null) {
            return;
        }
        MapRegion biomeRegion = layer < 0 && biomeMap != null ? biomeMap.getLoadedRegion(rx, rz) : null;
        ChunkRecord record = toRecord(region, biomeRegion, layer, chunkX, chunkZ);
        if (record == null) {
            return;
        }
        record.time = System.currentTimeMillis();
        long key = ((long) chunkX << 36) ^ ((long) (chunkZ & 0xFFFFFFFL) << 4) ^ (layer + 1);
        int hash = record.contentHash();
        Integer previous = sent.get(key);
        if (previous != null && previous == hash) {
            return;
        }
        sent.put(key, hash);
        if (outgoing.size() >= MAX_OUTGOING) {
            outgoing.poll();
        }
        outgoing.add(record);
    }

    /** The chunk of a region as a record (time not set), or null if nothing of it is explored. */
    static ChunkRecord toRecord(MapRegion region, MapRegion biomeRegion, int layer, int chunkX, int chunkZ) {
        ChunkRecord record = new ChunkRecord();
        record.chunkX = chunkX;
        record.chunkZ = chunkZ;
        record.layer = layer;
        if (biomeRegion != null) {
            record.biomes = new byte[ChunkRecord.AREA];
        }
        int baseX = (chunkX * 16) & (MapRegion.SIZE - 1);
        int baseZ = (chunkZ * 16) & (MapRegion.SIZE - 1);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                record.colors[i] = region.getPixel(baseX + lx, baseZ + lz);
                if (layer < 0) {
                    record.extra[i] = (byte) region.getExtra(baseX + lx, baseZ + lz);
                }
                if (biomeRegion != null) {
                    record.biomes[i] = (byte) biomeRegion.getExtra(baseX + lx, baseZ + lz);
                }
            }
        }
        return record.hasPixels() ? record : null;
    }

    /**
     * Uploads the map the account already has to its team: reads the saved regions of every dimension and cave
     * layer on a background thread and hands the chunks newer than what this team already got from us to the
     * game thread, which sends a few per tick. What was sent is remembered per team in {@code team-<id>.dat}.
     */
    private static final class Backfill implements Runnable {

        private final File worldDirectory;
        private final File progressFile;
        private final List<Future<?>> saves;
        private final BlockingQueue<Object[]> ready = new ArrayBlockingQueue<>(256);
        volatile boolean cancelled;

        Backfill(File worldDirectory, String team, List<Future<?>> saves) {
            this.worldDirectory = worldDirectory;
            // "v2": the first version could skip chunks that weren't saved yet, so everything is sent once more.
            this.progressFile = new File(worldDirectory, "team-" + team.replaceAll("[^a-zA-Z0-9_-]", "_") + ".v2.dat");
            this.saves = saves;
        }

        @Override
        public void run() {
            // Only read the map once everything mapped so far is on disk; what is mapped from now on is uploaded
            // as it is mapped, so marking a layer as sent "up to when we started reading it" loses nothing.
            for (Future<?> save : saves) {
                try {
                    save.get();
                } catch (Exception e) {
                    // A failed save is logged by the saver; its chunks go with the next upload.
                }
            }
            WayFarMap.LOG.info("Team map: uploading the existing map from {}", worldDirectory);
            Properties progress = new Properties();
            if (progressFile.isFile()) {
                try (InputStream in = new FileInputStream(progressFile)) {
                    progress.load(in);
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not read " + progressFile, e);
                }
            }
            File[] dimensions = worldDirectory.listFiles(
                file -> file.isDirectory() && file.getName()
                    .matches("dim-?\\d+"));
            if (dimensions == null) {
                return;
            }
            int total = 0;
            for (File dimensionDir : dimensions) {
                int dimensionId = Integer.parseInt(
                    dimensionDir.getName()
                        .substring(3));
                // The surface (with its biomes), then every cave layer.
                total += upload(progress, dimensionId, -1, dimensionDir, new File(dimensionDir, "biomes"));
                for (int layer = 0; layer < 16; layer++) {
                    File caveDir = new File(new File(dimensionDir, "caves"), String.valueOf(layer));
                    if (caveDir.isDirectory()) {
                        total += upload(progress, dimensionId, layer, caveDir, null);
                    }
                }
                if (cancelled) {
                    return;
                }
            }
            WayFarMap.LOG.info("Team map: existing map uploaded ({} chunks)", total);
        }

        /** Uploads one layer of one dimension; returns the number of chunks queued. */
        private int upload(Properties progress, int dimensionId, int layer, File dir, File biomeDir) {
            String key = "dim" + dimensionId + (layer < 0 ? ".surface" : ".cave" + layer);
            long since = parseLong(progress.getProperty(key));
            long started = System.currentTimeMillis();
            File[] files = dir.listFiles(
                file -> file.getName()
                    .startsWith("r.")
                    && file.getName()
                        .endsWith(".png"));
            if (files == null) {
                return 0;
            }
            int count = 0;
            for (File file : files) {
                if (cancelled) {
                    return count;
                }
                // Nothing in a file older than what this team already got can be newer.
                if (file.lastModified() <= since) {
                    continue;
                }
                String[] parts = file.getName()
                    .split("\\.");
                int rx, rz;
                try {
                    rx = Integer.parseInt(parts[1]);
                    rz = Integer.parseInt(parts[2]);
                } catch (Exception e) {
                    continue;
                }
                MapRegion region, biomeRegion = null;
                try {
                    region = MapRegion.read(file, rx, rz);
                    File biomeFile = biomeDir != null ? MapRegion.getFile(biomeDir, rx, rz) : null;
                    if (biomeFile != null && biomeFile.isFile()) {
                        biomeRegion = MapRegion.read(biomeFile, rx, rz);
                    }
                } catch (IOException e) {
                    continue;
                }
                for (int cz = 0; cz < MapRegion.CHUNKS; cz++) {
                    for (int cx = 0; cx < MapRegion.CHUNKS; cx++) {
                        long time = region.getChunkTime(cx, cz);
                        // Chunks we got from a team are not ours to upload again.
                        if (time <= since || region.isFromTeammate(cx, cz)) {
                            continue;
                        }
                        ChunkRecord record = toRecord(
                            region,
                            biomeRegion,
                            layer,
                            rx * MapRegion.CHUNKS + cx,
                            rz * MapRegion.CHUNKS + cz);
                        if (record == null) {
                            continue;
                        }
                        record.time = time;
                        try {
                            while (!ready.offer(new Object[] { dimensionId, record }, 1, TimeUnit.SECONDS)) {
                                if (cancelled) {
                                    return count;
                                }
                            }
                        } catch (InterruptedException e) {
                            return count;
                        }
                        count++;
                    }
                }
            }
            // Wait until the game thread sent it all before remembering this layer as done.
            while (!ready.isEmpty()) {
                if (cancelled) {
                    return count;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return count;
                }
            }
            synchronized (this) {
                progress.setProperty(key, String.valueOf(started));
                try (OutputStream out = new FileOutputStream(progressFile)) {
                    progress.store(out, "WayFarMap: map already uploaded to this team, per dimension and layer");
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not save " + progressFile, e);
                }
            }
            return count;
        }

        private static long parseLong(String value) {
            try {
                return value == null ? 0 : Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** Game thread: sends a few of the prepared chunks, one dimension per message. */
        void sendSome(int max) {
            List<ChunkRecord> part = new ArrayList<>();
            int dimensionId = 0;
            while (part.size() < max) {
                Object[] next = ready.peek();
                if (next == null || !part.isEmpty() && (Integer) next[0] != dimensionId) {
                    break;
                }
                ready.poll();
                dimensionId = (Integer) next[0];
                part.add((ChunkRecord) next[1]);
            }
            if (!part.isEmpty()) {
                ShareNetwork.sendToServer(new ShareNetwork.Chunks(dimensionId, part, true));
            }
        }
    }
}
