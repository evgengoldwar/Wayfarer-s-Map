package WayFarMap.client.gui.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

import WayFarMap.WayFarMap;
import WayFarMap.client.GlTextures;

/**
 * The pictures of a theme: one atlas (a PNG) and the pieces of it ("sprites") that replace parts of the mod's screens
 * (a button, a panel, a scrollbar, an icon...). A part with a sprite is drawn with it instead of its flat colors; one
 * without keeps the theme's colors. Sprites are drawn as nine-slices: the corners as they are, the edges and the middle
 * stretched (or repeated) to the size of the part.
 * <p>
 * The atlas is read again each time the theme is chosen, so a changed PNG shows by picking the theme again (or
 * as soon as it is saved, for the themes of the {@code CustomThemes} folder).
 */
public final class Skin {

    /** One piece of the atlas and how it is laid over a part. */
    public static final class Sprite {

        /** Its rectangle in the atlas, in the atlas's pixels. */
        public int u, v, w, h;
        /** The corners and edges kept as they are: left, top, right, bottom (atlas pixels). */
        public int left, top, right, bottom;
        /** GUI pixels per atlas pixel: 0.5 for an atlas drawn twice as fine. */
        public double scale = 1;
        /** Edges and middle repeated instead of stretched. */
        public boolean tile;
        /** The middle drawn; off for a frame around something (the minimap's). */
        public boolean center = true;
        /** Multiplied into the picture (ARGB); white keeps it as it is. */
        public int tint = 0xFFFFFFFF;
        /**
         * Takes the color the part would have been drawn in (an icon's, a text's), so one white picture follows the
         * theme and the state (lit, dimmed, disabled). On by default for icons only.
         */
        public boolean recolor;
        /** An animation: frames under each other in the atlas, each {@link #h} high, shown this long (ms). */
        public int frames = 1, frameTime = 100;
        /** Only the picture's own size is used (an icon): set when the part has a size of its own. */
        public int drawWidth, drawHeight;
    }

    /** Where the atlas comes from: a file of the CustomThemes folder or a resource pack. */
    private final Callable<BufferedImage> source;
    /** Shown in the log when the atlas can't be read. */
    private final String description;
    final Map<String, Sprite> sprites;
    private int texture = -1, textureWidth, textureHeight;
    private boolean loadTried;

    /** The skin of the theme in use, null for a theme of colors only. */
    private static Skin current;

    Skin(Callable<BufferedImage> source, String description, Map<String, Sprite> sprites) {
        this.source = source;
        this.description = description;
        this.sprites = sprites;
    }

    /** Puts this skin in use (null for none), letting go of the one before; its atlas is read on first use. */
    static void activate(Skin skin) {
        if (current != null && current != skin) {
            current.release();
        }
        current = skin;
        if (skin != null) {
            // Read again: the picture may have been changed since it was last in use.
            skin.release();
        }
    }

    private void release() {
        if (texture >= 0) {
            GlTextures.delete(texture);
            texture = -1;
        }
        loadTried = false;
    }

    /** The atlas uploaded; false when it couldn't be read. Render thread. */
    private boolean ready() {
        if (texture >= 0) {
            return true;
        }
        if (loadTried) {
            return false;
        }
        loadTried = true;
        try {
            BufferedImage image = source.call();
            if (image == null) {
                WayFarMap.LOG.warn("Theme atlas {} is not a picture", description);
                return false;
            }
            // Sprites reaching out of the picture are left out (the part keeps its colors).
            for (java.util.Iterator<Map.Entry<String, Sprite>> it = sprites.entrySet()
                .iterator(); it.hasNext();) {
                Map.Entry<String, Sprite> e = it.next();
                Sprite s = e.getValue();
                if (s.u + s.w > image.getWidth() || s.v + s.h * s.frames > image.getHeight()) {
                    ThemeLoader.problem(
                        description,
                        "sprite \"" + e.getKey()
                            + "\" reaches out of the "
                            + image.getWidth()
                            + "x"
                            + image.getHeight()
                            + " atlas");
                    it.remove();
                }
            }
            ThemeLoader.writeProblems();
            texture = GlTextures.create();
            TextureUtil.uploadTextureImageAllocate(texture, image, false, true);
            textureWidth = image.getWidth();
            textureHeight = image.getHeight();
            return true;
        } catch (Exception e) {
            ThemeLoader.problem(description, "the atlas could not be read: " + e);
            ThemeLoader.writeProblems();
            if (texture >= 0) {
                GlTextures.delete(texture);
                texture = -1;
            }
            return false;
        }
    }

