package WayFarMap.client.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import WayFarMap.WayFarMap;
import WayFarMap.share.Chargers;
import WayFarMap.share.DroneCentres;
import WayFarMap.share.MobGuards;
import cpw.mods.fml.common.Loader;

/**
 * Optional mods. Integration classes may only be touched after checking these.
 * <p>
 * Being installed is not enough: the integrations are compiled against the versions in {@code dependencies.gradle},
 * and an older version (an older GTNH pack) lacks methods they call, which would crash the game on the first frame
 * that draws the layer. So each integration also checks that the API it uses is there, and is turned off if not.
 */
public final class Mods {

    private static Boolean visualProspecting;
    private static Boolean claims;
    private static Boolean thaumcraftNodes;
    private static Boolean ae2;
    private static boolean chargersBroken, dronesBroken, guardsBroken;
    /** Set when drawing GregTech power failures failed: the layer is off for the rest of the session. */
    private static boolean powerfailsBroken;

    private Mods() {}

    /**
     * ServerUtilities chunk claims. Its map protocol lives in its Navigator integration, whose client handler
     * needs Navigator, so both have to be installed (as in GTNH).
     */
    public static boolean isClaimsAvailable() {
        if (claims == null) {
            claims = Loader.isModLoaded("serverutilities") && Loader.isModLoaded("navigator")
                && supported(
                    "ServerUtilities",
                    "2.2.31",
                    hasConstructor("serverutils.net.MessageNavigatorValidateKnown", "LongList"));
        }
        return claims;
    }

    /** GregTech power failure markers (GT5-Unofficial versions that have them); see {@link PowerfailLayer}. */
    public static boolean isPowerfailsAvailable() {
        return !powerfailsBroken && Loader.isModLoaded("gregtech") && PowerfailLayer.isAvailable();
    }

    /** Thaumcraft aura nodes found with TCNodeTracker (which needs Thaumcraft); see {@link ThaumcraftNodes}. */
    public static boolean isThaumcraftNodesAvailable() {
        if (thaumcraftNodes == null) {
            thaumcraftNodes = Loader.isModLoaded("tcnodetracker") && Loader.isModLoaded("Thaumcraft");
        }
        return thaumcraftNodes;
    }

    /**
     * AE2 wireless access points; see {@link WirelessLayer}. The GTNH fork's {@code ContainerWireless} with its synced
     * range and {@code AEBaseContainer.getTileEntity} are what the client reads.
     */
    public static boolean isAe2Loaded() {
        if (ae2 == null) {
            ae2 = Loader.isModLoaded("appliedenergistics2") && supported(
                "Applied Energistics 2",
                "rv3",
                hasMethod("appeng.container.implementations.ContainerWireless", "getRange")
                    && hasMethod("appeng.container.AEBaseContainer", "getTileEntity")
                    && hasMethod("appeng.tile.networking.TileWireless", "isActive"));
        }
        return ae2;
    }

    /** GregTech or EnderIO wireless chargers; see {@link ChargersLayer}. */
    public static boolean isChargersAvailable() {
        return !chargersBroken && (Chargers.isGtAvailable() || Chargers.isEnderIoAvailable());
    }

    /** GregTech's Drone Centre; see {@link DronesLayer}. */
    public static boolean isDronesAvailable() {
        return !dronesBroken && DroneCentres.isAvailable();
    }

    /** GregTech's Monster Repellator or Extra Utilities' Magnum Torch; see {@link GuardsLayer}. */
    public static boolean isGuardsAvailable() {
        return !guardsBroken && MobGuards.isAvailable();
    }

    private static Boolean nodeLayerDelete;

    /**
     * TCNodeTracker's {@code deleteNode(NodeList)}, which also updates its own map layers. Older versions only have
     * {@code deleteNode(ThaumcraftNodeLocation)}; there the node is taken off its list directly.
     */
    public static boolean hasNodeLayerDelete() {
        if (nodeLayerDelete == null) {
            nodeLayerDelete = hasMethod(
                "com.dyonovan.tcnodetracker.integration.navigator.ThaumcraftNodeLayerManager",
                "deleteNode",
                "NodeList");
        }
        return nodeLayerDelete;
    }

