package WayFarMap.share;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/** A waypoint shared with the team, as it goes over the network and into the team's file on the server. */
public final class SharedWaypoint {

    /** Longer icon NBT is left out (the icon is then drawn without it), so a message always fits a packet. */
    public static final int MAX_NBT = 3000;

    public UUID id;
    /** Set by the server, never taken from the client. */
    public UUID owner;
    public String ownerName = "";
    public String name = "";
    public int x, y, z, dimension;
    /** RGB, or -1 for no outline. */
    public int outlineColor = -1;
    public String iconItem = "";
    public int iconMeta;
    public String iconNbt = "";
    public String symbol = "";
    public String group = "";
    public boolean beam;

    public void write(DataOutput out) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeBoolean(owner != null);
        if (owner != null) {
            out.writeLong(owner.getMostSignificantBits());
            out.writeLong(owner.getLeastSignificantBits());
        }
        out.writeUTF(ownerName);
        out.writeUTF(name);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeInt(dimension);
        out.writeInt(outlineColor);
        out.writeUTF(iconItem);
        out.writeInt(iconMeta);
        out.writeUTF(iconNbt);
        out.writeUTF(symbol);
        out.writeUTF(group);
        out.writeBoolean(beam);
    }

    public static SharedWaypoint read(DataInput in) throws IOException {
        SharedWaypoint waypoint = new SharedWaypoint();
        waypoint.id = new UUID(in.readLong(), in.readLong());
        if (in.readBoolean()) {
            waypoint.owner = new UUID(in.readLong(), in.readLong());
        }
        waypoint.ownerName = cut(in.readUTF(), 64);
        waypoint.name = cut(in.readUTF(), 64);
        waypoint.x = in.readInt();
        waypoint.y = in.readInt();
        waypoint.z = in.readInt();
        waypoint.dimension = in.readInt();
        waypoint.outlineColor = in.readInt();
        waypoint.iconItem = cut(in.readUTF(), 128);
        waypoint.iconMeta = in.readInt();
        waypoint.iconNbt = in.readUTF();
        if (waypoint.iconNbt.length() > MAX_NBT) {
            waypoint.iconNbt = "";
        }
        waypoint.symbol = cut(in.readUTF(), 32);
        waypoint.group = cut(in.readUTF(), 32);
        waypoint.beam = in.readBoolean();
        return waypoint;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Whether teammates would see no difference between the two. */
    public boolean sameAs(SharedWaypoint other) {
        return x == other.x && y == other.y
            && z == other.z
            && dimension == other.dimension
            && outlineColor == other.outlineColor
            && iconMeta == other.iconMeta
            && beam == other.beam
            && name.equals(other.name)
            && iconItem.equals(other.iconItem)
            && iconNbt.equals(other.iconNbt)
            && symbol.equals(other.symbol)
            && group.equals(other.group)
            && ownerName.equals(other.ownerName);
    }
}
