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
 * Blocks that keep monsters from spawning around them, reached by reflection: GregTech's Monster Repellator, Extra
 * Utilities' Magnum Torch and Chandelier. Both only stop natural spawns of monsters, not spawners.
 * <p>
 * The repellator guards a sphere around its block's centre (GregTech's spawn handler, gtnhlib's proximity check adds
 * 0.5 to the radius): {@code 16 + 48 * tier} blocks with power, {@code 4 + 12 * tier} without, nothing when switched
 * off. The client knows the tier and the switch but not the energy; the server knows the range in force (the
 * machine's {@code mRange}, -1 when off).
 * <p>
 * Extra Utilities guards an ellipsoid around the block's centre, {@code (dx² + dz²) / h² + dy² / v² <= 1}, with
 * {@code h²} and {@code v²} from the tile entity: 128 and 32 for the torch, 16 and 16 for the chandelier. The client
 * knows it all. A torch placed as a multipart part doesn't guard anything (the spawn check wants the torch's own tile
 * entity there), so only the block counts.
 */
public final class MobGuards {

    public static final int KIND_REPELLATOR = 3, KIND_MAGNUM_TORCH = 4, KIND_CHANDELIER = 5;

    private static final String[] REPELLATORS = { "gregtech.common.tileentities.machines.basic.MTEMonsterRepellent",
        "gregtech.common.tileentities.machines.basic.GT_MetaTileEntity_MonsterRepellent" };
    private static final String TORCH = "com.rwtema.extrautils.tileentity.TileEntityAntiMobTorch";

    private static volatile Boolean repellators, torches;
    private static Class<?> repellator, torch;
    private static Field tier, range;
    private static Method horizontal, vertical;
    private static final Map<Class<?>, Optional<Method>> META_GETTERS = new ConcurrentHashMap<>();

    private MobGuards() {}

    public static boolean isAvailable() {
        return isRepellatorAvailable() || isTorchAvailable();
    }

    public static boolean isRepellatorAvailable() {
        if (repellators == null) {
            lookUpRepellator();
        }
        return repellators;
    }

    public static boolean isTorchAvailable() {
        if (torches == null) {
            lookUpTorch();
        }
        return torches;
    }

    private static synchronized void lookUpRepellator() {
        if (repellators != null) {
            return;
        }
        boolean found = false;
        if (Loader.isModLoaded("gregtech")) {
            for (String name : REPELLATORS) {
                try {
                    repellator = Class.forName(name, false, MobGuards.class.getClassLoader());
                    tier = repellator.getField("mTier");
                    range = repellator.getDeclaredField("mRange");
                    range.setAccessible(true);
                    found = true;
                    break;
                } catch (Throwable ignored) {
                    // An older or newer name.
                }
            }
            if (!found) {
                WayFarMap.LOG.info("GregTech Monster Repellators are not available");
            }
        }
        repellators = found;
    }

    private static synchronized void lookUpTorch() {
        if (torches != null) {
            return;
        }
        boolean found = false;
        if (Loader.isModLoaded("ExtraUtilities")) {
            try {
                torch = Class.forName(TORCH, false, MobGuards.class.getClassLoader());
                horizontal = torch.getMethod("getHorizontalTorchRangeSquared");
                vertical = torch.getMethod("getVerticalTorchRangeSquared");
                found = true;
            } catch (Throwable t) {
                WayFarMap.LOG.info("Extra Utilities Magnum Torches are not available: {}", t.toString());
            }
        }
        torches = found;
    }

    /** The repellator machine of a GregTech machine block, or null. */
    public static Object repellator(TileEntity tile) {
        if (tile == null || !isRepellatorAvailable()) {
            return null;
        }
        try {
            Optional<Method> getter = META_GETTERS.computeIfAbsent(tile.getClass(), MobGuards::metaGetter);
            if (!getter.isPresent()) {
                return null;
            }
            Object meta = getter.get()
                .invoke(tile);
            return repellator.isInstance(meta) ? meta : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Optional<Method> metaGetter(Class<?> type) {
        try {
            return Optional.of(type.getMethod("getMetaTileEntity"));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public static int tier(Object repellator) {
        try {
            return ((Number) tier.get(repellator)).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Server side: the range in force, -1 when switched off. */
    public static int rangeInForce(Object repellator) {
        try {
            return range.getInt(repellator);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int repellatorRange(int tier, boolean powered) {
        return powered ? 16 + 48 * tier : 4 + 12 * tier;
    }

    /** {@link #KIND_MAGNUM_TORCH} or {@link #KIND_CHANDELIER}, -1 if the tile entity is neither. */
    public static int torchKind(TileEntity tile) {
        if (tile == null || !isTorchAvailable() || !torch.isInstance(tile)) {
            return -1;
        }
        float h = squared(horizontal, tile);
        return h <= 0 ? -1 : h > 256 ? KIND_MAGNUM_TORCH : KIND_CHANDELIER;
    }

    /** The horizontal and the vertical radius of a torch's ellipsoid. */
    public static double[] torchRadii(TileEntity tile) {
        return new double[] { Math.sqrt(Math.max(0, squared(horizontal, tile))),
            Math.sqrt(Math.max(0, squared(vertical, tile))) };
    }

    private static float squared(Method method, TileEntity tile) {
        try {
            return ((Number) method.invoke(tile)).floatValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