    /** Whether the theme in use has pictures at all. */
    public static boolean active() {
        return current != null && !current.sprites.isEmpty();
    }

    /** The sprite of the part in the theme in use, null when it has none (the part keeps its colors). */
    public static Sprite get(String name) {
        Skin skin = current;
        if (skin == null) {
            return null;
        }
        Sprite sprite = skin.sprites.get(name);
        if (sprite == null || !skin.ready()) {
            return null;
        }
        return sprite;
    }

    /** The first of the names that has a sprite: a part's state, then the part itself ("button.hover", "button"). */
    public static Sprite first(String... names) {
        if (current == null) {
            return null;
        }
        for (String name : names) {
            Sprite sprite = get(name);
            if (sprite != null) {
                return sprite;
            }
        }
        return null;
    }

    /** Draws the part's sprite over the rectangle; false (nothing drawn) when the theme has none for it. */
    public static boolean draw(String name, double x0, double y0, double x1, double y1) {
        Sprite sprite = get(name);
        if (sprite == null) {
            return false;
        }
        draw(sprite, x0, y0, x1, y1, 0xFFFFFFFF, 1);
        return true;
    }

    /**
     * Draws the sprite over the rectangle as a nine-slice.
     *
     * @param color   the part's own color, taken when the sprite {@link Sprite#recolor recolors}
     * @param opacity 0 to 1, for a state fading in (a button lit by the mouse)
     */
    public static void draw(Sprite s, double x0, double y0, double x1, double y1, int color, double opacity) {
        Skin skin = current;
        if (skin == null || s == null || x1 <= x0 || y1 <= y0 || opacity <= 0 || !skin.ready()) {
            return;
        }
        int tint = s.recolor ? multiply(s.tint, color) : s.tint;
        float a = (tint >>> 24 & 0xFF) / 255f * (float) Math.min(1, opacity);
        if (a <= 0) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, skin.texture);
        GL11.glColor4f((tint >> 16 & 0xFF) / 255f, (tint >> 8 & 0xFF) / 255f, (tint & 0xFF) / 255f, a);

