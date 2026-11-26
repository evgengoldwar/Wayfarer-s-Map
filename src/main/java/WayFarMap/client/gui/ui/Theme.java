package WayFarMap.client.gui.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import org.lwjgl.opengl.GL11;

import WayFarMap.Config;

/** Colors and drawing helpers of the flat, minimal look shared by all WayFarMap screens. */
public final class Theme {

    /** The themes, in the order of the setting ({@code wayfarmap.option.map.theme.<name>}). */
    public static final int GRAPHITE = 0, FOREST = 1, NETHER = 2, OCEAN = 3, END = 4, DESERT = 5, SNOW = 6, VANILLA = 7;
    public static final String[] NAMES = { "graphite", "forest", "nether", "ocean", "end", "desert", "snow",
        "vanilla" };

    // The colors of the theme in use (ARGB), set by apply(): every screen of the mod draws with these.
    public static int SCREEN_DIM, PANEL, PANEL_ALT, BORDER, CONTROL, CONTROL_HOVER, CONTROL_DISABLED, ACCENT,
        ACCENT_DIM, DANGER, SUCCESS, TEXT, TEXT_MUTED, TEXT_DISABLED, ROW_HOVER, LABEL_BG;
    /** Background of a window's header. */
    public static int HEADER;
    /** A text field's box and its text. */
    public static int FIELD, FIELD_TEXT;
    /** Text on a button. */
    public static int BUTTON_TEXT;
    /** Text standing out from {@link #TEXT}, e.g. a hovered title: white on a dark theme. */
    public static int TEXT_BRIGHT;
    /** Softer text than {@link #TEXT}, brighter than {@link #TEXT_MUTED}. */
    public static int TEXT_SOFT;
    /** A light shade of the accent, e.g. the second word of a title. */
    public static int ACCENT_SOFT;
    /** Text of a tooltip. */
    public static int TOOLTIP_TEXT;
    /** A well under the panel: lists, pictures, previews. */
    public static int DEEP;
    /** Cards of a list, lit by the mouse and picked. */
    public static int CARD, CARD_HOVER, CARD_HEADER, CARD_SELECTED, CARD_SELECTED_HOVER;
    /** A side column of a window, and the band of a section's title. */
    public static int SIDEBAR, SECTION;
    /** Background of a chosen card (a language, a layout). */
    public static int CHOSEN;
    /** Backgrounds of something destructive: soft and lit, strong (switched on) and lit. */
    public static int DANGER_DIM, DANGER_DIM_HOVER, DANGER_STRONG, DANGER_STRONG_HOVER;
    /** The panels are light, with dark text. */
    public static boolean LIGHT;
    /** Panels and buttons have a raised edge (a light top and left, a dark bottom and right), like Minecraft's. */
    public static boolean BEVEL;
    /** Window titles are drawn with a shadow: on dark themes, unless the theme says otherwise. */
    public static boolean TITLE_SHADOW = true;

    /** The theme in use. */
    private static int current = -1;
    private static Palette palette;
    /** The value of the setting the theme was last taken from, to see it changed. */
    private static int lastSetting = -1;
    /** Whether the themes were read from the resource packs and the CustomThemes folder yet. */
    private static boolean loaded;

    /** The themes that can be chosen: the built-in ones first (in {@link #NAMES}' order), then the added ones. */
    private static List<Entry> ENTRIES = new ArrayList<>();

    /** One theme's colors; those not given are worked out from the others. */
    public static final class Palette {

        /** The base colors: the others are worked out from them unless a theme gives them too. */
        public int screenDim, panel, panelAlt, border, control, controlHover, controlDisabled, accent, accentDim,
            danger, success, text, textMuted, textDisabled, labelBg;
        public int header;
        int field, fieldText, buttonText, textBright, textSoft, accentSoft, deep, card, cardHover, cardHeader,
            cardSelected, cardSelectedHover, sidebar, section, chosen, dangerDim, dangerDimHover, dangerStrong,
            dangerStrongHover, rowHover, tooltipText;
        public boolean light, bevel;
        /** Text drawn with a shadow where the theme puts it on dark backgrounds (titles); null for the default. */
        Boolean textShadow;
        /** The strip under a window's header: its colors, each this many pixels long; null for none. */
        int[] decor;
        int decorRun = 4;
        /** The strip flows along (lava). */
        boolean decorFlows;

