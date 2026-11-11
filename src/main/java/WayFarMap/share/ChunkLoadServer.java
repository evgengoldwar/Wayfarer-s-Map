package WayFarMap.share;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkWatchEvent;

import WayFarMap.Config;
import WayFarMap.Perf;
import WayFarMap.WayFarMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * {@code /wf chunkload 2d|3d <radius>}: maps a large area around the player, generating the chunks not made yet. The
 * server loads (or generates) the chunks batch by batch, nearest first, and sends them to the player as the game
 * sends chunks; the player's map maps them (the flat map, and with {@code 3d} the 3D map too) and lets them go, then
 * asks for the next batch. Only for players allowed to cheat (operators). Goes on after the player or the server
 * comes back; {@code /wf chunkload stop} ends it.
 * <p>
 * {@code /wf regionload 2d|3d <radius>|full} does the same from the world's saved chunks only: chunks the region files
 * ({@code r.X.Z.mca}) don't have are left out, nothing is generated. {@code full} takes every region file of the
 * dimension. It brings back a map that was deleted, from the world itself.
 */
public final class ChunkLoadServer {

    public static final ChunkLoadServer INSTANCE = new ChunkLoadServer();

    /** Chunks per side of a batch when none is saved with the job (jobs started before it could be set). */
    private static final int DEFAULT_BATCH = 8;
    /** Chunks per packet, as the game sends them. */
    private static final int PER_PACKET = 5;
    private static final String FILE = "wayfarmap_chunkload.dat";

    /** One player's area being mapped. */
    private static final class Job {

        final UUID player;
        final int dimension, centerX, centerZ, radius;
        final boolean with3d;
        /** {@code /wf regionload}: only chunks saved in the world's region files, none generated. */
        final boolean savedOnly;
        final int id;
        /** Chunks per side of a batch (mapped); a ring of one more is sent around it for their neighbours. */
        final int batch;
        /** Batches in the order they are done: grid positions {i, j} around the center, nearest first. */
        /** Null for a {@link #full} job until its region files are looked at ({@link #regionOrder}). */
        int[] order;
        int index;
        long done;
        long total;
        /** {@code /wf regionload ... full}: the batches are those over the region files, not a whole square. */
        final boolean full;
        final long started;
        // Not saved:
        /** Chunks of the batch being loaded, and how far. */
        int[] loading;
        int loadingAt;
        boolean waiting;
        EntityPlayerMP sentTo;
        int[] previousOuter;
        boolean pausedTold;
        /** For the log: when the batch being loaded was started, and the time spent working on it. */
        long batchStarted, workNanos;
        /**
         * Chunks the player was made to watch for the map ({@link Watching}), by chunk key, with the player then: let
         * go with the batch they belong to, or when the player leaves or changes dimension.
         */
        final Map<Long, EntityPlayerMP> watched = new HashMap<>();
        /** Chunks the mods were only told the player watches (it couldn't be made a watcher): told it stops after. */
        final Map<Long, EntityPlayerMP> told = new HashMap<>();
        /** {@link #savedOnly}: which region files are there, by region key (looked at once each). */
        final Map<Long, Boolean> regionFiles = new HashMap<>();
        /**
         * Chunks picked on the world map ({@link #pack}), or null: then only they are mapped, and loaded with the
         * ring of chunks around them that the game needs to finish them (trees, ores).
         */
        java.util.Set<Long> selected;
        /**
         * The last pick of the player's map in it ({@link ShareNetwork.LoadChunks#seq}), told back when it ends so the
         * map stops showing those chunks queued. All picks for a job of the commands or one taken up after a restart.
         */
        int seq = Integer.MAX_VALUE;

        Job(UUID player, int dimension, int centerX, int centerZ, int radius, boolean with3d, int id, long started,
            int batch, boolean savedOnly, boolean full) {
            this(player, dimension, centerX, centerZ, radius, with3d, id, started, batch, savedOnly, full, null);
        }

        Job(UUID player, int dimension, int centerX, int centerZ, int radius, boolean with3d, int id, long started,
            int batch, boolean savedOnly, boolean full, java.util.Set<Long> selected) {
            this.player = player;
            this.dimension = dimension;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.with3d = with3d;
            this.savedOnly = savedOnly;
            this.full = full;
            this.id = id;
            this.started = started;
            this.batch = Math.max(2, batch);
            this.selected = selected;
            if (selected != null) {
                selectionOrder();
            } else if (!full) {
                this.order = spiral((radius + this.batch - 1) / this.batch, radius, this.batch);
                long side = 2L * radius + 1;
                this.total = side * side;
            }
        }

        /** Inner chunks of batch n: {x0, z0, x1, z1}, inclusive, within the area. */
        int[] inner(int n) {
            int i = order[n * 2], j = order[n * 2 + 1];
            int x0 = Math.max(centerX - radius, centerX + i * batch - batch / 2);
            int z0 = Math.max(centerZ - radius, centerZ + j * batch - batch / 2);
            int x1 = Math.min(centerX + radius, centerX + i * batch + batch / 2 - 1);
            int z1 = Math.min(centerZ + radius, centerZ + j * batch + batch / 2 - 1);
            return new int[] { x0, z0, x1, z1 };
        }

        /**
         * A full job's batches: those over the dimension's region files, nearest to the center first. A square
         * around far apart regions could hold millions of empty batches; this holds only the ones with a region.
         */
        void regionOrder(WorldServer world) {
            java.util.Set<Long> cells = new java.util.HashSet<>();
            regionCells(world, cells);
            orderCells(cells);
        }

        private void regionCells(WorldServer world, java.util.Set<Long> cells) {
            for (int[] region : regionFiles(world)) {
                int x0 = region[0] * 32, z0 = region[1] * 32;
                int i0 = Math.floorDiv(x0 - centerX + batch / 2, batch);
                int i1 = Math.floorDiv(x0 + 31 - centerX + batch / 2, batch);
                int j0 = Math.floorDiv(z0 - centerZ + batch / 2, batch);
                int j1 = Math.floorDiv(z0 + 31 - centerZ + batch / 2, batch);
                for (int i = i0; i <= i1; i++) {
                    for (int j = j0; j <= j1; j++) {
                        cells.add(((long) i << 32) | (j & 0xFFFFFFFFL));
                    }
                }
            }
        }

        /** A picked chunks job's batches: those holding a picked chunk, nearest first. */
        void selectionOrder() {
            java.util.Set<Long> cells = new java.util.HashSet<>();
            for (long chunk : selected) {
                int i = Math.floorDiv(unpackX(chunk) - centerX + batch / 2, batch);
                int j = Math.floorDiv(unpackZ(chunk) - centerZ + batch / 2, batch);
                cells.add(((long) i << 32) | (j & 0xFFFFFFFFL));
            }
            orderCells(cells);
            total = selected.size();
        }

