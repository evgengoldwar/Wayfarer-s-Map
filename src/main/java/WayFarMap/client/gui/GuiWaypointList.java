package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import WayFarMap.client.Lang;
import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.IconButton;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Smooth;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.map.MapManager;
import WayFarMap.client.waypoint.TeamWaypoints;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointGroup;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;

/**
 * All waypoints, in two panes: the groups down the left (all of them, each group, the ungrouped ones), where a group
 * is picked, shown or hidden, renamed, moved and deleted, and waypoints are dropped to move them; and the waypoints of
 * the group picked on the right, as cards in sections per group, each with its icon, place, distance and a compass
 * arrow pointing to it. Shown for one dimension at a time (this one at first) or all of them, narrowed down by
 * searching names and coordinates (typing anywhere searches) and sorted as kept, by name or by distance.
 * <p>
 * In a team (on a server with the mod) a waypoint can be shared with it from its card, and a group's section title
 * has the group's sharing: whether its new waypoints are shared, whether teammates' ones coming into it are shown.
 * Teammates' waypoints are listed with the others, tagged with their owner; they can be hidden or saved as own, not
 * changed.
 */
public class GuiWaypointList extends ScaledScreen {

    private static final int ID_GROUP_ACTION = 0, ID_NEW_WAYPOINT = 1, ID_DONE = 2, ID_DIMENSION = 3, ID_SORT = 4;

    /** Heights of a waypoint's card, of a group's section title, and the gap between cards. */
    private static final int CARD_HEIGHT = 28, HEADER_HEIGHT = 20, GAP = 3;
    /** Width of the groups' pane, and height of one of its lines. */
    private static final int SIDEBAR_WIDTH = 150, ENTRY_HEIGHT = 20;
    /** Size of the small icon buttons on a card and a group's line, and the room between them. */
    private static final int ACTION_SIZE = 14, ACTION_STEP = 16, ENTRY_ACTION = 11;
    /** Height of a line of the dimension list, and how many show at once. */
    private static final int CHOICE_ROW = 14, CHOICES_SHOWN = 10;
    private static final long CONFIRM_MS = 3000, DOUBLE_CLICK_MS = 350, COPIED_MS = 1200;
    /** How far a turn of the mouse wheel scrolls (GUI pixels). */
    private static final int WHEEL_STEP = CARD_HEIGHT + GAP;
    private static final String UNGROUPED_KEY = "\u0000ungrouped";

    private static final int CARD = 0xFF1B2028, CARD_HOVER = 0xFF232A34, CARD_SELECTED = 0xFF1E2C40;
    private static final int TILE = 0xFF0F1216, SECTION = 0xFF161B22, SIDEBAR = 0xFF12161B;

    /** Colors of the groups, picked by their names, so a group keeps its color. */
    private static final int[] GROUP_COLORS = { 0xFF4C9AFF, 0xFF3FB950, 0xFFF2C14E, 0xFFE5534B, 0xFFB37FEB, 0xFF39C5CF,
        0xFFFF8C42, 0xFFE67AB8, 0xFF8BD450, 0xFF6C8CFF };

    /** An arrow pointing up, turned to point at a waypoint. */
    private static final String[] ARROW = { "...#...", "..###..", ".#####.", "#######", "..###..", "..###..",
        "..###.." };
    private static final String[] SMALL_DOWN = { "..###..", "...#...", "...#...", "...#...", ".#####.", "..###..",
        "...#..." };
    private static final String[] SMALL_MAP = { "#######", "#..#..#", "#.##..#", "#..#.##", "#.....#", "#..##.#",
        "#######" };
    private static final String[] SMALL_TELEPORT = { "..###..", ".#...#.", "#..#..#", "#.###.#", "#..#..#", ".#...#.",
        "..###.." };
    private static final String[] SMALL_EYE_OFF = { "#......", ".####..", ".##..#.", "#..#..#", ".#..##.", "..####.",
        "......#" };
    private static final String[] SMALL_SORT = { ".#.....", "###....", ".#..###", ".#.....", ".#..##.", ".#.....",
        ".#..#.." };

    private enum Sort {

        KEPT("wayfarmap.gui.sort_kept"),
        NAME("wayfarmap.gui.sort_name"),
        DISTANCE("wayfarmap.gui.sort_distance");

        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    /** Collapsed groups (by key) stay collapsed while the game runs, as do the other choices of the screen. */
    private static final Set<String> collapsed = new HashSet<>();
    /** The dimension picked: null for the player's own, {@link #ALL} for every one. */
    private static Integer pickedDimension;
    private static final int ALL = Integer.MIN_VALUE;
    private static String searchText = "";
    /** The group picked on the left (its key), null for all of them. */
    private static String pickedGroup;
    private static Sort sort = Sort.KEPT;

    private final GuiScreen parent;

    /** One line of the list: a group's section title (waypoint == null) or a waypoint's card. */
    private static class Row {

        final WaypointGroup group;
        final boolean ungrouped;
        final Waypoint waypoint;
        /** Where it starts in the list and how high it is (GUI pixels, the list unscrolled). */
        int y, height;
        /** For a title: its group's waypoints the filters let through. */
        List<Waypoint> matching;

        Row(WaypointGroup group, boolean ungrouped, Waypoint waypoint) {
            this.group = group;
            this.ungrouped = ungrouped;
            this.waypoint = waypoint;
        }

        String key() {
            return ungrouped ? UNGROUPED_KEY : group.name;
        }
    }

    /** One line of the groups' pane: all waypoints (key == null), a group or the ungrouped ones. */
    private static class Entry {

        final String key;
        final WaypointGroup group;
        int count;

        Entry(String key, WaypointGroup group) {
            this.key = key;
            this.group = group;
        }
    }

    /** Clickable area registered while drawing, cut to the part of it that shows. */
    private static class Hit {

        final int x0, y0, x1, y1;
        final Runnable action;
        final String tooltip;

        Hit(int x0, int y0, int x1, int y1, Runnable action, String tooltip) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.action = action;
            this.tooltip = tooltip;
        }

