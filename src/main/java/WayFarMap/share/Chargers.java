package WayFarMap.share;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;

/**
 * Wireless chargers of GregTech (the former GT++ one) and EnderIO, reached by reflection only: either mod may be
 * missing or of another version, and then its chargers are simply not found. Used by both sides.
 * <p>
 * GregTech: the range is {@code V[tier] * 4} in the long range mode, {@code tier * 20} in the local one, and
 * {@code V[tier] * 2} (long) with {@code tier * 10} (local) in the mixed one, measured in 3D from the block's corner.
 * Only the server knows the mode; {@code tier * 10} is reached in every mode. A locked charger (the default) charges
 * its owner only.
 * <p>
 * EnderIO: one range for all, {@code wirelessChargerRange} of its config (not synced to the client, which reads its
 * own), measured in 3D between block coordinates; it works with energy stored and no redstone signal.
 * <p>
 * The client and the integrated server call this from their own threads, hence the synchronized lookups and the
 * concurrent caches.
 */
public final class Chargers {

    public static final int KIND_GT = 0, KIND_ENDERIO = 1;
    public static final int MODE_UNKNOWN = -1, MODE_LONG = 0, MODE_LOCAL = 1, MODE_MIXED = 2;

    private static final String GT_CHARGER = "gtPlusPlus.xmod.gregtech.common.tileentities.machines.basic.MTEWirelessCharger";
    private static final String ENDERIO_CHARGER = "crazypants.enderio.machine.wireless.TileWirelessCharger";

    private Chargers() {}

    // ---------------------------------------------------------------- GregTech

    private static volatile Boolean gt;
    private static Class<?> gtCharger;
    private static Field gtTier, gtMode, gtLocked;
    private static long[] voltages;

    public static boolean isGtAvailable() {
        if (gt == null) {
            lookUpGt();
        }
        return gt;
    }

    private static synchronized void lookUpGt() {
        if (gt == null) {
            gt = false;
            if (Loader.isModLoaded("gregtech")) {
                try {
                    gtCharger = Class.forName(GT_CHARGER, false, Chargers.class.getClassLoader());
                    gtTier = field(gtCharger, "mTier");
                    voltages = (long[]) Class.forName("gregtech.api.enums.GTValues")
                        .getField("V")
                        .get(null);
                    gtMode = optionalField(gtCharger, "mode");
                    gtLocked = optionalField(gtCharger, "locked");
                    gt = true;
                } catch (Throwable t) {
                    WayFarMap.LOG.info("GregTech wireless chargers are not available: {}", t.toString());
                }
            }
        }
    }

    /** The GregTech charger in the tile entity (its meta tile entity), or null. */
    public static Object gtCharger(TileEntity tile) {
        return tile == null || !isGtAvailable() ? null : gtChargerOf(GtMeta.of(tile));
    }

    /** The machine of a GregTech block ({@link GtMeta#of}) if it is a charger, or null. */
    public static Object gtChargerOf(Object machine) {
        return machine != null && isGtAvailable() && gtCharger.isInstance(machine) ? machine : null;
    }