        int frame = s.frames > 1 ? (int) (System.currentTimeMillis() / Math.max(1, s.frameTime) % s.frames) : 0;
        int v = s.v + frame * s.h;
        double scale = s.scale > 0 ? s.scale : 1;
        double left = s.left * scale, right = s.right * scale, top = s.top * scale, bottom = s.bottom * scale;
        double width = x1 - x0, height = y1 - y0;
        if (left + right > width) {
            double k = width / (left + right);
            left *= k;
            right *= k;
        }
        if (top + bottom > height) {
            double k = height / (top + bottom);
            top *= k;
            bottom *= k;
        }
        double[] xs = { x0, x0 + left, x1 - right, x1 };
        double[] ys = { y0, y0 + top, y1 - bottom, y1 };
        int[] us = { s.u, s.u + s.left, s.u + s.w - s.right, s.u + s.w };
        int[] vs = { v, v + s.top, v + s.h - s.bottom, v + s.h };

        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (row == 1 && col == 1 && !s.center) {
                    continue;
                }
                if (xs[col + 1] <= xs[col] || ys[row + 1] <= ys[row]
                    || us[col + 1] <= us[col]
                    || vs[row + 1] <= vs[row]) {
                    continue;
                }
                boolean tileX = s.tile && col == 1, tileY = s.tile && row == 1;
                patch(
                    t,
                    skin,
                    xs[col],
                    ys[row],
                    xs[col + 1],
                    ys[row + 1],
                    us[col],
                    vs[row],
                    us[col + 1],
                    vs[row + 1],
                    tileX ? (us[col + 1] - us[col]) * scale : 0,
                    tileY ? (vs[row + 1] - vs[row]) * scale : 0);
            }
        }
        t.draw();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * One cell of the nine: the atlas rectangle stretched over it, or repeated every {@code tileW} by
     * {@code tileH} GUI pixels (0 to stretch that way).
     */
    private static void patch(Tessellator t, Skin skin, double x0, double y0, double x1, double y1, double u0,
        double v0, double u1, double v1, double tileW, double tileH) {
        double tw = skin.textureWidth, th = skin.textureHeight;
        double stepX = tileW > 0 ? tileW : x1 - x0, stepY = tileH > 0 ? tileH : y1 - y0;
        // A guard against a huge number of quads for a tiny tile.
        if ((x1 - x0) / stepX * ((y1 - y0) / stepY) > 4096) {
            stepX = x1 - x0;
            stepY = y1 - y0;
        }
        for (double y = y0; y < y1 - 1e-6; y += stepY) {
            double yEnd = Math.min(y1, y + stepY);
            double vEnd = v0 + (v1 - v0) * (yEnd - y) / stepY;
            for (double x = x0; x < x1 - 1e-6; x += stepX) {
                double xEnd = Math.min(x1, x + stepX);
                double uEnd = u0 + (u1 - u0) * (xEnd - x) / stepX;
                t.addVertexWithUV(x, yEnd, 0, u0 / tw, vEnd / th);
                t.addVertexWithUV(xEnd, yEnd, 0, uEnd / tw, vEnd / th);
                t.addVertexWithUV(xEnd, y, 0, uEnd / tw, v0 / th);
                t.addVertexWithUV(x, y, 0, u0 / tw, v0 / th);
            }
        }
    }

    /**
     * The sprite repeated along a strip from {@code x0} to {@code x1}, moved left by {@code shift} GUI pixels (a
     * flowing strip), cut at both ends without a scissor, which would undo one the screen is drawing in.
     */
    public static void strip(Sprite s, double x0, double y0, double x1, double y1, double shift) {
        Skin skin = current;
        if (skin == null || s == null || x1 <= x0 || y1 <= y0 || !skin.ready()) {
            return;
        }
        double scale = s.scale > 0 ? s.scale : 1;
        double tile = s.w * scale;
        if (tile <= 0 || (x1 - x0) / tile > 2048) {
            return;
        }
        int frame = s.frames > 1 ? (int) (System.currentTimeMillis() / Math.max(1, s.frameTime) % s.frames) : 0;
        double v = s.v + frame * s.h;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, skin.texture);
        int tint = s.tint;
        GL11.glColor4f(
            (tint >> 16 & 0xFF) / 255f,
            (tint >> 8 & 0xFF) / 255f,
            (tint & 0xFF) / 255f,
            (tint >>> 24 & 0xFF) / 255f);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        double start = x0 - ((shift % tile) + tile) % tile;
        for (double x = start; x < x1; x += tile) {
            double from = Math.max(x0, x), to = Math.min(x1, x + tile);
            if (to <= from) {
                continue;
            }
            double u0 = s.u + (from - x) / scale, u1 = s.u + (to - x) / scale;
            patch(t, skin, from, y0, to, y1, u0, v, u1, v + s.h, 0, 0);
        }
        t.draw();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glDisable(GL11.GL_BLEND);
    }

    /** The two colors multiplied channel by channel, alpha included. */
    static int multiply(int a, int b) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = a >>> shift & 0xFF, y = b >>> shift & 0xFF;
            result |= (x * y + 127) / 255 << shift;
        }
        return result;
    }

    // --- Icons ---

    /** The names of the icons ({@code Icons.CLOSE} is "close"), for their sprites "icon.close". */
    private static Map<String[], String> iconNames;

    /** The sprite that replaces the icon in the theme in use, null for none. */
    static Sprite icon(String[] icon) {
        Skin skin = current;
        if (skin == null || skin.sprites.isEmpty()) {
            return null;
        }
        if (iconNames == null) {
            iconNames = iconNames();
        }
        String name = iconNames.get(icon);
        return name == null ? null : get("icon." + name);
    }

    /** The names of all icons, by the arrays of {@link Icons}. */
    public static Map<String[], String> iconNames() {
        Map<String[], String> names = new IdentityHashMap<>();
        for (java.lang.reflect.Field field : Icons.class.getDeclaredFields()) {
            if (field.getType() == String[].class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    names.put(
                        (String[]) field.get(null),
                        field.getName()
                            .toLowerCase(java.util.Locale.ROOT));
                } catch (IllegalAccessException ignored) {}
            }
        }
        return names;
    }

    /** An empty skin's sprites, for a theme without pictures. */
    static Map<String, Sprite> none() {
        return new HashMap<>();
    }
}