        Palette(boolean light, int screenDim, int panel, int panelAlt, int border, int control, int controlHover,
            int controlDisabled, int accent, int accentDim, int danger, int success, int text, int textMuted,
            int textDisabled, int labelBg) {
            this.light = light;
            this.screenDim = screenDim;
            this.panel = panel;
            this.panelAlt = panelAlt;
            this.border = border;
            this.control = control;
            this.controlHover = controlHover;
            this.controlDisabled = controlDisabled;
            this.accent = accent;
            this.accentDim = accentDim;
            this.danger = danger;
            this.success = success;
            this.text = text;
            this.textMuted = textMuted;
            this.textDisabled = textDisabled;
            this.labelBg = labelBg;
            header = panelAlt;
            derive();
        }

        private Palette() {}

        /** Works the other colors out from the base ones (and {@link #light}), as a theme without them gets them. */
        void derive() {
            int solid = panel | 0xFF000000;
            field = light ? blend(solid, 0xFFFFFFFF, 0.6) : blend(solid, 0xFF000000, 0.3);
            fieldText = text;
            buttonText = text;
            textBright = light ? 0xFF000000 : 0xFFFFFFFF;
            textSoft = blend(textMuted, text, 0.55);
            accentSoft = light ? blend(accent, 0xFF000000, 0.3) : blend(accent, 0xFFFFFFFF, 0.6);
            deep = light ? blend(solid, 0xFFFFFFFF, 0.5) : blend(solid, 0xFF000000, 0.4);
            card = blend(solid, control, 0.5);
            cardHover = blend(control, controlHover, 0.3);
            cardHeader = blend(card, controlHover, 0.3);
            cardSelected = blend(card, accentDim, 0.25);
            cardSelectedHover = blend(card, accentDim, 0.4);
            sidebar = light ? blend(solid, border, 0.25) : blend(solid, 0xFF000000, 0.17);
            section = solid;
            chosen = blend(solid, accentDim, 0.45);
            dangerDim = blend(solid, danger, 0.2);
            dangerDimHover = blend(solid, danger, 0.32);
            dangerStrong = blend(solid, danger, 0.5);
            dangerStrongHover = blend(solid, danger, 0.65);
            rowHover = light ? 0x14000000 : 0x20FFFFFF;
            tooltipText = text;
        }

