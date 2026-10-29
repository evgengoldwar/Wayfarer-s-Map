package WayFarMap.share;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server side of the wireless chargers, drone centres and mob protection layers. The server tells only about machines
 * the client has
 * seen itself: the client sends where they are ({@link ShareNetwork.MachineAsk}) and gets, every couple of seconds,
 * what only the server knows about those of them in loaded chunks: a GregTech charger's mode and whether it charges
 * this player, the drone level on GregTech versions that don't send it, a Monster Repellator's range in force, the
 * energy. Nothing else is looked at, so no
 * machine the player hasn't found is revealed, and no pass over all tile entities is needed.
 * <p>
 * A client that says {@link ShareNetwork#MACHINES_PROTOCOL} in its hello gets an empty list of each layer the server
 * can answer: that is its sign to start asking.
 */
public final class MachineServer {

    public static final MachineServer INSTANCE = new MachineServer();

    private static final int INTERVAL_TICKS = 40;

    public static boolean isActive() {
        return chargers() || drones() || MobGuards.isRepellatorAvailable();
    }

    private static boolean chargers() {
        return Chargers.isGtAvailable() || Chargers.isEnderIoAvailable();
    }

    private static boolean drones() {
        return DroneCentres.isAvailable();
    }

    private final Queue<Object[]> inbox = new ConcurrentLinkedQueue<>();
    /** Per player, per layer: the positions asked about. */
    private final Map<UUID, List<int[]>[]> asked = new HashMap<>();
    private final Map<UUID, ShareNetwork.MachineList[]> sent = new HashMap<>();
    private final Set<UUID> due = new HashSet<>();
    private boolean broken;
    private int tick;

    private MachineServer() {}

    public void stop() {
        inbox.clear();
        asked.clear();
        sent.clear();
        due.clear();
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.Hello hello) {
        if (player != null && hello.protocol >= ShareNetwork.MACHINES_PROTOCOL && isActive()) {
            inbox.add(new Object[] { player, hello });
        }
    }

    /** From the network thread. */
    void receive(EntityPlayerMP player, ShareNetwork.MachineAsk ask) {
        if (player != null && isActive()) {
            inbox.add(new Object[] { player, ask });
        }
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken) {
            return;
        }
        Object[] entry;
        while ((entry = inbox.poll()) != null) {
            EntityPlayerMP player = (EntityPlayerMP) entry[0];
            if (!TeamMapServer.isOnline(player)) {
                continue;
            }
            UUID id = player.getUniqueID();
            if (entry[1] instanceof ShareNetwork.Hello) {
                asked.put(id, new List[ShareNetwork.MachineList.LAYERS]);
                sent.remove(id);
                if (chargers()) {
                    ShareNetwork.sendTo(new ShareNetwork.MachineList(ShareNetwork.MachineList.CHARGERS), player);
                }
                if (drones()) {
                    ShareNetwork.sendTo(new ShareNetwork.MachineList(ShareNetwork.MachineList.DRONES), player);
                }
                if (MobGuards.isRepellatorAvailable()) {
                    ShareNetwork.sendTo(new ShareNetwork.MachineList(ShareNetwork.MachineList.GUARDS), player);
                }
            } else {
                ShareNetwork.MachineAsk ask = (ShareNetwork.MachineAsk) entry[1];
                List<int[]>[] layers = asked.get(id);
                if (layers != null) {
                    layers[ask.layer] = ask.positions;
                    due.add(id);
                }
            }
        }
        boolean round = ++tick >= INTERVAL_TICKS;
        if (round) {
            tick = 0;
        } else if (due.isEmpty()) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        try {
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) o;
                UUID id = player.getUniqueID();
                online.add(id);
                List<int[]>[] layers = asked.get(id);
                if (layers != null && (round || due.contains(id))) {
                    for (int layer = 0; layer < layers.length; layer++) {
                        if (layers[layer] != null) {
                            answer(player, layer, layers[layer]);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            broken = true;
            WayFarMap.LOG
                .error("The wireless chargers and drone centres layers failed and are turned off until restart", t);
        }
        due.clear();
        asked.keySet()
            .retainAll(online);
        sent.keySet()
            .retainAll(online);
    }

    private void answer(EntityPlayerMP player, int layer, List<int[]> positions) {
        ShareNetwork.MachineList list = new ShareNetwork.MachineList(layer);
        double enderIoRange = layer == ShareNetwork.MachineList.CHARGERS && Chargers.isEnderIoAvailable()
            ? Chargers.enderIoRange()
            : 0;
        for (int[] p : positions) {
            TileEntity tile = loadedTile(p[0], p[1], p[2], p[3]);
            if (tile == null) {
                continue;
            }
            if (layer == ShareNetwork.MachineList.CHARGERS) {
                charger(list, player, tile, p[0], enderIoRange);
            } else if (layer == ShareNetwork.MachineList.GUARDS) {
                Object repellator = MobGuards.repellator(tile);
                if (repellator != null) {
                    int tier = MobGuards.tier(repellator), range = MobGuards.rangeInForce(repellator);
                    boolean powered = range > 0 && range == MobGuards.repellatorRange(tier, true);
                    list.points.add(
                        new MachinePoint(
                            MobGuards.KIND_REPELLATOR,
                            p[0],
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            tier,
                            powered ? 1 : 0,
                            Math.max(0, range),
                            range > 0));
                }
            } else {
                Object centre = DroneCentres.centre(tile);
                if (centre != null) {
                    list.points.add(
                        new MachinePoint(
                            DroneCentres.KIND,
                            p[0],
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            DroneCentres.level(centre),
                            0,
                            DroneCentres.range(centre),
                            DroneCentres.isActive(tile)));
                }
            }
        }
        ShareNetwork.MachineList[] last = sent
            .computeIfAbsent(player.getUniqueID(), k -> new ShareNetwork.MachineList[ShareNetwork.MachineList.LAYERS]);
        if (list.equals(last[layer])) {
            return;
        }
        last[layer] = list;
        ShareNetwork.sendTo(list, player);
    }

    private static void charger(ShareNetwork.MachineList list, EntityPlayerMP player, TileEntity tile, int dimension,
        double enderIoRange) {
        Object gt = Chargers.gtCharger(tile);
        if (gt != null) {
            int tier = Chargers.gtTier(gt);
            int mode = Chargers.gtMode(gt);
            double range = mode == Chargers.MODE_UNKNOWN ? Chargers.gtMinRange(tier) : Chargers.gtRange(tier, mode);
            MachinePoint point = new MachinePoint(
                Chargers.KIND_GT,
                dimension,
                tile.xCoord,
                tile.yCoord,
                tile.zCoord,
                tier,
                mode,
                (float) range,
                Chargers.gtAllowedToWork(tile) && Chargers.gtHasEnergy(tile));
            (Chargers.gtChargesPlayer(tile, gt, player) ? list.points : list.foreign).add(point);
        } else if (Chargers.isEnderIoCharger(tile)) {
            list.points.add(
                new MachinePoint(
                    Chargers.KIND_ENDERIO,
                    dimension,
                    tile.xCoord,
                    tile.yCoord,
                    tile.zCoord,
                    0,
                    Chargers.MODE_UNKNOWN,
                    (float) enderIoRange,
                    Chargers.enderIoActive(tile)));
        }
    }

    /** The tile entity there if its chunk is loaded; never loads one. */
    private static TileEntity loadedTile(int dimension, int x, int y, int z) {
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null || y < 0
            || y > 255
            || !world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)) {
            return null;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        return tile == null || tile.isInvalid() ? null : tile;
    }
}
