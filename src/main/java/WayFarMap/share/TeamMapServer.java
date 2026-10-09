package WayFarMap.share;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.Perf;
import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * Server side of the team map: every ServerUtilities team gets a shared map. Clients with the mod upload the chunks
 * they map, and on joining a team (or the server, while in one) the whole map they already had; the server keeps
 * the most recently mapped version of each chunk per team, passes new chunks on to all online teammates (whatever
 * dimension they are in), and when a player joins sends everything the team got since that player last synced, in
 * every dimension: the player's own first, nearest regions first. So the maps of a team merge, newer chunks winning,
 * and every dimension the team explored can be looked at right away.
 * <p>
 * Stored in {@code <world>/wayfarmap/teams/<team>/dim<id>/<surface|caveN>/r.X.Z.bin}, one file per 32x32 chunks.
 * <p>
 * The waypoints teammates share with each other go through here too, to {@link TeamWaypointServer}.
 */
public final class TeamMapServer {

    public static final TeamMapServer INSTANCE = new TeamMapServer();

    private static final int REGION_SHIFT = 5;
    private static final int FILE_MAGIC = 0x57464D54;
    private static final int FILE_VERSION = 2;
    /** Upload sanity limits: chunks must be near the uploader, and not too many per second. */
    private static final int MAX_UPLOAD_DISTANCE = 96;
    private static final int MAX_UPLOADS_PER_SECOND = 400;
    /** Messages of teammates' chunks sent per player per tick while catching up. */
    private static final int SYNC_MESSAGES_PER_TICK = 1;
    private static final long SAVE_INTERVAL_MS = 30_000, IDLE_UNLOAD_MS = 120_000;

    private static Boolean active;

    /**
     * Region files are written here, not on the server thread: a save of many changed regions (a teammate uploading
     * a big map) would otherwise lag the server. One thread, so writes of the same file never overlap.
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap team map saver");
        thread.setDaemon(true);
        return thread;
    });

    /** Team maps are shared only where ServerUtilities (teams) is installed. */
    public static boolean isActive() {
        if (active == null) {
            active = Loader.isModLoaded("serverutilities");
        }
        return active;
    }

    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    /** Players whose client has the mod (said hello). */
    private final Set<UUID> capable = new HashSet<>();
    /** Those of them whose client knows team waypoints. */
    private final Set<UUID> waypointCapable = new HashSet<>();
    private final TeamWaypointServer waypoints = new TeamWaypointServer(this);
    private final Map<String, TeamStore> teams = new HashMap<>();
    private final Map<UUID, Sync> syncs = new HashMap<>();
    private final Map<UUID, int[]> uploadBudget = new HashMap<>();
    /** When the upload budgets were last reset: every real second, whatever the server's tick rate. */
    private long budgetReset;
    /** The team each capable player was last told about, to notice joining, creating or leaving one. */
    private final Map<UUID, String> knownTeams = new HashMap<>();
    private File root;
    private long lastSave;
    private int tick;

    private TeamMapServer() {}

