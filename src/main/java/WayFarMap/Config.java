package WayFarMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraftforge.common.config.Configuration;

/**
 * Mod settings. Every setting is declared once as an {@link Option}; the list drives loading, saving and the in-game
 * settings screen.
 */
public class Config {

    public static final String CATEGORY_MINIMAP = "minimap";
    public static final String CATEGORY_MAP = "map";
    public static final String CATEGORY_ENTITIES = "entities";
    public static final String CATEGORY_WAYPOINTS = "waypoints";
    public static final String CATEGORY_LOGS = "logs";
    public static final String CATEGORY_PLAYER_MARKER = "playerMarker";
    public static final String CATEGORY_COMMANDS = "commands";
    /**
     * Tabs of the settings screen splitting the world map's options (still saved under {@link #CATEGORY_MAP}, so
     * nothing set before is lost).
     */
    public static final String TAB_MAP = CATEGORY_MAP, TAB_MAP_2D = "map2d", TAB_MAP_3D = "map3d";
    /** Tab of the mobs' options (saved under {@link #CATEGORY_ENTITIES}, where they were before). */
    public static final String TAB_MOBS = "mobs";
    /** Categories in the order the settings screen shows them. */
    public static final List<String> CATEGORIES = Collections.unmodifiableList(
        Arrays.asList(
            CATEGORY_MINIMAP,
            TAB_MAP,
            TAB_MAP_2D,
            TAB_MAP_3D,
            CATEGORY_PLAYER_MARKER,
            CATEGORY_ENTITIES,
            TAB_MOBS,
            CATEGORY_WAYPOINTS,
            CATEGORY_COMMANDS,
            CATEGORY_LOGS));

    /** Minimap zoom levels, in GUI pixels per block. */
    public static final double[] MINIMAP_ZOOMS = { 0.125, 0.25, 0.5, 1.0, 2.0, 4.0 };
    /** Fullscreen map zoom levels, in GUI pixels per block. */
    public static final double[] MAP_ZOOMS = { 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0 };

    /**
     * Buttons of the world map that can be hidden (lang {@code wayfarmap.option.map.button_<name>}); the settings
     * button and the dimension title always stay.
     */
    public static final String[] MAP_BUTTONS = { "waypoints", "stats", "export", "addons", "follow", "light", "caves",
        "modes", "grid", "mobs", "team", "help", "about" };
    private static final boolean[] mapButtonShown = new boolean[MAP_BUTTONS.length];

    public static final int LIGHT_AUTO = 0, LIGHT_DAY = 1, LIGHT_NIGHT = 2;
    public static final int CAVES_AUTO = 0, CAVES_OFF = 1, CAVES_ON = 2;
    public static final int DISPLAY_BLOCKS = 0, DISPLAY_BIOMES = 1, DISPLAY_TOPO = 2;

    public static boolean minimapEnabled = true;
    public static int minimapSize = 100;
    /**
     * Where the minimap is: 0 puts it at the left (top) edge of the screen, 1 at the right (bottom) one, in between
     * anywhere on the way. Kept as a share of the free room, so it stays on screen at any window size.
     */
    public static double minimapX = 1.0, minimapY = 0.0;
    public static int minimapZoom = 3;
    public static boolean minimapShowCoordinates = true;
    public static boolean minimapShowBiome = true;
    public static final int SHAPE_SQUARE = 0, SHAPE_ROUND = 1;
    public static int minimapShape = SHAPE_SQUARE;
    /** Turn the minimap with the player, so the view direction is always up. */
    public static boolean minimapRotate = false;
    /** N, E, S and W on the edge of the minimap. */
    public static boolean minimapCompass = true;
    /** Size of the compass letters, of the text under the minimap (1 = the game's font), and its room from the map. */
    public static double minimapCompassScale = 1.0, minimapTextScale = 1.0;
    public static int minimapTextGap = 3;
    /** Frame around the minimap, and the color of its line (RGB). */
    public static boolean minimapFrame = true;
    public static final int MINIMAP_FRAME_COLOR = 0x2A313B;
    public static int minimapFrameColor = MINIMAP_FRAME_COLOR;
    /** How opaque the minimap's frame is, in percent, and how thick its colored line is, in pixels. */
    public static int minimapFrameOpacity = 100, minimapFrameWidth = 1;
    /** The minimap's own settings; each group is used only while its switch is on, else the world map's are. */
    public static boolean minimapOwnView, minimapOwnLayers, minimapOwnGrid, minimapOwnMobs;
    public static final int MINIMAP_VIEW_FLAT = 0, MINIMAP_VIEW_BARE = 1, MINIMAP_VIEW_TOPO = 2,
        MINIMAP_VIEW_BIOMES = 3;
    public static int minimapView = MINIMAP_VIEW_FLAT, minimapLightMode = LIGHT_AUTO;
    public static boolean minimapOreVeins = true, minimapUndergroundFluids, minimapClaims, minimapPowerfails = true,
        minimapThaumcraftNodes = true;
    public static boolean minimapChunkGrid;
    public static boolean minimapPlayers = true, minimapHostileMobs = true, minimapPassiveMobs = true,
        minimapAmbientMobs = true, minimapOtherEntities = true, minimapPets = true;
    /** The player's marker on the world map and the minimap: its look, size (percent), color and outline. */
    public static final int MARKER_ARROW = 0, MARKER_TRIANGLE = 1, MARKER_CHEVRON = 2, MARKER_KITE = 3,
        MARKER_CIRCLE = 4, MARKER_DOT = 5;
    public static int playerMarkerStyle = MARKER_ARROW;
    public static int playerMarkerScale = 100;
    public static final int PLAYER_MARKER_COLOR = 0xFFFFFF;
    public static int playerMarkerColor = PLAYER_MARKER_COLOR;
    /** A dark line around the marker, so it shows on light ground too. */
    public static boolean playerMarkerOutline = true;
    public static final int PLAYER_MARKER_OUTLINE_COLOR = 0x000000;
    public static int playerMarkerOutlineColor = PLAYER_MARKER_OUTLINE_COLOR;
    /** Scale of the mod's screens (screen pixels per GUI pixel), independent of Minecraft's; 0 = auto. */
    public static int uiScale = 0;
    /** A right click on a text field of the mod's screens clears its text. */
    public static boolean rightClickClearsText = true;
    /** Language of the mod's texts, whatever the game's is: an index into {@code Lang.CODES} (0 = English). */
    public static int modLanguage = 0;