        /** A copy, to change for a theme made from this one. */
        Palette copy() {
            Palette p = new Palette();
            for (java.lang.reflect.Field f : Palette.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(this);
                    f.set(p, value instanceof int[] ? ((int[]) value).clone() : value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            return p;
        }

        Palette header(int header) {
            this.header = header;
            return this;
        }

        Palette decor(int run, boolean flows, int... colors) {
            this.decorRun = run;
            this.decorFlows = flows;
            this.decor = colors;
            return this;
        }
    }

    /**
     * The names of the colors a theme file can set ({@code "colors": {"panel": "#F0161A20"}}), the base ones first:
     * setting one of those works out again the others the file doesn't set.
     */
    public static final String[] BASE_COLORS = { "screenDim", "panel", "panelAlt", "border", "control", "controlHover",
        "controlDisabled", "accent", "accentDim", "danger", "success", "text", "textMuted", "textDisabled", "labelBg" };
    public static final String[] DERIVED_COLORS = { "header", "field", "fieldText", "buttonText", "textBright",
        "textSoft", "accentSoft", "deep", "card", "cardHover", "cardHeader", "cardSelected", "cardSelectedHover",
        "sidebar", "section", "chosen", "dangerDim", "dangerDimHover", "dangerStrong", "dangerStrongHover", "rowHover",
        "tooltipText" };

    /** A theme of the list: built in, from a resource pack or from the CustomThemes folder. */
    public static final class Entry {

        /** Its name in the settings file and the folder's (lowercase). */
        public final String id;
        /** Its name shown, by language code ("" for any); null to take the translation of a built-in theme. */
        Map<String, String> names;
        Palette palette;
        /** Its pictures, null for colors only. */
        Skin skin;
        /** Where it was read from, for the log and the settings; null for a built-in theme as it is. */
        String origin;
        /** Reads it again from its file (when it is chosen again, or its file changed); null for a built-in one. */
        java.util.function.Supplier<Entry> reread;

        Entry(String id, Palette palette) {
            this.id = id;
            this.palette = palette;
        }

        /** Its name in the mod's language. */
        public String displayName() {
            if (names != null) {
                String name = names.get(WayFarMap.client.Lang.code());
                if (name == null) {
                    name = names.get("");
                }
                if (name == null) {
                    name = names.get("en_US");
                }
                if (name == null && !names.isEmpty()) {
                    name = names.values()
                        .iterator()
                        .next();
                }
                if (name != null) {
                    return name;
                }
            }
            String key = "wayfarmap.option.map.theme." + id;
            return WayFarMap.client.Lang.has(key) ? WayFarMap.client.Lang.format(key) : id;
        }
    }

    private static final Palette[] PALETTES = new Palette[NAMES.length];

    static {
        PALETTES[GRAPHITE] = new Palette(
            false,
            0xC0080A0D,
            0xF0161A20,
            0xF01C2129,
            0xFF2A313B,
            0xFF222831,
            0xFF2C3440,
            0xFF1A1E24,
            0xFF4C9AFF,
            0xFF2B5A96,
            0xFFE5534B,
            0xFF3FB950,
            0xFFE6EAF0,
            0xFF8A93A0,
            0xFF555D68,
            0xC0101418);
        PALETTES[FOREST] = new Palette(
            false,
            0xC0080D09,
            0xF017231A,
            0xF01D2C20,
            0xFF36503A,
            0xFF203124,
            0xFF2A412F,
            0xFF19241B,
            0xFF8BCF5A,
            0xFF3F6B2C,
            0xFFE07050,
            0xFF57C46A,
            0xFFECF2E2,
            0xFF9DB18F,
            0xFF5A6C55,
            0xC00F1A11).header(0xF03A2A1C)
                .decor(3, false, 0xFF4F8A32, 0xFF6AA83D, 0xFF3D6D27, 0xFF5C9A38, 0xFF7BBF45, 0xFF447A2B);
        PALETTES[NETHER] = new Palette(
            false,
            0xC00D0505,
            0xF01F1011,
            0xF0281415,
            0xFF5A2222,
            0xFF2C1617,
            0xFF3D1D1D,
            0xFF221112,
            0xFFFF8A2A,
            0xFF8A3A12,
            0xFFFF5252,
            0xFF6CC24A,
            0xFFF4E4D8,
            0xFFB58C80,
            0xFF6B4A44,
            0xC0180A0A).header(0xF03A1416)
                .decor(5, true, 0xFFFF9A2A, 0xFFFFCF4A, 0xFFE2561A, 0xFFFFB02E, 0xFFD84A12, 0xFFFF7A1A);
        PALETTES[OCEAN] = new Palette(
            false,
            0xC0040C10,
            0xF00E1D23,
            0xF0122830,
            0xFF24505C,
            0xFF15303A,
            0xFF1C3D48,
            0xFF10232A,
            0xFF55D6C8,
            0xFF1F6F6A,
            0xFFFF7A6B,
            0xFF4FD18B,
            0xFFE2F5F3,
            0xFF84AEB0,
            0xFF466467,
            0xC00A161B).header(0xF0123039)
                .decor(4, false, 0xFF2F8F86, 0xFF5FC9B5, 0xFF24706D, 0xFF3FA79A);
        PALETTES[END] = new Palette(
            false,
            0xC006040C,
            0xF015121E,
            0xF01B1727,
            0xFF3D3159,
            0xFF1E1A2B,
            0xFF2A2340,
            0xFF17141F,
            0xFFE4DD9A,
            0xFF5B4A86,
            0xFFFF6F91,
            0xFF6FD39A,
            0xFFEEE8F6,
            0xFFA297B8,
            0xFF5A5170,
            0xC0100D18).header(0xF0221C33)
                .decor(4, false, 0xFF2A1F44, 0xFF8F6FD6, 0xFFE4DD9A, 0xFF8F6FD6);
        PALETTES[DESERT] = new Palette(
            false,
            0xC0100B05,
            0xF02A2217,
            0xF0322819,
            0xFF5C4A2C,
            0xFF342A1C,
            0xFF443622,
            0xFF261F15,
            0xFFE9B95C,
            0xFF8A6526,
            0xFFE06646,
            0xFF8BC34A,
            0xFFF4E9D2,
            0xFFB6A07C,
            0xFF6E5F47,
            0xC01E170E).header(0xF03C2F1C)
                .decor(6, false, 0xFFD8B46A, 0xFFC49A52, 0xFFE4C47C, 0xFFB08646);
        PALETTES[SNOW] = new Palette(
            true,
            0xA0C8D2DC,
            0xF0EEF2F6,
            0xF0E3E9F0,
            0xFFB9C6D4,
            0xFFE0E7EF,
            0xFFD2DDE9,
            0xFFE8ECF0,
            0xFF2F7BD8,
            0xFF9CC2F0,
            0xFFC8372F,
            0xFF2E9E48,
            0xFF1C2430,
            0xFF5B6A7C,
            0xFF9AA6B4,
            0xE0F2F5F8).header(0xF0DBE4EE)
                .decor(4, false, 0xFFFFFFFF, 0xFFDCE6F2);
        Palette vanilla = new Palette(
            true,
            0xC0101010,
            0xFFC6C6C6,
            0xFFC6C6C6,
            0xFF555555,
            0xFF8B8B8B,
            0xFF7E88BF,
            0xFF6E6E6E,
            0xFF4A55B0,
            0xFF7E88BF,
            0xFFAA0000,
            0xFF00AA00,
            0xFF3F3F3F,
            0xFF5A5A5A,
            0xFF8A8A8A,
            0xE0C6C6C6);
        vanilla.bevel = true;
        vanilla.field = 0xFF000000;
        vanilla.fieldText = 0xFFE0E0E0;
        vanilla.buttonText = 0xFFFFFFFF;
        vanilla.deep = 0xFF8B8B8B;
        vanilla.card = 0xFFA4A4A4;
        vanilla.cardHover = 0xFFB0B0B0;
        vanilla.cardHeader = 0xFFB4B4B4;
        vanilla.sidebar = 0xFFB4B4B4;
        vanilla.rowHover = 0x30FFFFFF;
        PALETTES[VANILLA] = vanilla;
        for (int i = 0; i < NAMES.length; i++) {
            ENTRIES.add(new Entry(NAMES[i], PALETTES[i]));
        }
        apply(GRAPHITE);
    }

    /** How many themes can be chosen, the added ones included. */
    public static int count() {
        return ENTRIES.size();
    }

    /** The theme's name in the settings file. */
    public static String id(int theme) {
        return ENTRIES.get(clamp(theme)).id;
    }

    /** The theme's name as shown. */
    public static String displayName(int theme) {
        return ENTRIES.get(clamp(theme))
            .displayName();
    }

    /** The theme by its name, -1 when there is none of that name. */
    public static int indexOf(String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < ENTRIES.size(); i++) {
            if (ENTRIES.get(i).id.equalsIgnoreCase(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The themes as they are now: built in, then from the resource packs and the CustomThemes folder. */
    static List<Entry> entries() {
        return ENTRIES;
    }

    /** The built-in theme's colors as the mod has them, for themes made from it. */
    static Palette builtIn(int theme) {
        return PALETTES[theme];
    }

    private static int clamp(int theme) {
        return Math.max(0, Math.min(ENTRIES.size() - 1, theme));
    }

    /** The colors of a theme, e.g. to show it before it is chosen. */
    public static Palette palette(int theme) {
        return ENTRIES.get(clamp(theme)).palette;
    }

    /** Switches every screen of the mod to the theme. */
    public static void apply(int theme) {
        apply(theme, false);
    }

    /**
     * Switches every screen of the mod to the theme; {@code reread} reads its file and atlas again even if it is the
     * one in use. A theme from a file is always read again when it is chosen, so a changed picture shows.
     */
    static void apply(int theme, boolean reread) {
        theme = clamp(theme);
        if (theme == current && !reread) {
            return;
        }
        current = theme;
        Entry entry = ENTRIES.get(theme);
        if (entry.reread != null) {
            Entry fresh = entry.reread.get();
            if (fresh != null) {
                ENTRIES.set(theme, fresh);
                entry = fresh;
            }
        }
        Skin.activate(entry.skin);
        Palette p = palette = entry.palette;
        SCREEN_DIM = p.screenDim;
        PANEL = p.panel;
        PANEL_ALT = p.panelAlt;
        BORDER = p.border;
        CONTROL = p.control;
        CONTROL_HOVER = p.controlHover;
        CONTROL_DISABLED = p.controlDisabled;
        ACCENT = p.accent;
        ACCENT_DIM = p.accentDim;
        DANGER = p.danger;
        SUCCESS = p.success;
        TEXT = p.text;
        TEXT_MUTED = p.textMuted;
        TEXT_DISABLED = p.textDisabled;
        ROW_HOVER = p.rowHover;
        LABEL_BG = p.labelBg;
        HEADER = p.header;
        FIELD = p.field;
        FIELD_TEXT = p.fieldText;
        BUTTON_TEXT = p.buttonText;
        TEXT_BRIGHT = p.textBright;
        TEXT_SOFT = p.textSoft;
        ACCENT_SOFT = p.accentSoft;
        DEEP = p.deep;
        CARD = p.card;
        CARD_HOVER = p.cardHover;
        CARD_HEADER = p.cardHeader;
        CARD_SELECTED = p.cardSelected;
        CARD_SELECTED_HOVER = p.cardSelectedHover;
        SIDEBAR = p.sidebar;
        SECTION = p.section;
        TOOLTIP_TEXT = p.tooltipText;
        CHOSEN = p.chosen;
        DANGER_DIM = p.dangerDim;
        DANGER_DIM_HOVER = p.dangerDimHover;
        DANGER_STRONG = p.dangerStrong;
        DANGER_STRONG_HOVER = p.dangerStrongHover;
        LIGHT = p.light;
        BEVEL = p.bevel;
        TITLE_SHADOW = p.textShadow != null ? p.textShadow : !p.light;
    }

    /**
     * Reads the themes again (the resource packs were reloaded, a file of the CustomThemes folder changed) and takes
     * the chosen one again, its atlas too. Render thread.
     */
    public static void reload() {
        List<Entry> themes = ThemeLoader.load();
        ENTRIES = themes;
        loaded = true;
        Config.setThemeChoices(ids());
        int chosen = indexOf(Config.uiThemeName);
        if (chosen < 0 && Config.uiThemeName.isEmpty()) {
            // A settings file from before themes had names: the number is one of the built-in ones.
            chosen = Math.max(0, Math.min(NAMES.length - 1, Config.uiTheme));
        }
        // A theme that is gone (its resource pack turned off) leaves graphite in use, and comes back with its pack.
        Config.uiTheme = Math.max(0, chosen);
        lastSetting = Config.uiTheme;
        apply(Config.uiTheme, true);
    }

    private static String[] ids() {
        String[] ids = new String[ENTRIES.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ENTRIES.get(i).id;
        }
        return ids;
    }

    /** Takes the theme of the settings if it was changed: called before a screen of the mod is drawn. */
    public static void sync() {
        if (!loaded) {
            reload();
        } else if (ThemeLoader.folderChanged()) {
            // A theme of the CustomThemes folder was added, removed or saved: shown at once.
            reload();
        }
        if (Config.uiTheme != lastSetting) {
            // Chosen in the settings (or the welcome window): kept by its name, which stays when themes are added.
            lastSetting = Config.uiTheme;
            Config.uiThemeName = id(Config.uiTheme);
            Config.save();
        }
        apply(Config.uiTheme);
    }

    /** The color with another alpha (0-255). */
    public static int alpha(int color, int alpha) {
        return alpha << 24 | color & 0xFFFFFF;
    }

    /**
     * The theme's strip from {@code x0} to {@code x1}, {@code height} pixels high at {@code y} (the leaves under a
     * window's header, the lava, the prismarine...); nothing for a theme without one.
     */
    public static void decor(int x0, int y, int x1, int height) {
        decor(palette, x0, y, x1, height);
    }

    public static void decor(Palette p, int x0, int y, int x1, int height) {
        if (x1 <= x0) {
            return;
        }
        if (p == palette) {
            Skin.Sprite sprite = Skin.get("decor");
            if (sprite != null) {
                // The picture repeated along, flowing like the lava when the theme's strip flows.
                double shift = p.decorFlows ? System.currentTimeMillis() / 90.0 : 0;
                int h = sprite.drawHeight > 0 ? sprite.drawHeight : height;
                Skin.strip(sprite, x0, y, x1, y + h, shift);
                return;
            }
        }
        if (p.decor == null) {
            return;
        }
        int run = p.decorRun, count = p.decor.length;
        int shift = p.decorFlows ? (int) (System.currentTimeMillis() / 90 % (run * count)) : 0;
        for (int x = x0 - shift; x < x1; x += run) {
            int index = Math.floorMod((x - x0 + shift) / run, count);
            int from = Math.max(x0, x), to = Math.min(x1, x + run);
            if (to > from) {
                fill(from, y, to, y + height, p.decor[index]);
            }
        }
    }

    /** Whether the theme has a strip under the window headers. */
    public static boolean hasDecor() {
        return palette.decor != null || Skin.get("decor") != null;
    }

    private Theme() {}

    /** The width a sprite is drawn at across a thin part: its own (the "width" of the sprite), or the part's. */
    static int spriteWidth(Skin.Sprite sprite, int fallback) {
        return sprite.drawWidth > 0 ? sprite.drawWidth : fallback;
    }

    public static void fill(int x0, int y0, int x1, int y1, int color) {
        Gui.drawRect(x0, y0, x1, y1, color);
    }

    /** One pixel outline inside the rectangle. */
    public static void outline(int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y0 + 1, color);
        fill(x0, y1 - 1, x1, y1, color);
        fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    /**
     * Draws the theme's picture of the part over the rectangle, the first of the names it has (a state, then the part
     * itself); false when it has none of them, and the part is drawn in its colors.
     */
    public static boolean sprite(int x0, int y0, int x1, int y1, String... names) {
        Skin.Sprite sprite = Skin.first(names);
        if (sprite == null) {
            return false;
        }
        Skin.draw(sprite, x0, y0, x1, y1, 0xFFFFFFFF, 1);
        return true;
    }

    /** The shade over everything behind a window (the game, the map). */
    public static void dimScreen(int x0, int y0, int x1, int y1) {
        if (!sprite(x0, y0, x1, y1, "screen")) {
            fill(x0, y0, x1, y1, SCREEN_DIM);
        }
    }

    /** A tooltip's box: a shadow, a dark well and a border, or the theme's picture of it. */
    public static void tooltip(int x0, int y0, int x1, int y1) {
        if (sprite(x0, y0, x1, y1, "tooltip")) {
            return;
        }
        fill(x0 + 1, y0 + 1, x1 + 1, y1 + 1, 0x60000000);
        fill(x0, y0, x1, y1, alpha(DEEP, 0xF0));
        outline(x0, y0, x1, y1, BORDER);
    }

    /** The box of a label on the map (the name under the mouse, a coordinate). */
    public static void label(int x0, int y0, int x1, int y1) {
        if (!sprite(x0, y0, x1, y1, "label")) {
            fill(x0, y0, x1, y1, LABEL_BG);
        }
    }

    /** An open list under its button (a dropdown): its shadow, background and border. */
    public static void dropdown(int x0, int y0, int x1, int y1) {
        if (sprite(x0 - 1, y0 - 1, x1 + 1, y1 + 1, "dropdown")) {
            return;
        }
        fill(x0 + 2, y0 + 2, x1 + 2, y1 + 2, 0x60000000);
        fill(x0, y0, x1, y1, PANEL | 0xFF000000);
        outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, BORDER);
    }

    /** Panel with a thin border; with a raised edge inside it on a theme like Minecraft's. */
    public static void panel(int x0, int y0, int x1, int y1) {
        if (sprite(x0, y0, x1, y1, "panel")) {
            return;
        }
        fill(x0, y0, x1, y1, PANEL);
        if (BEVEL) {
            outline(x0, y0, x1, y1, 0xFF000000);
            bevel(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFFFFFFF, 0xFF555555, 2);
        } else {
            outline(x0, y0, x1, y1, BORDER);
        }
    }

    /** A raised edge inside the rectangle, {@code width} pixels: light at the top and left, dark at the others. */
    public static void bevel(int x0, int y0, int x1, int y1, int light, int dark, int width) {
        fill(x0, y0, x1 - width, y0 + width, light);
        fill(x0, y0 + width, x0 + width, y1 - width, light);
        fill(x0 + width, y1 - width, x1, y1, dark);
        fill(x1 - width, y0 + width, x1, y1 - width, dark);
    }

    public static void text(FontRenderer font, String text, int x, int y, int color) {
        font.drawString(text, x, y, color);
    }

    public static void centered(FontRenderer font, String text, int centerX, int y, int color) {
        font.drawString(text, centerX - font.getStringWidth(text) / 2, y, color);
    }

    /** Cuts the text to {@code maxWidth} pixels, ending with "..." when it doesn't fit. */
    public static String ellipsize(FontRenderer font, String text, int maxWidth) {
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        return font.trimStringToWidth(text, Math.max(0, maxWidth - font.getStringWidth(dots))) + dots;
    }

    public static boolean inside(int mouseX, int mouseY, int x0, int y0, int x1, int y1) {
        return mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
    }

    /** The color between {@code from} (t = 0) and {@code to} (t = 1), alpha included. */
    public static int blend(int from, int to, double t) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
            result |= (int) Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }

    /**
     * Draws only inside the rectangle (GUI pixels of the screen being drawn) until {@link #unclip}: a scrolled list
     * is cut at its edges instead of rows popping in and out.
     */
    public static void clip(int x0, int y0, int x1, int y1) {
        int factor = ScaledScreen.currentFactor();
        int displayHeight = Minecraft.getMinecraft().displayHeight;
        // The scissor is in window pixels: the screen's slide while it opens is not applied to it.
        int offset = ScaledScreen.currentOffset();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x0 * factor, displayHeight - (y1 + offset) * factor, (x1 - x0) * factor, (y1 - y0) * factor);
    }

    public static void unclip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * The color of an icon that has a color of its own: full when lit (hovered, on or chosen), brighter on an accent
     * background, a darker shade of it when dimmed (off, not chosen), so it can still be told apart.
     */
    public static int iconShade(int color, boolean lit, boolean onAccent, boolean dimmed) {
        if (onAccent) {
            return blend(color, 0xFFFFFFFF, 0.35);
        }
        if (dimmed && !lit) {
            return blend(color, PANEL | 0xFF000000, 0.55);
        }
        return color;
    }

    /** A filled circle, one rectangle per row of pixels. */
    public static void disc(double centerX, double centerY, double radius, int color) {
        int top = (int) Math.floor(centerY - radius), bottom = (int) Math.ceil(centerY + radius);
        for (int y = top; y < bottom; y++) {
            double dy = y + 0.5 - centerY;
            double reach = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int from = (int) Math.round(centerX - reach), to = (int) Math.round(centerX + reach);
            if (to > from) {
                fill(from, y, to, y + 1, color);
            }
        }
    }

    /** A thin scrollbar: its track, and the thumb lit while {@code lit}; {@code position} is 0 at the top to 1. */
    public static void scrollbar(int x, int y0, int y1, int visible, int total, double position, boolean lit) {
        int track = y1 - y0;
        int bar = Math.max(12, track * visible / Math.max(visible, total));
        int barY = y0 + (int) Math.round((track - bar) * Math.max(0, Math.min(1, position)));
        Skin.Sprite trackSprite = Skin.get("scrollbar.track");
        Skin.Sprite thumb = lit ? Skin.first("scrollbar.thumb.hover", "scrollbar.thumb") : Skin.get("scrollbar.thumb");
        // A picture of the bar may be wider than the 2 pixels of the flat one: centered on it.
        if (trackSprite != null) {
            int w = spriteWidth(trackSprite, 2);
            Skin.draw(trackSprite, x + 1 - w / 2.0, y0, x + 1 + w / 2.0, y1, 0xFFFFFFFF, 1);
        } else {
            fill(x, y0, x + 2, y1, CONTROL);
        }
        if (thumb != null) {
            int w = spriteWidth(thumb, 2);
            Skin.draw(thumb, x + 1 - w / 2.0, barY, x + 1 + w / 2.0, barY + bar, 0xFFFFFFFF, 1);
        } else {
            fill(x, barY, x + 2, barY + bar, lit ? ACCENT : BORDER);
        }
    }
}
