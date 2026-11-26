package WayFarMap.client.integration;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.container.implementations.ContainerWireless;
import appeng.core.AEConfig;
import appeng.tile.networking.TileWireless;

/** AE2 access for {@link WirelessLayer} on the client; only touched after {@link Mods#isAe2Loaded()}. */
final class Ae2WirelessClient {

    private Ae2WirelessClient() {}

    /** {x, y, z, active (0/1)} if the tile entity is an access point, or null. */
    static int[] accessPoint(TileEntity o) {
        if (!(o instanceof TileWireless)) {
            return null;
        }
        TileWireless tile = (TileWireless) o;
        return new int[] { tile.xCoord, tile.yCoord, tile.zCoord, tile.isActive() ? 1 : 0 };
    }

    static boolean isAccessPoint(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof TileWireless;
    }

    /**
     * The access point whose screen the player has open and its range, synced by AE2 as ten times the blocks:
     * {x, y, z, range, active (0/1)}; null when none is open or the range hasn't come yet.
     */
    static double[] openAccessPoint(EntityPlayer player) {
        if (!(player.openContainer instanceof ContainerWireless)) {
            return null;
        }
        ContainerWireless container = (ContainerWireless) player.openContainer;
        TileEntity tile = container.getTileEntity();
        long range = container.getRange();
        if (tile == null || range <= 0) {
            return null;
        }
        boolean active = tile instanceof TileWireless && ((TileWireless) tile).isActive();
        return new double[] { tile.xCoord, tile.yCoord, tile.zCoord, range / 10.0, active ? 1 : 0 };
    }

    /** The range without boosters by the client's AE2 config, for access points whose screen wasn't opened yet. */
    static double baseRange() {
        return AEConfig.instance.wireless_getMaxRange(0);
    }
}
