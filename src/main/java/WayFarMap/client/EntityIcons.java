package WayFarMap.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelSlime;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import WayFarMap.WayFarMap;

/**
 * Flat 2D icons of mobs, cut out of their own skin: the front face of the head of the model the game renders them
 * with. Works for vanilla mobs and for most modded mobs that use a regular model.
 */
public final class EntityIcons {

    /** Front face of a head on the skin texture: normalized UVs and size in texture pixels. */
    private static final class Face {

        final float u0, v0, u1, v1;
        final float width, height;

        /**
         * For a face made of several boxes (a slime: its body, eyes and mouth inside a see-through cube), each one's
         * front with its place in the face, back ones first; null for a single box.
         */
        final Part[] parts;

        Face(float u0, float v0, float u1, float v1, float width, float height) {
            this(u0, v0, u1, v1, width, height, null);
        }

        Face(float u0, float v0, float u1, float v1, float width, float height, Part[] parts) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
            this.parts = parts;
        }
    }

    /** The front of one box of a face made of several: its texture square, and where it is in the face (0-1). */
    private static final class Part {

        final float u0, v0, u1, v1;
        final float x0, y0, x1, y1;
        /** Depth of the front, to draw the back ones first. */
        final float z;

        Part(float[] uv, float x0, float y0, float x1, float y1, float z) {
            this.u0 = uv[0];
            this.v0 = uv[1];
            this.u1 = uv[2];
            this.v1 = uv[3];
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.z = z;
        }
    }

    private static final Face NONE = new Face(0, 0, 0, 0, 0, 0);
    /** Renderers are shared by all entities of a type, so the face is looked up once per renderer. */
    private static final Map<Render, Face> FACES = new IdentityHashMap<>();
    private static final int MASK_SIDE = 64;
    private static final Map<Face, Map<ResourceLocation, Optional<MobIconOutline>>> OUTLINES = new IdentityHashMap<>();

    private static Method textureMethod;
    private static Field mainModelField;
    /** The model drawn over the main one (the slime's see-through outer cube); null if not found. */
    private static Field renderPassModelField;
    private static Field quadListField;
    private static boolean reflectionFailed;

    private EntityIcons() {}

    static void resetOutlines() {
        OUTLINES.clear();
    }

    /**
     * Draws the mob's face centered on the given point, fitted in a {@code size} square.
     *
     * @return false if the mob has no usable face, so the caller can draw something else
     */
    public static boolean drawFace(EntityLivingBase entity, double sx, double sy, float size) {
        return drawFace(entity, sx, sy, size, 1f);
    }

    /** Same, at the given opacity. */
    public static boolean drawFace(EntityLivingBase entity, double sx, double sy, float size, float alpha) {
        return drawFace(entity, sx, sy, size, alpha, 1, 0, 0);
    }

    static boolean drawFace(EntityLivingBase entity, double sx, double sy, float size, float alpha, double pixel,
        int outlineWidth, int outlineColor) {
        Render render = RenderManager.instance.getEntityRenderObject(entity);
        if (!(render instanceof RendererLivingEntity) || !initReflection()) {
            return false;
        }
        Face face = FACES.get(render);
        if (face == null) {
            face = findFace((RendererLivingEntity) render);
            FACES.put(render, face);
        }
        if (face == NONE) {
            return false;
        }
        ResourceLocation texture;
        try {
            texture = (ResourceLocation) textureMethod.invoke(render, entity);
        } catch (Throwable t) {
            return false;
        }
        if (texture == null) {
            return false;
        }

        // Keep the face's aspect ratio inside the square.
        double halfW = size / 2.0, halfH = size / 2.0;
        if (face.width > face.height) {
            halfH *= face.height / face.width;
        } else if (face.height > face.width) {
            halfW *= face.width / face.height;
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        if (outlineWidth > 0 && outlineColor >>> 24 != 0) {
            MobIconOutline outline = outline(face, texture, 2 * halfW / size, 2 * halfH / size);
            if (outline != null) {
                outline.draw(sx, sy, size, pixel, outlineWidth, outlineColor);
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(texture);
            }
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        if (face.parts != null) {
            for (Part part : face.parts) {
                double x0 = sx - halfW + part.x0 * 2 * halfW, x1 = sx - halfW + part.x1 * 2 * halfW;
                double y0 = sy - halfH + part.y0 * 2 * halfH, y1 = sy - halfH + part.y1 * 2 * halfH;
                tessellator.addVertexWithUV(x0, y1, 0, part.u0, part.v1);
                tessellator.addVertexWithUV(x1, y1, 0, part.u1, part.v1);
                tessellator.addVertexWithUV(x1, y0, 0, part.u1, part.v0);
                tessellator.addVertexWithUV(x0, y0, 0, part.u0, part.v0);
            }
            tessellator.draw();
            return true;
        }
        tessellator.addVertexWithUV(sx - halfW, sy + halfH, 0, face.u0, face.v1);
        tessellator.addVertexWithUV(sx + halfW, sy + halfH, 0, face.u1, face.v1);
        tessellator.addVertexWithUV(sx + halfW, sy - halfH, 0, face.u1, face.v0);
        tessellator.addVertexWithUV(sx - halfW, sy - halfH, 0, face.u0, face.v0);
        tessellator.draw();
        return true;
    }

    private static MobIconOutline outline(Face face, ResourceLocation texture, double width, double height) {
        Map<ResourceLocation, Optional<MobIconOutline>> skins = OUTLINES.computeIfAbsent(
            face,
            ignored -> new LinkedHashMap<ResourceLocation, Optional<MobIconOutline>>(4, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Optional<MobIconOutline>> eldest) {
                    return size() > 8;
                }
            });
        return skins.computeIfAbsent(texture, name -> {
            try {
                return Optional.ofNullable(readOutline(face, width, height));
            } catch (RuntimeException e) {
                WayFarMap.LOG.debug("Could not read the outline for mob skin " + name, e);
                return Optional.empty();
            }
        })
            .orElse(null);
    }

    /** The bound skin can be assembled by the game. Read it once per face and skin, including any face layers. */
    private static MobIconOutline readOutline(Face face, double width, double height) {
        int skinWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int skinHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (skinWidth <= 0 || skinHeight <= 0 || (long) skinWidth * skinHeight > 2048 * 2048) {
            return null;
        }
        IntBuffer skin = BufferUtils.createIntBuffer(skinWidth * skinHeight);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, skin);
        int[] mask = new int[MASK_SIDE * MASK_SIDE];
        if (face.parts == null) {
            Part part = new Part(new float[] { face.u0, face.v0, face.u1, face.v1 }, 0, 0, 1, 1, 0);
            samplePart(mask, part, skin, skinWidth, skinHeight, width, height);
        } else {
            for (Part part : face.parts) {
                samplePart(mask, part, skin, skinWidth, skinHeight, width, height);
            }
        }
        return new MobIconOutline(MASK_SIDE, MASK_SIDE, mask, true);
    }

    private static void samplePart(int[] mask, Part part, IntBuffer skin, int skinWidth, int skinHeight, double width,
        double height) {
        double left = (1 - width) / 2 + part.x0 * width, right = (1 - width) / 2 + part.x1 * width;
        double top = (1 - height) / 2 + part.y0 * height, bottom = (1 - height) / 2 + part.y1 * height;
        for (int y = 0; y < MASK_SIDE; y++) {
            double py = (y + 0.5) / MASK_SIDE;
            if (py < top || py >= bottom) {
                continue;
            }
            double v = part.v0 + (part.v1 - part.v0) * (py - top) / (bottom - top);
            int sy = Math.max(0, Math.min(skinHeight - 1, (int) (v * skinHeight)));
            for (int x = 0; x < MASK_SIDE; x++) {
                double px = (x + 0.5) / MASK_SIDE;
                if (px < left || px >= right) {
                    continue;
                }
                double u = part.u0 + (part.u1 - part.u0) * (px - left) / (right - left);
                int sx = Math.max(0, Math.min(skinWidth - 1, (int) (u * skinWidth)));
                int i = y * MASK_SIDE + x;
                int a = skin.get(sy * skinWidth + sx) >>> 24;
                mask[i] = (a + (mask[i] >>> 24) * (255 - a) / 255) << 24;
            }
        }
    }

    /** Looks up the private/protected members by type, so it works with both dev and obfuscated names. */
    private static boolean initReflection() {
        if (textureMethod != null) {
            return true;
        }
        if (reflectionFailed) {
            return false;
        }
        try {
            for (Method method : Render.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == ResourceLocation.class && params.length == 1
                    && params[0] == Entity.class) {
                    method.setAccessible(true);
                    textureMethod = method;
                    break;
                }
            }
            // mainModel is declared before renderPassModel.
            for (Field field : RendererLivingEntity.class.getDeclaredFields()) {
                if (field.getType() == ModelBase.class) {
                    field.setAccessible(true);
                    if (mainModelField == null) {
                        mainModelField = field;
                    } else {
                        renderPassModelField = field;
                        break;
                    }
                }
            }
            for (Field field : ModelBox.class.getDeclaredFields()) {
                if (field.getType() == TexturedQuad[].class) {
                    field.setAccessible(true);
                    quadListField = field;
                    break;
                }
            }
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Mob icons are unavailable", t);
        }
        if (textureMethod == null || mainModelField == null || quadListField == null) {
            reflectionFailed = true;
            textureMethod = null;
            return false;
        }
        return true;
    }

    private static Face findFace(RendererLivingEntity render) {
        try {
            ModelBase model = (ModelBase) mainModelField.get(render);
            if (model instanceof ModelSlime) {
                Face face = slimeFace(render, model);
                if (face != null) {
                    return face;
                }
            }
            ModelRenderer head = findHead(model);
            if (head == null) {
                return NONE;
            }
            float[] uv = frontUv((ModelBox) head.cubeList.get(0));
            float width = (uv[2] - uv[0]) * model.textureWidth;
            float height = (uv[3] - uv[1]) * model.textureHeight;
            if (width <= 0 || height <= 0) {
                return NONE;
            }
            return new Face(uv[0], uv[1], uv[2], uv[3], width, height);
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** {u0, v0, u1, v1} of the front (-Z) of a box. */
    private static float[] frontUv(ModelBox box) throws IllegalAccessException {
        TexturedQuad[] quads = (TexturedQuad[]) quadListField.get(box);
        // ModelBox creates its faces in a fixed order; index 4 is the front (-Z), where mobs have their face.
        TexturedQuad front = quads[4];
        float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
        for (PositionTextureVertex vertex : front.vertexPositions) {
            u0 = Math.min(u0, vertex.texturePositionX);
            u1 = Math.max(u1, vertex.texturePositionX);
            v0 = Math.min(v0, vertex.texturePositionY);
            v1 = Math.max(v1, vertex.texturePositionY);
        }
        return new float[] { u0, v0, u1, v1 };
    }

    /**
     * A slime's face as the game shows it: the body, eyes and mouth of the main model (separate boxes inside), and
     * the see-through outer cube drawn over them. Vanilla slimes and those of mods using the slime model (Tinkers'
     * Construct's blue slime and King Slime). Null if it can't be made.
     */
    private static Face slimeFace(RendererLivingEntity render, ModelBase inner) throws IllegalAccessException {
        List<ModelBase> models = new ArrayList<>();
        models.add(inner);
        ModelBase outer = renderPassModelField == null ? null : (ModelBase) renderPassModelField.get(render);
        if (outer instanceof ModelSlime) {
            models.add(outer);
        }
        // Boxes in model units, then fitted into the face's square.
        List<float[]> boxes = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int m = 0; m < models.size(); m++) {
            ModelBase model = models.get(m);
            if (model.boxList == null) {
                continue;
            }
            for (Object o : model.boxList) {
                ModelRenderer renderer = (ModelRenderer) o;
                if (renderer.cubeList == null) {
                    continue;
                }
                for (Object c : renderer.cubeList) {
                    if (!(c instanceof ModelBox)) {
                        continue;
                    }
                    ModelBox box = (ModelBox) c;
                    float x0 = renderer.rotationPointX + box.posX1, x1 = renderer.rotationPointX + box.posX2;
                    float y0 = renderer.rotationPointY + box.posY1, y1 = renderer.rotationPointY + box.posY2;
                    // The outer cube is over everything inside: the farthest front of the inner boxes first.
                    float z = m == 1 ? -Float.MAX_VALUE : renderer.rotationPointZ + box.posZ1;
                    boxes.add(new float[] { x0, y0, x1, y1, z });
                    uvs.add(frontUv(box));
                    minX = Math.min(minX, x0);
                    minY = Math.min(minY, y0);
                    maxX = Math.max(maxX, x1);
                    maxY = Math.max(maxY, y1);
                }
            }
        }
        if (boxes.isEmpty() || maxX <= minX || maxY <= minY) {
            return null;
        }
        Part[] parts = new Part[boxes.size()];
        for (int i = 0; i < parts.length; i++) {
            float[] b = boxes.get(i);
            parts[i] = new Part(
                uvs.get(i),
                (b[0] - minX) / (maxX - minX),
                (b[1] - minY) / (maxY - minY),
                (b[2] - minX) / (maxX - minX),
                (b[3] - minY) / (maxY - minY),
                b[4]);
        }
        // Back to front: the front is -Z, so larger Z is farther.
        Arrays.sort(parts, (a, b) -> Float.compare(b.z, a.z));
        return new Face(0, 0, 1, 1, maxX - minX, maxY - minY, parts);
    }

    /**
     * The head of bipeds is known; for other models the first part is used, which is the head for most of them
     * (quadrupeds, creepers, spiders, chickens, wolves, villagers, golems, slimes, ghasts...).
     */
    private static ModelRenderer findHead(ModelBase model) {
        if (model instanceof ModelBiped) {
            return usable(((ModelBiped) model).bipedHead);
        }
        if (model == null || model.boxList == null) {
            return null;
        }
        for (Object part : model.boxList) {
            ModelRenderer renderer = usable((ModelRenderer) part);
            if (renderer != null) {
                return renderer;
            }
        }
        return null;
    }

    private static ModelRenderer usable(ModelRenderer renderer) {
        return renderer != null && renderer.cubeList != null
            && !renderer.cubeList.isEmpty()
            && renderer.cubeList.get(0) instanceof ModelBox ? renderer : null;
    }
}