        boolean contains(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    /** Where hits registered now are cut to: the pane being drawn. */
    private int hitX0, hitY0, hitX1, hitY1;

    private int panelLeft, panelRight, panelTop, panelBottom;
    private int sideLeft, sideRight, sideTop, sideBottom;
    private int listLeft, listRight, listTop, listBottom;
    /** Height of all of the list's rows. */
    private int contentHeight;
    /** How far the list and the groups' pane are scrolled (GUI pixels), and where they are drawn while easing. */
    private int scroll, sideScroll;
    private final Smooth shownScroll = new Smooth(0), shownSideScroll = new Smooth(0);
    /** How lit each card and line is by the mouse. */
    private final Map<Object, Smooth> lights = new HashMap<>();
    /** Where the mark of the picked group is drawn while it eases to it. */
    private final Smooth pickedMarkY = new Smooth(-1);

    private FlatTextField groupField, searchField;
    private FlatButton dimensionButton, sortButton;
    private IconButton groupActionButton;
    /** The list of dimensions is open under its button, and how far it is scrolled. */
    private boolean choicesOpen;
    private int choicesScroll;
    /** Names of the dimensions, looked up once (some are read from their maps' files). */
    private final Map<Integer, String> dimensionNames = new HashMap<>();
    /** Waypoints the filters let through, out of all of them. */
    private int shownCount;
    private WaypointGroup renamingGroup;

    /** The waypoint picked with a click or the arrow keys. */
    private Waypoint selected;
    private long lastClickTime;
    private Waypoint lastClicked;
    /** The waypoint whose place was copied, and when: "Copied!" shows on it for a moment. */
    private Waypoint copied;
    private long copiedAt;

    /** Waypoint under the mouse when the left button went down; becomes a drag once the mouse moves. */
    private Waypoint pressedWaypoint;
    private int pressX, pressY;
    private boolean draggingWaypoint;
    private long lastAutoScroll;

    private Object pendingDelete;
    private long pendingDeleteTime;
    /** The tooltip of what the mouse is on, drawn last. */
    private String tooltip;
    /** {@link WaypointManager#getTeamRevision} the rows were made at. */
    private int teamRevision;

    public GuiWaypointList(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelWidth = Math.min(width - 20, 660);
        panelLeft = (width - panelWidth) / 2;
        panelRight = panelLeft + panelWidth;
        panelTop = 6;
        panelBottom = height - 6;
        int bodyTop = panelTop + WindowHeader.HEIGHT + 8;
        int footerY = panelBottom - 26;

        int sidebarWidth = Math.min(SIDEBAR_WIDTH, panelWidth / 3);
        sideLeft = panelLeft + 8;
        sideRight = sideLeft + sidebarWidth;
        sideTop = bodyTop + 14;
        sideBottom = footerY - 26;

        listLeft = sideRight + 10;
        listRight = panelRight - 8;
        int toolbarY = bodyTop;
        listTop = toolbarY + 22;
        listBottom = footerY - 8;

        buttonList.clear();
        // The toolbar over the list: searching, the dimension and the order.
        int sortWidth = 96;
        int pickerWidth = Math.min(150, (listRight - listLeft) / 3);
        String oldSearch = searchField != null ? searchField.getText() : searchText;
        int searchRight = listRight - sortWidth - pickerWidth - 8;
        searchField = new FlatTextField(fontRendererObj, listLeft, toolbarY, searchRight - listLeft, 16)
            .setHint(Lang.format("wayfarmap.gui.search_waypoints"))
            .setOnCleared(() -> setSearch(""));
        searchField.setMaxStringLength(48);
        searchField.setText(oldSearch);
        dimensionButton = new FlatButton(ID_DIMENSION, searchRight + 4, toolbarY, pickerWidth, 16, "");
        buttonList.add(dimensionButton);
        sortButton = new FlatButton(ID_SORT, listRight - sortWidth, toolbarY, sortWidth, 16, "");
        sortButton.icon = SMALL_SORT;
        buttonList.add(sortButton);
        choicesOpen = false;

        // Under the groups: a new group's name, or a new name for one.
        String oldText = groupField != null ? groupField.getText() : "";
        groupField = new FlatTextField(fontRendererObj, sideLeft, sideBottom + 6, sideRight - sideLeft - 22, 16)
            .setHint(Lang.format("wayfarmap.gui.new_group_hint"))
            .setOnCleared(this::updateGroupButton);
        groupField.setMaxStringLength(32);
        groupField.setText(oldText);
        groupActionButton = new IconButton(ID_GROUP_ACTION, sideRight - 20, sideBottom + 6, Icons.SMALL_PLUS, "");
        buttonList.add(groupActionButton);

        int buttonWidth = 110;
        FlatButton newWaypoint = new FlatButton(
            ID_NEW_WAYPOINT,
            listRight - buttonWidth * 2 - 4,
            footerY,
            buttonWidth,
            18,
            Lang.format("wayfarmap.gui.new_waypoint"));
        newWaypoint.active = true;
        newWaypoint.icon = Icons.SMALL_PLUS;
        buttonList.add(newWaypoint);
        buttonList
            .add(new FlatButton(ID_DONE, listRight - buttonWidth, footerY, buttonWidth, 18, Lang.format("gui.done")));
        buttonList.add(WindowHeader.closeButton(ID_DONE, panelRight, panelTop));
        updateGroupButton();
        rebuildRows();
    }

    private void updateGroupButton() {
        boolean renaming = renamingGroup != null;
        groupActionButton.icon = renaming ? Icons.SMALL_CHECK : Icons.SMALL_PLUS;
        groupActionButton.tooltip = Lang.format(renaming ? "wayfarmap.gui.rename_group" : "wayfarmap.gui.create_group");
        groupActionButton.active = renaming;
    }

    private void setSearch(String text) {
        searchText = text;
        scroll = 0;
        rebuildRows();
    }

    // ---- What is shown ----

    private void rebuildRows() {
        WaypointManager manager = WaypointManager.INSTANCE;
        if (pickedGroup != null && !UNGROUPED_KEY.equals(pickedGroup) && manager.getGroup(pickedGroup) == null) {
            // The group picked is gone (deleted, or renamed elsewhere).
            pickedGroup = null;
        }
        entries.clear();
        Entry all = new Entry(null, null);
        entries.add(all);
        rows.clear();
        shownCount = 0;
        teamRevision = manager.getTeamRevision();
        for (WaypointGroup group : manager.getGroups()) {
            addGroup(group, false, group.name, manager.getWaypointsInGroup(group.name), all);
        }
        addGroup(null, true, UNGROUPED_KEY, manager.getWaypointsInGroup(null), all);

        int y = 0;
        for (Row row : rows) {
            row.y = y;
            row.height = row.waypoint == null ? HEADER_HEIGHT : CARD_HEIGHT;
            y += row.height + GAP;
        }
        contentHeight = Math.max(0, y - GAP);
        if (selected != null && !isListed(selected)) {
            selected = null;
        }
        scroll = clampScroll(scroll);
        sideScroll = clampSideScroll(sideScroll);
    }

    /**
     * A group's line on the left and, when it is the one picked (or all are), its section title and its waypoints the
     * filters let through; while searching, no section for a group with none of them.
     */
    private void addGroup(WaypointGroup group, boolean ungrouped, String key, List<Waypoint> waypoints, Entry all) {
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (matches(waypoint)) {
                matching.add(waypoint);
            }
        }
        sortWaypoints(matching);
        Entry entry = new Entry(key, group);
        entry.count = matching.size();
        all.count += matching.size();
        // The ungrouped ones get a line only when there are some, or there are no groups at all.
        if (!ungrouped || !waypoints.isEmpty() || entries.size() == 1) {
            entries.add(entry);
        }
        if (pickedGroup != null && !pickedGroup.equals(key)) {
            return;
        }
        shownCount += matching.size();
        if (matching.isEmpty() && (!searchText.isEmpty() || ungrouped && pickedGroup == null)) {
            return;
        }
        Row header = new Row(group, ungrouped, null);
        header.matching = matching;
        rows.add(header);
        // Searching opens the groups: what was found shows; a group picked alone shows open.
        if (!collapsed.contains(key) || !searchText.isEmpty() || pickedGroup != null) {
            for (Waypoint waypoint : matching) {
                rows.add(new Row(group, ungrouped, waypoint));
            }
        }
    }

    private void sortWaypoints(List<Waypoint> waypoints) {
        if (sort == Sort.NAME) {
            waypoints.sort(Comparator.comparing((Waypoint w) -> w.name.toLowerCase(Locale.ROOT)));
        } else if (sort == Sort.DISTANCE) {
            waypoints.sort(Comparator.comparingDouble(this::distanceTo));
        }
    }

    /** Distance from the player on the ground, infinite for another dimension (sorted last). */
    private double distanceTo(Waypoint waypoint) {
        if (mc == null || mc.thePlayer == null
            || mc.theWorld == null
            || waypoint.dimension != mc.theWorld.provider.dimensionId) {
            return Double.MAX_VALUE;
        }
        double dx = waypoint.x + 0.5 - mc.thePlayer.posX, dz = waypoint.z + 0.5 - mc.thePlayer.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean isListed(Waypoint waypoint) {
        for (Row row : rows) {
            if (row.waypoint == waypoint) {
                return true;
            }
        }
        return false;
    }

    /** The dimension shown: the picked one, the player's own, or {@link #ALL}. */
    private int shownDimension() {
        if (pickedDimension != null) {
            return pickedDimension;
        }
        return mc != null && mc.theWorld != null ? mc.theWorld.provider.dimensionId : ALL;
    }

    private boolean matches(Waypoint waypoint) {
        int dimension = shownDimension();
        if (dimension != ALL && waypoint.dimension != dimension) {
            return false;
        }
        if (searchText.isEmpty()) {
            return true;
        }
        String query = searchText.toLowerCase(Locale.ROOT)
            .trim();
        String coordinates = waypoint.x + " " + waypoint.y + " " + waypoint.z;
        boolean owner = waypoint.ownerName != null && waypoint.ownerName.toLowerCase(Locale.ROOT)
            .contains(query);
        return owner || waypoint.name.toLowerCase(Locale.ROOT)
            .contains(query)
            || coordinates.contains(query)
            || (waypoint.x + ", " + waypoint.y + ", " + waypoint.z).contains(query);
    }

    private String dimensionName(int id) {
        return dimensionNames.computeIfAbsent(id, i -> MapManager.INSTANCE.getDimensionName(i));
    }

    /** The choices of the dimension list: every dimension, then each one with waypoints, the player's own first. */
    private List<Integer> dimensionChoices() {
        Set<Integer> ids = new TreeSet<>();
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            ids.add(waypoint.dimension);
        }
        List<Integer> choices = new ArrayList<>();
        choices.add(ALL);
        if (mc.theWorld != null) {
            int own = mc.theWorld.provider.dimensionId;
            choices.add(own);
            ids.remove(own);
        }
        choices.addAll(ids);
        return choices;
    }

    private int countIn(int dimension) {
        int count = 0;
        for (Waypoint waypoint : WaypointManager.INSTANCE.getWaypoints()) {
            if (dimension == ALL || waypoint.dimension == dimension) {
                count++;
            }
        }
        return count;
    }

    private String choiceLabel(int dimension) {
        String name = dimension == ALL ? Lang.format("wayfarmap.gui.all_dimensions") : dimensionName(dimension);
        return name + " (" + countIn(dimension) + ")";
    }

    /** The open dimension list's line under the mouse, or -1. */
    private int choiceAt(int mouseX, int mouseY) {
        int x0 = dimensionButton.xPosition, y0 = dimensionButton.yPosition + 17;
        if (!choicesOpen || mouseX < x0 || mouseX >= x0 + dimensionButton.getWidth() || mouseY < y0) {
            return -1;
        }
        int line = (mouseY - y0) / CHOICE_ROW;
        int shown = Math.min(CHOICES_SHOWN, dimensionChoices().size());
        return line < shown ? line + choicesScroll : -1;
    }

    /** The color of a group, by its name; grey for the ungrouped ones, the accent for all. */
    private static int groupColor(String key) {
        if (key == null) {
            return Theme.ACCENT;
        }
        if (UNGROUPED_KEY.equals(key)) {
            return Theme.TEXT_MUTED;
        }
        return GROUP_COLORS[Math.floorMod(key.hashCode(), GROUP_COLORS.length)];
    }

    /** The color a waypoint is marked with: its outline's, else its group's. */
    private static int waypointColor(Waypoint waypoint) {
        if (waypoint.outlineColor != null) {
            return 0xFF000000 | waypoint.outlineColor;
        }
        return groupColor(waypoint.group == null ? UNGROUPED_KEY : waypoint.group);
    }

