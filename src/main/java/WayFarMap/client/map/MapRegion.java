package WayFarMap.client.map;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import WayFarMap.client.GlTextures;

/**
 * A 512x512 block area of the map (32x32 chunks). Pixels are ARGB; a pixel with alpha 0 has not been explored. Each
 * pixel can also carry one extra byte (0 = unknown): the height to stand on for the surface map, the biome for the
 * biome map. It is saved next to the image as {@code r.X.Z.dat}.
 */
public class MapRegion implements PixelSource {

    public static final int SIZE = 512;
    public static final int SHIFT = 9;
    /** Chunks per side. */
    public static final int CHUNKS = SIZE / 16;

    /** Upload buffer shared by all regions; uploads only happen on the render thread. */
    private static IntBuffer uploadBuffer;
    /**
     * Changed pixels are sent to the texture at most this often: every upload also rebuilds the mipmaps of the whole
     * 512x512 texture, and around the player the map changes every tick.
     */
    private static final long UPLOAD_INTERVAL_MS = 100;

    public final int rx;
    public final int rz;
    private final int[] pixels = new int[SIZE * SIZE];
    /** Allocated on first use; most maps (caves) never need it. */
    private byte[] extra;
    /**
     * When each of the 32x32 chunks was last mapped (by us or a teammate), in milliseconds; 0 = never. When maps
     * are merged, the newer chunk wins.
     */
    private final long[] chunkTimes = new long[CHUNKS * CHUNKS];
    /**
     * One bit per chunk: its current version came from a teammate, so it isn't ours to upload to a team again (the
     * team has it already). Cleared when we map the chunk ourselves.
     */
    private final long[] fromTeam = new long[CHUNKS * CHUNKS / 64];

    /**
     * Block light (torches, lamps, lava) just above the surface of each pixel, 0-15 in the low bits; null while there
     * is none. At night the map glows there ({@link #bindGlowTexture}). The high bits are the topography's flags (see
     * {@link #TOPO_KNOWN}).
     */
    private byte[] light;
    /** Some pixel has block light (not only topography flags): the glow is drawn. */
    private boolean lit;
    /**
     * Topography flags in the light byte of the map without plants: the pixel was scanned with them (older maps don't
     * have them), and its ground is water or lava.
     */
    public static final int TOPO_KNOWN = 0x40, TOPO_WATER = 0x10, TOPO_LAVA = 0x20;
    private int glowTextureId = -1;
    private boolean glowDirty;
    private long lastGlowUpload;
    /** Light levels of the glow: how much of the lit color shows over the dark map. */
    private static final int[] GLOW_ALPHA = new int[16];
    /** Warm light of torches over the map colors. */
    private static final float GLOW_R = 1.0f, GLOW_G = 0.86f, GLOW_B = 0.62f;
    private static final long GLOW_UPLOAD_INTERVAL_MS = 1000;

    static {
        for (int level = 1; level < 16; level++) {
            GLOW_ALPHA[level] = Math.min(255, (int) (255 * Math.pow(level / 14.0, 1.4)));
        }
    }

    /**
     * The shadow along the edge of the explored land ({@link #bindShadowTexture}): one texel for every
     * {@link #SHADOW_CELL} x {@link #SHADOW_CELL} blocks, smoothed by linear filtering.
     */
    private int shadowTextureId = -1;
    /** {@link #changes} and the missing neighbors the shadow was built for; -1 before it was built. */
    private int shadowChanges = -1, shadowNeighbors = -1;
    private long lastShadowUpload;
    private static final int SHADOW_CELL = 4, SHADOW_SIZE = SIZE / SHADOW_CELL;
    /** How far (in texels) the shadow reaches into the explored land, and the glow out of it. */
    private static final int SHADOW_REACH = 2;
    private static final long SHADOW_UPLOAD_INTERVAL_MS = 1000;
    /** Darkest the shadow gets right at the edge, and the strongest glow, out of 255. */
    private static final int SHADOW_ALPHA = 150, GLOW_EDGE_ALPHA = 30;
    /** The glow outside the edge: the accent color of the mod's screens. */
    private static final int EDGE_GLOW_RGB = 0x4C9AFF;

