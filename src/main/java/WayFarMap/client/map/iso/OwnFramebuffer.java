package WayFarMap.client.map.iso;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import WayFarMap.client.GlTextures;

/**
 * An off-screen buffer of the mod's own for pictures of blocks and icons. The game's ({@code Framebuffer}) is made only
 * while the game's framebuffers are on ({@code fboEnable}, which other mods may turn off too): without them nothing
 * was drawn off screen and every block was drawn from its icons, machines and microblocks as stone. This one only
 * needs the graphics card to have framebuffers (OpenGL 3.0 or EXT_framebuffer_object, nearly all of them). Render
 * thread only.
 */
final class OwnFramebuffer {

    /** GL_FRAMEBUFFER_BINDING (the same for the EXT and core versions). */
    static final int BINDING = 0x8CA6;
    private static Boolean supported;
    private static boolean core;

    final int width, height;
    private int framebuffer = -1, texture = -1, depth = -1;

    /** Whether the graphics card has framebuffers. */
    static boolean supported() {
        if (supported == null) {
            try {
                ContextCapabilities capabilities = GLContext.getCapabilities();
                core = capabilities.OpenGL30;
                supported = capabilities.OpenGL30 || capabilities.GL_EXT_framebuffer_object;
            } catch (Throwable t) {
                supported = false;
            }
        }
        return supported;
    }

    /** For the log: which framebuffers are used. */
    static String kind() {
        return !supported() ? "none" : core ? "OpenGL 3.0" : "EXT_framebuffer_object";
    }

    /** Binds a framebuffer by its name (0 for the screen). */
    static void bind(int name) {
        if (core) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, name);
        } else {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, name);
        }
    }

    /**
     * Makes it (render thread, {@link #supported} true): an RGBA color texture and, if asked, a depth buffer.
     *
     * @throws IllegalStateException if the graphics card doesn't take it
     */
    OwnFramebuffer(int width, int height, boolean withDepth) {
        this.width = width;
        this.height = height;
        int previous = GL11.glGetInteger(BINDING);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture = GlTextures.create();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                width,
                height,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            int status;
            if (core) {
                framebuffer = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(
                    GL30.GL_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
                if (withDepth) {
                    depth = GL30.glGenRenderbuffers();
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
                    GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
                    GL30.glFramebufferRenderbuffer(
                        GL30.GL_FRAMEBUFFER,
                        GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_RENDERBUFFER,
                        depth);
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
                }
                status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("framebuffer not complete: status " + status);
                }
            } else {
                int target = EXTFramebufferObject.GL_FRAMEBUFFER_EXT;
                framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
                EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer);
                EXTFramebufferObject.glFramebufferTexture2DEXT(
                    target,
                    EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
                if (withDepth) {
                    int renderbuffer = EXTFramebufferObject.GL_RENDERBUFFER_EXT;
                    depth = EXTFramebufferObject.glGenRenderbuffersEXT();
                    EXTFramebufferObject.glBindRenderbufferEXT(renderbuffer, depth);
                    EXTFramebufferObject
                        .glRenderbufferStorageEXT(renderbuffer, GL14.GL_DEPTH_COMPONENT24, width, height);
                    EXTFramebufferObject.glFramebufferRenderbufferEXT(
                        target,
                        EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT,
                        renderbuffer,
                        depth);
                    EXTFramebufferObject.glBindRenderbufferEXT(renderbuffer, 0);
                }
                status = EXTFramebufferObject.glCheckFramebufferStatusEXT(target);
                if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
                    throw new IllegalStateException("framebuffer not complete: status " + status);
                }
            }
        } catch (RuntimeException e) {
            delete();
            throw e;
        } finally {
            bind(previous);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    /** Draws into it from now on, over all of it. */
    void bind() {
        bind(framebuffer);
        GL11.glViewport(0, 0, width, height);
    }

    private void delete() {
        if (framebuffer != -1) {
            if (core) {
                GL30.glDeleteFramebuffers(framebuffer);
            } else {
                EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
            }
            framebuffer = -1;
        }
        if (depth != -1) {
            if (core) {
                GL30.glDeleteRenderbuffers(depth);
            } else {
                EXTFramebufferObject.glDeleteRenderbuffersEXT(depth);
            }
            depth = -1;
        }
        if (texture != -1) {
            GlTextures.delete(texture);
            texture = -1;
        }
    }
}
