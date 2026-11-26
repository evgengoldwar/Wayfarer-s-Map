package WayFarMap.share;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;

/**
 * GregTech's Drone Centre, by reflection only (the class was renamed in 2024, and GregTech may be missing). A downlink
 * module connects to a working centre within its range ({@code getRange()}, by the drone level: 128, 512 or 4096
 * blocks in today's GregTech, 32, 128 or 512 in the first one), measured in 3D from the controller's corner. A T4
 * drone with a key reaches any distance and dimension. There is no owner: any downlink in range connects.
 * <p>
 * The client learns the drone level only from GregTech 5.09.52.615 on (it was added for the drone's model over the
 * controller); before that only the server knows the range.
 */
public final class DroneCentres {

    public static final int KIND = 2;

    private static final String[] CLASSES = { "gregtech.common.tileentities.machines.multi.drone.MTEDroneCentre",
        "gregtech.common.tileentities.machines.multi.drone.GT_MetaTileEntity_DroneCentre" };

    private static volatile Boolean available;
    private static Class<?> centre;
    private static Method getRange;
    private static Field droneLevel;
    private static boolean clientKnowsLevel;
    private static final Map<Class<?>, Optional<Method>> IS_ACTIVE = new ConcurrentHashMap<>();

    private DroneCentres() {}

    public static boolean isAvailable() {
        if (available == null) {
            lookUp();
        }
        return available;
    }

    private static synchronized void lookUp() {
        if (available != null) {
            return;
        }
        boolean found = false;
        if (Loader.isModLoaded("gregtech")) {
            for (String name : CLASSES) {
                try {
                    centre = Class.forName(name, false, DroneCentres.class.getClassLoader());
                    getRange = centre.getMethod("getRange");
                    droneLevel = centre.getDeclaredField("droneLevel");
                    droneLevel.setAccessible(true);
                    clientKnowsLevel = declares(centre, "readFromStream") || declares(centre, "onDescriptionPacket");
                    found = true;
                    break;
                } catch (Throwable ignored) {
                    // Not this name.
                }
            }
            if (!found) {
                WayFarMap.LOG.info("GregTech's Drone Centre is not available");
            }
        }
        available = found;
    }

    private static boolean declares(Class<?> type, String method) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName()
                .equals(method)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this GregTech sends the drone level to the client, so the client knows the range. */
    public static boolean clientKnowsLevel() {
        return isAvailable() && clientKnowsLevel;
    }

    /** The drone centre in the tile entity (its meta tile entity), or null. */
    public static Object centre(TileEntity tile) {
        return tile == null || !isAvailable() ? null : centreOf(GtMeta.of(tile));
    }

    /** The machine of a GregTech block ({@link GtMeta#of}) if it is a drone centre, or null. */
    public static Object centreOf(Object machine) {
        return machine != null && isAvailable() && centre.isInstance(machine) ? machine : null;
    }

    /** The drone level, 0 without a drone (or on a client that isn't told). */
    public static int level(Object centre) {
        try {
            return droneLevel.getInt(centre);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The range in blocks, 0 without a drone. */
    public static int range(Object centre) {
        try {
            return ((Number) getRange.invoke(centre)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Running (GregTech's active flag, which the client knows). */
    public static boolean isActive(TileEntity tile) {
        try {
            Optional<Method> m = IS_ACTIVE.computeIfAbsent(tile.getClass(), t -> method(t, "isActive"));
            return m.isPresent() && Boolean.TRUE.equals(
                m.get()
                    .invoke(tile));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Optional<Method> method(Class<?> type, String name) {
        try {
            return Optional.of(type.getMethod(name));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