    private int textureId = -1;
    /** Area changed since the last upload, in local pixel coordinates (inclusive); minX > maxX when clean. */
    private int dirtyMinX, dirtyMinZ, dirtyMaxX = -1, dirtyMaxZ = -1;
    private long lastUpload;
    /** When the area waiting for upload was first changed (for the flat map log). */
    private long dirtySince;
    private volatile boolean saveDirty;
    /** For the log: when the region last went from saved to changed (0 while saved). */
    private volatile long unsavedSince;
    private volatile boolean saving;
    /** Incremented on every change, so derived images (e.g. search highlights) know when to rebuild. */
    private int changes;
    /** {@link #changes} when last saved, for the log. */
    int changesAtSave;

    public MapRegion(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
    }

    public void setPixel(int localX, int localZ, int argb) {
        int index = localZ * SIZE + localX;
        if (pixels[index] != argb) {
            pixels[index] = argb;
            markDirty();
            changes++;
            if (light != null) {
                glowDirty = true;
            }
            if (textureId != -1) {
                markTextureDirty(localX, localZ);
            }
        }
    }

    /** Sets the pixel and its extra byte (0..255, 0 = unknown). */
    public void setPixel(int localX, int localZ, int argb, int extraValue) {
        setPixel(localX, localZ, argb);
        int index = localZ * SIZE + localX;
        if (extra == null) {
            if (extraValue == 0) {
                return;
            }
            extra = new byte[SIZE * SIZE];
        }
        if (extra[index] != (byte) extraValue) {
            extra[index] = (byte) extraValue;
            markDirty();
            changes++;
        }
    }

    /** Sets the block light (0-15) above the pixel's surface, with the topography flags if any. */
    public void setLight(int localX, int localZ, int level) {
        int index = localZ * SIZE + localX;
        if (light == null) {
            if (level == 0) {
                return;
            }
            light = new byte[SIZE * SIZE];
        }
        if (light[index] != (byte) level) {
            if (((light[index] ^ level) & ~15) != 0) {
                // The topography flags changed: images derived from them are rebuilt.
                changes++;
            }
            light[index] = (byte) level;
            markDirty();
            glowDirty = true;
            lit |= (level & 15) != 0;
        }
    }

    /** Light byte of the pixel: block light in the low 4 bits, the topography flags above. */
    @Override
    public int getLight(int localX, int localZ) {
        return light == null ? 0 : light[localZ * SIZE + localX] & 0xFF;
    }

    /** Whether any pixel is lit by a block (the map glows there at night). */
    public boolean hasLight() {
        return lit;
    }

    /** Whether any of the levels has block light (not only topography flags). */
    static boolean anyLit(byte[] levels) {
        if (levels != null) {
            for (byte level : levels) {
                if ((level & 15) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A pixel of the map in the warm light of the given level, as the glow shows it over the dark map. */
    static int glowTexel(int argb, int level) {
        if (level == 0 || (argb >>> 24) == 0) {
            return 0;
        }
        return warmTexel(argb, GLOW_ALPHA[level]);
    }

    /** How much of the lit color the glow shows at the given block light level (0-255). */
    static int glowAlpha(int level) {
        return GLOW_ALPHA[level];
    }

    /** The color in the warm light of torches, with the given opacity. */
    static int warmTexel(int argb, int alpha) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * GLOW_R));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * GLOW_G));
        int b = Math.min(255, (int) ((argb & 0xFF) * GLOW_B));
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    /**
     * Binds the glow texture: the map's colors in warm light where blocks light the surface, transparent elsewhere,
     * drawn over the night-darkened map. Rebuilt at most once a second while it changes. Render thread.
     */
    public void bindGlowTexture() {
        boolean create = glowTextureId == -1;
        if (create) {
            glowTextureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTextureId);
        }
        long now = System.currentTimeMillis();
        if (create || glowDirty && now - lastGlowUpload >= GLOW_UPLOAD_INTERVAL_MS) {
            GlTextures.bindForUpload(glowTextureId);
            glowDirty = false;
            lastGlowUpload = now;
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            byte[] levels = light;
            for (int i = 0; i < SIZE * SIZE; i++) {
                int level = levels == null ? 0 : levels[i] & 15;
                int argb = pixels[i];
                if (level == 0 || (argb >>> 24) == 0) {
                    uploadBuffer.put(0);
                    continue;
                }
                uploadBuffer.put(glowTexel(argb, level));
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
    }

    /**
     * Binds the edge shadow texture: black, fading in over the explored land near unexplored land, and a faint glow
     * over the unexplored side, to be drawn over the map. Land past the region's sides counts as unexplored only where
     * the neighbor region does not exist ({@code missingNeighbors}: bits 1 west, 2 east, 4 north, 8 south). Rebuilt at
     * most once a second while the region changes. Render thread.
     */
    public void bindShadowTexture(int missingNeighbors) {
        boolean create = shadowTextureId == -1;
        if (create) {
            shadowTextureId = GlTextures.create();
            // Linear both ways: a texel covers 4x4 blocks, the shadow must look smooth, not blocky.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTextureId);
        }
        long now = System.currentTimeMillis();
        boolean stale = shadowChanges != changes || shadowNeighbors != missingNeighbors;
        if (create || stale && now - lastShadowUpload >= SHADOW_UPLOAD_INTERVAL_MS) {
            GlTextures.bindForUpload(shadowTextureId);
            shadowChanges = changes;
            shadowNeighbors = missingNeighbors;
            lastShadowUpload = now;
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            uploadBuffer.put(buildShadow(missingNeighbors));
            uploadBuffer.flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                SHADOW_SIZE,
                SHADOW_SIZE,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                uploadBuffer);
        }
    }

