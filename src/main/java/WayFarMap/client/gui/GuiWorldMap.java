package WayFarMap.client.gui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.MathHelper;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.Perf;
import WayFarMap.Tags;
import WayFarMap.WayFarMap;
import WayFarMap.client.IsoEntityDrawer;
import WayFarMap.client.KeyHandler;
import WayFarMap.client.Lang;
import WayFarMap.client.MapDrawer;
import WayFarMap.client.PlayerTrail;
import WayFarMap.client.TeamMates;
import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.integration.ClaimsLayer;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.PowerfailLayer;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.BiomeHighlight;
import WayFarMap.client.map.ChunkLoadClient;
import WayFarMap.client.map.ChunkLoadView;
import WayFarMap.client.map.MapCleaner;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;
import WayFarMap.client.map.Topography;
import WayFarMap.client.map.export.MapExport;
import WayFarMap.client.map.iso.IsoMap;
import WayFarMap.client.map.iso.IsoProjection;
import WayFarMap.client.waypoint.TeamWaypoints;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;

/** Fullscreen world map: drag to pan, mouse wheel to zoom. */
public class GuiWorldMap extends ScaledScreen {

    private static final int DEFAULT_ZOOM = 3;

    /** Zoom level is kept between openings of the map. */
    private static int zoomIndex = DEFAULT_ZOOM;

    private static final int HEADER_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 14;
    private static final float MARKER_SIZE = 12f;
    private static final float MIN_MARKER_SIZE = 6f;
    private static final int ID_WAYPOINTS = 0, ID_LIGHT = 1, ID_SETTINGS = 3, ID_CAVES = 4, ID_GRID = 6, ID_HELP = 10,
        ID_MOBS = 11, ID_ADDONS = 13, ID_TEAM = 14, ID_EXPORT = 17, ID_FOLLOW = 18, ID_STATS = 19, ID_MODES = 20,
        ID_CLOSE = 21, ID_ABOUT = 22;
    /** What the open menu is: the right click map menu, the mob filter, the add-on layers or teammates. */
    private static final int MENU_MAP = 0, MENU_MOBS = 1, MENU_ADDONS = 2, MENU_TEAM = 3, MENU_MODES = 5,
        MENU_CONFIRM = 6, MENU_WAYPOINT = 7;
    private static final int SLIDER_WIDTH = 10;
    private static final int MENU_WIDTH = 130, TEAM_MENU_WIDTH = 190, MENU_ROW = 14;
    private static final String[] CAVE_MODE_KEYS = { "auto", "off", "on" };
    /** Lang key suffixes of {@link Config#mapLightMode} values. */
    private static final String[] LIGHT_MODE_KEYS = { "auto", "day", "night" };

    /** How fast the zoom animation approaches the target zoom (higher is faster). */
    private static final double ZOOM_SPEED = 18.0;

    private double centerX;
    private double centerZ;
    /** Currently displayed scale; animates towards {@code Config.MAP_ZOOMS[zoomIndex]}. */
    private double scale = Config.MAP_ZOOMS[zoomIndex];
    private boolean initialized;

    /** World point that stays under {@link #anchorScreenX}/{@link #anchorScreenY} while the zoom animates. */
    private double anchorWorldX, anchorWorldZ, anchorScreenX, anchorScreenY;
    private boolean zooming;

    /** Longest glide of the view to a point, and how far (in screens) it starts from at most. */
    private static final int FLIGHT_MAX_MS = 650;
    private static final double FLIGHT_MAX_SCREENS = 1.5;
    /** Glide of the view to a point: from where it was to where it goes; flightStart is 0 when not gliding. */
    private double flightFromX, flightFromZ, flightToX, flightToZ;
    private long flightStart;
    private int flightMillis;

    /** Map lighting: auto, day or night, switched in turn. */
    private IconButton lightButton;
    private IconButton caveButton;
    /** The map's mode: 2D, 3D, topography or biomes, picked from a list. */
    private IconButton modesButton;
    private IconButton gridButton;
    /** Open the map at the player every time, or where it was closed. */
    private IconButton followButton;
    private IconButton mobsButton;
    /** 3D (isometric) view on or off (turned with Q / E). */
    /** Saves the whole map (flat or 3D) as a zoomable picture. */
    private IconButton exportButton;
    /** Add-on layers (ores, fluids, claims, power failures); null when none of those mods is installed. */
    private IconButton addonsButton;
    /** Online teammates, to jump to them; shown only while there are some. */
    private IconButton teamButton;
    /** Search of biomes, ore veins, fluids or power failures; kept between openings of the map. */
    private static String searchText = "";
    private FlatTextField searchField;
    private IconButton helpButton;
    private IconButton aboutButton;

    /** Chunks passed while dragging with Ctrl/Shift in the claims layer, applied on release. */
    private final Set<Long> claimSelection = new LinkedHashSet<>();
    private int claimButton = -1;
    private int claimAction;
    /** Chunk where the drag started: the selection is the rectangle from it to the chunk under the mouse. */
    private int claimStartX, claimStartZ, claimEndX = Integer.MIN_VALUE, claimEndZ;

    /** Dimension picker under the title; null when closed. */
    private List<MapManager.SavedDimension> dimensionList;
    private int dimensionListX, dimensionListWidth;
    /** Title bounds in the header, clickable to open the dimension picker. */
    private int titleX0, titleX1;

    /** Right click menu; null when closed. */
    private List<MenuEntry> menu;
    private int menuX, menuY;
    private int menuKind;
    private int menuWidth = MENU_WIDTH;
    /** Waypoint copied with the menu or Ctrl+C, pasted with the map's menu or Ctrl+V; null when nothing is copied. */
    private static Waypoint copiedWaypoint;
    private boolean draggingCaveSlider;

    /** One line of the right click menu. */
    private static final class MenuEntry {

        final String label;
        final boolean enabled;
        final Runnable action;
        /** Checkbox state for a toggle (the menu then stays open), null for a plain entry. */
        final Boolean checked;
        /** Small icon before the label, or null. */
        String[] icon;
        /** Muted text on the right: the key that does the same, or null. */
        String hint;
        /** Destructive (deleting): red, with a line above it when it follows other entries. */
        boolean danger;
        /** The current choice of a list (e.g. the map's mode): marked with the accent bar and color. */
        boolean selected;
        /** Color of the icon (ARGB), 0 for the usual muted one. */
        int iconColor;

        MenuEntry(String label, boolean enabled, Runnable action) {
            this(label, enabled, action, null);
        }

        MenuEntry(String label, boolean enabled, Runnable action, Boolean checked) {
            this.label = label;
            this.enabled = enabled;
            this.action = action;
            this.checked = checked;
        }

        MenuEntry icon(String[] icon) {
            this.icon = icon;
            return this;
        }

        /** The key bound to the mod's binding of that name, shown on the right; nothing if it has none. */
        MenuEntry key(String binding) {
            this.hint = KeyHandler.keyName(binding);
            return this;
        }

        /** Muted text on the right, e.g. a key combination. */
        MenuEntry hint(String hint) {
            this.hint = hint;
            return this;
        }

        MenuEntry danger() {
            this.danger = true;
            return this;
        }

        MenuEntry selected(boolean selected) {
            this.selected = selected;
            return this;
        }

        MenuEntry iconColor(int color) {
            this.iconColor = color;
            return this;
        }
    }

    private boolean dragging;
    private int lastRawMouseX;
    private int lastRawMouseY;
    private long lastFrameNanos;
    private int ticks;
    /** Waypoint to show in the middle of the map when it opens, or null. */
    private Waypoint focus;

    /** The world map opened on the waypoint, in its dimension. */
    public static GuiWorldMap showing(Waypoint waypoint) {
        GuiWorldMap map = new GuiWorldMap();
        map.focus = waypoint;
        return map;
    }

    @Override
    public void initGui() {
        super.initGui();
        checkWelcome();
        updateSurfaceView();
        if (!initialized && mc.thePlayer != null) {
            // Only on first open, not when the window is resized: back where the map was closed (unless it follows
            // the player), or at the player.
            initialized = true;
            MapManager.INSTANCE.stopViewing();
            if (Config.mapFollowPlayer || !restoreView()) {
                centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
            }
            if (focus != null) {
                centerOnWaypoint(focus);
                focus = null;
            }
            if (Mods.isVisualProspectingLoaded()) {
                ProspectingLayer.onOpenMap();
            }
        }
        lastFrameNanos = System.nanoTime();
        buttonList.clear();
        // Header: icons with tooltips. Left: settings, waypoints, add-on layers; right (before the zoom text):
        // mobs, grid, biomes, caves, day, night.
        int x = 4;
        String settingsName = Lang.format("wayfarmap.gui.settings");
        IconButton settingsButton = new IconButton(ID_SETTINGS, x, 4, Icons.SETTINGS, settingsName);
        settingsButton.iconColor = COLOR_SETTINGS;
        x = addIconButton(settingsButton, x);
        // The others can be hidden in the settings; the settings button and the dimension title always stay.
        if (Config.isMapButtonShown("waypoints")) {
            IconButton waypointsButton = new IconButton(
                ID_WAYPOINTS,
                x,
                4,
                Icons.WAYPOINTS,
                Lang.format("wayfarmap.gui.waypoints"));
            waypointsButton.iconColor = COLOR_WAYPOINTS;
            x = addIconButton(waypointsButton, x);
        }
        if (Config.isMapButtonShown("stats")) {
            IconButton statsButton = new IconButton(ID_STATS, x, 4, Icons.STATS, Lang.format("wayfarmap.gui.data"));
            statsButton.iconColor = COLOR_STATS;
            x = addIconButton(statsButton, x);
        }
        exportButton = new IconButton(ID_EXPORT, x, 4, Icons.CAMERA, Lang.format("wayfarmap.gui.export"));
        exportButton.iconColor = COLOR_EXPORT;
        if (Config.isMapButtonShown("export")) {
            x = addIconButton(exportButton, x);
        }
        addonsButton = null;
        if (Config.isMapButtonShown("addons") && (Mods.isVisualProspectingLoaded() || Mods.isClaimsAvailable()
            || Mods.isPowerfailsAvailable()
            || Mods.isThaumcraftNodesAvailable())) {
            addonsButton = new IconButton(ID_ADDONS, x, 4, Icons.ADDONS, Lang.format("wayfarmap.gui.addons"));
            addonsButton.iconColor = COLOR_ADDONS;
            addIconButton(addonsButton, x);
        }

        lightButton = new IconButton(ID_LIGHT, 0, 4, Icons.DAY_NIGHT, "");
        caveButton = new IconButton(ID_CAVES, 0, 4, Icons.CAVES, "");
        modesButton = new IconButton(ID_MODES, 0, 4, Icons.MAP2D, "");
        gridButton = new IconButton(ID_GRID, 0, 4, Icons.GRID, Lang.format("wayfarmap.gui.grid"));
        followButton = new IconButton(ID_FOLLOW, 0, 4, Icons.FOLLOW, Lang.format("wayfarmap.gui.follow"));
        mobsButton = new IconButton(ID_MOBS, 0, 4, Icons.MOBS, "");
        teamButton = new IconButton(ID_TEAM, 0, 4, Icons.TEAM, Lang.format("wayfarmap.gui.team"));
        caveButton.iconColor = COLOR_CAVES;
        gridButton.iconColor = COLOR_GRID;
        followButton.iconColor = COLOR_FOLLOW;
        mobsButton.iconColor = COLOR_MOBS;
        teamButton.iconColor = COLOR_TEAM;
        teamButton.visible = teamShown();
        followButton.visible = Config.isMapButtonShown("follow");
        lightButton.visible = Config.isMapButtonShown("light");
        caveButton.visible = Config.isMapButtonShown("caves");
        modesButton.visible = Config.isMapButtonShown("modes");
        gridButton.visible = Config.isMapButtonShown("grid");
        mobsButton.visible = Config.isMapButtonShown("mobs");
        for (IconButton button : rightButtons()) {
            buttonList.add(button);
        }
        updateLightButtons();
        menu = null;
        dimensionList = null;

        // Top right corner: closes the map, like Esc.
        buttonList.add(new IconButton(ID_CLOSE, width - 24, 4, Icons.CLOSE, Lang.format("wayfarmap.gui.close")));

        // Bottom right corner: about the mod, its author and links.
        aboutButton = new IconButton(
            ID_ABOUT,
            width - 22,
            height - FOOTER_HEIGHT + 1,
            Icons.ABOUT,
            Lang.format("wayfarmap.gui.about_button"));
        aboutButton.setHeight(13);
        aboutButton.iconColor = COLOR_HELP;
        aboutButton.visible = Config.isMapButtonShown("about");
        buttonList.add(aboutButton);
        // Next to it: the help screen with every feature explained.
        helpButton = new IconButton(
            ID_HELP,
            aboutButton.visible ? width - 44 : width - 22,
            height - FOOTER_HEIGHT + 1,
            Icons.HELP,
            Lang.format("wayfarmap.gui.help_button"));
        helpButton.setHeight(13);
        helpButton.iconColor = COLOR_HELP;
        helpButton.visible = Config.isMapButtonShown("help");
        buttonList.add(helpButton);

        Keyboard.enableRepeatEvents(true);
        searchField = new FlatTextField(fontRendererObj, width / 2 - 90, HEADER_HEIGHT + 4, 180, 14)
            .setHint(Lang.format("wayfarmap.gui.search_hint"))
            .setOnCleared(() -> {
                searchText = "";
                applySearch();
            });
        searchField.setMaxStringLength(40);
        searchField.setText(searchText);
        applySearch();
    }

    /** The teammates button: while some are online, unless hidden in the settings. */
    private boolean teamShown() {
        return Config.isMapButtonShown("team") && !TeamMates.INSTANCE.all()
            .isEmpty();
    }

    /** Buttons on the right of the header, from the right edge to the left. */
    private IconButton[] rightButtons() {
        return new IconButton[] { followButton, lightButton, caveButton, modesButton, gridButton, mobsButton,
            teamButton };
    }

    /** Places the right header buttons next to each other, leaving out hidden ones. */
    private void layoutRightButtons() {
        // Left of the zoom text, which is left of the close button.
        int right = width - 58;
        for (IconButton button : rightButtons()) {
            if (!button.visible) {
                continue;
            }
            right -= button.getWidth();
            button.xPosition = right;
            right -= 3;
        }
    }

    // ---------------------------------------------------------------- 3D view

    /** True when the map is drawn in 3D: the surface (biome view and cave layers stay flat). */
    private boolean isoShown() {
        return Config.isometric && !columnViewShown() && MapManager.INSTANCE.getViewCaveLayer() < 0;
    }

    private static IsoProjection isoProjection() {
        return IsoProjection.of(Config.isoRotation);
    }

    /** World move {dx, dz} that shows as the screen move (ox, oy); in 3D on the plane at sea level. */
    private double[] screenToWorldOffset(double ox, double oy) {
        if (isoShown()) {
            IsoProjection p = isoProjection();
            return p.ground(ox / scale, oy / scale / IsoProjection.SIN);
        }
        return new double[] { ox / scale, oy / scale };
    }

    /** Screen position {x, y} of a world point. */
    private double[] toScreen(double x, double y, double z) {
        if (isoShown()) {
            IsoProjection p = isoProjection();
            return new double[] { width / 2.0 + (p.u(x, z) - p.u(centerX, centerZ)) * scale,
                height / 2.0 + (p.v(x, y, z) - p.v(centerX, IsoProjection.REFERENCE_Y, centerZ)) * scale };
        }
        return new double[] { width / 2.0 + (x - centerX) * scale, height / 2.0 + (z - centerZ) * scale };
    }

