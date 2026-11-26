package WayFarMap.client.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.client.Lang;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.integration.Mods;
import WayFarMap.client.integration.ProspectingLayer;
import WayFarMap.client.integration.ThaumcraftNodes;
import WayFarMap.client.map.BlockColors;
import WayFarMap.client.map.FlatLog;
import WayFarMap.client.map.MapCleaner;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.map.export.MapPictures;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointManager;

/**
 * Map data: how much disk the maps take and cleaning them up, on one screen. At the top, cards of all worlds played
 * with a bar of what their files are; below, on the left, this world's dimensions, each with its size and a bar of
 * it beside the biggest one, and on the right the one picked (or all of them): a ring of its flat (2D) and 3D map,
 * a row for each with a button that deletes it, and what is kept whatever is deleted (waypoints and what was found
 * with the mods: GregTech ore veins, underground fluids, Thaumcraft nodes). At the bottom, the logs and the cache of
 * the 3D map's block pictures. Each delete
 * button asks again before it deletes, its time to do so running out under it. The sizes are counted again in the
 * background each time the screen is opened and after each cleaning; until then the last count is shown, dimmed.
 * Up and down pick the dimension, R counts again.
 */
public class GuiMapData extends ScaledScreen {

    private static final int ID_DONE = 0, ID_REFRESH = 2, ID_DEL_2D = 3, ID_DEL_3D = 4, ID_DEL_ALL = 5, ID_LOGS = 6,
        ID_FOLDER = 7, ID_CACHE = 8, ID_RELOAD = 9, ID_WIPE = 10;
    /** Height of a line of the dimension list, of a row of the details, of a card and of a kept count. */
    private static final int LIST_ROW = 20, ROW = 24, CARD = 30, CHIP = 22, BUTTON_WIDTH = 78;
    /** Where the body (the dimensions and the details) starts under the header, and the footer's height. */
    private static final int BODY_OFFSET = WindowHeader.HEIGHT + 64, FOOTER = 30;
    /** Outer and inner radius of the ring of the 2D and the 3D map. */
    private static final int RING = 34, HOLE = 24;
    /** How long a button waits for the second click that confirms it, and how long a message stays. */
    private static final long CONFIRM_MS = 4000, TOAST_MS = 3500;
    /** Colors of the 3D map and of the other files (the 2D map takes the accent). */
    private static final int ISO_COLOR = 0xFFA371F7, OTHER_COLOR = 0xFF5C6773;
    /** Part of the ring under the mouse. */
    private static final int PART_NONE = 0, PART_FLAT = 1, PART_ISO = 2;

    /** A world on the disk. */
    private static final String[] GLOBE = { "...#####...", ".##..#..##.", ".#..#.#..#.", "###########", "#..#...#..#",
        "#..#...#..#", "#..#...#..#", "###########", ".#..#.#..#.", ".##..#..##.", "...#####..." };
    /** Two arrows chasing each other: counted again. */
    private static final String[] REFRESH = { "...#####.#.", "..#.....##.", ".#.....###.", "#..........", "#..........",
        "#.........#", "..........#", "..........#", ".###.....#.", ".##.....#..", ".#.#####..." };
    /** A folder. */
    private static final String[] FOLDER = { "####.......", "#...#######", "#.........#", "###########", "#.........#",
        "#.........#", "#.........#", "#.........#", "###########" };

    /** One count of the map folders. */
    private static final class Data {

        final File worldDirectory;
        /** This world, per dimension: {2D, 3D}, as the cleaning would delete them. */
        final Map<Integer, long[]> dimensions = new HashMap<>();
        /** Pictures of blocks for the 3D map, shared by the dimensions of this world. */
        long sprites;
        /** All worlds: how many, and their {2D, 3D, other} sizes. */
        int worlds;
        final long[] all = new long[3];
        /** The 2D and 3D maps' logs, {@code wayfarmap/logs}. */
        long logs;
        /** What the 3D map learned of block pictures, kept from game to game, {@code wayfarmap/cache}. */
        long cache;
        long countedAt;

        Data(File worldDirectory) {
            this.worldDirectory = worldDirectory;
        }

        /** {2D, 3D} of a dimension, or of all of them (null; the pictures of blocks count with the 3D map). */
        long[] sizes(Integer dimension) {
            if (dimension != null) {
                long[] row = dimensions.get(dimension);
                return row == null ? new long[2] : row;
            }
            long[] sum = { 0, sprites };
            for (long[] row : dimensions.values()) {
                sum[0] += row[0];
                sum[1] += row[1];
            }
            return sum;
        }
    }

    /**
     * A delete button: red, and while it waits for its second click filled red with the time left running out
     * along its bottom.
     */
    private static final class DeleteButton extends FlatButton {

        long armedAt;

        DeleteButton(int id, int x, int y, int width) {
            super(id, x, y, width, 16, "");
            danger = true;
            icon = Icons.SMALL_TRASH;
        }

        @Override
        protected void drawBackground(boolean hovered) {
            if (!active || !enabled) {
                super.drawBackground(hovered);
                return;
            }
            int x1 = xPosition + width, y1 = yPosition + height;
            Theme.fill(xPosition, yPosition, x1, y1, hovered ? Theme.DANGER_DIM_HOVER : Theme.DANGER_DIM);
            Theme.outline(xPosition, yPosition, x1, y1, Theme.DANGER);
            double left = 1 - Math.min(1, (System.currentTimeMillis() - armedAt) / (double) CONFIRM_MS);
            Theme.fill(
                xPosition + 1,
                y1 - 2,
                xPosition + 1 + (int) Math.round((width - 2) * left),
                y1 - 1,
                Theme.DANGER);
        }
    }

    /** The last count, kept while the game runs so it shows at once the next time. */
    private static volatile Data last;
    /** The count running now, if any. */
    private static volatile Thread counting;
    /** Dimension picked, null for all of them; kept between openings of the same world's data. */
    private static Integer picked;
    /** The world folder {@link #picked} is of: in another world it means nothing. */
    private static File pickedWorld;

    private final GuiScreen parent;
    private final File logsDirectory, cacheDirectory;
    /**
     * This world's folder, kept: a cleaning closes the maps, and until the next tick opens them again the map
     * manager has none (the count right after the cleaning still needs it).
     */
    private File worldDirectory;
    private int left, top, right, bottom;
    /** Counted once per opening (not again when the window is resized). */
    private boolean started;
    /** Found with the mods, per dimension; null without the mod. */
    private Map<Integer, int[]> prospected;
    private Map<Integer, Integer> nodes;
    private final Map<Integer, Integer> waypoints = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private IconButton refreshButton, folderButton, reloadButton, wipeButton;
    private final List<DeleteButton> deleteButtons = new ArrayList<>();
    /** How far the dimension list is scrolled (GUI pixels): where it should be, and where it is drawn. */
    private int listScroll;
    private final Smooth listScrollShown = new Smooth(0);
    /** Where the highlight of the picked dimension is drawn, sliding to it. */
    private final Smooth pickedShown = new Smooth(-1);
    /** Share of the 2D map in the ring, and how much of the ring is drawn (it sweeps in once there are numbers). */
    private final Smooth flatShare = new Smooth(0.5), reveal = new Smooth(0);
    /** Button waiting for its second click, and since when. */
    private int armed = -1;
    private long armedAt;
    /** What the last cleaning did, and when. */
    private String toast;
    private long toastAt;
    /** Where the bar of the dimensions' shares was drawn, for the mouse; -1 when it wasn't. */
    private int shareX0, shareX1, shareBarY = -1;
    /** What to show by the mouse this frame, null for nothing. */
    private List<String> tooltip;

