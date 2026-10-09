package WayFarMap.client.waypoint;

/** A named set of waypoints that can be shown or hidden together. */
public class WaypointGroup {

    public String name;
    public boolean visible = true;
    /** Waypoints made in this group are shared with the team at once. */
    public boolean shareNew;
    /** Teammates' waypoints coming into this group: shown as the settings say, always, or never. */
    public int incoming;

    public static final int INCOMING_DEFAULT = 0, INCOMING_SHOWN = 1, INCOMING_HIDDEN = 2;

    public WaypointGroup() {}

    public WaypointGroup(String name) {
        this.name = name;
    }
}
