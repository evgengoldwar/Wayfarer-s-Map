package WayFarMap.client;

import java.lang.ref.WeakReference;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.OpisLayer;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.map.ChunkLoadView;
import WayFarMap.client.waypoint.ItemSprites;
import WayFarMap.client.waypoint.WaypointManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Lets go of what was kept for a world once it is left, so the old world (its chunks and entities) and the textures
 * made for it don't stay in memory until something happens to be drawn in the next one: on every change of world
 * (another dimension is another world on the client), and more when the server is left.
 */
public final class WorldCleanup {

    public static final WorldCleanup INSTANCE = new WorldCleanup();

    /** The world of the last tick; weak, not to be what keeps it. */
    private WeakReference<World> last = new WeakReference<>(null);
    private boolean inWorld;

    private WorldCleanup() {}

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = Minecraft.getMinecraft().theWorld;
        if (world == last.get()) {
            return;
        }
        if (inWorld) {
            // Mob icons and item pictures are made again in each world; the settings' preview mobs live in one.
            MobIcons.reset();
            ItemSprites.reset();
            MobPreview.release();
            ChunkLoadView.forgetRegionStates();
            if (world == null) {
                // The server is left: what was found on it, and the waypoints file it has (written to otherwise
                // by what still came in from it).
                ProspectingLayer.reset();
                if (Mods.isOpisAvailable()) {
                    OpisLayer.reset();
                }
                ChunkLoadView.reset();
                WaypointManager.INSTANCE.unload();
            }
        }
        last = new WeakReference<>(world);
        inWorld = world != null;
        if (inWorld) {
            // Asks the new server for the rights, so they are known by the time the map is opened.
            Mods.isOpisAllowed();
        }
    }
}