    /** Map lighting: {@link #LIGHT_AUTO} follows the day/night cycle. */
    public static int mapLightMode = LIGHT_AUTO;
    /** Cave view: {@link #CAVES_AUTO} switches to it while underground. */
    public static int caveMode = CAVES_AUTO;
    /** Surface drawn with block colors, biome colors or colored by height (topography). */
    public static int mapDisplayMode = DISPLAY_BLOCKS;
    /** Contour lines on the topography, every so many blocks of height. */
    public static boolean topoContours = true;
    public static int topoContourInterval = 4;
    public static boolean chunkGrid = false;
    /** What the unexplored part of the 2D map is drawn with: nothing, diagonal lines or dots. */
    public static final int UNEXPLORED_NONE = 0, UNEXPLORED_LINES = 1, UNEXPLORED_DOTS = 2;
    public static int unexploredPattern = UNEXPLORED_NONE;
    /** A soft shadow on the explored land along its edge, with a faint glow on the unexplored side. */
    public static boolean edgeShadow = false;
    /** How long the 2D map and the minimap fade from one cave layer (or the surface) to the next, in ms. */
    public static int layerFadeMs = 300;
    /** A fading line on the maps along the way the player came, this many blocks long. */
    public static boolean playerTrail = false;
    public static int playerTrailLength = 400;
    /** How the trail is colored: one color, a rainbow, by speed, by height, or like fire. */
    public static final int TRAIL_SINGLE = 0, TRAIL_RAINBOW = 1, TRAIL_SPEED = 2, TRAIL_HEIGHT = 3, TRAIL_FIRE = 4;
    public static int playerTrailColorMode = TRAIL_SINGLE;
    public static final int TRAIL_COLOR = 0x4C9AFF;
    public static int playerTrailColor = TRAIL_COLOR;
    /** The trail as a line, a dashed line or dots. */
    public static final int TRAIL_LINE = 0, TRAIL_DASHED = 1, TRAIL_DOTS = 2;
    public static int playerTrailStyle = TRAIL_LINE;
    /** Thickness of the trail, 1 to 4. */
    public static int playerTrailWidth = 2;
    /** The trail fades out toward its old end. */
    public static boolean playerTrailFade = true;
    /** Dashes and dots run toward the player and the rainbow shimmers along the trail. */
    public static boolean playerTrailAnimated = true;
    /** Thickness of the grid's lines in screen pixels, and their colors (RGB; chunk and region borders). */
    public static int gridLineWidth = 1;
    public static final int GRID_CHUNK_COLOR = 0xFFFFFF, GRID_REGION_COLOR = 0xFFFFFF;
    public static int gridChunkColor = GRID_CHUNK_COLOR;
    public static int gridRegionColor = GRID_REGION_COLOR;
    /** How opaque the grid's lines are, in percent. */
    public static int gridChunkOpacity = 20, gridRegionOpacity = 45;
    /** The world map always opens at the player instead of where it was closed. */
    public static boolean mapFollowPlayer = false;
    /** Centering the world map on the player or a teammate glides there instead of jumping. */
    public static boolean mapSmoothCamera = true;
    /** World map drawn in 3D, as an isometric view like Dynmap's, instead of from above. */
    public static boolean isometric = false;
    /** On the 3D map the player is drawn as its 3D model instead of the arrow. */
    public static boolean isoPlayerModel = true;
    /** Side the 3D view looks from: 0 = south-east, 1 = north-east, 2 = north-west, 3 = south-west. */
    public static final int ISO_QUALITY_MAX = 3;
    public static int isoRotation = 0;
    /** Detail of the 3D world map: at most {@code 8 << isoQuality} pixels per block (8, 16, 32 or 64). */
    public static int isoQuality = ISO_QUALITY_MAX;
    /** Zoomed far out, four rays per pixel instead of one: smoother, but up to four times slower to draw. */
    public static boolean isoSmooth = true;
    /** Milliseconds per game tick spent copying chunks' blocks for the 3D map. */
    public static int isoCaptureMs = 5;
    /** Keep the blocks of explored chunks, which the 3D map is drawn from. */
    public static boolean record3d = false;
    /**
     * Write a detailed log of how chunks get onto the 3D map ({@code .minecraft/wayfarmap/logs/3d-*.log}), for finding
     * why some take long.
     */
    public static boolean log3d = false;
    /**
     * Write a detailed log of the flat map ({@code .minecraft/wayfarmap/logs/2d-*.log}): chunks scanned, regions read,
     * saved and drawn, for finding what is slow or wrong.
     */
    public static boolean log2d = false;
    /** {@code /wf chunkload}: server milliseconds per tick for loading and generating chunks. */
    public static int chunkloadServerMs = 20;
    /** {@code /wf chunkload}: chunks per side of a batch (a new command takes it). */
    public static int chunkloadBatch = 8;
    /**
     * The world map's area loading view: the chunks picked (and loading all saved ones, deleting) are for the 3D map
     * too, whatever {@link #record3d} is.
     */
    public static boolean chunkload3d = false;
    /** The world map's area loading view: the mouse keys are shown under the toolbar's legend. */
    public static boolean chunkloadHints = true;
    /** {@code /wf chunkload}: client milliseconds per tick for mapping a batch's chunks. */
    public static int chunkloadClientMs = 6;
    /** VisualProspecting layers (only used when it is installed). */
    public static boolean showOreVeins = true;
    public static boolean showUndergroundFluids = false;
    /** ServerUtilities claims layer on the world map (only used when it is installed). */
    public static boolean showClaims = false;
    public static boolean showPowerfails = true;
    public static boolean showThaumcraftNodes = true;
    /** Share the explored map with the ServerUtilities team (where the server has the mod). */
    public static boolean shareMapWithTeam = true;
    public static boolean useTextureColors = true;
    /** Clear glass shows what is under it, lightly tinted with the glass color. */
    public static boolean seeThroughGlass = true;
    /**
     * Saturation and contrast of biome-tinted colors on the 2D map (grass, leaves, water): 1 = the game's own, below 1
     * more muted, above 1 more vivid.
     */
    public static double biomeColorSaturation = 1.0;
    /** Grass and flowers drawn on the flat map (off: the block under them shows, the "2D map without plants"). */
    public static boolean showPlants = true;
    public static int chunksScannedPerTick = 16;
    public static int autosaveIntervalSeconds = 60;

    public static boolean showOtherPlayers = true;
    /** Hostile mobs (red). */
    public static boolean showHostileMobs = true;
    /** Neutral mobs: animals and the like (grey). */
    public static boolean showPassiveMobs = true;
    /** Ambient mobs: bats and the like (purple). */
    public static boolean showAmbientMobs = true;
    /** Friendly mobs: villagers, traders, golems (green). */
    public static boolean showOtherEntities = true;
    /** Tamed mobs (blue). */
    public static boolean showPets = true;
    /** Width of the colored frame around mob icons, in GUI pixels. */
    public static int mobFrameWidth = 1;
    /** How opaque the frame around mob icons is, in percent, and the icons' size, in percent of the usual. */
    public static int mobFrameOpacity = 100, mobIconScale = 100;
    /** A small arrow at the mob's icon pointing where it looks. */
    public static boolean mobFacing = true;
    /** Names of pets (given with a name tag) under their icon. */
    public static boolean petNames = true;
    /** Names given with a name tag under the icons of hostile, neutral, ambient and friendly mobs. */
    public static boolean hostileNames = true, neutralNames = true, ambientNames = true, friendlyNames = true;

    public static boolean entityIcons = true;
    public static int entityIconLimit = 128;
    public static int entityVerticalRange = 32;

    public static boolean waypointsInWorld = true;
    public static boolean waypointsOnMinimap = true;
    /** Size of waypoint markers on the minimap, in GUI pixels. */
    public static int minimapWaypointSize = 8;
    /** When waypoint names show: always, or only for the one under the mouse (in the world: looked at). */
    public static final int LABELS_ALWAYS = 0, LABELS_HOVER = 1;
    /** Waypoint names on the world map (2D and 3D): always, or only the one under the mouse. */
    public static int waypointMapLabels = LABELS_ALWAYS;
    /** Waypoint names in the world: always, or only for the one the crosshair is on (the icon alone before). */
    public static int waypointWorldLabels = LABELS_ALWAYS;
    /** How far off a waypoint's icon the crosshair may be for its name to show, in degrees. */
    public static int waypointLookZone = 4;
    public static int waypointMaxDistance = 0;
    public static double waypointScale = 1.0;
    public static double waypointMinScale = 0.35;
    /** In-world waypoints fade out when the player comes near: in full from fadeStart blocks, gone at fadeEnd. */
    public static boolean waypointFadeNear = true;
    public static int waypointFadeStart = 12, waypointFadeEnd = 3;
    public static int waypointLabelMaxWidth = 100;
    public static boolean deathWaypoints = true;
    public static int deathWaypointsKeep = 3;
    /** Waypoints teammates share are shown as they come; a waypoint group can say otherwise for itself. */
    public static boolean teamWaypointsShown = true;

    public static final List<Option> OPTIONS = new ArrayList<>();

    /** Section of the settings screen the options declared next are shown under. */
    private static String currentGroup = "";
    /** Switch the options declared next depend on (shown under it), or null. */
    private static BoolOption currentParent;
    /** Tab the options declared next are shown on, or null for their category's (see {@link #tab}). */
    private static String currentTab;
    /** When the options declared next mean anything (see {@link #when}), or null for always. */
    private static BooleanSupplier currentCondition;

