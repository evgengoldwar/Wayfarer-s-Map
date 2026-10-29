package WayFarMap.client.gui.ui;

/** Minimal pixel icons for the world map's buttons, drawn with rectangles in the theme's colors. */
public final class Icons {

    private Icons() {}

    public static final String[] WAYPOINTS = { // a flag
        ".#.........", ".########..", ".#########.", ".########..", ".#.........", ".#.........", ".#.........",
        ".#.........", "###........" };

    public static final String[] STATS = { // a bar chart
        "........##.", "........##.", "....##..##.", "....##..##.", "##..##..##.", "##..##..##.", "##..##..##.",
        "##..##..##.", "###########" };

    public static final String[] SETTINGS = { // a gear
        ".....#.....", "..#.###.#..", "...#####...", "..###.###..", ".###...###.", "####...####", ".###...###.",
        "..###.###..", "...#####...", "..#.###.#..", ".....#....." };

    public static final String[] ADDONS = { // stacked layers
        "....###....", "..##...##..", "##.......##", "..##...##..", "....###....", "#.........#", ".##.....##.",
        "...##.##...", ".....#....." };

    public static final String[] GRID = { "##########", "#..#..#..#", "#..#..#..#", "##########", "#..#..#..#",
        "#..#..#..#", "##########", "#..#..#..#", "#..#..#..#", "##########" };

    public static final String[] BIOMES = { // a tree
        "....##....", "...####...", "..######..", ".########.", "..######..", ".########.", "##########", "....##....",
        "....##....", "...####..." };

    public static final String[] PLANTS = { // a flower over grass
        "....###....", "...#.#.#...", "....###....", ".....#.....", ".#...#...#.", "..#..#..#..", "#..#.#.#..#",
        ".#.#.#.#.#.", "..#######.." };

    public static final String[] CHUNKLOAD = { // an arrow down into a tray: loading
        "....###....", "....###....", "..#######..", "...#####...", "....###....", ".....#.....", "#.........#",
        "#.........#", "#.........#", "###########" };

    public static final String[] REGIONLOAD = { // an arrow up out of a box: taken back out of the world's files
        "###########", "#.........#", "#....#....#", "#...###...#", "#..#####..#", "#....#....#", "#....#....#",
        "#.........#", "###########" };

    public static final String[] TOPO = { // contour lines around a peak
        "...####...", "..#....#..", ".#..##..#.", "#..#..#..#", "#..#..#..#", ".#..##..#.", "..#....#..",
        "...####..." };

    public static final String[] CAVES = { // a hill with a tunnel
        "....##....", "...####...", "..######..", ".###..###.", ".##....##.", "##......##", "##......##",
        "##########" };

    public static final String[] DAY = { // the sun
        ".....#.....", ".#...#...#.", "..#.....#..", "....###....", "...#####...", "##.#####.##", "...#####...",
        "....###....", "..#.....#..", ".#...#...#.", ".....#....." };

    public static final String[] NIGHT = { // the moon and a star
        "...####...#", "..###.....#", ".###......#", ".##........", "###........", "###........", "###........",
        ".##.....#..", ".###.......", "..###......", "...####...." };

    public static final String[] DAY_NIGHT = { // a circle, half light and half dark
        "...#####...", "..#..####..", ".#...#####.", "#....######", "#....######", "#....######", "#....######",
        "#....######", ".#...#####.", "..#..####..", "...#####..." };

    public static final String[] MOBS = { // a creeper face
        "##########", "#........#", "#.##..##.#", "#.##..##.#", "#...##...#", "#..####..#", "#..####..#", "#..#..#..#",
        "#........#", "##########" };

    public static final String[] TEAM = { // two people
        "..##....##.", ".####..####", ".####..####", "..##....##.", "...........", ".####..####", "###########",
        "###########", "###########" };

    public static final String[] ISO = { // a block seen from above a corner
        "....###....", "..##...##..", "##.......##", "#.##...##.#", "#...###...#", "#....#....#", "#....#....#",
        "##...#...##", "..##.#.##..", "....###...." };

    public static final String[] CAMERA = { // a camera
        "...###.....", "###########", "#.........#", "#...###...#", "#..#...#..#", "#..#...#..#", "#...###...#",
        "#.........#", "###########" };

    public static final String[] FOLLOW = { // a crosshair on the player
        ".....#.....", "...#####...", "..#..#..#..", ".#...#...#.", ".#..###..#.", "#####.#####", ".#..###..#.",
        ".#...#...#.", "..#..#..#..", "...#####...", ".....#....." };