    public GuiMapData(GuiScreen parent) {
        this.parent = parent;
        File root = new File(net.minecraft.client.Minecraft.getMinecraft().mcDataDir, "wayfarmap");
        this.logsDirectory = new File(root, "logs");
        this.cacheDirectory = new File(root, "cache");
    }

    /** This world's folder: the map manager's, or the one kept while the maps are closed. */
    private File worldDirectory() {
        File current = MapManager.INSTANCE.getWorldDirectory();
        if (current != null) {
            worldDirectory = current;
        }
        return worldDirectory;
    }

    // ---------------------------------------------------------------- layout

    private int cardsY() {
        return top + WindowHeader.HEIGHT + 20;
    }

    private int bodyTop() {
        return top + BODY_OFFSET;
    }

    private int bodyBottom() {
        return bottom - FOOTER - 8;
    }

    /** The dimension list on the left: its frame. */
    private int sideLeft() {
        return left + 10;
    }

    private int sideRight() {
        return sideLeft() + Math.max(130, Math.min(190, (right - left - 20) * 9 / 25));
    }

    private int listTop() {
        return bodyTop() + 13;
    }

    private int listBottom() {
        return bodyBottom();
    }

    /** The details on the right: their frame. */
    private int detailLeft() {
        return sideRight() + 10;
    }

    private int detailTop() {
        return bodyTop() + 13;
    }

    private int ringCenterX() {
        return detailLeft() + 14 + RING;
    }

    private int ringCenterY() {
        return detailTop() + 12 + RING;
    }

    /** Top of a row of the details: 2D, 3D, whole map. */
    private int rowY(int row) {
        return detailTop() + 10 + row * ROW;
    }

    private int keptY() {
        return detailTop() + 20 + 2 * RING;
    }

    @Override
    public void initGui() {
        int panelWidth = Math.min(width - 16, 560);
        int panelHeight = Math.min(height - 16, 356);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        right = left + panelWidth;
        bottom = top + panelHeight;
        buttonList.clear();
        deleteButtons.clear();
        // In the header, left of the close button.
        refreshButton = new IconButton(
            ID_REFRESH,
            right - WindowHeader.CLOSE_ROOM - 20,
            top + 7,
            REFRESH,
            Lang.format("wayfarmap.data.refresh") + " (R)");
        folderButton = new IconButton(
            ID_FOLDER,
            refreshButton.xPosition - 24,
            top + 7,
            FOLDER,
            Lang.format("wayfarmap.data.folder"));
        // Reading the map again from disk, and deleting all of this world's map at once.
        reloadButton = new IconButton(
            ID_RELOAD,
            folderButton.xPosition - 24,
            top + 7,
            Icons.MAP2D,
            Lang.format("wayfarmap.data.reload"));
        wipeButton = new IconButton(
            ID_WIPE,
            reloadButton.xPosition - 24,
            top + 7,
            Icons.SMALL_TRASH,
            Lang.format("wayfarmap.data.wipe"));
        wipeButton.danger = true;
        buttonList.add(wipeButton);
        buttonList.add(reloadButton);
        buttonList.add(folderButton);
        buttonList.add(refreshButton);
        buttonList.add(WindowHeader.closeButton(ID_DONE, right, top));
        int x = right - 18 - BUTTON_WIDTH;
        for (int row = 0; row < 3; row++) {
            DeleteButton button = new DeleteButton(ID_DEL_2D + row, x, rowY(row) + (ROW - 16) / 2 - 1, BUTTON_WIDTH);
            deleteButtons.add(button);
            buttonList.add(button);
        }
        // In the footer, after the logs' size.
        DeleteButton logs = new DeleteButton(ID_LOGS, 0, bottom - FOOTER + 7, BUTTON_WIDTH);
        deleteButtons.add(logs);
        buttonList.add(logs);
        // After it, the cache of the 3D map's pictures.
        DeleteButton cache = new DeleteButton(ID_CACHE, 0, bottom - FOOTER + 7, BUTTON_WIDTH);
        deleteButtons.add(cache);
        buttonList.add(cache);
        if (!started) {
            started = true;
            if (counting == null) {
                startCount();
            }
            countFound();
            // The picked dimension in view.
            Integer remembered = picked;
            List<Integer> choices = choices();
            int index = Math.max(0, choices.indexOf(remembered));
            listScroll = Math.max(0, Math.min(maxScroll(), index * LIST_ROW - (listBottom() - listTop()) / 2));
            listScrollShown.set(listScroll);
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
    }

    // ---------------------------------------------------------------- counting

    /**
     * What this world has besides its map files: waypoints, and what was found with VisualProspecting (GregTech ore
     * veins, underground fluids) and TCNodeTracker (nodes). Render thread: those mods keep them there.
     */
    private void countFound() {
        waypoints.clear();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            waypoints.merge(waypoint.dimension, 1, Integer::sum);
        }
        if (Mods.isVisualProspectingLoaded()) {
            try {
                prospected = ProspectingLayer.countFound();
            } catch (Throwable t) {
                prospected = null;
            }
        }
        if (Mods.isThaumcraftNodesAvailable()) {
            try {
                nodes = ThaumcraftNodes.countFound();
            } catch (Throwable t) {
                nodes = null;
            }
        }
    }

    /**
     * Counts the sizes in the background: walking big maps' folders takes a while. A count started later (after a
     * cleaning) wins over one still running.
     */
    private void startCount() {
        File worldDirectory = worldDirectory();
        if (worldDirectory == null) {
            return;
        }
        File root = new File(mc.mcDataDir, "wayfarmap");
        Thread thread = new Thread(() -> {
            Data data = count(root, worldDirectory);
            if (counting == Thread.currentThread()) {
                last = data;
                counting = null;
            }
        }, "Wayfarer's Map data");
        thread.setDaemon(true);
        counting = thread;
        thread.start();
    }

    private static Data count(File root, File worldDirectory) {
        Data data = new Data(worldDirectory);
        for (File dimension : MapCleaner.dimensions(worldDirectory)) {
            try {
                int id = Integer.parseInt(
                    dimension.getName()
                        .substring(3));
                data.dimensions.put(id, new long[] { MapCleaner.size2d(dimension), MapCleaner.size3d(dimension) });
            } catch (NumberFormatException e) {
                // Not a dimension folder.
            }
        }
        data.sprites = new File(worldDirectory, "iso-sprites.dat").length();
        // Every world played: wayfarmap/singleplayer/<world> and wayfarmap/multiplayer/<server>.
        for (String kind : new String[] { "singleplayer", "multiplayer" }) {
            File[] worlds = new File(root, kind).listFiles(File::isDirectory);
            if (worlds == null) {
                continue;
            }
            for (File world : worlds) {
                long before = data.all[0] + data.all[1] + data.all[2];
                addWorld(world, data.all);
                // An empty folder (a world joined and left at once, or cleared) is no world with a map.
                if (data.all[0] + data.all[1] + data.all[2] > before) {
                    data.worlds++;
                }
            }
        }
        data.logs = MapCleaner.size(new File(root, "logs"));
        data.cache = MapCleaner.size(new File(root, "cache"));
        data.countedAt = System.currentTimeMillis();
        return data;
    }