    /** The shadow's texels (ARGB), see {@link #bindShadowTexture}. */
    int[] buildShadow(int missingNeighbors) {
        // How much of each cell is explored, 0 to 1.
        float[] explored = new float[SHADOW_SIZE * SHADOW_SIZE];
        float perPixel = 1f / (SHADOW_CELL * SHADOW_CELL);
        for (int z = 0; z < SIZE; z++) {
            int row = (z / SHADOW_CELL) * SHADOW_SIZE;
            for (int x = 0; x < SIZE; x++) {
                if ((pixels[z * SIZE + x] >>> 24) != 0) {
                    explored[row + x / SHADOW_CELL] += perPixel;
                }
            }
        }
        int[] texels = new int[SHADOW_SIZE * SHADOW_SIZE];
        for (int cz = 0; cz < SHADOW_SIZE; cz++) {
            for (int cx = 0; cx < SHADOW_SIZE; cx++) {
                float here = explored[cz * SHADOW_SIZE + cx];
                // The nearest unexplored and explored land around, weaker the farther it is.
                float nearUnexplored = 1 - here, nearExplored = here;
                for (int dz = -SHADOW_REACH; dz <= SHADOW_REACH; dz++) {
                    for (int dx = -SHADOW_REACH; dx <= SHADOW_REACH; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        float weight = 1 - (float) Math.sqrt(dx * dx + dz * dz) / (SHADOW_REACH + 1);
                        if (weight <= 0) {
                            continue;
                        }
                        float other = exploredAt(explored, cx + dx, cz + dz, missingNeighbors);
                        nearUnexplored = Math.max(nearUnexplored, (1 - other) * weight);
                        nearExplored = Math.max(nearExplored, other * weight);
                    }
                }
                int texel;
                if (here >= 0.5f) {
                    // Explored: darker the nearer the edge.
                    texel = Math.round(nearUnexplored * here * SHADOW_ALPHA) << 24;
                } else {
                    // Unexplored: a faint glow of the accent color next to the edge.
                    texel = Math.round(nearExplored * (1 - here) * GLOW_EDGE_ALPHA) << 24 | EDGE_GLOW_RGB;
                }
                texels[cz * SHADOW_SIZE + cx] = texel;
            }
        }
        return texels;
    }

    /**
     * How explored the cell is; past the region's sides, unexplored where the neighbor region does not exist and
     * explored otherwise (its own shadow is drawn by that region).
     */
    private static float exploredAt(float[] explored, int cx, int cz, int missingNeighbors) {
        int side = cx < 0 ? 1 : cx >= SHADOW_SIZE ? 2 : cz < 0 ? 4 : cz >= SHADOW_SIZE ? 8 : 0;
        if (side != 0) {
            return (missingNeighbors & side) != 0 ? 0 : 1;
        }
        return explored[cz * SHADOW_SIZE + cx];
    }

    @Override
    public int getChanges() {
        return changes;
    }

    @Override
    public int size() {
        return SIZE;
    }

    /** The live pixel array, for building the reduced copy on the render thread. */
    int[] pixelArray() {
        return pixels;
    }

    /** The live extra bytes (null if none), for building the reduced copy on the render thread. */
    /** Block light of each pixel's surface (0-15), or null if nothing is lit. */
    byte[] lightArray() {
        return light;
    }

    byte[] extraArray() {
        return extra;
    }

    public boolean hasTexture() {
        return textureId != -1;
    }