    /**
     * Block under the screen point: {x, y, z}, where y is only known in 3D (the block seen there; -1 otherwise).
     * In 3D where nothing is drawn, the point at sea level.
     */
    private int[] blockAt(int mouseX, int mouseY) {
        if (isoShown()) {
            int[] hit = IsoMap.INSTANCE.pick(
                viewDimension(),
                Config.isoRotation,
                centerX,
                centerZ,
                scale,
                ScaledScreen.currentFactor(),
                mouseX - width / 2.0,
                mouseY - height / 2.0);
            if (hit != null) {
                return hit;
            }
            IsoProjection p = isoProjection();
            double[] ground = p.unproject(
                p.u(centerX, centerZ) + (mouseX - width / 2.0) / scale,
                p.v(centerX, IsoProjection.REFERENCE_Y, centerZ) + (mouseY - height / 2.0) / scale,
                IsoProjection.REFERENCE_Y);
            return new int[] { MathHelper.floor_double(ground[0]), -1, MathHelper.floor_double(ground[1]) };
        }
        return new int[] { MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale), -1,
            MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) };
    }

    /** Puts a world point in the middle of the screen (in 3D the point itself, not the ground below it). */
    private void centerOn(double x, double y, double z) {
        flightStart = 0;
        if (isoShown()) {
            IsoProjection p = isoProjection();
            double toward = p.toward(x, z) + (IsoProjection.REFERENCE_Y - y) * IsoProjection.COS / IsoProjection.SIN;
            double[] center = p.ground(p.u(x, z), toward);
            centerX = center[0];
            centerZ = center[1];
        } else {
            centerX = x;
            centerZ = z;
        }
    }

    /**
     * Glides the view to a world point, like {@link #centerOn} but over a moment: quicker for short ways, eased at
     * both ends. From far away it starts a screen and a half off, so the way there isn't a blur of maps to load.
     */
    private void flyTo(double x, double y, double z) {
        double fromX = centerX, fromZ = centerZ;
        centerOn(x, y, z);
        double toX = centerX, toZ = centerZ;
        double pixels = Math.hypot(toX - fromX, toZ - fromZ) * scale;
        if (!Config.mapSmoothCamera || pixels < 1) {
            return;
        }
        double far = FLIGHT_MAX_SCREENS * Math.max(width, height);
        if (pixels > far) {
            double part = far / pixels;
            fromX = toX + (fromX - toX) * part;
            fromZ = toZ + (fromZ - toZ) * part;
            pixels = far;
        }
        flightFromX = fromX;
        flightFromZ = fromZ;
        flightToX = toX;
        flightToZ = toZ;
        flightMillis = (int) Math.min(FLIGHT_MAX_MS, 200 + Math.sqrt(pixels) * 14);
        flightStart = System.currentTimeMillis();
        centerX = fromX;
        centerZ = fromZ;
    }

    /** The search field shows up in biome view and with the ore vein or fluid layer (not in 3D). */
    private boolean searchAvailable() {
        // Not in the area loading view: it shows no layers, and its switch is where the field would be.
        return !isoShown() && !chunkloadShown()
            && (biomeViewShown() || prospectingLayerShown() || powerfailsShown() || nodesShown());
    }

    private static boolean nodesShown() {
        return Config.showThaumcraftNodes && Mods.isThaumcraftNodesAvailable();
    }

    private static boolean powerfailsShown() {
        return Config.showPowerfails && Mods.isPowerfailsAvailable();
    }

    private boolean biomeViewShown() {
        return Config.mapDisplayMode == Config.DISPLAY_BIOMES;
    }

    /** Biomes or topography: drawn for whole columns from the surface, so without cave layers and not in 3D. */
    private boolean columnViewShown() {
        return biomeViewShown() || Topography.isShown();
    }

    private static boolean prospectingLayerShown() {
        return Mods.isVisualProspectingLoaded() && (Config.showOreVeins || Config.showUndergroundFluids);
    }

    /** Sends the search text to the layers that are shown; the others search for nothing. */
    private void applySearch() {
        String query = searchAvailable() ? searchText : "";
        BiomeHighlight.setQuery(biomeViewShown() ? query : "");
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.setSearch(prospectingLayerShown() ? query : "");
        }
        if (Mods.isPowerfailsAvailable()) {
            PowerfailLayer.setSearch(powerfailsShown() ? query : "");
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            ThaumcraftNodes.setSearch(nodesShown() ? query : "");
        }
    }

    private int addIconButton(IconButton button, int x) {
        buttonList.add(button);
        return x + button.getWidth() + 3;
    }

    /** Menu under the add-ons button: a checkbox per layer; it stays open to switch several. */
    private void openAddonsMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        if (Mods.isVisualProspectingLoaded()) {
            entries.add(
                addonToggle("wayfarmap.gui.ores", Config.showOreVeins, Config::toggleOreVeins).icon(Icons.ORE)
                    .iconColor(0xFF5BD6E0)
                    .key("ores"));
            entries.add(
                addonToggle("wayfarmap.gui.fluids", Config.showUndergroundFluids, Config::toggleUndergroundFluids)
                    .icon(Icons.SMALL_DROP)
                    .iconColor(0xFF3F8FE8)
                    .key("fluids"));
        }
        if (Mods.isClaimsAvailable()) {
            entries.add(addonToggle("wayfarmap.gui.claims", Config.showClaims, () -> {
                Config.toggleClaims();
                if (Config.showClaims) {
                    ClaimsLayer.onShow();
                }
            }).icon(Icons.CLAIM)
                .iconColor(0xFFF2C14E)
                .key("claims"));
        }
        if (Mods.isPowerfailsAvailable()) {
            entries.add(
                addonToggle("wayfarmap.gui.powerfails", Config.showPowerfails, Config::togglePowerfails)
                    .icon(Icons.POWER)
                    .iconColor(0xFFFFD23F)
                    .key("powerfails"));
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            entries.add(
                addonToggle("wayfarmap.gui.nodes", Config.showThaumcraftNodes, Config::toggleThaumcraftNodes)
                    .icon(Icons.NODE)
                    .iconColor(0xFFC08CFF)
                    .key("nodes"));
        }
        menu = entries;
        menuKind = MENU_ADDONS;
        menuShown();
        menuWidth = menuWidthFor(entries, MENU_WIDTH);
        menuX = Math.max(2, Math.min(addonsButton.xPosition, width - menuWidth - 2));
        menuY = addonsButton.yPosition + 18;
    }

    private MenuEntry addonToggle(String key, boolean on, Runnable toggle) {
        return new MenuEntry(Lang.format(key), true, () -> {
            toggle.run();
            updateLightButtons();
        }, on);
    }

    /** "Mobs: neutral, ambient, friendly, pets, hostile, players", or that none are shown. */
    private static String mobsButtonText() {
        List<String> shown = new ArrayList<>();
        if (Config.showPassiveMobs) {
            shown.add(Lang.format("wayfarmap.gui.mobs.neutral"));
        }
        if (Config.showAmbientMobs) {
            shown.add(Lang.format("wayfarmap.gui.mobs.ambient"));
        }
        if (Config.showOtherEntities) {
            shown.add(Lang.format("wayfarmap.gui.mobs.friendly"));
        }
        if (Config.showPets) {
            shown.add(Lang.format("wayfarmap.gui.mobs.pets"));
        }
        if (Config.showHostileMobs) {
            shown.add(Lang.format("wayfarmap.gui.mobs.hostile"));
        }
        if (Config.showOtherPlayers) {
            shown.add(Lang.format("wayfarmap.gui.mobs.players"));
        }
        return Lang.format("wayfarmap.gui.mobs") + ": "
            + (shown.isEmpty() ? Lang.format("wayfarmap.gui.mobs.none") : String.join(", ", shown));
    }

    /** Menu under the "Mobs" button: a checkbox for each kind of mob and for players; all off shows none. */
    private void openMobsMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.neutral", Config.showPassiveMobs, Config::toggleNeutralMobs)
                .icon(Icons.SMALL_NEUTRAL)
                .iconColor(Theme.TEXT)
                .key("passive_mobs"));
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.ambient", Config.showAmbientMobs, Config::toggleAmbientMobs)
                .icon(Icons.SMALL_BAT)
                .iconColor(0xFFB57EDC)
                .key("ambient_mobs"));
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.friendly", Config.showOtherEntities, Config::toggleFriendlyMobs)
                .icon(Icons.SMALL_HEART)
                .iconColor(Theme.SUCCESS)
                .key("friendly_mobs"));
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.pets", Config.showPets, Config::togglePets).icon(Icons.SMALL_PAW)
                .iconColor(0xFFF2C14E)
                .key("pets"));
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.hostile", Config.showHostileMobs, Config::toggleHostileMobs)
                .icon(Icons.SMALL_CREEPER)
                .iconColor(Theme.DANGER)
                .key("hostile_mobs"));
        entries.add(
            addonToggle("wayfarmap.gui.mobs.menu.players", Config.showOtherPlayers, Config::toggleOtherPlayers)
                .icon(Icons.SMALL_PERSON)
                .iconColor(Theme.ACCENT)
                .key("players"));
        menu = entries;
        menuKind = MENU_MOBS;
        menuShown();
        menuWidth = menuWidthFor(entries, MENU_WIDTH);
        menuX = Math.max(2, Math.min(mobsButton.xPosition, width - menuWidth - 2));
        menuY = mobsButton.yPosition + 18;
    }

    /** Opens the pictures of the map: making one of what the map shows, and the ones made so far. */
    private void openPictures() {
        String what;
        int caveLayer = MapManager.INSTANCE.getViewCaveLayer();
        if (biomeViewShown()) {
            what = "biomes";
        } else if (Topography.isShown()) {
            what = "topo";
        } else if (caveLayer >= 0) {
            what = "caves_" + caveLayer * 16 + "-" + (caveLayer * 16 + 15);
        } else {
            what = Config.showPlants ? "2d" : "bare";
        }
        File world = MapManager.INSTANCE.getWorldDirectory();
        String name = (world == null ? "map" : world.getName()) + "_" + MapManager.INSTANCE.getViewedDimensionName();
        mc.displayGuiScreen(
            new GuiMapPictures(
                this,
                viewDimension(),
                isoShown(),
                what,
                name,
                MapManager.INSTANCE.getViewedDimensionName()));
    }

    private static String caveButtonText() {
        return Lang.format("wayfarmap.gui.caves") + ": "
            + Lang.format("wayfarmap.option.map.caveMode." + CAVE_MODE_KEYS[Config.caveMode]);
    }

    /** The forced mode is highlighted; with both off the map follows the time of day. */
    private void updateLightButtons() {
        if (searchField != null) {
            // The layers shown may have changed; send them the search.
            applySearch();
        }
        // Lighting: the sun or the moon when fixed, a half dark circle with a dot when automatic.
        int light = Config.mapLightMode >= 0 && Config.mapLightMode < LIGHT_MODE_KEYS.length ? Config.mapLightMode
            : Config.LIGHT_AUTO;
        lightButton.icon = light == Config.LIGHT_DAY ? Icons.DAY
            : light == Config.LIGHT_NIGHT ? Icons.NIGHT : Icons.DAY_NIGHT;
        lightButton.iconColor = light == Config.LIGHT_NIGHT ? COLOR_MOON : COLOR_SUN;
        lightButton.active = light != Config.LIGHT_AUTO;
        lightButton.badge = light == Config.LIGHT_AUTO ? Theme.ACCENT : 0;
        lightButton.tooltip = Lang.format("wayfarmap.option.map.lightMode") + ": "
            + Lang.format("wayfarmap.option.map.lightMode." + LIGHT_MODE_KEYS[light]);
        // Caves: highlighted when on, a dot when automatic, dimmed when off.
        caveButton.active = Config.caveMode == Config.CAVES_ON;
        caveButton.dim = Config.caveMode == Config.CAVES_OFF;
        caveButton.badge = Config.caveMode == Config.CAVES_AUTO ? Theme.ACCENT : 0;
        caveButton.tooltip = caveButtonText();
        int mode = currentMode();
        modesButton.icon = MODE_ICONS[mode];
        modesButton.iconColor = MODE_COLORS[mode];
        modesButton.active = mode != MODE_FLAT;
        modesButton.tooltip = Lang.format("wayfarmap.gui.modes") + ": "
            + Lang.format("wayfarmap.gui.modes." + MODE_KEYS[mode]);
        gridButton.active = Config.chunkGrid;
        followButton.active = Config.mapFollowPlayer;
        layoutRightButtons();
        // Mobs: highlighted while some are hidden, dim when none are shown; a dot when only hostile or only
        // peaceful mobs are left.
        boolean peaceful = Config.showPassiveMobs || Config.showAmbientMobs
            || Config.showOtherEntities
            || Config.showPets;
        boolean hostile = Config.showHostileMobs;
        mobsButton.active = !Config.allMobsShown();
        mobsButton.dim = Config.noMobsShown();
        mobsButton.badge = peaceful && !hostile ? Theme.SUCCESS : hostile && !peaceful ? Theme.DANGER : 0;
        mobsButton.tooltip = mobsButtonText();
        if (addonsButton != null) {
            addonsButton.active = prospectingLayerShown() || Config.showClaims && Mods.isClaimsAvailable()
                || powerfailsShown()
                || nodesShown();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_WAYPOINTS) {
            mc.displayGuiScreen(new GuiWaypointList(this));
        } else if (button.id == ID_STATS) {
            mc.displayGuiScreen(new GuiMapData(this));
        } else if (button.id == ID_TEAM) {
            openTeamMenu();
        } else if (button.id == ID_ADDONS) {
            openAddonsMenu();
        } else if (button.id == ID_FOLLOW) {
            Config.toggleFollowPlayer();
            updateLightButtons();
            if (Config.mapFollowPlayer) {
                // Turned on: show the player now, as the map will be every time it opens.
                showDimension(mc.theWorld.provider.dimensionId);
                flyTo(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
                zooming = false;
            }
        } else if (button.id == ID_GRID) {
            Config.toggleChunkGrid();
            updateLightButtons();
        } else if (button.id == ID_MODES) {
            openModesMenu();
        } else if (button.id == ID_EXPORT) {
            openPictures();
        } else if (button.id == ID_CAVES) {
            Config.cycleCaveMode();
            updateLightButtons();
        } else if (button.id == ID_SETTINGS) {
            mc.displayGuiScreen(new GuiSettings(this));
        } else if (button.id == ID_MOBS) {
            openMobsMenu();
        } else if (button.id == ID_CLOSE) {
            mc.displayGuiScreen(null);
        } else if (button.id == ID_HELP) {
            mc.displayGuiScreen(new GuiHelp(this));
        } else if (button.id == ID_ABOUT) {
            mc.displayGuiScreen(new GuiAbout(this));
        } else if (button.id == ID_LIGHT) {
            // Auto -> day -> night -> auto.
            Config.setMapLightMode(
                Config.mapLightMode == Config.LIGHT_AUTO ? Config.LIGHT_DAY
                    : Config.mapLightMode == Config.LIGHT_DAY ? Config.LIGHT_NIGHT : Config.LIGHT_AUTO);
            updateLightButtons();
        }
    }

    /** Moves and zooms the view once per frame, so panning is as smooth as the frame rate allows. */
    private void updateView() {
        long now = System.nanoTime();
        double seconds = Math.min(0.1, (now - lastFrameNanos) / 1.0e9);
        lastFrameNanos = now;

        if (flightStart != 0) {
            float t = Math.min(1f, (System.currentTimeMillis() - flightStart) / (float) flightMillis);
            // Eased in and out: sets off softly and comes to rest softly.
            double eased = t < 0.5f ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            centerX = flightFromX + (flightToX - flightFromX) * eased;
            centerZ = flightFromZ + (flightToZ - flightFromZ) * eased;
            if (t >= 1f) {
                flightStart = 0;
            }
        }

        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                dragging = false;
            } else {
                // Raw window coordinates give sub-GUI-pixel precision at any GUI scale.
                int rawX = Mouse.getX();
                int rawY = Mouse.getY();
                double dx = (rawX - lastRawMouseX) * (double) width / mc.displayWidth;
                double dy = -(rawY - lastRawMouseY) * (double) height / mc.displayHeight;
                lastRawMouseX = rawX;
                lastRawMouseY = rawY;
                double[] move = screenToWorldOffset(dx, dy);
                centerX -= move[0];
                centerZ -= move[1];
                if (zooming) {
                    anchorWorldX -= move[0];
                    anchorWorldZ -= move[1];
                }
            }
        }

        if (zooming) {
            double target = Config.MAP_ZOOMS[zoomIndex];
            // Interpolate in log space so every zoom step feels equally fast.
            double t = 1.0 - Math.exp(-ZOOM_SPEED * seconds);
            double logScale = Math.log(scale) + (Math.log(target) - Math.log(scale)) * t;
            scale = Math.exp(logScale);
            if (Math.abs(scale - target) < target * 0.002) {
                scale = target;
                zooming = false;
            }
            double[] offset = screenToWorldOffset(anchorScreenX - width / 2.0, anchorScreenY - height / 2.0);
            centerX = anchorWorldX - offset[0];
            centerZ = anchorWorldZ - offset[1];
        }
    }

    /** Switches between the flat map and the 3D view, keeping the same place in the middle. */
    /**
     * Modes of the map, in the order of the list: flat in block colors, 3D, flat without grass and flowers,
     * topography, biomes.
     */
    private static final int MODE_FLAT = 0, MODE_ISO = 1, MODE_BARE = 2, MODE_TOPO = 3, MODE_BIOMES = 4,
        MODE_CHUNKLOAD = 5;
    private static final String[] MODE_KEYS = { "flat", "iso", "bare", "topo", "biomes", "chunkload" };
    private static final String[][] MODE_ICONS = { Icons.MAP2D, Icons.ISO, Icons.NO_PLANTS, Icons.TOPO, Icons.BIOMES,
        Icons.CHUNKLOAD };
    /** Each mode's color, on its icon in the modes menu and on the modes button. */
    private static final int[] MODE_COLORS = { 0xFF5BD6E0, 0xFFE8A040, 0xFFE87AA0, 0xFF4FC3A8, 0xFF8BD450, 0xFFC08CFF };
    /** Colors of the header's icons. */
    private static final int COLOR_SETTINGS = 0xFFAAB4C3, COLOR_WAYPOINTS = 0xFFE5534B, COLOR_STATS = 0xFF3FB950,
        COLOR_EXPORT = 0xFFF2C14E, COLOR_ADDONS = 0xFF5BD6E0, COLOR_CAVES = 0xFFC8A070, COLOR_GRID = 0xFF7FB2FF,
        COLOR_FOLLOW = 0xFFFF7B72, COLOR_MOBS = 0xFF6CC24A, COLOR_TEAM = 0xFF4C9AFF, COLOR_SUN = 0xFFFFD23F,
        COLOR_MOON = 0xFFB9C7FF, COLOR_HELP = 0xFF4C9AFF;
    /**
     * The area loading view: the flat map with the chunks on it, those saved in the world and those picked to be
     * loaded.
     */
    private static boolean chunkloadView;
    /** Height of the buttons of the area loading toolbar. */
    private static final int LOAD_BAR_HEIGHT = 14;

    private static int currentMode() {
        if (Config.mapDisplayMode == Config.DISPLAY_BIOMES) {
            return MODE_BIOMES;
        }
        if (Config.mapDisplayMode == Config.DISPLAY_TOPO) {
            return MODE_TOPO;
        }
        if (Config.isometric) {
            return MODE_ISO;
        }
        return chunkloadView ? MODE_CHUNKLOAD : Config.showPlants ? MODE_FLAT : MODE_BARE;
    }

    /**
     * Menu under the modes button: 2D map, 3D map, 2D map without plants, topography, biomes; the current one is
     * marked.
     */
    private void openModesMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        int current = currentMode();
        for (int mode = 0; mode < MODE_KEYS.length; mode++) {
            if (mode == MODE_CHUNKLOAD && !ChunkLoadView.isAllowed()) {
                // Loading from the map is for operators: the mode isn't offered to others.
                continue;
            }
            final int value = mode;
            String label = Lang.format("wayfarmap.gui.modes." + MODE_KEYS[mode]);
            entries.add(
                new MenuEntry(label, true, () -> setMode(value)).icon(MODE_ICONS[mode])
                    .iconColor(MODE_COLORS[mode])
                    .selected(mode == current));
        }
        menu = entries;
        menuKind = MENU_MODES;
        menuShown();
        menuWidth = menuWidthFor(entries, MENU_WIDTH);
        menuX = Math.max(2, Math.min(modesButton.xPosition, width - menuWidth - 2));
        menuY = modesButton.yPosition + 18;
    }

    /** Switches the map's mode, keeping the point in the middle of the screen there (2D and 3D place it apart). */
    private void setMode(int mode) {
        double[] middle = middlePoint();
        int display = mode == MODE_TOPO ? Config.DISPLAY_TOPO
            : mode == MODE_BIOMES ? Config.DISPLAY_BIOMES : Config.DISPLAY_BLOCKS;
        Config.setMapMode(mode == MODE_ISO, display);
        // The map without grass and flowers is kept along with the surface: switching only picks the one drawn.
        Config.setShowPlants(mode != MODE_BARE);
        chunkloadView = mode == MODE_CHUNKLOAD;
        updateSurfaceView();
        centerOn(middle[0], middle[1], middle[2]);
        zooming = false;
        updateLightButtons();
        applySearch();
    }

    /** Turns the 3D view by quarters around the point in the middle of the screen. */
    private void rotateIso(int quarters) {
        double[] middle = middlePoint();
        Config.rotateIso(quarters);
        centerOn(middle[0], middle[1], middle[2]);
        zooming = false;
    }

    /**
     * The world point in the middle of the screen: in 3D the block seen there, on the flat map the ground from its
     * heights (sea level where unknown).
     */
    private double[] middlePoint() {
        if (isoShown()) {
            int[] block = blockAt(width / 2, height / 2);
            if (block[1] >= 0) {
                return new double[] { block[0] + 0.5, block[1] + 1, block[2] + 0.5 };
            }
            return new double[] { centerX, IsoProjection.REFERENCE_Y, centerZ };
        }
        int ground = MapManager.INSTANCE
            .getViewSurfaceHeight(MathHelper.floor_double(centerX), MathHelper.floor_double(centerZ));
        return new double[] { centerX, ground > 0 ? ground : IsoProjection.REFERENCE_Y, centerZ };
    }

    // ---------------------------------------------------------------- welcome window

    /**
     * Written when the welcome window is closed, with the mod's version then: the window shows again when the mod
     * is newer than that. Empty when it was written before the version was kept.
     */
    private static final String WELCOME_FILE = "welcome-shown";
    /** The welcome window is open: the map takes no input until it is closed. */
    private boolean welcome;
    private WelcomeWindow welcomeWindow;
    /** The welcome window was closed while this map was open: not shown again when the map is laid out again. */
    private boolean welcomeWindowClosed;

    private File welcomeFile() {
        return new File(new File(mc.mcDataDir, "wayfarmap"), WELCOME_FILE);
    }

    /**
     * The version the welcome window was last closed in: null if it never was (the mod was just installed), empty
     * if it was before the version was kept.
     */
    private String seenVersion() {
        File file = welcomeFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read " + file, e);
            // Not shown again and again only because the file can't be read.
            return Tags.VERSION;
        }
    }

    /**
     * Opens the welcome window the first time the map is opened after installing the mod, and again after it is
     * updated. The first time, the mod's language is set to the game's if the mod has it.
     */
    private void checkWelcome() {
        if (welcomeWindow != null) {
            welcome = !welcomeWindowClosed;
            return;
        }
        String seen = seenVersion();
        boolean firstRun = seen == null;
        boolean updated = !firstRun && Changelog.compare(seen, Tags.VERSION) < 0;
        welcome = firstRun || updated;
        if (!welcome) {
            return;
        }
        if (firstRun) {
            guessLanguage();
        }
        welcomeWindow = new WelcomeWindow(fontRendererObj, updated ? seen : null);
    }

    /** The mod's language as the game's, if the mod has it and none was chosen yet. */
    private void guessLanguage() {
        if (Config.modLanguage != 0 || mc.gameSettings == null || mc.gameSettings.language == null) {
            return;
        }
        for (int i = 0; i < Lang.CODES.length; i++) {
            if (Lang.CODES[i].equalsIgnoreCase(mc.gameSettings.language)) {
                Config.modLanguage = i;
                Config.save();
                return;
            }
        }
    }

    /** Closes the welcome window and keeps the version, so it isn't shown again until the mod is updated. */
    private void closeWelcome() {
        welcome = false;
        welcomeWindowClosed = true;
        File file = welcomeFile();
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), Tags.VERSION.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not write " + file, e);
        }
    }

    /** Acts on what a click or a key did in the welcome window. */
    private void onWelcome(WelcomeWindow.Click click) {
        switch (click) {
            case CLOSE:
                closeWelcome();
                break;
            case FINISH:
                // After the language is chosen, what's new in it: on the first run too, not only after an update.
                closeWelcome();
                mc.displayGuiScreen(new GuiChangelog(this, welcomeWindow.updatedFrom()));
                break;
            case HELP:
                closeWelcome();
                mc.displayGuiScreen(new GuiHelp(this));
                break;
            case CHANGELOG:
                // Over the window, which stays open under it.
                mc.displayGuiScreen(new GuiChangelog(this, welcomeWindow.updatedFrom()));
                break;
            default:
        }
    }

    /** The welcome window over the dimmed map; the help button it points to is outlined meanwhile. */
    private void drawWelcome(int mouseX, int mouseY) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        if (helpButton != null && helpButton.visible) {
            // Pulsing outline around the help button the window points to.
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 250.0);
            int alpha = 0x60 + (int) (0x9F * pulse);
            int x0 = helpButton.xPosition - 2, y0 = helpButton.yPosition - 2;
            int color = alpha << 24 | (Theme.ACCENT & 0xFFFFFF);
            Theme.outline(x0, y0, x0 + helpButton.getWidth() + 4, y0 + 13 + 4, color);
        }
        welcomeWindow.draw(width, height, mouseX, mouseY);
    }

    /** Whether the world map is on screen: open, or drawn behind one of the mod's screens opened over it. */
    public static boolean isVisible(Minecraft mc) {
        GuiScreen screen = mc.currentScreen;
        return screen instanceof GuiWorldMap
            || screen instanceof ScaledScreen && ((ScaledScreen) screen).showsBehind(GuiWorldMap.class);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        long perf = Perf.start();
        try {
            drawWindow(mouseX, mouseY, partialTicks);
        } finally {
            Perf.end(Perf.Part.WORLD_MAP, perf);
        }
    }

    private void drawWindow(int mouseX, int mouseY, float partialTicks) {
        if (isDrawnBehind()) {
            // Behind another screen: drawn as when open (the surface view it sets then), and never dragged.
            dragging = false;
            boolean surface = MapManager.INSTANCE.isSurfaceView();
            updateSurfaceView();
            try {
                drawMap(mouseX, mouseY, partialTicks);
            } finally {
                MapManager.INSTANCE.setSurfaceView(surface);
            }
            return;
        }
        drawMap(mouseX, mouseY, partialTicks);
        if (welcome) {
            drawWelcome(mouseX, mouseY);
        }
    }

    private void drawMap(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF0C0E11);

        MapDimension dimension = MapManager.INSTANCE.getViewMap();
        if (dimension == null || mc.thePlayer == null) {
            super.drawScaled(mouseX, mouseY, partialTicks);
            return;
        }
        // Teammates come and go while the map is open.
        boolean teammates = teamShown();
        if (teammates != teamButton.visible) {
            teamButton.visible = teammates;
            layoutRightButtons();
        }
        if (!teamButton.visible && menuKind == MENU_TEAM) {
            menu = null;
        }

        updateView();
        // The dimension shown: the player's, or another one picked from the title.
        int dimensionId = viewDimension();
        boolean otherDimension = MapManager.INSTANCE.isViewingOtherDimension();
        boolean iso = isoShown();
        if (iso) {
            // 3D: the terrain; the mobs and the player come below. The add-on layers and the grid are flat only.
            IsoMap.INSTANCE.draw(
                dimensionId,
                Config.isoRotation,
                centerX,
                centerZ,
                scale,
                ScaledScreen.currentFactor(),
                0,
                0,
                width,
                height);
        } else {
            drawFlatLayers(dimension, dimensionId, otherDimension, mouseX, mouseY, partialTicks);
        }
        // Teammates always, also in another dimension being looked at.
        if (!chunkloadShown()) {
            // In 3D the teammates the client has nearby are drawn as their model with the mobs, not as a head.
            MapDrawer.drawTeammates(
                mc,
                dimensionId,
                this::toScreen,
                scale,
                0,
                0,
                width,
                height,
                partialTicks,
                8f,
                true,
                iso && !otherDimension && IsoEntityDrawer.drawsPlayers());
            drawWaypoints(mouseX, mouseY);
        }

        double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
        double py = mc.thePlayer.prevPosY + (mc.thePlayer.posY - mc.thePlayer.prevPosY) * partialTicks
            - mc.thePlayer.yOffset;
        double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
        double[] playerScreen = toScreen(px, py, pz);
        double playerScreenX = playerScreen[0], playerScreenY = playerScreen[1];
        boolean playerOnScreen = !otherDimension && playerScreenX >= 0
            && playerScreenY >= 0
            && playerScreenX <= width
            && playerScreenY <= height;
        // 3D: the mobs in sight and the player as their 3D models (mobs of the player's own dimension only).
        boolean model = iso && !otherDimension
            && IsoEntityDrawer.draw(
                mc,
                this::toScreen,
                scale,
                isoProjection(),
                width,
                height,
                partialTicks,
                true,
                playerOnScreen && Config.isoPlayerModel);
        if (playerOnScreen) {
            float yaw = mc.thePlayer.prevRotationYaw
                + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;
            // Drawn as the player itself, or else as the arrow.
            if (!model && iso) {
                // Where one block ahead of the player lands on the screen gives the arrow's direction.
                double r = Math.toRadians(yaw);
                double[] ahead = toScreen(px - Math.sin(r), py, pz + Math.cos(r));
                yaw = (float) Math.toDegrees(Math.atan2(-(ahead[0] - playerScreenX), ahead[1] - playerScreenY));
            }
            if (!model) {
                MapDrawer.drawPlayerArrow(playerScreenX, playerScreenY, yaw, 5f);
            }
        }
        drawOverlay(mouseX, mouseY, partialTicks, dimension, dimensionId, otherDimension, iso);
    }

    /** The flat map and everything drawn on it (not in 3D). */
    private void drawFlatLayers(MapDimension dimension, int dimensionId, boolean otherDimension, int mouseX, int mouseY,
        float partialTicks) {
        MapDrawer.drawMap(dimension, centerX, centerZ, scale, 0, 0, width, height);
        if (chunkloadShown()) {
            // Only the map and its chunks (and the grid when it is on, as on the other 2D maps): no layers or mobs.
            updatePick(mouseX, mouseY);
            ChunkLoadView.draw(
                MapManager.INSTANCE.getViewMap(),
                dimensionId,
                centerX,
                centerZ,
                scale,
                0,
                0,
                width,
                height,
                pickSelection,
                pickMode == PICK_DELETE,
                true,
                pickMode == PICK_GENERATE_CAVES,
                pickMode == PICK_SAVED,
                pickMode == PICK_CANCEL);
            if (Config.chunkGrid) {
                MapDrawer.drawChunkGrid(centerX, centerZ, scale, 0, 0, width, height);
            }
            return;
        }
        // Drawn with the view off too: it fades out.
        Topography.draw(dimension, centerX, centerZ, scale, 0, 0, width, height, false);
        boolean prospecting = Mods.isVisualProspectingLoaded();
        // Search: gray over everything that doesn't match; matching biomes keep their color and get an outline.
        if (biomeViewShown() && BiomeHighlight.isActive()) {
            BiomeHighlight.draw(MapManager.INSTANCE.getViewBiomeMap(), centerX, centerZ, scale, 0, 0, width, height);
        } else if (prospecting && prospectingLayerShown() && ProspectingLayer.isSearchActive()) {
            Theme.fill(0, 0, width, height, 0xB0202428);
        }
        if (Config.chunkGrid) {
            MapDrawer.drawChunkGrid(centerX, centerZ, scale, 0, 0, width, height);
        }
        if (claimsShown()) {
            updateClaimPaint(mouseX, mouseY);
            Mods.draw(
                Mods.Addon.CLAIMS,
                () -> ClaimsLayer
                    .draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, claimSelection, claimAction));
        }
        if (prospecting && Config.showUndergroundFluids) {
            Mods.draw(
                Mods.Addon.VISUAL_PROSPECTING,
                () -> ProspectingLayer.drawFluids(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false));
        }
        if (prospecting && Config.showOreVeins && Mods.isVisualProspectingLoaded()) {
            Mods.draw(
                Mods.Addon.VISUAL_PROSPECTING,
                () -> ProspectingLayer
                    .drawOreVeins(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY));
        }
        if (nodesShown()) {
            Mods.draw(
                Mods.Addon.THAUMCRAFT_NODES,
                () -> ThaumcraftNodes
                    .draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY));
        }
        if (powerfailsShown()) {
            Mods.draw(
                Mods.Addon.POWERFAILS,
                () -> PowerfailLayer
                    .draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, false, mouseX, mouseY));
        }
        if (!otherDimension) {
            double px = mc.thePlayer.prevPosX + (mc.thePlayer.posX - mc.thePlayer.prevPosX) * partialTicks;
            double pz = mc.thePlayer.prevPosZ + (mc.thePlayer.posZ - mc.thePlayer.prevPosZ) * partialTicks;
            PlayerTrail.draw(dimensionId, centerX, centerZ, scale, 0, 0, width, height, px, pz);
            MapDrawer.drawEntities(mc, centerX, centerZ, scale, 0, 0, width, height, partialTicks, 8f, true);
        }
    }

    /** Header, footer, menus and tooltips over the map. */
    private void drawOverlay(int mouseX, int mouseY, float partialTicks, MapDimension dimension, int dimensionId,
        boolean otherDimension, boolean iso) {
        boolean prospecting = Mods.isVisualProspectingLoaded();
        // Header and footer.
        Theme.fill(0, 0, width, HEADER_HEIGHT, Theme.PANEL);
        Theme.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, Theme.BORDER);
        drawTitle(mouseX, mouseY, dimensionId, otherDimension);
        double targetScale = Config.MAP_ZOOMS[zoomIndex];
        String zoomText = targetScale >= 1 ? (int) targetScale + ":1" : "1:" + (int) Math.round(1 / targetScale);
        Theme.text(
            fontRendererObj,
            zoomText,
            width - 30 - fontRendererObj.getStringWidth(zoomText),
            8,
            Theme.TEXT_MUTED);

        Theme.fill(0, height - FOOTER_HEIGHT, width, height, Theme.PANEL);
        Theme.fill(0, height - FOOTER_HEIGHT, width, height - FOOTER_HEIGHT + 1, Theme.BORDER);
        int[] hovered = blockAt(mouseX, mouseY);
        int hoverX = hovered[0], hoverZ = hovered[2];
        // The bar's parts, left to right: an icon and a text each, set apart by thin lines.
        List<FooterPart> parts = new ArrayList<>();
        Waypoint hoveredWaypoint = waypointAt(mouseX, mouseY);
        if (hoveredWaypoint != null) {
            String where = hoveredWaypoint.x + ", " + hoveredWaypoint.y + ", " + hoveredWaypoint.z;
            String name = hoveredWaypoint.name + "  " + where + WaypointRenderer.ageSuffix(hoveredWaypoint);
            parts.add(new FooterPart(Icons.SMALL_FLAG, name, Theme.TEXT));
            if (hoveredWaypoint.isForeign()) {
                String whose = Lang.format("wayfarmap.gui.shared_by", hoveredWaypoint.ownerName);
                parts.add(new FooterPart(Icons.SMALL_PERSON, whose, Theme.ACCENT));
            } else if (hoveredWaypoint.isShared()) {
                parts.add(new FooterPart(Icons.SMALL_PERSON, Lang.format("wayfarmap.gui.shared"), Theme.ACCENT));
            }
            if (hoveredWaypoint.copyOf != null && !hoveredWaypoint.isForeign()) {
                parts.add(new FooterPart(Icons.SMALL_COPY, GuiWaypointList.copyLabel(hoveredWaypoint), 0xFFF2C14E));
            }
            parts.add(new FooterPart(null, Lang.format("wayfarmap.gui.waypoint_hint"), Theme.TEXT_MUTED));
        } else {
            String cursorText = hovered[1] >= 0 ? "X: " + hoverX + "  Y: " + hovered[1] + "  Z: " + hoverZ
                : "X: " + hoverX + "  Z: " + hoverZ;
            // Not explored there: the coordinates are dimmed, with a question mark.
            boolean unknown = iso ? hovered[1] < 0 : !isExplored(dimension, hoverX, hoverZ);
            if (unknown) {
                cursorText += "  ?";
            }
            parts.add(new FooterPart(Icons.SMALL_CURSOR, cursorText, unknown ? Theme.TEXT_MUTED : Theme.TEXT));
        }
        // Biome view shows biomes of whole columns, so there is no cave layer to pick.
        int caveLayer = columnViewShown() ? -1 : MapManager.INSTANCE.getViewCaveLayer();
        if (caveLayer >= 0 && hoveredWaypoint == null) {
            String layer = Lang.format("wayfarmap.gui.cave_layer", caveLayer * 16, caveLayer * 16 + 15);
            parts.add(new FooterPart(Icons.SMALL_CAVE, layer, Theme.TEXT));
        }
        if (biomeViewShown() && hoveredWaypoint == null) {
            BiomeGenBase biome = MapManager.INSTANCE.getViewBiome(hoverX, hoverZ);
            if (biome != null) {
                parts.add(new FooterPart(Icons.SMALL_TREE, biome.biomeName, Theme.TEXT));
            }
        }
        if (chunkloadShown()) {
            // The keys are listed in the toolbar at the top; here only how many chunks wait.
            int queued = ChunkLoadView.pendingCount(dimensionId);
            if (queued > 0) {
                String text = Lang.format("wayfarmap.gui.chunkload_queued", queued);
                parts.add(new FooterPart(Icons.SMALL_QUEUE, text, Theme.ACCENT));
            }
        }
        String exportStatus = MapExport.statusText();
        exportButton.active = exportStatus != null;
        // An area being loaded with /wf chunkload: how far it got, and the time left.
        String loadStatus = ChunkLoadClient.INSTANCE.statusText();
        // On the right, by the help button: what is going on, or a note on the view.
        String right = null;
        int rightColor = Theme.TEXT_MUTED;
        if (exportStatus != null) {
            right = exportStatus;
            rightColor = Theme.ACCENT;
        } else if (loadStatus != null && !chunkloadShown()) {
            // The area loading view has its own progress bar.
            right = loadStatus;
            rightColor = Theme.ACCENT;
        } else if (iso) {
            right = Lang.format("wayfarmap.gui.iso_hint");
        } else if (claimsShown() && !chunkloadShown()) {
            right = ClaimsLayer.countsText();
        } else if (Config.showClaims && Mods.isClaimsAvailable() && otherDimension) {
            // ServerUtilities takes the dimension of every claim change from the player, so claims can only be
            // shown and changed in the dimension the player is in.
            right = Lang.format("wayfarmap.claims.other_dimension");
            rightColor = Theme.DANGER;
        }
        int rightEdge = (helpButton.visible ? helpButton : aboutButton).xPosition - 8;
        int rightX = rightEdge;
        if (right != null && !right.isEmpty()) {
            // Never more than half the footer, so the left text keeps room too.
            right = Theme.ellipsize(fontRendererObj, right, width / 2);
            rightX = rightEdge - fontRendererObj.getStringWidth(right);
            Theme.text(fontRendererObj, right, rightX, height - 10, rightColor);
        }
        // The parts on the left take the room left, the last one cut short rather than running into the right text.
        drawFooterParts(parts, Math.max(26, rightX - 12));
        if (iso) {
            drawCompass();
        }

        GL11.glColor4f(1f, 1f, 1f, 1f);
        super.drawScaled(mouseX, mouseY, partialTicks);

        if (chunkloadShown()) {
            drawLoadBar(mouseX, mouseY);
        }
        if (caveLayer >= 0) {
            drawCaveSlider(mouseX, mouseY);
        }
        if (iso) {
            drawQualitySlider(mouseX, mouseY);
            if (!Config.record3d) {
                // Nothing new comes onto the 3D map: chunks without copied blocks stay empty.
                String warning = Lang.format("wayfarmap.gui.iso_not_recording");
                int w = fontRendererObj.getStringWidth(warning);
                int x = (width - w) / 2, y = height - FOOTER_HEIGHT - 16;
                Theme.fill(x - 4, y - 3, x + w + 4, y + 11, Theme.LABEL_BG);
                Theme.text(fontRendererObj, warning, x, y, Theme.DANGER);
            }
        }
        if (searchAvailable()) {
            searchField.drawTextBox();
        }
        IconButton hoveredIcon = null;
        for (Object o : buttonList) {
            if (o instanceof IconButton && ((IconButton) o).isMouseOver(mouseX, mouseY)) {
                hoveredIcon = (IconButton) o;
            }
        }
        if (dimensionList != null) {
            drawDimensionList(mouseX, mouseY);
        } else if (menu != null) {
            drawMenu(mouseX, mouseY);
        } else if (hoveredIcon != null && !hoveredIcon.tooltip.isEmpty()) {
            drawHoveringText(Collections.singletonList(hoveredIcon.tooltip), mouseX, mouseY, fontRendererObj);
        } else if (!iso && !chunkloadShown()
            && mouseY > HEADER_HEIGHT
            && mouseY < height - FOOTER_HEIGHT
            && claimButton < 0) {
                // Power failures are drawn on top, so their tooltip comes first.
                List<String> tooltip = powerfailsShown() ? PowerfailLayer.getHoveredTooltip() : null;
                if (tooltip == null && nodesShown()) {
                    tooltip = ThaumcraftNodes.getHoveredTooltip();
                }
                if (tooltip == null && prospecting && Config.showOreVeins) {
                    tooltip = ProspectingLayer.getHoveredTooltip();
                }
                if (tooltip == null && claimsShown()) {
                    tooltip = ClaimsLayer.tooltip(hoverX >> 4, hoverZ >> 4, dimensionId);
                }
                if (tooltip != null) {
                    drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
                }
            }
    }

    /** Where the compass's needle points (radians clockwise from up), turning to the view after Q / E. */
    private final Smooth compassAngle = new Smooth(Double.NaN);
    private static final int COMPASS_RADIUS = 15;

    /**
     * In 3D, which turns with Q / E: a compass in the lower left corner, its red needle pointing north on the screen.
     */
    private void drawCompass() {
        double[] center = toScreen(centerX, IsoProjection.REFERENCE_Y, centerZ);
        double[] north = toScreen(centerX, IsoProjection.REFERENCE_Y, centerZ - 16);
        double target = Math.atan2(north[0] - center[0], -(north[1] - center[1]));
        double shown = compassAngle.get();
        if (Double.isNaN(shown)) {
            compassAngle.set(target);
        } else {
            // The shortest way round: never a full turn back.
            double turn = Math.IEEEremainder(target - shown, 2 * Math.PI);
            compassAngle.set(shown);
            compassAngle.update(shown + turn, 12);
        }
        double angle = compassAngle.get();
        double cx = 8 + COMPASS_RADIUS, cy = height - FOOTER_HEIGHT - 8 - COMPASS_RADIUS;
        Theme.disc(cx, cy, COMPASS_RADIUS + 1, Theme.BORDER);
        Theme.disc(cx, cy, COMPASS_RADIUS, Theme.PANEL);
        double ux = Math.sin(angle), uy = -Math.cos(angle);
        // Small marks for east, south and west.
        for (int i = 1; i < 4; i++) {
            double a = angle + i * Math.PI / 2;
            int mx = (int) Math.round(cx + Math.sin(a) * (COMPASS_RADIUS - 3));
            int my = (int) Math.round(cy - Math.cos(a) * (COMPASS_RADIUS - 3));
            Theme.fill(mx - 1, my - 1, mx + 1, my + 1, Theme.TEXT_DISABLED);
        }
        // The needle: red half to the north, light half to the south.
        double length = COMPASS_RADIUS - 8, half = 2.5;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLES);
        tessellator.setColorOpaque_I(Theme.DANGER & 0xFFFFFF);
        tessellator.addVertex(cx + ux * length, cy + uy * length, 0);
        tessellator.addVertex(cx - uy * half, cy + ux * half, 0);
        tessellator.addVertex(cx + uy * half, cy - ux * half, 0);
        tessellator.setColorOpaque_I(Theme.TEXT_MUTED & 0xFFFFFF);
        tessellator.addVertex(cx - ux * length, cy - uy * length, 0);
        tessellator.addVertex(cx + uy * half, cy - ux * half, 0);
        tessellator.addVertex(cx - uy * half, cy + ux * half, 0);
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        // "N" just past the needle's tip, inside the rim.
        double reach = COMPASS_RADIUS - 4;
        int letterX = (int) Math.round(cx + ux * reach) - fontRendererObj.getStringWidth("N") / 2 + 1;
        int letterY = (int) Math.round(cy + uy * reach) - 4;
        fontRendererObj.drawStringWithShadow("N", letterX, letterY, Theme.TEXT);
    }

    /** A part of the bar at the bottom of the map: a small icon (or none) and a text. */
    private static final class FooterPart {

        final String[] icon;
        final String text;
        final int color;

        FooterPart(String[] icon, String text, int color) {
            this.icon = icon;
            this.text = text;
            this.color = color;
        }
    }

    /** The bottom bar's parts from the left, set apart by thin lines, ending before {@code right}. */
    private void drawFooterParts(List<FooterPart> parts, int right) {
        int x = 6, y = height - 10;
        for (int i = 0; i < parts.size() && x < right - 10; i++) {
            FooterPart part = parts.get(i);
            if (i > 0) {
                Theme.fill(x, height - FOOTER_HEIGHT + 4, x + 1, height - 3, Theme.BORDER);
                x += 7;
            }
            if (part.icon != null) {
                Icons.draw(part.icon, x, height - FOOTER_HEIGHT / 2 - part.icon.length / 2, Theme.TEXT_MUTED);
                x += Icons.width(part.icon) + 4;
            }
            String text = Theme.ellipsize(fontRendererObj, part.text, Math.max(0, right - x));
            Theme.text(fontRendererObj, text, x, y, part.color);
            x += fontRendererObj.getStringWidth(text) + 7;
        }
    }

    private int headerLeftEnd() {
        int end = 0;
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            if (button.id == ID_WAYPOINTS || button.id == ID_SETTINGS
                || button.id == ID_EXPORT
                || button.id == ID_ADDONS) {
                end = Math.max(end, button.xPosition + ((FlatButton) button).getWidth());
            }
        }
        return end;
    }

    // ---------------------------------------------------------------- 3D quality slider

    private static final int QUALITY_WIDTH = 20, QUALITY_ROW = 14;

    private int qualityX() {
        return width - QUALITY_WIDTH - 6;
    }

    private int qualityTop() {
        return HEADER_HEIGHT + 6;
    }

    /** The quality (0-3) of the slider's cell under the mouse, -1 if not on it; the best is at the top. */
    private int qualityAt(int mouseX, int mouseY) {
        int x = qualityX(), top = qualityTop();
        int rows = Config.ISO_QUALITY_MAX + 1;
        if (!Theme.inside(mouseX, mouseY, x, top, x + QUALITY_WIDTH, top + rows * QUALITY_ROW)) {
            return -1;
        }
        return Config.ISO_QUALITY_MAX - (mouseY - top) / QUALITY_ROW;
    }

    /** Pixels per block the 3D map is drawn with at most: 64 at the top down to 8. */
    private void drawQualitySlider(int mouseX, int mouseY) {
        int x = qualityX(), top = qualityTop();
        int rows = Config.ISO_QUALITY_MAX + 1;
        int hovered = qualityAt(mouseX, mouseY);
        Theme.fill(x - 1, top - 1, x + QUALITY_WIDTH + 1, top + rows * QUALITY_ROW + 1, Theme.BORDER);
        for (int quality = Config.ISO_QUALITY_MAX; quality >= 0; quality--) {
            int y = top + (Config.ISO_QUALITY_MAX - quality) * QUALITY_ROW;
            int color;
            if (quality == Config.isoQuality) {
                color = Theme.ACCENT;
            } else if (quality < Config.isoQuality) {
                color = Theme.ACCENT_DIM;
            } else {
                color = quality == hovered ? Theme.CONTROL_HOVER : Theme.PANEL;
            }
            Theme.fill(x, y, x + QUALITY_WIDTH, y + QUALITY_ROW - 1, color);
            Theme.centered(
                fontRendererObj,
                String.valueOf(8 << quality),
                x + QUALITY_WIDTH / 2,
                y + 3,
                quality <= Config.isoQuality ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        if (hovered >= 0 && menu == null) {
            String text = Lang.format("wayfarmap.iso.quality") + ": "
                + Lang.format("wayfarmap.iso.quality_value", 8 << hovered);
            drawHoveringText(Collections.singletonList(text), mouseX, mouseY, fontRendererObj);
        }
    }

    // ---------------------------------------------------------------- cave layer slider

    private int sliderX() {
        return width - SLIDER_WIDTH - 6;
    }

    private int sliderAutoTop() {
        return HEADER_HEIGHT + 6;
    }

    private int sliderTop() {
        return sliderAutoTop() + 18;
    }

    private int sliderBottom() {
        return height - FOOTER_HEIGHT - 8;
    }

    /** Layer (0-15) at the height of the mouse on the slider track; the top is the highest layer. */
    private int sliderLayerAt(int mouseY) {
        double t = (mouseY - sliderTop()) / (double) Math.max(1, sliderBottom() - sliderTop());
        return 15 - Math.max(0, Math.min(15, (int) Math.floor(t * 16)));
    }

    private boolean onCaveSlider(int mouseX, int mouseY) {
        return MapManager.INSTANCE.getViewCaveLayer() >= 0 && !columnViewShown()
            && Theme
                .inside(mouseX, mouseY, sliderX() - 4, sliderAutoTop(), sliderX() + SLIDER_WIDTH + 4, sliderBottom());
    }

    private void drawCaveSlider(int mouseX, int mouseY) {
        if (draggingCaveSlider) {
            if (Mouse.isButtonDown(0)) {
                MapManager.INSTANCE.setCaveLayerOverride(sliderLayerAt(mouseY));
            } else {
                draggingCaveSlider = false;
            }
        }
        int x = sliderX();
        int top = sliderTop(), bottom = sliderBottom();
        int override = MapManager.INSTANCE.getCaveLayerOverride();
        int layer = MapManager.INSTANCE.getViewCaveLayer();

        // "Auto" follows the player's height.
        int autoTop = sliderAutoTop();
        boolean autoHovered = Theme.inside(mouseX, mouseY, x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14);
        Theme.fill(x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14, override < 0 ? Theme.ACCENT_DIM : Theme.CONTROL);
        Theme.outline(x - 4, autoTop, x + SLIDER_WIDTH + 4, autoTop + 14, autoHovered ? Theme.ACCENT : Theme.BORDER);
        Theme.centered(fontRendererObj, "A", x + SLIDER_WIDTH / 2, autoTop + 3, Theme.TEXT);

        Theme.fill(x - 1, top - 1, x + SLIDER_WIDTH + 1, bottom + 1, Theme.BORDER);
        Theme.fill(x, top, x + SLIDER_WIDTH, bottom, Theme.PANEL);
        double step = (bottom - top) / 16.0;
        for (int i = 1; i < 16; i++) {
            int y = top + (int) Math.round(i * step);
            Theme.fill(x + 2, y, x + SLIDER_WIDTH - 2, y + 1, Theme.CONTROL_HOVER);
        }
        int knobTop = top + (int) Math.round((15 - layer) * step);
        int knobBottom = top + (int) Math.round((16 - layer) * step);
        Theme.fill(x, knobTop, x + SLIDER_WIDTH, knobBottom, override >= 0 ? Theme.ACCENT : Theme.TEXT_MUTED);

        boolean hovered = onCaveSlider(mouseX, mouseY) || draggingCaveSlider;
        String label = "Y " + layer * 16 + "-" + (layer * 16 + 15);
        int labelWidth = fontRendererObj.getStringWidth(label);
        int labelY = (knobTop + knobBottom) / 2 - 4;
        Theme.fill(x - labelWidth - 10, labelY - 2, x - 4, labelY + 10, Theme.LABEL_BG);
        Theme.text(fontRendererObj, label, x - labelWidth - 7, labelY, hovered ? Theme.TEXT : Theme.TEXT_MUTED);
    }

    // ---------------------------------------------------------------- dimension picker

    /** "[id] Name ▼" in the middle of the header; it opens the list of saved dimensions. */
    private void drawTitle(int mouseX, int mouseY, int dimensionId, boolean otherDimension) {
        String title = "[" + dimensionId + "] " + MapManager.INSTANCE.getViewedDimensionName() + " \u25BE";
        int titleWidth = fontRendererObj.getStringWidth(title);
        if (width / 2 - titleWidth / 2 <= headerLeftEnd() + 8
            || width / 2 + titleWidth / 2 >= rightButtonsStart() - 8) {
            // No room for the name: the id alone still opens the list.
            title = "[" + dimensionId + "] \u25BE";
            titleWidth = fontRendererObj.getStringWidth(title);
        }
        titleX0 = width / 2 - titleWidth / 2;
        titleX1 = titleX0 + titleWidth;
        boolean hovered = dimensionList != null || Theme.inside(mouseX, mouseY, titleX0 - 4, 4, titleX1 + 4, 20);
        if (hovered) {
            Theme.fill(titleX0 - 4, 4, titleX1 + 4, 20, Theme.CONTROL_HOVER);
        }
        // Accent while looking at another dimension than the player's.
        int color = otherDimension ? Theme.ACCENT : hovered ? Theme.TEXT : Theme.TEXT_MUTED;
        Theme.text(fontRendererObj, title, titleX0, 8, color);
    }

    private void openDimensionList() {
        if (dimensionList != null) {
            dimensionList = null;
            return;
        }
        menu = null;
        dimensionList = MapManager.INSTANCE.listSavedDimensions();
        String here = Lang.format("wayfarmap.gui.dimension_here");
        int widest = 0;
        for (MapManager.SavedDimension dimension : dimensionList) {
            widest = Math.max(
                widest,
                fontRendererObj.getStringWidth(dimensionLabel(dimension))
                    + fontRendererObj.getStringWidth("  " + here));
        }
        dimensionListWidth = Math.max(titleX1 - titleX0 + 8, widest + 12);
        dimensionListX = Math.max(2, Math.min(width / 2 - dimensionListWidth / 2, width - dimensionListWidth - 2));
    }

    private static String dimensionLabel(MapManager.SavedDimension dimension) {
        return "[" + dimension.id + "] " + dimension.name;
    }

    private void drawDimensionList(int mouseX, int mouseY) {
        int top = HEADER_HEIGHT - 2;
        int h = dimensionList.size() * MENU_ROW + 4;
        Theme.panel(dimensionListX, top, dimensionListX + dimensionListWidth, top + h);
        int shown = viewDimension();
        int playerDimension = mc.theWorld.provider.dimensionId;
        String here = Lang.format("wayfarmap.gui.dimension_here");
        for (int i = 0; i < dimensionList.size(); i++) {
            MapManager.SavedDimension dimension = dimensionList.get(i);
            int y = top + 2 + i * MENU_ROW;
            if (dimension.id == shown) {
                Theme.fill(
                    dimensionListX + 1,
                    y,
                    dimensionListX + dimensionListWidth - 1,
                    y + MENU_ROW,
                    Theme.ACCENT_DIM);
            } else if (Theme
                .inside(mouseX, mouseY, dimensionListX, y, dimensionListX + dimensionListWidth, y + MENU_ROW)) {
                    Theme.fill(
                        dimensionListX + 1,
                        y,
                        dimensionListX + dimensionListWidth - 1,
                        y + MENU_ROW,
                        Theme.CONTROL_HOVER);
                }
            String label = dimensionLabel(dimension);
            Theme.text(fontRendererObj, label, dimensionListX + 6, y + 3, Theme.TEXT);
            if (dimension.id == playerDimension) {
                Theme.text(
                    fontRendererObj,
                    here,
                    dimensionListX + dimensionListWidth - 6 - fontRendererObj.getStringWidth(here),
                    y + 3,
                    Theme.TEXT_MUTED);
            }
        }
    }

    /** @return true if the click was taken by the dimension list (which then closes) */
    private boolean clickDimensionList(int mouseX, int mouseY) {
        if (dimensionList == null) {
            return false;
        }
        List<MapManager.SavedDimension> entries = dimensionList;
        dimensionList = null;
        int top = HEADER_HEIGHT - 2;
        for (int i = 0; i < entries.size(); i++) {
            int y = top + 2 + i * MENU_ROW;
            if (Theme.inside(mouseX, mouseY, dimensionListX, y, dimensionListX + dimensionListWidth, y + MENU_ROW)) {
                showDimension(entries.get(i).id);
            }
        }
        return true;
    }

    /** Left edge of the buttons on the right of the header. */
    private int rightButtonsStart() {
        return teamButton.visible ? teamButton.xPosition : mobsButton.xPosition;
    }

    /** Menu under the team button: every online teammate and the dimension they are in; a click goes there. */
    private void openTeamMenu() {
        List<MenuEntry> entries = new ArrayList<>();
        for (TeamMates.Mate mate : TeamMates.INSTANCE.all()) {
            String where = "[" + mate.dimension + "] " + MapManager.INSTANCE.getDimensionName(mate.dimension);
            String label = mate.name + " \u00a77" + where;
            entries.add(
                new MenuEntry(
                    Theme.ellipsize(fontRendererObj, label, TEAM_MENU_WIDTH - 12),
                    true,
                    () -> goToTeammate(mate)));
        }
        menu = entries;
        menuKind = MENU_TEAM;
        menuShown();
        menuWidth = TEAM_MENU_WIDTH;
        menuX = Math.max(2, Math.min(teamButton.xPosition, width - TEAM_MENU_WIDTH - 2));
        menuY = teamButton.yPosition + 18;
    }

    /** Centers the map on the teammate, switching to their dimension if needed. */
    private void goToTeammate(TeamMates.Mate mate) {
        double[] position = TeamMates.INSTANCE.position(mate, 1f);
        if (mate.dimension != viewDimension()) {
            // Another dimension's map: no way to glide there from here.
            showDimension(mate.dimension);
            centerOn(position[0], position[1], position[2]);
        } else {
            flyTo(position[0], position[1], position[2]);
        }
        zooming = false;
    }

    /** Shows the waypoint in the middle of the map, switching to its dimension if it has a saved map. */
    private void centerOnWaypoint(Waypoint waypoint) {
        if (waypoint.dimension != viewDimension()) {
            if (waypoint.dimension == mc.theWorld.provider.dimensionId) {
                showDimension(waypoint.dimension);
            } else {
                for (MapManager.SavedDimension other : MapManager.INSTANCE.listSavedDimensions()) {
                    if (other.id == waypoint.dimension) {
                        showDimension(waypoint.dimension);
                        break;
                    }
                }
            }
        }
        centerOn(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5);
        zooming = false;
    }

    /** Switches the map to a saved dimension, keeping the view roughly in place (Nether coordinates are 1:8). */
    private void showDimension(int id) {
        int from = viewDimension();
        if (id == from) {
            return;
        }
        MapManager.INSTANCE.viewDimension(id);
        if (id == mc.theWorld.provider.dimensionId) {
            centerOn(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
        } else if (id == -1 && from != -1) {
            centerX /= 8;
            centerZ /= 8;
        } else if (from == -1 && id != -1) {
            centerX *= 8;
            centerZ *= 8;
        }
        zooming = false;
        scale = Config.MAP_ZOOMS[zoomIndex];
        claimButton = -1;
        claimSelection.clear();
        applySearch();
    }

    // ---------------------------------------------------------------- right click menu

    private void openMenu(int mouseX, int mouseY) {
        int dimension = viewDimension();
        // Teleporting works only within the player's dimension.
        boolean here = !MapManager.INSTANCE.isViewingOtherDimension();
        List<MenuEntry> entries = new ArrayList<>();
        // The vein under the mouse right now: the menu keeps it, since the mouse leaves the vein to click an entry.
        // The 3D view has no layers to point at.
        boolean flat = !isoShown() && !chunkloadShown();
        final Object vein = flat && Mods.isVisualProspectingLoaded() && Config.showOreVeins
            ? ProspectingLayer.getHoveredVein()
            : null;
        // Same for the power failure under the mouse.
        final Object powerfail = flat && powerfailsShown() ? PowerfailLayer.getHovered() : null;
        // And for the Thaumcraft node under the mouse.
        final Object node = flat && powerfail == null && nodesShown() ? ThaumcraftNodes.getHovered() : null;
        if (node != null) {
            entries.add(
                new MenuEntry(
                    Lang.format(ThaumcraftNodes.isTracked(node) ? "wayfarmap.node.untrack" : "wayfarmap.node.track"),
                    true,
                    () -> ThaumcraftNodes.toggleTracked(node)));
            entries.add(
                new MenuEntry(Lang.format("wayfarmap.node.deplete"), true, () -> ThaumcraftNodes.markDepleted(node)));
        }
        if (powerfail != null) {
            entries.add(
                new MenuEntry(Lang.format("wayfarmap.powerfail.clear"), true, () -> PowerfailLayer.clear(powerfail))
                    .icon(Icons.SMALL_CHECK));
            final int[] at = PowerfailLayer.position(powerfail);
            entries.add(
                new MenuEntry(
                    Lang.format("wayfarmap.powerfail.waypoint"),
                    true,
                    () -> mc.displayGuiScreen(GuiEditWaypoint.create(this, at[0], at[1] + 1, at[2], at[3])))
                        .icon(Icons.SMALL_PIN));
        }
        if (vein != null) {
            entries.add(
                new MenuEntry(
                    Lang.format(
                        ProspectingLayer.isTracked(vein) ? "wayfarmap.gui.vein_untrack" : "wayfarmap.gui.vein_track"),
                    true,
                    () -> ProspectingLayer.toggleTracked(vein)));
            entries.add(
                new MenuEntry(
                    Lang.format(
                        ProspectingLayer.isDepleted(vein) ? "wayfarmap.gui.vein_restore"
                            : "wayfarmap.gui.vein_deplete"),
                    true,
                    () -> ProspectingLayer.toggleDepleted(vein)));
        }
        int[] block = blockAt(mouseX, mouseY);
        final int bx = block[0];
        final int bz = block[2];
        // In 3D the block seen there gives the height, also in another dimension.
        final int seenY = block[1] >= 0 ? Math.min(255, block[1] + 1) : 0;
        final int safeY = here ? Teleport.findSafeY(mc.theWorld, bx, bz) : 0;
        entries.add(new MenuEntry(Lang.format("wayfarmap.gui.teleport_here"), here && Teleport.isAllowed(), () -> {
            if (safeY > 0) {
                Teleport.teleport(bx, safeY, bz);
            } else {
                // Unexplored or unknown height: ask which Y to go to.
                mc.displayGuiScreen(new GuiTeleportY(this, bx, bz));
            }
        }).icon(Icons.SMALL_UP));
        entries.add(
            new MenuEntry(
                Lang.format("wayfarmap.gui.new_waypoint"),
                true,
                () -> mc.displayGuiScreen(
                    GuiEditWaypoint
                        .create(this, bx, safeY > 0 ? safeY : seenY > 0 ? seenY : waypointY(bx, bz), bz, dimension)))
                            .icon(Icons.SMALL_PLUS)
                            .key("new_waypoint"));
        // A waypoint at once, without its editor: named by its coordinates, no icon.
        final int markY = safeY > 0 ? safeY : seenY > 0 ? seenY : waypointY(bx, bz);
        if (copiedWaypoint != null) {
            entries.add(
                new MenuEntry(Lang.format("wayfarmap.gui.paste"), true, () -> pasteWaypoint(bx, markY, bz))
                    .icon(Icons.SMALL_PASTE)
                    .hint("Ctrl+V"));
        }
        entries.add(
            new MenuEntry(
                Lang.format("wayfarmap.gui.quick_waypoint"),
                true,
                () -> WaypointManager.INSTANCE
                    .addWaypoint(new Waypoint(bx + ", " + markY + ", " + bz, bx, markY, bz, dimension)))
                        .icon(Icons.SMALL_FLAG));
        if (flat) {
            final int rx = bx >> MapRegion.SHIFT, rz = bz >> MapRegion.SHIFT;
            entries.add(
                new MenuEntry(
                    Lang.format("wayfarmap.gui.delete_region"),
                    true,
                    () -> confirmDeleteRegion(dimension, rx, rz)).icon(Icons.SMALL_TRASH)
                        .danger());
        }
        showMenu(entries, MENU_MAP, mouseX, mouseY);
    }

    /**
     * Menu of a waypoint on the map: share it in chat or with the team, teleport to it, edit, remove or disable it.
     * A teammate's one says whose it is, and is saved as an own one instead of edited or removed.
     */
    private void openWaypointMenu(Waypoint waypoint, int mouseX, int mouseY) {
        List<MenuEntry> entries = new ArrayList<>();
        final boolean foreign = waypoint.isForeign();
        if (foreign) {
            entries.add(
                new MenuEntry(Lang.format("wayfarmap.gui.shared_by", waypoint.ownerName), false, () -> {})
                    .icon(Icons.SMALL_PERSON));
        }
        entries.add(
            new MenuEntry(Lang.format("wayfarmap.gui.share"), true, () -> WaypointShare.share(waypoint))
                .icon(Icons.SMALL_CHAT));
        // Teleporting needs /tp permission and the same dimension.
        boolean canTeleport = Teleport.isAllowed() && mc.theWorld != null
            && waypoint.dimension == mc.theWorld.provider.dimensionId;
        entries.add(new MenuEntry(Lang.format("wayfarmap.gui.teleport"), canTeleport, () -> {
            mc.displayGuiScreen(null);
            Teleport.teleport(waypoint.x, waypoint.y, waypoint.z);
        }).icon(Icons.SMALL_UP));
        if (foreign) {
            entries.add(
                new MenuEntry(
                    Lang.format("wayfarmap.gui.save_own"),
                    true,
                    () -> WaypointManager.INSTANCE.saveAsOwn(waypoint)).icon(Icons.SMALL_DISK));
        } else {
            entries.add(
                new MenuEntry(
                    Lang.format("wayfarmap.gui.edit"),
                    true,
                    () -> mc.displayGuiScreen(GuiEditWaypoint.edit(this, waypoint))).icon(Icons.SMALL_PENCIL));
            final Waypoint original = WaypointManager.INSTANCE.getOriginal(waypoint);
            if (original != null) {
                // A copy of a teammate's waypoint: the map goes to the one it was made of.
                entries.add(
                    new MenuEntry(
                        Lang.format("wayfarmap.gui.go_original"),
                        true,
                        () -> mc.displayGuiScreen(showing(original))).icon(Icons.SMALL_PIN));
            }
            if (!waypoint.death && (TeamWaypoints.INSTANCE.isAvailable() || waypoint.isShared())) {
                entries.add(new MenuEntry(Lang.format("wayfarmap.gui.team_share"), true, () -> {
                    WaypointManager.setShared(waypoint, !waypoint.isShared());
                    WaypointManager.INSTANCE.waypointChanged();
                }, waypoint.isShared()));
            }
        }
        entries.add(
            new MenuEntry(Lang.format("wayfarmap.gui.copy"), true, () -> copiedWaypoint = waypoint.copy())
                .icon(Icons.SMALL_COPY)
                .hint("Ctrl+C"));
        // Disabled: gone from the world and the minimap, faded on this map.
        String toggle = Lang.format(waypoint.enabled ? "wayfarmap.gui.disable" : "wayfarmap.gui.enable");
        entries.add(new MenuEntry(toggle, true, () -> {
            waypoint.enabled = !waypoint.enabled;
            WaypointManager.INSTANCE.waypointChanged();
        }).icon(Icons.SMALL_EYE));
        if (!foreign) {
            // Removing comes last, set apart.
            entries.add(
                new MenuEntry(
                    Lang.format("wayfarmap.gui.remove"),
                    true,
                    () -> WaypointManager.INSTANCE.removeWaypoint(waypoint)).icon(Icons.SMALL_TRASH)
                        .danger());
        }
        showMenu(entries, MENU_WAYPOINT, mouseX, mouseY);
    }

    /** Places a copy of the copied waypoint at the block, in the dimension the map shows. */
    private void pasteWaypoint(int x, int y, int z) {
        if (copiedWaypoint == null) {
            return;
        }
        Waypoint pasted = copiedWaypoint.copy();
        pasted.x = x;
        pasted.y = y;
        pasted.z = z;
        pasted.dimension = viewDimension();
        // An ordinary waypoint, saved like any other: shown even if the original was off, the player's own and not
        // shared whatever the original was, and a copy of a death marker is not one more death to keep or drop, nor
        // in the deaths' group.
        pasted.enabled = true;
        pasted.makeOwn();
        if (pasted.death) {
            pasted.death = false;
            pasted.diedAt = 0;
            pasted.group = null;
        }
        WaypointManager.INSTANCE.addWaypoint(pasted);
    }

    /** Ctrl+C on a waypoint copies it; anywhere else it forgets the copied one. */
    private void copyAtMouse(int mouseX, int mouseY) {
        Waypoint hovered = waypointAt(mouseX, mouseY);
        copiedWaypoint = hovered != null ? hovered.copy() : null;
    }

    /** Ctrl+V pastes the copied waypoint under the mouse, at the same height a quick waypoint would get. */
    private void pasteAtMouse(int mouseX, int mouseY) {
        if (copiedWaypoint == null || chunkloadShown()) {
            return;
        }
        int[] block = blockAt(mouseX, mouseY);
        int bx = block[0], bz = block[2];
        int seenY = block[1] >= 0 ? Math.min(255, block[1] + 1) : 0;
        int safeY = MapManager.INSTANCE.isViewingOtherDimension() ? 0 : Teleport.findSafeY(mc.theWorld, bx, bz);
        pasteWaypoint(bx, safeY > 0 ? safeY : seenY > 0 ? seenY : waypointY(bx, bz), bz);
    }

    /** Opens a menu at the point, as wide as its longest entry. */
    private void showMenu(List<MenuEntry> entries, int kind, int x, int y) {
        int widest = menuWidthFor(entries, MENU_WIDTH);
        menu = entries;
        menuKind = kind;
        menuShown();
        menuWidth = widest;
        menuX = Math.max(2, Math.min(x, width - widest - 2));
        menuY = Math.min(y, height - entries.size() * MENU_ROW - 6);
    }

    /** Asks before deleting a region of the flat map, in the same place as the menu was. */
    private void confirmDeleteRegion(int dimension, int rx, int rz) {
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(
            new MenuEntry(
                Lang.format("wayfarmap.gui.delete_region_yes"),
                true,
                () -> MapManager.INSTANCE.deleteFlatRegion(dimension, rx, rz)).icon(Icons.SMALL_TRASH)
                    .danger());
        entries.add(new MenuEntry(Lang.format("gui.cancel"), true, () -> {}));
        showMenu(entries, MENU_CONFIRM, menuX, menuY);
    }

    /** How long a menu takes to drop open, in milliseconds. */
    private static final long MENU_OPEN_MS = 120;
    /** When the open menu was opened, and the kind and time of the last one closed by a click. */
    private long menuOpenedAt, menuClosedAt;
    private int menuClosedKind = -1;
    /** How lit each row of the open menu is by the mouse, for the entries it was made for. */
    private Smooth[] menuLight = new Smooth[0];
    private List<MenuEntry> menuLightFor;

    /**
     * A menu was just put up: it drops open, unless it is the same menu opened again right after a click in it (a
     * toggle that keeps the menu open, showing the new state).
     */
    private void menuShown() {
        long now = System.currentTimeMillis();
        if (menuKind != menuClosedKind || now - menuClosedAt > 200) {
            menuOpenedAt = now;
        }
    }

    /** Width of a menu: its longest label, with room for icons or checkboxes and for the key hints. */
    private int menuWidthFor(List<MenuEntry> entries, int minimum) {
        int widest = minimum;
        int iconColumn = iconColumn(entries);
        for (MenuEntry entry : entries) {
            int w = fontRendererObj.getStringWidth(entry.label) + 14;
            if (entry.checked != null) {
                w += 12;
            }
            if (entry.icon != null) {
                w += iconColumn + 5;
            }
            if (entry.hint != null) {
                w += fontRendererObj.getStringWidth(entry.hint) + 12;
            }
            widest = Math.max(widest, w);
        }
        return widest;
    }

    private void drawMenu(int mouseX, int mouseY) {
        int h = menu.size() * MENU_ROW + 4;
        if (menuLightFor != menu) {
            menuLightFor = menu;
            menuLight = new Smooth[menu.size()];
            for (int i = 0; i < menuLight.length; i++) {
                menuLight[i] = new Smooth(0);
            }
        }
        // Drops open: cut to a growing part of its height, coming down a little.
        double t = Math.min(1, (System.currentTimeMillis() - menuOpenedAt) / (double) MENU_OPEN_MS);
        double open = 1 - Math.pow(1 - t, 3);
        int shownHeight = (int) Math.ceil(h * (0.35 + 0.65 * open));
        int drop = (int) Math.round((1 - open) * 4);
        int top = menuY - drop;
        Theme.clip(menuX, top, menuX + menuWidth, top + shownHeight);
        Theme.panel(menuX, top, menuX + menuWidth, top + h);
        for (int i = 0; i < menu.size(); i++) {
            MenuEntry entry = menu.get(i);
            int y = top + 2 + i * MENU_ROW;
            boolean hovered = entry.enabled && Theme.inside(mouseX, mouseY, menuX, y, menuX + menuWidth, y + MENU_ROW);
            double lit = menuLight[i].update(hovered ? 1 : 0, 22);
            if (entry.danger && i > 0 && !menu.get(i - 1).danger) {
                // Destructive entries are set apart by a line.
                Theme.fill(menuX + 4, y, menuX + menuWidth - 4, y + 1, Theme.BORDER);
            }
            if (entry.selected) {
                // The current choice: always marked, a little lit.
                Theme.fill(menuX + 1, y, menuX + menuWidth - 1, y + MENU_ROW, 0x60000000 | Theme.ACCENT_DIM & 0xFFFFFF);
                Theme.fill(menuX + 1, y, menuX + 3, y + MENU_ROW, Theme.ACCENT);
            }
            if (lit > 0.02) {
                int light = Theme.blend(Theme.CONTROL_HOVER & 0xFFFFFF, Theme.CONTROL_HOVER, lit);
                Theme.fill(menuX + 1, y, menuX + menuWidth - 1, y + MENU_ROW, light);
                int bar = entry.danger ? Theme.DANGER : Theme.ACCENT;
                Theme.fill(menuX + 1, y, menuX + 3, y + MENU_ROW, Theme.blend(bar & 0xFFFFFF, bar, lit));
            }
            int color = !entry.enabled ? Theme.TEXT_DISABLED : entry.danger ? Theme.DANGER : Theme.TEXT;
            int textX = menuX + 6;
            if (entry.checked != null) {
                // Checkbox.
                Theme.outline(menuX + 5, y + 3, menuX + 13, y + 11, entry.checked ? Theme.ACCENT : Theme.BORDER);
                if (entry.checked) {
                    Theme.fill(menuX + 7, y + 5, menuX + 11, y + 9, Theme.ACCENT);
                }
                textX = menuX + 18;
                if (entry.icon != null) {
                    textX = drawMenuIcon(entry, textX, y, hovered) + 4;
                }
            } else if (entry.icon != null) {
                textX = drawMenuIcon(entry, menuX + 6, y, hovered) + 5;
            }
            Theme.text(fontRendererObj, entry.label, textX, y + 3, color);
            if (entry.hint != null) {
                int hintX = menuX + menuWidth - 6 - fontRendererObj.getStringWidth(entry.hint);
                Theme.text(fontRendererObj, entry.hint, hintX, y + 3, Theme.TEXT_DISABLED);
            }
        }
        Theme.unclip();
        if (menuKind == MENU_MAP && !Teleport.isAllowed() && open >= 1) {
            String note = Lang.format("wayfarmap.gui.no_teleport_permission");
            Theme.text(fontRendererObj, note, menuX + 2, menuY + h + 3, Theme.TEXT_DISABLED);
        }
    }

    /** Whether the menu is a list of choices (one of its entries is the current one), like the modes. */
    private static boolean isChoiceMenu(List<MenuEntry> entries) {
        for (MenuEntry entry : entries) {
            if (entry.selected) {
                return true;
            }
        }
        return false;
    }

    /** Width of the column the menu's icons are centered in: its widest icon, so the labels line up. */
    private static int iconColumn(List<MenuEntry> entries) {
        int widest = 7;
        for (MenuEntry entry : entries) {
            if (entry.icon != null) {
                widest = Math.max(widest, Icons.width(entry.icon));
            }
        }
        return widest;
    }

    /** The entry's icon, centered in its row and in the menu's icon column; returns where the column ends. */
    private int drawMenuIcon(MenuEntry entry, int x, int y, boolean hovered) {
        int color;
        if (!entry.enabled) {
            color = Theme.TEXT_DISABLED;
        } else if (entry.danger) {
            color = Theme.DANGER;
        } else if (entry.iconColor != 0) {
            // Its own color: darker while its toggle is off, or in a list of choices while it isn't the one chosen.
            boolean on = entry.checked != null ? entry.checked : !isChoiceMenu(menu) || entry.selected;
            color = Theme.iconShade(entry.iconColor, hovered, false, !on);
        } else if (entry.selected || hovered) {
            color = Theme.ACCENT;
        } else {
            color = Theme.TEXT_MUTED;
        }
        int column = iconColumn(menu);
        int iconX = x + (column - Icons.width(entry.icon)) / 2;
        Icons.draw(entry.icon, iconX, y + (MENU_ROW - entry.icon.length) / 2, color);
        return x + column;
    }

    /** @return true if the click was taken by the menu (which then closes) */
    private boolean clickMenu(int mouseX, int mouseY) {
        if (menu == null) {
            return false;
        }
        List<MenuEntry> entries = menu;
        menu = null;
        menuClosedKind = menuKind;
        menuClosedAt = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            int y = menuY + 2 + i * MENU_ROW;
            MenuEntry entry = entries.get(i);
            if (Theme.inside(mouseX, mouseY, menuX, y, menuX + menuWidth, y + MENU_ROW) && entry.enabled) {
                entry.action.run();
                if (entry.checked != null && menuKind == MENU_ADDONS) {
                    // Toggles keep the menu open, showing the new state.
                    openAddonsMenu();
                } else if (entry.checked != null && menuKind == MENU_MOBS) {
                    openMobsMenu();
                }
            }
        }
        return true;
    }

    /** Marker size in GUI pixels: markers shrink when zooming out so nearby waypoints don't pile up. */
    private float markerSize() {
        return (float) Math.max(MIN_MARKER_SIZE, Math.min(MARKER_SIZE, MARKER_SIZE * Math.pow(scale, 0.4)));
    }

    /** Waypoint under the mouse last frame, and how far its hover effect has grown (0 to 1). */
    private Waypoint hoverEffectWaypoint;
    private float hoverEffect;
    private long hoverEffectNanos;
    /** How much bigger a hovered waypoint is drawn. */
    private static final float HOVER_GROWTH = 0.3f;
    /** Time for the hover effect to grow in, in seconds. */
    private static final float HOVER_SECONDS = 0.12f;
    /** Time of one pulse of the frame around a hovered waypoint, in milliseconds. */
    private static final long HOVER_PULSE_MS = 1200;

    /** Grows the hover effect while the same waypoint stays under the mouse; starts over on another one. */
    private float updateHoverEffect(Waypoint hovered) {
        long now = System.nanoTime();
        float seconds = hoverEffectNanos == 0 ? 0f : Math.min(0.1f, (now - hoverEffectNanos) / 1.0e9f);
        hoverEffectNanos = now;
        if (hovered != hoverEffectWaypoint) {
            hoverEffectWaypoint = hovered;
            hoverEffect = 0f;
        } else if (hovered != null) {
            hoverEffect = Math.min(1f, hoverEffect + seconds / HOVER_SECONDS);
        }
        // Eased, so it pops out and settles.
        float t = hoverEffect;
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * Frame around the hovered waypoint, in its color, pulsing gently: shows it can be clicked (right click: its
     * menu). Drawn under the marker.
     */
    private static void drawHoverFrame(Waypoint waypoint, double sx, double sy, float size, float effect) {
        long cx = Math.round(sx), cy = Math.round(sy);
        GL11.glPushMatrix();
        GL11.glTranslated(sx - cx, sy - cy, 0);
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() % HOVER_PULSE_MS * 2 * Math.PI / HOVER_PULSE_MS);
        int rgb = waypoint.outlineColor != null ? waypoint.outlineColor & 0xFFFFFF : Theme.ACCENT & 0xFFFFFF;
        int half = Math.round(size / 2f) + 3 + Math.round(effect * (float) pulse);
        int x0 = (int) cx - half, y0 = (int) cy - half, x1 = (int) cx + half, y1 = (int) cy + half;
        // A soft glow inside, a bright frame, and a dark line around it so it shows on any ground.
        int glow = (int) (effect * (40 + 40 * pulse));
        Theme.fill(x0, y0, x1, y1, glow << 24 | rgb);
        Theme.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, (int) (effect * 160) << 24);
        Theme.outline(x0, y0, x1, y1, (int) (effect * (170 + 85 * pulse)) << 24 | rgb);
        GL11.glPopMatrix();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void drawWaypoints(int mouseX, int mouseY) {
        float size = markerSize();
        Waypoint hovered = waypointAt(mouseX, mouseY);
        float effect = updateHoverEffect(hovered);
        List<Waypoint> onScreen = new ArrayList<>();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getMapWaypoints(viewDimension())) {
            double[] at = waypointScreen(waypoint);
            double wx = at[0], wy = at[1];
            if (wx > -size && wy > -size && wx < width + size && wy < height + size && waypoint != hovered) {
                onScreen.add(waypoint);
            }
        }
        if (hovered != null) {
            // Drawn last so it is on top, and its label always wins.
            onScreen.add(hovered);
        }

        for (Waypoint waypoint : onScreen) {
            if (waypoint == hovered) {
                // Hovered: a pulsing frame, and the marker a little bigger.
                float hoveredSize = size * (1f + HOVER_GROWTH * effect);
                drawHoverFrame(waypoint, screenX(waypoint), screenY(waypoint), hoveredSize, effect);
                WaypointRenderer.drawMapMarker(waypoint, screenX(waypoint), screenY(waypoint), hoveredSize, false);
            } else {
                WaypointRenderer.drawMapMarker(waypoint, screenX(waypoint), screenY(waypoint), size, false);
            }
        }

        // Labels shrink with the markers when zooming out and stop growing at normal size when zooming in.
        float textScale = size / MARKER_SIZE;
        // Labels: skip any that would overlap a label already placed, starting with the hovered one.
        List<Waypoint> labelled = new ArrayList<>();
        List<int[]> rects = new ArrayList<>();
        for (int i = onScreen.size() - 1; i >= 0; i--) {
            Waypoint waypoint = onScreen.get(i);
            // The hovered one's label moves down out of the way of its bigger marker and frame.
            float labelSize = waypoint == hovered ? size * (1f + HOVER_GROWTH * effect) + 8 * effect : size;
            int[] rect = WaypointRenderer
                .getLabelRect(waypoint, screenX(waypoint), screenY(waypoint), labelSize, textScale);
            if (rect == null || (waypoint != hovered && overlapsAny(rect, rects))) {
                continue;
            }
            if (waypoint != hovered && Config.waypointMapLabels == Config.LABELS_HOVER) {
                // Names only under the mouse.
                continue;
            }
            labelled.add(waypoint);
            rects.add(rect);
        }
        for (int i = labelled.size() - 1; i >= 0; i--) {
            // Moved by the same sub-pixel remainder as its marker, so the two glide together.
            Waypoint waypoint = labelled.get(i);
            double sx = screenX(waypoint), sy = screenY(waypoint);
            GL11.glPushMatrix();
            GL11.glTranslated(sx - Math.round(sx), sy - Math.round(sy), 0);
            WaypointRenderer.drawMapLabel(waypoint, rects.get(i), textScale);
            GL11.glPopMatrix();
        }
    }

    private static boolean overlapsAny(int[] rect, List<int[]> others) {
        for (int[] other : others) {
            if (rect[0] < other[2] && other[0] < rect[2] && rect[1] < other[3] && other[1] < rect[3]) {
                return true;
            }
        }
        return false;
    }

    /** Screen position of the waypoint's marker; in 3D at its height. */
    private double[] waypointScreen(Waypoint waypoint) {
        return toScreen(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5);
    }

    private double screenX(Waypoint waypoint) {
        return waypointScreen(waypoint)[0];
    }

    private double screenY(Waypoint waypoint) {
        return waypointScreen(waypoint)[1];
    }

    private Waypoint waypointAt(int mouseX, int mouseY) {
        if (chunkloadShown()) {
            // The chunk loading view shows no waypoints.
            return null;
        }
        Waypoint best = null;
        double bestDistance = markerSize() / 2 + 2;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getMapWaypoints(viewDimension())) {
            double[] at = waypointScreen(waypoint);
            double wx = at[0], wy = at[1];
            double distance = Math.max(Math.abs(wx - mouseX), Math.abs(wy - mouseY));
            if (distance <= bestDistance) {
                best = waypoint;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Id of the dimension the map shows: the player's, or another one picked from the title. */
    private static int viewDimension() {
        return MapManager.INSTANCE.getViewedDimensionId();
    }

    /** Height for a new waypoint: the ground if known, in another dimension from its saved map. */
    private int waypointY(int x, int z) {
        if (!MapManager.INSTANCE.isViewingOtherDimension()) {
            return surfaceY(x, z);
        }
        int y = MapManager.INSTANCE.getViewSurfaceHeight(x, z);
        return y > 0 ? y : 64;
    }

    /** Height of the ground at the column if its chunk is loaded, otherwise the player's height. */
    private int surfaceY(int x, int z) {
        int playerY = MathHelper.floor_double(mc.thePlayer.boundingBox.minY);
        if (mc.theWorld.provider.hasNoSky || mc.theWorld.getChunkFromBlockCoords(x, z)
            .isEmpty()) {
            return playerY;
        }
        int y = mc.theWorld.getHeightValue(x, z);
        return y > 0 ? y : playerY;
    }

    private static boolean isExplored(MapDimension dimension, int x, int z) {
        return (dimension.peekPixel(x, z) >>> 24) != 0;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && welcome) {
            welcomeWindow.scroll(wheel);
            return;
        }
        if (wheel == 0) {
            return;
        }
        int newIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoomIndex + (wheel > 0 ? 1 : -1)));
        if (newIndex == zoomIndex) {
            return;
        }
        // Keep the block under the cursor in place while zooming.
        anchorScreenX = Mouse.getEventX() * (double) width / mc.displayWidth;
        anchorScreenY = height - Mouse.getEventY() * (double) height / mc.displayHeight;
        double[] offset = screenToWorldOffset(anchorScreenX - width / 2.0, anchorScreenY - height / 2.0);
        anchorWorldX = centerX + offset[0];
        anchorWorldZ = centerZ + offset[1];
        zoomIndex = newIndex;
        zooming = true;
        // Zooming takes the view over from a glide.
        flightStart = 0;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (welcome) {
            // Only its buttons close it; the map waits.
            if (button == 0) {
                onWelcome(welcomeWindow.click(mouseX, mouseY));
            }
            return;
        }
        if (clickDimensionList(mouseX, mouseY)) {
            return;
        }
        if (clickMenu(mouseX, mouseY)) {
            return;
        }
        if (button == 0 && titleX1 > titleX0 && Theme.inside(mouseX, mouseY, titleX0 - 4, 4, titleX1 + 4, 20)) {
            openDimensionList();
            return;
        }
        if (searchAvailable()) {
            searchField.mouseClicked(mouseX, mouseY, button);
            if (searchField.isMouseOver(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (mouseY < HEADER_HEIGHT || mouseY >= height - FOOTER_HEIGHT || mc.currentScreen != this) {
            return;
        }
        if (chunkloadShown() && button == 0 && overLoadHintToggle(mouseX, mouseY)) {
            Config.setChunkloadHints(!Config.chunkloadHints);
            return;
        }
        if (chunkloadShown() && button == 0 && loadBarSegment(mouseX, mouseY) >= 0) {
            clickLoadBar(loadBarSegment(mouseX, mouseY), mouseX, mouseY);
            return;
        }
        if (chunkloadShown() && overLoadPanel(mouseX, mouseY)) {
            return;
        }
        if (chunkloadShown() && (button == 0 || button == 1) && startPick(mouseX, mouseY, button)) {
            return;
        }
        if (!isoShown() && !chunkloadShown()
            && claimsShown()
            && (button == 0 || button == 1)
            && startClaimPaint(mouseX, mouseY, button)) {
            return;
        }
        if (button == 0 && isoShown() && qualityAt(mouseX, mouseY) >= 0) {
            Config.setIsoQuality(qualityAt(mouseX, mouseY));
            return;
        }
        if (button == 0 && onCaveSlider(mouseX, mouseY)) {
            if (mouseY < sliderTop()) {
                MapManager.INSTANCE.setCaveLayerOverride(-1);
            } else {
                draggingCaveSlider = true;
                MapManager.INSTANCE.setCaveLayerOverride(sliderLayerAt(mouseY));
            }
            return;
        }
        if (button == 1) {
            // Right click on a waypoint: its menu (share, teleport, edit, remove, disable); elsewhere the map's menu.
            Waypoint hovered = waypointAt(mouseX, mouseY);
            if (hovered != null) {
                openWaypointMenu(hovered, mouseX, mouseY);
            } else {
                openMenu(mouseX, mouseY);
            }
            return;
        }
        if (button == 0) {
            dragging = true;
            // Grabbing the map stops a glide where it is.
            flightStart = 0;
            lastRawMouseX = Mouse.getX();
            lastRawMouseY = Mouse.getY();
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button == 0) {
            dragging = false;
        }
        if (button == claimButton) {
            finishClaimPaint();
        }
        if (button == pickButton) {
            finishPick();
        }
    }

    // ---------------------------------------------------------------- chunk loading view

    /** Chunks picked by the drag going on in the area loading view. */
    private final Set<Long> pickSelection = new LinkedHashSet<>();
    private int pickButton = -1;
    /**
     * What the drag going on does, set by the keys held when it started: Ctrl+LMB loads the chunks saved in the world,
     * Shift+LMB generates the chunks, Ctrl+Shift+LMB generates them with every cave layer, Ctrl+RMB deletes them from
     * the map, Shift+RMB takes them off the queue.
     */
    private int pickMode = -1;
    private static final int PICK_SAVED = 0, PICK_GENERATE = 1, PICK_GENERATE_CAVES = 2, PICK_DELETE = 3,
        PICK_CANCEL = 4;
    private int pickStartX, pickStartZ, pickEndX = Integer.MIN_VALUE, pickEndZ;

    /**
     * The 3D view and the chunk and region loading views always show the surface, as with the cave mode off: the
     * cave mode is for the flat map only.
     */
    private static void updateSurfaceView() {
        MapManager.INSTANCE.setSurfaceView(Config.isometric || chunkloadView);
    }

    /**
     * The area loading toolbar under the header: its row of buttons (the 3D switch, loading every saved chunk,
     * stopping, deleting the dimension's map), the progress of the loading while there is one, the colors and the
     * mouse keys.
     */
    private static final int LOAD_ROW = HEADER_HEIGHT + 5, LOAD_PROGRESS_HEIGHT = 12;
    /** Buttons of the area loading toolbar, as {@link #loadBarSegment} tells them. */
    private static final int LOAD_3D = 0, LOAD_ALL = 1, LOAD_STOP = 2, LOAD_WIPE = 3;
    private static final int LOAD_GAP = 6;
    /** Color of the 3D switch when on, as the chunks queued for the 3D map. */
    private static final int LOAD_3D_COLOR = ChunkLoadView.LEGEND_PENDING_3D;

    /** Labels of the toolbar's buttons, by {@code LOAD_*}. */
    private String[] loadLabels() {
        return new String[] { Lang.format("wayfarmap.gui.load_3d"), Lang.format("wayfarmap.gui.load_all_saved"),
            Lang.format("wayfarmap.gui.load_stop"), Lang.format("wayfarmap.gui.load_wipe") };
    }

    /** Whether the Stop button does anything: an area is being loaded, or chunks are shown queued. */
    private static boolean canStopLoading() {
        return ChunkLoadClient.INSTANCE.isRunning() || ChunkLoadView.pendingCountAll() > 0;
    }

    /** Left and right edges of the toolbar's buttons ({@code LOAD_*} twice: x0, x1), centered. */
    private int[] loadLayout() {
        String[] labels = loadLabels();
        int[] widths = new int[labels.length];
        int total = LOAD_GAP * (labels.length - 1);
        for (int i = 0; i < labels.length; i++) {
            widths[i] = fontRendererObj.getStringWidth(labels[i]) + 22;
            total += widths[i];
        }
        int[] edges = new int[labels.length * 2];
        int x = width / 2 - total / 2;
        for (int i = 0; i < labels.length; i++) {
            edges[2 * i] = x;
            edges[2 * i + 1] = x + widths[i];
            x += widths[i] + LOAD_GAP;
        }
        return edges;
    }

    /** Top of the progress bar, or -1 while no loading is shown. */
    private int loadProgressRow() {
        return ChunkLoadClient.INSTANCE.isShown() ? LOAD_ROW + LOAD_BAR_HEIGHT + 5 : -1;
    }

    /** Top of the legend line: under the progress bar when there is one. */
    private int loadLegendRow() {
        int progress = loadProgressRow();
        return progress >= 0 ? progress + LOAD_PROGRESS_HEIGHT + 5 : LOAD_ROW + LOAD_BAR_HEIGHT + 5;
    }

    /** Bottom of the whole toolbar: the mouse keys under the legend only while the hints are open. */
    private int loadPanelBottom() {
        return loadLegendRow() + ((Config.chunkloadHints ? loadHintLines().length : 0) + 1) * 11 + 1;
    }

    /** Room on each side of the legend for the hints' toggle in the toolbar's lower right corner. */
    private static final int LOAD_TOGGLE_ROOM = 16;

    /** The button opening and closing the hints, in the toolbar's lower right corner: x0, y0, x1, y1. */
    private int[] loadHintToggle() {
        int right = loadPanelEdges()[1] + 2, bottom = loadPanelBottom() - 2;
        return new int[] { right - 11, bottom - 10, right, bottom };
    }

    private boolean overLoadHintToggle(int mouseX, int mouseY) {
        int[] box = loadHintToggle();
        return Theme.inside(mouseX, mouseY, box[0], box[1], box[2], box[3]);
    }

    /** What of the area loading toolbar is under the mouse ({@code LOAD_*}), -1 none. */
    private int loadBarSegment(int mouseX, int mouseY) {
        if (mouseY < LOAD_ROW || mouseY >= LOAD_ROW + LOAD_BAR_HEIGHT) {
            return -1;
        }
        int[] edges = loadLayout();
        for (int i = 0; i < edges.length / 2; i++) {
            if (mouseX >= edges[2 * i] && mouseX < edges[2 * i + 1]) {
                return i;
            }
        }
        return -1;
    }

    /** Left and right edges of the whole toolbar: its buttons and the hints under them. */
    private int[] loadPanelEdges() {
        int[] edges = loadLayout();
        int x0 = edges[0], x1 = edges[edges.length - 1];
        if (Config.chunkloadHints) {
            for (String line : loadHintLines()) {
                int w = fontRendererObj.getStringWidth(line) + 2 * LOAD_TOGGLE_ROOM;
                x0 = Math.min(x0, width / 2 - w / 2);
                x1 = Math.max(x1, width / 2 + w / 2);
            }
        }
        int legend = legendWidth() + 2 * LOAD_TOGGLE_ROOM;
        return new int[] { Math.min(x0, width / 2 - legend / 2), Math.max(x1, width / 2 + legend / 2) };
    }

    /** Whether the mouse is over the area loading toolbar (a drag there doesn't start). */
    private boolean overLoadPanel(int mouseX, int mouseY) {
        int[] edges = loadPanelEdges();
        return Theme.inside(mouseX, mouseY, edges[0] - 5, LOAD_ROW - 4, edges[1] + 5, loadPanelBottom());
    }

    /** A click on the toolbar's button. */
    private void clickLoadBar(int segment, int mouseX, int mouseY) {
        if (segment == LOAD_3D) {
            Config.setChunkload3d(!Config.chunkload3d);
        } else if (segment == LOAD_ALL) {
            confirmLoadAllSaved(mouseX, mouseY);
        } else if (segment == LOAD_STOP) {
            if (canStopLoading()) {
                // The server ends the loading and tells back: the queued chunks go away then, the batch is dropped.
                mc.thePlayer.sendChatMessage("/wf chunkload stop");
            }
        } else if (segment == LOAD_WIPE) {
            confirmWipeDimension(mouseX, mouseY);
        }
    }

    /**
     * Asks before deleting the whole map of the player's dimension: the 2D map, and the 3D map with it while the
     * view's 3D switch is on. The maps are saved and closed first and opened again empty (as the map data screen
     * does).
     */
    private void confirmWipeDimension(int mouseX, int mouseY) {
        int dimension = mc.theWorld.provider.dimensionId;
        boolean with3d = Config.chunkload3d;
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(
            new MenuEntry(
                Lang.format(with3d ? "wayfarmap.gui.load_wipe_3d" : "wayfarmap.gui.load_wipe_2d"),
                true,
                () -> wipeDimension(dimension, with3d)).icon(Icons.SMALL_TRASH)
                    .danger());
        entries.add(new MenuEntry(Lang.format("gui.cancel"), true, () -> {}));
        showMenu(entries, MENU_CONFIRM, mouseX, mouseY + 4);
    }

    private void wipeDimension(int dimension, boolean with3d) {
        File world = MapManager.INSTANCE.getWorldDirectory();
        if (world == null) {
            return;
        }
        File directory = new File(world, "dim" + dimension);
        ChunkLoadView.clearPending(dimension);
        MapManager.INSTANCE
            .resetMaps((with3d ? "2D and 3D map" : "2D map") + " of dim" + dimension + " (area loading view)", () -> {
                MapCleaner.delete2d(directory);
                if (with3d) {
                    MapCleaner.delete3d(directory);
                }
            });
        ChunkLoadView.refresh();
        mc.thePlayer
            .addChatMessage(Lang.chat(with3d ? "wayfarmap.chunkload.wiped_3d" : "wayfarmap.chunkload.wiped_2d"));
    }

    /**
     * Asks before mapping every chunk saved in the world's region files of this dimension ({@code /wf regionload
     * full}, for the 3D map too while the view's 3D switch is on): it can be a lot, and it takes the place of the
     * chunks queued so far.
     */
    private void confirmLoadAllSaved(int mouseX, int mouseY) {
        int dimension = mc.theWorld.provider.dimensionId;
        boolean with3d = Config.chunkload3d;
        List<MenuEntry> entries = new ArrayList<>();
        entries.add(
            new MenuEntry(
                Lang.format(with3d ? "wayfarmap.gui.load_all_saved_yes_3d" : "wayfarmap.gui.load_all_saved_yes"),
                true,
                () -> {
                    ChunkLoadView.clearPending(dimension);
                    mc.thePlayer.sendChatMessage("/wf regionload " + (with3d ? "3d" : "2d") + " full");
                }));
        entries.add(new MenuEntry(Lang.format("gui.cancel"), true, () -> {}));
        showMenu(entries, MENU_CONFIRM, mouseX, mouseY + 4);
    }

    /** The lines of keys under the legend: loading on the first, deleting and cancelling on the second. */
    private String[] loadHintLines() {
        return new String[] { Lang.format("wayfarmap.gui.load_keys_load"),
            Lang.format("wayfarmap.gui.load_keys_remove") };
    }

    private String[] legendWords() {
        return new String[] { Lang.format("wayfarmap.gui.load_legend_mapped"),
            Lang.format("wayfarmap.gui.load_legend_mapped_3d"), Lang.format("wayfarmap.gui.load_legend_mapped_both"),
            Lang.format("wayfarmap.gui.load_legend_saved"), Lang.format("wayfarmap.gui.load_legend_pending"),
            Lang.format("wayfarmap.gui.load_legend_pending_3d") };
    }

    /** Width of the legend line: a square before each color's word. */
    private int legendWidth() {
        int w = 0;
        String[] words = legendWords();
        for (int i = 0; i < words.length; i++) {
            w += 9 + fontRendererObj.getStringWidth(words[i]) + (i < words.length - 1 ? 16 : 0);
        }
        return w;
    }

    /** Minutes and seconds (or hours too) of a time in milliseconds. */
    private static String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The text on the progress bar: how many, how fast, the time left (or how long it took once done). */
    private static String loadProgressText() {
        ChunkLoadClient load = ChunkLoadClient.INSTANCE;
        String counts = String.format(Locale.US, "%,d / %,d", load.done(), load.total());
        String map = load.isWith3d() ? "2D + 3D" : "2D";
        if (load.isFinished()) {
            return Lang.format("wayfarmap.gui.load_progress_done", map, counts, clock(load.elapsedMs()));
        }
        long percent = Math.round(load.fraction() * 100);
        double speed = load.chunksPerSecond();
        long left = load.remainingMs();
        String rate = speed > 0 ? String.format(Locale.US, "%.1f", speed) : "...";
        String eta = left >= 0 ? clock(left) : "...";
        return Lang.format("wayfarmap.gui.load_progress", map, counts, percent, rate, clock(load.elapsedMs()), eta);
    }

    /**
     * The toolbar under the header of the area loading view: the 3D switch, loading every saved chunk, stopping,
     * deleting the dimension's whole map; under it the progress, the colors and the mouse keys.
     */
    private void drawLoadBar(int mouseX, int mouseY) {
        int[] edges = loadLayout();
        String[] labels = loadLabels();
        int[] panel = loadPanelEdges();
        int bottom = loadPanelBottom();
        Theme.fill(panel[0] - 5, LOAD_ROW - 4, panel[1] + 5, bottom, Theme.PANEL);
        Theme.outline(panel[0] - 5, LOAD_ROW - 4, panel[1] + 5, bottom, Theme.BORDER);
        int hovered = menu == null && dimensionList == null ? loadBarSegment(mouseX, mouseY) : -1;
        int y0 = LOAD_ROW, y1 = LOAD_ROW + LOAD_BAR_HEIGHT;
        boolean canStop = canStopLoading();

        for (int i = 0; i < labels.length; i++) {
            int sx = edges[2 * i], ex = edges[2 * i + 1];
            boolean over = hovered == i;
            boolean disabled = i == LOAD_STOP && !canStop;
            boolean on = i == LOAD_3D && Config.chunkload3d;
            int line = i == LOAD_WIPE || i == LOAD_STOP ? Theme.DANGER : on ? LOAD_3D_COLOR : Theme.ACCENT;
            Theme.fill(sx, y0, ex, y1, disabled ? Theme.CONTROL_DISABLED : over ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(sx, y0, ex, y1, on ? LOAD_3D_COLOR : over && !disabled ? line : Theme.BORDER);
            if (i == LOAD_3D) {
                // A check box: filled with the 3D color and ticked when on.
                Theme.outline(sx + 5, y0 + 3, sx + 13, y0 + 11, on ? LOAD_3D_COLOR : Theme.TEXT_MUTED);
                if (on) {
                    Theme.fill(sx + 7, y0 + 5, sx + 11, y0 + 9, LOAD_3D_COLOR);
                }
            } else if (i == LOAD_STOP) {
                // A stop square, red while there is something to stop.
                Theme.fill(sx + 6, y0 + 4, sx + 12, y0 + 10, disabled ? Theme.TEXT_DISABLED : Theme.DANGER);
            } else {
                // A mark in the color of what it does: gray like the saved chunks, red for deleting.
                Theme.fill(sx + 6, y0 + 4, sx + 12, y0 + 10, i == LOAD_ALL ? ChunkLoadView.LEGEND_SAVED : Theme.DANGER);
            }
            int color = disabled ? Theme.TEXT_DISABLED
                : i == LOAD_WIPE ? (over ? Theme.DANGER : 0xFFB0605A)
                    : on ? LOAD_3D_COLOR : over ? Theme.TEXT : Theme.TEXT_MUTED;
            Theme.text(fontRendererObj, labels[i], sx + 17, y0 + 3, color);
        }

        // The loading going on: a bar filling up, with how many, how fast and the time left on it.
        int progressRow = loadProgressRow();
        if (progressRow >= 0) {
            ChunkLoadClient load = ChunkLoadClient.INSTANCE;
            int bx0 = panel[0], bx1 = panel[1], by0 = progressRow, by1 = progressRow + LOAD_PROGRESS_HEIGHT;
            int barColor = load.isFinished() ? Theme.SUCCESS : load.isWith3d() ? LOAD_3D_COLOR : Theme.ACCENT;
            Theme.fill(bx0, by0, bx1, by1, Theme.CONTROL);
            int filled = bx0 + (int) Math.round((bx1 - bx0) * load.fraction());
            Theme.fill(bx0, by0, filled, by1, (barColor & 0xFFFFFF) | 0x80000000);
            Theme.outline(bx0, by0, bx1, by1, barColor);
            String text = Theme.ellipsize(fontRendererObj, loadProgressText(), bx1 - bx0 - 8);
            Theme.centered(fontRendererObj, text, (bx0 + bx1) / 2, by0 + 2, Theme.TEXT);
        }

        // The colors: each word after a square of it.
        int legendRow = loadLegendRow();
        String[] words = legendWords();
        int[] squares = { ChunkLoadView.LEGEND_MAPPED, ChunkLoadView.LEGEND_MAPPED_3D, ChunkLoadView.LEGEND_MAPPED_BOTH,
            ChunkLoadView.LEGEND_SAVED, ChunkLoadView.LEGEND_PENDING, ChunkLoadView.LEGEND_PENDING_3D };
        int hx = width / 2 - legendWidth() / 2;
        for (int i = 0; i < words.length; i++) {
            Theme.fill(hx, legendRow + 1, hx + 6, legendRow + 7, squares[i]);
            hx += 9;
            Theme.text(fontRendererObj, words[i], hx, legendRow, Theme.TEXT_MUTED);
            hx += fontRendererObj.getStringWidth(words[i]) + 16;
        }
        // The mouse keys, while the hints are open.
        if (Config.chunkloadHints) {
            String[] lines = loadHintLines();
            for (int i = 0; i < lines.length; i++) {
                Theme.centered(fontRendererObj, lines[i], width / 2, legendRow + 11 * (i + 1), Theme.TEXT_MUTED);
            }
        }
        // The hints' toggle: an arrow up to close them, down to open them.
        boolean overToggle = menu == null && dimensionList == null && overLoadHintToggle(mouseX, mouseY);
        int[] toggle = loadHintToggle();
        Theme.fill(toggle[0], toggle[1], toggle[2], toggle[3], overToggle ? Theme.CONTROL_HOVER : Theme.CONTROL);
        Theme.outline(toggle[0], toggle[1], toggle[2], toggle[3], overToggle ? Theme.ACCENT : Theme.BORDER);
        int arrowColor = overToggle ? Theme.TEXT : Theme.TEXT_MUTED;
        int ax = (toggle[0] + toggle[2]) / 2, ay = (toggle[1] + toggle[3]) / 2 - 1;
        for (int row = 0; row < 3; row++) {
            int ry = Config.chunkloadHints ? ay + 2 - row : ay + row;
            Theme.fill(ax - 2 + row, ry, ax + 3 - row, ry + 1, arrowColor);
        }

        if (hovered >= 0) {
            String[] descriptions = {
                Config.chunkload3d ? "wayfarmap.gui.load_3d.desc_on" : "wayfarmap.gui.load_3d.desc",
                "wayfarmap.gui.load_all_saved.desc", "wayfarmap.gui.load_stop.desc", "wayfarmap.gui.load_wipe.desc" };
            drawHoveringText(
                fontRendererObj.listFormattedStringToWidth(Lang.format(descriptions[hovered]), 240),
                mouseX,
                mouseY,
                fontRendererObj);
        } else if (overToggle) {
            String tip = Lang
                .format(Config.chunkloadHints ? "wayfarmap.gui.load_hints_hide" : "wayfarmap.gui.load_hints_show");
            drawHoveringText(Collections.singletonList(tip), mouseX, mouseY, fontRendererObj);
        } else if (pickButton >= 0 && !pickSelection.isEmpty()) {
            drawPickInfo(mouseX, mouseY);
        }
    }

    /**
     * By the mouse while dragging: what the drag does, how big the rectangle is and how many of its chunks it takes.
     */
    private void drawPickInfo(int mouseX, int mouseY) {
        int dimension = mc.theWorld.provider.dimensionId;
        int w = Math.min(Math.abs(pickEndX - pickStartX) + 1, ChunkLoadView.MAX_SIDE);
        int h = Math.min(Math.abs(pickEndZ - pickStartZ) + 1, ChunkLoadView.MAX_SIDE);
        String size = Lang.format("wayfarmap.gui.pick_size", w, h, pickSelection.size());
        String action;
        String count;
        int color;
        switch (pickMode) {
            case PICK_SAVED:
                action = Lang.format("wayfarmap.gui.pick_saved");
                count = Lang
                    .format("wayfarmap.gui.pick_count_saved", ChunkLoadView.countSaved(dimension, pickSelection));
                color = Config.chunkload3d ? LOAD_3D_COLOR : ChunkLoadView.LEGEND_PENDING;
                break;
            case PICK_GENERATE:
                action = Lang.format("wayfarmap.gui.pick_generate");
                count = null;
                color = Config.chunkload3d ? LOAD_3D_COLOR : ChunkLoadView.LEGEND_PENDING;
                break;
            case PICK_GENERATE_CAVES:
                action = Lang.format("wayfarmap.gui.pick_generate_caves");
                count = null;
                color = 0xFF58A6FF;
                break;
            case PICK_CANCEL:
                action = Lang.format("wayfarmap.gui.pick_cancel");
                count = Lang
                    .format("wayfarmap.gui.pick_count_queued", ChunkLoadView.countPending(dimension, pickSelection));
                color = Theme.TEXT;
                break;
            case PICK_DELETE:
                action = Lang.format(Config.chunkload3d ? "wayfarmap.gui.pick_delete_3d" : "wayfarmap.gui.pick_delete");
                count = Lang.format(
                    "wayfarmap.gui.pick_count_mapped",
                    ChunkLoadView.countOnMap(MapManager.INSTANCE.getViewMap(), pickSelection));
                color = Theme.DANGER;
                break;
            default:
                return;
        }
        if (Config.chunkload3d
            && (pickMode == PICK_SAVED || pickMode == PICK_GENERATE || pickMode == PICK_GENERATE_CAVES)) {
            action += " §6+3D";
        }
        List<String> lines = new ArrayList<>();
        lines.add(action);
        lines.add("§7" + size);
        if (count != null) {
            lines.add("§7" + count);
        }
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fontRendererObj.getStringWidth(line));
        }
        int x = Math.min(mouseX + 12, width - textWidth - 6), y = mouseY + 12;
        if (y + lines.size() * 10 + 4 > height - FOOTER_HEIGHT) {
            y = mouseY - lines.size() * 10 - 8;
        }
        Theme.fill(x - 4, y - 3, x + textWidth + 4, y + lines.size() * 10 + 1, Theme.LABEL_BG);
        Theme.fill(x - 4, y - 3, x - 2, y + lines.size() * 10 + 1, color);
        for (int i = 0; i < lines.size(); i++) {
            Theme.text(fontRendererObj, lines.get(i), x + 1, y + i * 10, i == 0 ? color : Theme.TEXT);
        }
    }

    /** The area loading view: the flat map of the player's own dimension. */
    private boolean chunkloadShown() {
        if (chunkloadView && !ChunkLoadView.isAllowed()) {
            // No longer allowed (or another server): back to the flat map.
            chunkloadView = false;
            updateSurfaceView();
        }
        // The surface even in caves (MapManager's surface view): only another dimension or 3D leave it out.
        return chunkloadView && !isoShown() && !MapManager.INSTANCE.isViewingOtherDimension();
    }

    /**
     * A drag with Ctrl or Shift picks a rectangle of chunks; what it does depends on the button and the keys held when
     * it starts (see {@link #pickMode}).
     */
    private boolean startPick(int mouseX, int mouseY, int button) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = isShiftDown();
        if (button == 0) {
            pickMode = ctrl && shift ? PICK_GENERATE_CAVES : ctrl ? PICK_SAVED : shift ? PICK_GENERATE : -1;
        } else {
            pickMode = ctrl ? PICK_DELETE : shift ? PICK_CANCEL : -1;
        }
        if (pickMode < 0) {
            return false;
        }
        pickButton = button;
        pickStartX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        pickStartZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        pickEndX = Integer.MIN_VALUE;
        updatePickRectangle(pickStartX, pickStartZ);
        return true;
    }

    private void updatePick(int mouseX, int mouseY) {
        if (pickButton < 0) {
            return;
        }
        if (!Mouse.isButtonDown(pickButton)) {
            finishPick();
            return;
        }
        updatePickRectangle(
            MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4,
            MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4);
    }

    private void updatePickRectangle(int endX, int endZ) {
        if (endX == pickEndX && endZ == pickEndZ) {
            return;
        }
        pickEndX = endX;
        pickEndZ = endZ;
        pickSelection.clear();
        pickSelection.addAll(ChunkLoadView.rectangle(pickStartX, pickStartZ, endX, endZ));
    }

    /**
     * The drag ends and does its work at once: loads, deletes from the map (both for the 3D map too while blocks are
     * recorded) or takes the chunks off the queue.
     */
    private void finishPick() {
        if (pickButton < 0) {
            return;
        }
        pickButton = -1;
        int dimension = mc.theWorld.provider.dimensionId;
        int mode = pickMode;
        pickMode = -1;
        switch (mode) {
            case PICK_SAVED:
                ChunkLoadView.pick(dimension, pickSelection, false, true, false);
                break;
            case PICK_GENERATE:
                ChunkLoadView.pick(dimension, pickSelection, false, false, false);
                break;
            case PICK_GENERATE_CAVES:
                ChunkLoadView.pick(dimension, pickSelection, false, false, true);
                break;
            case PICK_CANCEL:
                if (ChunkLoadView.anyPending(dimension, pickSelection)) {
                    ChunkLoadView.pick(dimension, pickSelection, true, true, false);
                }
                break;
            case PICK_DELETE:
                if (ChunkLoadView.anyPending(dimension, pickSelection)) {
                    ChunkLoadView.pick(dimension, pickSelection, true, true, false);
                }
                if (ChunkLoadView.countOnMap(MapManager.INSTANCE.getViewMap(), pickSelection) > 0) {
                    deleteChunks(dimension, new ArrayList<>(pickSelection), Config.chunkload3d);
                }
                break;
            default:
                break;
        }
        pickSelection.clear();
    }

    private void deleteChunks(int dimension, List<Long> chunks, boolean with3d) {
        int deleted = MapManager.INSTANCE.deleteFlatChunks(dimension, chunks);
        if (with3d) {
            IsoMap.INSTANCE.deleteChunks(dimension, chunks);
        }
        ChunkLoadView.refresh();
        mc.thePlayer.addChatMessage(
            Lang.chat(with3d ? "wayfarmap.chunkload.deleted_3d" : "wayfarmap.chunkload.deleted_2d", deleted));
    }

    private static boolean isShiftDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    // ---------------------------------------------------------------- claims painting

    /** Claims come from the server for the player's dimension only, so other dimensions don't show them. */
    private static boolean claimsShown() {
        return Config.showClaims && Mods.isClaimsAvailable() && !MapManager.INSTANCE.isViewingOtherDimension();
    }

    /**
     * With Ctrl and/or Shift held, a drag selects the rectangle of chunks between where it started and the mouse
     * instead of moving the map. Left button: Ctrl claims, Shift chunk loads own claims, both claim and load. Right
     * button: Ctrl unclaims, Shift unloads, both unload and unclaim.
     */
    private boolean startClaimPaint(int mouseX, int mouseY, int button) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (!ctrl && !shift) {
            return false;
        }
        if (button == 0) {
            claimAction = ctrl && shift ? ClaimsLayer.CLAIM_AND_LOAD : ctrl ? ClaimsLayer.CLAIM : ClaimsLayer.LOAD;
        } else {
            claimAction = ctrl && shift ? ClaimsLayer.UNLOAD_AND_UNCLAIM
                : ctrl ? ClaimsLayer.UNCLAIM : ClaimsLayer.UNLOAD;
        }
        claimButton = button;
        claimStartX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        claimStartZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        claimEndX = Integer.MIN_VALUE;
        updateClaimRectangle(claimStartX, claimStartZ);
        return true;
    }

    /** Called every frame: adds the chunks under the mouse, and finishes when the button was released. */
    private void updateClaimPaint(int mouseX, int mouseY) {
        if (claimButton < 0) {
            return;
        }
        if (!Mouse.isButtonDown(claimButton)) {
            finishClaimPaint();
            return;
        }
        int chunkX = MathHelper.floor_double(centerX + (mouseX - width / 2.0) / scale) >> 4;
        int chunkZ = MathHelper.floor_double(centerZ + (mouseY - height / 2.0) / scale) >> 4;
        updateClaimRectangle(chunkX, chunkZ);
    }

    /** Selects every fitting chunk in the rectangle from the start chunk to the given one. */
    private void updateClaimRectangle(int endX, int endZ) {
        if (endX == claimEndX && endZ == claimEndZ) {
            return;
        }
        claimEndX = endX;
        claimEndZ = endZ;
        claimSelection.clear();
        int dimension = mc.theWorld.provider.dimensionId;
        int minX = Math.min(claimStartX, endX), maxX = Math.max(claimStartX, endX);
        int minZ = Math.min(claimStartZ, endZ), maxZ = Math.max(claimStartZ, endZ);
        // Capped so a huge accidental drag can't send thousands of chunks.
        maxX = Math.min(maxX, minX + 63);
        maxZ = Math.min(maxZ, minZ + 63);
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                if (ClaimsLayer.accepts(claimAction, chunkX, chunkZ, dimension)) {
                    claimSelection.add(ClaimsLayer.pack(chunkX, chunkZ));
                }
            }
        }
    }

    private void finishClaimPaint() {
        if (claimButton < 0) {
            return;
        }
        claimButton = -1;
        ClaimsLayer.apply(claimAction, new ArrayList<>(claimSelection));
        claimSelection.clear();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (welcome) {
            onWelcome(welcomeWindow.key(keyCode));
            return;
        }
        if (dimensionList != null && keyCode == Keyboard.KEY_ESCAPE) {
            dimensionList = null;
            return;
        }
        if (menu != null && keyCode == Keyboard.KEY_ESCAPE) {
            menu = null;
            return;
        }
        if (pickButton >= 0 && keyCode == Keyboard.KEY_ESCAPE) {
            // A drag of the area loading view is let go without doing anything.
            pickButton = -1;
            pickMode = -1;
            pickSelection.clear();
            return;
        }
        if (searchAvailable() && searchField.isFocused()) {
            // Typing goes to the search; Esc clears it (or leaves the field when empty), Enter leaves the field.
            if (keyCode == Keyboard.KEY_ESCAPE && !searchField.getText()
                .isEmpty()) {
                searchField.setText("");
            } else if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER) {
                    searchField.setFocused(false);
                } else {
                    searchField.textboxKeyTyped(typedChar, keyCode);
                }
            if (!searchField.getText()
                .equals(searchText)) {
                searchText = searchField.getText();
                applySearch();
            }
            return;
        }
        if ((keyCode == Keyboard.KEY_C || keyCode == Keyboard.KEY_V) && isCtrlKeyDown()) {
            int mouseX = Mouse.getX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getY() * height / mc.displayHeight - 1;
            menu = null;
            if (keyCode == Keyboard.KEY_C) {
                copyAtMouse(mouseX, mouseY);
            } else {
                pasteAtMouse(mouseX, mouseY);
            }
            return;
        }
        if (keyCode == KeyHandler.OPEN_MAP.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (isoShown() && (keyCode == Keyboard.KEY_Q || keyCode == Keyboard.KEY_E)) {
            // Turns the 3D view: E clockwise, Q the other way.
            rotateIso(keyCode == Keyboard.KEY_E ? 1 : -1);
            return;
        }
        if (keyCode == Keyboard.KEY_SPACE && mc.thePlayer != null) {
            MapManager.INSTANCE.stopViewing();
            applySearch();
            // At the zoom it settles on, so the glide is measured at the scale it ends at.
            zooming = false;
            scale = Config.MAP_ZOOMS[zoomIndex];
            flyTo(mc.thePlayer.posX, mc.thePlayer.boundingBox.minY, mc.thePlayer.posZ);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        MapManager.INSTANCE.setSurfaceView(false);
        Keyboard.enableRepeatEvents(false);
        // Veins on the minimap go back to NEI's search; the map's search comes back when it is opened again.
        if (Mods.isVisualProspectingLoaded()) {
            ProspectingLayer.setSearch("");
        }
        if (Mods.isPowerfailsAvailable()) {
            PowerfailLayer.setSearch("");
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            ThaumcraftNodes.setSearch("");
        }
        saveView();
        if (!ScaledScreen.isBeingCovered(this)) {
            // Only when really closed: under a screen opened over it, it is still shown.
            MapManager.INSTANCE.trimAroundPlayer(mc.thePlayer);
        }
    }

    // ---------------------------------------------------------------- where the map was left

    /**
     * Where the map was last closed, per dimension the player was in: the dimension looked at, the center and the
     * zoom, as "dimension;x;z;zoom" in the account's world folder, so it survives restarts.
     */
    private static final String VIEW_FILE = "map-view.properties";

    private static Properties readViews(File file) {
        Properties views = new Properties();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                views.load(in);
            } catch (IOException e) {
                // Lost view positions only mean the map opens at the player.
            }
        }
        return views;
    }

    /** Opens the map where it was closed; false if there is nothing saved (or its dimension has no map). */
    private boolean restoreView() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null) {
            return false;
        }
        int playerDimension = mc.theWorld.provider.dimensionId;
        String saved = readViews(new File(worldDirectory, VIEW_FILE)).getProperty("dim" + playerDimension);
        if (saved == null) {
            return false;
        }
        try {
            String[] parts = saved.split(";");
            int dimension = Integer.parseInt(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            int zoom = Integer.parseInt(parts[3]);
            if (dimension != playerDimension) {
                boolean known = false;
                for (MapManager.SavedDimension other : MapManager.INSTANCE.listSavedDimensions()) {
                    known |= other.id == dimension;
                }
                if (!known) {
                    return false;
                }
                MapManager.INSTANCE.viewDimension(dimension);
            }
            centerX = x;
            centerZ = z;
            zoomIndex = Math.max(0, Math.min(Config.MAP_ZOOMS.length - 1, zoom));
            scale = Config.MAP_ZOOMS[zoomIndex];
            zooming = false;
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void saveView() {
        File worldDirectory = MapManager.INSTANCE.getWorldDirectory();
        if (worldDirectory == null || mc.theWorld == null || !initialized) {
            return;
        }
        File file = new File(worldDirectory, VIEW_FILE);
        Properties views = readViews(file);
        views.setProperty(
            "dim" + mc.theWorld.provider.dimensionId,
            viewDimension() + ";" + centerX + ";" + centerZ + ";" + zoomIndex);
        try (OutputStream out = new FileOutputStream(file)) {
            views.store(out, "WayFarMap: where the world map was last closed, per dimension the player was in");
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not save the map position to " + file, e);
        }
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
        // Twice a second: free the regions scrolled away from, so a long look around doesn't fill the memory.
        if (++ticks % 10 == 0 && mc.thePlayer != null) {
            double[] box = visibleWorldBox();
            MapManager.INSTANCE.trimForView(
                mc.thePlayer,
                (MathHelper.floor_double(box[0]) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(box[1]) >> MapRegion.SHIFT) - 1,
                (MathHelper.floor_double(box[2]) >> MapRegion.SHIFT) + 1,
                (MathHelper.floor_double(box[3]) >> MapRegion.SHIFT) + 1);
        }
    }

    /** World box {minX, minZ, maxX, maxZ} on the screen; in 3D all the ground that can show up, at any height. */
    private double[] visibleWorldBox() {
        if (!isoShown()) {
            double halfWidth = width / 2.0 / scale, halfHeight = height / 2.0 / scale;
            return new double[] { centerX - halfWidth, centerZ - halfHeight, centerX + halfWidth,
                centerZ + halfHeight };
        }
        IsoProjection p = isoProjection();
        double u0 = p.u(centerX, centerZ), v0 = p.v(centerX, IsoProjection.REFERENCE_Y, centerZ);
        double[] box = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (double sx : new double[] { -width / 2.0, width / 2.0 }) {
            for (double sy : new double[] { -height / 2.0, height / 2.0 }) {
                for (double y : new double[] { 0, IsoProjection.TOP }) {
                    double[] point = p.unproject(u0 + sx / scale, v0 + sy / scale, y);
                    box[0] = Math.min(box[0], point[0]);
                    box[1] = Math.min(box[1], point[1]);
                    box[2] = Math.max(box[2], point[0]);
                    box[3] = Math.max(box[3], point[1]);
                }
            }
        }
        return box;
    }

    /** The map fills the window: it is there at once. */
    @Override
    protected boolean slidesIn() {
        return false;
    }

    /** The map fills the window: nothing behind it would show. */
    @Override
    protected boolean showsScreenBehind() {
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