    /** Dimension of a {@code dim<id>} folder, {@link Integer#MIN_VALUE} for anything else. */
    private static int dimensionOf(File file) {
        String name = file.getName();
        if (!file.isDirectory() || !name.startsWith("dim")) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(3));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Adds a world's (or one account's) map files to {2D, 3D, other}. */
    private static void addWorld(File directory, long[] sizes) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (dimensionOf(file) != Integer.MIN_VALUE) {
                sizes[0] += MapCleaner.size2d(file);
                sizes[1] += MapCleaner.size3d(file);
                sizes[2] += MapCleaner.size(file) - MapCleaner.size2d(file) - MapCleaner.size3d(file);
            } else if (file.isDirectory() && file.getName()
                .startsWith("player-")) {
                    // Each account's map of the world.
                    addWorld(file, sizes);
                } else if (file.getName()
                    .equals("iso-sprites.dat")) {
                        sizes[1] += file.length();
                    } else {
                        sizes[2] += MapCleaner.size(file);
                    }
        }
    }

    /** The last count if it is of this world. */
    private Data visibleData() {
        Data data = last;
        File worldDirectory = worldDirectory();
        return data != null && worldDirectory != null && data.worldDirectory.equals(worldDirectory) ? data : null;
    }

    // ---------------------------------------------------------------- dimensions

    /** Dimensions of this world that have anything: map files, waypoints or finds; the player's always. */
    private List<Integer> dimensions(Data data) {
        TreeSet<Integer> ids = new TreeSet<>(waypoints.keySet());
        if (data != null) {
            for (Map.Entry<Integer, long[]> entry : data.dimensions.entrySet()) {
                if (entry.getValue()[0] + entry.getValue()[1] > 0) {
                    ids.add(entry.getKey());
                }
            }
        }
        if (prospected != null) {
            ids.addAll(prospected.keySet());
        }
        if (nodes != null) {
            ids.addAll(nodes.keySet());
        }
        Integer here = playerDimension();
        if (here != null) {
            ids.add(here);
        }
        // The picked one stays, also once its map was deleted: the screen must not jump to all dimensions (where the
        // next delete would take every dimension's map).
        if (picked != null) {
            ids.add(picked);
        }
        return new ArrayList<>(ids);
    }

    private Integer playerDimension() {
        return mc.theWorld == null ? null : mc.theWorld.provider.dimensionId;
    }

    private String dimensionName(int id) {
        // Once per dimension: it may read the dimension's info file.
        return names.computeIfAbsent(id, k -> MapManager.INSTANCE.getDimensionName(k));
    }

    private String scopeName() {
        return picked == null ? Lang.format("wayfarmap.stats.all_dims") : dimensionName(picked);
    }

    /** What the list offers: all dimensions (null), then each one. */
    private List<Integer> choices() {
        List<Integer> choices = new ArrayList<>();
        choices.add(null);
        choices.addAll(dimensions(visibleData()));
        return choices;
    }

    private int maxScroll() {
        return Math.max(0, choices().size() * LIST_ROW - (listBottom() - listTop() - 2));
    }

    /** Index into {@link #choices()} of the list line under the mouse, -1 if none. */
    private int listIndexAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, sideLeft() + 1, listTop() + 1, sideRight() - 1, listBottom() - 1)) {
            return -1;
        }
        int index = (mouseY - listTop() - 1 + (int) Math.round(listScrollShown.get())) / LIST_ROW;
        return index < choices().size() ? index : -1;
    }

    /** Picks the choice at the index and scrolls the list so it is in view. */
    private void pick(int index) {
        List<Integer> choices = choices();
        index = Math.max(0, Math.min(choices.size() - 1, index));
        Integer choice = choices.get(index);
        if (choice == null ? picked != null : !choice.equals(picked)) {
            picked = choice;
            pickedWorld = worldDirectory();
            armed = -1;
        }
        int rowTop = index * LIST_ROW, room = listBottom() - listTop() - 2;
        if (rowTop < listScroll) {
            listScroll = rowTop;
        } else if (rowTop + LIST_ROW > listScroll + room) {
            listScroll = rowTop + LIST_ROW - room;
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_DONE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == ID_REFRESH) {
            refresh();
            return;
        }
        if (button.id == ID_FOLDER) {
            File directory = worldDirectory();
            if (directory != null) {
                File dimension = picked == null ? null : new File(directory, "dim" + picked);
                MapPictures.open(dimension != null && dimension.isDirectory() ? dimension : directory);
            }
            return;
        }
        if (button.id == ID_RELOAD) {
            reload();
            return;
        }
        long now = System.currentTimeMillis();
        if (armed != button.id || now - armedAt > CONFIRM_MS) {
            // First click: asks again.
            armed = button.id;
            armedAt = now;
            if (button instanceof DeleteButton) {
                ((DeleteButton) button).armedAt = now;
            }
            return;
        }
        armed = -1;
        clean(button.id);
    }

    private void refresh() {
        if (counting != null) {
            return;
        }
        armed = -1;
        startCount();
        countFound();
    }

    /**
     * Forces the map to be built again: saved, closed and read again from disk (textures, reduced copies and block
     * colors made anew), and the chunks loaded around the player mapped again as when the world is joined.
     */
    private void reload() {
        if (worldDirectory() == null) {
            return;
        }
        armed = -1;
        MapManager.INSTANCE.resetMaps("nothing: map reloaded (Map data screen)", BlockColors::clearCache);
        toast = Lang.format("wayfarmap.data.reloaded");
        toastAt = System.currentTimeMillis();
    }

    private void clean(int id) {
        File worldDirectory = worldDirectory();
        if (worldDirectory == null) {
            return;
        }
        // Clearing everything: every dimension of this world, whichever is picked.
        File dimension = picked == null || id == ID_WIPE ? null : new File(worldDirectory, "dim" + picked);
        long[] freed = new long[1];
        String what = id == ID_LOGS ? "logs folder"
            : id == ID_CACHE ? "3D picture cache folder"
                : (id == ID_DEL_2D ? "2D map" : id == ID_DEL_3D ? "3D map" : "2D and 3D map")
                    + (dimension == null ? " of every dimension" : " of " + dimension.getName())
                    + " (Map data screen)";
        MapManager.INSTANCE.resetMaps(what, () -> {
            if (id == ID_LOGS) {
                freed[0] = MapCleaner.clearLogs(logsDirectory);
                return;
            }
            if (id == ID_CACHE) {
                // The maps are closed (their cache saved): deleted after, and learned again from nothing.
                freed[0] = MapCleaner.clearLogs(cacheDirectory);
                return;
            }
            boolean flat = id == ID_DEL_2D || id == ID_DEL_ALL || id == ID_WIPE,
                iso = id == ID_DEL_3D || id == ID_DEL_ALL || id == ID_WIPE;
            if (dimension == null) {
                freed[0] = (flat ? MapCleaner.deleteAll(worldDirectory, false) : 0)
                    + (iso ? MapCleaner.deleteAll(worldDirectory, true) : 0);
            } else {
                freed[0] = (flat ? MapCleaner.delete2d(dimension) : 0) + (iso ? MapCleaner.delete3d(dimension) : 0);
            }
        });
        FlatLog.note("CLEANED " + what + " freedKB=" + (freed[0] >> 10));
        toast = Lang.format("wayfarmap.clean.done", bytes(freed[0]));
        toastAt = System.currentTimeMillis();
        startCount();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (armed != -1) {
                armed = -1;
            } else {
                mc.displayGuiScreen(parent);
            }
        } else if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            pick(choices().indexOf(picked) + (keyCode == Keyboard.KEY_UP ? -1 : 1));
        } else if (keyCode == Keyboard.KEY_HOME || keyCode == Keyboard.KEY_END) {
            pick(keyCode == Keyboard.KEY_HOME ? 0 : choices().size() - 1);
        } else if (keyCode == Keyboard.KEY_R || keyCode == Keyboard.KEY_F5) {
            refresh();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        Integer share = shareAt(mouseX, mouseY);
        if (share != null && button == 0) {
            pick(choices().indexOf(share));
            return;
        }
        int index = listIndexAt(mouseX, mouseY);
        if (index >= 0 && button == 0) {
            pick(index);
            mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            listScroll = Math.max(0, Math.min(maxScroll(), listScroll + (wheel > 0 ? -LIST_ROW : LIST_ROW)));
        }
    }

    // ---------------------------------------------------------------- formatting

    /** Sum of the values of the picked dimension, or of all of them. */
    private static long sum(Map<Integer, ? extends Number> byDimension, Integer dimension) {
        if (dimension != null) {
            Number value = byDimension.get(dimension);
            return value == null ? 0 : value.longValue();
        }
        long total = 0;
        for (Number value : byDimension.values()) {
            total += value.longValue();
        }
        return total;
    }

    /** 1234 -> 1,234. */
    private static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** 0 B, 512 B, 1.5 KB, 12.3 MB, 1.2 GB. */
    private static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    /** Rounded, but never 0% for a part that has something nor 100% for one that isn't all of it. */
    private static String percent(long part, long whole) {
        if (whole <= 0) {
            return "";
        }
        if (part > 0 && part * 100 < whole) {
            return "<1%";
        }
        if (part < whole && part * 100 > whole * 99) {
            return ">99%";
        }
        return Math.round(part * 100.0 / whole) + "%";
    }

    // ---------------------------------------------------------------- drawing

    /** A muted section title, with an icon before it (null for none). */
    private void sectionTitle(String[] icon, int iconColor, String title, int x, int y) {
        if (icon != null) {
            Icons.draw(icon, x, y + (8 - icon.length) / 2, iconColor);
            x += Icons.width(icon) + 4;
        }
        Theme.text(fontRendererObj, title, x, y, Theme.TEXT_MUTED);
    }

    /** A pulsing bar where a number will be, while there is none yet. */
    private void placeholder(int x, int y, int width) {
        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 220.0);
        Theme.fill(x, y, x + width, y + 7, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, pulse));
    }

    /** A card: an icon in its color on a tinted square, a muted label and a value under it. */
    private void card(int x0, int y0, int x1, String[] icon, int color, String label, String value, int valueColor,
        boolean hovered) {
        Theme.fill(x0, y0, x1, y0 + CARD, hovered ? Theme.CONTROL_HOVER : Theme.PANEL_ALT);
        Theme.outline(x0, y0, x1, y0 + CARD, hovered ? Theme.blend(Theme.BORDER, color, 0.6) : Theme.BORDER);
        // The card's color along its left edge.
        Theme.fill(x0 + 1, y0 + 1, x0 + 3, y0 + CARD - 1, color);
        int box = 20, bx = x0 + 8, by = y0 + (CARD - box) / 2;
        Theme.fill(bx, by, bx + box, by + box, (color & 0x00FFFFFF) | 0x30000000);
        Icons.draw(icon, bx + (box - Icons.width(icon)) / 2, by + (box - icon.length) / 2, color);
        int textX = bx + box + 7, room = x1 - 6 - textX;
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, label, room), textX, y0 + 5, Theme.TEXT_MUTED);
        if (value == null) {
            placeholder(textX, y0 + 17, Math.min(room, 40));
        } else {
            fontRendererObj
                .drawStringWithShadow(Theme.ellipsize(fontRendererObj, value, room), textX, y0 + 17, valueColor);
        }
    }

    /** A bar split in parts of the colors, in proportion to the sizes; {@code reveal} of it drawn, from the left. */
    private static void stackedBar(int x0, int y0, int x1, int y1, long[] sizes, int[] colors, double reveal) {
        Theme.fill(x0, y0, x1, y1, Theme.CONTROL);
        long total = 0;
        for (long size : sizes) {
            total += size;
        }
        if (total <= 0) {
            return;
        }
        double width = (x1 - x0) * reveal, start = 0;
        for (int i = 0; i < sizes.length; i++) {
            double end = start + width * sizes[i] / total;
            int from = x0 + (int) Math.round(start), to = x0 + (int) Math.round(end);
            if (sizes[i] > 0) {
                Theme.fill(from, y0, Math.max(from + 1, to), y1, colors[i]);
            }
            start = end;
        }
    }

    /**
     * The ring of the 2D (accent, clockwise from the top) and the 3D map, drawn row by row with runs of one color
     * merged; the part under the mouse stands out a little.
     */
    private void ring(int cx, int cy, double flat, double shown, boolean empty, int hovered) {
        for (int y = -RING; y < RING; y++) {
            double dy = y + 0.5;
            int runStart = 0, runColor = 0;
            for (int x = -RING; x <= RING; x++) {
                int color = 0;
                if (x < RING) {
                    double dx = x + 0.5, distance = Math.sqrt(dx * dx + dy * dy);
                    if (distance <= RING && distance >= HOLE) {
                        boolean outerEdge = distance > RING - 1;
                        if (empty) {
                            color = Theme.CONTROL;
                        } else {
                            double angle = angle(dx, dy);
                            int part = angle < shown * flat ? PART_FLAT : angle < shown ? PART_ISO : PART_NONE;
                            color = part == PART_FLAT ? Theme.ACCENT : part == PART_ISO ? ISO_COLOR : Theme.CONTROL;
                            if (part != PART_NONE && hovered != PART_NONE && part != hovered) {
                                color = Theme.blend(color, Theme.PANEL_ALT | 0xFF000000, 0.45);
                            } else if (part != PART_NONE && part == hovered && outerEdge) {
                                color = Theme.blend(color, 0xFFFFFFFF, 0.4);
                            }
                        }
                    }
                }
                if (color != runColor) {
                    if (runColor != 0) {
                        Theme.fill(cx + runStart, cy + y, cx + x, cy + y + 1, runColor);
                    }
                    runStart = x;
                    runColor = color;
                }
            }
        }
    }

    /** Angle from the top, clockwise, from 0 to 1 a turn. */
    private static double angle(double dx, double dy) {
        double angle = Math.atan2(dx, -dy) / (2 * Math.PI);
        return angle < 0 ? angle + 1 : angle;
    }

    /** Which part of the ring is under the mouse. */
    private int ringPartAt(int mouseX, int mouseY, double flat, boolean empty) {
        double dx = mouseX + 0.5 - ringCenterX(), dy = mouseY + 0.5 - ringCenterY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (empty || distance > RING || distance < HOLE) {
            return PART_NONE;
        }
        return angle(dx, dy) < flat ? PART_FLAT : PART_ISO;
    }

    private String rowLabel(int id) {
        switch (id) {
            case ID_DEL_2D:
                return Lang.format("wayfarmap.stats.flat");
            case ID_DEL_3D:
                return Lang.format("wayfarmap.stats.iso");
            case ID_DEL_ALL:
                return Lang.format("wayfarmap.data.whole");
            case ID_CACHE:
                return Lang.format("wayfarmap.data.cache");
            case ID_WIPE:
                return Lang.format("wayfarmap.data.wipe_what");
            default:
                return Lang.format("wayfarmap.data.logs");
        }
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        shareBarY = -1;
        if (armed != -1 && System.currentTimeMillis() - armedAt > CONFIRM_MS) {
            armed = -1;
        }
        Data data = visibleData();
        boolean updating = counting != null;
        File world = worldDirectory();
        if (picked != null && (world == null || !world.equals(pickedWorld))) {
            picked = null;
        }
        long[] scope = data == null ? null : data.sizes(picked);
        // Old numbers are dimmed while they are counted again.
        int value = updating ? Theme.TEXT_DISABLED : Theme.TEXT;
        updateButtons(data, scope, updating);

        Theme.dimScreen(0, 0, width, height);
        // A soft shadow under the window.
        Theme.fill(left + 3, top + 3, right + 3, bottom + 3, 0x50000000);
        Theme.panel(left, top, right, bottom);
        drawHeader(data, updating);
        drawAllWorlds(data, value, mouseX, mouseY);
        drawDimensions(data, mouseX, mouseY);
        drawDetails(data, scope, value, mouseX, mouseY);
        drawFooter(data, value);
        super.drawScaled(mouseX, mouseY, partialTicks);
        drawToast();
        for (Object o : buttonList) {
            if (o instanceof IconButton && ((IconButton) o).isMouseOver(mouseX, mouseY)) {
                String text = ((IconButton) o).tooltip;
                if (text != null && !text.isEmpty()) {
                    tooltip = Arrays.asList(text);
                }
            }
        }
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
            GL11.glDisable(GL11.GL_LIGHTING);
        }
    }

    private void updateButtons(Data data, long[] scope, boolean updating) {
        boolean canDelete = worldDirectory() != null && data != null;
        refreshButton.enabled = !updating;
        folderButton.enabled = worldDirectory() != null;
        reloadButton.enabled = worldDirectory() != null;
        long[] world = data == null ? null : data.sizes(null);
        wipeButton.enabled = armed == ID_WIPE || canDelete && world[0] + world[1] > 0;
        // Waiting for its second click: lit up red.
        wipeButton.active = armed == ID_WIPE;
        for (DeleteButton b : deleteButtons) {
            long size = !canDelete ? 0
                : b.id == ID_LOGS ? data.logs
                    : b.id == ID_CACHE ? data.cache
                        : b.id == ID_DEL_2D ? scope[0] : b.id == ID_DEL_3D ? scope[1] : scope[0] + scope[1];
            b.displayString = Lang.format(
                armed == b.id ? "wayfarmap.clean.confirm"
                    : b.id == ID_LOGS || b.id == ID_CACHE ? "wayfarmap.clean.clear" : "wayfarmap.clean.delete");
            b.active = armed == b.id;
            // Nothing to delete: the button stays off (unless it waits for its second click).
            b.enabled = armed == b.id || size > 0;
            b.setWidth(
                Math.max(BUTTON_WIDTH, fontRendererObj.getStringWidth(b.displayString) + 6 + Icons.width(b.icon) + 15));
            if (b.id != ID_LOGS && b.id != ID_CACHE) {
                b.xPosition = right - 18 - b.getWidth();
            }
        }
    }

    private void drawHeader(Data data, boolean updating) {
        // Under the title: counting now, or when the numbers were counted.
        String counted;
        int countedColor;
        if (updating) {
            int dots = (int) (System.currentTimeMillis() / 400 % 4);
            counted = Lang.format("wayfarmap.stats.updating") + "...".substring(0, dots);
            countedColor = Theme.ACCENT;
        } else if (data != null) {
            counted = Lang.format(
                "wayfarmap.stats.updated",
                new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(data.countedAt)));
            countedColor = Theme.TEXT_MUTED;
        } else {
            counted = Lang.format("wayfarmap.stats.none");
            countedColor = Theme.TEXT_MUTED;
        }
        // In the pill: what this world takes, all its dimensions together.
        String pill = null;
        if (data != null) {
            long[] world = data.sizes(null);
            pill = Lang.format("wayfarmap.stats.this_world") + ": " + bytes(world[0] + world[1]);
        }
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            right,
            wipeButton.xPosition,
            Icons.STATS,
            Lang.format("wayfarmap.data.title"),
            counted,
            countedColor,
            pill);
        if (updating) {
            // A light running along the line under the header while it counts.
            int span = (right - left) / 4, travel = right - left - 2 + span;
            int x = left + 1 - span + (int) (System.currentTimeMillis() % 1400 * travel / 1400);
            for (int i = 0; i < span; i += 2) {
                double t = 1 - Math.abs(i - span / 2.0) / (span / 2.0);
                int from = Math.max(left + 1, x + i), to = Math.min(right - 1, x + i + 2);
                if (to > from) {
                    int y = top + WindowHeader.HEIGHT - 1;
                    Theme.fill(from, y, to, y + 1, Theme.blend(Theme.BORDER, Theme.ACCENT_SOFT, t));
                }
            }
        }
    }

    private void drawAllWorlds(Data data, int value, int mouseX, int mouseY) {
        int y = top + WindowHeader.HEIGHT + 8;
        sectionTitle(null, 0, Lang.format("wayfarmap.stats.all_worlds"), left + 10, y);
        String[][] icons = { GLOBE, Icons.MAP2D, Icons.ISO, Icons.STATS };
        int[] colors = { 0xFF3FB9A6, Theme.ACCENT, ISO_COLOR, 0xFFF2C14E };
        String[] labels = { Lang.format("wayfarmap.stats.worlds"), Lang.format("wayfarmap.stats.flat"),
            Lang.format("wayfarmap.stats.iso"), Lang.format("wayfarmap.stats.total") };
        String[] values = data == null ? new String[4]
            : new String[] { number(data.worlds), bytes(data.all[0]), bytes(data.all[1]),
                bytes(data.all[0] + data.all[1] + data.all[2]) };
        int count = 4, gap = 6;
        int cardWidth = (right - left - 20 - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int x0 = left + 10 + i * (cardWidth + gap), x1 = x0 + cardWidth;
            boolean hovered = Theme.inside(mouseX, mouseY, x0, cardsY(), x1, cardsY() + CARD);
            card(x0, cardsY(), x1, icons[i], colors[i], labels[i], values[i], value, hovered);
            if (hovered && data != null && i > 0) {
                long all = data.all[0] + data.all[1] + data.all[2];
                tooltip = Arrays.asList(
                    labels[i] + ": " + values[i],
                    "§7" + Lang
                        .format("wayfarmap.stats.flat") + ": " + bytes(data.all[0]) + "  " + percent(data.all[0], all),
                    "§7" + Lang
                        .format("wayfarmap.stats.iso") + ": " + bytes(data.all[1]) + "  " + percent(data.all[1], all),
                    "§7" + Lang
                        .format("wayfarmap.data.other") + ": " + bytes(data.all[2]) + "  " + percent(data.all[2], all));
            }
        }
        // What all worlds' files are: 2D, 3D, other.
        int barY = cardsY() + CARD + 5;
        double shown = reveal.update(data == null ? 0 : 1, 6);
        stackedBar(
            left + 10,
            barY,
            right - 10,
            barY + 3,
            data == null ? new long[3] : data.all,
            new int[] { Theme.ACCENT, ISO_COLOR, OTHER_COLOR },
            shown);
    }

    private void drawDimensions(Data data, int mouseX, int mouseY) {
        int x0 = sideLeft(), x1 = sideRight(), y0 = listTop(), y1 = listBottom();
        List<Integer> choices = choices();
        sectionTitle(
            Icons.ADDONS,
            Theme.TEXT_MUTED,
            Lang.format("wayfarmap.data.dimensions") + "  §8" + (choices.size() - 1),
            x0,
            bodyTop());
        Theme.fill(x0, y0, x1, y1, Theme.PANEL_ALT);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);
        // The biggest dimension fills its bar; the others are beside it.
        long biggest = 1;
        if (data != null) {
            for (long[] sizes : data.dimensions.values()) {
                biggest = Math.max(biggest, sizes[0] + sizes[1]);
            }
        }
        listScroll = Math.max(0, Math.min(maxScroll(), listScroll));
        double scroll = listScrollShown.update(listScroll, 18);
        int hovered = listIndexAt(mouseX, mouseY);
        int pickedIndex = Math.max(0, choices.indexOf(picked));
        if (pickedShown.get() < 0) {
            pickedShown.set(pickedIndex);
        }
        double highlight = pickedShown.update(pickedIndex, 20);
        Integer here = playerDimension();
        boolean scrolls = maxScroll() > 0;
        int rowRight = x1 - (scrolls ? 6 : 1);
        Theme.clip(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        // The picked line's highlight, sliding to it.
        int hy = y0 + 1 + (int) Math.round(highlight * LIST_ROW - scroll);
        Theme.fill(x0 + 1, hy, rowRight, hy + LIST_ROW, Theme.CONTROL_HOVER);
        Theme.fill(x0 + 1, hy, x0 + 3, hy + LIST_ROW, Theme.ACCENT);
        for (int index = 0; index < choices.size(); index++) {
            int y = y0 + 1 + index * LIST_ROW - (int) Math.round(scroll);
            if (y + LIST_ROW < y0 || y > y1) {
                continue;
            }
            Integer dimension = choices.get(index);
            boolean current = index == pickedIndex;
            if (index == hovered && !current) {
                Theme.fill(x0 + 1, y, rowRight, y + LIST_ROW, Theme.ROW_HOVER);
            }
            int textX = x0 + 8;
            if (dimension != null && dimension.equals(here)) {
                // Where the player is.
                Icons.draw(Icons.SMALL_PIN, textX, y + 3, Theme.SUCCESS);
            }
            textX += 10;
            long[] sizes = data == null ? null : data.sizes(dimension);
            String size = sizes == null ? "" : bytes(sizes[0] + sizes[1]);
            int sizeWidth = fontRendererObj.getStringWidth(size);
            String id = dimension == null ? "" : " " + dimension;
            int idWidth = fontRendererObj.getStringWidth(id);
            String label = dimension == null ? Lang.format("wayfarmap.stats.all_dims") : dimensionName(dimension);
            label = Theme.ellipsize(fontRendererObj, label, rowRight - 6 - sizeWidth - 6 - idWidth - textX);
            Theme.text(
                fontRendererObj,
                label,
                textX,
                y + 3,
                current ? Theme.TEXT : Theme.blend(Theme.TEXT, Theme.TEXT_MUTED, 0.3));
            Theme.text(fontRendererObj, id, textX + fontRendererObj.getStringWidth(label), y + 3, Theme.TEXT_DISABLED);
            Theme.text(
                fontRendererObj,
                size,
                rowRight - 6 - sizeWidth,
                y + 3,
                current ? Theme.ACCENT : Theme.TEXT_MUTED);
            // Its 2D and 3D map, beside the biggest dimension's (all of them: the whole bar).
            if (sizes != null) {
                int barX0 = textX, barX1 = rowRight - 6;
                long whole = sizes[0] + sizes[1];
                double fill = dimension == null ? 1 : Math.sqrt((double) whole / biggest);
                int barEnd = barX0 + (int) Math.round((barX1 - barX0) * fill * reveal.get());
                Theme.fill(barX0, y + 14, barX1, y + 16, Theme.CONTROL);
                if (whole > 0) {
                    stackedBar(
                        barX0,
                        y + 14,
                        Math.max(barX0 + 1, barEnd),
                        y + 16,
                        sizes,
                        new int[] { Theme.ACCENT, ISO_COLOR },
                        1);
                }
            }
            if (index == hovered) {
                List<String> lines = new ArrayList<>();
                lines.add(
                    dimension == null ? Lang.format("wayfarmap.stats.all_dims")
                        : dimensionName(dimension) + " §8[" + dimension + "]");
                if (sizes != null) {
                    lines.add("§9■ §7" + Lang.format("wayfarmap.stats.flat") + ": §f" + bytes(sizes[0]));
                    lines.add("§5■ §7" + Lang.format("wayfarmap.stats.iso") + ": §f" + bytes(sizes[1]));
                    if (dimension == null && data.sprites > 0) {
                        // All of them is the dimensions together and the block pictures they share.
                        lines.add("§8  " + Lang.format("wayfarmap.data.sprites_in", bytes(data.sprites)));
                    }
                }
                lines.add("§7" + Lang.format("wayfarmap.stats.waypoints") + ": §f" + number(sum(waypoints, dimension)));
                if (dimension != null && dimension.equals(here)) {
                    lines.add("§a" + Lang.format("wayfarmap.data.here"));
                }
                tooltip = lines;
            }
        }
        Theme.unclip();
        if (scrolls) {
            Theme.scrollbar(
                x1 - 4,
                y0 + 2,
                y1 - 2,
                y1 - y0,
                choices.size() * LIST_ROW,
                scroll / maxScroll(),
                Theme.inside(mouseX, mouseY, x0, y0, x1, y1));
        }
    }

    private void drawDetails(Data data, long[] scope, int value, int mouseX, int mouseY) {
        int x0 = detailLeft(), x1 = right - 10, y0 = detailTop(), y1 = bodyBottom();
        // Title: what is shown, and which dimension it is.
        String title = scopeName();
        if (picked != null) {
            title += " §8[" + picked + "]";
        }
        sectionTitle(
            picked != null && picked.equals(playerDimension()) ? Icons.SMALL_PIN : Icons.SMALL_EYE,
            picked != null && picked.equals(playerDimension()) ? Theme.SUCCESS : Theme.TEXT_MUTED,
            "",
            x0,
            bodyTop());
        fontRendererObj.drawStringWithShadow(
            Theme.ellipsize(fontRendererObj, title, x1 - x0 - 12),
            x0 + 11,
            bodyTop(),
            Theme.TEXT);
        Theme.fill(x0, y0, x1, y1, Theme.PANEL_ALT);
        Theme.outline(x0, y0, x1, y1, Theme.BORDER);

        long flat = scope == null ? 0 : scope[0], iso = scope == null ? 0 : scope[1], whole = flat + iso;
        double share = flatShare.update(whole <= 0 ? 0.5 : (double) flat / whole, 9);
        boolean empty = scope == null || whole <= 0;
        int part = ringPartAt(mouseX, mouseY, share, empty);
        // A row hovered lights its part of the ring too.
        for (int row = 0; row < 2; row++) {
            if (Theme.inside(mouseX, mouseY, x0 + 1, rowY(row), x1 - 1, rowY(row) + ROW)) {
                part = row == 0 ? PART_FLAT : PART_ISO;
            }
        }
        if (empty && part != PART_NONE) {
            part = PART_NONE;
        }
        int cx = ringCenterX(), cy = ringCenterY();
        ring(cx, cy, share, reveal.get(), empty, part);
        // In the hole: the whole size, or the part's under the mouse.
        String center, under;
        int centerColor;
        if (scope == null) {
            center = null;
            under = Lang.format("wayfarmap.stats.total");
            centerColor = value;
        } else if (part == PART_FLAT || part == PART_ISO) {
            long size = part == PART_FLAT ? flat : iso;
            center = percent(size, whole);
            under = bytes(size);
            centerColor = part == PART_FLAT ? Theme.ACCENT : ISO_COLOR;
        } else {
            center = bytes(whole);
            under = empty ? Lang.format("wayfarmap.data.empty") : Lang.format("wayfarmap.stats.total");
            centerColor = value;
        }
        int hole = 2 * HOLE - 4;
        if (center == null) {
            placeholder(cx - 15, cy - 8, 30);
        } else {
            String shown = Theme.ellipsize(fontRendererObj, center, hole);
            fontRendererObj
                .drawStringWithShadow(shown, cx - fontRendererObj.getStringWidth(shown) / 2, cy - 8, centerColor);
        }
        Theme.centered(fontRendererObj, Theme.ellipsize(fontRendererObj, under, hole), cx, cy + 2, Theme.TEXT_MUTED);

        // A row for the 2D map, the 3D map and both, each with its delete button.
        int rowX = cx + RING + 14;
        for (int row = 0; row < 3; row++) {
            int id = ID_DEL_2D + row, y = rowY(row);
            int buttonLeft = deleteButtons.get(row).xPosition;
            boolean lit = row < 2 && part == (row == 0 ? PART_FLAT : PART_ISO) || armed == id;
            if (lit) {
                int light = armed == id ? Theme.alpha(Theme.DANGER, 0x30) : Theme.ROW_HOVER;
                Theme.fill(rowX - 6, y, x1 - 1, y + ROW, light);
            }
            if (row == 2) {
                Theme.fill(rowX - 6, y, x1 - 1, y + 1, Theme.BORDER);
            }
            int color = row == 0 ? Theme.ACCENT : row == 1 ? ISO_COLOR : 0;
            int textX = rowX;
            if (color != 0) {
                Theme.fill(textX, y + 6, textX + 7, y + 13, color);
            } else {
                // Both: the two colors halved.
                Theme.fill(textX, y + 6, textX + 7, y + 13, Theme.ACCENT);
                Theme.fill(textX + 4, y + 6, textX + 7, y + 13, ISO_COLOR);
            }
            textX += 12;
            int room = buttonLeft - 8 - textX;
            Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, rowLabel(id), room), textX, y + 3, Theme.TEXT);
            long size = row == 0 ? flat : row == 1 ? iso : whole;
            if (scope == null) {
                placeholder(textX, y + 13, 36);
            } else {
                String sizeText = bytes(size);
                Theme.text(
                    fontRendererObj,
                    sizeText,
                    textX,
                    y + 13,
                    row == 2 ? value : value == Theme.TEXT ? color : value);
                if (row < 2 && whole > 0) {
                    Theme.text(
                        fontRendererObj,
                        "· " + percent(size, whole),
                        textX + fontRendererObj.getStringWidth(sizeText) + 4,
                        y + 13,
                        Theme.TEXT_MUTED);
                }
            }
            if (picked == null && row == 1
                && data != null
                && data.sprites > 0
                && Theme.inside(mouseX, mouseY, rowX - 6, y, buttonLeft - 2, y + ROW)) {
                // All dimensions' 3D map: theirs and the block pictures they share (deleted with it).
                tooltip = Arrays.asList(
                    rowLabel(id) + ": " + bytes(size),
                    "§7" + Lang.format("wayfarmap.data.sprites_in", bytes(data.sprites)));
            }
        }

        // What is kept whatever is deleted.
        int keptY = keptY();
        Theme.fill(x0 + 8, keptY - 6, x1 - 8, keptY - 5, Theme.BORDER);
        sectionTitle(Icons.SMALL_CHECK, Theme.SUCCESS, Lang.format("wayfarmap.data.kept"), x0 + 10, keptY);
        List<String[]> icons = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        icons.add(Icons.SMALL_FLAG);
        colors.add(0xFFF2C14E);
        labels.add(Lang.format("wayfarmap.stats.waypoints"));
        values.add(number(sum(waypoints, picked)));
        if (prospected != null) {
            Map<Integer, Integer> veins = new HashMap<>(), fluids = new HashMap<>();
            for (Map.Entry<Integer, int[]> entry : prospected.entrySet()) {
                veins.put(entry.getKey(), entry.getValue()[0]);
                fluids.put(entry.getKey(), entry.getValue()[1]);
            }
            icons.add(Icons.ORE);
            colors.add(0xFF3FB9A6);
            labels.add(Lang.format("wayfarmap.stats.veins"));
            values.add(number(sum(veins, picked)));
            icons.add(Icons.SMALL_DROP);
            colors.add(0xFF58A6FF);
            labels.add(Lang.format("wayfarmap.stats.fluids"));
            values.add(number(sum(fluids, picked)));
        }
        if (nodes != null) {
            icons.add(Icons.NODE);
            colors.add(0xFFD2A8FF);
            labels.add(Lang.format("wayfarmap.stats.nodes"));
            values.add(number(sum(nodes, picked)));
        }
        int count = icons.size(), gap = 5, chipY = keptY + 12;
        int chipWidth = (x1 - x0 - 20 - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            int cx0 = x0 + 10 + i * (chipWidth + gap), cx1 = cx0 + chipWidth;
            boolean hovered = Theme.inside(mouseX, mouseY, cx0, chipY, cx1, chipY + CHIP);
            Theme.fill(cx0, chipY, cx1, chipY + CHIP, hovered ? Theme.CONTROL_HOVER : Theme.CONTROL);
            Theme.outline(
                cx0,
                chipY,
                cx1,
                chipY + CHIP,
                hovered ? Theme.blend(Theme.BORDER, colors.get(i), 0.6) : Theme.BORDER);
            String[] icon = icons.get(i);
            Icons.draw(icon, cx0 + 6, chipY + (CHIP - icon.length) / 2, colors.get(i));
            int textX = cx0 + 6 + Math.max(9, Icons.width(icon)) + 5;
            String number = values.get(i);
            fontRendererObj.drawStringWithShadow(number, textX, chipY + 7, Theme.TEXT);
            int labelX = textX + fontRendererObj.getStringWidth(number) + 5;
            Theme.text(
                fontRendererObj,
                Theme.ellipsize(fontRendererObj, labels.get(i), cx1 - 5 - labelX),
                labelX,
                chipY + 7,
                Theme.TEXT_MUTED);
            if (hovered) {
                tooltip = Arrays.asList(labels.get(i) + ": " + number, "§7" + scopeName());
            }
        }

        // Each dimension's share of this world, biggest first; the picked one lit, a click picks one.
        int shareY = chipY + CHIP + 10;
        boolean shares = data != null && shareY + 34 < y1 - 16;
        if (shares) {
            drawShare(data, x0 + 10, shareY, x1 - 10, mouseX, mouseY);
        }

        // At the bottom: what the sizes are and what is kept.
        int noteWidth = x1 - x0 - 20;
        List<String> note = new ArrayList<>();
        for (Object line : fontRendererObj.listFormattedStringToWidth(Lang.format("wayfarmap.data.note"), noteWidth)) {
            note.add((String) line);
        }
        int room = (y1 - 4 - (shares ? shareY + 36 : chipY + CHIP + 6)) / 10;
        if (note.size() > room) {
            if (room <= 0) {
                note.clear();
            } else {
                note = new ArrayList<>(note.subList(0, room));
                note.set(room - 1, Theme.ellipsize(fontRendererObj, note.get(room - 1) + "...", noteWidth));
            }
        }
        for (int i = 0; i < note.size(); i++) {
            Theme.text(fontRendererObj, note.get(i), x0 + 10, y1 - 6 - (note.size() - i) * 10, Theme.TEXT_DISABLED);
        }
    }

    /** Colors of the dimensions in the bar of their shares, in turn. */
    private static final int[] SHARE_COLORS = { 0xFF4C9AFF, 0xFFA371F7, 0xFF3FB9A6, 0xFFF2C14E, 0xFFE5534B, 0xFF58A6FF,
        0xFFD2A8FF, 0xFF56D364, 0xFFFF9B50 };
    private static final int SHARE_HEIGHT = 8;

    /** Dimensions with map files, biggest first. */
    private List<Integer> bySize(Data data) {
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : data.dimensions.entrySet()) {
            if (entry.getValue()[0] + entry.getValue()[1] > 0) {
                ids.add(entry.getKey());
            }
        }
        ids.sort((a, b) -> Long.compare(total(data, b), total(data, a)));
        return ids;
    }

    private static long total(Data data, Integer dimension) {
        long[] sizes = data.sizes(dimension);
        return sizes[0] + sizes[1];
    }

    /** Where each dimension of {@link #bySize} starts in the bar from {@code x0} to {@code x1}, and where it ends. */
    private int[] shareEdges(Data data, List<Integer> ids, int x0, int x1) {
        long whole = 0;
        for (Integer id : ids) {
            whole += total(data, id);
        }
        int[] edges = new int[ids.size() + 1];
        long sum = 0;
        edges[0] = x0;
        for (int i = 0; i < ids.size(); i++) {
            sum += total(data, ids.get(i));
            edges[i + 1] = whole <= 0 ? x0 : x0 + (int) Math.round((x1 - x0) * (double) sum / whole);
        }
        return edges;
    }

    /** Dimension of the bar of shares under the mouse, null if none. */
    private Integer shareAt(int mouseX, int mouseY) {
        Data data = visibleData();
        if (data == null || shareBarY < 0
            || !Theme.inside(mouseX, mouseY, shareX0, shareBarY, shareX1, shareBarY + SHARE_HEIGHT)) {
            return null;
        }
        List<Integer> ids = bySize(data);
        int[] edges = shareEdges(data, ids, shareX0, shareX1);
        for (int i = 0; i < ids.size(); i++) {
            if (mouseX >= edges[i] && mouseX < edges[i + 1]) {
                return ids.get(i);
            }
        }
        return null;
    }

    private void drawShare(Data data, int x0, int y, int x1, int mouseX, int mouseY) {
        sectionTitle(Icons.STATS, Theme.TEXT_MUTED, Lang.format("wayfarmap.data.share"), x0, y);
        int barY = y + 13;
        shareX0 = x0;
        shareX1 = x1;
        shareBarY = barY;
        List<Integer> ids = bySize(data);
        Theme.fill(x0, barY, x1, barY + SHARE_HEIGHT, Theme.CONTROL);
        int[] edges = shareEdges(data, ids, x0, x1);
        Integer hovered = shareAt(mouseX, mouseY);
        long whole = total(data, null) - data.sprites;
        int shown = x0 + (int) Math.round((x1 - x0) * reveal.get());
        for (int i = 0; i < ids.size(); i++) {
            Integer id = ids.get(i);
            int from = edges[i], to = Math.min(shown, Math.max(edges[i] + 1, edges[i + 1]));
            if (to <= from) {
                continue;
            }
            int color = SHARE_COLORS[i % SHARE_COLORS.length];
            boolean lit = id.equals(hovered) || id.equals(picked);
            if (!lit && (picked != null || hovered != null)) {
                color = Theme.blend(color, Theme.PANEL_ALT | 0xFF000000, 0.6);
            }
            // A dark pixel between dimensions.
            Theme.fill(from, barY, Math.max(from + 1, to - (i < ids.size() - 1 ? 1 : 0)), barY + SHARE_HEIGHT, color);
            if (id.equals(hovered)) {
                Theme.outline(from - 1, barY - 1, to + 1, barY + SHARE_HEIGHT + 1, 0xFFFFFFFF);
                tooltip = Arrays.asList(
                    dimensionName(id) + " §8[" + id + "]",
                    "§f" + bytes(total(data, id)) + " §7· " + percent(total(data, id), whole));
            }
        }
        // The biggest ones under it, as many as fit.
        int x = x0, legendY = barY + SHARE_HEIGHT + 5;
        for (int i = 0; i < ids.size(); i++) {
            Integer id = ids.get(i);
            String text = dimensionName(id) + " " + percent(total(data, id), whole);
            int itemWidth = 9 + fontRendererObj.getStringWidth(text);
            if (x + itemWidth > x1) {
                break;
            }
            int color = SHARE_COLORS[i % SHARE_COLORS.length];
            Theme.fill(x, legendY + 1, x + 5, legendY + 6, color);
            Theme.text(fontRendererObj, text, x + 8, legendY, id.equals(picked) ? Theme.TEXT : Theme.TEXT_MUTED);
            x += itemWidth + 10;
        }
    }

    private void drawFooter(Data data, int value) {
        int y0 = bottom - FOOTER;
        Theme.fill(left + 1, y0, right - 1, bottom - 1, Theme.PANEL_ALT);
        Theme.fill(left + 1, y0, right - 1, y0 + 1, Theme.BORDER);
        int x = drawFooterItem(Icons.LOGS, ID_LOGS, data == null ? -1 : data.logs, left + 12, y0, value);
        deleteButtons.get(3).xPosition = x + 10;
        x += 10 + deleteButtons.get(3)
            .getWidth() + 18;
        Theme.fill(x - 9, y0 + 7, x - 8, bottom - 7, Theme.BORDER);
        x = drawFooterItem(Icons.PALETTE, ID_CACHE, data == null ? -1 : data.cache, x, y0, value);
        deleteButtons.get(4).xPosition = x + 10;
    }

    /** An icon, a label and a size (-1: not counted yet) in the footer; returns where it ends. */
    private int drawFooterItem(String[] icon, int id, long bytes, int x, int y0, int value) {
        Icons.draw(icon, x, y0 + (FOOTER - icon.length) / 2, Theme.TEXT_MUTED);
        x += Icons.width(icon) + 6;
        String label = rowLabel(id);
        Theme.text(fontRendererObj, label, x, y0 + 11, Theme.TEXT);
        x += fontRendererObj.getStringWidth(label) + 6;
        if (bytes < 0) {
            placeholder(x, y0 + 11, 30);
            return x + 30;
        }
        String size = bytes(bytes);
        Theme.text(fontRendererObj, size, x, y0 + 11, value == Theme.TEXT ? Theme.TEXT_MUTED : value);
        return x + fontRendererObj.getStringWidth(size);
    }

    /**
     * Over the footer: the confirmation asked while a button waits for its second click, else for a while what the
     * last cleaning did. It slides in.
     */
    private void drawToast() {
        String text;
        int color;
        double shown;
        if (armed != -1) {
            String what = armed == ID_LOGS || armed == ID_CACHE || armed == ID_WIPE ? rowLabel(armed)
                : rowLabel(armed) + " · " + scopeName();
            text = Lang.format("wayfarmap.data.confirm", what);
            color = Theme.DANGER;
            shown = Math.min(1, (System.currentTimeMillis() - armedAt) / 150.0);
        } else {
            long age = System.currentTimeMillis() - toastAt;
            if (toast == null || age > TOAST_MS) {
                return;
            }
            text = toast;
            color = Theme.SUCCESS;
            shown = Math.min(Math.min(1, age / 150.0), Math.min(1, (TOAST_MS - age) / 250.0));
        }
        int maxWidth = right - left - 60;
        text = Theme.ellipsize(fontRendererObj, text, maxWidth);
        int shift = (int) Math.round((1 - shown) * 6);
        int textWidth = fontRendererObj.getStringWidth(text);
        int bottomY = bottom - FOOTER - 4 + shift;
        int x0 = (left + right - textWidth) / 2 - 12, y0 = bottomY - 18, x1 = x0 + textWidth + 24;
        if (!Theme.sprite(x0, y0, x1, bottomY, "toast")) {
            Theme.fill(x0 + 2, y0 + 2, x1 + 2, bottomY + 2, 0x60000000);
            Theme.fill(x0, y0, x1, bottomY, Theme.alpha(Theme.PANEL, 0xF8));
            Theme.outline(x0, y0, x1, bottomY, color);
        }
        Theme.fill(x0, y0, x0 + 3, bottomY, color);
        Theme.text(fontRendererObj, text, x0 + 13, y0 + 5, color);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