    /** From the network thread: queued for the server thread. */
    static void receive(EntityPlayerMP player, IMessage message) {
        if (isActive() && player != null) {
            INSTANCE.inbox.add(new Object[] { player, message });
        }
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        long perf = Perf.start();
        try {
            serverTick(event);
        } finally {
            Perf.end(Perf.Part.TEAM_MAP, perf);
        }
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        long now = System.currentTimeMillis();
        if (now - budgetReset >= 1000) {
            // By the clock: on a lagging server 20 ticks take longer, and clients would lose chunks to the limit.
            budgetReset = now;
            uploadBudget.clear();
        }
        if (tick % 100 == 0) {
            checkTeams();
        }
        if (tick % 10 == 0) {
            sendTeammates();
        }
        Object[] entry;
        while ((entry = inbox.poll()) != null) {
            EntityPlayerMP player = (EntityPlayerMP) entry[0];
            try {
                if (entry[1] instanceof ShareNetwork.Hello) {
                    onHello(player, (ShareNetwork.Hello) entry[1]);
                } else if (entry[1] instanceof ShareNetwork.Waypoints) {
                    String team = knownTeams.get(player.getUniqueID());
                    if (team != null && !team.isEmpty() && waypointCapable.contains(player.getUniqueID())) {
                        waypoints.receive(player, team, (ShareNetwork.Waypoints) entry[1]);
                    }
                } else if (entry[1] instanceof ShareNetwork.Chunks) {
                    onUpload(player, (ShareNetwork.Chunks) entry[1]);
                }
            } catch (Exception e) {
                WayFarMap.LOG.warn("Team map: could not handle a message from " + player.getCommandSenderName(), e);
            }
        }
        for (Sync sync : new ArrayList<>(syncs.values())) {
            try {
                sync.step();
            } catch (Exception e) {
                WayFarMap.LOG.warn("Team map: sync failed for " + sync.player.getCommandSenderName(), e);
                syncs.remove(sync.player.getUniqueID());
            }
        }
        if (now - lastSave >= SAVE_INTERVAL_MS) {
            lastSave = now;
            for (TeamStore store : teams.values()) {
                store.save(false);
                store.unloadIdle(now);
            }
            waypoints.save();
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        UUID id = event.player.getUniqueID();
        finishSync(id);
        capable.remove(id);
        waypointCapable.remove(id);
        uploadBudget.remove(id);
        knownTeams.remove(id);
        toldAlone.remove(id);
    }

    /** Saves everything and forgets the world (server stopping). */
    public void stop() {
        for (Sync sync : new ArrayList<>(syncs.values())) {
            finishSync(sync.player.getUniqueID());
        }
        for (TeamStore store : teams.values()) {
            store.save(true);
        }
        // Wait for the writes, so the files are complete even if the server exits right after.
        Future<?> done = WRITER.submit(() -> {});
        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            WayFarMap.LOG.warn("Team map: saving took too long", e);
        }
        waypoints.stop();
        teams.clear();
        syncs.clear();
        capable.clear();
        waypointCapable.clear();
        knownTeams.clear();
        inbox.clear();
        root = null;
    }

    // ---------------------------------------------------------------- messages

    private void onHello(EntityPlayerMP player, ShareNetwork.Hello hello) {
        capable.add(player.getUniqueID());
        if (hello.protocol >= ShareNetwork.WAYPOINTS_PROTOCOL) {
            waypointCapable.add(player.getUniqueID());
        }
        String team = SuTeams.teamId(player);
        knownTeams.put(player.getUniqueID(), team == null ? "" : team);
        // The client learns its team: with one it starts uploading the map it already has.
        ShareNetwork.sendTo(new ShareNetwork.Hello(team), player);
        startSync(player);
        sendWaypoints(player, team);
    }

    private void sendWaypoints(EntityPlayerMP player, String team) {
        if (team != null && waypointCapable.contains(player.getUniqueID())) {
            waypoints.sendAll(player, team);
        }
    }