    static {
        String c = CATEGORY_MINIMAP;
        group("general");
        parent(null);
        bool(c, "enabled", "Show the minimap on the HUD.", true, () -> minimapEnabled, v -> minimapEnabled = v);
        parent("enabled");
        add(new PositionOption(c, "position", 1.0, 0.0));
        integer(c, "size", "Minimap size in GUI pixels.", 100, 48, 256, 4, () -> minimapSize, v -> minimapSize = v);
        group("mapView");
        parent("enabled");
        choice(
            c,
            "shape",
            "Minimap shape: 0 = square, 1 = round.",
            SHAPE_SQUARE,
            new String[] { "square", "round" },
            () -> minimapShape,
            v -> minimapShape = v);
        choice(
            c,
            "zoomLevel",
            "Minimap zoom level index (0 = farthest: 1 pixel for 8 blocks).",
            3,
            new String[] { "z8", "z4", "z0", "z1", "z2", "z3" },
            () -> minimapZoom,
            v -> minimapZoom = v);
        bool(
            c,
            "rotate",
            "Turn the minimap with the player so the view direction is always up.",
            false,
            () -> minimapRotate,
            v -> minimapRotate = v);
        group("compass");
        parent("enabled");
        bool(
            c,
            "compass",
            "Show N, E, S and W on the edge of the minimap.",
            true,
            () -> minimapCompass,
            v -> minimapCompass = v);
        parent("compass");
        decimal(
            c,
            "compassScale",
            "Size of the N, E, S and W letters: 1.0 = the game's font.",
            1.0,
            0.5,
            3.0,
            0.1,
            () -> minimapCompassScale,
            v -> minimapCompassScale = v);
        group("frame");
        parent("enabled");
        bool(c, "frame", "Draw a frame around the minimap.", true, () -> minimapFrame, v -> minimapFrame = v);
        parent("frame");
        color(
            c,
            "frameColor",
            "Color of the minimap frame, as #RRGGBB.",
            MINIMAP_FRAME_COLOR,
            () -> minimapFrameColor,
            v -> minimapFrameColor = v);
        integer(
            c,
            "frameOpacity",
            "How opaque the minimap frame is, in percent: lower lets the world show through it.",
            100,
            0,
            100,
            5,
            () -> minimapFrameOpacity,
            v -> minimapFrameOpacity = v);
        integer(
            c,
            "frameWidth",
            "Thickness of the minimap frame's colored line, in pixels.",
            1,
            1,
            3,
            1,
            () -> minimapFrameWidth,
            v -> minimapFrameWidth = v);
        group("info");
        parent("enabled");
        bool(
            c,
            "showCoordinates",
            "Show coordinates under the minimap.",
            true,
            () -> minimapShowCoordinates,
            v -> minimapShowCoordinates = v);
        bool(
            c,
            "showBiome",
            "Show the current biome under the minimap.",
            true,
            () -> minimapShowBiome,
            v -> minimapShowBiome = v);
        // Only while there is text under the minimap.
        when(() -> minimapShowCoordinates || minimapShowBiome);
        decimal(
            c,
            "textScale",
            "Size of the coordinates and the biome under the minimap: 1.0 = the game's font.",
            1.0,
            0.5,
            2.0,
            0.25,
            () -> minimapTextScale,
            v -> minimapTextScale = v);
        integer(
            c,
            "textGap",
            "Room between the minimap and the text under it, in pixels.",
            3,
            0,
            16,
            1,
            () -> minimapTextGap,
            v -> minimapTextGap = v);

        group("ownView");
        parent("enabled");
        bool(
            c,
            "ownView",
            "The minimap has its own map view instead of the world map's.",
            false,
            () -> minimapOwnView,
            v -> minimapOwnView = v);
        parent("ownView");
        choice(
            c,
            "view",
            "Minimap view: 0 = 2D, 1 = 2D without plants, 2 = topography, 3 = biomes.",
            MINIMAP_VIEW_FLAT,
            new String[] { "flat", "bare", "topo", "biomes" },
            () -> minimapView,
            v -> minimapView = v);
        choice(
            c,
            "light",
            "Minimap lighting: 0 = follow the day/night cycle, 1 = always day, 2 = always night.",
            LIGHT_AUTO,
            new String[] { "auto", "day", "night" },
            () -> minimapLightMode,
            v -> minimapLightMode = v);
        group("ownLayers");
        parent("enabled");
        bool(
            c,
            "ownLayers",
            "The minimap has its own choice of mod layers instead of the world map's.",
            false,
            () -> minimapOwnLayers,
            v -> minimapOwnLayers = v);
        parent("ownLayers");
        bool(c, "layerOreVeins", "Ore veins on the minimap.", true, () -> minimapOreVeins, v -> {
            minimapOreVeins = v;
            // One at a time, as on the world map.
            if (v) minimapUndergroundFluids = false;
        });
        bool(
            c,
            "layerUndergroundFluids",
            "Underground fluids on the minimap.",
            false,
            () -> minimapUndergroundFluids,
            v -> {
                minimapUndergroundFluids = v;
                if (v) minimapOreVeins = false;
            });
        bool(c, "layerClaims", "Chunk claims on the minimap.", false, () -> minimapClaims, v -> minimapClaims = v);
        bool(
            c,
            "layerPowerfails",
            "GregTech power failures on the minimap.",
            true,
            () -> minimapPowerfails,
            v -> minimapPowerfails = v);
        bool(
            c,
            "layerThaumcraftNodes",
            "Thaumcraft nodes on the minimap.",
            true,
            () -> minimapThaumcraftNodes,
            v -> minimapThaumcraftNodes = v);
        group("ownGrid");
        parent("enabled");
        bool(
            c,
            "ownGrid",
            "The minimap has its own chunk grid switch instead of the world map's.",
            false,
            () -> minimapOwnGrid,
            v -> minimapOwnGrid = v);
        parent("ownGrid");
        bool(c, "grid", "Chunk borders on the minimap.", false, () -> minimapChunkGrid, v -> minimapChunkGrid = v);
        group("ownMobs");
        parent("enabled");
        bool(
            c,
            "ownMobs",
            "The minimap has its own choice of players and mobs instead of the world map's.",
            false,
            () -> minimapOwnMobs,
            v -> minimapOwnMobs = v);
        parent("ownMobs");
        bool(c, "mobsPlayers", "Other players on the minimap.", true, () -> minimapPlayers, v -> minimapPlayers = v);
        bool(
            c,
            "mobsHostile",
            "Hostile mobs on the minimap.",
            true,
            () -> minimapHostileMobs,
            v -> minimapHostileMobs = v);
        bool(
            c,
            "mobsPassive",
            "Neutral mobs on the minimap.",
            true,
            () -> minimapPassiveMobs,
            v -> minimapPassiveMobs = v);
        bool(
            c,
            "mobsAmbient",
            "Ambient mobs on the minimap.",
            true,
            () -> minimapAmbientMobs,
            v -> minimapAmbientMobs = v);
        bool(
            c,
            "mobsFriendly",
            "Friendly mobs on the minimap.",
            true,
            () -> minimapOtherEntities,
            v -> minimapOtherEntities = v);
        bool(c, "mobsPets", "Tamed mobs on the minimap.", true, () -> minimapPets, v -> minimapPets = v);
        c = CATEGORY_MAP;
        tab(TAB_MAP);
        group("language");
        parent(null);
        choice(
            c,
            "language",
            "Language of the mod's texts, whatever Minecraft's is: 0 = English, 1 = Russian, 2 = Ukrainian, "
                + "3 = Chinese.",
            0,
            new String[] { "en_US", "ru_RU", "uk_UA", "zh_CN" },
            () -> modLanguage,
            v -> modLanguage = v);
        group("general");
        parent(null);
        choice(
            c,
            "uiScale",
            "Scale of the map and the mod's screens, independent of Minecraft's GUI scale: "
                + "0 = auto (fits the window), 1-6 = fixed.",
            0,
            new String[] { "auto", "s1", "s2", "s3", "s4", "s5", "s6" },
            () -> uiScale,
            v -> uiScale = v);
        bool(
            c,
            "rightClickClearsText",
            "Clear a text field of the mod's screens (a search, a name) by right-clicking it.",
            true,
            () -> rightClickClearsText,
            v -> rightClickClearsText = v);
        group("navigation");
        parent(null);
        bool(
            c,
            "followPlayer",
            "The world map always opens at the player. If false, it opens where it was closed.",
            false,
            () -> mapFollowPlayer,
            v -> mapFollowPlayer = v);
        bool(
            c,
            "smoothCamera",
            "Centering the map on the player or a teammate glides there instead of jumping.",
            true,
            () -> mapSmoothCamera,
            v -> mapSmoothCamera = v);
        group("buttons");
        for (int i = 0; i < MAP_BUTTONS.length; i++) {
            final int index = i;
            mapButtonShown[i] = true;
            bool(
                c,
                "button_" + MAP_BUTTONS[i],
                "Show the " + MAP_BUTTONS[i] + " button on the world map.",
                true,
                () -> mapButtonShown[index],
                v -> mapButtonShown[index] = v);
        }
        tab(TAB_MAP_2D);
        group("view");
        parent(null);
        choice(
            c,
            "displayMode",
            "Surface map colors: 0 = blocks, 1 = biomes, 2 = topography (colored by height).",
            DISPLAY_BLOCKS,
            new String[] { "blocks", "biomes", "topo" },
            () -> mapDisplayMode,
            v -> mapDisplayMode = v);
        // Contour lines are only drawn on the topography.
        when(() -> mapDisplayMode == DISPLAY_TOPO);
        bool(c, "topoContours", "Contour lines on the topography.", true, () -> topoContours, v -> topoContours = v);
        parent("topoContours");
        integer(
            c,
            "topoContourInterval",
            "Blocks of height between two contour lines of the topography.",
            4,
            2,
            32,
            2,
            () -> topoContourInterval,
            v -> topoContourInterval = v);
        parent(null);
        when(null);
        choice(
            c,
            "lightMode",
            "Map lighting: 0 = follow the day/night cycle, 1 = always day, 2 = always night.",
            LIGHT_AUTO,
            new String[] { "auto", "day", "night" },
            () -> mapLightMode,
            v -> mapLightMode = v);
        choice(
            c,
            "caveMode",
            "Cave view: 0 = automatic while underground, 1 = off, 2 = always.",
            CAVES_AUTO,
            new String[] { "auto", "off", "on" },
            () -> caveMode,
            v -> caveMode = v);
        integer(
            c,
            "layerFadeMs",
            "Fade between cave layers and the surface on the 2D map and the minimap, in ms; 0 = at once.",
            300,
            0,
            1000,
            50,
            () -> layerFadeMs,
            v -> layerFadeMs = v);
        group("colors");
        parent(null);
        bool(
            c,
            "useTextureColors",
            "Color the map with the average color of block textures. If false, vanilla map colors are used.",
            true,
            () -> useTextureColors,
            v -> useTextureColors = v);
        decimal(
            c,
            // Was "biomeColorContrast" with 1.3 by default, too bright: the new key starts saved configs at 1.0.
            "biomeColorSaturation",
            "Saturation and contrast of biome-tinted colors (grass, leaves, water) on the 2D map: 1.0 = the game's "
                + "own colors, lower is more muted, higher more vivid. Transitions between biomes stay smooth. "
                + "Applies as chunks are rescanned.",
            1.0,
            0.5,
            2.0,
            0.05,
            () -> biomeColorSaturation,
            v -> biomeColorSaturation = v);
        bool(
            c,
            "seeThroughGlass",
            "Show what is under glass, lightly tinted with the glass color. Applies as chunks are rescanned.",
            true,
            () -> seeThroughGlass,
            v -> seeThroughGlass = v);
        bool(
            c,
            "showPlants",
            "Draw grass and flowers on the 2D map. If false, the block under them is shown (the \"2D map without "
                + "plants\" mode). Areas mapped before it was kept show them until they are mapped again.",
            true,
            () -> showPlants,
            v -> showPlants = v);
        group("explored");
        parent(null);
        choice(
            c,
            "unexploredPattern",
            "Unexplored land on the 2D map and the minimap: 0 = plain, 1 = diagonal lines, 2 = dots.",
            UNEXPLORED_NONE,
            new String[] { "none", "lines", "dots" },
            () -> unexploredPattern,
            v -> unexploredPattern = v);
        bool(
            c,
            "edgeShadow",
            "A soft shadow along the edge of the explored land on the 2D map and the minimap.",
            false,
            () -> edgeShadow,
            v -> edgeShadow = v);
        group("grid");
        parent(null);
        bool(
            c,
            "chunkGrid",
            "Draw chunk borders on the world map and the minimap.",
            false,
            () -> chunkGrid,
            v -> chunkGrid = v);
        parent("chunkGrid");
        integer(
            c,
            "gridLineWidth",
            "Thickness of the grid's lines in screen pixels.",
            1,
            1,
            8,
            1,
            () -> gridLineWidth,
            v -> gridLineWidth = v);
        color(
            c,
            "gridChunkColor",
            "Color of the chunk borders of the grid, as #RRGGBB.",
            GRID_CHUNK_COLOR,
            () -> gridChunkColor,
            v -> gridChunkColor = v);
        integer(
            c,
            "gridChunkOpacity",
            "Opacity of the chunk borders of the grid, in percent.",
            20,
            5,
            100,
            5,
            () -> gridChunkOpacity,
            v -> gridChunkOpacity = v);
        color(
            c,
            "gridRegionColor",
            "Color of the region borders (every 512 blocks) of the grid, as #RRGGBB.",
            GRID_REGION_COLOR,
            () -> gridRegionColor,
            v -> gridRegionColor = v);
        integer(
            c,
            "gridRegionOpacity",
            "Opacity of the region borders of the grid, in percent.",
            45,
            5,
            100,
            5,
            () -> gridRegionOpacity,
            v -> gridRegionOpacity = v);
        group("trail");
        parent(null);
        bool(
            c,
            "playerTrail",
            "A fading line on the 2D map and the minimap along the way the player came.",
            false,
            () -> playerTrail,
            v -> playerTrail = v);
        parent("playerTrail");
        integer(
            c,
            "playerTrailLength",
            "Length of the player's trail, in blocks.",
            400,
            100,
            2000,
            100,
            () -> playerTrailLength,
            v -> playerTrailLength = v);
        choice(
            c,
            "playerTrailColorMode",
            "Colors of the trail: 0 = one color, 1 = rainbow, 2 = by speed, 3 = by height, 4 = fire.",
            TRAIL_SINGLE,
            new String[] { "single", "rainbow", "speed", "height", "fire" },
            () -> playerTrailColorMode,
            v -> playerTrailColorMode = v);
        when(() -> playerTrailColorMode == TRAIL_SINGLE);
        color(
            c,
            "playerTrailColor",
            "Color of the trail when it has one color, as #RRGGBB.",
            TRAIL_COLOR,
            () -> playerTrailColor,
            v -> playerTrailColor = v);
        when(null);
        choice(
            c,
            "playerTrailStyle",
            "Look of the trail: 0 = line, 1 = dashed line, 2 = dots.",
            TRAIL_LINE,
            new String[] { "line", "dashed", "dots" },
            () -> playerTrailStyle,
            v -> playerTrailStyle = v);
        integer(
            c,
            "playerTrailWidth",
            "Thickness of the trail, 1 to 4.",
            2,
            1,
            4,
            1,
            () -> playerTrailWidth,
            v -> playerTrailWidth = v);
        bool(
            c,
            "playerTrailFade",
            "The trail fades out toward its old end.",
            true,
            () -> playerTrailFade,
            v -> playerTrailFade = v);
        bool(
            c,
            "playerTrailAnimated",
            "Dashes and dots run toward the player, and the rainbow shimmers along the trail.",
            true,
            () -> playerTrailAnimated,
            v -> playerTrailAnimated = v);
        tab(TAB_MAP_3D);
        group("iso");
        parent(null);
        bool(
            c,
            "isometric",
            "Show the world map in 3D (isometric, like Dynmap) instead of from above.",
            false,
            () -> isometric,
            v -> isometric = v);
        parent("isometric");
        choice(
            c,
            "isoRotation",
            "Side the 3D world map is looked at from: 0 = south-east, 1 = north-east, 2 = north-west, "
                + "3 = south-west.",
            0,
            new String[] { "se", "ne", "nw", "sw" },
            () -> isoRotation,
            v -> isoRotation = v);
        integer(
            c,
            "isoQuality",
            "Detail of the 3D world map when zoomed in: 0 = 8, 1 = 16, 2 = 32, 3 = 64 pixels per block at most. "
                + "Less is quicker to draw.",
            ISO_QUALITY_MAX,
            0,
            ISO_QUALITY_MAX,
            1,
            () -> isoQuality,
            v -> isoQuality = v);
        bool(
            c,
            "isoSmooth",
            "Smooth the 3D world map zoomed far out (four rays per pixel). Off draws those tiles up to four times "
                + "faster.",
            true,
            () -> isoSmooth,
            v -> isoSmooth = v);
        parent(null);
        bool(
            c,
            "isoPlayerModel",
            "Show the player as its 3D model (skin, armor, walking) on the 3D map instead of the arrow.",
            true,
            () -> isoPlayerModel,
            v -> isoPlayerModel = v);
        group("record");
        parent(null);
        bool(
            c,
            "record3d",
            "Keep the blocks of explored chunks for the 3D world map (dim<id>/blocks, a few MB per region).",
            false,
            () -> record3d,
            v -> record3d = v);
        parent("record3d");
        integer(
            c,
            "isoCaptureMs",
            "Milliseconds per game tick spent copying the blocks of chunks for the 3D map. More puts new chunks on "
                + "the 3D map sooner but may cost frames while flying over new land.",
            5,
            1,
            20,
            1,
            () -> isoCaptureMs,
            v -> isoCaptureMs = v);
        tab(TAB_MAP_2D);
        group("layers");
        parent(null);
        bool(
            c,
            "oreVeins",
            "Show ore veins prospected with VisualProspecting (if installed).",
            true,
            () -> showOreVeins,
            v -> {
                showOreVeins = v;
                // Ore veins and underground fluids are shown one at a time.
                if (v) showUndergroundFluids = false;
            });
        bool(
            c,
            "undergroundFluids",
            "Show underground fluids prospected with VisualProspecting (if installed).",
            false,
            () -> showUndergroundFluids,
            v -> {
                showUndergroundFluids = v;
                if (v) showOreVeins = false;
            });
        bool(
            c,
            "claims",
            "Show ServerUtilities chunk claims on the world map (if installed).",
            false,
            () -> showClaims,
            v -> showClaims = v);
        bool(
            c,
            "powerfails",
            "Show GregTech power failures on the world map and the minimap (if GregTech has them).",
            true,
            () -> showPowerfails,
            v -> showPowerfails = v);
        bool(
            c,
            "thaumcraftNodes",
            "Show the Thaumcraft aura nodes found with TCNodeTracker on the maps (if installed).",
            true,
            () -> showThaumcraftNodes,
            v -> showThaumcraftNodes = v);
        group("data");
        parent(null);
        integer(
            c,
            "chunksScannedPerTick",
            "How many loaded chunks may be scanned into the map per client tick.",
            16,
            1,
            64,
            1,
            () -> chunksScannedPerTick,
            v -> chunksScannedPerTick = v);
        integer(
            c,
            "autosaveIntervalSeconds",
            "How often explored map data is written to disk.",
            60,
            10,
            600,
            10,
            () -> autosaveIntervalSeconds,
            v -> autosaveIntervalSeconds = v);
        bool(
            c,
            "shareWithTeam",
            "Share the explored map with your ServerUtilities team, on servers that have this mod too.",
            true,
            () -> shareMapWithTeam,
            v -> shareMapWithTeam = v);

        tab(null);
        c = CATEGORY_PLAYER_MARKER;
        group("look");
        parent(null);
        choice(
            c,
            "style",
            "Look of the player on the maps: 0 = arrow, 1 = triangle, 2 = chevron, 3 = kite, 4 = circle with a nose, "
                + "5 = dot.",
            MARKER_ARROW,
            new String[] { "arrow", "triangle", "chevron", "kite", "circle", "dot" },
            () -> playerMarkerStyle,
            v -> playerMarkerStyle = v);
        integer(
            c,
            "scale",
            "Size of the player marker, in percent of the usual size.",
            100,
            50,
            300,
            10,
            () -> playerMarkerScale,
            v -> playerMarkerScale = v);
        color(
            c,
            "color",
            "Color of the player marker, as #RRGGBB.",
            PLAYER_MARKER_COLOR,
            () -> playerMarkerColor,
            v -> playerMarkerColor = v);
        bool(
            c,
            "outline",
            "Dark outline around the player marker, so it shows on light ground too.",
            true,
            () -> playerMarkerOutline,
            v -> playerMarkerOutline = v);
        parent("outline");
        color(
            c,
            "outlineColor",
            "Color of the player marker's outline, as #RRGGBB.",
            PLAYER_MARKER_OUTLINE_COLOR,
            () -> playerMarkerOutlineColor,
            v -> playerMarkerOutlineColor = v);

        c = CATEGORY_ENTITIES;
        group("shown");
        parent(null);
        bool(
            c,
            "showOtherPlayers",
            "Show other players on the maps.",
            true,
            () -> showOtherPlayers,
            v -> showOtherPlayers = v);
        // The mobs' own tab; still saved under the entities, where they were before.
        tab(TAB_MOBS);
        group("shown");
        bool(
            c,
            "showHostileMobs",
            "Show hostile mobs on the maps (red frame).",
            true,
            () -> showHostileMobs,
            v -> showHostileMobs = v);
        bool(
            c,
            "showPassiveMobs",
            "Show neutral mobs: cows, sheep and other animals (grey frame).",
            true,
            () -> showPassiveMobs,
            v -> showPassiveMobs = v);
        bool(
            c,
            "showAmbientMobs",
            "Show ambient mobs: bats and the like (purple frame).",
            true,
            () -> showAmbientMobs,
            v -> showAmbientMobs = v);
        bool(
            c,
            "showOtherEntities",
            "Show friendly mobs: villagers, traders, golems (green frame).",
            true,
            () -> showOtherEntities,
            v -> showOtherEntities = v);
        bool(c, "showPets", "Show tamed mobs (blue frame).", true, () -> showPets, v -> showPets = v);
        integer(
            c,
            "verticalRange",
            "Only show mobs at most this many blocks above or below you; those below fade out the lower they are.",
            32,
            4,
            256,
            4,
            () -> entityVerticalRange,
            v -> entityVerticalRange = v);
        group("icons");
        parent(null);
        bool(
            c,
            "icons",
            "Draw mobs as a small icon of their face instead of dots.",
            true,
            () -> entityIcons,
            v -> entityIcons = v);
        parent("icons");
        integer(
            c,
            "iconLimit",
            "Only the nearest this many mobs get an icon, the rest are dots.",
            128,
            4,
            256,
            4,
            () -> entityIconLimit,
            v -> entityIconLimit = v);
        integer(
            c,
            "frameWidth",
            "Width of the colored frame around mob icons that tells what kind of mob it is, in pixels; 0 = none.",
            1,
            0,
            3,
            1,
            () -> mobFrameWidth,
            v -> mobFrameWidth = v);
        // Only with a frame to make see-through.
        when(() -> mobFrameWidth > 0);
        integer(
            c,
            "frameOpacity",
            "How opaque the colored frame around mob icons is, in percent.",
            100,
            10,
            100,
            5,
            () -> mobFrameOpacity,
            v -> mobFrameOpacity = v);
        when(null);
        integer(
            c,
            "iconScale",
            "Size of mob icons, in percent of the usual size.",
            100,
            50,
            250,
            10,
            () -> mobIconScale,
            v -> mobIconScale = v);
        bool(
            c,
            "facing",
            "A small arrow at each mob's icon pointing where it looks.",
            true,
            () -> mobFacing,
            v -> mobFacing = v);
        // Names given with a name tag, each kind of mob on its own.
        group("names");
        parent(null);
        bool(
            c,
            "hostileNames",
            "Names of hostile mobs (given with a name tag) under their icon.",
            true,
            () -> hostileNames,
            v -> hostileNames = v);
        bool(
            c,
            "neutralNames",
            "Names of neutral mobs (given with a name tag) under their icon.",
            true,
            () -> neutralNames,
            v -> neutralNames = v);
        bool(
            c,
            "ambientNames",
            "Names of ambient mobs (given with a name tag) under their icon.",
            true,
            () -> ambientNames,
            v -> ambientNames = v);
        bool(
            c,
            "friendlyNames",
            "Names of friendly mobs (given with a name tag) under their icon.",
            true,
            () -> friendlyNames,
            v -> friendlyNames = v);
        bool(
            c,
            "petNames",
            "Names of pets (given with a name tag) under their icon.",
            true,
            () -> petNames,
            v -> petNames = v);
        tab(null);

        c = CATEGORY_WAYPOINTS;
        group("where");
        parent(null);
        bool(
            c,
            "showInWorld",
            "Show waypoint markers in the world.",
            true,
            () -> waypointsInWorld,
            v -> waypointsInWorld = v);
        parent("showInWorld");
        integer(
            c,
            "maxDistance",
            "Waypoints farther than this many blocks are not shown in the world. 0 = no limit.",
            0,
            0,
            10000,
            100,
            () -> waypointMaxDistance,
            v -> waypointMaxDistance = v);
        parent(null);
        bool(
            c,
            "showOnMinimap",
            "Show waypoints on the minimap.",
            true,
            () -> waypointsOnMinimap,
            v -> waypointsOnMinimap = v);
        parent("showOnMinimap");
        integer(
            c,
            "minimapSize",
            "Size of waypoint markers on the minimap, in pixels.",
            8,
            4,
            12,
            2,
            () -> minimapWaypointSize,
            v -> minimapWaypointSize = v);
        group("size");
        parent("showInWorld");
        decimal(
            c,
            "scale",
            "Size of in-world waypoints (1.0 = default).",
            1.0,
            0.25,
            3.0,
            0.05,
            () -> waypointScale,
            v -> waypointScale = v);
        decimal(
            c,
            "minScale",
            "In-world waypoints shrink with distance down to this fraction of their close-up size, then stop.",
            0.35,
            0.05,
            1.0,
            0.05,
            () -> waypointMinScale,
            v -> waypointMinScale = v);
        group("names");
        parent(null);
        integer(
            c,
            "labelMaxWidth",
            "Maximum width of waypoint names on the maps and in the world, in pixels; longer names end with '...'.",
            100,
            30,
            300,
            10,
            () -> waypointLabelMaxWidth,
            v -> waypointLabelMaxWidth = v);
        choice(
            c,
            "mapLabels",
            "Waypoint names on the world map (2D and 3D): always, or only for the waypoint under the mouse.",
            0,
            new String[] { "always", "hover" },
            () -> waypointMapLabels,
            v -> waypointMapLabels = v);
        parent("showInWorld");
        choice(
            c,
            "worldLabels",
            "Waypoint names in the world: always, or only for the waypoint the crosshair is on (its icon alone "
                + "before).",
            0,
            new String[] { "always", "look" },
            () -> waypointWorldLabels,
            v -> waypointWorldLabels = v);
        // Only used when names show for the waypoint looked at.
        when(() -> waypointWorldLabels == LABELS_HOVER);
        integer(
            c,
            "lookZone",
            "With names shown when looked at: how far off the waypoint's icon the crosshair may be, in degrees.",
            4,
            0,
            20,
            1,
            () -> waypointLookZone,
            v -> waypointLookZone = v);
        group("fade");
        parent("showInWorld");
        bool(
            c,
            "fadeNear",
            "In-world waypoints (marker and beam) fade out smoothly as you come near them.",
            true,
            () -> waypointFadeNear,
            v -> waypointFadeNear = v);
        parent("fadeNear");
        integer(
            c,
            "fadeStart",
            "In-world waypoints start fading this many blocks away.",
            12,
            2,
            64,
            1,
            () -> waypointFadeStart,
            v -> waypointFadeStart = v);
        integer(
            c,
            "fadeEnd",
            "In-world waypoints are gone this many blocks away (closer than where they start fading).",
            3,
            0,
            32,
            1,
            () -> waypointFadeEnd,
            v -> waypointFadeEnd = v);
        parent(null);
        group("death");
        parent(null);
        bool(c, "deathPoints", "Place a waypoint where you die.", true, () -> deathWaypoints, v -> deathWaypoints = v);
        parent("deathPoints");
        integer(
            c,
            "deathPointsKeep",
            "How many death waypoints to keep; older ones are removed.",
            3,
            1,
            20,
            1,
            () -> deathWaypointsKeep,
            v -> deathWaypointsKeep = v);
        parent(null);
        group("team");
        parent(null);
        bool(
            c,
            "teamShown",
            "Show the waypoints teammates share as soon as they come. Off: they come hidden, to be shown one by one. "
                + "A waypoint group can be set to do otherwise, in the waypoint list.",
            true,
            () -> teamWaypointsShown,
            v -> teamWaypointsShown = v);

        c = CATEGORY_LOGS;
        group("logs");
        parent(null);
        bool(
            c,
            "log3d",
            "Write a detailed log of how chunks get onto the 3D map (.minecraft/wayfarmap/logs/3d-*.log), to find out "
                + "why some take long. Takes effect when a world is joined.",
            false,
            () -> log3d,
            v -> log3d = v);
        bool(
            c,
            "log2d",
            "Write a detailed log of the flat map (.minecraft/wayfarmap/logs/2d-*.log): chunks scanned, regions read "
                + "from and saved to disk, textures and drawing. Takes effect when a world is joined.",
            false,
            () -> log2d,
            v -> log2d = v);

        c = CATEGORY_COMMANDS;
        group("chunkload");
        parent(null);
        integer(
            c,
            "chunkloadServerMs",
            "/wf chunkload: milliseconds of each server tick spent loading and generating chunks. More maps an area "
                + "faster; on a shared server less keeps the TPS up (in single player the server has time to spare).",
            20,
            5,
            200,
            5,
            () -> chunkloadServerMs,
            v -> chunkloadServerMs = v);
        integer(
            c,
            "chunkloadBatch",
            "/wf chunkload: chunks per side of a batch. Bigger batches load fewer chunks twice (each batch loads a "
                + "ring of one chunk around it) and wait less for the client, but hold more chunks in memory. Used by "
                + "the next command started.",
            8,
            4,
            32,
            4,
            () -> chunkloadBatch,
            v -> chunkloadBatch = v);
        integer(
            c,
            "chunkloadClientMs",
            "/wf chunkload: milliseconds of each client tick spent mapping the chunks of a batch.",
            6,
            2,
            50,
            1,
            () -> chunkloadClientMs,
            v -> chunkloadClientMs = v);
        bool(
            c,
            "chunkload3d",
            "Area loading view of the world map: the chunks picked go onto the 3D map too (their blocks are recorded "
                + "for it even with Record blocks off). Also the switch in the view's toolbar.",
            false,
            () -> chunkload3d,
            v -> chunkload3d = v);
        bool(
            c,
            "chunkloadHints",
            "Area loading view of the world map: the mouse keys are shown in the view's toolbar. Also the arrow in "
                + "the toolbar's lower right corner.",
            true,
            () -> chunkloadHints,
            v -> chunkloadHints = v);
    }

