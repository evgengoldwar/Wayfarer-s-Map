package WayFarMap.client.gui.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import WayFarMap.WayFarMap;

/**
 * Finds the themes besides the built-in ones and reads them:
 * <ul>
 * <li>the folder {@code .minecraft/wayfarmap/CustomThemes}: each folder in it with a {@code theme.json} (and the
 * atlas it names next to it) is a theme, named after the folder; so is each {@code <name>.json} right in it;</li>
 * <li>resource packs: {@code assets/wayfarmap/themes/themes.json} lists the pack's themes
 * ({@code {"themes": ["name"]}}), each in {@code assets/wayfarmap/themes/<name>/theme.json}. A pack can change a
 * built-in theme the same way, without listing it: {@code assets/wayfarmap/themes/nether/theme.json}.</li>
 * </ul>
 * A theme of the folder with the name of another one (a built-in one, a pack's) takes its place. The folder is made on
 * first start with a description of everything a theme can change and a working example.
 */
public final class ThemeLoader {

    public static final String FOLDER = "CustomThemes";
    private static final String DEFAULTS = "/assets/wayfarmap/custom_themes/";
    /** The first line of the description the mod writes, so it can write it again when it changes. */
    private static final String README_MARK = "<!-- wayfarmap:auto";
    private static final long CHECK_MS = 1000;

    /** What the folder held when it was last read: its files and when they were saved, to see a change. */
    private static String folderState = "";
    private static long lastCheck;
    /** Whether the folder with its description and example was made (or found) this start. */
    private static boolean folderReady;
    /** What was wrong in the themes last read, also written to {@code CustomThemes/errors.txt}. */
    private static final List<String> problems = new ArrayList<>();

    private ThemeLoader() {}

