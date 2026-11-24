package WayFarMap.share;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

/**
 * The machine (meta tile entity) of a GregTech machine block, by reflection: one getter looked up per tile entity
 * class, so a tile entity of any other mod costs a map lookup. Called from the client and the integrated server
 * threads.
 */
public final class GtMeta {

    private static final Map<Class<?>, Optional<Method>> GETTERS = new ConcurrentHashMap<>();

    private GtMeta() {}

    /** The machine in the tile entity, or null (not a GregTech machine block, or its machine not there yet). */
    public static Object of(TileEntity tile) {
        if (tile == null) {
            return null;
        }
        try {
            Optional<Method> getter = GETTERS.computeIfAbsent(tile.getClass(), GtMeta::getter);
            return getter.isPresent() ? getter.get()
                .invoke(tile) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A GregTech machine block whose machine the client has not been told yet (it comes in a later packet than the
     * block): not a sign that a machine seen there is gone.
     */
    public static boolean isPending(TileEntity tile) {
        if (tile == null) {
            return false;
        }
        try {
            Optional<Method> getter = GETTERS.computeIfAbsent(tile.getClass(), GtMeta::getter);
            return getter.isPresent() && getter.get()
                .invoke(tile) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Optional<Method> getter(Class<?> type) {
        try {
            return Optional.of(type.getMethod("getMetaTileEntity"));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