    /** To the online members of a team whose client knows team waypoints, but for one of them. */
    void sendToTeam(String teamId, UUID except, IMessage message) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            UUID id = player.getUniqueID();
            if (!id.equals(except) && waypointCapable.contains(id) && teamId.equals(knownTeams.get(id))) {
                ShareNetwork.sendTo(message, player);
            }
        }
    }

    File teamDirectory(String teamId) {
        if (root == null) {
            root = new File(DimensionManager.getCurrentSaveRootDirectory(), "wayfarmap/teams");
        }
        return new File(root, teamId);
    }

    /** Players last told they have no teammates online, so the empty list isn't sent again and again. */
    private final Set<UUID> toldAlone = new HashSet<>();

    /** Tells every player with the mod where their online teammates are, in any dimension. */
    private void sendTeammates() {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            UUID id = player.getUniqueID();
            if (!capable.contains(id)) {
                continue;
            }
            ShareNetwork.Teammates message = new ShareNetwork.Teammates();
            for (EntityPlayerMP mate : SuTeams.onlineTeammates(player)) {
                if (mate == player) {
                    continue;
                }
                ShareNetwork.Teammates.Mate entry = new ShareNetwork.Teammates.Mate();
                entry.id = mate.getUniqueID();
                entry.name = mate.getCommandSenderName();
                entry.dimension = mate.dimension;
                entry.x = mate.posX;
                entry.y = mate.posY;
                entry.z = mate.posZ;
                entry.yaw = mate.rotationYaw;
                message.mates.add(entry);
            }
            if (message.mates.isEmpty()) {
                if (!toldAlone.add(id)) {
                    continue;
                }
            } else {
                toldAlone.remove(id);
            }
            ShareNetwork.sendTo(message, player);
        }
    }

    /** A player who created, joined or left a team gets the new team's map, and uploads theirs to it. */
    private void checkTeams() {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            UUID id = player.getUniqueID();
            if (!capable.contains(id)) {
                continue;
            }
            String team = SuTeams.teamId(player);
            String current = team == null ? "" : team;
            String old = knownTeams.get(id);
            if (!current.equals(old)) {
                knownTeams.put(id, current);
                finishSync(id);
                if (old != null && !old.isEmpty()) {
                    // What the player shared stays with the player, not with the team left.
                    waypoints.removeOwner(old, id);
                }
                ShareNetwork.sendTo(new ShareNetwork.Hello(team), player);
                startSync(player);
                sendWaypoints(player, team);
            }
        }
    }

    private void onUpload(EntityPlayerMP player, ShareNetwork.Chunks message) {
        UUID id = player.getUniqueID();
        String teamId = SuTeams.teamId(player);
        // Chunks just mapped must be in the player's dimension; the map a player already had can be anywhere.
        if (teamId == null || !capable.contains(id) || !message.backfill && message.dimension != player.dimension) {
            return;
        }
        int[] budget = uploadBudget.computeIfAbsent(id, k -> new int[1]);
        int playerChunkX = (int) Math.floor(player.posX) >> 4, playerChunkZ = (int) Math.floor(player.posZ) >> 4;
        long now = System.currentTimeMillis();
        TeamStore store = store(teamId);
        List<ChunkRecord> accepted = new ArrayList<>();
        for (ChunkRecord record : message.records) {
            if (budget[0]++ >= MAX_UPLOADS_PER_SECOND) {
                break;
            }
            if (!message.backfill && (Math.abs(record.chunkX - playerChunkX) > MAX_UPLOAD_DISTANCE
                || Math.abs(record.chunkZ - playerChunkZ) > MAX_UPLOAD_DISTANCE)) {
                continue;
            }
            // The client's time of mapping it (clocks may differ a little; never in the future).
            record.time = record.time <= 0 ? now : Math.min(record.time, now);
            if (store.put(message.dimension, record, id)) {
                accepted.add(record);
            }
        }
        if (accepted.isEmpty()) {
            return;
        }
        // Teammates get it right away, wherever they are: their client files it under its dimension. The messages
        // are made once for all of them (each is compressed only once).
        List<ShareNetwork.Chunks> parts = null;
        for (EntityPlayerMP mate : SuTeams.onlineTeammates(player)) {
            if (mate != player && capable.contains(mate.getUniqueID())) {
                if (parts == null) {
                    parts = messages(message.dimension, accepted);
                }
                for (ShareNetwork.Chunks part : parts) {
                    ShareNetwork.sendTo(part, mate);
                }
            }
        }
    }

    private static List<ShareNetwork.Chunks> messages(int dimension, List<ChunkRecord> records) {
        List<ShareNetwork.Chunks> parts = new ArrayList<>();
        for (int i = 0; i < records.size(); i += ShareNetwork.MAX_RECORDS) {
            parts.add(
                new ShareNetwork.Chunks(
                    dimension,
                    records.subList(i, Math.min(records.size(), i + ShareNetwork.MAX_RECORDS))));
        }
        return parts;
    }

    // ---------------------------------------------------------------- catching up

    private void startSync(EntityPlayerMP player) {
        String teamId = SuTeams.teamId(player);
        if (teamId == null) {
            return;
        }
        syncs.put(player.getUniqueID(), new Sync(player, store(teamId)));
    }

    /**
     * Remembers how far the player got, so next time only newer chunks are sent. Dimensions caught up on were kept
     * current by live updates until now; the others keep what they had.
     */
    private void finishSync(UUID id) {
        Sync sync = syncs.remove(id);
        if (sync != null) {
            long now = System.currentTimeMillis();
            for (int dimension : sync.completed) {
                sync.store.setLastSync(id, dimension, now);
            }
        }
    }

    /**
     * Sends a player everything the team got since the player's last sync, in every dimension the team mapped: the
     * player's own dimension first (nearest regions first), then the others, so they can be looked at on the world
     * map without going there.
     */
    private final class Sync {

        final EntityPlayerMP player;
        final TeamStore store;
        final ArrayDeque<Integer> dimensions = new ArrayDeque<>();
        final List<Integer> completed = new ArrayList<>();
        final ArrayDeque<RegionKey> regions = new ArrayDeque<>();
        final ArrayDeque<ChunkRecord> pending = new ArrayDeque<>();
        int dimension;
        long since, started;
        boolean done;

        Sync(EntityPlayerMP player, TeamStore store) {
            this.player = player;
            this.store = store;
            dimensions.add(player.dimension);
            for (int other : store.dimensions()) {
                if (other != player.dimension) {
                    dimensions.add(other);
                }
            }
            nextDimension();
        }

        private void nextDimension() {
            if (dimensions.isEmpty()) {
                done = true;
                return;
            }
            dimension = dimensions.poll();
            since = store.lastSync(player.getUniqueID(), dimension);
            started = System.currentTimeMillis();
            List<RegionKey> keys = store.regionsNewerThan(dimension, since);
            final int rx = (int) Math.floor(player.posX) >> (4 + REGION_SHIFT);
            final int rz = (int) Math.floor(player.posZ) >> (4 + REGION_SHIFT);
            // Nearest first, the surface before the caves.
            Collections.sort(
                keys,
                (a, b) -> Integer.compare(
                    Math.max(Math.abs(a.rx - rx), Math.abs(a.rz - rz)) * 2 + (a.layer < 0 ? 0 : 1),
                    Math.max(Math.abs(b.rx - rx), Math.abs(b.rz - rz)) * 2 + (b.layer < 0 ? 0 : 1)));
            regions.addAll(keys);
        }

        void step() {
            if (done) {
                return;
            }
            if (player.playerNetServerHandler == null) {
                syncs.remove(player.getUniqueID());
                return;
            }
            // Read the next region file when the queue runs low (one per tick, to spread the disk reads).
            if (pending.size() < ShareNetwork.MAX_RECORDS * SYNC_MESSAGES_PER_TICK && !regions.isEmpty()) {
                // Read without keeping it loaded: catching up on a big map would otherwise fill the memory.
                StoredRegion region = store.peek(regions.poll());
                if (region != null) {
                    UUID id = player.getUniqueID();
                    for (StoredChunk chunk : region.chunks.values()) {
                        if (chunk.received > since && !id.equals(chunk.author)) {
                            pending.add(chunk.record);
                        }
                    }
                }
            }
            for (int i = 0; i < SYNC_MESSAGES_PER_TICK && !pending.isEmpty(); i++) {
                List<ChunkRecord> part = new ArrayList<>();
                while (part.size() < ShareNetwork.MAX_RECORDS && !pending.isEmpty()) {
                    part.add(pending.poll());
                }
                ShareNetwork.sendTo(new ShareNetwork.Chunks(dimension, part), player);
            }
            if (regions.isEmpty() && pending.isEmpty()) {
                store.setLastSync(player.getUniqueID(), dimension, started);
                completed.add(dimension);
                nextDimension();
            }
        }
    }

    // ---------------------------------------------------------------- storage

    private TeamStore store(String teamId) {
        TeamStore store = teams.get(teamId);
        if (store == null) {
            store = new TeamStore(teamDirectory(teamId));
            teams.put(teamId, store);
        }
        return store;
    }

    private static final class RegionKey {

        final int dimension, layer, rx, rz;

        RegionKey(int dimension, int layer, int rx, int rz) {
            this.dimension = dimension;
            this.layer = layer;
            this.rx = rx;
            this.rz = rz;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RegionKey)) {
                return false;
            }
            RegionKey k = (RegionKey) o;
            return k.dimension == dimension && k.layer == layer && k.rx == rx && k.rz == rz;
        }

        @Override
        public int hashCode() {
            return ((dimension * 31 + layer) * 31 + rx) * 31 + rz;
        }

        String layerFolder() {
            return layer < 0 ? "surface" : "cave" + layer;
        }
    }

    private static final class StoredChunk {

        final ChunkRecord record;
        final UUID author;
        /**
         * When the server got it. Catching up goes by this, not by when it was mapped: an old map uploaded today
         * is news for a player who synced yesterday.
         */
        final long received;

        StoredChunk(ChunkRecord record, UUID author, long received) {
            this.record = record;
            this.author = author;
            this.received = received;
        }
    }

    private static final class StoredRegion {

        final Map<Integer, StoredChunk> chunks = new HashMap<>();
        long newest;
        boolean dirty;
        long lastUse;
        /** Writes queued or running (changed by the writer thread too), and whether the last one failed. */
        final AtomicInteger writing = new AtomicInteger();
        volatile boolean failed;
    }

    /** One team's map on disk, with its regions loaded on demand. */
    private static final class TeamStore {

        final File dir;
        final Map<RegionKey, StoredRegion> loaded = new HashMap<>();
        /** Newest chunk time of every region file, per dimension, read when first needed. */
        final Map<Integer, Map<RegionKey, Long>> index = new HashMap<>();
        NBTTagCompound lastSyncs;
        boolean lastSyncsDirty;

        TeamStore(File dir) {
            this.dir = dir;
        }

        File file(RegionKey key) {
            return new File(
                new File(new File(dir, "dim" + key.dimension), key.layerFolder()),
                "r." + key.rx + "." + key.rz + ".bin");
        }

        /** Keeps the chunk if it is newer than the team's version; returns whether it did. */
        boolean put(int dimension, ChunkRecord record, UUID author) {
            RegionKey key = new RegionKey(
                dimension,
                record.layer,
                record.chunkX >> REGION_SHIFT,
                record.chunkZ >> REGION_SHIFT);
            StoredRegion region = region(key, true);
            int index = (record.chunkX & 31) | (record.chunkZ & 31) << REGION_SHIFT;
            StoredChunk existing = region.chunks.get(index);
            if (existing != null && existing.record.time >= record.time) {
                return false;
            }
            long received = System.currentTimeMillis();
            region.chunks.put(index, new StoredChunk(record, author, received));
            region.newest = Math.max(region.newest, received);
            region.dirty = true;
            index(dimension).put(key, region.newest);
            return true;
        }

        StoredRegion region(RegionKey key, boolean create) {
            StoredRegion region = loaded.get(key);
            if (region == null) {
                region = read(file(key));
                if (region == null) {
                    if (!create) {
                        return null;
                    }
                    region = new StoredRegion();
                }
                loaded.put(key, region);
            }
            region.lastUse = System.currentTimeMillis();
            return region;
        }

        /** The region as loaded, or read from its file without loading it; null if there is none. */
        StoredRegion peek(RegionKey key) {
            StoredRegion region = loaded.get(key);
            return region != null ? region : read(file(key));
        }

        Map<RegionKey, Long> index(int dimension) {
            Map<RegionKey, Long> regions = index.get(dimension);
            if (regions == null) {
                regions = new HashMap<>();
                File[] layers = new File(dir, "dim" + dimension).listFiles();
                if (layers != null) {
                    for (File layerDir : layers) {
                        String name = layerDir.getName();
                        int layer;
                        if (name.equals("surface")) {
                            layer = -1;
                        } else if (name.startsWith("cave")) {
                            try {
                                layer = Integer.parseInt(name.substring(4));
                            } catch (NumberFormatException e) {
                                continue;
                            }
                        } else {
                            continue;
                        }
                        File[] files = layerDir.listFiles();
                        if (files == null) {
                            continue;
                        }
                        for (File file : files) {
                            String[] parts = file.getName()
                                .split("\\.");
                            if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("bin")) {
                                continue;
                            }
                            try {
                                RegionKey key = new RegionKey(
                                    dimension,
                                    layer,
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]));
                                regions.put(key, readNewest(file));
                            } catch (Exception e) {
                                // Not a region file or unreadable: skipped.
                            }
                        }
                    }
                }
                index.put(dimension, regions);
            }
            return regions;
        }

        /** Every dimension the team has a map of. */
        List<Integer> dimensions() {
            List<Integer> result = new ArrayList<>(index.keySet());
            File[] dirs = dir.listFiles(
                file -> file.isDirectory() && file.getName()
                    .matches("dim-?\\d+"));
            if (dirs != null) {
                for (File dimensionDir : dirs) {
                    int id = Integer.parseInt(
                        dimensionDir.getName()
                            .substring(3));
                    if (!result.contains(id)) {
                        result.add(id);
                    }
                }
            }
            return result;
        }

        List<RegionKey> regionsNewerThan(int dimension, long since) {
            List<RegionKey> keys = new ArrayList<>();
            for (Map.Entry<RegionKey, Long> entry : index(dimension).entrySet()) {
                if (entry.getValue() > since) {
                    keys.add(entry.getKey());
                }
            }
            return keys;
        }

        long lastSync(UUID player, int dimension) {
            NBTTagCompound players = lastSyncs();
            return players.getCompoundTag(player.toString())
                .getLong("dim" + dimension);
        }

        void setLastSync(UUID player, int dimension, long time) {
            NBTTagCompound players = lastSyncs();
            NBTTagCompound tag = players.getCompoundTag(player.toString());
            tag.setLong("dim" + dimension, time);
            players.setTag(player.toString(), tag);
            lastSyncsDirty = true;
        }

        NBTTagCompound lastSyncs() {
            if (lastSyncs == null) {
                File file = new File(dir, "players.dat");
                try {
                    lastSyncs = file.isFile() ? CompressedStreamTools.read(file) : null;
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not read " + file, e);
                }
                if (lastSyncs == null) {
                    lastSyncs = new NBTTagCompound();
                }
            }
            return lastSyncs;
        }

        void save(boolean all) {
            for (Map.Entry<RegionKey, StoredRegion> entry : loaded.entrySet()) {
                StoredRegion region = entry.getValue();
                if (region.dirty || region.failed) {
                    // The chunks are never changed once stored, so a copy of the map is enough.
                    List<StoredChunk> chunks = new ArrayList<>(region.chunks.values());
                    long newest = region.newest;
                    File file = file(entry.getKey());
                    region.dirty = false;
                    region.failed = false;
                    region.writing.incrementAndGet();
                    WRITER.execute(() -> {
                        try {
                            write(file, newest, chunks);
                        } catch (IOException e) {
                            WayFarMap.LOG.warn("Team map: could not save " + file, e);
                            region.failed = true;
                        } finally {
                            region.writing.decrementAndGet();
                        }
                    });
                }
            }
            if (lastSyncsDirty && lastSyncs != null) {
                try {
                    dir.mkdirs();
                    CompressedStreamTools.safeWrite(lastSyncs, new File(dir, "players.dat"));
                    lastSyncsDirty = false;
                } catch (IOException e) {
                    WayFarMap.LOG.warn("Team map: could not save " + dir, e);
                }
            }
        }

        void unloadIdle(long now) {
            Iterator<StoredRegion> it = loaded.values()
                .iterator();
            while (it.hasNext()) {
                StoredRegion region = it.next();
                // Not while its file is being written: it would be read back half written or outdated.
                boolean saved = !region.dirty && !region.failed && region.writing.get() == 0;
                if (saved && now - region.lastUse > IDLE_UNLOAD_MS) {
                    it.remove();
                }
            }
        }

        /** Writer thread. */
        private static void write(File file, long newest, List<StoredChunk> chunks) throws IOException {
            File tmp = new File(file.getPath() + ".tmp");
            file.getParentFile()
                .mkdirs();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_VERSION);
                out.writeLong(newest);
                out.writeInt(chunks.size());
                for (StoredChunk chunk : chunks) {
                    out.writeLong(chunk.author.getMostSignificantBits());
                    out.writeLong(chunk.author.getLeastSignificantBits());
                    out.writeLong(chunk.received);
                    chunk.record.write(out);
                }
            }
            if (file.exists() && !file.delete()) {
                throw new IOException("Could not replace " + file);
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("Could not rename " + tmp);
            }
        }

        private static StoredRegion read(File file) {
            if (!file.isFile()) {
                return null;
            }
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
                int version;
                if (in.readInt() != FILE_MAGIC || (version = in.readInt()) < 1 || version > FILE_VERSION) {
                    return null;
                }
                StoredRegion region = new StoredRegion();
                region.newest = in.readLong();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    UUID author = new UUID(in.readLong(), in.readLong());
                    long received = version >= 2 ? in.readLong() : 0;
                    ChunkRecord record = ChunkRecord.read(in);
                    region.chunks.put(
                        (record.chunkX & 31) | (record.chunkZ & 31) << REGION_SHIFT,
                        new StoredChunk(record, author, version >= 2 ? received : record.time));
                }
                return region;
            } catch (IOException e) {
                WayFarMap.LOG.warn("Team map: could not read " + file, e);
                return null;
            }
        }

        private static long readNewest(File file) throws IOException {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
                int version;
                if (in.readInt() != FILE_MAGIC || (version = in.readInt()) < 1 || version > FILE_VERSION) {
                    throw new IOException("Not a team map region");
                }
                return in.readLong();
            }
        }
    }
}