    private boolean isGroupVisible(String key) {
        WaypointManager manager = WaypointManager.INSTANCE;
        if (UNGROUPED_KEY.equals(key)) {
            return manager.isUngroupedVisible();
        }
        WaypointGroup group = manager.getGroup(key);
        return group == null || group.visible;
    }

    private void toggleGroup(String key) {
        WaypointManager manager = WaypointManager.INSTANCE;
        if (UNGROUPED_KEY.equals(key)) {
            manager.setUngroupedVisible(!manager.isUngroupedVisible());
        } else {
            WaypointGroup group = manager.getGroup(key);
            if (group != null) {
                manager.setGroupVisible(group, !group.visible);
            }
        }
    }

    private String groupTitle(String key) {
        if (key == null) {
            return Lang.format("wayfarmap.gui.all_waypoints");
        }
        return UNGROUPED_KEY.equals(key) ? Lang.format("wayfarmap.gui.no_group") : key;
    }

    // ---- Scrolling ----

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, Math.max(0, contentHeight - (listBottom - listTop))));
    }

    private int clampSideScroll(int value) {
        return Math.max(0, Math.min(value, Math.max(0, entries.size() * ENTRY_HEIGHT - (sideBottom - sideTop))));
    }

    /** Scrolls the list just enough for the waypoint's card to show. */
    private void scrollTo(Waypoint waypoint) {
        for (Row row : rows) {
            if (row.waypoint == waypoint) {
                if (row.y < scroll) {
                    scroll = clampScroll(row.y - GAP);
                } else if (row.y + row.height > scroll + listBottom - listTop) {
                    scroll = clampScroll(row.y + row.height - (listBottom - listTop) + GAP);
                }
                return;
            }
        }
    }

    // ---- Actions ----

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case ID_GROUP_ACTION:
                applyGroupField();
                break;
            case ID_NEW_WAYPOINT:
                newWaypoint();
                break;
            case ID_DONE:
                mc.displayGuiScreen(parent);
                break;
            case ID_DIMENSION:
                choicesOpen = !choicesOpen;
                choicesScroll = 0;
                break;
            case ID_SORT:
                sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
                rebuildRows();
                break;
            default:
                break;
        }
    }

    private void newWaypoint() {
        if (mc.thePlayer == null) {
            return;
        }
        GuiEditWaypoint editor = GuiEditWaypoint.create(
            this,
            MathHelper.floor_double(mc.thePlayer.posX),
            MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
            MathHelper.floor_double(mc.thePlayer.posZ),
            mc.theWorld.provider.dimensionId);
        mc.displayGuiScreen(editor);
    }

    private void applyGroupField() {
        String name = groupField.getText()
            .trim();
        if (name.isEmpty()) {
            groupField.setFocused(true);
            return;
        }
        WaypointManager manager = WaypointManager.INSTANCE;
        if (renamingGroup != null) {
            String oldName = renamingGroup.name;
            if (manager.renameGroup(renamingGroup, name)) {
                if (collapsed.remove(oldName)) {
                    collapsed.add(renamingGroup.name);
                }
                if (oldName.equals(pickedGroup)) {
                    pickedGroup = renamingGroup.name;
                }
            }
            renamingGroup = null;
        } else {
            pickedGroup = manager.createGroup(name).name;
            scroll = 0;
        }
        groupField.setText("");
        groupField.setFocused(false);
        updateGroupButton();
        rebuildRows();
    }

    private void startRenaming(WaypointGroup group) {
        renamingGroup = group;
        groupField.setText(group.name);
        groupField.setFocused(true);
        updateGroupButton();
    }

    private void pickGroup(String key) {
        if (!Objects.equals(pickedGroup, key)) {
            pickedGroup = key;
            scroll = 0;
            shownScroll.set(0);
            rebuildRows();
        }
    }

    /** Runs {@code action} on the second click within a few seconds on the same object. */
    private void confirmDelete(Object object, Runnable action) {
        long now = System.currentTimeMillis();
        if (Objects.equals(pendingDelete, object) && now - pendingDeleteTime < CONFIRM_MS) {
            pendingDelete = null;
            action.run();
            rebuildRows();
        } else {
            pendingDelete = object;
            pendingDeleteTime = now;
        }
    }

    private boolean isPendingDelete(Object object) {
        return Objects.equals(pendingDelete, object) && System.currentTimeMillis() - pendingDeleteTime < CONFIRM_MS;
    }

    private void showOnMap(Waypoint waypoint) {
        mc.displayGuiScreen(GuiWorldMap.showing(waypoint));
    }

    /** A teammate's waypoint is not edited: it is saved as an own one first. */
    private void edit(Waypoint waypoint) {
        if (!waypoint.isForeign()) {
            mc.displayGuiScreen(GuiEditWaypoint.edit(this, waypoint));
        }
    }

    /** "Copy: Steve" for a copy of a teammate's waypoint. */
    static String copyLabel(Waypoint waypoint) {
        return waypoint.copyOfOwner == null || waypoint.copyOfOwner.isEmpty() ? Lang.format("wayfarmap.gui.copy_tag")
            : Lang.format("wayfarmap.gui.copy_of", waypoint.copyOfOwner);
    }

    /** Picks the waypoint in the list, taking off whatever keeps it from being listed. */
    private void reveal(Waypoint waypoint) {
        rebuildRows();
        if (!isListed(waypoint)) {
            pickedGroup = null;
            pickedDimension = ALL;
            searchField.setText("");
            searchText = "";
            collapsed.remove(waypoint.group == null ? UNGROUPED_KEY : waypoint.group);
            rebuildRows();
        }
        selected = waypoint;
        scrollTo(waypoint);
    }

    private static boolean canShare(Waypoint waypoint) {
        return !waypoint.isForeign() && !waypoint.death
            && (TeamWaypoints.INSTANCE.isAvailable() || waypoint.isShared());
    }

    private boolean canTeleport(Waypoint waypoint) {
        return Teleport.isAllowed() && mc.theWorld != null && waypoint.dimension == mc.theWorld.provider.dimensionId;
    }

    private void copyPlace(Waypoint waypoint) {
        setClipboardString(waypoint.x + " " + waypoint.y + " " + waypoint.z);
        copied = waypoint;
        copiedAt = System.currentTimeMillis();
    }

    // ---- Input ----

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE && choicesOpen) {
            choicesOpen = false;
            return;
        }
        if (searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchField.setFocused(false);
                return;
            }
            if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
                // From the search straight into what it found.
                searchField.setFocused(false);
                moveSelection(keyCode == Keyboard.KEY_UP ? -1 : 1);
                return;
            }
            searchField.textboxKeyTyped(typedChar, keyCode);
            if (!searchField.getText()
                .equals(searchText)) {
                setSearch(searchField.getText());
            }
            return;
        }
        if (groupField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                renamingGroup = null;
                groupField.setText("");
                groupField.setFocused(false);
                updateGroupButton();
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applyGroupField();
            } else {
                groupField.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                mc.displayGuiScreen(parent);
                return;
            case Keyboard.KEY_UP:
                moveSelection(-1);
                return;
            case Keyboard.KEY_DOWN:
                moveSelection(1);
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                if (selected != null) {
                    showOnMap(selected);
                }
                return;
            case Keyboard.KEY_DELETE:
                if (selected != null && !selected.isForeign()) {
                    final Waypoint waypoint = selected;
                    confirmDelete(waypoint, () -> WaypointManager.INSTANCE.removeWaypoint(waypoint));
                }
                return;
            case Keyboard.KEY_F2:
                if (selected != null) {
                    edit(selected);
                }
                return;
            default:
                break;
        }
        if (keyCode == Keyboard.KEY_F && isCtrlKeyDown()) {
            searchField.setFocused(true);
            return;
        }
        if (keyCode == Keyboard.KEY_N && isCtrlKeyDown()) {
            newWaypoint();
            return;
        }
        if (!isCtrlKeyDown() && (Character.isLetterOrDigit(typedChar) || typedChar == '-')) {
            // Typing anywhere searches.
            searchField.setFocused(true);
            searchField.textboxKeyTyped(typedChar, keyCode);
            setSearch(searchField.getText());
        }
    }

    /** Picks the waypoint above or below the one picked (the first one if none is). */
    private void moveSelection(int offset) {
        List<Waypoint> listed = new ArrayList<>();
        for (Row row : rows) {
            if (row.waypoint != null) {
                listed.add(row.waypoint);
            }
        }
        if (listed.isEmpty()) {
            return;
        }
        int index = selected == null ? -1 : listed.indexOf(selected);
        int next = index < 0 ? (offset > 0 ? 0 : listed.size() - 1)
            : Math.max(0, Math.min(listed.size() - 1, index + offset));
        selected = listed.get(next);
        scrollTo(selected);
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
        if (choicesOpen) {
            int most = Math.max(0, dimensionChoices().size() - CHOICES_SHOWN);
            choicesScroll = Math.max(0, Math.min(most, choicesScroll + (wheel > 0 ? -1 : 1)));
        } else if (mouseX < sideRight + 5) {
            sideScroll = clampSideScroll(sideScroll + (wheel > 0 ? -ENTRY_HEIGHT * 2 : ENTRY_HEIGHT * 2));
        } else {
            scroll = clampScroll(scroll + (wheel > 0 ? -WHEEL_STEP : WHEEL_STEP));
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (choicesOpen) {
            // While it is open, a click picks from the list or closes it.
            int index = choiceAt(mouseX, mouseY);
            List<Integer> choices = dimensionChoices();
            if (index >= 0 && index < choices.size() && button == 0) {
                int picked = choices.get(index);
                boolean own = mc.theWorld != null && picked == mc.theWorld.provider.dimensionId;
                // The player's own stays "the player's own", wherever they go next.
                pickedDimension = own ? null : picked;
                scroll = 0;
                rebuildRows();
            }
            if (index >= 0 || !dimensionButton.isMouseOver(mouseX, mouseY)) {
                choicesOpen = false;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
        groupField.mouseClicked(mouseX, mouseY, button);
        searchField.mouseClicked(mouseX, mouseY, button);
        if (button == 1) {
            // A right click on a card shows it on the map.
            Row row = rowAt(mouseX, mouseY);
            if (row != null && row.waypoint != null) {
                showOnMap(row.waypoint);
            }
            return;
        }
        if (button != 0) {
            return;
        }
        // The last one drawn is on top.
        Hit clicked = hitAt(mouseX, mouseY);
        if (clicked != null) {
            clicked.action.run();
            return;
        }
        Row row = rowAt(mouseX, mouseY);
        if (row != null && row.waypoint != null) {
            long now = System.currentTimeMillis();
            if (lastClicked == row.waypoint && now - lastClickTime < DOUBLE_CLICK_MS) {
                lastClicked = null;
                edit(row.waypoint);
                return;
            }
            lastClicked = row.waypoint;
            lastClickTime = now;
            selected = row.waypoint;
            pressedWaypoint = row.waypoint;
            pressX = mouseX;
            pressY = mouseY;
        } else if (row != null) {
            toggleCollapsed(row.key());
        }
    }

    private void toggleCollapsed(String key) {
        if (pickedGroup != null) {
            // A group picked alone always shows open.
            return;
        }
        if (!collapsed.remove(key)) {
            collapsed.add(key);
        }
        rebuildRows();
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button == 0) {
            finishDrag(mouseX, mouseY);
        }
    }

    /**
     * Drops the dragged waypoint into the group under the mouse, on the left or in the list. Called from whichever
     * notices the release first: the frame (mouse state) or the tick (mouse event), since GUI mouse events only
     * arrive 20 times per second.
     */
    private void finishDrag(int mouseX, int mouseY) {
        if (pressedWaypoint != null && draggingWaypoint) {
            String target = dropTargetAt(mouseX, mouseY);
            if (target != null) {
                String group = UNGROUPED_KEY.equals(target) ? null : target;
                if (!Objects.equals(group, pressedWaypoint.group)) {
                    WaypointManager.INSTANCE.moveToGroup(pressedWaypoint, group);
                    rebuildRows();
                }
            }
        }
        pressedWaypoint = null;
        draggingWaypoint = false;
    }

    /** The key of the group a waypoint dropped here would land in, or null for none. */
    private String dropTargetAt(int mouseX, int mouseY) {
        Entry entry = entryAt(mouseX, mouseY);
        if (entry != null) {
            return entry.key;
        }
        Row row = rowAt(mouseX, mouseY);
        return row != null ? row.key() : null;
    }

    private Row rowAt(int mouseX, int mouseY) {
        if (mouseX < listLeft || mouseX >= listRight || mouseY < listTop || mouseY >= listBottom) {
            return null;
        }
        double y = shownScroll.get() + mouseY - listTop;
        for (Row row : rows) {
            if (y >= row.y && y < row.y + row.height + GAP) {
                return row;
            }
        }
        return null;
    }

    private Entry entryAt(int mouseX, int mouseY) {
        if (mouseX < sideLeft || mouseX >= sideRight || mouseY < sideTop || mouseY >= sideBottom) {
            return null;
        }
        int index = (int) Math.floor((shownSideScroll.get() + mouseY - sideTop) / ENTRY_HEIGHT);
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    private void updateDrag(int mouseX, int mouseY) {
        if (pressedWaypoint == null) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            finishDrag(mouseX, mouseY);
            return;
        }
        if (!draggingWaypoint && Math.abs(mouseX - pressX) + Math.abs(mouseY - pressY) > 3) {
            draggingWaypoint = true;
        }
        // Scroll while holding the waypoint near the top or bottom of the list.
        long now = System.currentTimeMillis();
        if (draggingWaypoint && now - lastAutoScroll > 16 && mouseX >= listLeft) {
            if (mouseY < listTop + 14) {
                scroll = clampScroll(scroll - 6);
                lastAutoScroll = now;
            } else if (mouseY > listBottom - 14) {
                scroll = clampScroll(scroll + 6);
                lastAutoScroll = now;
            }
        }
    }

    @Override
    public void updateScreen() {
        groupField.updateCursorCounter();
        searchField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // ---- Drawing ----

    private double light(Object key, boolean on, double speed) {
        return lights.computeIfAbsent(key, k -> new Smooth(0))
            .update(on ? 1 : 0, speed);
    }

    /** Registers a clickable area, cut to the pane being drawn. */
    private void hit(int x0, int y0, int x1, int y1, Runnable action, String tip) {
        int cx0 = Math.max(x0, hitX0), cy0 = Math.max(y0, hitY0), cx1 = Math.min(x1, hitX1), cy1 = Math.min(y1, hitY1);
        if (cx1 > cx0 && cy1 > cy0) {
            hits.add(new Hit(cx0, cy0, cx1, cy1, action, tip));
        }
    }

    /** The clickable area under the mouse, the last one drawn first (it is on top), or null. */
    private Hit hitAt(int mouseX, int mouseY) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i)
                .contains(mouseX, mouseY)) {
                return hits.get(i);
            }
        }
        return null;
    }

    private void hitRegion(int x0, int y0, int x1, int y1) {
        hitX0 = x0;
        hitY0 = y0;
        hitX1 = x1;
        hitY1 = y1;
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        hits.clear();
        if (teamRevision != WaypointManager.INSTANCE.getTeamRevision()) {
            // Teammates' waypoints came or went meanwhile.
            rebuildRows();
        }
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        // A soft shadow around the window.
        Theme.fill(panelLeft - 2, panelTop + 2, panelRight + 2, panelBottom + 2, 0x30000000);
        Theme.fill(panelLeft - 1, panelTop + 1, panelRight + 1, panelBottom + 1, 0x50000000);
        Theme.panel(panelLeft, panelTop, panelRight, panelBottom);
        int total = WaypointManager.INSTANCE.getWaypoints()
            .size();
        String count = shownCount == total ? String.valueOf(total) : shownCount + " / " + total;
        WindowHeader.draw(
            fontRendererObj,
            panelLeft,
            panelTop,
            panelRight,
            panelRight - WindowHeader.CLOSE_ROOM,
            Icons.WAYPOINTS,
            Lang.format("wayfarmap.gui.waypoints"),
            Lang.format("wayfarmap.gui.list_hint"),
            Theme.TEXT_MUTED,
            count);

        updateDrag(mouseX, mouseY);
        String dropTarget = draggingWaypoint ? dropTargetAt(mouseX, mouseY) : null;

        drawSidebar(mouseX, mouseY, dropTarget);
        drawList(mouseX, mouseY, dropTarget);
        drawFooter();

        // The search box's magnifier, and the toolbar's buttons.
        searchField.drawTextBox();
        if (searchField.getText()
            .isEmpty()) {
            Icons.draw(
                Icons.SMALL_ZOOM,
                searchField.boxX + searchField.boxWidth - 12,
                searchField.boxY + 4,
                searchField.isFocused() ? Theme.ACCENT : Theme.TEXT_DISABLED);
        }
        groupField.drawTextBox();
        dimensionButton.displayString = Theme
            .ellipsize(fontRendererObj, choiceLabel(shownDimension()), dimensionButton.getWidth() - 20)
            + (choicesOpen ? " ▴" : " ▾");
        dimensionButton.active = choicesOpen;
        sortButton.displayString = Lang.format(sort.key);
        sortButton.active = sort != Sort.KEPT;
        super.drawScaled(mouseX, mouseY, partialTicks);
        if (groupActionButton.isMouseOver(mouseX, mouseY)) {
            tooltip = groupActionButton.tooltip;
        }
        if (choicesOpen) {
            drawChoices(mouseX, mouseY);
        } else if (!draggingWaypoint && tooltip == null) {
            Hit hit = hitAt(mouseX, mouseY);
            tooltip = hit != null ? hit.tooltip : null;
        }

        if (draggingWaypoint) {
            drawDragged(mouseX, mouseY, dropTarget);
        } else if (tooltip != null && !choicesOpen) {
            drawTooltip(tooltip, mouseX, mouseY);
        }
    }

    /** The groups' pane: a title, a line per group with its count, and the mark of the one picked. */
    private void drawSidebar(int mouseX, int mouseY, String dropTarget) {
        Theme.fill(sideLeft, sideTop - 14, sideRight, sideBottom, SIDEBAR);
        Theme.outline(sideLeft - 1, sideTop - 15, sideRight + 1, sideBottom + 1, Theme.BORDER);
        String title = Lang.format("wayfarmap.gui.groups")
            .toUpperCase(Locale.ROOT);
        Theme.text(fontRendererObj, title, sideLeft + 6, sideTop - 10, Theme.TEXT_MUTED);
        String groups = String.valueOf(
            WaypointManager.INSTANCE.getGroups()
                .size());
        Theme.text(
            fontRendererObj,
            groups,
            sideRight - 6 - fontRendererObj.getStringWidth(groups),
            sideTop - 10,
            Theme.TEXT_DISABLED);
        Theme.fill(sideLeft + 4, sideTop - 1, sideRight - 4, sideTop, Theme.BORDER);

        double shown = shownSideScroll.update(sideScroll, 16);
        hitRegion(sideLeft, sideTop, sideRight, sideBottom);
        Theme.clip(sideLeft, sideTop, sideRight, sideBottom);
        int pickedIndex = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (Objects.equals(entry.key, pickedGroup)) {
                pickedIndex = i;
            }
            int y = sideTop + (int) Math.round(i * ENTRY_HEIGHT - shown);
            if (y + ENTRY_HEIGHT < sideTop || y > sideBottom) {
                continue;
            }
            drawEntry(entry, y, mouseX, mouseY, dropTarget);
        }
        // The picked group's mark glides to it.
        double target = pickedIndex * ENTRY_HEIGHT;
        if (pickedMarkY.get() < 0) {
            pickedMarkY.set(target);
        }
        double markY = pickedMarkY.update(target, 18);
        int my = sideTop + (int) Math.round(markY - shown);
        Theme.fill(sideLeft, my + 3, sideLeft + 2, my + ENTRY_HEIGHT - 3, Theme.ACCENT);
        Theme.unclip();
        if (entries.size() * ENTRY_HEIGHT > sideBottom - sideTop) {
            double most = Math.max(1, entries.size() * ENTRY_HEIGHT - (sideBottom - sideTop));
            Theme.scrollbar(
                sideRight - 3,
                sideTop,
                sideBottom,
                sideBottom - sideTop,
                entries.size() * ENTRY_HEIGHT,
                shown / most,
                Theme.inside(mouseX, mouseY, sideLeft, sideTop, sideRight, sideBottom));
        }
    }

    private void drawEntry(final Entry entry, int y, int mouseX, int mouseY, String dropTarget) {
        boolean picked = Objects.equals(entry.key, pickedGroup);
        boolean hovered = !draggingWaypoint
            && Theme.inside(mouseX, mouseY, sideLeft, Math.max(y, sideTop), sideRight, y + ENTRY_HEIGHT);
        boolean dropping = draggingWaypoint && entry.key != null && entry.key.equals(dropTarget);
        double lit = light(entry.key == null ? "\u0000all" : entry.key, hovered || dropping, 20);
        int background = picked ? CARD_SELECTED : Theme.blend(SIDEBAR, CARD_HOVER, lit);
        Theme.fill(sideLeft + 1, y + 1, sideRight - 1, y + ENTRY_HEIGHT - 1, background);
        if (dropping) {
            Theme.outline(sideLeft + 1, y + 1, sideRight - 1, y + ENTRY_HEIGHT - 1, Theme.ACCENT);
        }
        hit(sideLeft, y, sideRight, y + ENTRY_HEIGHT, () -> pickGroup(entry.key), null);

        boolean visible = entry.key == null || isGroupVisible(entry.key);
        int color = groupColor(entry.key);
        int x = sideLeft + 8;
        int cy = y + ENTRY_HEIGHT / 2;
        if (entry.key == null) {
            Icons.draw(Icons.SMALL_FLAG, x, cy - 3, picked ? Theme.ACCENT : Theme.TEXT_MUTED);
        } else {
            // The group's color, as a little gem.
            int dot = visible ? color : Theme.blend(color, SIDEBAR, 0.6);
            Theme.fill(x + 1, cy - 3, x + 6, cy + 3, dot);
            Theme.fill(x, cy - 2, x + 7, cy + 2, dot);
            Theme.fill(x + 2, cy - 2, x + 4, cy - 1, Theme.blend(dot, 0xFFFFFFFF, 0.5));
        }
        x += 12;

        // On the right: the count, or the group's buttons while the mouse is on it.
        int right = sideRight - 6;
        boolean showActions = hovered && entry.key != null;
        if (showActions) {
            right = drawEntryActions(entry, right, y, mouseX, mouseY);
        } else {
            String count = String.valueOf(entry.count);
            int w = fontRendererObj.getStringWidth(count) + 6;
            Theme.fill(right - w, cy - 5, right, cy + 5, picked ? Theme.ACCENT_DIM : Theme.CONTROL);
            Theme.text(fontRendererObj, count, right - w + 3, cy - 4, picked ? Theme.TEXT : Theme.TEXT_MUTED);
            right -= w + 3;
            if (!visible) {
                drawEyeOff(right - 8, cy - 2, Theme.TEXT_DISABLED);
                right -= 10;
            }
        }
        String name = Theme.ellipsize(fontRendererObj, groupTitle(entry.key), right - 3 - x);
        int textColor = !visible ? Theme.TEXT_DISABLED : picked ? Theme.TEXT : Theme.blend(0xFFC0C7D0, Theme.TEXT, lit);
        if (picked) {
            fontRendererObj.drawStringWithShadow(name, x, cy - 4, textColor);
        } else {
            fontRendererObj.drawString(name, x, cy - 4, textColor);
        }
    }

    /** A group's buttons, from the right: delete, move down, move up, rename, show; returns where they start. */
    private int drawEntryActions(final Entry entry, int right, int y, int mouseX, int mouseY) {
        final WaypointManager manager = WaypointManager.INSTANCE;
        final WaypointGroup group = entry.group;
        int ay = y + (ENTRY_HEIGHT - ENTRY_ACTION) / 2;
        int x = right - ENTRY_ACTION;
        if (group != null) {
            boolean pending = isPendingDelete(group);
            entryButton(
                x,
                ay,
                Icons.SMALL_TRASH,
                pending ? Theme.TEXT : Theme.DANGER,
                pending ? Theme.DANGER : 0,
                mouseX,
                mouseY,
                () -> confirmDelete(group, () -> manager.removeGroup(group)),
                Lang.format(pending ? "wayfarmap.gui.delete_group_sure" : "wayfarmap.gui.delete_group"));
            x -= ENTRY_ACTION + 1;
            entryButton(x, ay, SMALL_DOWN, Theme.TEXT_MUTED, 0, mouseX, mouseY, () -> {
                manager.moveGroup(group, 1);
                rebuildRows();
            }, Lang.format("wayfarmap.gui.move_down"));
            x -= ENTRY_ACTION + 1;
            entryButton(x, ay, Icons.SMALL_UP, Theme.TEXT_MUTED, 0, mouseX, mouseY, () -> {
                manager.moveGroup(group, -1);
                rebuildRows();
            }, Lang.format("wayfarmap.gui.move_up"));
            x -= ENTRY_ACTION + 1;
            entryButton(
                x,
                ay,
                Icons.SMALL_PENCIL,
                Theme.TEXT_MUTED,
                0,
                mouseX,
                mouseY,
                () -> startRenaming(group),
                Lang.format("wayfarmap.gui.rename_group"));
            x -= ENTRY_ACTION + 1;
        }
        boolean visible = isGroupVisible(entry.key);
        int ex = x, ey = ay;
        boolean over = Theme.inside(mouseX, mouseY, ex, ey, ex + ENTRY_ACTION, ey + ENTRY_ACTION);
        Theme.fill(ex, ey, ex + ENTRY_ACTION, ey + ENTRY_ACTION, over ? Theme.CONTROL_HOVER : Theme.CONTROL);
        if (visible) {
            Icons.draw(Icons.SMALL_EYE, ex + 2, ey + 3, over ? Theme.TEXT : Theme.ACCENT);
        } else {
            drawEyeOff(ex + 2, ey + 3, over ? Theme.TEXT : Theme.TEXT_DISABLED);
        }
        hit(
            ex,
            ey,
            ex + ENTRY_ACTION,
            ey + ENTRY_ACTION,
            () -> toggleGroup(entry.key),
            Lang.format(visible ? "wayfarmap.gui.hide_group" : "wayfarmap.gui.show_group"));
        return x - 2;
    }

    private void entryButton(int x, int y, String[] icon, int color, int background, int mouseX, int mouseY,
        Runnable action, String tip) {
        boolean over = Theme.inside(mouseX, mouseY, x, y, x + ENTRY_ACTION, y + ENTRY_ACTION);
        int fill = background != 0 ? background : over ? Theme.CONTROL_HOVER : Theme.CONTROL;
        Theme.fill(x, y, x + ENTRY_ACTION, y + ENTRY_ACTION, fill);
        int ix = x + (ENTRY_ACTION - Icons.width(icon)) / 2, iy = y + (ENTRY_ACTION - icon.length) / 2;
        Icons.draw(icon, ix, iy, over && background == 0 ? Theme.TEXT : color);
        hit(x, y, x + ENTRY_ACTION, y + ENTRY_ACTION, action, tip);
    }

    /** The eye, struck through: hidden. */
    private static void drawEyeOff(int x, int y, int color) {
        Icons.draw(Icons.SMALL_EYE, x, y, color);
        for (int i = 0; i < 7; i++) {
            Theme.fill(x + i, y - 1 + i, x + i + 1, y + i, color);
        }
    }

    /** The waypoints' list: a section title per group and a card per waypoint, or a word when there are none. */
    private void drawList(int mouseX, int mouseY, String dropTarget) {
        Theme.fill(listLeft - 1, listTop - 1, listRight + 1, listBottom + 1, 0xFF0D1014);
        Theme.outline(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, Theme.BORDER);
        double shown = shownScroll.update(scroll, 16);
        boolean mouseInList = Theme.inside(mouseX, mouseY, listLeft, listTop, listRight, listBottom);
        hitRegion(listLeft, listTop, listRight, listBottom);
        Theme.clip(listLeft, listTop, listRight, listBottom);
        int scrollRoom = contentHeight > listBottom - listTop ? 6 : 0;
        int x0 = listLeft + 3, x1 = listRight - 3 - scrollRoom;
        for (Row row : rows) {
            int y = listTop + 3 + (int) Math.round(row.y - shown);
            if (y + row.height < listTop || y > listBottom) {
                continue;
            }
            boolean hovered = mouseInList && !draggingWaypoint
                && Theme.inside(mouseX, mouseY, x0, y, x1, y + row.height);
            boolean dropping = dropTarget != null && dropTarget.equals(row.key());
            if (row.waypoint == null) {
                drawSection(row, x0, x1, y, mouseX, mouseY, hovered, dropping);
            } else {
                drawCard(row, x0, x1, y, mouseX, mouseY, hovered, dropping);
            }
        }
        Theme.unclip();
        // Soft edges where the list goes on, above and below.
        if (shown > 1) {
            Theme.fill(listLeft, listTop, listRight, listTop + 1, 0x80000000);
            Theme.fill(listLeft, listTop + 1, listRight, listTop + 3, 0x30000000);
        }
        if (shown < contentHeight - (listBottom - listTop) - 1) {
            Theme.fill(listLeft, listBottom - 3, listRight, listBottom - 1, 0x30000000);
            Theme.fill(listLeft, listBottom - 1, listRight, listBottom, 0x80000000);
        }
        if (shownCount == 0 && !hasCards()) {
            drawEmpty();
        }
        if (scrollRoom > 0) {
            double most = Math.max(1, contentHeight - (listBottom - listTop));
            Theme.scrollbar(
                listRight - 5,
                listTop + 2,
                listBottom - 2,
                listBottom - listTop,
                contentHeight,
                shown / most,
                mouseInList);
        }
    }

    private boolean hasCards() {
        for (Row row : rows) {
            if (row.waypoint != null) {
                return true;
            }
        }
        return false;
    }

    /** What the list says when it shows no waypoint: a big flag and why. */
    private void drawEmpty() {
        int cx = (listLeft + listRight) / 2, cy = (listTop + listBottom) / 2 - 10;
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy - 22, 0);
        GL11.glScalef(3, 3, 1);
        Icons.draw(Icons.WAYPOINTS, -Icons.width(Icons.WAYPOINTS) / 2, -Icons.WAYPOINTS.length / 2, Theme.BORDER);
        GL11.glPopMatrix();
        String text;
        if (!searchText.isEmpty()) {
            text = Lang.format("wayfarmap.gui.nothing_found");
        } else if (pickedGroup != null) {
            text = Lang.format("wayfarmap.gui.empty_group");
        } else {
            text = Lang.format("wayfarmap.gui.no_waypoints_here");
        }
        Theme.centered(fontRendererObj, text, cx, cy + 6, Theme.TEXT_MUTED);
        Theme.centered(fontRendererObj, Lang.format("wayfarmap.gui.empty_hint"), cx, cy + 18, Theme.TEXT_DISABLED);
    }

    /** A group's section title: its color, name and count; a click opens or closes it, and it can be cleared. */
    private void drawSection(Row row, int x0, int x1, int y, int mouseX, int mouseY, boolean hovered,
        boolean dropping) {
        String key = row.key();
        boolean visible = isGroupVisible(key);
        int color = groupColor(key);
        double lit = light("\u0000section:" + key, hovered, 20);
        Theme.fill(x0, y, x1, y + HEADER_HEIGHT, Theme.blend(SECTION, CARD, lit));
        // The group's color fades out along the bottom line.
        int lineWidth = x1 - x0;
        for (int i = 0; i < 4; i++) {
            int from = x0 + lineWidth * i / 4, to = x0 + lineWidth * (i + 1) / 4;
            Theme.fill(from, y + HEADER_HEIGHT - 1, to, y + HEADER_HEIGHT, Theme.blend(color, SECTION, i / 4.0 + 0.1));
        }
        if (dropping) {
            Theme.outline(x0, y, x1, y + HEADER_HEIGHT, Theme.ACCENT);
            String drop = Lang.format("wayfarmap.gui.drop_here");
            Theme.text(fontRendererObj, drop, x1 - 6 - fontRendererObj.getStringWidth(drop), y + 6, Theme.ACCENT);
        }
        int x = x0 + 6;
        int cy = y + HEADER_HEIGHT / 2;
        if (pickedGroup == null) {
            boolean isCollapsed = collapsed.contains(key) && searchText.isEmpty();
            String[] arrow = isCollapsed ? Icons.SECTION_CLOSED : Icons.SECTION_OPEN;
            int arrowColor = hovered ? Theme.TEXT : Theme.TEXT_MUTED;
            Icons.draw(arrow, x + (5 - Icons.width(arrow)) / 2, cy - arrow.length / 2, arrowColor);
            x += 10;
        }
        Theme.disc(x + 3, cy, 3, visible ? color : Theme.blend(color, SECTION, 0.6));
        x += 11;
        String title = groupTitle(key);
        fontRendererObj.drawStringWithShadow(title, x, cy - 4, visible ? Theme.TEXT : Theme.TEXT_DISABLED);
        x += fontRendererObj.getStringWidth(title) + 6;
        String count = String.valueOf(row.matching.size());
        Theme.text(fontRendererObj, count, x, cy - 4, Theme.TEXT_MUTED);
        x += fontRendererObj.getStringWidth(count) + 6;
        if (row.group != null && row.group.shareNew) {
            Icons.draw(Icons.SMALL_PERSON, x, cy - 3, Theme.ACCENT);
            x += 11;
        }
        if (!visible) {
            drawEyeOff(x, cy - 2, Theme.TEXT_DISABLED);
            String hidden = Lang.format("wayfarmap.gui.hidden");
            Theme.text(fontRendererObj, hidden, x + 10, cy - 4, Theme.TEXT_DISABLED);
        }
        // The buttons, from the right: clearing the group, then its sharing with the team.
        int buttonsRight = x1 - 4;
        if (!dropping && lit > 0.05 && !row.matching.isEmpty()) {
            // Deletes all of the group's waypoints the list shows (the filters apply), after a second click.
            final String pendingKey = "\u0000clear:" + key;
            final List<Waypoint> waypoints = row.matching;
            boolean pending = isPendingDelete(pendingKey);
            String label = Lang.format(pending ? "wayfarmap.gui.confirm" : "wayfarmap.gui.clear_group");
            int w = fontRendererObj.getStringWidth(label) + 10;
            int bx1 = x1 - 4, bx0 = bx1 - w, by = y + 4;
            buttonsRight = bx0 - 3;
            boolean over = Theme.inside(mouseX, mouseY, bx0, by, bx1, by + 12);
            int fill = pending ? Theme.DANGER : over ? Theme.CONTROL_HOVER : Theme.CONTROL;
            Theme.fill(bx0, by, bx1, by + 12, Theme.blend(SECTION, fill, lit));
            int labelColor = Theme.blend(SECTION, pending ? Theme.TEXT : Theme.DANGER, lit);
            Theme.text(fontRendererObj, label, bx0 + 5, by + 2, labelColor);
            hit(
                bx0,
                by,
                bx1,
                by + 12,
                () -> confirmDelete(pendingKey, () -> WaypointManager.INSTANCE.removeWaypoints(waypoints)),
                Lang.format("wayfarmap.gui.clear_group_hint"));
        }
        if (!dropping && lit > 0.05 && row.group != null && TeamWaypoints.INSTANCE.isAvailable()) {
            drawSharing(row.group, buttonsRight, y + 3, mouseX, mouseY, lit, Theme.blend(SECTION, CARD, lit));
        }
    }

    /**
     * A group's sharing with the team, from the right: share all of its waypoints now, show or hide teammates'
     * waypoints coming into it (or as the settings say), share the waypoints made in it from now on.
     */
    private void drawSharing(final WaypointGroup group, int right, int y, int mouseX, int mouseY, double lit,
        int background) {
        final WaypointManager manager = WaypointManager.INSTANCE;
        int x = right - ACTION_SIZE;
        action(
            x,
            y,
            Icons.SMALL_UP,
            Theme.TEXT,
            0,
            lit,
            background,
            mouseX,
            mouseY,
            () -> manager.shareGroup(group),
            Lang.format("wayfarmap.gui.share_group_all"));
        x -= ACTION_STEP;
        final int incoming = group.incoming;
        boolean asSettings = incoming == WaypointGroup.INCOMING_DEFAULT;
        action(
            x,
            y,
            incoming == WaypointGroup.INCOMING_HIDDEN ? SMALL_EYE_OFF : Icons.SMALL_EYE,
            asSettings ? Theme.TEXT_MUTED : Theme.TEXT,
            asSettings ? 0 : Theme.ACCENT_DIM,
            lit,
            background,
            mouseX,
            mouseY,
            () -> manager.setIncoming(group, (incoming + 1) % 3),
            Lang.format("wayfarmap.gui.incoming." + incoming));
        x -= ACTION_STEP;
        action(
            x,
            y,
            Icons.SMALL_PERSON,
            group.shareNew ? Theme.TEXT : Theme.TEXT_MUTED,
            group.shareNew ? Theme.ACCENT_DIM : 0,
            lit,
            background,
            mouseX,
            mouseY,
            () -> manager.setShareNew(group, !group.shareNew),
            Lang.format(group.shareNew ? "wayfarmap.gui.share_new_on" : "wayfarmap.gui.share_new_off"));
    }

    /**
     * A waypoint's card: a stripe of its color, the eye that shows or hides it, its icon on a tile, its name over its
     * place, and on the right how far it is with an arrow pointing to it; with the mouse on it, its buttons instead.
     */
    private void drawCard(Row row, int x0, int x1, int y, int mouseX, int mouseY, boolean hovered, boolean dropping) {
        final Waypoint waypoint = row.waypoint;
        final WaypointManager manager = WaypointManager.INSTANCE;
        boolean isSelected = waypoint == selected;
        boolean dragged = draggingWaypoint && waypoint == pressedWaypoint;
        double lit = light(waypoint, hovered || isSelected, 18);
        int y1 = y + CARD_HEIGHT;
        int background = isSelected ? Theme.blend(CARD_SELECTED, 0xFF24364F, hovered ? 1 : 0)
            : Theme.blend(CARD, CARD_HOVER, lit);
        Theme.fill(x0, y, x1, y1, background);
        Theme.fill(x0, y1, x1, y1 + 1, 0x60000000);
        if (isSelected) {
            Theme.outline(x0, y, x1, y1, Theme.ACCENT_DIM);
        } else if (dropping) {
            Theme.outline(x0, y, x1, y1, 0x804C9AFF);
        }
        int color = waypointColor(waypoint);
        boolean shown = manager.isVisible(waypoint);
        Theme.fill(x0, y, x0 + 3, y1, shown ? color : Theme.blend(color, background, 0.65));

        // The eye: shows or hides the waypoint.
        int ex = x0 + 8, ey = y + (CARD_HEIGHT - 5) / 2;
        boolean overEye = Theme.inside(mouseX, mouseY, ex - 3, ey - 4, ex + 10, ey + 9);
        if (waypoint.enabled) {
            Icons.draw(Icons.SMALL_EYE, ex, ey, overEye ? Theme.TEXT : Theme.ACCENT);
        } else {
            drawEyeOff(ex, ey, overEye ? Theme.TEXT : Theme.TEXT_DISABLED);
        }
        hit(ex - 3, ey - 4, ex + 10, ey + 9, () -> {
            waypoint.enabled = !waypoint.enabled;
            manager.waypointChanged();
        }, Lang.format(waypoint.enabled ? "wayfarmap.gui.hide_waypoint" : "wayfarmap.gui.show_waypoint"));

        // Its icon, on a tile.
        int tx = x0 + 22, ty = y + 4;
        Theme.fill(tx, ty, tx + 20, ty + 20, TILE);
        Theme.outline(tx, ty, tx + 20, ty + 20, Theme.blend(Theme.BORDER, color, 0.35 + 0.4 * lit));
        WaypointRenderer.drawMapMarker(waypoint, tx + 10, ty + 10, 12f, false);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        int textX = tx + 26;
        // The right side: how far, or the buttons; the name gets what is left.
        int actionsWidth = actionCount(waypoint) * ACTION_STEP;
        String distance = distanceText(waypoint);
        int distanceWidth = distance == null ? 0 : fontRendererObj.getStringWidth(distance) + 18;
        int rightRoom = Math.max(actionsWidth, distanceWidth) + 8;
        int textRoom = Math.max(20, x1 - rightRoom - textX);

        String name = waypoint.name.isEmpty() ? "-" : Theme.ellipsize(fontRendererObj, waypoint.name, textRoom);
        if (shown) {
            fontRendererObj.drawStringWithShadow(name, textX, y + 5, Theme.TEXT);
        } else {
            fontRendererObj.drawString(name, textX, y + 5, Theme.TEXT_DISABLED);
        }
        drawPlaceLine(waypoint, textX, y + 16, textRoom, background);

        if (lit < 0.99 && distance != null) {
            drawDistance(waypoint, distance, x1 - 6, y, Theme.blend(Theme.TEXT_MUTED, background, lit));
        }
        if (lit > 0.01 && !dragged) {
            drawActions(waypoint, x1 - 5, y + (CARD_HEIGHT - ACTION_SIZE) / 2, mouseX, mouseY, lit, background);
        }
        if (copied == waypoint && System.currentTimeMillis() - copiedAt < COPIED_MS) {
            String done = Lang.format("wayfarmap.gui.copied");
            int w = fontRendererObj.getStringWidth(done) + 8;
            int cx = x1 - actionsWidth - w - 10;
            Theme.fill(cx, y + 8, cx + w, y + 20, Theme.SUCCESS);
            Theme.text(fontRendererObj, done, cx + 4, y + 10, 0xFF0D1014);
        }
        if (dragged) {
            // It is in the hand: only its ghost stays here.
            Theme.fill(x0, y, x1, y1, 0xA00D1014);
            Theme.outline(x0, y, x1, y1, Theme.ACCENT_DIM);
        }
    }

    /** The waypoint's place under its name, then its dimension, or how long ago the player died there. */
    private void drawPlaceLine(Waypoint waypoint, int x, int y, int room, int background) {
        int right = x + room;
        String[] parts = { "X", String.valueOf(waypoint.x), "Y", String.valueOf(waypoint.y), "Z",
            String.valueOf(waypoint.z) };
        for (int i = 0; i < parts.length && x < right; i++) {
            boolean label = i % 2 == 0;
            String part = parts[i];
            int w = fontRendererObj.getStringWidth(part);
            if (x + w > right) {
                break;
            }
            Theme.text(fontRendererObj, part, x, y, label ? Theme.TEXT_DISABLED : Theme.TEXT_MUTED);
            x += w + (label ? 2 : 6);
        }
        String tag = null;
        int tagColor = Theme.TEXT_MUTED;
        if (mc.theWorld != null && waypoint.dimension != mc.theWorld.provider.dimensionId) {
            tag = dimensionName(waypoint.dimension);
            tagColor = 0xFFB37FEB;
        } else if (waypoint.death) {
            tag = WaypointRenderer.ageSuffix(waypoint)
                .trim();
            tagColor = Theme.DANGER;
        }
        x = drawTag(tag, tagColor, x, y, right, background);
        // Whose it is, or that the team sees it.
        String team = waypoint.isForeign() ? waypoint.ownerName
            : waypoint.isShared() ? Lang.format("wayfarmap.gui.shared") : null;
        x = drawTag(team, Theme.ACCENT, x, y, right, background);
        if (waypoint.copyOf != null && !waypoint.isForeign()) {
            drawTag(copyLabel(waypoint), 0xFFF2C14E, x, y, right, background);
        }
    }

    /** A word on a tinted plate, if there is room for it; returns where the next one goes. */
    private int drawTag(String tag, int color, int x, int y, int right, int background) {
        if (tag == null || tag.isEmpty() || right - x <= 24) {
            return x;
        }
        tag = Theme.ellipsize(fontRendererObj, tag, right - x - 6);
        int w = fontRendererObj.getStringWidth(tag) + 6;
        Theme.fill(x, y - 2, x + w, y + 9, Theme.blend(background, color, 0.18));
        Theme.text(fontRendererObj, tag, x + 3, y, color);
        return x + w + 4;
    }

    /** "123 m" from the player, null in another dimension. */
    private String distanceText(Waypoint waypoint) {
        double distance = distanceTo(waypoint);
        if (distance == Double.MAX_VALUE) {
            return null;
        }
        if (distance >= 10000) {
            return String.format(Locale.ROOT, "%.1f km", distance / 1000);
        }
        return (int) distance + " m";
    }

    /** How far the waypoint is, with an arrow that turns as the player does, pointing to it. */
    private void drawDistance(Waypoint waypoint, String distance, int right, int y, int color) {
        int ax = right - 4, ay = y + CARD_HEIGHT / 2;
        double dx = waypoint.x + 0.5 - mc.thePlayer.posX, dz = waypoint.z + 0.5 - mc.thePlayer.posZ;
        boolean here = dx * dx + dz * dz < 4;
        if (here) {
            Theme.disc(ax, ay, 3, Theme.SUCCESS);
        } else {
            // The player looks along yaw; the waypoint is that much to the right of it.
            double toward = Math.toDegrees(Math.atan2(-dx, dz));
            double turn = toward - mc.thePlayer.rotationYaw;
            GL11.glPushMatrix();
            GL11.glTranslatef(ax, ay, 0);
            GL11.glRotatef((float) turn, 0, 0, 1);
            Icons.draw(ARROW, -3, -4, Theme.blend(color, Theme.ACCENT, 0.7));
            GL11.glPopMatrix();
        }
        int w = fontRendererObj.getStringWidth(distance);
        Theme.text(fontRendererObj, distance, ax - 9 - w, ay - 4, color);
    }

    private int actionCount(Waypoint waypoint) {
        int count = waypoint.isForeign() ? 4 : canShare(waypoint) ? 6 : 5;
        if (WaypointManager.INSTANCE.getOriginal(waypoint) != null) {
            count++;
        }
        return canTeleport(waypoint) ? count + 1 : count;
    }

    /**
     * The card's buttons, from the right: delete, edit, share with the team, share in the chat, copy, on the map and
     * teleport; for a teammate's waypoint saving it as an own one instead of the first three, and for such a copy
     * going to the waypoint it was made of. Faded in by lit.
     */
    private void drawActions(final Waypoint waypoint, int right, int y, int mouseX, int mouseY, double lit,
        int background) {
        int x = right - ACTION_SIZE;
        if (waypoint.isForeign()) {
            action(x, y, Icons.SMALL_DISK, Theme.SUCCESS, 0, lit, background, mouseX, mouseY, () -> {
                selected = WaypointManager.INSTANCE.saveAsOwn(waypoint);
                rebuildRows();
            }, Lang.format("wayfarmap.gui.save_own_hint"));
            x -= ACTION_STEP;
        } else {
            boolean pending = isPendingDelete(waypoint);
            action(
                x,
                y,
                Icons.SMALL_TRASH,
                pending ? Theme.TEXT : Theme.DANGER,
                pending ? Theme.DANGER : 0,
                lit,
                background,
                mouseX,
                mouseY,
                () -> confirmDelete(waypoint, () -> WaypointManager.INSTANCE.removeWaypoint(waypoint)),
                Lang.format(pending ? "wayfarmap.gui.delete_sure" : "wayfarmap.gui.delete"));
            x -= ACTION_STEP;
            action(
                x,
                y,
                Icons.SMALL_PENCIL,
                Theme.TEXT,
                0,
                lit,
                background,
                mouseX,
                mouseY,
                () -> edit(waypoint),
                Lang.format("wayfarmap.gui.edit"));
            x -= ACTION_STEP;
            if (canShare(waypoint)) {
                final boolean shared = waypoint.isShared();
                action(
                    x,
                    y,
                    Icons.SMALL_PERSON,
                    shared ? Theme.TEXT : Theme.TEXT_MUTED,
                    shared ? Theme.ACCENT_DIM : 0,
                    lit,
                    background,
                    mouseX,
                    mouseY,
                    () -> {
                        WaypointManager.setShared(waypoint, !shared);
                        WaypointManager.INSTANCE.waypointChanged();
                    },
                    Lang.format(shared ? "wayfarmap.gui.team_share_off" : "wayfarmap.gui.team_share_on"));
                x -= ACTION_STEP;
            }
            final Waypoint original = WaypointManager.INSTANCE.getOriginal(waypoint);
            if (original != null) {
                action(
                    x,
                    y,
                    Icons.SMALL_PIN,
                    0xFFF2C14E,
                    0,
                    lit,
                    background,
                    mouseX,
                    mouseY,
                    () -> reveal(original),
                    Lang.format("wayfarmap.gui.go_original"));
                x -= ACTION_STEP;
            }
        }
        action(
            x,
            y,
            Icons.SMALL_CHAT,
            Theme.TEXT,
            0,
            lit,
            background,
            mouseX,
            mouseY,
            () -> WaypointShare.share(waypoint),
            Lang.format("wayfarmap.gui.share_chat"));
        x -= ACTION_STEP;
        action(
            x,
            y,
            Icons.SMALL_COPY,
            Theme.TEXT,
            0,
            lit,
            background,
            mouseX,
            mouseY,
            () -> copyPlace(waypoint),
            Lang.format("wayfarmap.gui.copy_coords"));
        x -= ACTION_STEP;
        action(
            x,
            y,
            SMALL_MAP,
            Theme.ACCENT,
            0,
            lit,
            background,
            mouseX,
            mouseY,
            () -> showOnMap(waypoint),
            Lang.format("wayfarmap.gui.show_on_map_hint"));
        x -= ACTION_STEP;
        // Teleporting needs /tp permission and the same dimension.
        if (canTeleport(waypoint)) {
            action(x, y, SMALL_TELEPORT, Theme.SUCCESS, 0, lit, background, mouseX, mouseY, () -> {
                mc.displayGuiScreen(null);
                Teleport.teleport(waypoint.x, waypoint.y, waypoint.z);
            }, Lang.format("wayfarmap.gui.teleport"));
        }
    }

    private void action(int x, int y, String[] icon, int color, int fill, double lit, int background, int mouseX,
        int mouseY, Runnable run, String tip) {
        boolean over = Theme.inside(mouseX, mouseY, x, y, x + ACTION_SIZE, y + ACTION_SIZE);
        int box = fill != 0 ? fill : over ? Theme.CONTROL_HOVER : Theme.CONTROL;
        Theme.fill(x, y, x + ACTION_SIZE, y + ACTION_SIZE, Theme.blend(background, box, lit));
        if (over) {
            Theme.outline(x, y, x + ACTION_SIZE, y + ACTION_SIZE, Theme.blend(background, color, lit));
        }
        int ix = x + (ACTION_SIZE - Icons.width(icon)) / 2, iy = y + (ACTION_SIZE - icon.length) / 2;
        Icons.draw(icon, ix, iy, Theme.blend(background, over && fill == 0 ? Theme.TEXT : color, lit));
        if (lit > 0.5) {
            hit(x, y, x + ACTION_SIZE, y + ACTION_SIZE, run, tip);
        }
    }

    /** Under the panes: how many waypoints and groups, and how many of them are hidden. */
    private void drawFooter() {
        WaypointManager manager = WaypointManager.INSTANCE;
        int hidden = 0;
        for (Waypoint waypoint : manager.getWaypoints()) {
            if (!manager.isVisible(waypoint)) {
                hidden++;
            }
        }
        int y = panelBottom - 26 + 5;
        String stats = Lang.format(
            "wayfarmap.gui.waypoint_stats",
            manager.getWaypoints()
                .size(),
            manager.getGroups()
                .size(),
            hidden);
        int room = listRight - 110 * 2 - 12 - sideLeft;
        Theme.text(fontRendererObj, Theme.ellipsize(fontRendererObj, stats, room), sideLeft, y, Theme.TEXT_MUTED);
    }

    /** The dragged waypoint follows the mouse, saying where it would land. */
    private void drawDragged(int mouseX, int mouseY, String target) {
        String name = pressedWaypoint.name.isEmpty() ? "-"
            : Theme.ellipsize(fontRendererObj, pressedWaypoint.name, 140);
        String where = target == null ? null : "→ " + groupTitle(target);
        int whereWidth = where == null ? 0 : fontRendererObj.getStringWidth(where);
        int w = Math.max(fontRendererObj.getStringWidth(name), whereWidth) + 26;
        int h = where == null ? 16 : 26;
        int x = mouseX + 8, y = mouseY - 8;
        Theme.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x60000000);
        Theme.fill(x, y, x + w, y + h, Theme.PANEL_ALT | 0xFF000000);
        Theme.outline(x, y, x + w, y + h, Theme.ACCENT);
        Theme.fill(x, y, x + 2, y + h, waypointColor(pressedWaypoint));
        WaypointRenderer.drawMapMarker(pressedWaypoint, x + 11, y + 8, 9f, false);
        GL11.glDisable(GL11.GL_LIGHTING);
        fontRendererObj.drawStringWithShadow(name, x + 20, y + 4, Theme.TEXT);
        if (where != null) {
            Theme.text(fontRendererObj, where, x + 20, y + 15, groupColor(target));
        }
    }

    private void drawTooltip(String text, int mouseX, int mouseY) {
        int w = fontRendererObj.getStringWidth(text) + 8;
        int x = Math.min(mouseX + 8, width - w - 2), y = mouseY - 16;
        if (y < 2) {
            y = mouseY + 14;
        }
        Theme.fill(x + 1, y + 1, x + w + 1, y + 13, 0x60000000);
        Theme.fill(x, y, x + w, y + 12, 0xF0101418);
        Theme.outline(x, y, x + w, y + 12, Theme.BORDER);
        Theme.text(fontRendererObj, text, x + 4, y + 2, Theme.TEXT);
    }

    /** The open list of dimensions under its button, over everything; the one shown is marked. */
    private void drawChoices(int mouseX, int mouseY) {
        List<Integer> choices = dimensionChoices();
        int x0 = dimensionButton.xPosition, x1 = x0 + dimensionButton.getWidth();
        int y0 = dimensionButton.yPosition + 17;
        int shown = Math.min(CHOICES_SHOWN, choices.size());
        int y1 = y0 + shown * CHOICE_ROW;
        Theme.fill(x0 + 2, y0 + 2, x1 + 2, y1 + 2, 0x60000000);
        Theme.fill(x0, y0, x1, y1, Theme.PANEL | 0xFF000000);
        Theme.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, Theme.BORDER);
        int current = shownDimension(), hovered = choiceAt(mouseX, mouseY);
        int own = mc.theWorld != null ? mc.theWorld.provider.dimensionId : ALL;
        for (int line = 0; line < shown; line++) {
            int index = line + choicesScroll;
            int dimension = choices.get(index);
            int y = y0 + line * CHOICE_ROW;
            if (dimension == current) {
                Theme.fill(x0, y, x1, y + CHOICE_ROW, 0x334C9AFF);
                Theme.fill(x0, y, x0 + 2, y + CHOICE_ROW, Theme.ACCENT);
            } else if (index == hovered) {
                Theme.fill(x0, y, x1, y + CHOICE_ROW, Theme.ROW_HOVER);
            }
            String label = Theme.ellipsize(fontRendererObj, choiceLabel(dimension), x1 - x0 - 18);
            Theme.text(fontRendererObj, label, x0 + 6, y + 3, dimension == current ? Theme.ACCENT : Theme.TEXT);
            if (dimension == own) {
                // Where the player is.
                Theme.disc(x1 - 7, y + CHOICE_ROW / 2.0, 2, Theme.SUCCESS);
            }
        }
        if (choices.size() > CHOICES_SHOWN) {
            double position = choicesScroll / (double) (choices.size() - CHOICES_SHOWN);
            Theme.scrollbar(x1 - 3, y0, y1, CHOICES_SHOWN, choices.size(), position, false);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
