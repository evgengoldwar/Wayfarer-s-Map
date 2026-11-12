package WayFarMap.client;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.Config;

/**
 * The player marker as a texture, drawn from the exact distance to its shape: smooth edges and an even outline at any
 * size and angle, where triangles drawn straight to the screen come out jagged. Every mipmap level is drawn the same
 * way (not shrunk from the one above), so the small marker of the minimap stays sharp.
 */
final class PlayerMarkerTexture {

    /** Pixels per side of the largest level. */
    private static final int SIZE = 128;
    /** Half the width of the texture, in marker sizes: the shape with its outline fits inside. */
    static final double EXTENT = 1.6;
    /** Width of the outline, in marker sizes (about one GUI pixel on the minimap). */
    private static final double OUTLINE = 0.24;
    /** Textures kept (looks and colors seen lately). */
    private static final int CACHED = 8;

    private static final Map<Long, Integer> TEXTURES = new LinkedHashMap<>(16, 0.75f, true);
    private static IntBuffer buffer;

    private PlayerMarkerTexture() {}

    /** Binds the texture of the marker look in these colors (opaque RGB; outline 0 = none). Render thread. */
    static void bind(int style, int color, int outline) {
        long key = (long) style << 56 | (long) (color & 0xFFFFFF) << 32 | outline & 0xFFFFFFFFL;
        Integer id = TEXTURES.get(key);
        if (id != null) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            return;
        }
        if (TEXTURES.size() >= CACHED) {
            Iterator<Integer> eldest = TEXTURES.values()
                .iterator();
            GlTextures.delete(eldest.next());
            eldest.remove();
        }
        id = GlTextures.create();
        TEXTURES.put(key, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        if (buffer == null) {
            buffer = BufferUtils.createIntBuffer(SIZE * SIZE);
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        int level = 0;
        for (int size = SIZE; size >= 1; size /= 2, level++) {
            buffer.clear();
            draw(buffer, size, style, color, outline);
            buffer.flip();
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                level,
                GL11.GL_RGBA,
                size,
                size,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                buffer);
        }
    }

    /** One level of the texture: the shape filled with the color, the outline around it, antialiased by distance. */
    private static void draw(IntBuffer out, int size, int style, int color, int outline) {
        double pixel = 2 * EXTENT / size;
        boolean outlined = outline != 0;
        int edgeRgb = (outlined ? outline : color) & 0xFFFFFF;
        for (int j = 0; j < size; j++) {
            double y = -EXTENT + (j + 0.5) * pixel;
            for (int i = 0; i < size; i++) {
                double x = -EXTENT + (i + 0.5) * pixel;
                double d = distance(style, x, y);
                double fill = coverage(d, pixel);
                double alpha = outlined ? coverage(d - OUTLINE, pixel) : fill;
                int a = (int) Math.round(alpha * 255);
                // Fully clear pixels keep the edge color, so filtering doesn't darken the edge.
                int rgb = outlined ? mix(edgeRgb, color & 0xFFFFFF, fill) : edgeRgb;
                out.put(a << 24 | rgb);
            }
        }
    }

    /** How much of a pixel the inside covers, from the distance to the edge (negative inside). */
    private static double coverage(double d, double pixel) {
        return Math.max(0, Math.min(1, 0.5 - d / pixel));
    }

    private static int mix(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    // Shapes pointing up (-y), in marker sizes; same proportions as the triangles drawn before.
    private static final double[] TRIANGLE = { 0, -1, 0.7, 0.8, -0.7, 0.8 };
    private static final double[] CHEVRON = { 0, -1, 0.8, 0.85, 0, -0.15, -0.8, 0.85 };
    private static final double[] KITE = { 0, -1, 0.6, 0.3, 0, 0.8, -0.6, 0.3 };
    private static final double[] ARROW = { 0, -1, 0.75, 1, 0, 0.45, -0.75, 1 };
    /** Radius of the circle and the dot. */
    private static final double DISC = 0.6;
    /**
     * Point of the circle's nose: its sides touch the circle (a drop, without corners where they meet), at 60 degrees
     * from the point as {@code DISC / NOSE = cos 60}.
     */
    private static final double NOSE = 1.2;
    private static final double[] DROP = { 0, -NOSE, DISC * Math.sin(Math.PI / 3), -DISC * Math.cos(Math.PI / 3), 0, 0,
        -DISC * Math.sin(Math.PI / 3), -DISC * Math.cos(Math.PI / 3) };

    /** Signed distance from the point to the marker's shape, negative inside. */
    private static double distance(int style, double x, double y) {
        switch (style) {
            case Config.MARKER_TRIANGLE:
                return polygon(TRIANGLE, x, y);
            case Config.MARKER_CHEVRON:
                return polygon(CHEVRON, x, y);
            case Config.MARKER_KITE:
                return polygon(KITE, x, y);
            case Config.MARKER_CIRCLE:
                return Math.min(Math.hypot(x, y) - DISC, polygon(DROP, x, y));
            case Config.MARKER_DOT:
                return Math.hypot(x, y) - DISC;
            default:
                return polygon(ARROW, x, y);
        }
    }

    /** Signed distance to a polygon {x0, y0, x1, y1, ...} (any simple polygon, convex or not). */
    private static double polygon(double[] v, double px, double py) {
        int n = v.length / 2;
        double best = (px - v[0]) * (px - v[0]) + (py - v[1]) * (py - v[1]);
        double sign = 1;
        for (int i = 0, j = n - 1; i < n; j = i, i++) {
            double xi = v[2 * i], yi = v[2 * i + 1], xj = v[2 * j], yj = v[2 * j + 1];
            double ex = xj - xi, ey = yj - yi;
            double wx = px - xi, wy = py - yi;
            double t = Math.max(0, Math.min(1, (wx * ex + wy * ey) / (ex * ex + ey * ey)));
            double bx = wx - ex * t, by = wy - ey * t;
            best = Math.min(best, bx * bx + by * by);
            boolean c1 = py >= yi, c2 = py < yj, c3 = ex * wy > ey * wx;
            if (c1 && c2 && c3 || !c1 && !c2 && !c3) {
                sign = -sign;
            }
        }
        return sign * Math.sqrt(best);
    }
}