    private static Configuration configuration;

    public static void synchronizeConfiguration(File configFile) {
        configuration = new Configuration(configFile);
        // The minimap zoom was "zoom", an index into 4 levels; two farther ones came before them as "zoomLevel".
        if (configuration.hasKey(CATEGORY_MINIMAP, "zoom") && !configuration.hasKey(CATEGORY_MINIMAP, "zoomLevel")) {
            int old = configuration.get(CATEGORY_MINIMAP, "zoom", 1)
                .getInt(1);
            configuration.get(CATEGORY_MINIMAP, "zoomLevel", 3)
                .set(Math.max(0, Math.min(3, old)) + 2);
        }
        configuration.getCategory(CATEGORY_MINIMAP)
            .remove("zoom");
        // The minimap was put in one of 4 corners ("corner"); now it goes anywhere ("positionX", "positionY").
        if (configuration.hasKey(CATEGORY_MINIMAP, "corner") && !configuration.hasKey(CATEGORY_MINIMAP, "positionX")) {
            int corner = configuration.get(CATEGORY_MINIMAP, "corner", 1)
                .getInt(1);
            configuration.get(CATEGORY_MINIMAP, "positionX", 1.0)
                .set(corner % 2 == 0 ? 0.0 : 1.0);
            configuration.get(CATEGORY_MINIMAP, "positionY", 0.0)
                .set(corner < 2 ? 0.0 : 1.0);
        }
        configuration.getCategory(CATEGORY_MINIMAP)
            .remove("corner");
        for (Option option : OPTIONS) {
            option.load(configuration);
        }
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /** Writes all current values to the config file. */
    public static void save() {
        if (configuration == null) {
            return;
        }
        for (Option option : OPTIONS) {
            option.save(configuration);
        }
        configuration.save();
    }

    /** The options shown on a tab of the settings screen (see {@link #CATEGORIES}). */
    public static List<Option> getOptions(String tab) {
        List<Option> result = new ArrayList<>();
        for (Option option : OPTIONS) {
            if (option.tab.equals(tab)) {
                result.add(option);
            }
        }
        return result;
    }

    public static void setMinimapZoom(int zoom) {
        minimapZoom = Math.max(0, Math.min(MINIMAP_ZOOMS.length - 1, zoom));
        save();
    }

    public static void setMinimapEnabled(boolean enabled) {
        minimapEnabled = enabled;
        save();
    }

    /** Auto -> off -> on -> auto. */
    public static void cycleCaveMode() {
        caveMode = (caveMode + 1) % 3;
        save();
    }

    /** Ore veins and underground fluids are exclusive: turning one on turns the other off. */
    public static void toggleOreVeins() {
        showOreVeins = !showOreVeins;
        if (showOreVeins) {
            showUndergroundFluids = false;
        }
        save();
    }

    public static void toggleUndergroundFluids() {
        showUndergroundFluids = !showUndergroundFluids;
        if (showUndergroundFluids) {
            showOreVeins = false;
        }
        save();
    }

    public static void toggleThaumcraftNodes() {
        showThaumcraftNodes = !showThaumcraftNodes;
        save();
    }

    public static void togglePowerfails() {
        showPowerfails = !showPowerfails;
        save();
    }

    public static void toggleClaims() {
        showClaims = !showClaims;
        save();
    }

    /** Whether every kind of mob and other players are shown (the "Mobs" button is not highlighted then). */
    public static boolean allMobsShown() {
        return showHostileMobs && showPassiveMobs
            && showAmbientMobs
            && showOtherEntities
            && showPets
            && showOtherPlayers;
    }

    /** Whether nothing at all is shown: no kind of mob, no other players. */
    public static boolean noMobsShown() {
        return !showHostileMobs && !showPassiveMobs
            && !showAmbientMobs
            && !showOtherEntities
            && !showPets
            && !showOtherPlayers;
    }

    public static void toggleNeutralMobs() {
        showPassiveMobs = !showPassiveMobs;
        save();
    }

    public static void toggleAmbientMobs() {
        showAmbientMobs = !showAmbientMobs;
        save();
    }

    public static void toggleFriendlyMobs() {
        showOtherEntities = !showOtherEntities;
        save();
    }

    public static void togglePets() {
        showPets = !showPets;
        save();
    }

    public static void toggleHostileMobs() {
        showHostileMobs = !showHostileMobs;
        save();
    }

    public static void toggleOtherPlayers() {
        showOtherPlayers = !showOtherPlayers;
        save();
    }

    public static void toggleIsometric() {
        isometric = !isometric;
        save();
    }

    /** Turns the 3D view by a quarter: +1 or -1. */
    public static void setIsoQuality(int quality) {
        isoQuality = Math.max(0, Math.min(ISO_QUALITY_MAX, quality));
        save();
    }

    /** Most pixels per block the 3D world map is drawn with. */
    public static int isoPixelsPerBlock() {
        return 8 << isoQuality;
    }

    public static void rotateIso(int quarters) {
        isoRotation = Math.floorMod(isoRotation + quarters, 4);
        save();
    }

    /** The 3D switch of the area loading view. */
    public static void setChunkload3d(boolean on) {
        chunkload3d = on;
        save();
    }

    /** The hints' toggle of the area loading view. */
    public static void setChunkloadHints(boolean on) {
        chunkloadHints = on;
        save();
    }

    public static void setShowPlants(boolean show) {
        showPlants = show;
        save();
    }

    // What a map shows: the minimap's own settings where it has them, else the world map's.

    public static int displayMode(boolean minimap) {
        if (!minimap || !minimapOwnView) {
            return mapDisplayMode;
        }
        return minimapView == MINIMAP_VIEW_BIOMES ? DISPLAY_BIOMES
            : minimapView == MINIMAP_VIEW_TOPO ? DISPLAY_TOPO : DISPLAY_BLOCKS;
    }

    public static boolean showPlants(boolean minimap) {
        return minimap && minimapOwnView ? minimapView != MINIMAP_VIEW_BARE : showPlants;
    }

    public static int lightMode(boolean minimap) {
        return minimap && minimapOwnView ? minimapLightMode : mapLightMode;
    }

    public static boolean chunkGrid(boolean minimap) {
        return minimap && minimapOwnGrid ? minimapChunkGrid : chunkGrid;
    }

    public static boolean oreVeins(boolean minimap) {
        return minimap && minimapOwnLayers ? minimapOreVeins : showOreVeins;
    }

    public static boolean undergroundFluids(boolean minimap) {
        return minimap && minimapOwnLayers ? minimapUndergroundFluids : showUndergroundFluids;
    }

    public static boolean claims(boolean minimap) {
        return minimap && minimapOwnLayers ? minimapClaims : showClaims;
    }

    public static boolean powerfails(boolean minimap) {
        return minimap && minimapOwnLayers ? minimapPowerfails : showPowerfails;
    }

    public static boolean thaumcraftNodes(boolean minimap) {
        return minimap && minimapOwnLayers ? minimapThaumcraftNodes : showThaumcraftNodes;
    }

    public static boolean otherPlayers(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapPlayers : showOtherPlayers;
    }

    public static boolean hostileMobs(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapHostileMobs : showHostileMobs;
    }

    public static boolean passiveMobs(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapPassiveMobs : showPassiveMobs;
    }

    public static boolean ambientMobs(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapAmbientMobs : showAmbientMobs;
    }

    public static boolean otherEntities(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapOtherEntities : showOtherEntities;
    }

    public static boolean pets(boolean minimap) {
        return minimap && minimapOwnMobs ? minimapPets : showPets;
    }

    // The minimap's keys: a group that was following the world map starts its own settings from what is shown now.

    public static void ownMinimapView() {
        if (!minimapOwnView) {
            minimapView = mapDisplayMode == DISPLAY_BIOMES ? MINIMAP_VIEW_BIOMES
                : mapDisplayMode == DISPLAY_TOPO ? MINIMAP_VIEW_TOPO
                    : showPlants ? MINIMAP_VIEW_FLAT : MINIMAP_VIEW_BARE;
            minimapLightMode = mapLightMode;
            minimapOwnView = true;
        }
    }

    public static void ownMinimapLayers() {
        if (!minimapOwnLayers) {
            minimapOreVeins = showOreVeins;
            minimapUndergroundFluids = showUndergroundFluids;
            minimapClaims = showClaims;
            minimapPowerfails = showPowerfails;
            minimapThaumcraftNodes = showThaumcraftNodes;
            minimapOwnLayers = true;
        }
    }

    public static void ownMinimapGrid() {
        if (!minimapOwnGrid) {
            minimapChunkGrid = chunkGrid;
            minimapOwnGrid = true;
        }
    }

    public static void ownMinimapMobs() {
        if (!minimapOwnMobs) {
            minimapPlayers = showOtherPlayers;
            minimapHostileMobs = showHostileMobs;
            minimapPassiveMobs = showPassiveMobs;
            minimapAmbientMobs = showAmbientMobs;
            minimapOtherEntities = showOtherEntities;
            minimapPets = showPets;
            minimapOwnMobs = true;
        }
    }

    /** The grid button and key: turns the chunk borders on and off. */
    public static void toggleChunkGrid() {
        chunkGrid = !chunkGrid;
        save();
    }

    /** Whether the world map shows this button ({@link #MAP_BUTTONS}); unknown names are shown. */
    public static boolean isMapButtonShown(String name) {
        for (int i = 0; i < MAP_BUTTONS.length; i++) {
            if (MAP_BUTTONS[i].equals(name)) {
                return mapButtonShown[i];
            }
        }
        return true;
    }

    public static void toggleFollowPlayer() {
        mapFollowPlayer = !mapFollowPlayer;
        save();
    }

    /** Biome view on or off; turning it on turns the topography off (one replaces the other). */
    public static void toggleBiomeView() {
        mapDisplayMode = mapDisplayMode == DISPLAY_BIOMES ? DISPLAY_BLOCKS : DISPLAY_BIOMES;
        save();
    }

    /** The map's mode: flat or 3D, in block colors, biome colors or topography (only flat). */
    public static void setMapMode(boolean iso, int display) {
        isometric = iso;
        mapDisplayMode = display;
        save();
    }

    /** Topography on or off; turning it on turns the biome view off. */
    public static void toggleTopoView() {
        mapDisplayMode = mapDisplayMode == DISPLAY_TOPO ? DISPLAY_BLOCKS : DISPLAY_TOPO;
        save();
    }

    public static void setMapLightMode(int mode) {
        mapLightMode = mode;
        save();
    }

    // ---------------------------------------------------------------- option types

    /** One setting: where it lives in the file and how to read/write its static field. */
    public abstract static class Option {

        public final String category;
        public final String key;
        public final String comment;
        /** Section of the settings screen it is shown under ({@code wayfarmap.settings.group.<group>}). */
        public String group = "";
        /** Tab of the settings screen it is shown on: its category, or a part of it. */
        public String tab;
        /** The switch it depends on: shown under it, dimmed while it is off; null if none. */
        public BoolOption parent;
        /**
         * When it means anything with the other options as they are set (the color of the trail only when it has one
         * color); null for always. The settings screen hides it otherwise.
         */
        public BooleanSupplier condition;

        Option(String category, String key, String comment) {
            this.category = category;
            this.key = key;
            this.comment = comment;
            this.tab = category;
        }

        /** Translation key of the name; {@code + ".desc"} is the description. */
        public String langKey() {
            return "wayfarmap.option." + category + "." + key;
        }

        abstract void load(Configuration configuration);

        abstract void save(Configuration configuration);

        public abstract void reset();

        /** Whether it is still set to its default value. */
        public abstract boolean isDefault();

        /** Whether it is used with the other options as they are set now (see {@link #condition}). */
        public boolean applies() {
            return condition == null || condition.getAsBoolean();
        }
    }

    public static class BoolOption extends Option {

        public final boolean defaultValue;
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;

        BoolOption(String category, String key, String comment, boolean defaultValue, Supplier<Boolean> getter,
            Consumer<Boolean> setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.getter = getter;
            this.setter = setter;
        }

        public boolean get() {
            return getter.get();
        }

        public void set(boolean value) {
            setter.accept(value);
        }

        @Override
        void load(Configuration configuration) {
            set(configuration.getBoolean(key, category, defaultValue, comment));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }

        @Override
        public boolean isDefault() {
            return get() == defaultValue;
        }
    }

    public static class IntOption extends Option {

        public final int defaultValue, min, max, step;
        private final IntSupplier getter;
        private final IntConsumer setter;

        IntOption(String category, String key, String comment, int defaultValue, int min, int max, int step,
            IntSupplier getter, IntConsumer setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
        }

        public int get() {
            return getter.getAsInt();
        }

        public void set(int value) {
            setter.accept(Math.max(min, Math.min(max, value)));
        }

        @Override
        void load(Configuration configuration) {
            set(configuration.getInt(key, category, defaultValue, min, max, comment));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment, min, max)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }

        @Override
        public boolean isDefault() {
            return get() == defaultValue;
        }
    }

