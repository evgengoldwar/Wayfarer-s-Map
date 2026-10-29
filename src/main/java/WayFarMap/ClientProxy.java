package WayFarMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import WayFarMap.client.InspectCommand;
import WayFarMap.client.IsoEntityDrawer;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.Lang;
import WayFarMap.client.MinimapRenderer;
import WayFarMap.client.MobIconDump;
import WayFarMap.client.PerfTicks;
import WayFarMap.client.PlayerTrail;
import WayFarMap.client.Teleport;
import WayFarMap.client.WorldCleanup;
import WayFarMap.client.integration.ChargersLayer;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.DronesLayer;
import WayFarMap.client.integration.GuardsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.WirelessLayer;
import WayFarMap.client.map.ChunkLoadClient;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.TeamMapClient;
import WayFarMap.client.waypoint.DeathMarker;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;
import WayFarMap.share.ShareNetwork;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        KeyHandler keyHandler = new KeyHandler();
        keyHandler.register();
        FMLCommonHandler.instance()
            .bus()
            .register(keyHandler);

        MapManager manager = MapManager.INSTANCE;
        FMLCommonHandler.instance()
            .bus()
            .register(manager);
        MinecraftForge.EVENT_BUS.register(manager);
        IResourceManager resourceManager = Minecraft.getMinecraft()
            .getResourceManager();
        if (resourceManager instanceof IReloadableResourceManager) {
            // Texture packs change block colors.
            ((IReloadableResourceManager) resourceManager).registerReloadListener(manager);
            // Resource packs can change the mod's translations.
            ((IReloadableResourceManager) resourceManager).registerReloadListener(reloaded -> Lang.reload());
        }

        FMLCommonHandler.instance()
            .bus()
            .register(Teleport.INSTANCE);

        if (Mods.isClaimsAvailable()) {
            ClaimsLayer.register();
        }

        if (Mods.isAe2Loaded()) {
            FMLCommonHandler.instance()
                .bus()
                .register(WirelessLayer.INSTANCE);
        }
        if (Mods.isChargersAvailable()) {
            FMLCommonHandler.instance()
                .bus()
                .register(ChargersLayer.INSTANCE);
            // Right clicks and the chat: a GregTech charger's answer tells its mode.
            MinecraftForge.EVENT_BUS.register(ChargersLayer.INSTANCE);
        }
        if (Mods.isDronesAvailable()) {
            FMLCommonHandler.instance()
                .bus()
                .register(DronesLayer.INSTANCE);
        }
        if (Mods.isGuardsAvailable()) {
            FMLCommonHandler.instance()
                .bus()
                .register(GuardsLayer.INSTANCE);
        }

        FMLCommonHandler.instance()
            .bus()
            .register(TeamMapClient.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(ChunkLoadClient.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(DeathMarker.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(PlayerTrail.INSTANCE);
        // Lets go of the old world's caches once it is left.
        FMLCommonHandler.instance()
            .bus()
            .register(WorldCleanup.INSTANCE);
        // Times frames and ticks for the 3D log, to tell what costs frames per second and ticks per second.
        FMLCommonHandler.instance()
            .bus()
            .register(PerfTicks.INSTANCE);

        // Waypoints shared in the chat: shown with an [Add] button that runs a client-side command.
        MinecraftForge.EVENT_BUS.register(WaypointShare.INSTANCE);
        MinecraftForge.EVENT_BUS.register(Lang.ChatTranslator.INSTANCE);
        ClientCommandHandler.instance.registerCommand(new WaypointShare.AddCommand());
        // Report of how a block gets onto the 3D map, to send when it looks wrong there.
        ClientCommandHandler.instance.registerCommand(new InspectCommand());
        // Material to make the map's mob icons from: every mob drawn by the game, its skin and model.
        ClientCommandHandler.instance.registerCommand(new MobIconDump());

        MinecraftForge.EVENT_BUS.register(new IsoEntityDrawer.NameTags());
        MinecraftForge.EVENT_BUS.register(new MinimapRenderer());
        MinecraftForge.EVENT_BUS.register(new WaypointRenderer());
    }

    @Override
    public void receiveTeamMap(IMessage message) {
        TeamMapClient.INSTANCE.receive(message);
    }

    @Override
    public void receiveChunkLoad(IMessage message) {
        ChunkLoadClient.INSTANCE.receive(message);
    }

    @Override
    public void receiveMachines(IMessage message) {
        ShareNetwork.MachineList list = (ShareNetwork.MachineList) message;
        if (list.layer == ShareNetwork.MachineList.CHARGERS && Mods.isChargersAvailable()) {
            ChargersLayer.INSTANCE.receive(list);
        } else if (list.layer == ShareNetwork.MachineList.DRONES && Mods.isDronesAvailable()) {
            DronesLayer.INSTANCE.receive(list);
        } else if (list.layer == ShareNetwork.MachineList.GUARDS && Mods.isGuardsAvailable()) {
            GuardsLayer.INSTANCE.receive(list);
        }
    }

    @Override
    public void receiveWireless(IMessage message) {
        if (Mods.isAe2Loaded()) {
            WirelessLayer.INSTANCE.receive((ShareNetwork.Wireless) message);
        }
    }
}
