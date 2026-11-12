package WayFarMap.client;

import org.lwjgl.opengl.GL11;

/**
 * Making, binding for an upload, and deleting the mod's GL textures so the upload always goes into the texture meant.
 * <p>
 * Renderers that keep the bound texture to skip binding it again (Angelica's state cache) can be wrong about it after
 * a texture is deleted: the graphics driver hands the same number to the next new texture, the bind of it is skipped
 * as already done, and its pixels went into whichever texture was really bound. Map regions showed another region's
 * map, mob icons or player markers then. Binding 0 first makes every bind here a real one.
 */
public final class GlTextures {

    private GlTextures() {}

    /** A new texture, bound. Render thread. */
    public static int create() {
        int id = GL11.glGenTextures();
        bindForUpload(id);
        return id;
    }

    /** Binds the texture for sure, before its pixels are uploaded. Render thread. */
    public static void bindForUpload(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    /** Deletes the texture, with nothing left bound that a cache could take for it. Render thread. */
    public static void delete(int id) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glDeleteTextures(id);
    }
}
