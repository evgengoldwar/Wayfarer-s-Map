package WayFarMap.client.map;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import WayFarMap.client.GlTextures;

/**
 * Biome search on the biome map: everything that doesn't match is covered in gray, and the edges of the matching
 * biomes are outlined. One overlay texture per region, rebuilt when the search or the region changes.
 */
public final class BiomeHighlight {

    private static final int GRAY = 0xC8303438;
    private static final int OUTLINE = 0xFFFFD34D;
    /** Rebuilding a region's overlay while it is being explored is throttled to this interval. */
    private static final long REBUILD_MS = 1000;

    private static String query = "";
    private static int queryVersion;
    /** Which biome ids match the query. */
    private static final boolean[] matches = new boolean[256];

    private static final class Overlay {

        int textureId = -1;
        int queryVersion = -1;
        int regionChanges = -1;
        long builtAt;
    }

    private static final Map<PixelSource, Overlay> OVERLAYS = new WeakHashMap<>();
    /** Overlays (re)built per frame; a new search over many regions spreads over a few frames. */
    private static final int BUILDS_PER_FRAME = 4, LOD_BUILDS_PER_FRAME = 32;
    private static int buildsLeft;
    private static IntBuffer buffer;

    private BiomeHighlight() {}

    public static boolean isActive() {
        return !query.isEmpty();
    }

    /** Sets the search text; biome names containing it (ignoring case) are highlighted. */
    public static void setQuery(String text) {
        String q = text == null ? ""
            : text.trim()
                .toLowerCase(Locale.ROOT);
        if (q.equals(query)) {
            return;
        }
        query = q;
        queryVersion++;
        BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
        for (int id = 0; id < matches.length; id++) {
            BiomeGenBase biome = id < biomes.length ? biomes[id] : null;
            matches[id] = !q.isEmpty() && biome != null
                && biome.biomeName != null
                && biome.biomeName.toLowerCase(Locale.ROOT)
                    .contains(q);
        }
    }