    public static final String[] FLAT = { // a place marker standing on the ground: the 2D map
        "...#####...", "..##...##..", "..#.###.#..", "..#.###.#..", "..##...##..", "...##.##...", "....###....",
        ".##..#..##.", "#.........#", ".#########." };

    public static final String[] CLOSE = { // a cross
        "##.....##", ".##...##.", "..##.##..", "...###...", "...###...", "..##.##..", ".##...##.", "##.....##" };

    public static final String[] HELP = { // a question mark
        "..#####..", ".##...##.", ".......##", "......##.", "....###..", "....##...", ".........", "....##...",
        "....##..." };

    public static final String[] ABOUT = { // a letter i
        "....##...", "....##...", ".........", "...###...", "....##...", "....##...", "....##...", "....##...",
        "...####.." };

    public static final String[] GITHUB = { // the octocat's head
        "..#.....#..", "..##...##..", ".#########.", "###########", "###.###.###", "###.###.###", "###########",
        ".#########.", "..#######..", "...##.##...", "...##.##..." };

    public static final String[] BOOSTY = { // a bold B
        "#######..", "###..###.", "###..###.", "###..###.", "#######..", "###...###", "###...###", "###...###",
        "########." };

    public static final String[] TELEGRAM = { // a paper plane
        "..........#", "........###", "......##.##", "....##..#.#", "..##...#..#", "###...#...#", "..##.#...#.",
        "....##..#..", ".....#.#...", ".....##....", ".....#....." };

    public static final String[] MINIMAP = { // a round map with the player's arrow
        "...#####...", ".##.....##.", ".#.......#.", "#....#....#", "#...###...#", "#..#####..#", "#....#....#",
        "#.........#", ".#.......#.", ".##.....##.", "...#####..." };

    public static final String[] MARKER = { // the player's arrow
        "....#....", "...###...", "...###...", "..#####..", "..#####..", ".#######.", ".###.###.", "###...###",
        "##.....##" };

    public static final String[] LOGS = { // a page of text
        "#######..", "#.....##.", "#.###..##", "#.......#", "#.#####.#", "#.......#", "#.####..#", "#.......#",
        "#.#####.#", "#.......#", "#########" };

    public static final String[] PALETTE = { // a painter's palette
        "...####....", ".##....##..", "#..##....#.", "#..##.##..#", "#......##.#", "#.##......#", "#.##...##.#",
        ".#....#..#.", "..####..#..", "......##..." };

    public static final String[] MAP2D = { // a square of flat map seen from above: land, a lake, a path
        "###########", "#.........#", "#.###.....#", "#.###..##.#", "#.....###.#", "#..#..###.#", "#.###.....#",
        "#.........#", "###########" };

    public static final String[] NO_PLANTS = { // a flower and a cross: the map without grass and flowers
        "..###..#..#", ".#.#.#..##.", "..###...##.", "...#...#..#", "#..#..#....", ".#.#.#.....", "#.#.#.#....",
        ".#####....." };

    public static final String[] KEYS = { // a keyboard
        "###########", "#.........#", "#.#.#.#.#.#", "#.........#", "#.#.#####.#", "#.........#", "###########" };

    public static final String[] ORE = { // a cut gem
        "..#####..", ".#.#.#.#.", "#########", ".#.....#.", "..#...#..", "...#.#...", "....#...." };

    public static final String[] POWER = { // a lightning bolt
        "....###", "...###.", "..###..", ".######", "######.", "..###..", ".###...", "###....", "#......" };

    public static final String[] NODE = { // a sparkle
        "....#....", "....#....", "...###...", "..#####..", "#########", "..#####..", "...###...", "....#....",
        "....#...." };

    public static final String[] WIRELESS = { // an antenna sending waves
        "..#####..", ".#.....#.", "#..###..#", "..#...#..", "....#....", "....#....", "...###...", "....#....",
        "...###..." };

    public static final String[] CHARGER = { // a lightning bolt between waves: wireless power
        "......##...", ".#...##..#.", "#...##....#", "#..######.#", "#....##...#", "#...##....#", "#..##.....#",
        ".#.......#." };

    public static final String[] DRONE = { // a quadcopter seen from above
        "##.....##", "##.....##", "..#...#..", "...###...", "...###...", "...###...", "..#...#..", "##.....##",
        "##.....##" };

    /** Mob protection: a no-entry sign for a Monster Repellator, a torch, a chandelier. */
    public static final String[] REPELLATOR = { "..#####..", ".#.....#.", "#.....#.#", "#....#..#", "#...#...#",
        "#..#....#", "#.#.....#", ".#.....#.", "..#####.." };
    public static final String[] MAGNUM_TORCH = { "..#..", ".###.", ".###.", "#####", ".###.", "..#..", "..#..",
        "..#..", ".###." };
    public static final String[] CHANDELIER = { "....#....", "....#....", "#.#.#.#.#", "#.#.#.#.#", "#########",
        ".#.....#.", "..#####.." };