    public static int gtTier(Object charger) {
        try {
            return ((Number) gtTier.get(charger)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Switched on (a soft mallet or a cover can turn machines off). */
    public static boolean gtAllowedToWork(TileEntity tile) {
        Object result = call(tile, "isAllowedToWork");
        return !(result instanceof Boolean) || (Boolean) result;
    }

    /** Server side: the mode, {@link #MODE_UNKNOWN} if this version keeps it elsewhere. */
    public static int gtMode(Object charger) {
        if (gtMode == null) {
            return MODE_UNKNOWN;
        }
        try {
            Object mode = gtMode.get(charger);
            return mode instanceof Enum ? ((Enum<?>) mode).ordinal() : MODE_UNKNOWN;
        } catch (Throwable t) {
            return MODE_UNKNOWN;
        }
    }

    /** Server side: whether the charger would charge the player (a locked or private one only its owner). */
    public static boolean gtChargesPlayer(TileEntity tile, Object charger, EntityPlayer player) {
        boolean locked = true;
        try {
            if (gtLocked != null) {
                locked = gtLocked.getBoolean(charger);
            }
        } catch (Throwable ignored) {
            // As by default: locked.
        }
        Object isPrivate = call(tile, "privateAccess");
        if (!locked && !Boolean.TRUE.equals(isPrivate)) {
            return true;
        }
        Object owner = call(tile, "getOwnerUuid");
        return !(owner instanceof UUID) || owner.equals(player.getUniqueID());
    }

    /** Server side: whether it has energy to give. */
    public static boolean gtHasEnergy(TileEntity tile) {
        Object stored = call(tile, "getStoredEU");
        return !(stored instanceof Number) || ((Number) stored).longValue() > 0;
    }

    /** The range in the mode: the long one in the mixed mode, which reaches farther; 0 for an unknown mode. */
    public static double gtRange(int tier, int mode) {
        long v = tier >= 0 && tier < voltages.length ? voltages[tier] : 0;
        switch (mode) {
            case MODE_LONG:
                return (int) v * 4;
            case MODE_LOCAL:
                return tier * 20;
            case MODE_MIXED:
                return (int) v * 2;
            default:
                return 0;
        }
    }

    /** The local range of the mixed mode, where it gives several amps; 0 in the other modes. */
    public static double gtMixedLocalRange(int tier, int mode) {
        return mode == MODE_MIXED ? tier * 10 : 0;
    }

    /** Reached in every mode: the mixed mode's local range, the smallest of all. */
    public static double gtMinRange(int tier) {
        return tier * 10;
    }

    /**
     * The mode whose ranges are among the numbers (those in the charger's chat answer to a right click: "Range: 128m",
     * "Range: 64m (Local: 10m)"), whatever the server's language; {@link #MODE_UNKNOWN} if none fits.
     */
    public static int gtModeFromNumbers(int tier, java.util.Collection<Long> numbers) {
        if (numbers.contains((long) gtRange(tier, MODE_MIXED)) && numbers.contains((long) gtMinRange(tier))) {
            return MODE_MIXED;
        }
        if (numbers.contains((long) gtRange(tier, MODE_LONG))) {
            return MODE_LONG;
        }
        if (numbers.contains((long) gtRange(tier, MODE_LOCAL))) {
            return MODE_LOCAL;
        }
        return MODE_UNKNOWN;
    }

    // ---------------------------------------------------------------- EnderIO

    private static volatile Boolean enderIo;
    private static Class<?> enderIoCharger;
    private static Method enderIoActive;
    private static Field enderIoRange;

    public static boolean isEnderIoAvailable() {
        if (enderIo == null) {
            lookUpEnderIo();
        }
        return enderIo;
    }

    private static synchronized void lookUpEnderIo() {
        if (enderIo == null) {
            enderIo = false;
            if (Loader.isModLoaded("EnderIO")) {
                try {
                    enderIoCharger = Class.forName(ENDERIO_CHARGER, false, Chargers.class.getClassLoader());
                    enderIoActive = enderIoCharger.getMethod("isActive");
                    enderIoRange = Class.forName("crazypants.enderio.config.Config")
                        .getField("wirelessChargerRange");
                    enderIo = true;
                } catch (Throwable t) {
                    WayFarMap.LOG.info("EnderIO wireless chargers are not available: {}", t.toString());
                }
            }
        }
    }

    public static boolean isEnderIoCharger(TileEntity tile) {
        return tile != null && isEnderIoAvailable() && enderIoCharger.isInstance(tile);
    }

    /** Energy stored and no redstone signal; the client knows both (EnderIO sends the energy around). */
    public static boolean enderIoActive(TileEntity tile) {
        try {
            return (Boolean) enderIoActive.invoke(tile);
        } catch (Throwable t) {
            return false;
        }
    }

    public static double enderIoRange() {
        try {
            return enderIoRange.getInt(null);
        } catch (Throwable t) {
            return 24;
        }
    }

    // ---------------------------------------------------------------- reflection

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Look in the superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field optionalField(Class<?> type, String name) {
        try {
            return field(type, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Map<String, Optional<Method>> NO_ARG_METHODS = new ConcurrentHashMap<>();

    /** A public method without arguments, null if missing or failing. */
    private static Object call(Object target, String name) {
        Class<?> type = target.getClass();
        try {
            Optional<Method> method = NO_ARG_METHODS.computeIfAbsent(type.getName() + "#" + name, k -> {
                try {
                    return Optional.of(type.getMethod(name));
                } catch (Throwable t) {
                    return Optional.empty();
                }
            });
            return method.isPresent() ? method.get()
                .invoke(target) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
