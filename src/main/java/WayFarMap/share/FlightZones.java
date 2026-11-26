package WayFarMap.share;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.tileentity.TileEntity;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;

/**
 * Blocks that keep players flying around them, reached by reflection: GregTech's MagLev Pylon and Automagy's
 * Thaumostatic Spire. Used by both sides.
 * <p>
 * MagLev Pylon: creative flight for whoever wears a MagLev Harness inside a cube around it (gtnhlib's cube check,
 * the block's centre ± range + 0.5: the blocks within the range of the pylon's own block). The range is
 * {@code (tier - 1) * 16} with energy, half of it without, none when switched off. The client knows the tier and the
 * switch, not the energy; the server knows the range in force (the machine's {@code range}, -1 when off).
 * <p>
 * Thaumostatic Spire: refuels the Potentia of every thaumostatic harness in a vertical cylinder around its controller
 * (the 2D distance from the controller's centre, no height limit): 4 blocks per pylon of the structure, 15 while it
 * burns Aer (one Aer lasts 1200 ticks), up to 6 pylons. It works while built, powered by Aer vis and with Potentia
 * stored. The controller sends all of that to the client, but only when its essentia changes: the Aer countdown runs
 * on the server alone, so the client counts it down itself.
 */
public final class FlightZones {

    public static final int KIND_MAGLEV = 6, KIND_SPIRE = 7;
    public static final int SPIRE_BOOST_TICKS = 1200;

    private static final String MAGLEV = "gregtech.common.tileentities.machines.basic.MTEMagLevPylon";
    private static final String CONTROLLER = "tuhljin.automagy.tiles.TileEntityThaumostaticController";
    private static final String SPIRE = "tuhljin.automagy.tiles.TileEntityThaumostaticRefuelerSpire";
    private static final int SPIRE_PER_PYLON = 4, SPIRE_BOOSTED_PER_PYLON = 15, SPIRE_MAX_PYLONS = 6;

    private static volatile Boolean maglevs, spires;
    private static Class<?> maglev, controller, spire;
    private static Field maglevTier, maglevRange;
    private static Field active, pylonCount, timeRangeUp, spireY, aspects;
    private static Method isPowered, getAmount;
    private static Object potentia;

    private FlightZones() {}

    public static boolean isAvailable() {
        return isMagLevAvailable() || isSpireAvailable();
    }

    public static boolean isMagLevAvailable() {
        if (maglevs == null) {
            lookUpMagLev();
        }
        return maglevs;
    }

    public static boolean isSpireAvailable() {
        if (spires == null) {
            lookUpSpire();
        }
        return spires;
    }

    private static synchronized void lookUpMagLev() {
        if (maglevs != null) {
            return;
        }
        boolean found = false;
        if (Loader.isModLoaded("gregtech")) {
            try {
                maglev = Class.forName(MAGLEV, false, FlightZones.class.getClassLoader());
                maglevTier = maglev.getField("mTier");
                maglevRange = maglev.getDeclaredField("range");
                maglevRange.setAccessible(true);
                found = true;
            } catch (Throwable t) {
                WayFarMap.LOG.info("GregTech MagLev Pylons are not available: {}", t.toString());
            }
        }
        maglevs = found;
    }

    private static synchronized void lookUpSpire() {
        if (spires != null) {
            return;
        }
        boolean found = false;
        if (Loader.isModLoaded("Automagy")) {
            try {
                ClassLoader loader = FlightZones.class.getClassLoader();
                controller = Class.forName(CONTROLLER, false, loader);
                spire = Class.forName(SPIRE, false, loader);
                active = controller.getField("active");
                pylonCount = controller.getField("pylonCount");
                timeRangeUp = controller.getField("timeRangeUp");
                spireY = controller.getField("spireY");
                aspects = controller.getField("aspects");
                isPowered = spire.getMethod("isPowered");
                Class<?> aspect = Class.forName("thaumcraft.api.aspects.Aspect", false, loader);
                potentia = aspect.getField("ENERGY")
                    .get(null);
                getAmount = aspects.getType()
                    .getMethod("getAmount", aspect);
                found = true;
            } catch (Throwable t) {
                WayFarMap.LOG.info("Automagy Thaumostatic Spires are not available: {}", t.toString());
            }
        }
        spires = found;
    }

    // ---------------------------------------------------------------- MagLev Pylon

    /** The machine of a GregTech block ({@link GtMeta#of}) if it is a MagLev Pylon, or null. */
    public static Object magLevOf(Object machine) {
        return machine != null && isMagLevAvailable() && maglev.isInstance(machine) ? machine : null;
    }

    public static Object magLev(TileEntity tile) {
        return tile == null || !isMagLevAvailable() ? null : magLevOf(GtMeta.of(tile));
    }

    public static int magLevTier(Object pylon) {
        try {
            return ((Number) maglevTier.get(pylon)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Server side: the range in force, -1 when switched off. */
    public static int magLevRangeInForce(Object pylon) {
        try {
            return maglevRange.getInt(pylon);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int magLevRange(int tier, boolean powered) {
        return (int) ((powered ? 1.0 : 0.5) * Math.max(0, tier - 1) * 16);
    }

    // ---------------------------------------------------------------- Thaumostatic Spire

    public static boolean isController(TileEntity tile) {
        return tile != null && isSpireAvailable() && controller.isInstance(tile);
    }

    /** The structure is built and switched on with a wand. */
    public static boolean controllerActive(TileEntity tile) {
        try {
            return active.getBoolean(tile);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The pylons that count, at most 6. */
    public static int pylons(TileEntity tile) {
        try {
            return Math.max(0, Math.min(SPIRE_MAX_PYLONS, pylonCount.getInt(tile)));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Ticks left of the Aer boost to the range, as last sent. */
    public static int boostTicks(TileEntity tile) {
        try {
            return Math.max(0, timeRangeUp.getInt(tile));
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int potentia(TileEntity tile) {
        try {
            return ((Number) getAmount.invoke(aspects.get(tile), potentia)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The spire block on top of the controller has Aer vis; false if it isn't there. */
    public static boolean spirePowered(TileEntity tile) {
        try {
            TileEntity top = tile.getWorldObj()
                .getTileEntity(tile.xCoord, spireY.getInt(tile), tile.zCoord);
            return spire.isInstance(top) && (Boolean) isPowered.invoke(top);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int spireRange(int pylons, boolean boosted) {
        return pylons * (boosted ? SPIRE_BOOSTED_PER_PYLON : SPIRE_PER_PYLON);
    }
}