    /** The folder of the custom themes. */
    public static File folder() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "wayfarmap"), FOLDER);
    }

    /** The themes: built in, from the resource packs, then from the folder. Render thread. */
    static List<Theme.Entry> load() {
        problems.clear();
        List<Theme.Entry> list = new ArrayList<>();
        for (int i = 0; i < Theme.NAMES.length; i++) {
            list.add(new Theme.Entry(Theme.NAMES[i], Theme.builtIn(i)));
        }
        try {
            loadPacks(list);
        } catch (RuntimeException e) {
            problem("resource packs", "could not be read: " + e);
        }
        try {
            ensureFolder();
            loadFolder(list);
        } catch (RuntimeException e) {
            problem(FOLDER, "could not be read: " + e);
        }
        folderState = folderState();
        lastCheck = System.currentTimeMillis();
        writeProblems();
        return list;
    }

    /** Whether a theme file of the folder was added, removed or saved since it was read (checked once a second). */
    static boolean folderChanged() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_MS) {
            return false;
        }
        lastCheck = now;
        return !folderState().equals(folderState);
    }

    // --- Resource packs ---

    private static void loadPacks(List<Theme.Entry> list) {
        IResourceManager resources = Minecraft.getMinecraft()
            .getResourceManager();
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(Theme.NAMES));
        try {
            for (Object o : resources.getAllResources(new ResourceLocation("wayfarmap", "themes/themes.json"))) {
                try (InputStream in = ((IResource) o).getInputStream()) {
                    JsonElement index = parse(in);
                    JsonElement themes = index.isJsonObject() ? index.getAsJsonObject()
                        .get("themes") : index;
                    if (themes != null && themes.isJsonArray()) {
                        for (JsonElement id : themes.getAsJsonArray()) {
                            ids.add(
                                id.getAsString()
                                    .toLowerCase(Locale.ROOT));
                        }
                    }
                } catch (Exception e) {
                    problem("assets/wayfarmap/themes/themes.json", "is not a list of themes: " + e.getMessage());
                }
            }
        } catch (IOException noPackHasThemes) {
            // No pack lists any.
        }
        for (String id : ids) {
            ResourceLocation json = new ResourceLocation("wayfarmap", "themes/" + id + "/theme.json");
            IResource resource;
            try {
                resource = resources.getResource(json);
            } catch (IOException none) {
                continue;
            }
            String origin = "resource pack: assets/wayfarmap/themes/" + id + "/theme.json";
            Theme.Entry parent = find(list, id);
            Supplier<Theme.Entry> reread = new Supplier<Theme.Entry>() {

                @Override
                public Theme.Entry get() {
                    try (InputStream in = Minecraft.getMinecraft()
                        .getResourceManager()
                        .getResource(json)
                        .getInputStream()) {
                        JsonObject fresh = parse(in).getAsJsonObject();
                        return build(
                            id,
                            fresh,
                            parentOf(fresh, Theme.entries(), parent, origin),
                            origin,
                            packAtlas(id),
                            this);
                    } catch (Exception e) {
                        problem(origin, e.getMessage());
                        return null;
                    }
                }
            };
            try (InputStream in = resource.getInputStream()) {
                JsonObject theme = parse(in).getAsJsonObject();
                Theme.Entry base = parentOf(theme, list, parent, origin);
                put(list, build(id, theme, base, origin, packAtlas(id), reread));
            } catch (Exception e) {
                problem(origin, e.getMessage());
            }
        }
    }

    private static AtlasSource packAtlas(String id) {
        return name -> {
            ResourceLocation location = name.contains(":") ? new ResourceLocation(name)
                : new ResourceLocation("wayfarmap", "themes/" + id + "/" + name);
            return () -> {
                try (InputStream in = Minecraft.getMinecraft()
                    .getResourceManager()
                    .getResource(location)
                    .getInputStream()) {
                    return ImageIO.read(in);
                }
            };
        };
    }

    // --- The CustomThemes folder ---

    /** A theme file of the folder: {@code <name>/theme.json} or {@code <name>.json}. */
    private static final class FolderTheme {

        final String id;
        final File json, dir;

        FolderTheme(String id, File json, File dir) {
            this.id = id;
            this.json = json;
            this.dir = dir;
        }
    }

    private static List<FolderTheme> folderThemes() {
        List<FolderTheme> themes = new ArrayList<>();
        File[] files = folder().listFiles();
        if (files == null) {
            return themes;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            // "_icons", ".git": not themes.
            if (name.startsWith("_") || name.startsWith(".")) {
                continue;
            }
            if (file.isDirectory()) {
                File json = new File(file, "theme.json");
                if (json.isFile()) {
                    themes.add(new FolderTheme(name.toLowerCase(Locale.ROOT), json, file));
                }
            } else if (name.toLowerCase(Locale.ROOT)
                .endsWith(".json")) {
                    themes.add(
                        new FolderTheme(
                            name.substring(0, name.length() - 5)
                                .toLowerCase(Locale.ROOT),
                            file,
                            folder()));
                }
        }
        return themes;
    }

    private static void loadFolder(List<Theme.Entry> list) {
        // Read first, then built in order: a theme can be made from another theme of the folder.
        Map<FolderTheme, JsonObject> pending = new LinkedHashMap<>();
        for (FolderTheme theme : folderThemes()) {
            try {
                pending.put(theme, readJson(theme.json));
            } catch (Exception e) {
                problem(origin(theme), message(e));
            }
        }
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Map.Entry<FolderTheme, JsonObject> item : new ArrayList<>(pending.entrySet())) {
                FolderTheme theme = item.getKey();
                String parentId = extendsOf(item.getValue());
                if (parentId != null && !parentId.equals(theme.id) && waitsFor(pending, parentId)) {
                    continue;
                }
                pending.remove(theme);
                progress = true;
                add(list, theme, item.getValue());
            }
        }
        // Themes made from each other in a circle: made from graphite.
        for (Map.Entry<FolderTheme, JsonObject> item : pending.entrySet()) {
            problem(origin(item.getKey()), "\"extends\" goes around in a circle; made from graphite");
            item.getValue()
                .remove("extends");
            add(list, item.getKey(), item.getValue());
        }
    }

    private static boolean waitsFor(Map<FolderTheme, JsonObject> pending, String id) {
        for (FolderTheme theme : pending.keySet()) {
            if (theme.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Theme.Entry> list, FolderTheme theme, JsonObject json) {
        String origin = origin(theme);
        Theme.Entry replaced = find(list, theme.id);
        AtlasSource atlas = name -> {
            File file = new File(theme.dir, name);
            return () -> {
                if (!file.isFile()) {
                    throw new IOException("no file " + file.getName() + " next to the theme");
                }
                return ImageIO.read(file);
            };
        };
        Supplier<Theme.Entry> reread = new Supplier<Theme.Entry>() {

            @Override
            public Theme.Entry get() {
                try {
                    JsonObject fresh = readJson(theme.json);
                    return build(
                        theme.id,
                        fresh,
                        parentOf(fresh, Theme.entries(), replaced, origin),
                        origin,
                        atlas,
                        this);
                } catch (Exception e) {
                    problem(origin, message(e));
                    writeProblems();
                    return null;
                }
            }
        };
        try {
            put(list, build(theme.id, json, parentOf(json, list, replaced, origin), origin, atlas, reread));
        } catch (Exception e) {
            problem(origin, message(e));
        }
    }

    private static String origin(FolderTheme theme) {
        return FOLDER + "/"
            + folder().toPath()
                .relativize(theme.json.toPath())
                .toString()
                .replace('\\', '/');
    }

    /** The folder's theme files and pictures with when they were saved: changes when one is added, changed, removed. */
    private static String folderState() {
        File root = folder();
        if (!root.isDirectory()) {
            return "";
        }
        StringBuilder state = new StringBuilder();
        File[] files = root.listFiles();
        if (files == null) {
            return "";
        }
        Arrays.sort(files);
        for (File file : files) {
            if (file.isDirectory()) {
                File[] inner = file.listFiles();
                if (inner == null) {
                    continue;
                }
                Arrays.sort(inner);
                for (File f : inner) {
                    appendState(state, f);
                }
            } else {
                appendState(state, file);
            }
        }
        return state.toString();
    }

    private static void appendState(StringBuilder state, File file) {
        String name = file.getName()
            .toLowerCase(Locale.ROOT);
        // Only what makes a theme: the mod's own notes (errors.txt) don't count.
        if (file.isFile() && (name.endsWith(".json") || name.endsWith(".png"))) {
            state.append(file.getPath())
                .append(':')
                .append(file.lastModified())
                .append(':')
                .append(file.length())
                .append('\n');
        }
    }

    /**
     * Makes the folder on first start, with the description of what a theme can change and a working example; the
     * description is written again when the mod's own changes. The icons' template is written each start.
     */
    private static void ensureFolder() {
        if (folderReady) {
            return;
        }
        folderReady = true;
        File root = folder();
        boolean fresh = !root.isDirectory();
        if (fresh && !root.mkdirs()) {
            problem(FOLDER, "could not be made");
            return;
        }
        try {
            File readme = new File(root, "README.md");
            byte[] ours = resource(DEFAULTS + "README.md");
            if (ours != null) {
                boolean write = !readme.isFile();
                if (!write) {
                    String theirs = new String(Files.readAllBytes(readme.toPath()), StandardCharsets.UTF_8);
                    write = theirs.startsWith(README_MARK) && !Arrays.equals(ours, Files.readAllBytes(readme.toPath()));
                }
                if (write) {
                    Files.write(readme.toPath(), ours);
                }
            }
            if (fresh) {
                File example = new File(root, "example");
                example.mkdirs();
                copy(DEFAULTS + "example/theme.json", new File(example, "theme.json"));
                copy(DEFAULTS + "example/atlas.png", new File(example, "atlas.png"));
            }
            IconTemplate.write(new File(root, "_icons"));
        } catch (IOException e) {
            problem(FOLDER, "could not be filled: " + e);
        }
    }

    private static void copy(String resource, File to) throws IOException {
        byte[] data = resource(resource);
        if (data != null && !to.exists()) {
            Files.write(to.toPath(), data);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = ThemeLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0;) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    // --- Reading a theme ---

    /** The atlas of a theme by the name its file gives ("atlas.png"). */
    private interface AtlasSource {

        Callable<BufferedImage> of(String name);
    }

    private static JsonObject readJson(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            JsonElement json = parse(in);
            if (!json.isJsonObject()) {
                throw new IOException("the file is not a JSON object { ... }");
            }
            return json.getAsJsonObject();
        }
    }

    /** Lenient JSON: comments (line ones and block ones) are allowed, so a theme file can explain itself. */
    private static JsonElement parse(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        try {
            return new JsonParser().parse(reader);
        } catch (RuntimeException e) {
            throw new IOException("broken JSON: " + message(e));
        }
    }

    private static String extendsOf(JsonObject json) {
        JsonElement parent = json.get("extends");
        return parent != null && parent.isJsonPrimitive() ? parent.getAsString()
            .toLowerCase(Locale.ROOT) : null;
    }

    /**
     * The theme a file is made from: the one it names in "extends", else the one of its name it takes the place of,
     * else graphite.
     */
    private static Theme.Entry parentOf(JsonObject json, List<Theme.Entry> list, Theme.Entry replaced, String origin) {
        String id = extendsOf(json);
        if (id != null) {
            Theme.Entry parent = find(list, id);
            if (parent != null && parent != replaced) {
                return parent;
            }
            if (replaced != null && replaced.id.equals(id)) {
                return replaced;
            }
            if (parent == null) {
                problem(origin, "\"extends\": there is no theme \"" + id + "\"; made from graphite");
            }
        }
        return replaced != null ? replaced : list.get(Theme.GRAPHITE);
    }

    /** The theme of the file, made from {@code parent}. */
    private static Theme.Entry build(String id, JsonObject json, Theme.Entry parent, String origin, AtlasSource atlas,
        Supplier<Theme.Entry> reread) {
        Theme.Palette p = parent.palette.copy();
        boolean rebase = false;
        if (json.has("light")) {
            p.light = bool(json, "light", p.light);
            rebase = true;
        }
        JsonObject colors = object(json, "colors", origin);
        Map<String, Integer> derived = new HashMap<>();
        if (colors != null) {
            Set<String> known = new LinkedHashSet<>(Arrays.asList(Theme.BASE_COLORS));
            known.addAll(Arrays.asList(Theme.DERIVED_COLORS));
            for (Map.Entry<String, JsonElement> color : colors.entrySet()) {
                String name = color.getKey();
                if (!known.contains(name)) {
                    problem(origin, "\"colors\": there is no color \"" + name + "\"");
                    continue;
                }
                Integer value = color(color.getValue(), origin, "colors." + name);
                if (value == null) {
                    continue;
                }
                if (Arrays.asList(Theme.BASE_COLORS)
                    .contains(name)) {
                    setColor(p, name, value);
                    rebase = true;
                } else {
                    derived.put(name, value);
                }
            }
        }
        if (rebase) {
            // The other colors follow the new base ones, unless the file gives them too.
            p.derive();
            p.header = p.panelAlt;
        }
        for (Map.Entry<String, Integer> color : derived.entrySet()) {
            setColor(p, color.getKey(), color.getValue());
        }
        if (json.has("bevel")) {
            p.bevel = bool(json, "bevel", p.bevel);
        }
        if (json.has("titleShadow")) {
            p.textShadow = bool(json, "titleShadow", true);
        }
        if (json.has("decor")) {
            JsonElement decor = json.get("decor");
            if (decor.isJsonNull() || decor.isJsonPrimitive() && !decor.getAsBoolean()) {
                p.decor = null;
            } else if (decor.isJsonObject()) {
                JsonObject d = decor.getAsJsonObject();
                p.decorRun = Math.max(1, integer(d, "run", p.decorRun));
                p.decorFlows = bool(d, "flows", p.decorFlows);
                if (d.has("colors") && d.get("colors")
                    .isJsonArray()) {
                    JsonArray array = d.getAsJsonArray("colors");
                    List<Integer> values = new ArrayList<>();
                    for (JsonElement c : array) {
                        Integer value = color(c, origin, "decor.colors");
                        if (value != null) {
                            values.add(value);
                        }
                    }
                    p.decor = values.isEmpty() ? null
                        : values.stream()
                            .mapToInt(Integer::intValue)
                            .toArray();
                }
            } else {
                problem(origin, "\"decor\" must be { \"run\", \"flows\", \"colors\" } or null");
            }
        }

        Theme.Entry entry = new Theme.Entry(id, p);
        entry.origin = origin;
        entry.reread = reread;
        entry.names = names(json.get("name"), parent);
        // Pictures: its own atlas and sprites; without an atlas, the ones of the theme it is made from.
        if (json.has("atlas") && json.get("atlas")
            .isJsonPrimitive()) {
            String atlasName = json.get("atlas")
                .getAsString();
            Map<String, Skin.Sprite> sprites = sprites(object(json, "sprites", origin), origin);
            entry.skin = new Skin(atlas.of(atlasName), origin + " -> " + atlasName, sprites);
        } else {
            entry.skin = parent.skin;
            if (json.has("sprites")) {
                problem(origin, "\"sprites\" are given but no \"atlas\" to take them from");
            }
        }
        return entry;
    }

    private static Map<String, String> names(JsonElement name, Theme.Entry parent) {
        if (name == null || name.isJsonNull()) {
            return parent.names;
        }
        Map<String, String> names = new HashMap<>();
        if (name.isJsonPrimitive()) {
            names.put("", name.getAsString());
        } else if (name.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : name.getAsJsonObject()
                .entrySet()) {
                names.put(
                    e.getKey(),
                    e.getValue()
                        .getAsString());
            }
        }
        return names;
    }

    private static Map<String, Skin.Sprite> sprites(JsonObject json, String origin) {
        Map<String, Skin.Sprite> sprites = Skin.none();
        if (json == null) {
            return sprites;
        }
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            String name = e.getKey();
            if (!e.getValue()
                .isJsonObject()) {
                // null or false: the part keeps its colors.
                continue;
            }
            JsonObject s = e.getValue()
                .getAsJsonObject();
            Skin.Sprite sprite = new Skin.Sprite();
            int[] uv = ints(s.get("uv"));
            if (uv != null && uv.length == 4) {
                sprite.u = uv[0];
                sprite.v = uv[1];
                sprite.w = uv[2];
                sprite.h = uv[3];
            } else {
                sprite.u = integer(s, "x", -1);
                sprite.v = integer(s, "y", -1);
                sprite.w = integer(s, "w", 0);
                sprite.h = integer(s, "h", 0);
            }
            if (sprite.u < 0 || sprite.v < 0 || sprite.w <= 0 || sprite.h <= 0) {
                problem(origin, "sprite \"" + name + "\" needs \"uv\": [x, y, width, height]");
                continue;
            }
            JsonElement border = s.get("border");
            int[] b = ints(border);
            if (b != null && b.length == 4) {
                sprite.left = b[0];
                sprite.top = b[1];
                sprite.right = b[2];
                sprite.bottom = b[3];
            } else if (b != null && b.length == 2) {
                sprite.left = sprite.right = b[0];
                sprite.top = sprite.bottom = b[1];
            } else if (border != null && border.isJsonPrimitive()) {
                sprite.left = sprite.top = sprite.right = sprite.bottom = border.getAsInt();
            }
            sprite.left = clamp(sprite.left, sprite.w);
            sprite.right = clamp(sprite.right, sprite.w - sprite.left);
            sprite.top = clamp(sprite.top, sprite.h);
            sprite.bottom = clamp(sprite.bottom, sprite.h - sprite.top);
            if (s.has("scale")) {
                sprite.scale = Math.max(
                    0.05,
                    s.get("scale")
                        .getAsDouble());
            }
            sprite.tile = bool(s, "tile", false);
            sprite.center = bool(s, "center", true);
            sprite.recolor = bool(s, "recolor", name.startsWith("icon."));
            if (s.has("tint")) {
                Integer tint = color(s.get("tint"), origin, "sprites." + name + ".tint");
                if (tint != null) {
                    sprite.tint = tint;
                }
            }
            sprite.frames = Math.max(1, integer(s, "frames", 1));
            sprite.frameTime = Math.max(1, integer(s, "frameTime", 100));
            sprite.drawWidth = Math.max(0, integer(s, "width", 0));
            sprite.drawHeight = Math.max(0, integer(s, "height", 0));
            sprites.put(name, sprite);
        }
        return sprites;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static void setColor(Theme.Palette p, String name, int value) {
        try {
            java.lang.reflect.Field field = Theme.Palette.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(p, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A color: "#RRGGBB" (opaque), "#AARRGGBB", "0xAARRGGBB" or a number; null (with a note in errors.txt) when it
     * is none of them.
     */
    static Integer color(JsonElement element, String origin, String where) {
        if (element == null || !element.isJsonPrimitive()) {
            problem(origin, where + ": a color is \"#RRGGBB\" or \"#AARRGGBB\"");
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return (int) primitive.getAsLong();
        }
        String text = primitive.getAsString()
            .trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        try {
            if (text.length() == 3) {
                // #RGB
                StringBuilder full = new StringBuilder();
                for (char c : text.toCharArray()) {
                    full.append(c)
                        .append(c);
                }
                text = full.toString();
            }
            if (text.length() == 6) {
                return 0xFF000000 | Integer.parseInt(text, 16);
            }
            if (text.length() == 8) {
                return (int) Long.parseLong(text, 16);
            }
        } catch (NumberFormatException ignored) {}
        problem(origin, where + ": \"" + primitive.getAsString() + "\" is not a color (\"#RRGGBB\" or \"#AARRGGBB\")");
        return null;
    }

    private static JsonObject object(JsonObject json, String key, String origin) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            problem(origin, "\"" + key + "\" must be an object { ... }");
            return null;
        }
        return value.getAsJsonObject();
    }

    private static int[] ints(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i)
                .getAsInt();
        }
        return values;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static Theme.Entry find(List<Theme.Entry> list, String id) {
        for (Theme.Entry entry : list) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** Adds the theme, or puts it in the place of the one of its name. */
    private static void put(List<Theme.Entry> list, Theme.Entry entry) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(entry.id)) {
                list.set(i, entry);
                return;
            }
        }
        list.add(entry);
    }

    // --- Problems ---

    static void problem(String origin, String message) {
        String line = origin + ": " + message;
        if (!problems.contains(line)) {
            problems.add(line);
            WayFarMap.LOG.warn("Theme {}", line);
        }
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.toString();
    }

    /** {@code CustomThemes/errors.txt}: what was wrong in the themes, or no file when nothing was. */
    static void writeProblems() {
        File root = folder();
        if (!root.isDirectory()) {
            return;
        }
        File file = new File(root, "errors.txt");
        try {
            if (problems.isEmpty()) {
                Files.deleteIfExists(file.toPath());
            } else {
                StringBuilder text = new StringBuilder(
                    "Problems found in the themes (written again each time they are read):\n"
                        + "Ошибки в темах (файл пишется заново при каждом чтении тем):\n\n");
                for (String line : problems) {
                    text.append("- ")
                        .append(line)
                        .append('\n');
                }
                Files.write(
                    file.toPath(),
                    text.toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {}
    }
}
