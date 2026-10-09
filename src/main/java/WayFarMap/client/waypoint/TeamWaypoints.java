package WayFarMap.client.waypoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import WayFarMap.share.ShareNetwork;
import WayFarMap.share.SharedWaypoint;

/**
 * Client side of the team's waypoints (on a server with this mod and ServerUtilities, in a team). The player's own
 * shared waypoints live in {@link WaypointManager} like any other and are only told to the server: all of them when
 * the server says which team the player is in, then whatever changes on each save. The teammates' ones come from the
 * server and are kept in the same list, marked with their owner: they can be shown or hidden here but not changed,
 * and they go when their owner stops sharing them.
 */
public final class TeamWaypoints {

    public static final TeamWaypoints INSTANCE = new TeamWaypoints();

    private boolean available;
    /** What the server was last told of each shared waypoint, by its id. */
    private final Map<String, SharedWaypoint> sent = new HashMap<>();
    /** What came from the server before the world's waypoints were loaded. */
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    private TeamWaypoints() {}

    /** Whether waypoints can be shared now: the server takes them and the player is in a team. */
    public boolean isAvailable() {
        return available;
    }

    /** Left the server. */
    public void reset() {
        available = false;
        sent.clear();
        pending.clear();
    }

    /** The server said which team the player is in (empty for none), and whether it knows team waypoints. */
    public void onTeam(boolean supported, String team) {
        pending.add(() -> {
            available = supported && !team.isEmpty();
            sent.clear();
            if (available) {
                ShareNetwork.Waypoints all = new ShareNetwork.Waypoints();
                all.full = true;
                for (SharedWaypoint waypoint : shared()) {
                    all.ids.add(waypoint.id);
                }
                ShareNetwork.sendToServer(all);
                localChanged();
            } else if (supported) {
                // Not in a team any more: nobody's waypoints to see.
                WaypointManager.INSTANCE.applyTeam(new ArrayList<>(), id -> true);
            }
        });
    }

    public void receive(ShareNetwork.Waypoints message) {
        pending.add(() -> {
            Set<String> ids = new HashSet<>();
            for (UUID id : message.ids) {
                ids.add(id.toString());
            }
            List<Waypoint> puts = new ArrayList<>();
            for (SharedWaypoint shared : message.puts) {
                if (shared.owner != null) {
                    puts.add(fromShared(shared));
                }
            }
            WaypointManager.INSTANCE.applyTeam(puts, id -> message.full != ids.contains(id));
        });
    }

    /** Every client tick while in a world. */
    public void tick() {
        while (!pending.isEmpty() && WaypointManager.INSTANCE.isLoaded()) {
            pending.poll()
                .run();
        }
    }

    /** The waypoints were saved: tells the server what of the shared ones is new, changed or gone. */
    void localChanged() {
        if (!available) {
            return;
        }
        List<SharedWaypoint> changed = new ArrayList<>();
        Set<String> gone = new HashSet<>(sent.keySet());
        for (SharedWaypoint waypoint : shared()) {
            String id = waypoint.id.toString();
            gone.remove(id);
            SharedWaypoint before = sent.put(id, waypoint);
            if (before == null || !before.sameAs(waypoint)) {
                changed.add(waypoint);
            }
        }
        if (!gone.isEmpty()) {
            ShareNetwork.Waypoints message = new ShareNetwork.Waypoints();
            for (String id : gone) {
                sent.remove(id);
                message.ids.add(UUID.fromString(id));
            }
            ShareNetwork.sendToServer(message);
        }
        for (int i = 0; i < changed.size(); i += ShareNetwork.Waypoints.MAX_PUTS) {
            ShareNetwork.Waypoints message = new ShareNetwork.Waypoints();
            message.puts.addAll(changed.subList(i, Math.min(changed.size(), i + ShareNetwork.Waypoints.MAX_PUTS)));
            ShareNetwork.sendToServer(message);
        }
    }

    private static List<SharedWaypoint> shared() {
        List<SharedWaypoint> result = new ArrayList<>();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            if (!waypoint.isShared() || waypoint.death) {
                continue;
            }
            SharedWaypoint shared = new SharedWaypoint();
            try {
                shared.id = UUID.fromString(waypoint.shareId);
            } catch (IllegalArgumentException e) {
                continue;
            }
            shared.name = cut(waypoint.name, 64);
            shared.x = waypoint.x;
            shared.y = waypoint.y;
            shared.z = waypoint.z;
            shared.dimension = waypoint.dimension;
            shared.outlineColor = waypoint.outlineColor == null ? -1 : waypoint.outlineColor & 0xFFFFFF;
            shared.iconItem = waypoint.iconItem == null ? "" : waypoint.iconItem;
            shared.iconMeta = waypoint.iconMeta;
            shared.iconNbt = waypoint.iconNbt == null || waypoint.iconNbt.length() > SharedWaypoint.MAX_NBT ? ""
                : waypoint.iconNbt;
            shared.symbol = waypoint.symbol == null ? "" : waypoint.symbol;
            shared.group = waypoint.group == null ? "" : cut(waypoint.group, 32);
            shared.beam = waypoint.beam;
            result.add(shared);
        }
        return result;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static Waypoint fromShared(SharedWaypoint shared) {
        Waypoint waypoint = new Waypoint(shared.name, shared.x, shared.y, shared.z, shared.dimension);
        waypoint.outlineColor = shared.outlineColor < 0 ? null : (Integer) shared.outlineColor;
        waypoint.iconItem = shared.iconItem.isEmpty() ? null : shared.iconItem;
        waypoint.iconMeta = shared.iconMeta;
        waypoint.iconNbt = shared.iconNbt.isEmpty() ? null : shared.iconNbt;
        waypoint.symbol = shared.symbol.isEmpty() ? null : shared.symbol;
        waypoint.group = shared.group.isEmpty() ? null : shared.group;
        waypoint.beam = shared.beam;
        waypoint.shareId = shared.id.toString();
        waypoint.owner = shared.owner.toString();
        waypoint.ownerName = shared.ownerName;
        return waypoint;
    }
}