        /** Whether the chunk or one around it is picked (a picked chunks job loads only those). */
        boolean nearSelected(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (selected.contains(pack(x + dx, z + dz))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Picked chunks in the batch. */
        int selectedIn(int[] inner) {
            int count = 0;
            for (int x = inner[0]; x <= inner[2]; x++) {
                for (int z = inner[1]; z <= inner[3]; z++) {
                    if (selected.contains(pack(x, z))) {
                        count++;
                    }
                }
            }
            return count;
        }

        /** The batches from {@code cells}, nearest to the center first; the total is their chunks. */
        private void orderCells(java.util.Set<Long> cells) {
            List<int[]> list = new ArrayList<>();
            for (long cell : cells) {
                list.add(new int[] { (int) (cell >> 32), (int) cell });
            }
            list.sort(
                (a, b) -> a[0] * a[0] + a[1] * a[1] != b[0] * b[0] + b[1] * b[1]
                    ? Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1])
                    : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            order = new int[list.size() * 2];
            total = 0;
            for (int n = 0; n < list.size(); n++) {
                order[n * 2] = list.get(n)[0];
                order[n * 2 + 1] = list.get(n)[1];
                int[] inner = inner(n);
                total += (long) (inner[2] - inner[0] + 1) * (inner[3] - inner[1] + 1);
            }
        }

        int batches() {
            return order.length / 2;
        }
    }

    static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    static int unpackZ(long packed) {
        return (int) packed;
    }

    private final Map<UUID, Job> jobs = new HashMap<>();
    /** The last pick number each player's map sent ({@link ShareNetwork.LoadChunks#seq}). */
    private final Map<UUID, Integer> lastSeq = new HashMap<>();
    /** Progress is saved this often while jobs run, so they go on after a restart. */
    private static final long SAVE_MS = 5000;
    private long lastSave;
    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();

    private ChunkLoadServer() {}

    /**
     * Batch grid positions ring by ring from the middle ({i, j} pairs), leaving out those outside the area of the
     * radius (the outer ring may reach past it).
     */
    static int[] spiral(int rings, int radius, int batch) {
        int side = 2 * rings + 1;
        int[] order = new int[side * side * 2];
        int n = 0;
        for (int k = 0; k <= rings; k++) {
            List<int[]> ring = new ArrayList<>();
            if (k == 0) {
                ring.add(new int[] { 0, 0 });
            }
            // Round the ring: top row, right column, bottom row, left column.
            for (int i = -k; i < k; i++) {
                ring.add(new int[] { i, -k });
            }
            for (int j = -k; j < k; j++) {
                ring.add(new int[] { k, j });
            }
            for (int i = k; i > -k; i--) {
                ring.add(new int[] { i, k });
            }
            for (int j = k; j > -k; j--) {
                ring.add(new int[] { -k, j });
            }
            for (int[] p : ring) {
                if (within(p[0], radius, batch) && within(p[1], radius, batch)) {
                    order[n++] = p[0];
                    order[n++] = p[1];
                }
            }
        }
        return java.util.Arrays.copyOf(order, n);
    }

    /** Whether the batches in column (or row) i hold any chunk within the radius. */
    private static boolean within(int i, int radius, int batch) {
        return i * batch - batch / 2 <= radius && i * batch + batch / 2 - 1 >= -radius;
    }

    // ---------------------------------------------------------------- the command

    public static final class Command extends CommandBase {

        @Override
        public String getCommandName() {
            return "wf";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/wf chunkload <2d|3d> <radius in chunks> | /wf regionload <2d|3d> <radius in chunks|full> | "
                + "/wf chunkload|regionload stop | /wf chunkload|regionload status";
        }

        /** Operators only (single player: with cheats allowed). */
        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            boolean regions = args.length >= 2 && args[0].equalsIgnoreCase("regionload");
            if (args.length < 2 || !args[0].equalsIgnoreCase("chunkload") && !regions) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            String what = args[1].toLowerCase();
            if (what.equals("stop")) {
                INSTANCE.stop(player);
            } else if (what.equals("status")) {
                INSTANCE.status(player);
            } else if ((what.equals("2d") || what.equals("3d")) && args.length >= 3) {
                boolean with3d = what.equals("3d");
                if (regions && args[2].equalsIgnoreCase("full")) {
                    INSTANCE.beginFull(player, with3d);
                } else {
                    int radius = parseIntWithMin(sender, args[2], 1);
                    INSTANCE.begin(player, with3d, radius, regions);
                }
            } else {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }

        @Override
        public List addTabCompletionOptions(ICommandSender sender, String[] args) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "chunkload", "regionload");
            }
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, "2d", "3d", "stop", "status");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("regionload")) {
                return getListOfStringsMatchingLastWord(args, "full");
            }
            return null;
        }
    }

    private void begin(EntityPlayerMP player, boolean with3d, int radius, boolean savedOnly) {
        int cx = (int) Math.floor(player.posX) >> 4, cz = (int) Math.floor(player.posZ) >> 4;
        Job job = start(player, with3d, cx, cz, radius, savedOnly, false, null);
        player.addChatMessage(
            new ChatComponentTranslation(
                savedOnly ? "wayfarmap.regionload.started" : "wayfarmap.chunkload.started",
                with3d ? "3D" : "2D",
                2 * radius + 1,
                2 * radius + 1,
                job.total,
                (long) radius * 16));
    }

    /** {@code /wf regionload ... full}: the area of all the dimension's region files. */
    private void beginFull(EntityPlayerMP player, boolean with3d) {
        WorldServer world = DimensionManager.getWorld(player.dimension);
        List<int[]> regions = world == null ? new ArrayList<>() : regionFiles(world);
        if (regions.isEmpty()) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.regionload.no_regions"));
            return;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] region : regions) {
            minX = Math.min(minX, region[0]);
            minZ = Math.min(minZ, region[1]);
            maxX = Math.max(maxX, region[0]);
            maxZ = Math.max(maxZ, region[1]);
        }
        // Centered on them all, the radius reaching all of them; only the batches over a region are done.
        int x0 = minX * 32, z0 = minZ * 32, x1 = maxX * 32 + 31, z1 = maxZ * 32 + 31;
        int cx = Math.floorDiv(x0 + x1, 2), cz = Math.floorDiv(z0 + z1, 2);
        int radius = Math.max(Math.max(cx - x0, x1 - cx), Math.max(cz - z0, z1 - cz));
        Job job = start(player, with3d, cx, cz, radius, true, true, world);
        player.addChatMessage(
            new ChatComponentTranslation(
                "wayfarmap.regionload.started_full",
                with3d ? "3D" : "2D",
                regions.size(),
                x1 - x0 + 1,
                z1 - z0 + 1,
                job.total));
    }

    private static final java.util.regex.Pattern REGION_FILE = java.util.regex.Pattern
        .compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /**
     * Some mods send what their blocks keep only to players who start watching the chunk, not with the chunk nor in
     * a description packet (ForgeMultipart's parts: without them its microblocks came out empty on the 3D map, 52 to
     * 99% of them, against 1-2% for chunks met while playing): told the player does, as the game tells them when it
     * sends a chunk. That it no longer does when the chunk is let go ({@link Watching#unwatch}, the game tells
     * them), not right after: ForgeMultipart sends the parts at the end of the tick, to the players watching then.
     */
    private static void tellWatched(WorldServer world, EntityPlayerMP player, Chunk chunk) {
        try {
            if (player.worldObj != world) {
                // The mods look for the chunk in the player's world: only for the dimension the player is in.
                return;
            }
            ChunkCoordIntPair at = chunk.getChunkCoordIntPair();
            MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.Watch(at, player));
        } catch (RuntimeException e) {
            // A mod failing on it: its blocks are drawn without what it would have sent.
            WayFarMap.LOG.debug("A mod failed on chunk " + chunk.xPosition + ", " + chunk.zPosition + " watched", e);
        }
    }

    /** The region files of the world's dimension, as {rx, rz}. */
    private static List<int[]> regionFiles(WorldServer world) {
        List<int[]> regions = new ArrayList<>();
        File[] files = new File(world.getChunkSaveLocation(), "region").listFiles();
        if (files != null) {
            for (File file : files) {
                java.util.regex.Matcher m = REGION_FILE.matcher(file.getName());
                if (m.matches() && file.length() > 0) {
                    regions.add(new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) });
                }
            }
        }
        return regions;
    }

    private Job start(EntityPlayerMP player, boolean with3d, int cx, int cz, int radius, boolean savedOnly,
        boolean full, WorldServer world) {
        Job old = jobs.remove(player.getUniqueID());
        if (old != null) {
            release(old, old.previousOuter, null);
            release(old, old.loading, null);
            unwatchAll(old);
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.replaced"));
        }
        // The chunks queued on the map so far are not loaded anymore: no longer shown waiting.
        ended(player, seqOf(player), false);
        Job job = new Job(
            player.getUniqueID(),
            player.dimension,
            cx,
            cz,
            radius,
            with3d,
            (int) (System.nanoTime() & 0x7FFFFFFF),
            System.currentTimeMillis(),
            Config.chunkloadBatch,
            savedOnly,
            full);
        if (full) {
            job.regionOrder(world);
        }
        jobs.put(job.player, job);
        save();
        WayFarMap.LOG.info(
            "{} started mapping {} chunks around {}, {} in dimension {} ({}{})",
            player.getCommandSenderName(),
            job.total,
            cx,
            cz,
            job.dimension,
            with3d ? "3D" : "2D",
            savedOnly ? ", saved chunks only" : "");
        return job;
    }

    private void stop(EntityPlayerMP player) {
        Job job = jobs.remove(player.getUniqueID());
        // Told even with nothing running: the map may still show chunks queued from before.
        ended(player, seqOf(player), false);
        if (job == null) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.none"));
            return;
        }
        release(job, job.previousOuter, null);
        release(job, job.loading, null);
        unwatchAll(job);
        save();
        player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.stopped", job.done, job.total));
    }

    private void status(EntityPlayerMP player) {
        Job job = jobs.get(player.getUniqueID());
        if (job == null) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.none"));
            return;
        }
        player.addChatMessage(
            new ChatComponentTranslation(
                "wayfarmap.chunkload.status",
                job.with3d ? "3D" : "2D",
                job.done,
                job.total,
                job.done * 100 / Math.max(1, job.total)));
    }

    // ---------------------------------------------------------------- running

    /** From the network thread: the player's client mapped a batch. */
    void receive(EntityPlayerMP player, ShareNetwork.LoadDone message) {
        inbox.add(new Object[] { player.getUniqueID(), message });
    }

    /** From the network thread: chunks picked on the player's world map. */
    void receive(EntityPlayerMP player, ShareNetwork.LoadChunks message) {
        inbox.add(new Object[] { player.getUniqueID(), message });
    }

    /** From the network thread: the region loading view asks which chunks are saved. */
    void receive(EntityPlayerMP player, ShareNetwork.SavedRequest message) {
        inbox.add(new Object[] { player.getUniqueID(), message });
    }

    /** From the network thread: the map asks which chunks of every region file are saved. */
    void receive(EntityPlayerMP player, ShareNetwork.SavedScan message) {
        inbox.add(new Object[] { player.getUniqueID(), message });
    }

    /** Region files per tick a scan answers ({@link ShareNetwork.SavedScan}): the region cache holds 256 files. */
    private static final int SCAN_PER_TICK = 16;

    /** Scans of all region files going on, by player: {dimension, regions left, region count}. */
    private final Map<UUID, Object[]> scans = new HashMap<>();

    /** Starts answering which chunks of every region file of the player's dimension are saved (operators only). */
    private void startScan(EntityPlayerMP player) {
        if (!player.canCommandSenderUseCommand(2, "wf")) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.not_allowed"));
            return;
        }
        WorldServer world = DimensionManager.getWorld(player.dimension);
        List<int[]> regions = world == null ? new ArrayList<>() : regionFiles(world);
        scans.put(player.getUniqueID(), new Object[] { player.dimension, new ArrayDeque<>(regions), regions.size() });
    }

    /** A few region files of each scan a tick; the end is told once all are answered. */
    @SuppressWarnings("unchecked")
    private void continueScans() {
        java.util.Iterator<Map.Entry<UUID, Object[]>> it = scans.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Object[]> entry = it.next();
            EntityPlayerMP player = online(entry.getKey());
            int dimension = (Integer) entry.getValue()[0];
            if (player == null || player.dimension != dimension) {
                it.remove();
                continue;
            }
            WorldServer world = DimensionManager.getWorld(dimension);
            ArrayDeque<int[]> left = (ArrayDeque<int[]>) entry.getValue()[1];
            if (world != null) {
                for (int n = 0; n < SCAN_PER_TICK && !left.isEmpty(); n++) {
                    int[] region = left.poll();
                    ShareNetwork.sendTo(savedChunks(world, dimension, region[0], region[1]), player);
                }
            } else {
                left.clear();
            }
            if (left.isEmpty()) {
                it.remove();
                ShareNetwork.sendTo(new ShareNetwork.SavedScan(dimension, (Integer) entry.getValue()[2]), player);
            }
        }
    }

    /** Answers which chunks of the regions are saved in the player's dimension (operators only). */
    private void answerSaved(EntityPlayerMP player, ShareNetwork.SavedRequest message) {
        if (!player.canCommandSenderUseCommand(2, "wf")) {
            return;
        }
        WorldServer world = DimensionManager.getWorld(player.dimension);
        if (world == null) {
            return;
        }
        for (int n = 0; n + 1 < message.regions.length; n += 2) {
            ShareNetwork
                .sendTo(savedChunks(world, player.dimension, message.regions[n], message.regions[n + 1]), player);
        }
    }

    /** Which chunks of a region are saved in the world (or loaded now). */
    private static ShareNetwork.SavedChunks savedChunks(WorldServer world, int dimension, int rx, int rz) {
        File folder = world.getChunkSaveLocation();
        ShareNetwork.SavedChunks answer = new ShareNetwork.SavedChunks();
        answer.dimension = dimension;
        answer.regionX = rx;
        answer.regionZ = rz;
        File file = new File(new File(folder, "region"), "r." + rx + "." + rz + ".mca");
        // Region files that aren't there are not made (the region cache would create them).
        net.minecraft.world.chunk.storage.RegionFile region = file.isFile() && file.length() > 0
            ? RegionFileCache.createOrLoadRegionFile(folder, rx * 32, rz * 32)
            : null;
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                boolean saved = world.theChunkProviderServer.chunkExists(rx * 32 + lx, rz * 32 + lz);
                if (!saved && region != null) {
                    try {
                        saved = region.isChunkSaved(lx, lz);
                    } catch (RuntimeException e) {
                        saved = false;
                    }
                }
                if (saved) {
                    int bit = lz * 32 + lx;
                    answer.bits[bit >> 6] |= 1L << (bit & 63);
                }
            }
        }
        return answer;
    }

    /** What each online player was last told about loading from the map, by player (a new login is told again). */
    private final Map<UUID, Object[]> toldAllowed = new HashMap<>();
    private int allowedCheckTicks;

    /**
     * Tells players whether they may load chunks from the world map, when they come and when it changes (checked
     * every few seconds: operators can be made or unmade while playing).
     */
    private void tellAllowed() {
        if (++allowedCheckTicks < 100 && !toldAllowedPending) {
            return;
        }
        allowedCheckTicks = 0;
        toldAllowedPending = false;
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            online.add(player.getUniqueID());
            boolean allowed = player.canCommandSenderUseCommand(2, "wf");
            boolean teleport = canUseTeleport(player);
            Object[] told = toldAllowed.get(player.getUniqueID());
            if (told == null || told[0] != player) {
                // Just joined: with nothing being loaded for it, its map forgets the chunks it still shows queued
                // (from an earlier game, or a loading that ended while it was away).
                lastSeq.remove(player.getUniqueID());
                if (!jobs.containsKey(player.getUniqueID())) {
                    ended(player, Integer.MAX_VALUE, false);
                }
            }
            if (told == null || told[0] != player || (Boolean) told[1] != allowed || (Boolean) told[2] != teleport) {
                toldAllowed.put(player.getUniqueID(), new Object[] { player, allowed, teleport });
                ShareNetwork.sendTo(new ShareNetwork.LoadAllowed(allowed, teleport), player);
            }
        }
        toldAllowed.keySet()
            .retainAll(online);
    }

    /**
     * Whether the player may run {@code /tp}: by being an operator or by a ServerUtilities rank. The list of the
     * commands a player may use is the one ServerUtilities hooks into (as for {@code /help}), so it takes ranks into
     * account; without ServerUtilities it is the vanilla check.
     */
    private static boolean canUseTeleport(EntityPlayerMP player) {
        for (Object o : MinecraftServer.getServer()
            .getCommandManager()
            .getPossibleCommands(player)) {
            if (o instanceof net.minecraft.command.ICommand
                && "tp".equals(((net.minecraft.command.ICommand) o).getCommandName())) {
                return true;
            }
        }
        return false;
    }

    /** A player just joined: told on the next tick instead of within the next few seconds. */
    private boolean toldAllowedPending;

    @SubscribeEvent
    public void onPlayerLogin(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        toldAllowedPending = true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        long perf = Perf.start();
        try {
            serverTick(event);
        } finally {
            Perf.end(Perf.Part.CHUNKLOAD, perf);
        }
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tellAllowed();
        if (!scans.isEmpty()) {
            continueScans();
        }
        if (jobs.isEmpty() && inbox.isEmpty()) {
            return;
        }
        Object[] next;
        while ((next = inbox.poll()) != null) {
            if (next[1] instanceof ShareNetwork.SavedScan) {
                EntityPlayerMP player = online((UUID) next[0]);
                if (player != null) {
                    startScan(player);
                }
                continue;
            }
            if (next[1] instanceof ShareNetwork.SavedRequest) {
                EntityPlayerMP player = online((UUID) next[0]);
                if (player != null) {
                    answerSaved(player, (ShareNetwork.SavedRequest) next[1]);
                }
                continue;
            }
            if (next[1] instanceof ShareNetwork.LoadChunks) {
                EntityPlayerMP player = online((UUID) next[0]);
                if (player != null) {
                    pick(player, (ShareNetwork.LoadChunks) next[1]);
                }
                continue;
            }
            Job job = jobs.get(next[0]);
            ShareNetwork.LoadDone done = (ShareNetwork.LoadDone) next[1];
            if (job != null && job.waiting && done.job == job.id && done.index == job.index) {
                batchDone(job);
            }
        }
        long end = System.nanoTime() + Math.max(1, Config.chunkloadServerMs) * 1_000_000L;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (System.nanoTime() >= end) {
                break;
            }
            work(job, end);
        }
    }

    /**
     * Chunks picked on the world map: added to (or taken off) the player's picked chunks job, which goes on with
     * what it hasn't done yet. Operators only, like the command.
     */
    private void pick(EntityPlayerMP player, ShareNetwork.LoadChunks message) {
        if (!player.canCommandSenderUseCommand(2, "wf")) {
            player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.not_allowed"));
            return;
        }
        lastSeq.put(player.getUniqueID(), Math.max(seqOf(player), message.seq));
        java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
        Job old = jobs.get(player.getUniqueID());
        if (old != null && old.selected != null && old.dimension == player.dimension && old.order != null) {
            // What the old job hasn't mapped yet: the batch being done and those after it.
            for (int n = old.index; n < old.batches(); n++) {
                int[] inner = old.inner(n);
                for (int x = inner[0]; x <= inner[2]; x++) {
                    for (int z = inner[1]; z <= inner[3]; z++) {
                        if (old.selected.contains(pack(x, z))) {
                            chunks.add(pack(x, z));
                        }
                    }
                }
            }
        }
        for (long chunk : message.chunks) {
            if (message.remove) {
                chunks.remove(chunk);
            } else {
                chunks.add(chunk);
            }
        }
        if (old != null) {
            jobs.remove(old.player);
            release(old, old.previousOuter, null);
            release(old, old.loading, null);
            unwatchAll(old);
        }
        if (chunks.isEmpty()) {
            save();
            ended(player, message.seq, false);
            if (old != null && old.selected != null) {
                player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.picked_none"));
            }
            return;
        }
        // For the 3D map too if these chunks or those still waiting were asked for it.
        boolean with3d = message.with3d || old != null && old.selected != null && old.with3d;
        // Nothing generated if these picks and those still waiting are all from the world's saved chunks.
        boolean savedOnly = message.savedOnly && (old == null || old.selected == null || old.savedOnly);
        int cx = (int) Math.floor(player.posX) >> 4, cz = (int) Math.floor(player.posZ) >> 4;
        int radius = 1;
        for (long chunk : chunks) {
            radius = Math.max(radius, Math.max(Math.abs(unpackX(chunk) - cx), Math.abs(unpackZ(chunk) - cz)));
        }
        Job job = new Job(
            player.getUniqueID(),
            player.dimension,
            cx,
            cz,
            radius,
            with3d,
            (int) (System.nanoTime() & 0x7FFFFFFF),
            System.currentTimeMillis(),
            Config.chunkloadBatch,
            savedOnly,
            false,
            chunks);
        job.seq = message.seq;
        jobs.put(job.player, job);
        save();
        if (!message.remove) {
            player.addChatMessage(
                new ChatComponentTranslation("wayfarmap.chunkload.picked", message.chunks.length, chunks.size()));
        }
    }

    /** The last pick number the player's map sent, 0 if none. */
    private int seqOf(EntityPlayerMP player) {
        Integer seq = lastSeq.get(player.getUniqueID());
        return seq == null ? 0 : seq;
    }

    /**
     * Tells the player's map that the loading of its picks up to {@code seq} ended (see
     * {@link ShareNetwork.LoadEnded}).
     */
    private static void ended(EntityPlayerMP player, int seq, boolean finished) {
        if (player != null) {
            ShareNetwork.sendTo(new ShareNetwork.LoadEnded(seq, finished), player);
        }
    }

    private static EntityPlayerMP online(UUID id) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.getUniqueID()
                .equals(id)) {
                return player;
            }
        }
        return null;
    }

    private void work(Job job, long end) {
        EntityPlayerMP player = online(job.player);
        if (player == null) {
            // Goes on when the player is back (the batch is sent again: the client lost it).
            job.waiting = false;
            job.sentTo = null;
            pause(job);
            return;
        }
        if (player.dimension != job.dimension) {
            job.waiting = false;
            pause(job);
            if (!job.pausedTold) {
                job.pausedTold = true;
                player.addChatMessage(new ChatComponentTranslation("wayfarmap.chunkload.paused", job.dimension));
            }
            return;
        }
        job.pausedTold = false;
        if (job.waiting) {
            if (job.sentTo != player) {
                // The player came back (a new connection): the batch again.
                job.waiting = false;
            } else {
                return;
            }
        }
        WorldServer world = DimensionManager.getWorld(job.dimension);
        if (world == null) {
            return;
        }
        if (job.order == null) {
            // A full job taken up after a restart: its batches from the region files again.
            job.regionOrder(world);
        }
        if (job.index >= job.batches()) {
            jobs.remove(job.player);
            unwatchAll(job);
            save();
            ended(player, job.seq, true);
            return;
        }
        ChunkProviderServer provider = world.theChunkProviderServer;
        // Batches with nothing saved (/wf regionload) are passed at once: several of them a tick.
        while (!job.waiting && jobs.get(job.player) == job && System.nanoTime() < end) {
            if (!workBatch(job, player, world, provider, end)) {
                return;
            }
        }
    }

    /**
     * The job waits for its player (gone, or in another dimension): the chunks it loaded are let go meanwhile, or
     * they stayed loaded for as long as the server ran if the player never came back. The batch is loaded again when
     * the job goes on.
     */
    private void pause(Job job) {
        release(job, job.previousOuter, null);
        release(job, job.loading, null);
        job.previousOuter = null;
        job.loading = null;
        unwatchAll(job);
    }

    /** Loads (more of) the batch, and sends it once loaded; false while it isn't loaded yet. */
    private boolean workBatch(Job job, EntityPlayerMP player, WorldServer world, ChunkProviderServer provider,
        long end) {
        if (job.loading == null) {
            int[] inner = job.inner(job.index);
            job.loading = new int[] { inner[0] - 1, inner[1] - 1, inner[2] + 1, inner[3] + 1 };
            job.loadingAt = 0;
            job.batchStarted = System.nanoTime();
            job.workNanos = 0;
        }
        long workStart = System.nanoTime();
        int[] outer = job.loading;
        int width = outer[2] - outer[0] + 1, count = width * (outer[3] - outer[1] + 1);
        while (job.loadingAt < count && System.nanoTime() < end) {
            int x = outer[0] + job.loadingAt % width, z = outer[1] + job.loadingAt / width;
            if (job.savedOnly && !isSaved(job, world, x, z) || job.selected != null && !job.nearSelected(x, z)) {
                // Never saved (nothing to map there, nothing is generated), or not near a picked chunk.
                job.loadingAt++;
                continue;
            }
            try {
                // Generated (and decorated once its neighbours are there) if it wasn't yet.
                provider.loadChunk(x, z);
            } catch (RuntimeException e) {
                WayFarMap.LOG.warn("Could not load chunk " + x + ", " + z + " for /wf chunkload", e);
            }
            job.loadingAt++;
        }
        job.workNanos += System.nanoTime() - workStart;
        if (job.loadingAt < count) {
            return false;
        }
        send(job, player, world, outer);
        return true;
    }

    /** Sends the batch's chunks as the game sends them, their tile entities, then the batch itself. */
    private void send(Job job, EntityPlayerMP player, WorldServer world, int[] outer) {
        List<Chunk> chunks = new ArrayList<>();
        int reloaded = 0, missing = 0;
        long reloadStart = System.nanoTime();
        for (int z = outer[1]; z <= outer[3]; z++) {
            for (int x = outer[0]; x <= outer[2]; x++) {
                if (job.savedOnly && !isSaved(job, world, x, z) || job.selected != null && !job.nearSelected(x, z)) {
                    continue;
                }
                if (!world.theChunkProviderServer.chunkExists(x, z)) {
                    // Loaded in an earlier tick and let go since (the server unloads chunks no player is near):
                    // loaded again (from disk now), or it would be missing on the map.
                    try {
                        world.theChunkProviderServer.loadChunk(x, z);
                        reloaded++;
                    } catch (RuntimeException e) {
                        WayFarMap.LOG.warn("Could not load chunk " + x + ", " + z + " for /wf chunkload", e);
                    }
                }
                if (world.theChunkProviderServer.chunkExists(x, z)) {
                    chunks.add(world.getChunkFromChunkCoords(x, z));
                } else {
                    missing++;
                }
            }
        }
        job.workNanos += System.nanoTime() - reloadStart;
        if ((job.savedOnly || job.selected != null) && chunks.isEmpty()) {
            // Nothing (saved, or picked) in the whole batch: on to the next one without asking the player's map.
            batchDone(job);
            return;
        }
        if (reloaded > 0 || missing > 0) {
            WayFarMap.LOG.info(
                "/wf chunkload batch {}: {} chunks let go by the server before they were sent were loaded again, {} "
                    + "could not be loaded",
                job.index,
                reloaded,
                missing);
        }
        // Sent here, its tile entities too, and the mods told the player watches it (ForgeMultipart sends its parts
        // then). The player is also made a watcher of the chunk in the game's chunk watcher, so mods sending to
        // the players watching a chunk send to it too (GregTech's covers); the game itself isn't left to send it:
        // it sends only chunks that ticked, which those far from players never do (they never came, the client
        // waited 30 s for each). Chunks the player watches already as it plays are on its client: sent again,
        // the client's copy was replaced and lost what the mods had sent (microblocks gone even from what the
        // player recorded).
        List<Chunk> toSend = new ArrayList<>();
        int watching = 0, alreadyWatched = 0;
        for (Chunk chunk : chunks) {
            long chunkKey = key(chunk.xPosition, chunk.zPosition);
            if (job.watched.containsKey(chunkKey)) {
                // Watched for an earlier batch (in the ring around it), maybe let go by the client since (it keeps
                // only the chunks near the player): sent again.
                watching++;
                toSend.add(chunk);
                continue;
            }
            if (player.worldObj == world && world.getPlayerManager()
                .isPlayerWatchingChunk(player, chunk.xPosition, chunk.zPosition)) {
                alreadyWatched++;
                continue;
            }
            if (player.worldObj == world && Watching.watch(world, player, chunk.xPosition, chunk.zPosition)) {
                // Sent here, not by the game (it would wait for the chunk to tick).
                player.loadedChunks.remove(chunk.getChunkCoordIntPair());
                job.watched.put(chunkKey, player);
                watching++;
            }
            toSend.add(chunk);
        }
        for (int from = 0; from < toSend.size(); from += PER_PACKET) {
            player.playerNetServerHandler.sendPacket(
                new S26PacketMapChunkBulk(toSend.subList(from, Math.min(toSend.size(), from + PER_PACKET))));
        }
        for (Chunk chunk : toSend) {
            for (Object o : chunk.chunkTileEntityMap.values()) {
                TileEntity tileEntity = (TileEntity) o;
                try {
                    Packet packet = tileEntity.getDescriptionPacket();
                    if (packet != null) {
                        player.playerNetServerHandler.sendPacket(packet);
                    }
                } catch (RuntimeException e) {
                    // A tile entity that can't describe itself: drawn as its block.
                }
            }
            tellWatched(world, player, chunk);
            long chunkKey = key(chunk.xPosition, chunk.zPosition);
            if (player.worldObj == world && !job.watched.containsKey(chunkKey)) {
                job.told.put(chunkKey, player);
            }
        }
        int[] inner = job.inner(job.index);
        ShareNetwork.LoadBatch batch = new ShareNetwork.LoadBatch();
        batch.job = job.id;
        batch.index = job.index;
        batch.dimension = job.dimension;
        batch.with3d = job.with3d;
        batch.innerX0 = inner[0];
        batch.innerZ0 = inner[1];
        batch.innerX1 = inner[2];
        batch.innerZ1 = inner[3];
        batch.outerX0 = outer[0];
        batch.outerZ0 = outer[1];
        batch.outerX1 = outer[2];
        batch.outerZ1 = outer[3];
        batch.doneBefore = job.done;
        batch.total = job.total;
        batch.viewDistance = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance();
        batch.sent = chunks.size();
        batch.reloaded = reloaded;
        batch.missing = missing;
        batch.serverMs = (int) ((System.nanoTime() - job.batchStarted) / 1_000_000L);
        batch.workMs = (int) (job.workNanos / 1_000_000L);
        batch.watched = watching;
        batch.alreadyWatched = alreadyWatched;
        if (job.selected != null) {
            int width = inner[2] - inner[0] + 1, count = width * (inner[3] - inner[1] + 1);
            batch.picked = new long[(count + 63) / 64];
            for (int n = 0; n < count; n++) {
                if (job.selected.contains(pack(inner[0] + n % width, inner[1] + n / width))) {
                    batch.picked[n >> 6] |= 1L << (n & 63);
                }
            }
        }
        ShareNetwork.sendTo(batch, player);
        job.waiting = true;
        job.sentTo = player;
    }

    private void batchDone(Job job) {
        job.waiting = false;
        int[] inner = job.inner(job.index);
        job.done += job.selected != null ? job.selectedIn(inner)
            : (long) (inner[2] - inner[0] + 1) * (inner[3] - inner[1] + 1);
        // The batch before is let go (this one stays: the next one needs its ring).
        release(job, job.previousOuter, job.loading);
        job.previousOuter = job.loading;
        job.loading = null;
        job.index++;
        // Now and then, not every few batches: /wf regionload passes many empty ones a tick.
        if (System.currentTimeMillis() - lastSave >= SAVE_MS) {
            save();
        }
        if (job.index >= job.batches()) {
            jobs.remove(job.player);
            release(job, job.previousOuter, null);
            unwatchAll(job);
            save();
            EntityPlayerMP player = online(job.player);
            long seconds = (System.currentTimeMillis() - job.started) / 1000;
            // Chunks that could not be loaded stay queued on the map otherwise.
            ended(player, job.seq, true);
            if (player != null) {
                player.addChatMessage(
                    new ChatComponentTranslation(
                        "wayfarmap.chunkload.finished",
                        job.total,
                        seconds / 3600,
                        seconds / 60 % 60,
                        seconds % 60));
            }
        }
    }

    /**
     * Whether the chunk is in the world already: loaded, or saved in its region file. Region files that aren't there
     * are not made (the game's region cache would create them).
     */
    private static boolean isSaved(Job job, WorldServer world, int x, int z) {
        if (world.theChunkProviderServer.chunkExists(x, z)) {
            return true;
        }
        File folder = world.getChunkSaveLocation();
        long key = ((long) (x >> 5) << 32) | ((z >> 5) & 0xFFFFFFFFL);
        Boolean there = job.regionFiles.get(key);
        if (there == null) {
            File file = new File(new File(folder, "region"), "r." + (x >> 5) + "." + (z >> 5) + ".mca");
            there = file.isFile() && file.length() > 0;
            job.regionFiles.put(key, there);
        }
        if (!there) {
            return false;
        }
        try {
            return RegionFileCache.createOrLoadRegionFile(folder, x, z)
                .isChunkSaved(x & 31, z & 31);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The player no longer watches a chunk it was made to watch for the map ({@link Job#watched}): the game tells its
     * client to drop it and the mods that it no longer watches it. Unless the player came near it since: it is the
     * game's then, watched on as it plays.
     */
    private static void unwatch(Job job, WorldServer world, int x, int z) {
        EntityPlayerMP toldTo = job.told.remove(key(x, z));
        if (toldTo != null && toldTo.worldObj == world
            && !world.getPlayerManager()
                .isPlayerWatchingChunk(toldTo, x, z)) {
            // (Unless the player came near it since: the game told them it watches it as it plays.)
            Watching.toldUnwatched(toldTo, x, z);
        }
        EntityPlayerMP player = job.watched.remove(key(x, z));
        if (player == null) {
            return;
        }
        int view = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance();
        if (player.worldObj == world && online(job.player) == player
            && Math.abs(((int) Math.floor(player.posX) >> 4) - x) <= view
            && Math.abs(((int) Math.floor(player.posZ) >> 4) - z) <= view) {
            return;
        }
        Watching.unwatch(world, player, x, z);
    }

    /** Every chunk the player was made to watch for the map: the player left, changed dimension or it ended. */
    private static void unwatchAll(Job job) {
        if (job.watched.isEmpty() && job.told.isEmpty()) {
            return;
        }
        WorldServer world = DimensionManager.getWorld(job.dimension);
        java.util.Set<Long> keys = new java.util.HashSet<>(job.watched.keySet());
        keys.addAll(job.told.keySet());
        for (Long key : keys) {
            int x = (int) (key >> 32), z = (int) (long) key;
            if (world == null) {
                job.watched.remove(key);
                job.told.remove(key);
            } else {
                unwatch(job, world, x, z);
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Makes a player watch a chunk as the game does when the player comes near ({@code PlayerManager}'s watcher of
     * the chunk): the game sends it, its tile entities and tells the mods (ForgeMultipart's parts, GregTech's covers
     * go to players watching). Its methods are private, found by their names in the development and the game's
     * mappings; without them the chunks are sent the way they were before.
     */
    static final class Watching {

        private static boolean looked;
        /** Making a player watch failed once: not tried again (letting go of those watched still is). */
        private static boolean failed;
        private static Method watcher, add, remove;
        /** The watcher's list of players, to take one off without the game telling its client (see unwatch). */
        private static java.lang.reflect.Field players;

        private Watching() {}

        static boolean works() {
            if (!looked) {
                looked = true;
                try {
                    watcher = method(
                        net.minecraft.server.management.PlayerManager.class,
                        new String[] { "getOrCreateChunkWatcher", "func_72690_a" },
                        int.class,
                        int.class,
                        boolean.class);
                    Class<?> instance = watcher.getReturnType();
                    add = method(instance, new String[] { "addPlayer", "func_73255_a" }, EntityPlayerMP.class);
                    remove = method(instance, new String[] { "removePlayer", "func_73252_b" }, EntityPlayerMP.class);
                    for (java.lang.reflect.Field field : instance.getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            players = field;
                            break;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    WayFarMap.LOG
                        .warn("/wf chunkload can't make the player watch the chunks it sends: {}", e.toString());
                    watcher = null;
                }
            }
            return watcher != null && !failed;
        }

        private static Method method(Class<?> type, String[] names, Class<?>... parameters)
            throws NoSuchMethodException {
            for (String name : names) {
                try {
                    Method method = type.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {}
            }
            throw new NoSuchMethodException(type.getName() + "." + names[0]);
        }

        /** Whether the player now watches the chunk (the server's main thread). */
        static boolean watch(WorldServer world, EntityPlayerMP player, int x, int z) {
            if (!works()) {
                return false;
            }
            try {
                Object chunkWatcher = watcher.invoke(world.getPlayerManager(), x, z, true);
                if (chunkWatcher == null) {
                    return false;
                }
                add.invoke(chunkWatcher, player);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                WayFarMap.LOG.warn("/wf chunkload could not make the player watch chunk " + x + ", " + z, e);
                failed = true;
                return false;
            }
        }

        static void unwatch(WorldServer world, EntityPlayerMP player, int x, int z) {
            if (watcher == null) {
                return;
            }
            try {
                Object chunkWatcher = watcher.invoke(world.getPlayerManager(), x, z, false);
                if (chunkWatcher == null) {
                    return;
                }
                if (player.worldObj == world) {
                    remove.invoke(chunkWatcher, player);
                    return;
                }
                // The player is in another dimension now: the game would tell its client to drop the chunk at
                // those coordinates there (a hole near the player). Only taken off the list, the mods told.
                java.util.List<?> watchers = players == null ? null : (java.util.List<?>) players.get(chunkWatcher);
                if (watchers != null && watchers.remove(player)) {
                    MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.UnWatch(new ChunkCoordIntPair(x, z), player));
                    if (watchers.isEmpty()) {
                        dropWatcher(world, chunkWatcher, x, z);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                WayFarMap.LOG.warn("/wf chunkload could not let go of chunk " + x + ", " + z, e);
            }
        }

        private static boolean dropLooked;
        private static java.lang.reflect.Field instances, instanceList, withPlayers;
        private static Method removeInstance;

        /**
         * A watcher left with no player (its last one taken off from another dimension): taken out of the game's
         * lists and its chunk let go, as the game does when its last player leaves. Kept, the game loaded the chunk
         * again every few minutes and never let it go.
         */
        private static void dropWatcher(WorldServer world, Object chunkWatcher, int x, int z) {
            net.minecraft.server.management.PlayerManager manager = world.getPlayerManager();
            if (!dropLooked) {
                dropLooked = true;
                try {
                    Class<?> type = net.minecraft.server.management.PlayerManager.class;
                    instances = field(type, "playerInstances", "field_72700_c");
                    instanceList = field(type, "playerInstanceList", "field_111193_e");
                    withPlayers = field(type, "chunkWatcherWithPlayers", "field_72697_d");
                    removeInstance = method(
                        net.minecraft.util.LongHashMap.class,
                        new String[] { "remove", "func_76159_d" },
                        long.class);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    WayFarMap.LOG.warn("/wf chunkload can't drop chunk watchers left empty: {}", e.toString());
                    instances = null;
                }
            }
            try {
                if (instances != null) {
                    long key = (long) x + 2147483647L | (long) z + 2147483647L << 32;
                    removeInstance.invoke(instances.get(manager), key);
                    ((java.util.List<?>) instanceList.get(manager)).remove(chunkWatcher);
                    ((java.util.List<?>) withPlayers.get(manager)).remove(chunkWatcher);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                WayFarMap.LOG.debug("Could not drop the watcher of chunk " + x + ", " + z, e);
            }
            world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x, z);
        }

        private static java.lang.reflect.Field field(Class<?> type, String... names) throws NoSuchFieldException {
            for (String name : names) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {}
            }
            throw new NoSuchFieldException(type.getName() + "." + names[0]);
        }

        /** Whether any player watches the chunk in the game's watchers; null if that can't be told. */
        static Boolean isWatched(WorldServer world, int x, int z) {
            works();
            if (watcher == null) {
                return null;
            }
            try {
                Object chunkWatcher = watcher.invoke(world.getPlayerManager(), x, z, false);
                if (chunkWatcher == null) {
                    return false;
                }
                return players == null || !((java.util.List<?>) players.get(chunkWatcher)).isEmpty();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        /** The mods told the player no longer watches a chunk they were only told it watches. */
        static void toldUnwatched(EntityPlayerMP player, int x, int z) {
            try {
                MinecraftForge.EVENT_BUS.post(new ChunkWatchEvent.UnWatch(new ChunkCoordIntPair(x, z), player));
            } catch (RuntimeException e) {
                WayFarMap.LOG.debug("A mod failed on chunk " + x + ", " + z + " no longer watched", e);
            }
        }
    }

    /** Lets the server unload the chunks of an area (not those in {@code keep}, nor near any player). */
    private void release(Job job, int[] area, int[] keep) {
        if (area == null) {
            return;
        }
        WorldServer world = DimensionManager.getWorld(job.dimension);
        if (world == null) {
            return;
        }
        int near = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance() + 2;
        List<int[]> players = new ArrayList<>();
        for (Object o : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            players.add(new int[] { (int) Math.floor(player.posX) >> 4, (int) Math.floor(player.posZ) >> 4 });
        }
        for (int z = area[1]; z <= area[3]; z++) {
            for (int x = area[0]; x <= area[2]; x++) {
                if (keep != null && x >= keep[0] && x <= keep[2] && z >= keep[1] && z <= keep[3]) {
                    continue;
                }
                unwatch(job, world, x, z);
                // Left to the game where a player watches it (it lets go when the last one leaves); any other is
                // let go here. A margin around players instead kept the ring just outside their view loaded for
                // good: the game never lets go of a chunk nobody watches unless told.
                Boolean watched = Watching.isWatched(world, x, z);
                boolean seen = false;
                if (watched != null) {
                    seen = watched;
                } else {
                    for (int[] p : players) {
                        if (Math.abs(p[0] - x) <= near && Math.abs(p[1] - z) <= near) {
                            seen = true;
                            break;
                        }
                    }
                }
                if (!seen && world.theChunkProviderServer.chunkExists(x, z)) {
                    world.theChunkProviderServer.unloadChunksIfNotNearSpawn(x, z);
                }
            }
        }
    }

    // ---------------------------------------------------------------- saved between server starts

    public void start() {
        jobs.clear();
        inbox.clear();
        File file = file();
        if (file == null || !file.isFile()) {
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            NBTTagList list = CompressedStreamTools.readCompressed(in)
                .getTagList("jobs", 10);
            for (int n = 0; n < list.tagCount(); n++) {
                NBTTagCompound tag = list.getCompoundTagAt(n);
                Job job = new Job(
                    new UUID(tag.getLong("most"), tag.getLong("least")),
                    tag.getInteger("dimension"),
                    tag.getInteger("x"),
                    tag.getInteger("z"),
                    tag.getInteger("radius"),
                    tag.getBoolean("3d"),
                    tag.getInteger("id"),
                    tag.getLong("started"),
                    tag.hasKey("batch") ? tag.getInteger("batch") : DEFAULT_BATCH,
                    tag.getBoolean("savedOnly"),
                    tag.getBoolean("full"),
                    picked(tag.getIntArray("picked")));
                job.index = tag.getInteger("index");
                job.done = tag.getLong("done");
                if (job.full || job.index < job.batches()) {
                    jobs.put(job.player, job);
                }
            }
        } catch (IOException | RuntimeException e) {
            WayFarMap.LOG.warn("Could not read " + file, e);
        }
    }

    public void stop() {
        save();
        jobs.values()
            .forEach(ChunkLoadServer::unwatchAll);
        jobs.clear();
        inbox.clear();
        toldAllowed.clear();
        lastSeq.clear();
    }

    private void save() {
        lastSave = System.currentTimeMillis();
        File file = file();
        if (file == null) {
            return;
        }
        NBTTagList list = new NBTTagList();
        for (Job job : jobs.values()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("most", job.player.getMostSignificantBits());
            tag.setLong("least", job.player.getLeastSignificantBits());
            tag.setInteger("dimension", job.dimension);
            tag.setInteger("x", job.centerX);
            tag.setInteger("z", job.centerZ);
            tag.setInteger("radius", job.radius);
            tag.setBoolean("3d", job.with3d);
            tag.setInteger("id", job.id);
            tag.setLong("started", job.started);
            tag.setInteger("batch", job.batch);
            tag.setBoolean("savedOnly", job.savedOnly);
            tag.setBoolean("full", job.full);
            if (job.selected != null) {
                int[] picked = new int[job.selected.size() * 2];
                int n = 0;
                for (long chunk : job.selected) {
                    picked[n++] = unpackX(chunk);
                    picked[n++] = unpackZ(chunk);
                }
                tag.setIntArray("picked", picked);
            }
            tag.setInteger("index", job.index);
            tag.setLong("done", job.done);
            list.appendTag(tag);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("jobs", list);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, out);
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save " + file, e);
        }
    }

    /** The picked chunks saved with a job, or null for a job of the commands. */
    private static java.util.Set<Long> picked(int[] saved) {
        if (saved == null || saved.length < 2) {
            return null;
        }
        java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
        for (int n = 0; n + 1 < saved.length; n += 2) {
            chunks.add(pack(saved[n], saved[n + 1]));
        }
        return chunks;
    }

    private static File file() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : new File(new File(root, "data"), FILE);
    }
}
