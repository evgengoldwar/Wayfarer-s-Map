package WayFarMap.client.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.Config;
import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.map.FlatExport;
import WayFarMap.client.map.MapDimension;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.MapRegion;
import WayFarMap.client.map.export.MapExport;
import WayFarMap.client.map.export.MapPictures;
import WayFarMap.client.map.export.TilePyramid;
import WayFarMap.client.map.iso.IsoExport;

/**
 * Pictures of the map, on one screen: on the left new ones are made (the 3D map, or any of the 2D maps at once: with
 * plants, without them, the topography, the biomes; how detailed, at night, as one picture or as a page that zooms in
 * a browser), on the right the ones made so far, small, to filter and scroll through, with quick buttons on each. A
 * click opens one big to look at closer (zoom and move it), flip through them on a strip at the bottom, copy it to
 * the clipboard, open it or delete it. Only the mod's own pictures are shown and deleted: they are in a folder of
 * their own.
 */
public class GuiMapPictures extends ScaledScreen {

    private static final int ID_CLOSE = 0, ID_2D = 1, ID_3D = 2, ID_MAKE = 3, ID_FOLDER = 4, ID_DELETE_ALL = 5,
        ID_VIEW_CLOSE = 6, ID_VIEW_COPY = 7, ID_VIEW_OPEN = 8, ID_VIEW_DELETE = 9, ID_VIEW_PREVIOUS = 10,
        ID_VIEW_NEXT = 11;
    /** Delete buttons on the small pictures ask again too: their id for that is this plus the picture's index. */
    private static final int ID_CARD_DELETE = 1000;
    /**
     * Width of the left side, height of a detail row, of a layer's tile, room between the small pictures and their
     * least width.
     */
    private static final int LEFT_WIDTH = 224, ROW = 14, TILE = 20, GAP = 6, CARD_MIN = 118;
    /** Height of the bar over a picture opened big, and of the strip of small pictures under it. */
    private static final int VIEW_BAR = 28, STRIP = 46;
    /** Size of a small picture on the strip, and of a quick button on a small picture. */
    private static final int STRIP_W = 56, STRIP_H = 34, QUICK = 14;
    /** Longest side of the small pictures and of the big one, in pixels of the texture. */
    private static final int THUMB_SIZE = 256, VIEW_SIZE = 2048;
    /** How long a delete button waits for the second click that confirms it, and a message shows. */
    private static final long CONFIRM_MS = 4000, TOAST_MS = 2500, DOUBLE_CLICK_MS = 300;
    /** Rough time to draw one 3D tile on one thread, in seconds, for the estimate. */
    private static final double SECONDS_PER_TILE = 0.2;
    private static final int PICTURE_BACKGROUND = 0xFF0C0E11;
    /** The modes' colors, as on the map's mode list. */
    private static final int FLAT_COLOR = 0xFF5BD6E0, BARE_COLOR = 0xFFE87AA0, TOPO_COLOR = 0xFF4FC3A8,
        BIOMES_COLOR = 0xFF8BD450, CAVES_COLOR = 0xFFC8A070, ISO_COLOR = 0xFFE8A040, NIGHT_COLOR = 0xFFB9A8FF;
    private static final int SELECTED_ROW = 0x334C9AFF, SHADOW = 0x50000000, BADGE = 0xD0101418;

    /** The filters of the pictures: all of them, the 2D ones, the 3D ones, the pages for a browser. */
    private static final String[] FILTERS = { "all", "flat", "iso", "web" };
    /** A picture's name: what it shows, pixels per block, at night, the date (see {@link #make}). */
    private static final Pattern NAME = Pattern.compile(
        "_(2d|bare|topo|biomes|caves_\\d+-\\d+|3d)_(\\d+)px(_night)?_\\d{4}-\\d\\d-\\d\\d_\\d\\d\\.\\d\\d\\.\\d\\d$");

    /** The choices, kept while the game runs: detail (index in the list) of each mode, at night, as a page. */
    private static int flatQuality = 1, isoQuality = 2;
    private static boolean night, site;
    /** The 2D maps to save, by key; empty until first chosen, then the one shown on the map is picked. */
    private static final Set<String> chosenLayers = new LinkedHashSet<>();
    private static boolean layersPicked;
    private static int filter;

    /** Reads pictures in the background, one at a time. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WayFarMap pictures");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });

    /** One detail the map can be saved at. */
    private static final class Quality {

        /** 2D: pixels per block; 3D: the level. */
        final int value;
        final String label, size;
        final boolean usable;

        Quality(int value, String label, String size, boolean usable) {
            this.value = value;
            this.label = label;
            this.size = size;
            this.usable = usable;
        }
    }

    /** One of the 2D maps that can be saved. */
    private static final class Layer {

        /** For the choice and in the file's name: "2d", "bare", "topo", "biomes", "caves_0-15". */
        final String key;
        final String label;
        final String[] icon;
        final int color;
        final MapDimension map;
        final boolean topography;
        /** Width and height of the picture at one pixel per block. */
        final long[] size;
        final boolean usable;
        final Smooth hover = new Smooth(0);

        Layer(String key, String label, String[] icon, int color, MapDimension map, boolean topography) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.map = map;
            this.topography = topography;
            Set<Long> regions = map == null ? Collections.<Long>emptySet() : new FlatExport(map, 1).tiles();
            this.size = TilePyramid.pictureSize(regions, MapRegion.SIZE);
            this.usable = !regions.isEmpty();
        }