    public static boolean isVisualProspectingLoaded() {
        if (visualProspecting == null) {
            String pkg = "com.sinthoras.visualprospecting.";
            visualProspecting = Loader.isModLoaded("visualprospecting") && supported(
                "VisualProspecting",
                "1.5.32",
                hasMethod(pkg + "integration.model.locations.UndergroundFluidLocation", "getColor")
                    && hasMethod(pkg + "integration.model.locations.OreVeinLocation", "getIconFromPrimaryOre")
                    && hasMethod(pkg + "integration.model.render.DimensionStoneBackground", "getBackgroundIcon")
                    && hasMethod(pkg + "Utils", "getItemFilter"));
        }
        return visualProspecting;
    }

    // ---------------------------------------------------------------- failure guard

    /** One of the mod layer integrations, for {@link #draw}. */
    public enum Addon {
        VISUAL_PROSPECTING,
        CLAIMS,
        THAUMCRAFT_NODES,
        POWERFAILS,
        AE2_WIRELESS,
        CHARGERS,
        DRONES,
        GUARDS
    }

    private static final Set<Addon> FAILED = new HashSet<>();

    /**
     * Runs one mod layer's drawing. If the other mod throws (an incompatible version the checks above missed,
     * broken data), the layer is turned off for the session instead of taking the game down: a draw that stops
     * halfway leaves the Tessellator mid-batch, and every later draw, even Minecraft's loading screen, then fails
     * with "Already tesselating!".
     */
    public static void draw(Addon addon, Runnable drawing) {
        try {
            drawing.run();
        } catch (Throwable t) {
            finishTessellator();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            fail(addon, t);
        }
    }

    /** Turns the layer off for the session after the other mod threw, outside of drawing too. */
    public static void fail(Addon addon, Throwable t) {
        switch (addon) {
            case VISUAL_PROSPECTING:
                visualProspecting = false;
                break;
            case CLAIMS:
                claims = false;
                break;
            case THAUMCRAFT_NODES:
                thaumcraftNodes = false;
                break;
            case POWERFAILS:
                powerfailsBroken = true;
                break;
            case AE2_WIRELESS:
                ae2 = false;
                break;
            case CHARGERS:
                chargersBroken = true;
                break;
            case DRONES:
                dronesBroken = true;
                break;
            case GUARDS:
                guardsBroken = true;
                break;
        }
        if (FAILED.add(addon)) {
            WayFarMap.LOG.error("The " + addon + " map layer failed and is turned off until restart", t);
        }
    }

    /** Ends a batch the failed layer had started, if any. */
    private static void finishTessellator() {
        try {
            Tessellator.instance.draw();
        } catch (Throwable ignored) {
            // Not tesselating: nothing was left open.
        }
    }

    // ---------------------------------------------------------------- API checks

    private static boolean supported(String mod, String minVersion, boolean apiPresent) {
        if (!apiPresent) {
            WayFarMap.LOG.warn(
                "{} is installed but too old for Wayfarer's Map ({} or newer is needed); its map layers are off",
                mod,
                minVersion);
        }
        return apiPresent;
    }

    private static Class<?> find(String className) throws ClassNotFoundException {
        return Class.forName(className, false, Mods.class.getClassLoader());
    }

    private static boolean hasMethod(String className, String method) {
        return hasMethod(className, method, null);
    }

    /** True if the class has a public method of that name; with a parameter type, taking just that (simple name). */
    private static boolean hasMethod(String className, String method, String parameterType) {
        try {
            for (Method m : find(className).getMethods()) {
                if (!m.getName()
                    .equals(method)) {
                    continue;
                }
                Class<?>[] parameters = m.getParameterTypes();
                if (parameterType == null || parameters.length == 1 && parameters[0].getSimpleName()
                    .equals(parameterType)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Missing class or one of its dependencies.
        }
        return false;
    }

    /** True if the class has a public constructor taking one parameter of the given simple type name. */
    private static boolean hasConstructor(String className, String parameterType) {
        try {
            for (Constructor<?> c : find(className).getConstructors()) {
                Class<?>[] parameters = c.getParameterTypes();
                if (parameters.length == 1 && parameters[0].getSimpleName()
                    .equals(parameterType)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Missing class or one of its dependencies.
        }
        return false;
    }
}
