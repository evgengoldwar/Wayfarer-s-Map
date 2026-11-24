package WayFarMap.share;

import io.netty.buffer.ByteBuf;

/**
 * A machine with a coverage, for the map: a wireless charger ({@link Chargers#KIND_GT}, {@link Chargers#KIND_ENDERIO}),
 * a drone centre ({@link DroneCentres#KIND}), a mob guard ({@link MobGuards}) or a flight zone ({@link FlightZones}).
 * Where it is, its tier (a GregTech charger's voltage tier, a drone
 * centre's drone level), a GregTech charger's mode ({@link Chargers#MODE_UNKNOWN} when not known), its range in
 * blocks and whether it works.
 */
public final class MachinePoint {

    public final int kind, dimension, x, y, z, tier, mode;
    public final float range;
    public final boolean active;

    public MachinePoint(int kind, int dimension, int x, int y, int z, int tier, int mode, float range, boolean active) {
        this.kind = kind;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tier = tier;
        this.mode = mode;
        this.range = range;
        this.active = active;
    }

    static MachinePoint read(ByteBuf buf) {
        return new MachinePoint(
            buf.readByte(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readByte(),
            buf.readByte(),
            buf.readFloat(),
            buf.readBoolean());
    }

    void write(ByteBuf buf) {
        buf.writeByte(kind);
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(tier);
        buf.writeByte(mode);
        buf.writeFloat(range);
        buf.writeBoolean(active);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MachinePoint)) {
            return false;
        }
        MachinePoint p = (MachinePoint) o;
        return kind == p.kind && dimension == p.dimension
            && x == p.x
            && y == p.y
            && z == p.z
            && tier == p.tier
            && mode == p.mode
            && range == p.range
            && active == p.active;
    }

    @Override
    public int hashCode() {
        return (((kind * 31 + dimension) * 31 + x) * 31 + y) * 31 + z;
    }
}
