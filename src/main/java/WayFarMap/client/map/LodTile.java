package WayFarMap.client.map;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import WayFarMap.client.GlTextures;

/**
 * A region shrunk 4 times (128x128 pixels, one per 4x4 blocks) for drawing the map zoomed out: a full region takes
 * about 1.3 MB of memory and as much video memory, so hundreds of them on screen would not fit. The tile keeps only
 * the reduced pixels; the full region is not kept in memory for it.
 */
public final class LodTile implements PixelSource {

    public static final int FACTOR = 4;
    public static final int SIZE = MapRegion.SIZE / FACTOR;

    private static IntBuffer uploadBuffer;

    private final int[] pixels = new int[SIZE * SIZE];
    /**
     * How many of each pixel's 4x4 blocks are explored: the texture shows the pixel that much opaque, as the full
     * region looks from as far out. A lone lava block seen through a hole in unexplored land is a faint speck, not a
     * whole bright pixel.
     */
    private final byte[] coverage = new byte[SIZE * SIZE];
    /** Extra byte of one explored block per pixel (biome for the biome map); null if the region has none. */
    private byte[] extra;
    /**
     * Block light per pixel, the brightest of its 4x4 blocks, with the topography flags; null if the region has none.
     * The night glow is drawn from {@link #glow}.
     */
    private byte[] light;
    /**
     * The night glow per pixel ({@link MapRegion#glowTexel}) averaged over its 4x4 blocks, as the full region's glow
     * looks from as far out: a single torch or lava block lights a sixteenth of the pixel, not all of it. Null while
     * nothing in the region is lit.
     */
    private int[] glow;
    /** Some pixel has block light (not only topography flags). */
    private boolean lit;
    private int glowTextureId = -1;
    private boolean glowPending;
    /** {@link MapRegion#getChanges()} of the region it was built from, -1 when built from the file. */
    int sourceChanges = -1;
    /** When it was last built, to rebuild regions that change all the time (around the player) only now and then. */
    long builtAt;
    private int changes;
    private int textureId = -1;
    private boolean uploadPending;

    /** Reduces a full region's pixels; safe on any thread when the arrays aren't changed meanwhile. */
    static LodTile of(int[] regionPixels, byte[] regionExtra, byte[] regionLight) {
        LodTile tile = new LodTile();
        tile.fill(regionPixels, regionExtra, regionLight);
        return tile;
    }

    /** Rebuilds the tile from the live region (render thread). */
    void update(MapRegion region) {
        fill(region.pixelArray(), region.extraArray(), region.lightArray());
        sourceChanges = region.getChanges();
        builtAt = System.currentTimeMillis();
    }

