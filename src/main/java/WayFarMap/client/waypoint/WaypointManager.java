package WayFarMap.client.waypoint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import WayFarMap.Config;
import WayFarMap.WayFarMap;

/**
 * Waypoints and groups of the current world or server, saved to {@code waypoints.json}. Teammates' shared waypoints
 * are kept among them (see {@link TeamWaypoints}), each marked with its owner; every save tells the server what
 * changed in the player's own shared ones.
 */
public class WaypointManager {

    public static final WaypointManager INSTANCE = new WaypointManager();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    /** On-disk format. */
    private static class Data {

        List<WaypointGroup> groups = new ArrayList<>();
        List<Waypoint> waypoints = new ArrayList<>();
        boolean ungroupedVisible = true;
    }

    private File file;
    private Data data = new Data();
    /** Goes up when teammates' waypoints change, so an open list knows to show them anew. */
    private int teamRevision;

    private WaypointManager() {}

    public boolean isLoaded() {
        return file != null;
    }

    public void load(File worldDirectory) {
        File newFile = new File(worldDirectory, "waypoints.json");
        if (newFile.equals(file)) {
            return;
        }
        file = newFile;
        data = new Data();
        if (!file.isFile()) {
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded != null) {
                data = loaded;
                if (data.groups == null) data.groups = new ArrayList<>();
                if (data.waypoints == null) data.waypoints = new ArrayList<>();
                data.groups.removeIf(g -> g == null || g.name == null);
                data.waypoints.removeIf(w -> w == null);
                for (Waypoint waypoint : data.waypoints) {
                    if (waypoint.name == null) waypoint.name = "";
                    if (waypoint.group != null && getGroup(waypoint.group) == null) waypoint.group = null;
                }
            }
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not read waypoints from " + file, e);
        }
    }

    public void unload() {
        file = null;
        data = new Data();
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            if (file.exists() && !file.delete()) {
                WayFarMap.LOG.warn("Could not replace " + file);
            }
            if (!tmp.renameTo(file)) {
                WayFarMap.LOG.warn("Could not rename " + tmp + " to " + file);
            }
        } catch (Exception e) {
            WayFarMap.LOG.warn("Could not save waypoints to " + file, e);
        }
        TeamWaypoints.INSTANCE.localChanged();
    }

    public List<Waypoint> getWaypoints() {
        return Collections.unmodifiableList(data.waypoints);
    }

    public List<WaypointGroup> getGroups() {
        return Collections.unmodifiableList(data.groups);
    }

    public WaypointGroup getGroup(String name) {
        if (name == null) {
            return null;
        }
        for (WaypointGroup group : data.groups) {
            if (group.name.equals(name)) {
                return group;
            }
        }
        return null;
    }

    public List<Waypoint> getWaypointsInGroup(String group) {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : data.waypoints) {
            if (group == null ? waypoint.group == null : group.equals(waypoint.group)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    /** Whether the waypoint should be drawn: it and its group are both enabled. */
    public boolean isVisible(Waypoint waypoint) {
        return waypoint.enabled && isGroupVisible(waypoint);
    }

    /** Whether the waypoint's group (or the ungrouped ones) is shown. */
    private boolean isGroupVisible(Waypoint waypoint) {
        if (waypoint.group == null) {
            return data.ungroupedVisible;
        }
        WaypointGroup group = getGroup(waypoint.group);
        return group == null || group.visible;
    }

    /** Waypoints to draw in the given dimension. */
    public List<Waypoint> getVisibleWaypoints(int dimension) {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : data.waypoints) {
            if (waypoint.dimension == dimension && isVisible(waypoint)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    /**
     * Waypoints for the world map in the given dimension: disabled ones too (the map draws them faded, so they can
     * be turned on again from it), but not those of hidden groups.
     */
    public List<Waypoint> getMapWaypoints(int dimension) {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint waypoint : data.waypoints) {
            if (waypoint.dimension == dimension && isGroupVisible(waypoint)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    public boolean isUngroupedVisible() {
        return data.ungroupedVisible;
    }

    public void setUngroupedVisible(boolean visible) {
        data.ungroupedVisible = visible;
        save();
    }

    public void setGroupVisible(WaypointGroup group, boolean visible) {
        group.visible = visible;
        save();
    }

    public void addWaypoint(Waypoint waypoint) {
        data.waypoints.add(waypoint);
        save();
    }

    /**
     * Adds a death marker to the death group (created again if it was deleted), moves all death markers into it and
     * removes the oldest ones beyond {@code keep}.
     */
    public void addDeathWaypoint(Waypoint waypoint, String groupName, int keep) {
        String group = groupName.trim();
        if (getGroup(group) == null) {
            data.groups.add(new WaypointGroup(group));
        }
        waypoint.death = true;
        data.waypoints.add(waypoint);
        int deaths = 0;
        for (int i = data.waypoints.size() - 1; i >= 0; i--) {
            Waypoint other = data.waypoints.get(i);
            if (!other.death) {
                continue;
            }
            if (++deaths > keep) {
                data.waypoints.remove(i);
            } else {
                other.group = group;
            }
        }
        save();
    }

    /** Teammates' waypoints stay: only their owners remove them. */
    public void removeWaypoint(Waypoint waypoint) {
        if (!waypoint.isForeign()) {
            data.waypoints.remove(waypoint);
            save();
        }
    }

    public void removeWaypoints(Collection<Waypoint> waypoints) {
        data.waypoints.removeIf(waypoint -> !waypoint.isForeign() && waypoints.contains(waypoint));
        save();
    }

    // ---- Shared with the team ----

    /** Shares the player's own waypoint with the team, or stops sharing it; call {@link #waypointChanged} after. */
    public static void setShared(Waypoint waypoint, boolean shared) {
        if (shared && waypoint.shareId == null) {
            waypoint.shareId = UUID.randomUUID()
                .toString();
        } else if (!shared) {
            waypoint.shareId = null;
        }
    }

    public void setShareNew(WaypointGroup group, boolean shareNew) {
        group.shareNew = shareNew;
        save();
    }

    public void setIncoming(WaypointGroup group, int incoming) {
        group.incoming = incoming;
        save();
    }

    /** Shares all of the player's own waypoints in the group; returns how many were not shared before. */
    public int shareGroup(WaypointGroup group) {
        int count = 0;
        for (Waypoint waypoint : data.waypoints) {
            if (group.name.equals(waypoint.group) && !waypoint.isForeign() && !waypoint.death && !waypoint.isShared()) {
                setShared(waypoint, true);
                count++;
            }
        }
        if (count > 0) {
            save();
        }
        return count;
    }

    /** Whether a teammate's new waypoint coming into the group (null for none) is shown at once. */
    private boolean showsIncoming(String groupName) {
        WaypointGroup group = getGroup(groupName);
        int incoming = group == null ? WaypointGroup.INCOMING_DEFAULT : group.incoming;
        return incoming == WaypointGroup.INCOMING_DEFAULT ? Config.teamWaypointsShown
            : incoming == WaypointGroup.INCOMING_SHOWN;
    }

    /**
     * Teammates' waypoints as the server tells them: those whose id {@code removed} takes are dropped, and
     * {@code puts} are added or replace what was known of them. A new one goes into its owner's group (made if
     * missing), shown or not as that group and the settings say; a known one stays as shown, and in the group the
     * player moved it to unless its owner moved it too.
     */
    public void applyTeam(List<Waypoint> puts, Predicate<String> removed) {
        boolean changed = data.waypoints.removeIf(waypoint -> waypoint.isForeign() && removed.test(waypoint.shareId));
        for (Waypoint put : puts) {
            Waypoint existing = null;
            for (Waypoint waypoint : data.waypoints) {
                if (waypoint.isForeign() && put.shareId.equals(waypoint.shareId)) {
                    existing = waypoint;
                    break;
                }
            }
            String group = put.group;
            put.ownerGroup = group;
            if (existing != null && Objects.equals(group, existing.ownerGroup)) {
                put.group = existing.group;
            } else if (group != null && getGroup(group) == null) {
                data.groups.add(new WaypointGroup(group));
            }
            if (existing == null) {
                put.enabled = showsIncoming(group);
                data.waypoints.add(put);
            } else {
                put.enabled = existing.enabled;
                existing.copyFrom(put);
            }
            changed = true;
        }
        if (changed) {
            teamRevision++;
            save();
        }
    }

    /** The teammate's waypoint this one is a copy of, or null if it is no copy or that one is no longer shared. */
    public Waypoint getOriginal(Waypoint copy) {
        if (copy.copyOf == null || copy.isForeign()) {
            return null;
        }
        for (Waypoint waypoint : data.waypoints) {
            if (waypoint.isForeign() && copy.copyOf.equals(waypoint.shareId)) {
                return waypoint;
            }
        }
        return null;
    }

    public int getTeamRevision() {
        return teamRevision;
    }

    /**
     * Keeps a teammate's waypoint as the player's own, to change as they like. The teammate's one is hidden, not to
     * show twice, and the copy remembers which one it was made of. Returns the copy.
     */
    public Waypoint saveAsOwn(Waypoint foreign) {
        Waypoint copy = foreign.copy();
        copy.makeOwn();
        copy.copyOf = foreign.shareId;
        copy.copyOfOwner = foreign.ownerName;
        copy.enabled = true;
        foreign.enabled = false;
        data.waypoints.add(copy);
        save();
        return copy;
    }

    /** Moves the waypoint into the named group, or out of any group for null. */
    public void moveToGroup(Waypoint waypoint, String group) {
        waypoint.group = group != null && getGroup(group) != null ? group : null;
        save();
    }

    /** Call after changing a waypoint's fields. */
    public void waypointChanged() {
        save();
    }

    /** @return the new group, or the existing one with that name. */
    public WaypointGroup createGroup(String name) {
        name = name.trim();
        WaypointGroup existing = getGroup(name);
        if (existing != null) {
            return existing;
        }
        WaypointGroup group = new WaypointGroup(name);
        data.groups.add(group);
        save();
        return group;
    }

    /** @return false if another group already has the new name */
    public boolean renameGroup(WaypointGroup group, String newName) {
        newName = newName.trim();
        if (newName.isEmpty() || (getGroup(newName) != null && getGroup(newName) != group)) {
            return false;
        }
        for (Waypoint waypoint : data.waypoints) {
            if (group.name.equals(waypoint.group)) {
                waypoint.group = newName;
            }
        }
        group.name = newName;
        save();
        return true;
    }

    /** Deletes the group; its waypoints are kept and moved to "no group". */
    public void removeGroup(WaypointGroup group) {
        for (Waypoint waypoint : data.waypoints) {
            if (group.name.equals(waypoint.group)) {
                waypoint.group = null;
            }
        }
        data.groups.remove(group);
        save();
    }

    public void moveGroup(WaypointGroup group, int offset) {
        int index = data.groups.indexOf(group);
        int target = index + offset;
        if (index < 0 || target < 0 || target >= data.groups.size()) {
            return;
        }
        Collections.swap(data.groups, index, target);
        save();
    }
}
