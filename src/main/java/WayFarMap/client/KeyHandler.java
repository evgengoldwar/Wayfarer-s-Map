package WayFarMap.client;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;

import WayFarMap.Config;
import WayFarMap.client.gui.GuiEditWaypoint;
import WayFarMap.client.gui.GuiWaypointList;
import WayFarMap.client.gui.GuiWorldMap;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class KeyHandler {

    private static final String CATEGORY = "key.categories.wayfarmap";
    private static final String CATEGORY_MAP = "key.categories.wayfarmap.map";
    private static final String CATEGORY_MINIMAP = "key.categories.wayfarmap.minimap";

    public static final KeyBinding OPEN_MAP = new KeyBinding("key.wayfarmap.open_map", Keyboard.KEY_M, CATEGORY);
    public static final KeyBinding TOGGLE_MINIMAP = new KeyBinding(
        "key.wayfarmap.toggle_minimap",
        Keyboard.KEY_N,
        CATEGORY_MINIMAP);
    public static final KeyBinding ZOOM_IN = new KeyBinding(
        "key.wayfarmap.zoom_in",
        Keyboard.KEY_EQUALS,
        CATEGORY_MINIMAP);
    public static final KeyBinding ZOOM_OUT = new KeyBinding(
        "key.wayfarmap.zoom_out",
        Keyboard.KEY_MINUS,
        CATEGORY_MINIMAP);
    public static final KeyBinding NEW_WAYPOINT = new KeyBinding(
        "key.wayfarmap.new_waypoint",
        Keyboard.KEY_B,
        CATEGORY);
    public static final KeyBinding CAVE_MODE = new KeyBinding("key.wayfarmap.cave_mode", Keyboard.KEY_K, CATEGORY);
    public static final KeyBinding WAYPOINT_LIST = new KeyBinding(
        "key.wayfarmap.waypoint_list",
        Keyboard.KEY_U,
        CATEGORY);

    // Map views and layers (shared by the minimap and the world map): no key until the player sets one.
    public static final KeyBinding MODE_BLOCKS = unbound("mode_blocks");
    public static final KeyBinding MODE_PLANTLESS = unbound("mode_plantless");
    public static final KeyBinding MODE_TOPO = unbound("mode_topo");
    public static final KeyBinding MODE_BIOMES = unbound("mode_biomes");
    public static final KeyBinding ORES = unbound("ores");
    public static final KeyBinding FLUIDS = unbound("fluids");
    public static final KeyBinding CLAIMS = unbound("claims");
    public static final KeyBinding POWERFAILS = unbound("powerfails");
    public static final KeyBinding NODES = unbound("nodes");
    public static final KeyBinding WIRELESS = unbound("wireless");
    public static final KeyBinding CHARGERS = unbound("chargers");
    public static final KeyBinding DRONES = unbound("drones");
    public static final KeyBinding GUARDS = unbound("guards");
    public static final KeyBinding CHUNK_GRID = unbound("chunk_grid");
    public static final KeyBinding HOSTILE_MOBS = unbound("hostile_mobs");
    public static final KeyBinding PASSIVE_MOBS = unbound("passive_mobs");
    public static final KeyBinding AMBIENT_MOBS = unbound("ambient_mobs");
    public static final KeyBinding FRIENDLY_MOBS = unbound("friendly_mobs");
    public static final KeyBinding PETS = unbound("pets");
    public static final KeyBinding PLAYERS = unbound("players");
    public static final KeyBinding LIGHT = unbound("light");

    // The same for the minimap alone: a key gives that group of the minimap its own settings.
    public static final KeyBinding MINIMAP_MODE_BLOCKS = unbound("minimap_mode_blocks", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_MODE_PLANTLESS = unbound("minimap_mode_plantless", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_MODE_TOPO = unbound("minimap_mode_topo", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_MODE_BIOMES = unbound("minimap_mode_biomes", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_ORES = unbound("minimap_ores", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_FLUIDS = unbound("minimap_fluids", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_CLAIMS = unbound("minimap_claims", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_POWERFAILS = unbound("minimap_powerfails", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_NODES = unbound("minimap_nodes", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_WIRELESS = unbound("minimap_wireless", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_CHARGERS = unbound("minimap_chargers", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_DRONES = unbound("minimap_drones", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_GUARDS = unbound("minimap_guards", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_CHUNK_GRID = unbound("minimap_chunk_grid", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_HOSTILE_MOBS = unbound("minimap_hostile_mobs", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_PASSIVE_MOBS = unbound("minimap_passive_mobs", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_AMBIENT_MOBS = unbound("minimap_ambient_mobs", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_FRIENDLY_MOBS = unbound("minimap_friendly_mobs", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_PETS = unbound("minimap_pets", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_PLAYERS = unbound("minimap_players", CATEGORY_MINIMAP);
    public static final KeyBinding MINIMAP_LIGHT = unbound("minimap_light", CATEGORY_MINIMAP);

    private static final KeyBinding[] UNBOUND = { MODE_BLOCKS, MODE_PLANTLESS, MODE_TOPO, MODE_BIOMES, ORES, FLUIDS,
        CLAIMS, POWERFAILS, NODES, WIRELESS, CHARGERS, DRONES, GUARDS, CHUNK_GRID, HOSTILE_MOBS, PASSIVE_MOBS,
        AMBIENT_MOBS, FRIENDLY_MOBS, PETS, PLAYERS, LIGHT, MINIMAP_MODE_BLOCKS, MINIMAP_MODE_PLANTLESS,
        MINIMAP_MODE_TOPO, MINIMAP_MODE_BIOMES, MINIMAP_ORES, MINIMAP_FLUIDS, MINIMAP_CLAIMS, MINIMAP_POWERFAILS,
        MINIMAP_NODES, MINIMAP_WIRELESS, MINIMAP_CHARGERS, MINIMAP_DRONES, MINIMAP_GUARDS, MINIMAP_CHUNK_GRID,
        MINIMAP_HOSTILE_MOBS, MINIMAP_PASSIVE_MOBS, MINIMAP_AMBIENT_MOBS, MINIMAP_FRIENDLY_MOBS, MINIMAP_PETS,
        MINIMAP_PLAYERS, MINIMAP_LIGHT };

    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };
    private static final String[] LIGHT_MODE_KEYS = { "auto", "day", "night" };
    /** Chat line of these keys: each message replaces the one before instead of filling the chat. */
    private static final int CHAT_LINE = 0x57466D70;

    /**
     * The key (or mouse button) one of the mod's bindings is set to now, as the controls screen names it; null if it
     * has none. {@code name} is the binding's name without {@code key.wayfarmap.} ({@code open_map}).
     */
    public static String keyName(String name) {
        String description = "key.wayfarmap." + name;
        KeyBinding[] all = Minecraft.getMinecraft().gameSettings.keyBindings;
        if (all != null) {
            for (KeyBinding binding : all) {
                if (binding != null && description.equals(binding.getKeyDescription())) {
                    int code = binding.getKeyCode();
                    return code == Keyboard.KEY_NONE ? null : GameSettings.getKeyDisplayString(code);
                }
            }
        }
        return null;
    }

    private static KeyBinding unbound(String name) {
        return unbound(name, CATEGORY_MAP);
    }

    private static KeyBinding unbound(String name, String category) {
        return new KeyBinding("key.wayfarmap." + name, Keyboard.KEY_NONE, category);
    }

    public void register() {
        ClientRegistry.registerKeyBinding(OPEN_MAP);
        ClientRegistry.registerKeyBinding(TOGGLE_MINIMAP);
        ClientRegistry.registerKeyBinding(ZOOM_IN);
        ClientRegistry.registerKeyBinding(ZOOM_OUT);
        ClientRegistry.registerKeyBinding(NEW_WAYPOINT);
        ClientRegistry.registerKeyBinding(WAYPOINT_LIST);
        ClientRegistry.registerKeyBinding(CAVE_MODE);
        for (KeyBinding key : UNBOUND) {
            ClientRegistry.registerKeyBinding(key);
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.theWorld == null) {
            return;
        }
        if (OPEN_MAP.isPressed()) {
            mc.displayGuiScreen(new GuiWorldMap());
        }
        if (TOGGLE_MINIMAP.isPressed()) {
            Config.setMinimapEnabled(!Config.minimapEnabled);
        }
        if (ZOOM_IN.isPressed()) {
            Config.setMinimapZoom(Config.minimapZoom + 1);
        }
        if (ZOOM_OUT.isPressed()) {
            Config.setMinimapZoom(Config.minimapZoom - 1);
        }
        if (NEW_WAYPOINT.isPressed() && mc.thePlayer != null) {
            mc.displayGuiScreen(
                GuiEditWaypoint.create(
                    null,
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
                    MathHelper.floor_double(mc.thePlayer.posZ),
                    mc.theWorld.provider.dimensionId));
        }
        if (CAVE_MODE.isPressed()) {
            Config.cycleCaveMode();
            mc.ingameGUI.getChatGUI()
                .printChatMessage(
                    new ChatComponentText(
                        Lang.format("wayfarmap.option.map.caveMode") + ": "
                            + Lang.format("wayfarmap.option.map.caveMode." + CAVE_MODE_KEYS[Config.caveMode])));
        }
        if (WAYPOINT_LIST.isPressed()) {
            mc.displayGuiScreen(new GuiWaypointList(null));
        }
        viewKeys(mc);
        minimapKeys(mc);
    }

    private static void viewKeys(Minecraft mc) {
        if (MODE_BLOCKS.isPressed()) {
            Config.mapDisplayMode = Config.DISPLAY_BLOCKS;
            Config.setShowPlants(true);
            tell(mc, MODE_BLOCKS, null);
        }
        if (MODE_PLANTLESS.isPressed()) {
            Config.mapDisplayMode = Config.DISPLAY_BLOCKS;
            Config.setShowPlants(false);
            tell(mc, MODE_PLANTLESS, null);
        }
        if (MODE_TOPO.isPressed()) {
            Config.toggleTopoView();
            tell(mc, MODE_TOPO, Config.mapDisplayMode == Config.DISPLAY_TOPO);
        }
        if (MODE_BIOMES.isPressed()) {
            Config.toggleBiomeView();
            tell(mc, MODE_BIOMES, Config.mapDisplayMode == Config.DISPLAY_BIOMES);
        }
        if (ORES.isPressed() && installed(mc, ORES, Mods.isVisualProspectingLoaded())) {
            Config.toggleOreVeins();
            tell(mc, ORES, Config.showOreVeins);
        }
        if (FLUIDS.isPressed() && installed(mc, FLUIDS, Mods.isVisualProspectingLoaded())) {
            Config.toggleUndergroundFluids();
            tell(mc, FLUIDS, Config.showUndergroundFluids);
        }
        if (CLAIMS.isPressed() && installed(mc, CLAIMS, Mods.isClaimsAvailable())) {
            Config.toggleClaims();
            if (Config.showClaims) {
                ClaimsLayer.onShow();
            }
            tell(mc, CLAIMS, Config.showClaims);
        }
        if (POWERFAILS.isPressed() && installed(mc, POWERFAILS, Mods.isPowerfailsAvailable())) {
            Config.togglePowerfails();
            tell(mc, POWERFAILS, Config.showPowerfails);
        }
        if (NODES.isPressed() && installed(mc, NODES, Mods.isThaumcraftNodesAvailable())) {
            Config.toggleThaumcraftNodes();
            tell(mc, NODES, Config.showThaumcraftNodes);
        }
        if (WIRELESS.isPressed() && installed(mc, WIRELESS, Mods.isAe2Loaded())) {
            Config.toggleWireless();
            tell(mc, WIRELESS, Config.showWireless);
        }
        if (CHARGERS.isPressed() && installed(mc, CHARGERS, Mods.isChargersAvailable())) {
            Config.toggleChargers();
            tell(mc, CHARGERS, Config.showChargers);
        }
        if (DRONES.isPressed() && installed(mc, DRONES, Mods.isDronesAvailable())) {
            Config.toggleDrones();
            tell(mc, DRONES, Config.showDrones);
        }
        if (GUARDS.isPressed() && installed(mc, GUARDS, Mods.isGuardsAvailable())) {
            Config.toggleGuards();
            tell(mc, GUARDS, Config.showGuards);
        }
        if (CHUNK_GRID.isPressed()) {
            Config.toggleChunkGrid();
            tell(mc, CHUNK_GRID, Config.chunkGrid);
        }
        if (HOSTILE_MOBS.isPressed()) {
            Config.toggleHostileMobs();
            tell(mc, HOSTILE_MOBS, Config.showHostileMobs);
        }
        if (PASSIVE_MOBS.isPressed()) {
            Config.toggleNeutralMobs();
            tell(mc, PASSIVE_MOBS, Config.showPassiveMobs);
        }
        if (AMBIENT_MOBS.isPressed()) {
            Config.toggleAmbientMobs();
            tell(mc, AMBIENT_MOBS, Config.showAmbientMobs);
        }
        if (FRIENDLY_MOBS.isPressed()) {
            Config.toggleFriendlyMobs();
            tell(mc, FRIENDLY_MOBS, Config.showOtherEntities);
        }
        if (PETS.isPressed()) {
            Config.togglePets();
            tell(mc, PETS, Config.showPets);
        }
        if (PLAYERS.isPressed()) {
            Config.toggleOtherPlayers();
            tell(mc, PLAYERS, Config.showOtherPlayers);
        }
        if (LIGHT.isPressed()) {
            Config.setMapLightMode((Config.mapLightMode + 1) % LIGHT_MODE_KEYS.length);
            say(
                mc,
                Lang.format("wayfarmap.option.map.lightMode") + ": "
                    + Lang.format("wayfarmap.option.map.lightMode." + LIGHT_MODE_KEYS[Config.mapLightMode]));
        }
    }

    private static void minimapKeys(Minecraft mc) {
        if (MINIMAP_MODE_BLOCKS.isPressed()) {
            minimapView(mc, MINIMAP_MODE_BLOCKS, Config.MINIMAP_VIEW_FLAT, false);
        }
        if (MINIMAP_MODE_PLANTLESS.isPressed()) {
            minimapView(mc, MINIMAP_MODE_PLANTLESS, Config.MINIMAP_VIEW_BARE, false);
        }
        if (MINIMAP_MODE_TOPO.isPressed()) {
            minimapView(mc, MINIMAP_MODE_TOPO, Config.MINIMAP_VIEW_TOPO, true);
        }
        if (MINIMAP_MODE_BIOMES.isPressed()) {
            minimapView(mc, MINIMAP_MODE_BIOMES, Config.MINIMAP_VIEW_BIOMES, true);
        }
        Runnable layers = Config::ownMinimapLayers;
        if (MINIMAP_ORES.isPressed() && installed(mc, MINIMAP_ORES, Mods.isVisualProspectingLoaded())) {
            minimapToggle(mc, MINIMAP_ORES, layers, () -> Config.minimapOreVeins, on -> {
                Config.minimapOreVeins = on;
                if (on) {
                    Config.minimapUndergroundFluids = false;
                }
            });
        }
        if (MINIMAP_FLUIDS.isPressed() && installed(mc, MINIMAP_FLUIDS, Mods.isVisualProspectingLoaded())) {
            minimapToggle(mc, MINIMAP_FLUIDS, layers, () -> Config.minimapUndergroundFluids, on -> {
                Config.minimapUndergroundFluids = on;
                if (on) {
                    Config.minimapOreVeins = false;
                }
            });
        }
        if (MINIMAP_CLAIMS.isPressed() && installed(mc, MINIMAP_CLAIMS, Mods.isClaimsAvailable())) {
            minimapToggle(mc, MINIMAP_CLAIMS, layers, () -> Config.minimapClaims, on -> {
                Config.minimapClaims = on;
                if (on) {
                    ClaimsLayer.onShow();
                }
            });
        }
        if (MINIMAP_POWERFAILS.isPressed() && installed(mc, MINIMAP_POWERFAILS, Mods.isPowerfailsAvailable())) {
            minimapToggle(
                mc,
                MINIMAP_POWERFAILS,
                layers,
                () -> Config.minimapPowerfails,
                on -> Config.minimapPowerfails = on);
        }
        if (MINIMAP_NODES.isPressed() && installed(mc, MINIMAP_NODES, Mods.isThaumcraftNodesAvailable())) {
            minimapToggle(
                mc,
                MINIMAP_NODES,
                layers,
                () -> Config.minimapThaumcraftNodes,
                on -> Config.minimapThaumcraftNodes = on);
        }
        if (MINIMAP_WIRELESS.isPressed() && installed(mc, MINIMAP_WIRELESS, Mods.isAe2Loaded())) {
            minimapToggle(
                mc,
                MINIMAP_WIRELESS,
                layers,
                () -> Config.minimapWireless,
                on -> Config.minimapWireless = on);
        }
        if (MINIMAP_CHARGERS.isPressed() && installed(mc, MINIMAP_CHARGERS, Mods.isChargersAvailable())) {
            minimapToggle(
                mc,
                MINIMAP_CHARGERS,
                layers,
                () -> Config.minimapChargers,
                on -> Config.minimapChargers = on);
        }
        if (MINIMAP_DRONES.isPressed() && installed(mc, MINIMAP_DRONES, Mods.isDronesAvailable())) {
            minimapToggle(mc, MINIMAP_DRONES, layers, () -> Config.minimapDrones, on -> Config.minimapDrones = on);
        }
        if (MINIMAP_GUARDS.isPressed() && installed(mc, MINIMAP_GUARDS, Mods.isGuardsAvailable())) {
            minimapToggle(mc, MINIMAP_GUARDS, layers, () -> Config.minimapGuards, on -> Config.minimapGuards = on);
        }
        if (MINIMAP_CHUNK_GRID.isPressed()) {
            minimapToggle(
                mc,
                MINIMAP_CHUNK_GRID,
                Config::ownMinimapGrid,
                () -> Config.minimapChunkGrid,
                on -> Config.minimapChunkGrid = on);
        }
        Runnable mobs = Config::ownMinimapMobs;
        if (MINIMAP_HOSTILE_MOBS.isPressed()) {
            minimapToggle(
                mc,
                MINIMAP_HOSTILE_MOBS,
                mobs,
                () -> Config.minimapHostileMobs,
                on -> Config.minimapHostileMobs = on);
        }
        if (MINIMAP_PASSIVE_MOBS.isPressed()) {
            minimapToggle(
                mc,
                MINIMAP_PASSIVE_MOBS,
                mobs,
                () -> Config.minimapPassiveMobs,
                on -> Config.minimapPassiveMobs = on);
        }
        if (MINIMAP_AMBIENT_MOBS.isPressed()) {
            minimapToggle(
                mc,
                MINIMAP_AMBIENT_MOBS,
                mobs,
                () -> Config.minimapAmbientMobs,
                on -> Config.minimapAmbientMobs = on);
        }
        if (MINIMAP_FRIENDLY_MOBS.isPressed()) {
            minimapToggle(
                mc,
                MINIMAP_FRIENDLY_MOBS,
                mobs,
                () -> Config.minimapOtherEntities,
                on -> Config.minimapOtherEntities = on);
        }
        if (MINIMAP_PETS.isPressed()) {
            minimapToggle(mc, MINIMAP_PETS, mobs, () -> Config.minimapPets, on -> Config.minimapPets = on);
        }
        if (MINIMAP_PLAYERS.isPressed()) {
            minimapToggle(mc, MINIMAP_PLAYERS, mobs, () -> Config.minimapPlayers, on -> Config.minimapPlayers = on);
        }
        if (MINIMAP_LIGHT.isPressed()) {
            Config.ownMinimapView();
            Config.minimapLightMode = (Config.minimapLightMode + 1) % LIGHT_MODE_KEYS.length;
            Config.save();
            say(
                mc,
                Lang.format("key.wayfarmap.minimap_light.name") + ": "
                    + Lang.format("wayfarmap.option.minimap.light." + LIGHT_MODE_KEYS[Config.minimapLightMode]));
        }
    }

    /** {@code toggles}: pressed again, the view goes back to the 2D map. */
    private static void minimapView(Minecraft mc, KeyBinding key, int view, boolean toggles) {
        Config.ownMinimapView();
        boolean on = !toggles || Config.minimapView != view;
        Config.minimapView = on ? view : Config.MINIMAP_VIEW_FLAT;
        Config.save();
        tell(mc, key, toggles ? on : null);
    }

    private static void minimapToggle(Minecraft mc, KeyBinding key, Runnable own, BooleanSupplier shown,
        Consumer<Boolean> show) {
        own.run();
        boolean on = !shown.getAsBoolean();
        show.accept(on);
        Config.save();
        tell(mc, key, on);
    }

    /** False, with a message, if the mod the layer comes from isn't installed. */
    private static boolean installed(Minecraft mc, KeyBinding key, boolean available) {
        if (!available) {
            say(mc, Lang.format(key.getKeyDescription()) + ": " + Lang.format("key.wayfarmap.not_installed"));
        }
        return available;
    }

    /** The key's name, and on or off (nothing for a mode that is only set). */
    private static void tell(Minecraft mc, KeyBinding key, Boolean on) {
        String text = Lang.format(key.getKeyDescription());
        if (on != null) {
            text += ": " + Lang.format(on ? "options.on" : "options.off");
        }
        say(mc, text);
    }

    private static void say(Minecraft mc, String text) {
        IChatComponent message = new ChatComponentText(text);
        mc.ingameGUI.getChatGUI()
            .printChatMessageWithOptionalDeletion(message, CHAT_LINE);
    }
}