        FlatExport export(int scale) {
            return topography ? FlatExport.topography(map, scale) : new FlatExport(map, scale);
        }
    }

    /** What a picture shows, from its name: the map, pixels per block, at night; kind null if unknown. */
    private static final class Kind {

        final String kind, label;
        final String[] icon;
        final int color, pixels;
        final boolean night, iso;

        Kind(String kind, String label, String[] icon, int color, int pixels, boolean night) {
            this.kind = kind;
            this.label = label;
            this.icon = icon;
            this.color = color;
            this.pixels = pixels;
            this.night = night;
            this.iso = "3d".equals(kind);
        }
    }

    /** A picture read in the background and then put into a texture. */
    private static final class Shown {

        volatile BufferedImage read;
        volatile boolean failed;
        DynamicTexture texture;
        int width, height;
        /** Fades the picture in once it is there. */
        final Smooth appear = new Smooth(0);

        /** Whether it can be drawn; puts what was read into a texture first (render thread). */
        boolean ready() {
            BufferedImage image = read;
            if (texture == null && image != null) {
                width = image.getWidth();
                height = image.getHeight();
                texture = new DynamicTexture(image);
                read = null;
            }
            return texture != null;
        }

        void release() {
            if (texture != null) {
                texture.deleteGlTexture();
                texture = null;
            }
        }
    }

    private final GuiScreen parent;
    /** What the map shows now, for a new picture: the dimension, which 2D map (surface, biomes, a cave layer). */
    private final int dimension;
    private final String flatWhat, name, dimensionName;
    private boolean iso;
    /**
     * Whether the dimension's 3D map has blocks to save; recording them may be off, what was recorded is still
     * there.
     */
    private boolean isoAvailable;
    private final List<Layer> layers = new ArrayList<>();
    private final List<Quality> flatChoices = new ArrayList<>();
    /** Counted the first time 3D is picked (it reads the 3D map); null until then. */
    private List<Quality> isoChoices;

    /** Every picture, and the ones the filter shows (what the grid and the big view go through). */
    private List<MapPictures.Picture> allPictures = new ArrayList<>();
    private List<MapPictures.Picture> pictures = new ArrayList<>();
    private final Map<File, Kind> kinds = new HashMap<>();
    /** Pictures there when the screen opened: the others are new, and marked. */
    private Set<File> seenAtOpen;
    /** Bytes the pictures folder takes, counted with the list. */
    private long folderBytes;
    private int seenFinished = -1;
    private final Map<File, Shown> thumbs = new HashMap<>();
    private final Map<File, int[]> dimensions = new HashMap<>();
    private final Map<File, Smooth> cardHover = new HashMap<>();
    /** Where the grid is scrolled to, and where it is drawn on the way there. */
    private double scroll;
    private final Smooth scrollShown = new Smooth(0);

    /** The picture opened big, -1 for none; its texture, zoom (1 = fitted) and how far it is moved. */
    private int viewing = -1;
    private Shown viewImage;
    private File viewFile;
    private double viewZoom = 1, viewPanX, viewPanY;
    private boolean viewDragging;
    private int dragX, dragY;
    private long lastClickAt;
    /** How far the strip at the bottom is scrolled, easing to keep the picture shown in its middle. */
    private final Smooth stripScroll = new Smooth(0);
    private final Smooth viewOpen = new Smooth(0);

    /** Button waiting for its second click, and since when. */
    private int armed = -1;
    private long armedAt;
    /** A short message at the bottom, its color and when it was given (set from other threads too). */
    private volatile String toast;
    private volatile int toastColor;
    private volatile long toastAt;
    /** A note to show by the mouse after everything else is drawn, or null. */
    private List<String> tooltip;

    private int left, top, right, bottom, contentTop, contentBottom, galleryLeft, gridTop;
    private FlatButton flatButton, isoButton, makeButton, folderButton, deleteAllButton;
    private FlatButton copyButton, openButton, deleteButton, previousButton, nextButton;
    private IconButton viewCloseButton;

    /**
     * @param dimension     the dimension shown on the map
     * @param iso           the map shows 3D: it is picked at first
     * @param flatWhat      which 2D map is shown: picked at first ("2d", "bare", "topo", "biomes", "caves_0-15")
     * @param name          the world and dimension, for the name of the picture
     * @param dimensionName the dimension's name, for the picture's page
     */
    public GuiMapPictures(GuiScreen parent, int dimension, boolean iso, String flatWhat, String name,
        String dimensionName) {
        this.parent = parent;
        this.dimension = dimension;
        this.iso = iso;
        this.flatWhat = flatWhat;
        this.name = name;
        this.dimensionName = dimensionName;
        IsoExport probe = IsoExport.of(dimension, Config.isoRotation, IsoExport.MAX_LEVEL, false);
        this.isoAvailable = probe != null && probe.hasBlocks();
        this.iso = iso && isoAvailable;
        findLayers();
        countFlat();
    }

    // ---------------------------------------------------------------- choices

    /** The 2D maps of the dimension shown: with and without plants, the topography, biomes, the cave layer shown. */
    private void findLayers() {
        MapManager maps = MapManager.INSTANCE;
        MapDimension surface = maps.getViewSurfaceMap();
        if (surface != null) {
            MapDimension bare = surface.plantless() != null ? surface.plantless() : surface;
            layers.add(
                new Layer("2d", Lang.format("wayfarmap.pictures.layer.2d"), Icons.MAP2D, FLAT_COLOR, surface, false));
            layers.add(
                new Layer(
                    "bare",
                    Lang.format("wayfarmap.pictures.layer.bare"),
                    Icons.NO_PLANTS,
                    BARE_COLOR,
                    bare,
                    false));
            layers.add(
                new Layer("topo", Lang.format("wayfarmap.pictures.layer.topo"), Icons.TOPO, TOPO_COLOR, surface, true));
        }
        MapDimension biomes = maps.getViewBiomeMap();
        if (biomes != null) {
            layers.add(
                new Layer(
                    "biomes",
                    Lang.format("wayfarmap.pictures.layer.biomes"),
                    Icons.BIOMES,
                    BIOMES_COLOR,
                    biomes,
                    false));
        }
        if (flatWhat.startsWith("caves_") && maps.getViewMap() != null) {
            String range = flatWhat.substring("caves_".length());
            layers.add(
                new Layer(
                    flatWhat,
                    Lang.format("wayfarmap.pictures.layer.caves", range),
                    Icons.CAVES,
                    CAVES_COLOR,
                    maps.getViewMap(),
                    false));
        }
        // Layers once chosen that this map has not (another cave layer) are dropped.
        Set<String> keys = new HashSet<>();
        for (Layer layer : layers) {
            keys.add(layer.key);
        }
        chosenLayers.retainAll(keys);
        if (!layersPicked || chosenLayers.isEmpty() && keys.contains(flatWhat)) {
            // At first, the map that is shown.
            chosenLayers.clear();
            chosenLayers.add(keys.contains(flatWhat) ? flatWhat : "2d");
            layersPicked = true;
        }
    }

    /** The 2D maps chosen, that have something to save. */
    private List<Layer> chosenUsable() {
        List<Layer> list = new ArrayList<>();
        for (Layer layer : layers) {
            if (layer.usable && chosenLayers.contains(layer.key)) {
                list.add(layer);
            }
        }
        return list;
    }

    /** The 2D map's details: 1 to 16 pixels per block, with the size of the biggest chosen picture. */
    private void countFlat() {
        flatChoices.clear();
        long[] picture = { 0, 0 };
        List<Layer> chosen = chosenUsable();
        for (Layer layer : chosen) {
            if (layer.size[0] * layer.size[1] > picture[0] * picture[1]) {
                picture = layer.size;
            }
        }
        for (int pixels = 1; pixels <= 16; pixels *= 2) {
            flatChoices.add(
                new Quality(
                    pixels,
                    Lang.format("wayfarmap.pictures.pixels", pixels),
                    picture[0] * pixels + "×" + picture[1] * pixels,
                    !chosen.isEmpty()));
        }
    }

    /** The 3D map's details, from the least: pixels per block, the picture's size and about how long it takes. */
    private void countIso() {
        isoChoices = new ArrayList<>();
        for (int level = IsoExport.MAX_LEVEL; level >= 0; level--) {
            IsoExport export = IsoExport.of(dimension, Config.isoRotation, level, false);
            if (export == null) {
                continue;
            }
            Set<Long> tiles = export.tiles();
            double minutes = tiles.size() * SECONDS_PER_TILE / export.threads() / 60;
            String time = minutes < 1 ? Lang.format("wayfarmap.pictures.under_minute")
                : Lang.format("wayfarmap.pictures.minutes", (int) Math.ceil(minutes));
            long[] picture = TilePyramid.pictureSize(tiles, export.tileSize());
            isoChoices.add(
                new Quality(
                    level,
                    Lang.format("wayfarmap.pictures.pixels", (int) export.pixelsPerBlock()),
                    picture[0] + "×" + picture[1] + " · " + time,
                    !tiles.isEmpty()));
        }
        isoQuality = Math.min(isoQuality, Math.max(0, isoChoices.size() - 1));
        boolean any = false;
        for (Quality quality : isoChoices) {
            any |= quality.usable;
        }
        if (!any) {
            // Files, but no blocks in them: as empty as no 3D map.
            isoAvailable = false;
            iso = false;
        }
    }

    private List<Quality> choices() {
        if (iso && isoChoices == null) {
            countIso();
        }
        return iso ? isoChoices : flatChoices;
    }

    private Quality chosen() {
        List<Quality> list = choices();
        if (list.isEmpty()) {
            return null;
        }
        int index = iso ? isoQuality : flatQuality;
        return list.get(Math.max(0, Math.min(list.size() - 1, index)));
    }

    /** How many pictures a click on the button makes. */
    private int pictureCount() {
        return iso ? 1 : chosenUsable().size();
    }

    private boolean canMake() {
        Quality quality = chosen();
        return quality != null && quality.usable && pictureCount() > 0;
    }

    private void make() {
        Quality quality = chosen();
        if (!canMake()) {
            return;
        }
        if (iso) {
            IsoExport export = IsoExport.of(dimension, Config.isoRotation, quality.value, night);
            if (export == null) {
                return;
            }
            TilePyramid.Info info = new TilePyramid.Info();
            String what = "3d_" + (int) export.pixelsPerBlock() + "px" + (night ? "_night" : "");
            info.title = dimensionName + " (3D)";
            info.mode = "3d";
            info.pixelsPerBlock = export.pixelsPerBlock();
            // Up to 128 screen pixels per block, and always a few times closer than the picture itself.
            info.maxZoom = Math.max(4, 128 / export.pixelsPerBlock());
            MapExport.start(export, info, name + "_" + what, site);
            return;
        }
        List<MapExport.Request> requests = new ArrayList<>();
        for (Layer layer : chosenUsable()) {
            TilePyramid.Info info = new TilePyramid.Info();
            String what = layer.key + "_" + quality.value + "px";
            info.title = dimensionName + " (" + layer.label + ", " + quality.value + "px)";
            info.mode = "2d";
            info.pixelsPerBlock = quality.value;
            // Up to 64 screen pixels per block, like the closest zoom of the map.
            info.maxZoom = Math.max(4, 64.0 / quality.value);
            requests.add(new MapExport.Request(layer.export(quality.value), info, name + "_" + what, layer.label));
        }
        MapExport.start(requests, site);
    }

    // ---------------------------------------------------------------- layout

    private int layersTop() {
        return contentTop + 36;
    }

    /** Rows of layer tiles (two a row); 3D has one, the night. */
    private int layerRows() {
        return iso ? 1 : (layers.size() + 1) / 2;
    }

    private int qualityLabelY() {
        return layersTop() + Math.max(1, layerRows()) * (TILE + 3) + 6;
    }

    private int qualityTop() {
        return qualityLabelY() + 13;
    }

    private int formatLabelY() {
        return qualityTop() + Math.max(1, choices().size()) * ROW + 7;
    }

    private int formatTop() {
        return formatLabelY() + 13;
    }

    /** A layer's tile: left, top, right, bottom. */
    private int[] tile(int index) {
        int x = left + 10, half = (LEFT_WIDTH - 4) / 2;
        int column = index % 2, row = index / 2;
        int x0 = x + column * (LEFT_WIDTH - half);
        int y0 = layersTop() + row * (TILE + 3);
        int x1 = iso || index == layers.size() - 1 && column == 0 ? x + LEFT_WIDTH : x0 + half;
        return new int[] { x0, y0, x1, y0 + TILE };
    }

    private int filterTop() {
        return contentTop + 14;
    }

    private int columns() {
        return Math.max(1, (right - 10 - galleryLeft + GAP) / (CARD_MIN + GAP));
    }

    private int cardWidth() {
        int columns = columns();
        return (right - 10 - galleryLeft - (columns - 1) * GAP) / columns;
    }

    private int thumbHeight() {
        return cardWidth() * 5 / 8;
    }

    private int cardHeight() {
        return thumbHeight() + 25;
    }

    private int maxScroll() {
        int rows = (pictures.size() + columns() - 1) / columns();
        int total = rows * (cardHeight() + GAP) - GAP;
        return Math.max(0, total - (contentBottom - gridTop));
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 660);
        int panelHeight = Math.min(height - 16, 380);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        contentTop = top + WindowHeader.HEIGHT + 8;
        contentBottom = bottom - 10;
        galleryLeft = left + 10 + LEFT_WIDTH + 17;
        gridTop = contentTop + 32;

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, right, top));
        int x = left + 10, half = (LEFT_WIDTH - 4) / 2;
        flatButton = new FlatButton(ID_2D, x, contentTop, half, 20, Lang.format("wayfarmap.pictures.flat"));
        flatButton.icon = Icons.MAP2D;
        flatButton.iconColor = FLAT_COLOR;
        isoButton = new FlatButton(ID_3D, x + LEFT_WIDTH - half, contentTop, half, 20, "3D");
        isoButton.icon = Icons.ISO;
        isoButton.iconColor = ISO_COLOR;
        makeButton = new FlatButton(ID_MAKE, x, contentBottom - 20, LEFT_WIDTH, 20, "");
        buttonList.add(flatButton);
        buttonList.add(isoButton);
        buttonList.add(makeButton);

        String deleteAll = Lang.format("wayfarmap.pictures.delete_all");
        int deleteWidth = Math.max(
            fontRendererObj.getStringWidth(deleteAll),
            fontRendererObj.getStringWidth(Lang.format("wayfarmap.pictures.sure"))) + iconButtonRoom(Icons.SMALL_TRASH);
        deleteAllButton = new FlatButton(ID_DELETE_ALL, right - 10 - deleteWidth, contentTop - 3, deleteWidth, 14, "");
        deleteAllButton.danger = true;
        deleteAllButton.icon = Icons.SMALL_TRASH;
        deleteAllButton.iconColor = Theme.DANGER;
        String folder = Lang.format("wayfarmap.pictures.folder");
        int folderWidth = fontRendererObj.getStringWidth(folder) + iconButtonRoom(Icons.SMALL_PASTE);
        folderButton = new FlatButton(
            ID_FOLDER,
            deleteAllButton.xPosition - 4 - folderWidth,
            contentTop - 3,
            folderWidth,
            14,
            folder);
        folderButton.icon = Icons.SMALL_PASTE;
        buttonList.add(folderButton);
        buttonList.add(deleteAllButton);

        // Over a picture opened big: its bar at the top, the arrows on the sides.
        viewCloseButton = new IconButton(ID_VIEW_CLOSE, width - 24, 6, Icons.CLOSE, "");
        deleteButton = viewButton(
            ID_VIEW_DELETE,
            "wayfarmap.pictures.delete",
            "wayfarmap.pictures.sure",
            Icons.SMALL_TRASH,
            width - 30);
        deleteButton.danger = true;
        deleteButton.iconColor = Theme.DANGER;
        openButton = viewButton(
            ID_VIEW_OPEN,
            "wayfarmap.pictures.open",
            null,
            Icons.SMALL_EYE,
            deleteButton.xPosition - 4);
        copyButton = viewButton(
            ID_VIEW_COPY,
            "wayfarmap.pictures.copy",
            null,
            Icons.SMALL_COPY,
            openButton.xPosition - 4);
        int middle = (VIEW_BAR + height - STRIP) / 2;
        previousButton = new FlatButton(ID_VIEW_PREVIOUS, 6, middle - 22, 18, 44, "<");
        nextButton = new FlatButton(ID_VIEW_NEXT, width - 24, middle - 22, 18, 44, ">");
        buttonList.add(viewCloseButton);
        buttonList.add(copyButton);
        buttonList.add(openButton);
        buttonList.add(deleteButton);
        buttonList.add(previousButton);
        buttonList.add(nextButton);
        refresh();
    }

    /**
     * A button of the bar over a picture opened big, ending at {@code rightX}.
     *
     * @param otherKey the text it shows at times (asking again), so it is wide enough for both; or null
     */
    private FlatButton viewButton(int id, String key, String otherKey, String[] icon, int rightX) {
        String text = Lang.format(key);
        int textWidth = fontRendererObj.getStringWidth(text);
        if (otherKey != null) {
            textWidth = Math.max(textWidth, fontRendererObj.getStringWidth(Lang.format(otherKey)));
        }
        int buttonWidth = textWidth + iconButtonRoom(icon);
        FlatButton button = new FlatButton(id, rightX - buttonWidth, 6, buttonWidth, 16, text);
        button.icon = icon;
        return button;
    }

    /**
     * Room a {@link FlatButton} with an icon needs besides its text so the text isn't cut: the icon with its margins
     * before the text, and the margin it keeps after it, plus a little air.
     */
    private static int iconButtonRoom(String[] icon) {
        return 6 + Icons.width(icon) + 5 + 10 + 4;
    }

    // ---------------------------------------------------------------- pictures

    /** Lists the pictures again, letting go of the small pictures of the ones gone. */
    private void refresh() {
        seenFinished = MapExport.finished();
        File shown = viewing >= 0 && viewing < pictures.size() ? pictures.get(viewing).root : null;
        allPictures = MapPictures.list();
        folderBytes = MapPictures.folderSize();
        Set<File> present = new HashSet<>();
        for (MapPictures.Picture picture : allPictures) {
            present.add(picture.preview);
            kinds.computeIfAbsent(picture.root, file -> kindOf(picture));
        }
        if (seenAtOpen == null) {
            seenAtOpen = new HashSet<>();
            for (MapPictures.Picture picture : allPictures) {
                seenAtOpen.add(picture.root);
            }
        }
        for (Iterator<Map.Entry<File, Shown>> it = thumbs.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<File, Shown> entry = it.next();
            if (!present.contains(entry.getKey())) {
                entry.getValue()
                    .release();
                it.remove();
            }
        }
        applyFilter();
        if (viewing >= 0) {
            // The same picture stays open if it is still there.
            int index = indexOf(shown);
            showBig(index >= 0 ? index : Math.min(viewing, pictures.size() - 1));
        }
    }

    private int indexOf(File root) {
        for (int i = 0; root != null && i < pictures.size(); i++) {
            if (pictures.get(i).root.equals(root)) {
                return i;
            }
        }
        return -1;
    }

    /** The pictures the filter lets through. */
    private void applyFilter() {
        pictures = new ArrayList<>();
        for (MapPictures.Picture picture : allPictures) {
            if (passes(picture, filter)) {
                pictures.add(picture);
            }
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private boolean passes(MapPictures.Picture picture, int which) {
        Kind kind = kinds.get(picture.root);
        switch (FILTERS[which]) {
            case "flat":
                return kind != null && kind.kind != null && !kind.iso;
            case "iso":
                return kind != null && kind.iso;
            case "web":
                return picture.site;
            default:
                return true;
        }
    }

    private int count(int which) {
        int count = 0;
        for (MapPictures.Picture picture : allPictures) {
            count += passes(picture, which) ? 1 : 0;
        }
        return count;
    }

    /** What the picture shows, from its name. */
    private static Kind kindOf(MapPictures.Picture picture) {
        Matcher m = NAME.matcher(picture.name);
        if (!m.find()) {
            return new Kind(null, null, Icons.CAMERA, Theme.TEXT_MUTED, 0, false);
        }
        String what = m.group(1);
        int pixels = Integer.parseInt(m.group(2));
        boolean night = m.group(3) != null;
        if (what.startsWith("caves_")) {
            return new Kind(
                "caves",
                Lang.format("wayfarmap.pictures.layer.caves", what.substring("caves_".length())),
                Icons.CAVES,
                CAVES_COLOR,
                pixels,
                false);
        }
        switch (what) {
            case "3d":
                return new Kind(what, "3D", Icons.ISO, ISO_COLOR, pixels, night);
            case "bare":
                return new Kind(
                    what,
                    Lang.format("wayfarmap.pictures.layer.bare"),
                    Icons.NO_PLANTS,
                    BARE_COLOR,
                    pixels,
                    false);
            case "topo":
                return new Kind(
                    what,
                    Lang.format("wayfarmap.pictures.layer.topo"),
                    Icons.TOPO,
                    TOPO_COLOR,
                    pixels,
                    false);
            case "biomes":
                return new Kind(
                    what,
                    Lang.format("wayfarmap.pictures.layer.biomes"),
                    Icons.BIOMES,
                    BIOMES_COLOR,
                    pixels,
                    false);
            default:
                return new Kind(
                    what,
                    Lang.format("wayfarmap.pictures.layer.2d"),
                    Icons.MAP2D,
                    FLAT_COLOR,
                    pixels,
                    false);
        }
    }

    private Kind kind(MapPictures.Picture picture) {
        Kind kind = kinds.get(picture.root);
        return kind != null ? kind : kindOf(picture);
    }

    /** A short title for the picture: what it shows and how detailed, or its file's name. */
    private String title(MapPictures.Picture picture) {
        Kind kind = kind(picture);
        if (kind.kind == null) {
            return picture.name;
        }
        String night = kind.night ? " · " + Lang.format("wayfarmap.pictures.night_short") : "";
        return kind.label + " · " + kind.pixels + " px" + night;
    }

    private static Shown load(File file, int maxSide) {
        Shown shown = new Shown();
        LOADER.submit(() -> {
            BufferedImage image = MapPictures.read(file, maxSide);
            if (image == null) {
                shown.failed = true;
            } else {
                shown.read = image;
            }
        });
        return shown;
    }

    private Shown thumb(MapPictures.Picture picture) {
        return thumbs.computeIfAbsent(picture.preview, file -> load(file, THUMB_SIZE));
    }

    private int[] dimensions(MapPictures.Picture picture) {
        return dimensions.computeIfAbsent(picture.image, MapPictures::dimensions);
    }

    /** Opens the picture big (or goes on to another one), fitted to the window. */
    private void showBig(int index) {
        if (index < 0 || index >= pictures.size()) {
            closeBig();
            return;
        }
        File file = pictures.get(index).image;
        if (viewing < 0) {
            viewOpen.set(0);
            stripScroll.set(index);
        }
        if (index != viewing || viewImage == null || !file.equals(viewFile)) {
            if (viewImage != null) {
                viewImage.release();
            }
            viewImage = load(file, VIEW_SIZE);
            viewFile = file;
            viewZoom = 1;
            viewPanX = viewPanY = 0;
        }
        viewing = index;
        armed = -1;
    }

    private void closeBig() {
        if (viewImage != null) {
            viewImage.release();
        }
        viewImage = null;
        viewFile = null;
        viewing = -1;
        viewDragging = false;
        armed = -1;
    }

    private void flip(int by) {
        if (viewing >= 0 && !pictures.isEmpty()) {
            showBig(Math.floorMod(viewing + by, pictures.size()));
        }
    }

    private void say(String text, int color) {
        toast = text;
        toastColor = color;
        toastAt = System.currentTimeMillis();
    }

    private void copy(MapPictures.Picture picture) {
        int[] size = dimensions(picture);
        boolean smaller = size != null && Math.max(size[0], size[1]) > MapPictures.CLIPBOARD_MAX;
        say(Lang.format("wayfarmap.pictures.copying"), Theme.TEXT_MUTED);
        MapPictures.copy(picture, failure -> {
            if (failure != null) {
                say(Lang.format("wayfarmap.pictures.copy_failed", failure), Theme.DANGER);
            } else if (smaller) {
                say(Lang.format("wayfarmap.pictures.copied_smaller", MapPictures.CLIPBOARD_MAX), Theme.SUCCESS);
            } else {
                say(Lang.format("wayfarmap.pictures.copied"), Theme.SUCCESS);
            }
        });
    }

    /** Deletes the picture, saying how it went; the list is read again. */
    private void delete(MapPictures.Picture picture) {
        if (MapPictures.delete(picture)) {
            say(Lang.format("wayfarmap.pictures.deleted"), Theme.SUCCESS);
        } else {
            say(Lang.format("wayfarmap.pictures.delete_failed"), Theme.DANGER);
        }
    }

    // ---------------------------------------------------------------- input

    /** First click of a delete button: it asks again; true when this click confirms. */
    private boolean confirmed(int id) {
        long now = System.currentTimeMillis();
        if (armed != id || now - armedAt > CONFIRM_MS) {
            armed = id;
            armedAt = now;
            return false;
        }
        armed = -1;
        return true;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_CLOSE:
                mc.displayGuiScreen(parent);
                break;
            case ID_2D:
            case ID_3D:
                iso = button.id == ID_3D;
                break;
            case ID_MAKE:
                if (MapExport.running()) {
                    MapExport.cancel();
                } else {
                    make();
                }
                break;
            case ID_FOLDER:
                File folder = MapExport.folder();
                folder.mkdirs();
                MapPictures.open(folder);
                break;
            case ID_DELETE_ALL:
                if (confirmed(ID_DELETE_ALL)) {
                    int deleted = MapPictures.deleteAll();
                    say(Lang.format("wayfarmap.pictures.deleted_all", deleted), Theme.SUCCESS);
                    refresh();
                }
                break;
            case ID_VIEW_CLOSE:
                closeBig();
                break;
            case ID_VIEW_PREVIOUS:
                flip(-1);
                break;
            case ID_VIEW_NEXT:
                flip(1);
                break;
            case ID_VIEW_COPY:
                copy(pictures.get(viewing));
                break;
            case ID_VIEW_OPEN:
                MapPictures.open(
                    pictures.get(viewing)
                        .openable());
                break;
            case ID_VIEW_DELETE:
                if (confirmed(ID_VIEW_DELETE)) {
                    deleteViewed();
                }
                break;
            default:
                break;
        }
    }

    private void deleteViewed() {
        int index = viewing;
        delete(pictures.get(index));
        refresh();
        if (pictures.isEmpty()) {
            closeBig();
        } else {
            showBig(Math.min(index, pictures.size() - 1));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (viewing >= 0) {
            boolean control = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            if (keyCode == Keyboard.KEY_ESCAPE) {
                if (armed != -1) {
                    armed = -1;
                } else {
                    closeBig();
                }
            } else if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_A) {
                flip(-1);
            } else if (keyCode == Keyboard.KEY_RIGHT || keyCode == Keyboard.KEY_D) {
                flip(1);
            } else if (keyCode == Keyboard.KEY_HOME) {
                showBig(0);
            } else if (keyCode == Keyboard.KEY_END) {
                showBig(pictures.size() - 1);
            } else if (keyCode == Keyboard.KEY_C && control) {
                copy(pictures.get(viewing));
            } else if (keyCode == Keyboard.KEY_DELETE && confirmed(ID_VIEW_DELETE)) {
                deleteViewed();
            } else if (keyCode == Keyboard.KEY_RETURN) {
                MapPictures.open(
                    pictures.get(viewing)
                        .openable());
            } else if (keyCode == Keyboard.KEY_EQUALS || keyCode == Keyboard.KEY_ADD) {
                zoomAt(width / 2, viewCenterY(), 1.25);
            } else if (keyCode == Keyboard.KEY_MINUS || keyCode == Keyboard.KEY_SUBTRACT) {
                zoomAt(width / 2, viewCenterY(), 0.8);
            } else if (keyCode == Keyboard.KEY_0 || keyCode == Keyboard.KEY_NUMPAD0) {
                viewZoom = 1;
                viewPanX = viewPanY = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (armed != -1) {
                armed = -1;
            } else {
                mc.displayGuiScreen(parent);
            }
        } else if (keyCode == Keyboard.KEY_RETURN && !MapExport.running() && canMake()) {
            make();
        }
    }

    private int viewAreaBottom() {
        return height - STRIP;
    }

    private double viewCenterY() {
        return (VIEW_BAR + viewAreaBottom()) / 2.0;
    }

    /** Zooms the big picture around the point: what is under it stays there. */
    private void zoomAt(double x, double y, double by) {
        double before = viewZoom;
        viewZoom = Math.max(1, Math.min(32, viewZoom * by));
        double cx = width / 2.0 + viewPanX, cy = viewCenterY() + viewPanY;
        double change = viewZoom / before;
        viewPanX += (x - cx) * (1 - change);
        viewPanY += (y - cy) * (1 - change);
        if (viewZoom == 1) {
            viewPanX = viewPanY = 0;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (viewing >= 0) {
            if (mouseY >= viewAreaBottom()) {
                flip(wheel > 0 ? -1 : 1);
            } else {
                zoomAt(mouseX, mouseY, wheel > 0 ? 1.25 : 0.8);
            }
            return;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - Integer.signum(wheel) * (cardHeight() + GAP) / 2.0));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (viewing >= 0) {
            clickBig(mouseX, mouseY, button);
            return;
        }
        if (overButton(mouseX, mouseY)) {
            return;
        }
        int x = left + 10;
        if (button == 0 && mouseX >= x && mouseX < x + LEFT_WIDTH) {
            clickChoices(mouseX, mouseY);
            return;
        }
        int chip = chipAt(mouseX, mouseY);
        if (button == 0 && chip >= 0) {
            filter = chip;
            scroll = 0;
            applyFilter();
            return;
        }
        int index = cardAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        MapPictures.Picture picture = pictures.get(index);
        if (button == 1) {
            // Right click: straight to the program that opens it.
            MapPictures.open(picture.openable());
            return;
        }
        if (button != 0) {
            return;
        }
        int quick = quickAt(index, mouseX, mouseY);
        if (quick == 0) {
            copy(picture);
        } else if (quick == 1) {
            MapPictures.open(picture.openable());
        } else if (quick == 2) {
            if (confirmed(ID_CARD_DELETE + index)) {
                delete(picture);
                refresh();
            }
        } else {
            showBig(index);
        }
    }

    private void clickBig(int mouseX, int mouseY, int button) {
        if (button != 0 || overButton(mouseX, mouseY)) {
            return;
        }
        if (mouseY >= viewAreaBottom()) {
            int index = stripAt(mouseX, mouseY);
            if (index >= 0) {
                showBig(index);
            }
            return;
        }
        if (mouseY <= VIEW_BAR) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickAt < DOUBLE_CLICK_MS) {
            // Double click: close up where clicked, or back to the whole picture.
            if (viewZoom > 1) {
                viewZoom = 1;
                viewPanX = viewPanY = 0;
            } else {
                zoomAt(mouseX, mouseY, 4);
            }
            lastClickAt = 0;
            return;
        }
        lastClickAt = now;
        viewDragging = true;
        dragX = mouseX;
        dragY = mouseY;
    }

    private boolean overButton(int mouseX, int mouseY) {
        for (Object o : buttonList) {
            FlatButton b = (FlatButton) o;
            if (b.visible && b.enabled && b.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    /** A click on the left side: a layer, the night, a detail or the format. */
    private void clickChoices(int mouseX, int mouseY) {
        if (MapExport.running()) {
            return;
        }
        int layer = layerAt(mouseX, mouseY);
        if (layer >= 0) {
            if (iso) {
                night = !night;
            } else if (layers.get(layer).usable) {
                String key = layers.get(layer).key;
                if (!chosenLayers.remove(key)) {
                    chosenLayers.add(key);
                }
                countFlat();
            }
            return;
        }
        List<Quality> list = choices();
        int row = Math.floorDiv(mouseY - qualityTop(), ROW);
        if (mouseY >= qualityTop() && row >= 0 && row < list.size()) {
            if (list.get(row).usable || !iso) {
                if (iso) {
                    isoQuality = row;
                } else {
                    flatQuality = row;
                }
            }
            return;
        }
        if (mouseY >= formatTop()) {
            int format = Math.floorDiv(mouseY - formatTop(), ROW + 1);
            if (format == 0 || format == 1) {
                site = format == 1;
            }
        }
    }

    /** The layer tile under the mouse (in 3D the night's), or -1. */
    private int layerAt(int mouseX, int mouseY) {
        int count = iso ? 1 : layers.size();
        for (int i = 0; i < count; i++) {
            int[] t = tile(i);
            if (Theme.inside(mouseX, mouseY, t[0], t[1], t[2], t[3])) {
                return i;
            }
        }
        return -1;
    }

    /** The filter chips: left, right of each. */
    private int[][] chips() {
        int[][] chips = new int[FILTERS.length][2];
        int x = galleryLeft;
        for (int i = 0; i < FILTERS.length; i++) {
            int w = fontRendererObj.getStringWidth(chipText(i)) + 12;
            chips[i][0] = x;
            chips[i][1] = x + w;
            x += w + 4;
        }
        return chips;
    }

    private String chipText(int i) {
        return Lang.format("wayfarmap.pictures.filter." + FILTERS[i]) + "  " + count(i);
    }

    private int chipAt(int mouseX, int mouseY) {
        if (mouseY < filterTop() || mouseY >= filterTop() + 13) {
            return -1;
        }
        int[][] chips = chips();
        for (int i = 0; i < chips.length; i++) {
            if (mouseX >= chips[i][0] && mouseX < chips[i][1]) {
                return i;
            }
        }
        return -1;
    }

    /** The small picture under the mouse, or -1. */
    private int cardAt(int mouseX, int mouseY) {
        if (mouseX < galleryLeft || mouseX >= right - 10 || mouseY < gridTop || mouseY >= contentBottom) {
            return -1;
        }
        int columns = columns(), cardWidth = cardWidth(), cardHeight = cardHeight();
        int column = (mouseX - galleryLeft) / (cardWidth + GAP);
        int y = mouseY - gridTop + (int) Math.round(scrollShown.get());
        int row = y / (cardHeight + GAP);
        if (column >= columns || (mouseX - galleryLeft) % (cardWidth + GAP) >= cardWidth
            || y % (cardHeight + GAP) >= cardHeight) {
            return -1;
        }
        int index = row * columns + column;
        return index < pictures.size() ? index : -1;
    }

    /** Where the card of the picture is: its left and top. */
    private int[] cardPosition(int index) {
        int columns = columns();
        int cx = galleryLeft + index % columns * (cardWidth() + GAP);
        int cy = gridTop + index / columns * (cardHeight() + GAP) - (int) Math.round(scrollShown.get());
        return new int[] { cx, cy };
    }

    /** Left of the card's quick button (0 copy, 1 open, 2 delete); they sit at the bottom right of the picture. */
    private int quickX(int cx, int i) {
        return cx + cardWidth() - 4 - (3 - i) * (QUICK + 2) + 2;
    }

    /** The quick button of the card under the mouse (0 copy, 1 open, 2 delete), or -1. */
    private int quickAt(int index, int mouseX, int mouseY) {
        int[] p = cardPosition(index);
        int y0 = p[1] + thumbHeight() - QUICK - 3;
        for (int i = 0; i < 3; i++) {
            int x0 = quickX(p[0], i);
            if (Theme.inside(mouseX, mouseY, x0, y0, x0 + QUICK, y0 + QUICK)) {
                return i;
            }
        }
        return -1;
    }

    /** Left of the strip's small picture, by the picture's index. */
    private double stripX(int index) {
        return width / 2.0 - STRIP_W / 2.0 + (index - stripScroll.get()) * (STRIP_W + 4);
    }

    private int stripAt(int mouseX, int mouseY) {
        int y0 = viewAreaBottom() + (STRIP - STRIP_H) / 2;
        if (mouseY < y0 || mouseY >= y0 + STRIP_H) {
            return -1;
        }
        for (int i = 0; i < pictures.size(); i++) {
            double x0 = stripX(i);
            if (mouseX >= x0 && mouseX < x0 + STRIP_W) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onGuiClosed() {
        for (Shown shown : thumbs.values()) {
            shown.release();
        }
        thumbs.clear();
        closeBig();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        if (MapExport.finished() != seenFinished) {
            refresh();
        }
        if (armed != -1 && System.currentTimeMillis() - armedAt > CONFIRM_MS) {
            armed = -1;
        }
        tooltip = null;
        boolean big = viewing >= 0 && viewing < pictures.size();
        updateButtons(big);

        Theme.dimScreen(0, 0, width, height);
        // A soft shadow around the window.
        Theme.fill(left - 2, top - 1, right + 2, bottom + 3, 0x30000000);
        Theme.fill(left - 1, top, right + 1, bottom + 2, 0x40000000);
        Theme.panel(left, top, right, bottom);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            right,
            right - WindowHeader.CLOSE_ROOM,
            Icons.CAMERA,
            Lang.format("wayfarmap.pictures.title"),
            Lang.format("wayfarmap.pictures.subtitle"),
            Theme.TEXT_MUTED,
            null);
        drawChoices(big ? -10000 : mouseX, mouseY);
        // Between the two sides.
        Theme.fill(galleryLeft - 9, contentTop, galleryLeft - 8, contentBottom, Theme.BORDER);
        drawGallery(big ? -10000 : mouseX, mouseY);
        if (!big) {
            super.drawScaled(mouseX, mouseY, partialTicks);
            drawToast(height - 4);
            drawIsoNote(mouseX, mouseY);
            drawTooltip(mouseX, mouseY);
            return;
        }
        drawBig(mouseX, mouseY);
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawToast(viewAreaBottom() - 6);
        drawTooltip(mouseX, mouseY);
    }

    private void drawTooltip(int mouseX, int mouseY) {
        if (tooltip == null) {
            return;
        }
        drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    /** Why 3D can't be picked, under the mouse over its button. */
    private void drawIsoNote(int mouseX, int mouseY) {
        if (isoAvailable || !isoButton.isMouseOver(mouseX, mouseY)) {
            return;
        }
        drawHoveringText(
            fontRendererObj.listFormattedStringToWidth(Lang.format("wayfarmap.pictures.iso_empty"), 180),
            mouseX,
            mouseY,
            fontRendererObj);
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    private void updateButtons(boolean big) {
        boolean running = MapExport.running();
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            boolean viewButton = b.id >= ID_VIEW_CLOSE;
            b.visible = viewButton == big;
        }
        flatButton.active = !iso;
        isoButton.active = iso;
        flatButton.enabled = !running;
        isoButton.enabled = !running && isoAvailable;
        int count = pictureCount();
        makeButton.displayString = running ? Lang.format("wayfarmap.pictures.stop")
            : count > 1 ? Lang.format("wayfarmap.pictures.make_many", count) : Lang.format("wayfarmap.pictures.make");
        makeButton.danger = running;
        makeButton.active = !running && canMake();
        makeButton.enabled = running || canMake();
        makeButton.icon = running ? null : Icons.CAMERA;
        deleteAllButton.displayString = Lang
            .format(armed == ID_DELETE_ALL ? "wayfarmap.pictures.sure" : "wayfarmap.pictures.delete_all");
        deleteAllButton.active = armed == ID_DELETE_ALL;
        deleteAllButton.enabled = !allPictures.isEmpty() && !running;
        deleteButton.displayString = Lang
            .format(armed == ID_VIEW_DELETE ? "wayfarmap.pictures.sure" : "wayfarmap.pictures.delete");
        deleteButton.active = armed == ID_VIEW_DELETE;
        previousButton.enabled = nextButton.enabled = pictures.size() > 1;
    }

    /** A section's name with a thin line after it to the right edge. */
    private void sectionLabel(String text, int x, int y, int x1) {
        Theme.text(fontRendererObj, text, x, y, Theme.TEXT_MUTED);
        int lineX = x + fontRendererObj.getStringWidth(text) + 5;
        if (lineX < x1) {
            Theme.fill(lineX, y + 4, x1, y + 5, Theme.BORDER);
        }
    }

    private void drawChoices(int mouseX, int mouseY) {
        int x = left + 10, x1 = x + LEFT_WIDTH;
        boolean running = MapExport.running();
        // The side as a card of its own.
        Theme.fill(x - 4, contentTop - 4, x1 + 4, contentBottom + 4, 0x40000000);
        Theme.outline(x - 4, contentTop - 4, x1 + 4, contentBottom + 4, Theme.BORDER);

        // What to save: the 2D maps (any of them, each a picture of its own), or 3D at night.
        sectionLabel(
            Lang.format(iso ? "wayfarmap.pictures.options" : "wayfarmap.pictures.layers"),
            x,
            layersTop() - 12,
            x1);
        if (iso) {
            int[] t = tile(0);
            drawTile(
                t,
                Icons.NIGHT,
                NIGHT_COLOR,
                Lang.format("wayfarmap.pictures.night"),
                night,
                !running,
                Theme.inside(mouseX, mouseY, t[0], t[1], t[2], t[3]),
                nightHover);
        } else if (layers.isEmpty()) {
            Theme.text(
                fontRendererObj,
                Lang.format("wayfarmap.pictures.no_map"),
                x,
                layersTop() + 6,
                Theme.TEXT_DISABLED);
        } else {
            for (int i = 0; i < layers.size(); i++) {
                Layer layer = layers.get(i);
                int[] t = tile(i);
                boolean hovered = Theme.inside(mouseX, mouseY, t[0], t[1], t[2], t[3]);
                drawTile(
                    t,
                    layer.icon,
                    layer.color,
                    layer.label,
                    chosenLayers.contains(layer.key) && layer.usable,
                    !running && layer.usable,
                    hovered,
                    layer.hover);
                if (hovered && !layer.usable) {
                    tooltip = Collections.singletonList(Lang.format("wayfarmap.pictures.layer_empty"));
                }
            }
        }

        // How detailed, with the picture's size.
        int qualityY = qualityLabelY();
        sectionLabel(Lang.format("wayfarmap.pictures.quality"), x, qualityY, x1);
        List<Quality> list = choices();
        int selected = iso ? isoQuality : flatQuality;
        if (list.isEmpty()) {
            String none = Lang.format("wayfarmap.pictures.no_3d");
            Theme.text(fontRendererObj, none, x, qualityTop() + 3, Theme.TEXT_DISABLED);
        }
        for (int i = 0; i < list.size(); i++) {
            Quality quality = list.get(i);
            int y = qualityTop() + i * ROW;
            boolean usable = quality.usable;
            boolean hovered = !running && usable && Theme.inside(mouseX, mouseY, x, y, x1, y + ROW);
            drawChoiceRow(x, y, x1, i == selected, hovered, usable && !running);
            int labelColor = usable ? Theme.TEXT : Theme.TEXT_DISABLED;
            Theme.text(fontRendererObj, quality.label, x + 14, y + 3, labelColor);
            if (!usable && !iso) {
                continue;
            }
            int labelWidth = fontRendererObj.getStringWidth(quality.label);
            String size = Theme.ellipsize(fontRendererObj, quality.size, x1 - 4 - (x + 14 + labelWidth + 6));
            Theme.text(
                fontRendererObj,
                size,
                x1 - 4 - fontRendererObj.getStringWidth(size),
                y + 3,
                i == selected ? Theme.ACCENT : Theme.TEXT_MUTED);
        }

        // One picture, or a page for the browser.
        sectionLabel(Lang.format("wayfarmap.pictures.format"), x, formatLabelY(), x1);
        String[] formats = { "single", "site" };
        for (int i = 0; i < formats.length; i++) {
            int y = formatTop() + i * (ROW + 1);
            boolean on = site == (i == 1);
            boolean hovered = !running && Theme.inside(mouseX, mouseY, x, y, x1, y + ROW);
            drawChoiceRow(x, y, x1, on, hovered, !running);
            Theme.text(fontRendererObj, Lang.format("wayfarmap.pictures." + formats[i]), x + 14, y + 3, Theme.TEXT);
            String tag = Lang.format("wayfarmap.pictures." + formats[i] + "_tag");
            int tagWidth = fontRendererObj.getStringWidth(tag);
            int tagX = x1 - 6 - tagWidth;
            Theme.fill(tagX - 3, y + 2, x1 - 3, y + ROW - 2, on ? Theme.ACCENT_DIM : Theme.CONTROL);
            Theme.text(fontRendererObj, tag, tagX, y + 3, on ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        int infoBottom = makeButton.yPosition - (running ? 30 : 16);
        int hintY = formatTop() + 2 * (ROW + 1) + 3;
        String hintText = Lang.format("wayfarmap.pictures." + (site ? "site" : "single") + "_hint");
        List<?> hint = fontRendererObj.listFormattedStringToWidth(hintText, LEFT_WIDTH);
        for (int i = 0; i < hint.size() && hintY + i * 10 + 8 <= infoBottom; i++) {
            Theme.text(fontRendererObj, (String) hint.get(i), x, hintY + i * 10, Theme.TEXT_DISABLED);
        }

        if (running) {
            drawProgress(x, x1, makeButton.yPosition - 28);
            return;
        }
        // What the button will make, or why it can't.
        int summaryY = makeButton.yPosition - 12;
        String summary;
        int color = Theme.TEXT_MUTED;
        if (!iso && chosenUsable().isEmpty()) {
            summary = Lang.format(layers.isEmpty() ? "wayfarmap.pictures.no_map" : "wayfarmap.pictures.pick_layer");
            color = Theme.DANGER;
        } else if (!iso) {
            StringBuilder names = new StringBuilder();
            for (Layer layer : chosenUsable()) {
                names.append(names.length() == 0 ? "" : ", ")
                    .append(layer.label);
            }
            summary = names.toString();
        } else {
            summary = "3D" + (night ? " · " + Lang.format("wayfarmap.pictures.night_short") : "");
        }
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, summary, LEFT_WIDTH), x, summaryY, color);
    }

    private final Smooth nightHover = new Smooth(0);

    /** Making pictures: what is being made, which of how many, and how far, all over the button that stops it. */
    private void drawProgress(int x, int x1, int y) {
        String label = MapExport.currentLabel();
        int[] position = MapExport.position();
        double overall = MapExport.progress(), one = MapExport.pictureProgress();
        String left = label != null ? label : Lang.format("wayfarmap.pictures.making");
        if (position != null && position[1] > 1) {
            left = (position[0] + 1) + "/" + position[1] + " · " + left;
        }
        String percent = overall < 0 ? Lang.format("wayfarmap.export.preparing")
            : (int) Math.floor((position != null && position[1] > 1 ? one : overall) * 100) + "%";
        int percentWidth = fontRendererObj.getStringWidth(percent);
        Theme
            .text(fontRendererObj, Theme.ellipsize(fontRendererObj, left, x1 - x - percentWidth - 6), x, y, Theme.TEXT);
        Theme.text(fontRendererObj, percent, x1 - percentWidth, y, Theme.ACCENT);
        int barY = y + 12, barH = 6;
        Theme.fill(x, barY, x1, barY + barH, Theme.CONTROL);
        int w = x1 - x;
        if (overall >= 0) {
            int filled = (int) Math.round(w * overall);
            Theme.fill(x, barY, x + filled, barY + barH, Theme.ACCENT_DIM);
            Theme.fill(x, barY, x + filled, barY + 2, Theme.ACCENT);
            // A light running along what is done.
            double t = System.currentTimeMillis() % 1400 / 1400.0;
            int sheen = (int) Math.round(x - 20 + (filled + 20) * t);
            int s0 = Math.max(x, sheen), s1 = Math.min(x + filled, sheen + 20);
            if (s1 > s0) {
                Theme.fill(s0, barY, s1, barY + barH, 0x30FFFFFF);
            }
        } else {
            // Preparing: a stripe going back and forth.
            double t = System.currentTimeMillis() % 1600 / 1600.0;
            int stripe = w / 4;
            int from = x + (int) Math.round((w - stripe) * (0.5 - 0.5 * Math.cos(t * 2 * Math.PI)));
            Theme.fill(from, barY, from + stripe, barY + barH, Theme.ACCENT_DIM);
        }
        Theme.outline(x, barY, x1, barY + barH, Theme.BORDER);
    }

    /** A tile to switch on and off: its icon in its color, the name, a box marked when on. */
    private void drawTile(int[] t, String[] icon, int color, String label, boolean on, boolean enabled, boolean hovered,
        Smooth hover) {
        double lit = hover.update(hovered && enabled ? 1 : 0, 22);
        int x0 = t[0], y0 = t[1], x1 = t[2], y1 = t[3];
        int background = on ? Theme.blend(Theme.CONTROL, color, 0.16 + 0.08 * lit)
            : Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
        if (!enabled && !on) {
            background = Theme.CONTROL_DISABLED;
        }
        int border = on ? Theme.blend(color, 0xFFFFFFFF, 0.15 * lit) : Theme.blend(Theme.BORDER, color, 0.6 * lit);
        Theme.fill(x0, y0, x1, y1, background);
        Theme.outline(x0, y0, x1, y1, border);
        if (on) {
            Theme.fill(x0, y0, x0 + 2, y1, color);
        }
        int iconColor = !enabled && !on ? Theme.TEXT_DISABLED : Theme.iconShade(color, on || lit > 0.5, false, !on);
        Icons.draw(icon, x0 + 6, y0 + (TILE - icon.length) / 2, iconColor);
        int textX = x0 + 6 + Icons.width(icon) + 5;
        int box = x1 - 13;
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, label, box - 3 - textX),
            textX,
            y0 + 6,
            !enabled && !on ? Theme.TEXT_DISABLED : on ? Theme.TEXT : Theme.TEXT_MUTED);
        // The box.
        int by = y0 + (TILE - 9) / 2;
        if (on) {
            Theme.fill(box, by, box + 9, by + 9, color);
            Icons.draw(Icons.SMALL_CHECK, box + 1, by + 1, 0xFF0C0E11);
        } else {
            Theme.fill(box, by, box + 9, by + 9, enabled ? Theme.blend(Theme.BORDER, color, 0.5 * lit) : Theme.CONTROL);
            Theme.fill(box + 1, by + 1, box + 8, by + 8, Theme.PANEL | 0xFF000000);
        }
    }

    /** A choice's row: lit under the mouse, marked when chosen with a round mark. */
    private void drawChoiceRow(int x, int y, int x1, boolean on, boolean hovered, boolean enabled) {
        if (on) {
            Theme.fill(x, y, x1, y + ROW - 1, SELECTED_ROW);
            Theme.fill(x, y, x + 2, y + ROW - 1, Theme.ACCENT);
        } else if (hovered) {
            Theme.fill(x, y, x1, y + ROW - 1, Theme.ROW_HOVER);
        }
        double cx = x + 8, cy = y + 6.5;
        int ring = enabled ? Theme.BORDER : Theme.CONTROL;
        Theme.disc(cx, cy, 3.5, on ? Theme.ACCENT : ring);
        Theme.disc(cx, cy, 2.5, Theme.PANEL | 0xFF000000);
        if (on) {
            Theme.disc(cx, cy, 1.5, Theme.ACCENT);
        }
    }

    private void drawGallery(int mouseX, int mouseY) {
        int x1 = right - 10;
        String title = Lang.format("wayfarmap.pictures.gallery");
        Theme.text(fontRendererObj, title, galleryLeft, contentTop, Theme.TEXT);
        if (folderBytes > 0) {
            // How much the whole folder takes on disk, up to the buttons.
            int sizeX = galleryLeft + fontRendererObj.getStringWidth(title) + 6;
            String size = Theme.ellipsize(
                fontRendererObj,
                Lang.format("wayfarmap.pictures.folder_size", bytes(folderBytes)),
                folderButton.xPosition - 6 - sizeX);
            Theme.text(fontRendererObj, size, sizeX, contentTop, Theme.TEXT_DISABLED);
        }
        drawChips(mouseX, mouseY);

        double shownScroll = scrollShown.update(scroll, 18);
        if (pictures.isEmpty()) {
            int cx = (galleryLeft + x1) / 2, cy = (gridTop + contentBottom) / 2;
            // A frame with the camera in it.
            Theme.fill(cx - 22, cy - 40, cx + 22, cy - 12, Theme.CONTROL);
            Theme.outline(cx - 22, cy - 40, cx + 22, cy - 12, Theme.BORDER);
            Icons.draw(Icons.CAMERA, cx - Icons.width(Icons.CAMERA) / 2, cy - 30, Theme.TEXT_DISABLED);
            boolean none = allPictures.isEmpty();
            Theme.centered(
                fontRendererObj,
                Lang.format(none ? "wayfarmap.pictures.empty" : "wayfarmap.pictures.empty_filter"),
                cx,
                cy - 4,
                Theme.TEXT_MUTED);
            if (none) {
                String hint = Lang.format("wayfarmap.pictures.empty_hint");
                Theme.centered(fontRendererObj, hint, cx, cy + 8, Theme.TEXT_DISABLED);
            }
            return;
        }
        int cardWidth = cardWidth(), cardHeight = cardHeight(), thumbHeight = thumbHeight();
        int hovered = cardAt(mouseX, mouseY);
        Theme.clip(galleryLeft - 2, gridTop, x1 + 2, contentBottom);
        for (int i = 0; i < pictures.size(); i++) {
            int[] p = cardPosition(i);
            int cx = p[0], cy = p[1];
            if (cy + cardHeight <= gridTop || cy >= contentBottom) {
                continue;
            }
            drawCard(i, cx, cy, cardWidth, cardHeight, thumbHeight, i == hovered, mouseX, mouseY);
        }
        Theme.unclip();
        // Fades at the edges of the grid when there is more past them.
        int maxScroll = maxScroll();
        if (shownScroll > 1) {
            gradient(
                galleryLeft - 2,
                gridTop,
                x1 + 2,
                gridTop + 8,
                Theme.alpha(Theme.PANEL, 0xF0),
                Theme.alpha(Theme.PANEL, 0));
        }
        if (shownScroll < maxScroll - 1) {
            gradient(
                galleryLeft - 2,
                contentBottom - 8,
                x1 + 2,
                contentBottom,
                Theme.alpha(Theme.PANEL, 0),
                Theme.alpha(Theme.PANEL, 0xF0));
        }
        if (maxScroll > 0) {
            int visible = contentBottom - gridTop;
            Theme.scrollbar(
                x1 + 4,
                gridTop,
                contentBottom,
                visible,
                visible + maxScroll,
                shownScroll / maxScroll,
                Theme.inside(mouseX, mouseY, galleryLeft, gridTop, x1 + 8, contentBottom));
        }
    }

    private void drawChips(int mouseX, int mouseY) {
        int[][] chips = chips();
        int y0 = filterTop(), y1 = y0 + 13;
        for (int i = 0; i < chips.length; i++) {
            boolean on = filter == i;
            boolean hovered = Theme.inside(mouseX, mouseY, chips[i][0], y0, chips[i][1], y1);
            int count = count(i);
            Theme.fill(
                chips[i][0],
                y0,
                chips[i][1],
                y1,
                on ? Theme.ACCENT_DIM : hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            int border = on ? Theme.ACCENT : hovered ? Theme.ACCENT_DIM : Theme.BORDER;
            Theme.outline(chips[i][0], y0, chips[i][1], y1, border);
            String label = Lang.format("wayfarmap.pictures.filter." + FILTERS[i]);
            int textX = chips[i][0] + 6;
            int color = on ? Theme.TEXT : count == 0 ? Theme.TEXT_DISABLED : Theme.TEXT_MUTED;
            Theme.text(fontRendererObj, label, textX, y0 + 3, color);
            String number = String.valueOf(count);
            Theme.text(
                fontRendererObj,
                number,
                chips[i][1] - 6 - fontRendererObj.getStringWidth(number),
                y0 + 3,
                on ? Theme.ACCENT_SOFT : Theme.TEXT_DISABLED);
        }
    }

    private void drawCard(int i, int cx, int cy, int cardWidth, int cardHeight, int thumbHeight, boolean hovered,
        int mouseX, int mouseY) {
        MapPictures.Picture picture = pictures.get(i);
        Kind kind = kind(picture);
        double lit = cardHover.computeIfAbsent(picture.root, f -> new Smooth(0))
            .update(hovered ? 1 : 0, 20);
        boolean fresh = seenAtOpen != null && !seenAtOpen.contains(picture.root);
        if (lit > 0.01) {
            Theme.fill(cx + 1, cy + 2, cx + cardWidth + 2, cy + cardHeight + 2, Theme.blend(0, SHADOW, lit));
        }
        Theme.fill(cx, cy, cx + cardWidth, cy + cardHeight, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
        int border = fresh ? Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, lit)
            : Theme.blend(Theme.BORDER, kind.color, lit);
        Theme.outline(cx, cy, cx + cardWidth, cy + cardHeight, border);
        drawCover(thumb(picture), cx + 1, cy + 1, cx + cardWidth - 1, cy + 1 + thumbHeight, lit);
        // A line in the color of what it shows, under the picture.
        Theme.fill(
            cx + 1,
            cy + 1 + thumbHeight,
            cx + cardWidth - 1,
            cy + 2 + thumbHeight,
            Theme.blend(Theme.blend(kind.color, Theme.CONTROL, 0.5), kind.color, lit));

        // What it shows, top left; a page for the browser and a new one, top right.
        int badgeX = cx + 4;
        if (kind.kind != null) {
            String px = kind.pixels + "px";
            int w = Icons.width(kind.icon) + 4 + fontRendererObj.getStringWidth(px) + 6;
            Theme.fill(badgeX, cy + 4, badgeX + w, cy + 15, BADGE);
            Icons.draw(kind.icon, badgeX + 3, cy + 4 + (11 - kind.icon.length) / 2, kind.color);
            Theme.text(fontRendererObj, px, badgeX + 3 + Icons.width(kind.icon) + 3, cy + 6, Theme.TEXT);
            if (kind.night) {
                badgeX += w + 2;
                Theme.fill(badgeX, cy + 4, badgeX + 15, cy + 15, BADGE);
                Icons.draw(Icons.NIGHT, badgeX + 2, cy + 4, NIGHT_COLOR);
            }
        }
        int tagRight = cx + cardWidth - 4;
        if (picture.site) {
            String web = Lang.format("wayfarmap.pictures.web");
            int webWidth = fontRendererObj.getStringWidth(web) + 6;
            Theme.fill(tagRight - webWidth, cy + 4, tagRight, cy + 15, BADGE);
            Theme.text(fontRendererObj, web, tagRight - webWidth + 3, cy + 6, Theme.ACCENT);
            tagRight -= webWidth + 2;
        }
        if (fresh) {
            String text = Lang.format("wayfarmap.pictures.new_badge");
            int w = fontRendererObj.getStringWidth(text) + 6;
            double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0);
            Theme.fill(tagRight - w, cy + 4, tagRight, cy + 15, Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT, pulse));
            Theme.text(fontRendererObj, text, tagRight - w + 3, cy + 6, Theme.TEXT);
        }

        // Quick buttons, while the mouse is over it.
        if (lit > 0.05) {
            String[][] icons = { Icons.SMALL_COPY, Icons.SMALL_EYE, Icons.SMALL_TRASH };
            String[] keys = { "wayfarmap.pictures.copy", "wayfarmap.pictures.open", "wayfarmap.pictures.delete" };
            int quick = hovered ? quickAt(i, mouseX, mouseY) : -1;
            int y0 = cy + thumbHeight - QUICK - 3;
            for (int q = 0; q < 3; q++) {
                int x0 = quickX(cx, q);
                boolean armedHere = q == 2 && armed == ID_CARD_DELETE + i;
                int background = armedHere ? Theme.DANGER
                    : q == quick ? (q == 2 ? Theme.DANGER_STRONG : Theme.ACCENT_DIM) : BADGE;
                Theme.fill(x0, y0, x0 + QUICK, y0 + QUICK, Theme.blend(0, background, lit));
                if (q == quick || armedHere) {
                    Theme.outline(x0, y0, x0 + QUICK, y0 + QUICK, q == 2 ? Theme.DANGER : Theme.ACCENT);
                }
                String[] icon = icons[q];
                int iconColor = armedHere ? 0xFFFFFFFF : q == 2 ? Theme.DANGER : Theme.TEXT;
                Icons.draw(
                    icon,
                    x0 + (QUICK - Icons.width(icon)) / 2,
                    y0 + (QUICK - icon.length) / 2,
                    Theme.blend(iconColor & 0x00FFFFFF, iconColor, lit));
                if (q == quick) {
                    tooltip = Collections.singletonList(Lang.format(armedHere ? "wayfarmap.pictures.sure" : keys[q]));
                }
            }
            if (hovered && quick < 0 && mouseY < cy + thumbHeight) {
                tooltip = java.util.Arrays.asList(picture.name, "§7" + Lang.format("wayfarmap.pictures.card_hint"));
            }
        }

        int textX = cx + 5, textWidth = cardWidth - 10;
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, title(picture), textWidth),
            textX,
            cy + thumbHeight + 5,
            Theme.TEXT);
        String info = when(picture.modified) + " · " + bytes(picture.bytes);
        Theme.text(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, info, textWidth),
            textX,
            cy + thumbHeight + 15,
            Theme.TEXT_MUTED);
    }

    /** A date as "today 14:32", "yesterday 14:32" or "07.10.2026 14:32". */
    private static String when(long time) {
        Calendar then = Calendar.getInstance(), now = Calendar.getInstance();
        then.setTimeInMillis(time);
        String clock = new SimpleDateFormat("HH:mm").format(new Date(time));
        if (sameDay(then, now)) {
            return Lang.format("wayfarmap.pictures.today", clock);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, now)) {
            return Lang.format("wayfarmap.pictures.yesterday", clock);
        }
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(time));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** A rectangle going from one color at the top to another at the bottom. */
    private static void gradient(int x0, int y0, int x1, int y1, int topColor, int bottomColor) {
        int rows = y1 - y0;
        for (int y = 0; y < rows; y++) {
            Theme.fill(x0, y0 + y, x1, y0 + y + 1, Theme.blend(topColor, bottomColor, (y + 0.5) / rows));
        }
    }

    /**
     * The picture filling the box on a dark ground, cut to it so nothing is left empty, fading in once read; loading
     * or unreadable shows a note instead.
     */
    private void drawCover(Shown shown, int x0, int y0, int x1, int y1, double lit) {
        Theme.fill(x0, y0, x1, y1, PICTURE_BACKGROUND);
        if (!shown.ready()) {
            drawNote(shown, x0, y0, x1, y1);
            return;
        }
        double boxRatio = (x1 - x0) / (double) (y1 - y0), ratio = shown.width / (double) shown.height;
        double u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        if (ratio > boxRatio) {
            double keep = boxRatio / ratio;
            u0 = (1 - keep) / 2;
            u1 = u0 + keep;
        } else {
            double keep = ratio / boxRatio;
            v0 = (1 - keep) / 2;
            v1 = v0 + keep;
        }
        float alpha = (float) shown.appear.update(1, 10);
        float shade = (float) (0.88 + 0.12 * lit);
        drawTexture(shown, x0, y0, x1, y1, u0, v0, u1, v1, shade, alpha);
    }

    private void drawNote(Shown shown, int x0, int y0, int x1, int y1) {
        String note = Lang.format(shown.failed ? "wayfarmap.pictures.unreadable" : "wayfarmap.pictures.loading");
        if (!shown.failed) {
            // Three dots taking turns.
            long t = System.currentTimeMillis() / 250 % 3;
            int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2 + 8;
            for (int d = 0; d < 3; d++) {
                Theme.fill(cx - 7 + d * 5, cy, cx - 4 + d * 5, cy + 3, d == t ? Theme.ACCENT : Theme.BORDER);
            }
        }
        Theme.centered(
            fontRendererObj,
            Theme.ellipsize(fontRendererObj, note, x1 - x0 - 4),
            (x0 + x1) / 2,
            (y0 + y1) / 2 - 6,
            Theme.TEXT_DISABLED);
    }

    private static void drawTexture(Shown shown, double x0, double y0, double x1, double y1, double u0, double v0,
        double u1, double v1, float shade, float alpha) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(shade, shade, shade, alpha);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shown.texture.getGlTextureId());
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x0, y1, 0, u0, v1);
        tessellator.addVertexWithUV(x1, y1, 0, u1, v1);
        tessellator.addVertexWithUV(x1, y0, 0, u1, v0);
        tessellator.addVertexWithUV(x0, y0, 0, u0, v0);
        tessellator.draw();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** A picture opened big over the whole window, with its bar at the top and the strip of all of them below. */
    private void drawBig(int mouseX, int mouseY) {
        if (viewDragging) {
            if (!Mouse.isButtonDown(0)) {
                viewDragging = false;
            } else {
                viewPanX += mouseX - dragX;
                viewPanY += mouseY - dragY;
                dragX = mouseX;
                dragY = mouseY;
            }
        }
        double open = viewOpen.update(1, 14);
        MapPictures.Picture picture = pictures.get(viewing);
        Kind kind = kind(picture);
        int dim = Theme.blend(Theme.alpha(Theme.SCREEN_DIM, 0), Theme.alpha(Theme.SCREEN_DIM, 0xF4), open);
        Theme.fill(0, 0, width, height, dim);
        int areaTop = VIEW_BAR, areaBottom = viewAreaBottom();
        if (viewImage != null && viewImage.ready()) {
            double fit = Math
                .min((width - 60) / (double) viewImage.width, (areaBottom - areaTop - 12) / (double) viewImage.height);
            double grow = 0.94 + 0.06 * open;
            double w = viewImage.width * fit * viewZoom * grow, h = viewImage.height * fit * viewZoom * grow;
            // Kept so some of the picture is always in view.
            double roomX = Math.max(0, (w - (width - 60)) / 2) + 20;
            double roomY = Math.max(0, (h - (areaBottom - areaTop)) / 2) + 20;
            viewPanX = Math.max(-roomX, Math.min(roomX, viewPanX));
            viewPanY = Math.max(-roomY, Math.min(roomY, viewPanY));
            double cx = width / 2.0 + viewPanX, cy = (areaTop + areaBottom) / 2.0 + viewPanY;
            Theme.clip(0, areaTop, width, areaBottom);
            int ix0 = (int) Math.floor(cx - w / 2), iy0 = (int) Math.floor(cy - h / 2);
            int ix1 = (int) Math.ceil(cx + w / 2), iy1 = (int) Math.ceil(cy + h / 2);
            Theme.fill(ix0 + 3, iy0 + 4, ix1 + 3, iy1 + 4, 0x60000000);
            Theme.fill(ix0, iy0, ix1, iy1, PICTURE_BACKGROUND);
            float alpha = (float) (viewImage.appear.update(1, 10) * open);
            drawTexture(viewImage, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 0, 0, 1, 1, 1f, alpha);
            Theme.outline(ix0 - 1, iy0 - 1, ix1 + 1, iy1 + 1, Theme.blend(Theme.BORDER, kind.color, 0.35));
            Theme.unclip();
            // How close, bottom right of the picture's area.
            String zoom = viewZoom <= 1 ? Lang.format("wayfarmap.pictures.fit") : String.format("×%.1f", viewZoom);
            int zw = fontRendererObj.getStringWidth(zoom) + 8;
            Theme.fill(width - 30 - zw, areaBottom - 16, width - 30, areaBottom - 4, BADGE);
            Theme.text(fontRendererObj, zoom, width - 30 - zw + 4, areaBottom - 14, Theme.TEXT_MUTED);
        } else {
            drawNote(viewImage == null ? new Shown() : viewImage, 0, areaTop, width, areaBottom);
        }

        // The bar: which picture of how many, what it shows, its name, size in pixels and on disk.
        Theme.fill(0, 0, width, VIEW_BAR, Theme.PANEL_ALT);
        Theme.fill(0, VIEW_BAR - 1, width, VIEW_BAR, Theme.BORDER);
        String counter = (viewing + 1) + " / " + pictures.size();
        int counterWidth = fontRendererObj.getStringWidth(counter) + 10;
        Theme.fill(8, 8, 8 + counterWidth, 20, Theme.CONTROL);
        Theme.outline(8, 8, 8 + counterWidth, 20, Theme.BORDER);
        Theme.text(fontRendererObj, counter, 13, 10, Theme.TEXT_MUTED);
        int textX = 8 + counterWidth + 8;
        Icons.draw(kind.icon, textX, (VIEW_BAR - kind.icon.length) / 2, kind.color);
        textX += Icons.width(kind.icon) + 6;
        int[] size = dimensions(picture);
        String details = (size == null ? "" : size[0] + "×" + size[1] + " · ") + bytes(picture.bytes)
            + " · "
            + when(picture.modified)
            + (picture.site ? " · " + Lang.format("wayfarmap.pictures.web") : "");
        int textRoom = copyButton.xPosition - 8 - textX;
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, title(picture), textRoom), textX, 5, Theme.TEXT);
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, details, textRoom), textX, 16, Theme.TEXT_MUTED);

        drawStrip(mouseX, mouseY);
        if (System.currentTimeMillis() - toastAt > TOAST_MS) {
            String hint = Lang.format("wayfarmap.pictures.view_hint");
            hint = Theme.ellipsize(fontRendererObj, hint, width - 2 * (40 + 60));
            Theme.centered(fontRendererObj, hint, width / 2, areaBottom - 14, Theme.TEXT_DISABLED);
        }
    }

    /** The small pictures of all of them under the big one, the one shown in the middle; a click goes to one. */
    private void drawStrip(int mouseX, int mouseY) {
        int y0 = viewAreaBottom();
        Theme.fill(0, y0, width, height, Theme.PANEL_ALT);
        Theme.fill(0, y0, width, y0 + 1, Theme.BORDER);
        stripScroll.update(viewing, 12);
        int top = y0 + (STRIP - STRIP_H) / 2;
        int hovered = stripAt(mouseX, mouseY);
        for (int i = 0; i < pictures.size(); i++) {
            double x0 = stripX(i);
            if (x0 + STRIP_W < 0 || x0 > width) {
                continue;
            }
            int sx = (int) Math.round(x0);
            boolean current = i == viewing;
            MapPictures.Picture picture = pictures.get(i);
            drawCover(thumb(picture), sx, top, sx + STRIP_W, top + STRIP_H, current || i == hovered ? 1 : 0);
            if (!current) {
                // The others a bit darker.
                Theme.fill(sx, top, sx + STRIP_W, top + STRIP_H, i == hovered ? 0x20000000 : 0x60000000);
            }
            int border = current ? Theme.ACCENT : i == hovered ? kind(picture).color : Theme.BORDER;
            Theme.outline(sx - 1, top - 1, sx + STRIP_W + 1, top + STRIP_H + 1, border);
            if (current) {
                Theme.fill(sx, top + STRIP_H + 2, sx + STRIP_W, top + STRIP_H + 4, Theme.ACCENT);
            }
            if (i == hovered) {
                tooltip = Collections.singletonList(title(picture));
            }
        }
        // Fades at both ends.
        for (int x = 0; x < 24; x++) {
            int color = Theme.blend(Theme.alpha(Theme.PANEL_ALT, 0xF0), Theme.alpha(Theme.PANEL_ALT, 0), x / 24.0);
            Theme.fill(x, y0 + 1, x + 1, height, color);
            Theme.fill(width - 1 - x, y0 + 1, width - x, height, color);
        }
    }

    private void drawToast(int bottomY) {
        String text = toast;
        long age = System.currentTimeMillis() - toastAt;
        if (text == null || age > TOAST_MS) {
            return;
        }
        // Slides in, and out at the end.
        double in = Math.min(1, age / 150.0), out = Math.min(1, (TOAST_MS - age) / 250.0);
        int shift = (int) Math.round((1 - Math.min(in, out)) * 6);
        int textWidth = fontRendererObj.getStringWidth(text);
        int x0 = (width - textWidth) / 2 - 10, y0 = bottomY - 16 + shift;
        int x1 = x0 + textWidth + 20;
        if (!Theme.sprite(x0, y0, x1, bottomY + shift, "toast")) {
            Theme.fill(x0 + 2, y0 + 2, x1 + 2, bottomY + shift + 2, 0x60000000);
            Theme.fill(x0, y0, x1, bottomY + shift, Theme.alpha(Theme.PANEL, 0xF0));
            Theme.outline(x0, y0, x1, bottomY + shift, toastColor);
        }
        Theme.fill(x0, y0, x0 + 3, bottomY + shift, toastColor);
        Theme.text(fontRendererObj, text, x0 + 10, y0 + 4, toastColor);
    }

    /** Bytes as a short text: KB, MB or GB. */
    private static String bytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