    /** @return the extra byte of the pixel, 0 if unknown */
    @Override
    public int getExtra(int localX, int localZ) {
        return extra == null ? 0 : extra[localZ * SIZE + localX] & 0xFF;
    }

    private void markTextureDirty(int localX, int localZ) {
        if (dirtyMaxX < dirtyMinX) {
            dirtySince = System.nanoTime();
            dirtyMinX = dirtyMaxX = localX;
            dirtyMinZ = dirtyMaxZ = localZ;
        } else {
            dirtyMinX = Math.min(dirtyMinX, localX);
            dirtyMaxX = Math.max(dirtyMaxX, localX);
            dirtyMinZ = Math.min(dirtyMinZ, localZ);
            dirtyMaxZ = Math.max(dirtyMaxZ, localZ);
        }
    }

    /** True if any column of the chunk (region-local chunk coordinates) is explored. */
    public boolean hasPixels(int localChunkX, int localChunkZ) {
        for (int z = 0; z < 16; z++) {
            int row = (localChunkZ * 16 + z) * SIZE + localChunkX * 16;
            for (int x = 0; x < 16; x++) {
                if ((pixels[row + x] >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getPixel(int localX, int localZ) {
        return pixels[localZ * SIZE + localX];
    }

    /**
     * Binds the GL texture of this region, uploading only the part that changed since the last call. Must be called on
     * the render thread.
     */
    public void bindTexture() {
        if (textureId == -1) {
            textureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            // Crisp pixels when zoomed in, smooth mipmapped filtering (no flicker while panning) when zoomed out.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // The driver regenerates the mipmaps on every upload.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            // Allocate with the real pixels instead of null + glTexSubImage2D: the null-allocated path
            // renders magenta at 1:4 under Angelica's SDL GPU/Metal renderer (Angelica #2358).
            if (uploadBuffer == null) {
                uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
            }
            uploadBuffer.clear();
            uploadBuffer.put(pixels, 0, SIZE * SIZE)
                .flip();
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
            dirtyMaxX = -1;
            dirtyMinX = 0;
            lastUpload = System.currentTimeMillis();
            FlatLog.textureMade(rx, rz, false);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        }
        long now = System.currentTimeMillis();
        if (dirtyMaxX >= dirtyMinX && (now - lastUpload >= UPLOAD_INTERVAL_MS || isFullyDirty())) {
            lastUpload = now;
            int width = dirtyMaxX - dirtyMinX + 1, height = dirtyMaxZ - dirtyMinZ + 1;
            long start = System.nanoTime();
            GlTextures.bindForUpload(textureId);
            upload(dirtyMinX, dirtyMinZ, width, height);
            if (FlatLog.on()) {
                long end = System.nanoTime();
                FlatLog.uploaded(rx, rz, width, height, end - start, dirtySince == 0 ? 0 : end - dirtySince, false);
            }
            dirtyMaxX = -1;
            dirtyMinX = 0;
        }
    }

    private boolean isFullyDirty() {
        return dirtyMinX == 0 && dirtyMinZ == 0 && dirtyMaxX == SIZE - 1 && dirtyMaxZ == SIZE - 1;
    }

    private void upload(int x, int z, int width, int height) {
        if (uploadBuffer == null) {
            uploadBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
        }
        uploadBuffer.clear();
        for (int row = 0; row < height; row++) {
            uploadBuffer.put(pixels, (z + row) * SIZE + x, width);
        }
        uploadBuffer.flip();
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            x,
            z,
            width,
            height,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            uploadBuffer);
    }

    public void deleteTexture() {
        if (textureId != -1) {
            GlTextures.delete(textureId);
            textureId = -1;
            dirtyMaxX = -1;
            dirtyMinX = 0;
        }
        if (glowTextureId != -1) {
            GlTextures.delete(glowTextureId);
            glowTextureId = -1;
        }
        if (shadowTextureId != -1) {
            GlTextures.delete(shadowTextureId);
            shadowTextureId = -1;
            shadowChanges = -1;
        }
        BiomeHighlight.forget(this);
        Topography.forget(this);
    }

    /** When the chunk (region-local chunk coordinates 0-31) was last mapped; 0 = never. */
    public long getChunkTime(int localChunkX, int localChunkZ) {
        return chunkTimes[localChunkZ * CHUNKS + localChunkX];
    }

    /** Sets when we mapped the chunk ourselves. */
    public void setChunkTime(int localChunkX, int localChunkZ, long time) {
        setChunkTime(localChunkX, localChunkZ, time, false);
    }

    /** Sets when the chunk was mapped, and whether this version of it came from a teammate. */
    public void setChunkTime(int localChunkX, int localChunkZ, long time, boolean teammate) {
        int index = localChunkZ * CHUNKS + localChunkX;
        long bit = 1L << (index & 63);
        boolean wasTeammate = (fromTeam[index >> 6] & bit) != 0;
        if (chunkTimes[index] != time || wasTeammate != teammate) {
            chunkTimes[index] = time;
            if (teammate) {
                fromTeam[index >> 6] |= bit;
            } else {
                fromTeam[index >> 6] &= ~bit;
            }
            markDirty();
        }
    }

    /**
     * Forgets a chunk (region-local chunk coordinates 0-31): its pixels, their extra bytes and light, and when it was
     * mapped, as if it had never been explored.
     *
     * @return whether anything of it was there
     */
    public boolean clearChunk(int localChunkX, int localChunkZ) {
        boolean had = getChunkTime(localChunkX, localChunkZ) != 0;
        for (int z = 0; z < 16; z++) {
            int lz = localChunkZ * 16 + z;
            for (int x = 0; x < 16; x++) {
                int lx = localChunkX * 16 + x;
                int index = lz * SIZE + lx;
                if (pixels[index] != 0) {
                    had = true;
                    setPixel(lx, lz, 0);
                }
                if (extra != null && extra[index] != 0) {
                    extra[index] = 0;
                    markDirty();
                    changes++;
                }
                if (light != null && light[index] != 0) {
                    light[index] = 0;
                    markDirty();
                    glowDirty = true;
                }
            }
        }
        setChunkTime(localChunkX, localChunkZ, 0, false);
        return had;
    }

    /** True if the chunk as we have it came from a teammate (see {@link #setChunkTime(int, int, long, boolean)}). */
    public boolean isFromTeammate(int localChunkX, int localChunkZ) {
        int index = localChunkZ * CHUNKS + localChunkX;
        return (fromTeam[index >> 6] & 1L << (index & 63)) != 0;
    }

    private void markDirty() {
        if (!saveDirty) {
            unsavedSince = System.currentTimeMillis();
        }
        saveDirty = true;
    }

    /** For the log: how long the region has had changes not saved, 0 if none. */
    public long dirtyForMs() {
        return saveDirty && unsavedSince != 0 ? System.currentTimeMillis() - unsavedSince : 0;
    }

    public boolean isSaveDirty() {
        return saveDirty;
    }

    /** Copies the data for saving on another thread and clears the dirty flag. */
    public Snapshot snapshotForSave() {
        saveDirty = false;
        saving = true;
        return new Snapshot(
            pixels.clone(),
            extra != null ? extra.clone() : null,
            chunkTimes.clone(),
            fromTeam.clone(),
            light != null ? light.clone() : null);
    }

    public static final class Snapshot {

        final int[] pixels;
        final byte[] extra;
        final long[] chunkTimes;
        final long[] fromTeam;
        final byte[] light;

        Snapshot(int[] pixels, byte[] extra, long[] chunkTimes, long[] fromTeam, byte[] light) {
            this.pixels = pixels;
            this.extra = extra;
            this.chunkTimes = chunkTimes;
            this.fromTeam = fromTeam;
            this.light = light;
        }
    }

    /** Whether a save of this region is queued or running, i.e. the file on disk may be out of date. */
    public boolean isSaving() {
        return saving;
    }

    public void onSaved() {
        saving = false;
    }

    public static File getFile(File dimensionDir, int rx, int rz) {
        return new File(dimensionDir, "r." + rx + "." + rz + ".png");
    }

    /** Size of the region's files on disk (image, extra, light, times), for the log. */
    static long bytesOnDisk(File imageFile) {
        return imageFile.length() + getExtraFile(imageFile).length()
            + getLightFile(imageFile).length()
            + getTimesFile(imageFile).length();
    }

    /** Which of the region's files are on disk and their sizes in KB, for the log. */
    static String partsOnDisk(File imageFile) {
        StringBuilder parts = new StringBuilder();
        File[] files = { imageFile, getExtraFile(imageFile), getLightFile(imageFile), getTimesFile(imageFile) };
        String[] names = { "png", "dat", "light", "time" };
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) {
                if (parts.length() > 0) {
                    parts.append('+');
                }
                parts.append(names[i])
                    .append(':')
                    .append((files[i].length() + 1023) >> 10)
                    .append("KB");
            }
        }
        return parts.length() == 0 ? "-" : parts.toString();
    }

    /** Deletes the region's files (image, extra, light, times) in the folder. */
    public static void deleteFiles(File dimensionDir, int rx, int rz) {
        File image = getFile(dimensionDir, rx, rz);
        for (File file : new File[] { image, getExtraFile(image), getLightFile(image), getTimesFile(image) }) {
            file.delete();
        }
    }

    private static File getExtraFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".dat");
    }