    /** Draws the highlight over the biome map in the given rectangle (same geometry as MapDrawer#drawMap). */
    public static void draw(MapDimension biomeMap, double centerX, double centerZ, double scale, int x, int y,
        int width, int height) {
        if (!isActive() || biomeMap == null) {
            return;
        }
        double left = centerX - width / 2.0 / scale;
        double top = centerZ - height / 2.0 / scale;
        double right = left + width / scale;
        double bottom = top + height / scale;
        int rx0 = (int) Math.floor(left) >> MapRegion.SHIFT;
        int rz0 = (int) Math.floor(top) >> MapRegion.SHIFT;
        int rx1 = (int) Math.floor(right) >> MapRegion.SHIFT;
        int rz1 = (int) Math.floor(bottom) >> MapRegion.SHIFT;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        Tessellator tessellator = Tessellator.instance;
        // Same resolution as the map under it.
        boolean lod = WayFarMap.client.MapDrawer.useLod(scale);
        buildsLeft = lod ? LOD_BUILDS_PER_FRAME : BUILDS_PER_FRAME;
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                PixelSource region = lod ? biomeMap.requestLod(rx, rz) : biomeMap.getLoadedRegion(rx, rz);
                if (region == null) {
                    continue;
                }
                double regionX = (double) rx * MapRegion.SIZE;
                double regionZ = (double) rz * MapRegion.SIZE;
                double bx0 = Math.max(left, regionX);
                double bz0 = Math.max(top, regionZ);
                double bx1 = Math.min(right, regionX + MapRegion.SIZE);
                double bz1 = Math.min(bottom, regionZ + MapRegion.SIZE);
                if (bx1 <= bx0 || bz1 <= bz0) {
                    continue;
                }
                int texture = overlayTexture(region);
                if (texture == -1) {
                    continue;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                double u0 = (bx0 - regionX) / MapRegion.SIZE;
                double v0 = (bz0 - regionZ) / MapRegion.SIZE;
                double u1 = (bx1 - regionX) / MapRegion.SIZE;
                double v1 = (bz1 - regionZ) / MapRegion.SIZE;
                double sx0 = x + (bx0 - left) * scale;
                double sy0 = y + (bz0 - top) * scale;
                double sx1 = x + (bx1 - left) * scale;
                double sy1 = y + (bz1 - top) * scale;
                tessellator.startDrawingQuads();
                tessellator.addVertexWithUV(sx0, sy1, 0, u0, v1);
                tessellator.addVertexWithUV(sx1, sy1, 0, u1, v1);
                tessellator.addVertexWithUV(sx1, sy0, 0, u1, v0);
                tessellator.addVertexWithUV(sx0, sy0, 0, u0, v0);
                tessellator.draw();
            }
        }
    }

    /** Frees all overlay textures (e.g. when leaving the world). */
    public static void clear() {
        Iterator<Overlay> it = OVERLAYS.values()
            .iterator();
        while (it.hasNext()) {
            Overlay overlay = it.next();
            if (overlay.textureId != -1) {
                GlTextures.delete(overlay.textureId);
            }
            it.remove();
        }
    }

    /** Frees the overlay of a region or reduced copy whose own texture is freed. */
    static void forget(PixelSource source) {
        Overlay overlay = OVERLAYS.remove(source);
        if (overlay != null && overlay.textureId != -1) {
            GlTextures.delete(overlay.textureId);
        }
    }

    /** Overlay texture of the region, or -1 while it waits for its turn to be built. */
    private static int overlayTexture(PixelSource region) {
        Overlay overlay = OVERLAYS.get(region);
        if (overlay == null) {
            overlay = new Overlay();
            OVERLAYS.put(region, overlay);
        }
        long now = System.currentTimeMillis();
        boolean stale = overlay.queryVersion != queryVersion
            || (overlay.regionChanges != region.getChanges() && now - overlay.builtAt >= REBUILD_MS);
        if (stale && buildsLeft <= 0) {
            // Out of budget this frame: an outdated overlay is still better than a flash of the plain map.
            return overlay.queryVersion == -1 || overlay.textureId == -1 ? -1 : overlay.textureId;
        }
        if (stale) {
            buildsLeft--;
        }
        if (overlay.textureId == -1) {
            overlay.textureId = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
            stale = true;
        }
        if (stale) {
            build(region);
            GlTextures.bindForUpload(overlay.textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                region.size(),
                region.size(),
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                buffer);
            overlay.queryVersion = queryVersion;
            overlay.regionChanges = region.getChanges();
            overlay.builtAt = now;
        }
        return overlay.textureId;
    }

    /** Fills {@link #buffer} with the overlay of the region: gray where no match, outline on the matches' edges. */
    private static void build(PixelSource region) {
        int size = region.size();
        if (buffer == null) {
            buffer = BufferUtils.createIntBuffer(MapRegion.SIZE * MapRegion.SIZE);
        }
        buffer.clear();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int state = state(region, x, z);
                int argb;
                if (state == 0) {
                    argb = 0; // unexplored
                } else if (state == 1) {
                    argb = GRAY;
                } else {
                    boolean edge = state(region, x - 1, z) == 1 || state(region, x + 1, z) == 1
                        || state(region, x, z - 1) == 1
                        || state(region, x, z + 1) == 1;
                    argb = edge ? OUTLINE : 0;
                }
                buffer.put(argb);
            }
        }
        buffer.flip();
    }

    /** 0 = unexplored (or outside the region), 1 = explored but not matching, 2 = matching. */
    private static int state(PixelSource region, int x, int z) {
        int size = region.size();
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return 0;
        }
        int id = region.getExtra(x, z);
        if (id == 0) {
            return (region.getPixel(x, z) >>> 24) == 0 ? 0 : 1;
        }
        return matches[(id - 1) & 0xFF] ? 2 : 1;
    }
}