    public static final String[] CLAIM = { // a shield
        "#########", "#.......#", "#.......#", "#.......#", ".#.....#.", ".#.....#.", "..#...#..", "...#.#...",
        "....#...." };

    /** Marks of the help's notes: an exclamation mark for an important one, a light bulb for a tip. */
    public static final String[] NOTE = { "##", "##", "##", "##", "##", "..", "##" };
    public static final String[] TIP = { ".###.", "#...#", "#...#", "#...#", ".#.#.", ".###.", "..#.." };
    /** A list item's mark. */
    public static final String[] BULLET = { ".#.", "###", ".#." };

    /** Small icons (7x7) for the map's bottom bar and its menus. */
    public static final String[] SMALL_CURSOR = { "...#...", ".#####.", ".#...#.", "##.#.##", ".#...#.", ".#####.",
        "...#..." };
    public static final String[] SMALL_TREE = { "..###..", ".#####.", "#######", ".#####.", "...#...", "...#...",
        "..###.." };
    public static final String[] SMALL_CAVE = { "..###..", ".#####.", "##...##", "#.....#", "#.....#", "#######" };
    public static final String[] SMALL_FLAG = { "##.....", "#####..", "######.", "#####..", "#......", "#......",
        "#......" };
    public static final String[] SMALL_QUEUE = { "...#...", "...#...", ".#####.", "..###..", "...#...", "#.....#",
        "#######" };
    public static final String[] SMALL_UP = { "...#...", "..###..", ".#####.", "...#...", "...#...", "...#...",
        "..###.." };
    public static final String[] SMALL_PLUS = { "...#...", "...#...", "...#...", "#######", "...#...", "...#...",
        "...#..." };
    public static final String[] SMALL_TRASH = { ".#####.", "#######", ".#...#.", ".#.#.#.", ".#.#.#.", ".#...#.",
        ".#####." };
    public static final String[] SMALL_PENCIL = { ".....##", "....###", "...###.", "..###..", ".###...", "##.....",
        "#......" };
    public static final String[] SMALL_CHAT = { "#######", "#.....#", "#.###.#", "#.....#", "#######", ".##....",
        "#......" };
    public static final String[] SMALL_COPY = { "####...", "#..#...", "#.#####", "#.#...#", "###...#", "..#...#",
        "..#####" };
    public static final String[] SMALL_PASTE = { "..###..", "##...##", "#.###.#", "#.....#", "#.###.#", "#.....#",
        "#######" };
    public static final String[] SMALL_EYE = { "..###..", ".#...#.", "#..#..#", ".#...#.", "..###.." };
    public static final String[] SMALL_DROP = { "...#...", "..###..", ".#####.", "#######", "#######", ".#####.",
        "..###.." };
    public static final String[] SMALL_CHECK = { "......#", ".....##", "#...##.", "##.##..", ".###...", "..#....",
        "......." };
    public static final String[] SMALL_PIN = { ".#####.", "##...##", "##...##", ".#####.", "..###..", "...#...",
        "...#..." };

    /** Small icons (7x7) of the kinds of mobs, for the mobs menu. */
    public static final String[] SMALL_NEUTRAL = { "#.....#", ".#####.", ".#.#.#.", ".#####.", "..###..", "..#.#.." };
    public static final String[] SMALL_BAT = { "#.....#", "##.#.##", "###.###", "#######", "#.#.#.#", "......." };
    public static final String[] SMALL_HEART = { ".##.##.", "#######", "#######", ".#####.", "..###..", "...#..." };
    public static final String[] SMALL_PAW = { "..#.#..", ".#####.", ".#####.", "..###..", ".......", ".#.#.#.",
        ".#.#.#." };
    public static final String[] SMALL_CREEPER = { "#######", "#..#..#", "#..#..#", "###.###", "##...##", "##.#.##",
        "#######" };
    public static final String[] SMALL_PERSON = { "..###..", "..###..", "...#...", ".#####.", "...#...", "..#.#..",
        ".#...#." };