    private static File getLightFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".light");
    }

    private static File getTimesFile(File imageFile) {
        String path = imageFile.getPath();
        return new File(path.substring(0, path.length() - 4) + ".time");
    }

    /**
     * For the log: what reading or writing a region's files did, part by part (time, size on disk, what went wrong),
     * and what the region holds. Filled on the loader or saver thread, then put on the READ or SAVE line.
     */
    public static final class IoTrace {

        private final StringBuilder parts = new StringBuilder();
        /** Problems that didn't stop the read or the save (a part lost, a move that couldn't be atomic). */
        int warnings;
        String firstWarning;
        /** PNG decoding (read) or encoding (write), and the copy of its pixels, in nanoseconds. */
        long pngNanos, pixelCopyNanos;
        int atomicMoves, plainMoves;
        String content = "";

        void part(String name, long nanos, long bytes, String note) {
            parts.append(' ')
                .append(name)
                .append("[ms=")
                .append(FlatLog.ms(nanos));
            if (bytes >= 0) {
                parts.append(" kb=")
                    .append((bytes + 1023) >> 10);
            }
            if (note != null && !note.isEmpty()) {
                parts.append(' ')
                    .append(note);
            }
            parts.append(']');
        }

        void warn(String what) {
            warnings++;
            if (firstWarning == null) {
                firstWarning = what;
            }
        }

        public int warnings() {
            return warnings;
        }

        public String firstWarning() {
            return firstWarning;
        }

        public long pngNanos() {
            return pngNanos;
        }

        public int plainMoves() {
            return plainMoves;
        }

        @Override
        public String toString() {
            String text = parts.toString()
                .trim();
            if (atomicMoves + plainMoves > 0) {
                text += " moves[atomic=" + atomicMoves + " plain=" + plainMoves + "]";
            }
            if (!content.isEmpty()) {
                text += " " + content;
            }
            if (warnings > 0) {
                text += " warnings=" + warnings + " (first: " + firstWarning + ")";
            }
            return text.isEmpty() ? "-" : text;
        }
    }

    /** What the region holds, for the log: chunks explored, from teammates, with times; extra and light kept. */
    String contentSummary() {
        int explored = 0, timed = 0, team = 0;
        for (int cz = 0; cz < CHUNKS; cz++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                int index = cz * CHUNKS + cx;
                if (hasPixels(cx, cz)) {
                    explored++;
                }
                if (chunkTimes[index] != 0) {
                    timed++;
                }
                if ((fromTeam[index >> 6] & 1L << (index & 63)) != 0) {
                    team++;
                }
            }
        }
        return "content[chunks=" + explored
            + " timed="
            + timed
            + " fromTeam="
            + team
            + " heights="
            + (extra != null ? "yes" : "no")
            + " light="
            + (light != null ? "yes" : "no")
            + "]";
    }

    public static void write(File file, Snapshot snapshot, IoTrace trace) throws IOException {
        writeImage(file, snapshot.pixels, trace);
        if (snapshot.extra != null) {
            writeGzip(getExtraFile(file), snapshot.extra, "dat", trace);
        } else {
            trace.part("dat", 0, -1, "none (no heights)");
        }
        if (snapshot.light != null) {
            writeGzip(getLightFile(file), snapshot.light, "light", trace);
        } else {
            trace.part("light", 0, -1, "none (no light)");
        }
        long start = System.nanoTime();
        File timesFile = getTimesFile(file);
        File tmp = new File(timesFile.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))) {
            for (long time : snapshot.chunkTimes) {
                out.writeLong(time);
            }
            // Added later: older versions stop reading before it.
            for (long bits : snapshot.fromTeam) {
                out.writeLong(bits);
            }
        }
        long written = tmp.length();
        replace(tmp, timesFile, trace);
        trace.part("time", System.nanoTime() - start, written, null);
    }

    private static void writeGzip(File target, byte[] data, String name, IoTrace trace) throws IOException {
        long start = System.nanoTime();
        File tmp = new File(target.getPath() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(tmp))) {
            out.write(data);
        }
        long written = tmp.length();
        replace(tmp, target, trace);
        trace.part(name, System.nanoTime() - start, written, ratio(data.length, written));
    }

    /** "ratio=12%": the size on disk against the raw data. */
    private static String ratio(long raw, long written) {
        return raw <= 0 ? "" : "ratio=" + written * 100 / raw + "%";
    }

    /** Puts the new file in place in one step where the file system can: a crash never leaves no file at all. */
    private static void replace(File tmp, File file, IoTrace trace) throws IOException {
        try {
            Files
                .move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            trace.atomicMoves++;
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            trace.plainMoves++;
            trace.warn("atomic move of " + file.getName() + " failed (" + e + "), replaced in two steps");
        }
    }

    private static void writeImage(File file, int[] data, IoTrace trace) throws IOException {
        long start = System.nanoTime();
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        // Straight into the image's own pixels: setRGB goes through the color model pixel by pixel.
        int[] target = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        System.arraycopy(data, 0, target, 0, SIZE * SIZE);
        trace.pixelCopyNanos = System.nanoTime() - start;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            if (!parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            trace.part("mkdirs", 0, -1, parent.getName());
        }
        File tmp = new File(file.getPath() + ".tmp");
        long encodeStart = System.nanoTime();
        if (!ImageIO.write(image, "png", tmp)) {
            throw new IOException("No PNG writer available");
        }
        trace.pngNanos = System.nanoTime() - encodeStart;
        long written = tmp.length();
        replace(tmp, file, trace);
        trace.part(
            "png",
            System.nanoTime() - start,
            written,
            "encodeMs=" + FlatLog.ms(trace.pngNanos)
                + " copyMs="
                + FlatLog.ms(trace.pixelCopyNanos)
                + " "
                + ratio(SIZE * SIZE * 4L, written));
    }

    public static MapRegion read(File file, int rx, int rz) throws IOException {
        return read(file, rx, rz, new IoTrace());
    }

    public static MapRegion read(File file, int rx, int rz, IoTrace trace) throws IOException {
        long start = System.nanoTime();
        BufferedImage image = ImageIO.read(file);
        trace.pngNanos = System.nanoTime() - start;
        if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
            String what = image == null ? "not a readable image"
                : "size " + image.getWidth() + "x" + image.getHeight() + " instead of " + SIZE + "x" + SIZE;
            trace.part("png", trace.pngNanos, file.length(), "INVALID " + what);
            throw new IOException("Invalid map region image " + file + ": " + what);
        }
        MapRegion region = new MapRegion(rx, rz);
        long copyStart = System.nanoTime();
        boolean fast = copyPixels(image, region.pixels);
        if (!fast) {
            image.getRGB(0, 0, SIZE, SIZE, region.pixels, 0, SIZE);
        }
        trace.pixelCopyNanos = System.nanoTime() - copyStart;
        // A type other than INT_ARGB makes getRGB convert every pixel: slow, worth knowing.
        trace.part(
            "png",
            System.nanoTime() - start,
            file.length(),
            "decodeMs=" + FlatLog.ms(trace.pngNanos)
                + (fast ? " copyMs=" : " getRgbMs=")
                + FlatLog.ms(trace.pixelCopyNanos)
                + " imageType="
                + imageType(image.getType()));
        region.extra = readGzip(getExtraFile(file), "dat", "heights lost, the map still shows", trace);
        region.light = readGzip(getLightFile(file), "light", "night glow lost", trace);
        region.lit = anyLit(region.light);
        File timesFile = getTimesFile(file);
        boolean haveTimes = false;
        long timesStart = System.nanoTime();
        if (timesFile.isFile()) {
            String note = null;
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(timesFile)))) {
                for (int i = 0; i < region.chunkTimes.length; i++) {
                    region.chunkTimes[i] = in.readLong();
                }
                haveTimes = true;
                try {
                    for (int i = 0; i < region.fromTeam.length; i++) {
                        region.fromTeam[i] = in.readLong();
                    }
                    // Older files may go on with bits no longer used: left unread.
                } catch (EOFException e) {
                    // Saved before it was kept: everything counts as ours.
                    note = "old (no teammate bits)";
                }
            } catch (IOException e) {
                // Fall back to the file's date below.
                note = "CORRUPT (" + e + "), times from the file's date";
                trace.warn("time file unreadable: " + e);
            }
            trace.part("time", System.nanoTime() - timesStart, timesFile.length(), note);
        } else {
            trace.part("time", 0, -1, "missing, times from the file's date");
        }
        if (!haveTimes) {
            // Saved before chunk times existed: every explored chunk counts as mapped when the file was written.
            long modified = file.lastModified();
            for (int cz = 0; cz < CHUNKS; cz++) {
                for (int cx = 0; cx < CHUNKS; cx++) {
                    if (region.hasPixels(cx, cz)) {
                        region.chunkTimes[cz * CHUNKS + cx] = modified;
                    }
                }
            }
        }
        if (FlatLog.on()) {
            trace.content = region.contentSummary();
        }
        return region;
    }

    /**
     * Copies a decoded region picture into ARGB pixels straight from its raster, for the layouts PNG decoding gives
     * (INT_ARGB, and 4BYTE_ABGR for pictures with alpha). False for any other layout: getRGB is used then, which goes
     * through the color model pixel by pixel and took longer than decoding the PNG.
     */
    private static boolean copyPixels(BufferedImage image, int[] pixels) {
        Raster raster = image.getRaster();
        if (raster.getMinX() != 0 || raster.getMinY() != 0
            || raster.getSampleModelTranslateX() != 0
            || raster.getSampleModelTranslateY() != 0) {
            return false;
        }
        if (image.getType() == BufferedImage.TYPE_INT_ARGB && raster.getDataBuffer() instanceof DataBufferInt) {
            DataBufferInt buffer = (DataBufferInt) raster.getDataBuffer();
            if (buffer.getNumBanks() != 1 || buffer.getOffset() != 0 || buffer.getSize() < SIZE * SIZE) {
                return false;
            }
            System.arraycopy(buffer.getData(), 0, pixels, 0, SIZE * SIZE);
            return true;
        }
        if (image.getType() != BufferedImage.TYPE_4BYTE_ABGR || !(raster.getDataBuffer() instanceof DataBufferByte)) {
            return false;
        }
        SampleModel model = raster.getSampleModel();
        if (!(model instanceof PixelInterleavedSampleModel)) {
            return false;
        }
        PixelInterleavedSampleModel interleaved = (PixelInterleavedSampleModel) model;
        int[] offsets = interleaved.getBandOffsets();
        DataBufferByte buffer = (DataBufferByte) raster.getDataBuffer();
        if (interleaved.getPixelStride() != 4 || interleaved.getScanlineStride() != SIZE * 4
            || offsets.length != 4
            || buffer.getNumBanks() != 1
            || buffer.getOffset() != 0) {
            return false;
        }
        // Bands are R, G, B, A at these byte offsets in each pixel.
        int r = offsets[0], g = offsets[1], b = offsets[2], a = offsets[3];
        byte[] data = buffer.getData();
        if (data.length < SIZE * SIZE * 4) {
            return false;
        }
        for (int i = 0, p = 0; i < SIZE * SIZE; i++, p += 4) {
            pixels[i] = (data[p + a] & 0xFF) << 24 | (data[p + r] & 0xFF) << 16
                | (data[p + g] & 0xFF) << 8
                | data[p + b] & 0xFF;
        }
        return true;
    }

    /** A gzip part of the region (heights or light), null if missing or unreadable (the rest is still fine). */
    private static byte[] readGzip(File part, String name, String lost, IoTrace trace) {
        if (!part.isFile()) {
            trace.part(name, 0, -1, "missing");
            return null;
        }
        long start = System.nanoTime();
        byte[] data = new byte[SIZE * SIZE];
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(part)))) {
            in.readFully(data);
            trace.part(name, System.nanoTime() - start, part.length(), null);
            return data;
        } catch (IOException e) {
            trace.part(name, System.nanoTime() - start, part.length(), "CORRUPT (" + e + ")");
            trace.warn(name + " file unreadable, " + lost + ": " + e);
            return null;
        }
    }

    private static String imageType(int type) {
        switch (type) {
            case BufferedImage.TYPE_INT_ARGB:
                return "INT_ARGB";
            case BufferedImage.TYPE_4BYTE_ABGR:
                return "4BYTE_ABGR";
            case BufferedImage.TYPE_3BYTE_BGR:
                return "3BYTE_BGR";
            case BufferedImage.TYPE_INT_RGB:
                return "INT_RGB";
            case BufferedImage.TYPE_CUSTOM:
                return "CUSTOM";
            default:
                return String.valueOf(type);
        }
    }
}