    private void fill(int[] source, byte[] sourceExtra, byte[] sourceLight) {
        lit = false;
        if (sourceLight == null) {
            glow = null;
        }
        if (sourceExtra != null && extra == null) {
            extra = new byte[SIZE * SIZE];
        }
        if (sourceLight == null) {
            light = null;
        } else if (light == null) {
            light = new byte[SIZE * SIZE];
        }
        int full = MapRegion.SIZE;
        for (int tz = 0; tz < SIZE; tz++) {
            for (int tx = 0; tx < SIZE; tx++) {
                // Average color of the explored blocks of the 4x4 square.
                int r = 0, g = 0, b = 0, count = 0, extraValue = 0, lightValue = 0;
                // Topography flags: known if any block has them, water or lava if most of the known ones are.
                int known = 0, water = 0, lava = 0;
                // The glow's opacity summed over the blocks, and its colors weighted by it.
                int glowAlpha = 0, glowR = 0, glowG = 0, glowB = 0;
                for (int dz = 0; dz < FACTOR; dz++) {
                    int row = (tz * FACTOR + dz) * full + tx * FACTOR;
                    for (int dx = 0; dx < FACTOR; dx++) {
                        int argb = source[row + dx];
                        if ((argb >>> 24) == 0) {
                            continue;
                        }
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        count++;
                        if (extraValue == 0 && sourceExtra != null) {
                            extraValue = sourceExtra[row + dx] & 0xFF;
                        }
                        if (sourceLight != null) {
                            int level = sourceLight[row + dx];
                            lightValue = Math.max(lightValue, level & 15);
                            if ((level & 15) != 0) {
                                int alpha = MapRegion.glowAlpha(level & 15);
                                glowAlpha += alpha;
                                glowR += alpha * ((argb >> 16) & 0xFF);
                                glowG += alpha * ((argb >> 8) & 0xFF);
                                glowB += alpha * (argb & 0xFF);
                            }
                            if ((level & MapRegion.TOPO_KNOWN) != 0) {
                                known++;
                                water += (level & MapRegion.TOPO_WATER) != 0 ? 1 : 0;
                                lava += (level & MapRegion.TOPO_LAVA) != 0 ? 1 : 0;
                            }
                        }
                    }
                }
                int index = tz * SIZE + tx;
                pixels[index] = count == 0 ? 0 : 0xFF000000 | (r / count) << 16 | (g / count) << 8 | (b / count);
                coverage[index] = (byte) count;
                if (extra != null) {
                    extra[index] = (byte) extraValue;
                }
                if (light != null) {
                    int flags = known == 0 ? 0
                        : MapRegion.TOPO_KNOWN | (water * 2 > known ? MapRegion.TOPO_WATER : 0)
                            | (lava * 2 > known ? MapRegion.TOPO_LAVA : 0);
                    light[index] = (byte) (lightValue | flags);
                    if (glowAlpha != 0) {
                        if (glow == null) {
                            glow = new int[SIZE * SIZE];
                        }
                        lit = true;
                    }
                }
                if (glow != null) {
                    glow[index] = glowAlpha == 0 ? 0
                        : MapRegion.warmTexel(
                            0xFF000000 | glowR / glowAlpha << 16 | glowG / glowAlpha << 8 | glowB / glowAlpha,
                            glowAlpha / (FACTOR * FACTOR));
                }
            }
        }
        changes++;
        uploadPending = true;
        glowPending = true;
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int getLight(int localX, int localZ) {
        return light == null ? 0 : light[localZ * SIZE + localX] & 0xFF;
    }

    @Override
    public int getPixel(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    @Override
    public int getExtra(int localX, int localZ) {
        return extra == null ? 0 : extra[localZ * SIZE + localX] & 0xFF;
    }

    @Override
    public int getChanges() {
        return changes;
    }

    public boolean hasTexture() {
        return textureId != -1;
    }

    /** Binds the tile's texture, uploading it if it changed. Render thread only. */
    public void bindTexture() {
        if (textureId == -1) {
            textureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            uploadPending = true;
            FlatLog.textureMade(0, 0, true);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        }
        if (uploadPending) {
            GlTextures.bindForUpload(textureId);
            uploadPending = false;
            long start = System.nanoTime();
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            for (int i = 0; i < SIZE * SIZE; i++) {
                int alpha = coverage[i] * 255 / (FACTOR * FACTOR);
                uploadBuffer.put(alpha << 24 | pixels[i] & 0xFFFFFF);
            }
            uploadBuffer.flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                SIZE,
                SIZE,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                uploadBuffer);
            if (FlatLog.on()) {
                FlatLog.uploaded(0, 0, SIZE, SIZE, System.nanoTime() - start, 0, true);
            }
        }
    }

    /** Whether any pixel is lit by a block (the map glows there at night). */
    public boolean hasLight() {
        return lit;
    }

    /**
     * Binds the glow texture: the tile's colors in warm light where blocks light the surface, transparent elsewhere,
     * drawn over the night-darkened map (zoomed out, the full regions' glow isn't drawn). Render thread only.
     */
    public void bindGlowTexture() {
        if (glowTextureId == -1) {
            glowTextureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            glowPending = true;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTextureId);
        }
        if (!glowPending) {
            return;
        }
        GlTextures.bindForUpload(glowTextureId);
        glowPending = false;
        if (uploadBuffer == null) {
            uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
        }
        uploadBuffer.clear();
        int[] texels = glow;
        for (int i = 0; i < SIZE * SIZE; i++) {
            uploadBuffer.put(texels == null ? 0 : texels[i]);
        }
        uploadBuffer.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            SIZE,
            SIZE,
            0,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            uploadBuffer);
    }

    public void deleteTexture() {
        if (textureId != -1) {
            GlTextures.delete(textureId);
            textureId = -1;
        }
        if (glowTextureId != -1) {
            GlTextures.delete(glowTextureId);
            glowTextureId = -1;
        }
        BiomeHighlight.forget(this);
        Topography.forget(this);
    }
}