    /** Small icons (7x7) of the settings' sections. */
    public static final String[] SMALL_GLOBE = { "..###..", ".#.#.#.", "#..#..#", "#######", "#..#..#", ".#.#.#.",
        "..###.." };
    public static final String[] SMALL_GEAR = { "..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.",
        "..#.#.." };
    public static final String[] SMALL_ZOOM = { ".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.",
        ".....##" };
    public static final String[] SMALL_COMPASS = { "..###..", ".#.#.#.", "#..#..#", "#.###.#", "#..#..#", ".#...#.",
        "..###.." };
    public static final String[] SMALL_FRAME = { "#######", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#",
        "#######" };
    public static final String[] SMALL_TEXT = { "#######", "#..#..#", "...#...", "...#...", "...#...", "...#...",
        "..###.." };
    public static final String[] SMALL_GRID = { "#######", "#..#..#", "#..#..#", "#######", "#..#..#", "#..#..#",
        "#######" };
    public static final String[] SMALL_TRAIL = { "......#", ".....##", "....###", "...#...", "..#....", ".......",
        "#......" };
    public static final String[] SMALL_PALETTE = { ".#####.", "#.#...#", "#...#.#", "#.#...#", "#...###", "#..#...",
        ".##...." };
    public static final String[] SMALL_FOG = { "#######", "#..#.##", "#.#.#.#", "##.#..#", "#.#...#", "##....#",
        "#######" };
    public static final String[] SMALL_BUTTONS = { "###.###", "#.#.#.#", "###.###", ".......", "###.###", "#.#.#.#",
        "###.###" };
    public static final String[] SMALL_CUBE = { "..###..", "##...##", "#.###.#", "#..#..#", "#..#..#", ".#.#.#.",
        "..###.." };
    public static final String[] SMALL_DISK = { "######.", "#.#.#.#", "#.###.#", "#.....#", "#.###.#", "#.###.#",
        "#######" };
    public static final String[] SMALL_LAYERS = { "...#...", ".##.##.", "#.....#", ".##.##.", "#..#..#", ".##.##.",
        "...#..." };
    public static final String[] SMALL_RESIZE = { "####...", "##.....", "#.#....", "#..#..#", "....#.#", ".....##",
        "...####" };
    public static final String[] SMALL_FADE = { "##.#..#", "##.#...", "##.#..#", "##.#...", "##.#..#", "##.#...",
        "##.#..#" };
    public static final String[] SMALL_SKULL = { ".#####.", "#######", "#..#..#", "#######", ".##.##.", "..###..",
        "..#.#.." };
    public static final String[] SMALL_PAGE = { "#####..", "#...##.", "#.###.#", "#.....#", "#.###.#", "#.....#",
        "#######" };

    /** An open section (pointing down) and a closed one (pointing right), before a title that opens and closes. */
    public static final String[] SECTION_OPEN = { "#####", ".###.", "..#.." };
    public static final String[] SECTION_CLOSED = { "#..", "##.", "###", "##.", "#.." };

    /** The mod's logo, a compass rose: layers of one color each, drawn together by {@link #drawLogo}. */
    private static final String[] LOGO_RING = { ".....#####.....", "...##.....##...", "..#.........#..",
        ".#...........#.", ".#...........#.", "#.............#", "#.............#", "#.............#",
        "#.............#", "#.............#", ".#...........#.", ".#...........#.", "..#.........#..",
        "...##.....##...", ".....#####....." };

    private static final String[] LOGO_TICKS = { "", "", "", "", "", "", "", "..##.......##..", "", "", "", "", "", "",
        "" };

    private static final String[] LOGO_NORTH = { "", "", ".......#.......", ".......#.......", "......###......",
        "......###......", ".....#####.....", ".....##.##....." };

    private static final String[] LOGO_SOUTH = { "", "", "", "", "", "", "", "", ".....#####.....", "......###......",
        "......###......", ".......#.......", ".......#......." };

    private static final String[] LOGO_PIVOT = { "", "", "", "", "", "", "", ".......#......." };

    public static final int LOGO_SIZE = 15;

    /** Draws the logo with its top left corner at (x, y), one GUI pixel per dot. */
    public static void drawLogo(int x, int y) {
        draw(LOGO_RING, x, y, Theme.ACCENT);
        draw(LOGO_TICKS, x, y, Theme.TEXT_MUTED);
        draw(LOGO_NORTH, x, y, Theme.DANGER);
        draw(LOGO_SOUTH, x, y, Theme.TEXT);
        draw(LOGO_PIVOT, x, y, 0xFFF2C14E);
    }

    public static int width(String[] icon) {
        int width = 0;
        for (String row : icon) {
            width = Math.max(width, row.length());
        }
        return width;
    }

    /** Draws the icon with its top left corner at (x, y), one GUI pixel per '#', merging runs into rectangles. */
    public static void draw(String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    Theme.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }
}
