package WayFarMap.share;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.entity.player.EntityPlayerMP;

import WayFarMap.WayFarMap;

/**
 * Waypoints the members of a team share with it, kept on the server so teammates who join later get them too. A
 * waypoint belongs to the player who shared it: that player's client is where it lives and is edited, and after
 * joining it tells which ones it shares, so the server only stores them and passes the changes on. The others can't
 * change it.
 * <p>
 * Stored in {@code <world>/wayfarmap/teams/<team>/waypoints.bin}. Server thread only; driven by
 * {@link TeamMapServer}.
 */
final class TeamWaypointServer {

    private static final int FILE_MAGIC = 0x57465750;
    private static final int MAX_PER_PLAYER = 512;

    private static final class Store {

        final File file;
        final Map<UUID, SharedWaypoint> waypoints = new LinkedHashMap<>();
        boolean dirty;

        Store(File file) {
            this.file = file;
        }
    }

    private final TeamMapServer server;
    private final Map<String, Store> stores = new HashMap<>();

    TeamWaypointServer(TeamMapServer server) {
        this.server = server;
    }

    /** Everything the teammates share, for a player who joined the server or the team. */
    void sendAll(EntityPlayerMP player, String teamId) {
        UUID id = player.getUniqueID();
        ShareNetwork.Waypoints first = new ShareNetwork.Waypoints();
        first.full = true;
        List<SharedWaypoint> theirs = new ArrayList<>();
        for (SharedWaypoint waypoint : store(teamId).waypoints.values()) {
            if (!id.equals(waypoint.owner)) {
                first.ids.add(waypoint.id);
                theirs.add(waypoint);
            }
        }
        ShareNetwork.sendTo(first, player);
        for (ShareNetwork.Waypoints part : parts(new ArrayList<>(), theirs)) {
            ShareNetwork.sendTo(part, player);
        }
    }

    /** A player's own shared waypoints changed. */
    void receive(EntityPlayerMP player, String teamId, ShareNetwork.Waypoints message) {
        UUID owner = player.getUniqueID();
        Store store = store(teamId);
        List<UUID> removed = new ArrayList<>();
        List<SharedWaypoint> changed = new ArrayList<>();
        if (message.full) {
            Set<UUID> keep = new HashSet<>(message.ids);
            Iterator<SharedWaypoint> it = store.waypoints.values()
                .iterator();
            while (it.hasNext()) {
                SharedWaypoint waypoint = it.next();
                if (owner.equals(waypoint.owner) && !keep.contains(waypoint.id)) {
                    it.remove();
                    removed.add(waypoint.id);
                }
            }
        } else {
            for (UUID id : message.ids) {
                SharedWaypoint waypoint = store.waypoints.get(id);
                if (waypoint != null && owner.equals(waypoint.owner)) {
                    store.waypoints.remove(id);
                    removed.add(id);
                }
            }
        }
        int owned = 0;
        for (SharedWaypoint waypoint : store.waypoints.values()) {
            if (owner.equals(waypoint.owner)) {
                owned++;
            }
        }
        for (SharedWaypoint put : message.puts) {
            SharedWaypoint existing = store.waypoints.get(put.id);
            if (existing != null ? !owner.equals(existing.owner) : owned >= MAX_PER_PLAYER) {
                continue;
            }
            put.owner = owner;
            put.ownerName = player.getCommandSenderName();
            if (existing != null && existing.sameAs(put)) {
                continue;
            }
            if (existing == null) {
                owned++;
            }
            store.waypoints.put(put.id, put);
            changed.add(put);
        }
        if (removed.isEmpty() && changed.isEmpty()) {
            return;
        }
        store.dirty = true;
        for (ShareNetwork.Waypoints part : parts(removed, changed)) {
            server.sendToTeam(teamId, owner, part);
        }
    }

    /** The player is no longer in the team: what they shared with it is gone for the others. */
    void removeOwner(String teamId, UUID owner) {
        Store store = store(teamId);
        List<UUID> removed = new ArrayList<>();
        Iterator<SharedWaypoint> it = store.waypoints.values()
            .iterator();
        while (it.hasNext()) {
            SharedWaypoint waypoint = it.next();
            if (owner.equals(waypoint.owner)) {
                it.remove();
                removed.add(waypoint.id);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        store.dirty = true;
        for (ShareNetwork.Waypoints part : parts(removed, new ArrayList<>())) {
            server.sendToTeam(teamId, owner, part);
        }
    }

    private static List<ShareNetwork.Waypoints> parts(List<UUID> removed, List<SharedWaypoint> changed) {
        List<ShareNetwork.Waypoints> parts = new ArrayList<>();
        if (!removed.isEmpty()) {
            ShareNetwork.Waypoints part = new ShareNetwork.Waypoints();
            part.ids.addAll(removed);
            parts.add(part);
        }
        for (int i = 0; i < changed.size(); i += ShareNetwork.Waypoints.MAX_PUTS) {
            ShareNetwork.Waypoints part = new ShareNetwork.Waypoints();
            part.puts.addAll(changed.subList(i, Math.min(changed.size(), i + ShareNetwork.Waypoints.MAX_PUTS)));
            parts.add(part);
        }
        return parts;
    }

    void save() {
        for (Store store : stores.values()) {
            if (store.dirty) {
                store.dirty = false;
                write(store);
            }
        }
    }

    void stop() {
        save();
        stores.clear();
    }

    private Store store(String teamId) {
        Store store = stores.get(teamId);
        if (store == null) {
            store = new Store(new File(server.teamDirectory(teamId), "waypoints.bin"));
            read(store);
            stores.put(teamId, store);
        }
        return store;
    }

    private static void read(Store store) {
        if (!store.file.isFile()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(store.file)))) {
            if (in.readInt() != FILE_MAGIC || in.readInt() != 1) {
                return;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                SharedWaypoint waypoint = SharedWaypoint.read(in);
                if (waypoint.owner != null) {
                    store.waypoints.put(waypoint.id, waypoint);
                }
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Team waypoints: could not read " + store.file, e);
        }
    }

    private static void write(Store store) {
        File tmp = new File(store.file.getPath() + ".tmp");
        try {
            store.file.getParentFile()
                .mkdirs();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(1);
                out.writeInt(store.waypoints.size());
                for (SharedWaypoint waypoint : store.waypoints.values()) {
                    waypoint.write(out);
                }
            }
            if (store.file.exists() && !store.file.delete() || !tmp.renameTo(store.file)) {
                throw new IOException("Could not replace " + store.file);
            }
        } catch (IOException e) {
            WayFarMap.LOG.warn("Team waypoints: could not save " + store.file, e);
            store.dirty = true;
        }
    }
}
