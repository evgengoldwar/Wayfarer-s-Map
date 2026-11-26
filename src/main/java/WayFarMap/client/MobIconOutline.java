package WayFarMap.client;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.renderer.texture.TextureUtil;

/** An icon's alpha mask, expanded by a configurable number of screen pixels. Render thread only. */
final class MobIconOutline {

    private static final int MAX_MASKS = 256, MAX_PIXELS = 2 * 1024 * 1024;
    private static final Map<MaskKey, Integer> MASKS = new LinkedHashMap<>(64, 0.75f, true);
    private static int cachedPixels;

    private final int width, height;
    private final byte[] alpha;
    private final boolean pixelArt;

    MobIconOutline(int width, int height, int[] argb, boolean pixelArt) {
        this.width = width;
        this.height = height;
        this.pixelArt = pixelArt;
        alpha = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            alpha[i] = (byte) (argb[i] >>> 24);
        }
    }

    static void clear() {
        for (int texture : MASKS.values()) {
            GlTextures.delete(texture);
        }
        MASKS.clear();
        cachedPixels = 0;
    }

    void draw(double x, double y, float size, double pixel, int thickness, int color) {
        if (thickness <= 0 || color >>> 24 == 0) {
            return;
        }
        int side = Math.max(1, (int) Math.round(size / pixel));
        MaskKey key = new MaskKey(this, side, thickness);
        Integer texture = MASKS.get(key);
        if (texture == null) {
            int span = side + 2 * thickness;
            BufferedImage image = new BufferedImage(span, span, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, span, span, ring(side, thickness), 0, span);
            texture = GlTextures.create();
            TextureUtil.uploadTextureImageAllocate(texture, image, false, true);
            MASKS.put(key, texture);
            cachedPixels += key.area();
            Iterator<Map.Entry<MaskKey, Integer>> oldest = MASKS.entrySet()
                .iterator();
            while (MASKS.size() > 1 && (MASKS.size() > MAX_MASKS || cachedPixels > MAX_PIXELS)) {
                Map.Entry<MaskKey, Integer> entry = oldest.next();
                GlTextures.delete(entry.getValue());
                cachedPixels -= entry.getKey()
                    .area();
                oldest.remove();
            }
        }
        MobIcons.drawTexture(texture, x, y, size * (0.5 + thickness / (double) side), color);
    }

    /** White coverage for the tint pass. Subtract the icon so the tint never fills its opaque pixels. */
    int[] ring(int side, int thickness) {
        int span = side + 2 * thickness;
        int[] center = new int[span * span], across = new int[center.length], ring = new int[center.length];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                center[(y + thickness) * span + x + thickness] = sample(x, y, side);
            }
        }
        // Two one-dimensional maxima give a square expansion with corners joined on the pixel grid.
        for (int y = thickness; y < span - thickness; y++) {
            int row = y * span;
            for (int x = 0; x < span; x++) {
                int peak = 0;
                for (int nx = Math.max(0, x - thickness); nx <= Math.min(span - 1, x + thickness); nx++) {
                    peak = Math.max(peak, center[row + nx]);
                }
                across[row + x] = peak;
            }
        }
        for (int y = 0; y < span; y++) {
            for (int x = 0; x < span; x++) {
                int peak = 0;
                for (int ny = Math.max(0, y - thickness); ny <= Math.min(span - 1, y + thickness); ny++) {
                    peak = Math.max(peak, across[ny * span + x]);
                }
                int i = y * span + x;
                ring[i] = (peak - center[i]) << 24 | 0xFFFFFF;
            }
        }
        return ring;
    }

    private int sample(int x, int y, int side) {
        if (pixelArt || side >= Math.max(width, height)) {
            int sx = (int) ((x + 0.5) * width / side), sy = (int) ((y + 0.5) * height / side);
            return alpha[sy * width + sx] & 255;
        }
        // The generated icons use mipmaps. Average the source coverage when scaling them down too.
        int x0 = x * width / side, x1 = Math.max(x0 + 1, (x + 1) * width / side);
        int y0 = y * height / side, y1 = Math.max(y0 + 1, (y + 1) * height / side);
        int total = 0;
        for (int sy = y0; sy < y1; sy++) {
            for (int sx = x0; sx < x1; sx++) {
                total += alpha[sy * width + sx] & 255;
            }
        }
        return total / ((x1 - x0) * (y1 - y0));
    }

    private static final class MaskKey {

        private final MobIconOutline source;
        private final int side, thickness;

        MaskKey(MobIconOutline source, int side, int thickness) {
            this.source = source;
            this.side = side;
            this.thickness = thickness;
        }

        int area() {
            int span = side + 2 * thickness;
            return span * span;
        }

        @Override
        public int hashCode() {
            return (source.hashCode() * 31 + side) * 31 + thickness;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MaskKey)) {
                return false;
            }
            MaskKey key = (MaskKey) other;
            return source == key.source && side == key.side && thickness == key.thickness;
        }
    }
}