    /** An int setting shown as a list of named values. */
    public static class ChoiceOption extends IntOption {

        /** Suffixes of the value translation keys: {@code langKey() + "." + values[i]}. */
        public final String[] values;

        ChoiceOption(String category, String key, String comment, int defaultValue, String[] values, IntSupplier getter,
            IntConsumer setter) {
            super(category, key, comment, defaultValue, 0, values.length - 1, 1, getter, setter);
            this.values = values;
        }
    }

    public static class DoubleOption extends Option {

        public final double defaultValue, min, max, step;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        DoubleOption(String category, String key, String comment, double defaultValue, double min, double max,
            double step, DoubleSupplier getter, DoubleConsumer setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
        }

        public double get() {
            return getter.getAsDouble();
        }

        public void set(double value) {
            double snapped = Math.round(value / step) * step;
            setter.accept(Math.max(min, Math.min(max, snapped)));
        }

        @Override
        void load(Configuration configuration) {
            set(
                configuration.get(category, key, defaultValue, comment, min, max)
                    .getDouble(defaultValue));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, defaultValue, comment, min, max)
                .set(get());
        }

        @Override
        public void reset() {
            set(defaultValue);
        }

        @Override
        public boolean isDefault() {
            // Values are snapped to the step, the default may not be.
            return Math.abs(get() - defaultValue) < step / 2;
        }
    }

    /** An RGB color, saved as {@code #RRGGBB}. */
    public static class ColorOption extends Option {

        public final int defaultValue;
        private final IntSupplier getter;
        private final IntConsumer setter;

        ColorOption(String category, String key, String comment, int defaultValue, IntSupplier getter,
            IntConsumer setter) {
            super(category, key, comment);
            this.defaultValue = defaultValue;
            this.getter = getter;
            this.setter = setter;
        }

        public int get() {
            return getter.getAsInt();
        }

        public void set(int rgb) {
            setter.accept(rgb & 0xFFFFFF);
        }

        /** 0x2A313B -> #2A313B. */
        public static String hex(int rgb) {
            return String.format("#%06X", rgb & 0xFFFFFF);
        }

        @Override
        void load(Configuration configuration) {
            String value = configuration.getString(key, category, hex(defaultValue), comment)
                .trim();
            try {
                set(Integer.parseInt(value.startsWith("#") ? value.substring(1) : value, 16));
            } catch (NumberFormatException e) {
                set(defaultValue);
            }
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key, hex(defaultValue), comment)
                .set(hex(get()));
        }

        @Override
        public void reset() {
            set(defaultValue);
        }

        @Override
        public boolean isDefault() {
            return get() == (defaultValue & 0xFFFFFF);
        }
    }

    /**
     * Where the minimap is on screen ({@link #minimapX}, {@link #minimapY}), saved as {@code <key>X} and
     * {@code <key>Y}; the settings screen opens a screen to drag it around.
     */
    public static class PositionOption extends Option {

        public final double defaultX, defaultY;

        PositionOption(String category, String key, double defaultX, double defaultY) {
            super(category, key, "Minimap position: 0 = left (top) edge of the screen, 1 = right (bottom) edge.");
            this.defaultX = defaultX;
            this.defaultY = defaultY;
        }

        public static void set(double x, double y) {
            minimapX = Math.max(0, Math.min(1, x));
            minimapY = Math.max(0, Math.min(1, y));
        }

        @Override
        void load(Configuration configuration) {
            set(
                configuration.get(category, key + "X", defaultX, comment, 0, 1)
                    .getDouble(defaultX),
                configuration.get(category, key + "Y", defaultY, comment, 0, 1)
                    .getDouble(defaultY));
        }

        @Override
        void save(Configuration configuration) {
            configuration.get(category, key + "X", defaultX, comment, 0, 1)
                .set(minimapX);
            configuration.get(category, key + "Y", defaultY, comment, 0, 1)
                .set(minimapY);
        }

        @Override
        public void reset() {
            set(defaultX, defaultY);
        }

        @Override
        public boolean isDefault() {
            return Math.abs(minimapX - defaultX) < 1e-4 && Math.abs(minimapY - defaultY) < 1e-4;
        }
    }

    /** Whether it is the choice of the mod's language, drawn with the languages' flags. */
    public static boolean isLanguage(Option option) {
        return option instanceof ChoiceOption && CATEGORY_MAP.equals(option.category) && "language".equals(option.key);
    }

    /** Tab the options declared next are shown on; null for their category's own. */
    private static void tab(String tab) {
        currentTab = tab;
    }

    private static void group(String group) {
        currentGroup = group;
        currentParent = null;
        currentCondition = null;
    }

    /** When the options declared next mean anything, until the next section; null for always. */
    private static void when(BooleanSupplier condition) {
        currentCondition = condition;
    }

    private static void parent(String key) {
        currentParent = null;
        if (key == null) {
            return;
        }
        for (Option option : OPTIONS) {
            if (option.key.equals(key) && option instanceof BoolOption) {
                currentParent = (BoolOption) option;
            }
        }
    }

    private static void add(Option option) {
        option.group = currentGroup;
        if (currentTab != null) {
            option.tab = currentTab;
        }
        option.parent = currentParent;
        option.condition = currentCondition;
        OPTIONS.add(option);
    }

    private static void bool(String category, String key, String comment, boolean def, Supplier<Boolean> getter,
        Consumer<Boolean> setter) {
        add(new BoolOption(category, key, comment, def, getter, setter));
    }

    private static void integer(String category, String key, String comment, int def, int min, int max, int step,
        IntSupplier getter, IntConsumer setter) {
        add(new IntOption(category, key, comment, def, min, max, step, getter, setter));
    }

    private static void choice(String category, String key, String comment, int def, String[] values,
        IntSupplier getter, IntConsumer setter) {
        add(new ChoiceOption(category, key, comment, def, values, getter, setter));
    }

    private static void color(String category, String key, String comment, int def, IntSupplier getter,
        IntConsumer setter) {
        add(new ColorOption(category, key, comment, def, getter, setter));
    }

    private static void decimal(String category, String key, String comment, double def, double min, double max,
        double step, DoubleSupplier getter, DoubleConsumer setter) {
        add(new DoubleOption(category, key, comment, def, min, max, step, getter, setter));
    }
}
