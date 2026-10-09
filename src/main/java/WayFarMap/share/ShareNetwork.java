package WayFarMap.share;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.entity.player.EntityPlayerMP;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

/**
 * The team map channel. The client says hello when it joins; a server with this mod and ServerUtilities answers,
 * and from then on the client uploads the chunks it maps and gets its teammates' ones, and the same for the waypoints
 * shared with the team. Servers without the mod never answer, so nothing else is ever sent there.
 */
public final class ShareNetwork {

    /** 3: team waypoints ({@link Waypoints}), sent only when both sides say at least that. */
    public static final int PROTOCOL = 3;
    public static final int WAYPOINTS_PROTOCOL = 3;
    /** Records per message from the server: at most ~50 KB even uncompressed. */
    public static final int MAX_RECORDS = 32;
    /**
     * Records per upload: client packets are limited to 32 KB, and 16 records stay under it even if they don't
     * compress at all.
     */
    public static final int MAX_UPLOAD_RECORDS = 16;

    private static SimpleNetworkWrapper channel;

    private ShareNetwork() {}

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(WayFarMap.MODID);
        channel.registerMessage(HelloToServer.class, Hello.class, 0, Side.SERVER);
        channel.registerMessage(HelloToClient.class, Hello.class, 1, Side.CLIENT);
        channel.registerMessage(ChunksToServer.class, Chunks.class, 2, Side.SERVER);
        channel.registerMessage(ChunksToClient.class, Chunks.class, 3, Side.CLIENT);
        channel.registerMessage(TeammatesToClient.class, Teammates.class, 5, Side.CLIENT);
        channel.registerMessage(LoadBatchToClient.class, LoadBatch.class, 6, Side.CLIENT);
        channel.registerMessage(LoadDoneToServer.class, LoadDone.class, 7, Side.SERVER);
        channel.registerMessage(LoadChunksToServer.class, LoadChunks.class, 8, Side.SERVER);
        channel.registerMessage(SavedRequestToServer.class, SavedRequest.class, 9, Side.SERVER);
        channel.registerMessage(SavedChunksToClient.class, SavedChunks.class, 10, Side.CLIENT);
        channel.registerMessage(LoadAllowedToClient.class, LoadAllowed.class, 11, Side.CLIENT);
        channel.registerMessage(LoadEndedToClient.class, LoadEnded.class, 12, Side.CLIENT);
        channel.registerMessage(WaypointsToServer.class, Waypoints.class, 13, Side.SERVER);
        channel.registerMessage(WaypointsToClient.class, Waypoints.class, 14, Side.CLIENT);
    }

    public static void sendToServer(IMessage message) {
        channel.sendToServer(message);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        channel.sendTo(message, player);
    }

    /**
     * Client hello (asks whether the server shares team maps) and the server's answer, which it sends again
     * whenever the player's team changes: {@code team} is the team id, empty without a team.
     */
    public static final class Hello implements IMessage {

        public int protocol = PROTOCOL;
        public String team = "";

        public Hello() {}

        public Hello(String team) {
            this.team = team == null ? "" : team;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            protocol = buf.readInt();
            byte[] bytes = new byte[Math.min(64, Math.max(0, buf.readShort()))];
            buf.readBytes(bytes);
            team = new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(protocol);
            byte[] bytes = team.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /** Map chunks of one dimension: uploads from a client, or teammates' chunks from the server. */
    public static final class Chunks implements IMessage {

        public int dimension;
        /** An upload of the map the player already had (any dimension, anywhere), not of chunks just mapped. */
        public boolean backfill;
        public final List<ChunkRecord> records = new ArrayList<>();
        /**
         * The records compressed, made on the first send: the server sends the same message to every teammate,
         * and compressing it once instead of once per teammate spares the server's tick.
         */
        private byte[] encoded;

        public Chunks() {}

        public Chunks(int dimension, List<ChunkRecord> records) {
            this.dimension = dimension;
            this.records.addAll(records);
        }

        public Chunks(int dimension, List<ChunkRecord> records, boolean backfill) {
            this(dimension, records);
            this.backfill = backfill;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            dimension = buf.readInt();
            backfill = buf.readBoolean();
            byte[] data = new byte[Math.max(0, Math.min(buf.readInt(), buf.readableBytes()))];
            buf.readBytes(data);
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
                int count = in.readShort();
                for (int i = 0; i < count && i < MAX_RECORDS * 2; i++) {
                    records.add(ChunkRecord.read(in));
                }
            } catch (IOException e) {
                WayFarMap.LOG.warn("Bad team map message", e);
                records.clear();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            byte[] data = encoded;
            if (data == null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                    out.writeShort(records.size());
                    for (ChunkRecord record : records) {
                        record.write(out);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                data = bytes.toByteArray();
                encoded = data;
            }
            buf.writeInt(dimension);
            buf.writeBoolean(backfill);
            buf.writeInt(data.length);
            buf.writeBytes(data);
        }
    }

    /** Where the player's online teammates are (the player not included); sent twice a second. */
    public static final class Teammates implements IMessage {

        /** One teammate. */
        public static final class Mate {

            public UUID id;
            public String name;
            public int dimension;
            public double x, y, z;
            public float yaw;
        }

        public final List<Mate> mates = new ArrayList<>();

        @Override
        public void fromBytes(ByteBuf buf) {
            int count = Math.min(buf.readShort(), 256);
            for (int i = 0; i < count; i++) {
                Mate mate = new Mate();
                mate.id = new UUID(buf.readLong(), buf.readLong());
                byte[] name = new byte[Math.min(64, Math.max(0, buf.readShort()))];
                buf.readBytes(name);
                mate.name = new String(name, StandardCharsets.UTF_8);
                mate.dimension = buf.readInt();
                mate.x = buf.readDouble();
                mate.y = buf.readDouble();
                mate.z = buf.readDouble();
                mate.yaw = buf.readFloat();
                mates.add(mate);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(mates.size());
            for (Mate mate : mates) {
                buf.writeLong(mate.id.getMostSignificantBits());
                buf.writeLong(mate.id.getLeastSignificantBits());
                byte[] name = mate.name.getBytes(StandardCharsets.UTF_8);
                buf.writeShort(name.length);
                buf.writeBytes(name);
                buf.writeInt(mate.dimension);
                buf.writeDouble(mate.x);
                buf.writeDouble(mate.y);
                buf.writeDouble(mate.z);
                buf.writeFloat(mate.yaw);
            }
        }
    }

    /**
     * A batch of chunks of {@code /wf chunkload} was sent to the player (as the game sends chunks, just before this):
     * the client maps the inner ones (the outer ring is there for their neighbours), lets them all go, and answers
     * with {@link LoadDone}.
     */
    public static final class LoadBatch implements IMessage {

        public int job, index, dimension;
        public boolean with3d;
        /** Inner chunks to map and the chunks sent (inner and a ring around), inclusive. */
        public int innerX0, innerZ0, innerX1, innerZ1, outerX0, outerZ0, outerX1, outerZ1;
        /** Chunks of the whole area done before this batch, and in all. */
        public long doneBefore, total;
        /** The server's view distance: chunks this close to the player are the game's, not let go. */
        public int viewDistance;
        /**
         * For the log: chunks sent, those loaded again because the server had let them go before they were sent,
         * those it could not give, and the server's time on the batch (from its start, and working on it).
         */
        public int sent, reloaded, missing;
        public int serverMs, workMs;
        /** For the log: of those sent, the ones the player was made to watch, and those it watched already. */
        public int watched, alreadyWatched;
        /**
         * For chunks picked on the world map: which inner chunks to map (bit (z - innerZ0) * width + x - innerX0);
         * null maps them all. The others are only there for their neighbours (loaded so the picked ones get
         * finished), not to be put on the map.
         */
        public long[] picked;

        @Override
        public void fromBytes(ByteBuf buf) {
            job = buf.readInt();
            index = buf.readInt();
            dimension = buf.readInt();
            with3d = buf.readBoolean();
            innerX0 = buf.readInt();
            innerZ0 = buf.readInt();
            innerX1 = buf.readInt();
            innerZ1 = buf.readInt();
            outerX0 = buf.readInt();
            outerZ0 = buf.readInt();
            outerX1 = buf.readInt();
            outerZ1 = buf.readInt();
            doneBefore = buf.readLong();
            total = buf.readLong();
            viewDistance = buf.readInt();
            sent = buf.readInt();
            reloaded = buf.readInt();
            missing = buf.readInt();
            serverMs = buf.readInt();
            workMs = buf.readInt();
            int words = buf.readInt();
            if (words > 0) {
                picked = new long[Math.min(words, 1024)];
                for (int i = 0; i < picked.length; i++) {
                    picked[i] = buf.readLong();
                }
            }
            // Added later, at the end: a server of an older version sends none (read as 0), an older client
            // leaves them unread.
            if (buf.readableBytes() >= 8) {
                watched = buf.readInt();
                alreadyWatched = buf.readInt();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(job);
            buf.writeInt(index);
            buf.writeInt(dimension);
            buf.writeBoolean(with3d);
            buf.writeInt(innerX0);
            buf.writeInt(innerZ0);
            buf.writeInt(innerX1);
            buf.writeInt(innerZ1);
            buf.writeInt(outerX0);
            buf.writeInt(outerZ0);
            buf.writeInt(outerX1);
            buf.writeInt(outerZ1);
            buf.writeLong(doneBefore);
            buf.writeLong(total);
            buf.writeInt(viewDistance);
            buf.writeInt(sent);
            buf.writeInt(reloaded);
            buf.writeInt(missing);
            buf.writeInt(serverMs);
            buf.writeInt(workMs);
            buf.writeInt(picked == null ? 0 : picked.length);
            if (picked != null) {
                for (long word : picked) {
                    buf.writeLong(word);
                }
            }
            buf.writeInt(watched);
            buf.writeInt(alreadyWatched);
        }
    }

    /** The client mapped a batch of {@code /wf chunkload}: the server may send the next one. */
    public static final class LoadDone implements IMessage {

        public int job, index;

        public LoadDone() {}

        public LoadDone(int job, int index) {
            this.job = job;
            this.index = index;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            job = buf.readInt();
            index = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(job);
            buf.writeInt(index);
        }
    }

    /**
     * Chunks picked on the world map's chunk loading view: to load (generating those not made yet) and map, or to
     * take off the queue again.
     */
    public static final class LoadChunks implements IMessage {

        /** Chunks per message: client packets are limited to 32 KB. */
        public static final int MAX = 3000;

        public boolean remove;
        /** Loaded for the 3D map too (the player records its blocks). */
        public boolean with3d;
        /** Only from the world's saved chunks (the region loading view): nothing is generated. */
        public boolean savedOnly;
        /**
         * The client's number of the pick (the parts of one pick share it): the server tells it back when the loading
         * ends ({@link LoadEnded}), so the client knows which of its queued chunks ended with it.
         */
        public int seq;
        public long[] chunks = new long[0];

        public LoadChunks() {}

        public LoadChunks(boolean remove, boolean with3d, boolean savedOnly, int seq, long[] chunks) {
            this.remove = remove;
            this.with3d = with3d;
            this.savedOnly = savedOnly;
            this.seq = seq;
            this.chunks = chunks;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            remove = buf.readBoolean();
            with3d = buf.readBoolean();
            savedOnly = buf.readBoolean();
            seq = buf.readInt();
            int count = Math.min(MAX, buf.readInt());
            chunks = new long[count];
            for (int i = 0; i < count; i++) {
                chunks[i] = buf.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(remove);
            buf.writeBoolean(with3d);
            buf.writeBoolean(savedOnly);
            buf.writeInt(seq);
            buf.writeInt(chunks.length);
            for (long chunk : chunks) {
                buf.writeLong(chunk);
            }
        }
    }

    /** The region loading view asks which chunks of these regions (x, z pairs) are saved in the world. */
    public static final class SavedRequest implements IMessage {

        /** Regions per message. */
        public static final int MAX = 64;

        public int[] regions = new int[0];

        public SavedRequest() {}

        public SavedRequest(int[] regions) {
            this.regions = regions;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int count = Math.min(MAX, buf.readInt());
            regions = new int[count * 2];
            for (int i = 0; i < regions.length; i++) {
                regions[i] = buf.readInt();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(regions.length / 2);
            for (int value : regions) {
                buf.writeInt(value);
            }
        }
    }

    /** Which chunks of a region are saved in the world (or loaded): bit lz * 32 + lx. */
    public static final class SavedChunks implements IMessage {

        public int dimension, regionX, regionZ;
        public long[] bits = new long[16];

        @Override
        public void fromBytes(ByteBuf buf) {
            dimension = buf.readInt();
            regionX = buf.readInt();
            regionZ = buf.readInt();
            for (int i = 0; i < 16; i++) {
                bits[i] = buf.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(dimension);
            buf.writeInt(regionX);
            buf.writeInt(regionZ);
            for (int i = 0; i < 16; i++) {
                buf.writeLong(bits[i]);
            }
        }
    }

    /** Whether the player may load chunks from the world map (an operator, like the commands). */
    public static final class LoadAllowed implements IMessage {

        public boolean allowed;

        public LoadAllowed() {}

        public LoadAllowed(boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            allowed = buf.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(allowed);
        }
    }

    /**
     * The player's area loading ended: finished, stopped ({@code /wf chunkload stop}, the map's Stop button),
     * replaced by a new command, or none is running when the player joins. The chunks the client still shows queued
     * from picks up to {@link #seq} are no longer waited for (they would stay red forever).
     */
    public static final class LoadEnded implements IMessage {

        /** Picks up to this number ({@link LoadChunks#seq}) ended; {@link Integer#MAX_VALUE} for all of them. */
        public int seq;
        /** The area was loaded to the end (not stopped). */
        public boolean finished;

        public LoadEnded() {}

        public LoadEnded(int seq, boolean finished) {
            this.seq = seq;
            this.finished = finished;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            seq = buf.readInt();
            finished = buf.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(seq);
            buf.writeBoolean(finished);
        }
    }

    /**
     * Waypoints shared with the team. From a client: its own ones, new or changed ({@link #puts}) and no longer shared
     * ({@link #ids}). From the server: the same of the teammates. With {@link #full}, {@link #ids} are instead all
     * that are shared (by this player, or by the teammates): the receiver drops the others it knew.
     */
    public static final class Waypoints implements IMessage {

        /** Waypoints per message: client packets are limited to 32 KB. */
        public static final int MAX_PUTS = 8;
        private static final int MAX_IDS = 8192;

        public boolean full;
        public final List<UUID> ids = new ArrayList<>();
        public final List<SharedWaypoint> puts = new ArrayList<>();

        @Override
        public void fromBytes(ByteBuf buf) {
            try (ByteBufInputStream in = new ByteBufInputStream(buf)) {
                full = in.readBoolean();
                int count = Math.min(MAX_IDS, in.readInt());
                for (int i = 0; i < count; i++) {
                    ids.add(new UUID(in.readLong(), in.readLong()));
                }
                count = Math.min(MAX_PUTS, in.readInt());
                for (int i = 0; i < count; i++) {
                    puts.add(SharedWaypoint.read(in));
                }
            } catch (IOException | RuntimeException e) {
                WayFarMap.LOG.warn("Bad team waypoints message", e);
                full = false;
                ids.clear();
                puts.clear();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            try (ByteBufOutputStream out = new ByteBufOutputStream(buf)) {
                out.writeBoolean(full);
                out.writeInt(ids.size());
                for (UUID id : ids) {
                    out.writeLong(id.getMostSignificantBits());
                    out.writeLong(id.getLeastSignificantBits());
                }
                out.writeInt(puts.size());
                for (SharedWaypoint waypoint : puts) {
                    waypoint.write(out);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // Handlers run on the network thread; both sides only queue the message for their own thread.

    public static final class WaypointsToServer implements IMessageHandler<Waypoints, IMessage> {

        @Override
        public IMessage onMessage(Waypoints message, MessageContext context) {
            TeamMapServer.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class WaypointsToClient implements IMessageHandler<Waypoints, IMessage> {

        @Override
        public IMessage onMessage(Waypoints message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }

    public static final class LoadBatchToClient implements IMessageHandler<LoadBatch, IMessage> {

        @Override
        public IMessage onMessage(LoadBatch message, MessageContext context) {
            WayFarMap.proxy.receiveChunkLoad(message);
            return null;
        }
    }

    public static final class LoadDoneToServer implements IMessageHandler<LoadDone, IMessage> {

        @Override
        public IMessage onMessage(LoadDone message, MessageContext context) {
            ChunkLoadServer.INSTANCE.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class LoadChunksToServer implements IMessageHandler<LoadChunks, IMessage> {

        @Override
        public IMessage onMessage(LoadChunks message, MessageContext context) {
            ChunkLoadServer.INSTANCE.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class SavedRequestToServer implements IMessageHandler<SavedRequest, IMessage> {

        @Override
        public IMessage onMessage(SavedRequest message, MessageContext context) {
            ChunkLoadServer.INSTANCE.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class SavedChunksToClient implements IMessageHandler<SavedChunks, IMessage> {

        @Override
        public IMessage onMessage(SavedChunks message, MessageContext context) {
            WayFarMap.proxy.receiveChunkLoad(message);
            return null;
        }
    }

    public static final class LoadEndedToClient implements IMessageHandler<LoadEnded, IMessage> {

        @Override
        public IMessage onMessage(LoadEnded message, MessageContext context) {
            WayFarMap.proxy.receiveChunkLoad(message);
            return null;
        }
    }

    public static final class LoadAllowedToClient implements IMessageHandler<LoadAllowed, IMessage> {

        @Override
        public IMessage onMessage(LoadAllowed message, MessageContext context) {
            WayFarMap.proxy.receiveChunkLoad(message);
            return null;
        }
    }

    public static final class HelloToServer implements IMessageHandler<Hello, IMessage> {

        @Override
        public IMessage onMessage(Hello message, MessageContext context) {
            TeamMapServer.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    public static final class ChunksToServer implements IMessageHandler<Chunks, IMessage> {

        @Override
        public IMessage onMessage(Chunks message, MessageContext context) {
            TeamMapServer.receive(context.getServerHandler().playerEntity, message);
            return null;
        }
    }

    /** The client proxy passes these to the client's team map (the dedicated server never gets them). */
    public static final class HelloToClient implements IMessageHandler<Hello, IMessage> {

        @Override
        public IMessage onMessage(Hello message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }

    public static final class TeammatesToClient implements IMessageHandler<Teammates, IMessage> {

        @Override
        public IMessage onMessage(Teammates message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }

    public static final class ChunksToClient implements IMessageHandler<Chunks, IMessage> {

        @Override
        public IMessage onMessage(Chunks message, MessageContext context) {
            WayFarMap.proxy.receiveTeamMap(message);
            return null;
        }
    }
}
