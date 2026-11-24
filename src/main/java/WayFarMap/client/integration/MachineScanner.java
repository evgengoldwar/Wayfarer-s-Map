package WayFarMap.client.integration;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;

import WayFarMap.Perf;
import WayFarMap.share.GtMeta;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * One look at the tile entities of the loaded chunks for all the coverage layers (AE2 access points, chargers, drone
 * centres, mob guards, flight), spread over {@link #SWEEP_TICKS} ticks. Each layer used to look at every tile entity
 * itself,
 * all of them in the same tick once a second, each asking every GregTech block for its machine by reflection again:
 * on a base of thousands of machines the game hitched every second. Now a tick looks at its share of the chunks, and
 * a GregTech block's machine is asked for once and handed to every layer.
 * <p>
 * The chunks around the player are looked in, not {@code loadedTileEntityList}: Forge puts only ticking tile entities
 * there, and an access point doesn't tick. Runs after the layers' own tick (lower priority), so a layer has loaded the
 * world's saved points before it is shown any.
 */
public final class MachineScanner {

    static final MachineScanner INSTANCE = new MachineScanner();

    /** Ticks a look over all the chunks takes. */
    private static final int SWEEP_TICKS = 20;

    /** A coverage layer fed by the scanner. */
    interface Layer {

        Mods.Addon addon();

        /** Its mod is there and it is in a world; a layer not scanning sits out the sweep. */
        boolean scans();

        /** A sweep over the chunks begins. */
        void sweepStart();

        /** A tile entity of a loaded chunk; {@code gtMachine} is its GregTech machine ({@link GtMeta#of}) or null. */
        void see(int dimension, TileEntity tile, Object gtMachine);

        /** All the chunks of the sweep looked at: drop what is gone, ask the server. */
        void sweepEnd(World world, int dimension);
    }

    private final List<Layer> layers = new ArrayList<>();
    /** The layers of the sweep going on (a failed one set to null), or null between sweeps. */
    private Layer[] sweeping;
    private World world;
    private int dimension;
    private int firstX, firstZ, side, next, perTick;

    private MachineScanner() {}

    /** Registers the scanner with the layers whose mods are there. */
    public static void register() {
        if (Mods.isAe2Loaded()) {
            INSTANCE.layers.add(WirelessLayer.INSTANCE);
        }
        if (Mods.isChargersAvailable()) {
            INSTANCE.layers.add(ChargersLayer.INSTANCE);
        }
        if (Mods.isDronesAvailable()) {
            INSTANCE.layers.add(DronesLayer.INSTANCE);
        }
        if (Mods.isGuardsAvailable()) {
            INSTANCE.layers.add(GuardsLayer.INSTANCE);
        }
        if (Mods.isFlightAvailable()) {
            INSTANCE.layers.add(FlightLayer.INSTANCE);
        }
        if (!INSTANCE.layers.isEmpty()) {
            FMLCommonHandler.instance()
                .bus()
                .register(INSTANCE);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            sweeping = null;
            world = null;
            return;
        }
        long start = Perf.start();
        if (mc.theWorld != world || mc.theWorld.provider.dimensionId != dimension) {
            sweeping = null;
        }
        if (sweeping == null && !begin(mc)) {
            Perf.end(Perf.Part.COVERAGE_SCAN, start);
            return;
        }
        int total = side * side;
        for (int end = Math.min(total, next + perTick); next < end; next++) {
            look(world.getChunkFromChunkCoords(firstX + next / side, firstZ + next % side));
        }
        if (next >= total) {
            for (int i = 0; i < sweeping.length; i++) {
                Layer layer = sweeping[i];
                if (layer != null) {
                    try {
                        layer.sweepEnd(world, dimension);
                    } catch (Throwable t) {
                        Mods.fail(layer.addon(), t);
                    }
                }
            }
            sweeping = null;
        }
        Perf.end(Perf.Part.COVERAGE_SCAN, start);
    }

    /** Starts a sweep around the player; false if no layer scans. */
    private boolean begin(Minecraft mc) {
        List<Layer> active = new ArrayList<>();
        for (Layer layer : layers) {
            try {
                if (layer.scans()) {
                    layer.sweepStart();
                    active.add(layer);
                }
            } catch (Throwable t) {
                Mods.fail(layer.addon(), t);
            }
        }
        if (active.isEmpty()) {
            return false;
        }
        sweeping = active.toArray(new Layer[0]);
        world = mc.theWorld;
        dimension = world.provider.dimensionId;
        // The chunks the server sends reach past the client's own render distance.
        int radius = Math.min(32, Math.max(12, mc.gameSettings.renderDistanceChunks) + 2);
        firstX = (MathHelper.floor_double(mc.thePlayer.posX) >> 4) - radius;
        firstZ = (MathHelper.floor_double(mc.thePlayer.posZ) >> 4) - radius;
        side = radius * 2 + 1;
        next = 0;
        perTick = (side * side + SWEEP_TICKS - 1) / SWEEP_TICKS;
        return true;
    }

    private void look(Chunk chunk) {
        if (chunk instanceof EmptyChunk || !chunk.isChunkLoaded || chunk.chunkTileEntityMap.isEmpty()) {
            return;
        }
        for (Object o : chunk.chunkTileEntityMap.values()) {
            TileEntity tile = (TileEntity) o;
            if (tile.isInvalid()) {
                continue;
            }
            Object machine = GtMeta.of(tile);
            for (int i = 0; i < sweeping.length; i++) {
                Layer layer = sweeping[i];
                if (layer == null) {
                    continue;
                }
                try {
                    layer.see(dimension, tile, machine);
                } catch (Throwable t) {
                    Mods.fail(layer.addon(), t);
                    sweeping[i] = null;
                }
            }
        }
    }
}
