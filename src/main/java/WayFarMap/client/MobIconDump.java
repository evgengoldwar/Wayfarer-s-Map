package WayFarMap.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.google.gson.GsonBuilder;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.registry.EntityRegistry;

/**
 * {@code /wfmobicons [filter]}: everything needed to see why a mob's icon on the map looks as it does, for every mob
 * the game knows (vanilla and mods), or those whose "mod/name" contains the filter. Per mob: the icon the map draws
 * ({@link MobIcons}, and how small it shows on the map), the face cut out of the skin it falls back to
 * ({@link EntityIcons}), the mob drawn by the game from the front, three quarters, the side and above, a picture of
 * its model's parts each in its own color with its number, its skin, and in {@code mobs.json} its model (every part
 * with its boxes and texture squares) and each step of the icon's making (which parts were tried as the head and why
 * one was taken or not). One card per mob with all of it, all mobs side by side in {@code sheet-N.png}, a
 * {@code README.txt} on what is where; all packed in one zip to send. Client side only; the mobs are made but never
 * put in the world.
 */
public final class MobIconDump extends CommandBase {

    public static final String COMMAND = "wfmobicons";

    /** Pixels per side of each picture drawn here; the icon and the parts are {@link MobIcons#SIZE}. */
    private static final int SIZE = 256;
    /** Mobs per contact sheet, a picture's side on it, and the label's height. */
    private static final int SHEET_ROWS = 40, CELL = 64, LABEL = 14;
    /** Sizes the map draws icons at, shown on the card and the sheets. */
    private static final int[] MAP_SIZES = { 8, 12, 16, 24 };
    /** Parts of a model drawn in the parts picture at most (each is one more picture). */
    private static final int MAX_PARTS = 64;
    private static final String[] SHOTS = { "icon", "onMap", "fallback", "parts", "front", "threeQuarter", "side",
        "top" };
    private static final int ICON = 0, ON_MAP = 1, FALLBACK = 2, PARTS = 3, FRONT = 4, THREE_QUARTER = 5, SIDE = 6,
        TOP = 7;
    /** The dark tile under icons on the map (see MapDrawer). */
    private static final int TILE = 0xFF101418;
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int[] PALETTE = { 0xE6194B, 0x3CB44B, 0xFFE119, 0x4363D8, 0xF58231, 0x911EB4, 0x46F0F0,
        0xF032E6, 0xBCF60C, 0xFABEBE, 0x008080, 0xE6BEFF, 0x9A6324, 0xFFFAC8, 0x800000, 0xAAFFC3, 0x808000, 0xFFD8B1,
        0x000075, 0x808080 };

    private static Method textureMethod;
    private static Field mainModelField, renderPassModelField, quadListField;

    @Override
    public String getCommandName() {
        return COMMAND;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + COMMAND + " [mod/name filter]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        if (!OpenGlHelper.isFramebufferEnabled()) {
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + Lang.format("wayfarmap.mobicons.noFramebuffer")));
            return;
        }
        try {
            Dump dump = new Dump(mc, args.length == 0 ? null : String.join(" ", args));
            dump.run();
            ChatComponentText message = new ChatComponentText(
                EnumChatFormatting.GREEN
                    + Lang.format("wayfarmap.mobicons.done", dump.mobs, dump.failed, dump.zip.getName()));
            // Click: the folder with the zip.
            message.setChatStyle(
                new ChatStyle().setChatClickEvent(
                    new ClickEvent(
                        ClickEvent.Action.OPEN_FILE,
                        dump.zip.getParentFile()
                            .getAbsolutePath())));
            sender.addChatMessage(message);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not dump the mob icons", t);
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + Lang.format("wayfarmap.mobicons.failed", t)));
        }
    }

    /** One mob's pictures (null where one couldn't be made) and what is known of it. */
    private static final class Mob {

        final String key;
        final BufferedImage[] shots = new BufferedImage[SHOTS.length];
        final Map<String, Object> info = new LinkedHashMap<>();
        BufferedImage skin;
        /** One line on how its icon was made, for the card and the sheets. */
        String summary = "";

        Mob(String key) {
            this.key = key;
        }
    }

    /** One run of the command. */
    private static final class Dump {

        final Minecraft mc;
        final String filter;
        final File folder, zip;
        final TextureManager textures;
        final List<Mob> done = new ArrayList<>();
        Framebuffer framebuffer;
        IntBuffer readBuffer;
        int mobs, failed;

        Dump(Minecraft mc, String filter) {
            this.mc = mc;
            this.filter = filter == null ? null : filter.toLowerCase(Locale.ROOT);
            this.textures = mc.getTextureManager();
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            File dumps = new File(new File(mc.mcDataDir, "wayfarmap"), "dumps");
            folder = new File(dumps, "mob-icons-" + date);
            zip = new File(dumps, "mob-icons-" + date + ".zip");
        }

        void run() throws Exception {
            if (!folder.mkdirs() && !folder.isDirectory()) {
                throw new IOException("Could not make " + folder);
            }
            findReflection();
            Map<String, Class<?>> kinds = kinds();
            // Bosses put up their health bar when drawn: the one there was (or none) is put back.
            String bossName = net.minecraft.entity.boss.BossStatus.bossName;
            float bossHealth = net.minecraft.entity.boss.BossStatus.healthScale;
            int bossTime = net.minecraft.entity.boss.BossStatus.statusBarTime;
            boolean bossColor = net.minecraft.entity.boss.BossStatus.hasColorModifier;
            try {
                framebuffer = new Framebuffer(SIZE, SIZE, true);
                readBuffer = BufferUtils.createIntBuffer(SIZE * SIZE);
                for (Map.Entry<String, Class<?>> kind : kinds.entrySet()) {
                    mobs++;
                    Mob mob = dumpMob(kind.getKey(), kind.getValue());
                    done.add(mob);
                    for (int shot = 0; shot < SHOTS.length; shot++) {
                        if (mob.shots[shot] != null) {
                            write(mob.shots[shot], new File(new File(folder, SHOTS[shot]), safe(mob.key) + ".png"));
                        }
                    }
                    write(card(mob), new File(new File(folder, "cards"), safe(mob.key) + ".png"));
                }
            } finally {
                if (framebuffer != null) {
                    framebuffer.deleteFramebuffer();
                }
                Framebuffer game = mc.getFramebuffer();
                if (game != null) {
                    game.bindFramebuffer(true);
                }
                net.minecraft.entity.boss.BossStatus.bossName = bossName;
                net.minecraft.entity.boss.BossStatus.healthScale = bossHealth;
                net.minecraft.entity.boss.BossStatus.statusBarTime = bossTime;
                net.minecraft.entity.boss.BossStatus.hasColorModifier = bossColor;
            }
            List<Map<String, Object>> infos = new ArrayList<>();
            for (Mob mob : done) {
                infos.add(mob.info);
            }
            try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(folder, "mobs.json")),
                StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting()
                    .serializeSpecialFloatingPointValues()
                    .create()
                    .toJson(infos, writer);
            }
            writeSheets();
            writeReadme();
            zipFolder();
        }

        /**
         * Living mobs by "mod/name": the named ones, then those with a renderer but no name. Not players; only those
         * matching the filter if there is one.
         */
        private Map<String, Class<?>> kinds() {
            return mobKinds(filter);
        }

        private Mob dumpMob(String key, Class<?> kind) {
            Mob mob = new Mob(key);
            Map<String, Object> info = mob.info;
            info.put("key", key);
            info.put("class", chain(kind, EntityLivingBase.class));
            EntityLivingBase entity;
            try {
                entity = (EntityLivingBase) create(kind);
            } catch (Throwable t) {
                info.put("error", "could not be made: " + t);
                mob.summary = "could not be made";
                failed++;
                return mob;
            }
            // Facing the camera (south), standing still.
            entity.rotationYaw = entity.prevRotationYaw = 0f;
            entity.renderYawOffset = entity.prevRenderYawOffset = 0f;
            entity.rotationYawHead = entity.prevRotationYawHead = 0f;
            entity.rotationPitch = entity.prevRotationPitch = 0f;
            info.put("width", entity.width);
            info.put("height", entity.height);
            info.put("child", entity.isChild());
            Render render = RenderManager.instance.getEntityClassRenderObject(kind.asSubclass(Entity.class));
            info.put("renderer", render == null ? null : chain(render.getClass(), Render.class));
            if (render == null) {
                mob.summary = "no renderer";
                failed++;
                return mob;
            }
            ResourceLocation texture = texture(render, entity);
            info.put("texture", texture == null ? null : texture.toString());
            if (texture != null) {
                writeSkin(texture, mob);
            }
            ModelBase main = null, pass = null;
            if (render instanceof RendererLivingEntity) {
                main = model(mainModelField, render);
                pass = model(renderPassModelField, render);
                info.put("model", main == null ? null : chain(main.getClass(), ModelBase.class));
                info.put("textureSize", main == null ? null : new int[] { main.textureWidth, main.textureHeight });
                if (pass != null) {
                    info.put("renderPassModel", chain(pass.getClass(), ModelBase.class));
                }
            }

            // The icon as the map makes it, and what was tried for it.
            Map<String, Object> trace = new LinkedHashMap<>();
            float[] frame = null;
            if (render instanceof RendererLivingEntity) {
                int[] icon = null;
                try {
                    icon = MobIcons.make(entity, (RendererLivingEntity) render, trace);
                } catch (Throwable t) {
                    trace.put("error", t.toString());
                }
                if (icon != null) {
                    mob.shots[ICON] = image(icon, MobIcons.SIZE);
                    mob.shots[ON_MAP] = onMap(mob.shots[ICON]);
                }
                frame = (float[]) trace.get("frame");
                Object result = trace.get("result");
                mob.summary = icon == null ? "NO ICON" + (trace.containsKey("error") ? ": " + trace.get("error") : "")
                    : "head".equals(result)
                        ? "head = part " + trace.get("headPart") + name(main, (Integer) trace.get("headPart"))
                        : String.valueOf(result);
            } else {
                trace.put("result", "not a living renderer: no icon, a dot on the map");
                mob.summary = "not a living renderer";
            }
            info.put("icon", trace);
            info.put("iconSummary", mob.summary);
            mob.shots[FALLBACK] = shot(() -> EntityIcons.drawFace(entity, SIZE / 2.0, SIZE / 2.0, SIZE - 16), true);
            if (frame == null) {
                frame = new float[] { 0f, entity.height / 2f, Math.max(entity.height, entity.width) * 0.6f + 0.2f };
            }

            if (main != null) {
                List<Map<String, Object>> parts = describe(main);
                info.put("modelParts", parts);
                if (pass != null) {
                    info.put("renderPassParts", describe(pass));
                }
                mob.shots[PARTS] = partsPicture(entity, main, pass, frame, parts);
            }

            float cx = frame[0], cy = frame[1], half = frame[2];
            mob.shots[FRONT] = shot(() -> drawMob(entity, cx, cy, half, 0f, 0f), false);
            mob.shots[THREE_QUARTER] = shot(() -> drawMob(entity, cx, cy, half, 20f, -35f), false);
            mob.shots[SIDE] = shot(() -> drawMob(entity, cx, cy, half, 0f, 90f), false);
            mob.shots[TOP] = shot(() -> drawMob(entity, cx, cy, half, 90f, 0f), false);
            for (int shot = 0; shot < SHOTS.length; shot++) {
                info.put("picture." + SHOTS[shot], mob.shots[shot] == null ? "none" : "ok");
            }
            if (mob.shots[FRONT] == null) {
                failed++;
            }
            return mob;
        }

        /**
         * The mob from the front unlit (as for its icon), each part of its model tinted in its own color with its
         * number: what differs when the part is hidden. Each part's pixel count and bounds go in its description.
         */
        private BufferedImage partsPicture(EntityLivingBase entity, ModelBase main, ModelBase pass, float[] frame,
            List<Map<String, Object>> parts) {
            int size = MobIcons.SIZE;
            int[] whole = MobIcons.shot(entity, frame[0], frame[1], frame[2], null, null);
            if (whole == null || main.boxList == null) {
                return null;
            }
            int scale = SIZE / size;
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            // The mob in grey, so the colors stand out.
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int p = whole[y * size + x];
                    if (p >>> 24 == 0) {
                        continue;
                    }
                    int grey = ((p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF)) / 6 + 40;
                    fill(image, x, y, scale, 0xFF000000 | grey << 16 | grey << 8 | grey);
                }
            }
            List<int[]> labels = new ArrayList<>();
            int count = Math.min(main.boxList.size(), MAX_PARTS);
            for (int i = 0; i < count; i++) {
                Object o = main.boxList.get(i);
                if (!(o instanceof ModelRenderer)) {
                    continue;
                }
                ModelRenderer part = (ModelRenderer) o;
                ModelRenderer passPart = pass != null && pass.getClass() == main.getClass()
                    && pass.boxList != null
                    && i < pass.boxList.size()
                    && pass.boxList.get(i) instanceof ModelRenderer ? (ModelRenderer) pass.boxList.get(i) : null;
                int[] without = MobIcons.shot(entity, frame[0], frame[1], frame[2], part, passPart);
                Map<String, Object> description = i < parts.size() ? parts.get(i) : new LinkedHashMap<>();
                if (without == null) {
                    description.put("picture", "failed");
                    continue;
                }
                int color = PALETTE[i % PALETTE.length];
                long sx = 0, sy = 0;
                int n = 0, x0 = size, y0 = size, x1 = -1, y1 = -1;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int at = y * size + x;
                        if (whole[at] == without[at]) {
                            continue;
                        }
                        n++;
                        sx += x;
                        sy += y;
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                        int under = image.getRGB(x * scale, y * scale);
                        fill(image, x, y, scale, under >>> 24 == 0 ? 0xC0000000 | color : mix(under, color));
                    }
                }
                description.put("visiblePixels", n);
                if (n > 0) {
                    // Bounds in the 128 px picture of the icon's frame, top row first.
                    description.put("visibleBounds", new int[] { x0, y0, x1, y1 });
                    labels.add(new int[] { i, (int) (sx / n) * scale, (int) (sy / n) * scale, color });
                }
            }
            Graphics2D g = image.createGraphics();
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
            for (int[] label : labels) {
                String text = String.valueOf(label[0]);
                int w = g.getFontMetrics()
                    .stringWidth(text), x = label[1] - w / 2, y = label[2] + 5;
                g.setColor(Color.BLACK);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        g.drawString(text, x + dx, y + dy);
                    }
                }
                g.setColor(Color.WHITE);
                g.drawString(text, x, y);
            }
            g.dispose();
            return image;
        }

        /** Something drawn into the buffer and read back; null if nothing was drawn or it failed. */
        private BufferedImage shot(Drawing drawing, boolean screen) {
            int previous = GL11.glGetInteger(FRAMEBUFFER_BINDING);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                framebuffer.bindFramebuffer(true);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glClearColor(0f, 0f, 0f, 0f);
                GL11.glClearDepth(1.0);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                if (screen) {
                    // As on the map: screen pixels, y down.
                    GL11.glOrtho(0, SIZE, SIZE, 0, -100, 100);
                }
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                // No light map: fully lit, as in the inventory.
                OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                OpenGlHelper.glBlendFunc(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDisable(GL11.GL_FOG);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                if (!drawing.draw()) {
                    return null;
                }
                readBuffer.clear();
                GL11.glReadPixels(0, 0, SIZE, SIZE, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, readBuffer);
                int[] all = new int[SIZE * SIZE];
                readBuffer.get(all);
                BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                boolean drawn = false;
                for (int row = 0; row < SIZE; row++) {
                    // Read back bottom-up.
                    image.setRGB(0, row, SIZE, 1, all, (SIZE - 1 - row) * SIZE, SIZE);
                    for (int x = 0; x < SIZE && !drawn; x++) {
                        drawn = all[row * SIZE + x] >>> 24 != 0;
                    }
                }
                return drawn ? image : null;
            } catch (Throwable t) {
                return null;
            } finally {
                RenderHelper.disableStandardItemLighting();
                Framebuffer game = mc.getFramebuffer();
                if (game != null && previous != 0 && previous == game.framebufferObject) {
                    game.bindFramebuffer(false);
                } else {
                    framebuffer.unbindFramebuffer();
                }
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        /**
         * The mob drawn by its own renderer, lit as in the inventory, seen straight (no perspective): {@code half}
         * blocks around ({@code x}, {@code y}) of it, turned by {@code pitch} and {@code yaw} degrees.
         */
        private boolean drawMob(EntityLivingBase entity, float x, float y, float half, float pitch, float yaw) {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glOrtho(x - half, x + half, y - half, y + half, -50, 50);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glTranslatef(0f, y, 0f);
            GL11.glRotatef(pitch, 1f, 0f, 0f);
            GL11.glRotatef(yaw, 0f, 1f, 0f);
            GL11.glTranslatef(0f, -y, 0f);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            RenderHelper.enableStandardItemLighting();
            RenderManager manager = RenderManager.instance;
            float viewY = manager.playerViewY;
            manager.playerViewY = 180f;
            try {
                manager.renderEntityWithPosYaw(entity, 0, 0, 0, 0f, 1f);
            } finally {
                manager.playerViewY = viewY;
            }
            return true;
        }

        /**
         * Every part of a model, with its number (the one in the parts picture): its name, place, turn, and its boxes
         * with the texture square of each box's front; the parts fixed to it inside, likewise.
         */
        private List<Map<String, Object>> describe(ModelBase model) {
            List<Map<String, Object>> parts = new ArrayList<>();
            if (model.boxList == null) {
                return parts;
            }
            List<Integer> candidates = MobIcons.headCandidates(model);
            for (int i = 0; i < model.boxList.size(); i++) {
                Object o = model.boxList.get(i);
                if (o instanceof ModelRenderer) {
                    Map<String, Object> p = describe((ModelRenderer) o, model, 0);
                    p.put("index", i);
                    int rank = candidates.indexOf(i);
                    p.put("headCandidateRank", rank < 0 ? null : rank);
                    parts.add(p);
                } else {
                    parts.add(new LinkedHashMap<>());
                }
            }
            return parts;
        }

        private Map<String, Object> describe(ModelRenderer part, ModelBase model, int depth) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", part.boxName);
            p.put("rotationPoint", new float[] { part.rotationPointX, part.rotationPointY, part.rotationPointZ });
            p.put("rotateAngle", new float[] { part.rotateAngleX, part.rotateAngleY, part.rotateAngleZ });
            p.put("offset", new float[] { part.offsetX, part.offsetY, part.offsetZ });
            p.put("hidden", part.isHidden || !part.showModel);
            p.put("mirror", part.mirror);
            p.put("texture", new float[] { part.textureWidth, part.textureHeight });
            List<Map<String, Object>> boxes = new ArrayList<>();
            if (part.cubeList != null) {
                for (Object c : part.cubeList) {
                    if (!(c instanceof ModelBox)) {
                        boxes.add(
                            Collections.singletonMap(
                                "class",
                                c == null ? null
                                    : c.getClass()
                                        .getName()));
                        continue;
                    }
                    ModelBox box = (ModelBox) c;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("from", new float[] { box.posX1, box.posY1, box.posZ1 });
                    b.put("to", new float[] { box.posX2, box.posY2, box.posZ2 });
                    b.put("frontUv", frontUv(box, model));
                    boxes.add(b);
                }
            }
            p.put("boxes", boxes);
            if (part.childModels != null && !part.childModels.isEmpty()) {
                List<Map<String, Object>> children = new ArrayList<>();
                for (Object child : part.childModels) {
                    if (child instanceof ModelRenderer && depth < 8) {
                        children.add(describe((ModelRenderer) child, model, depth + 1));
                    }
                }
                p.put("children", children);
            }
            return p;
        }

        /** {u0, v0, u1, v1} in texture pixels of the front (-Z) of a box. */
        private float[] frontUv(ModelBox box, ModelBase model) {
            try {
                TexturedQuad[] quads = (TexturedQuad[]) quadListField.get(box);
                float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
                for (PositionTextureVertex vertex : quads[4].vertexPositions) {
                    u0 = Math.min(u0, vertex.texturePositionX);
                    u1 = Math.max(u1, vertex.texturePositionX);
                    v0 = Math.min(v0, vertex.texturePositionY);
                    v1 = Math.max(v1, vertex.texturePositionY);
                }
                return new float[] { u0 * model.textureWidth, v0 * model.textureHeight, u1 * model.textureWidth,
                    v1 * model.textureHeight };
            } catch (Throwable t) {
                return null;
            }
        }

        /** The skin: the file from the resource packs, else read back from the graphics card (horses). */
        private void writeSkin(ResourceLocation texture, Mob mob) {
            File file = new File(new File(folder, "skin"), safe(mob.key) + ".png");
            file.getParentFile()
                .mkdirs();
            try (InputStream in = mc.getResourceManager()
                .getResource(texture)
                .getInputStream()) {
                Files.copy(in, file.toPath());
                mob.info.put("skin", "file");
                mob.skin = ImageIO.read(file);
                return;
            } catch (Throwable notAFile) {
                // Made by the game: read it back.
            }
            try {
                textures.bindTexture(texture);
                ITextureObject object = textures.getTexture(texture);
                if (object == null || object == TextureUtil.missingTexture) {
                    mob.info.put("skin", "missing");
                    return;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, object.getGlTextureId());
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width <= 0 || height <= 0 || width * height > 4096 * 4096) {
                    mob.info.put("skin", "unreadable");
                    return;
                }
                IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                int[] argb = new int[width * height];
                pixels.get(argb);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, width, height, argb, 0, width);
                ImageIO.write(image, "png", file);
                mob.info.put("skin", "gpu");
                mob.skin = image;
            } catch (Throwable t) {
                mob.info.put("skin", "unreadable: " + t);
            }
        }

        /**
         * One mob on one picture: its pictures large with their names, its skin, and how its icon was made, so a mob
         * can be looked at alone.
         */
        private BufferedImage card(Mob mob) {
            int cell = SIZE, columns = 4, rows = (SHOTS.length + columns - 1) / columns, head = 18;
            int skinHeight = 0, skinScale = 1;
            if (mob.skin != null) {
                skinScale = Math.max(1, Math.min(4, columns * cell / Math.max(1, mob.skin.getWidth())));
                skinHeight = mob.skin.getHeight() * skinScale + head;
            }
            List<String> lines = cardLines(mob);
            int textHeight = 16 * lines.size() + 8;
            BufferedImage image = new BufferedImage(
                columns * cell,
                head + rows * (cell + head) + skinHeight + textHeight,
                BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x30, 0x30, 0x30));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(Color.WHITE);
            g.drawString(mob.key, 4, 14);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            for (int s = 0; s < SHOTS.length; s++) {
                int x = s % columns * cell, y = head + s / columns * (cell + head);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(SHOTS[s], x + 4, y + 13);
                checkers(g, x, y + head, cell);
                if (mob.shots[s] != null) {
                    g.drawImage(mob.shots[s], x, y + head, cell, cell, null);
                } else {
                    g.setColor(Color.GRAY);
                    g.drawString("none", x + cell / 2 - 12, y + head + cell / 2);
                }
            }
            int y = head + rows * (cell + head);
            if (mob.skin != null) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("skin (x" + skinScale + ")", 4, y + 13);
                checkers(g, 0, y + head, mob.skin.getWidth() * skinScale, mob.skin.getHeight() * skinScale);
                g.drawImage(
                    mob.skin,
                    0,
                    y + head,
                    mob.skin.getWidth() * skinScale,
                    mob.skin.getHeight() * skinScale,
                    null);
                y += skinHeight;
            }
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(Color.WHITE);
            for (String line : lines) {
                y += 16;
                g.drawString(line, 4, y);
            }
            g.dispose();
            return image;
        }

        /** The text under a card: the mob's classes and each step of its icon's making. */
        @SuppressWarnings("unchecked")
        private List<String> cardLines(Mob mob) {
            List<String> lines = new ArrayList<>();
            Map<String, Object> info = mob.info;
            lines.add("icon: " + mob.summary);
            lines.add(
                "size " + info.get("width")
                    + " x "
                    + info.get("height")
                    + (Boolean.TRUE.equals(info.get("child")) ? ", child" : ""));
            lines.add("renderer: " + first(info.get("renderer")));
            Object pass = info.get("renderPassModel");
            lines.add("model: " + first(info.get("model")) + (pass != null ? " + pass " + first(pass) : ""));
            Object icon = info.get("icon");
            if (icon instanceof Map) {
                Object tried = ((Map<String, Object>) icon).get("tried");
                if (tried instanceof List) {
                    for (Object attempt : (List<Object>) tried) {
                        Map<String, Object> a = (Map<String, Object>) attempt;
                        lines.add(
                            "  tried part " + a.get("part")
                                + " "
                                + a.get("name")
                                + ": "
                                + a.get("result")
                                + (a.get("share") != null
                                    ? String.format(Locale.ROOT, " (%.3f of the mob)", (Float) a.get("share"))
                                    : ""));
                    }
                }
            }
            return lines;
        }

        /** All mobs side by side, {@link #SHEET_ROWS} per sheet: their pictures on a checkered ground, named. */
        private void writeSheets() throws IOException {
            int width = CELL * SHOTS.length + 420, row = CELL + LABEL / 2;
            for (int first = 0, sheet = 1; first < done.size(); first += SHEET_ROWS, sheet++) {
                int count = Math.min(SHEET_ROWS, done.size() - first);
                BufferedImage image = new BufferedImage(width, count * row + LABEL, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(0x30, 0x30, 0x30));
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(Color.WHITE);
                for (int s = 0; s < SHOTS.length; s++) {
                    g.drawString(SHOTS[s], s * CELL + 2, LABEL - 3);
                }
                for (int n = 0; n < count; n++) {
                    Mob mob = done.get(first + n);
                    int top = LABEL + n * row;
                    for (int s = 0; s < SHOTS.length; s++) {
                        checkers(g, s * CELL, top, CELL);
                        if (mob.shots[s] != null) {
                            g.drawImage(mob.shots[s], s * CELL, top, CELL, CELL, null);
                        }
                    }
                    g.setColor(Color.WHITE);
                    g.drawString(first + n + 1 + ". " + mob.key, SHOTS.length * CELL + 6, top + CELL / 2 - 6);
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString(mob.summary, SHOTS.length * CELL + 6, top + CELL / 2 + 8);
                }
                g.dispose();
                ImageIO.write(image, "png", new File(folder, "sheet-" + sheet + ".png"));
            }
        }

        private void writeReadme() throws IOException {
            String text = "Mob icon dump of Wayfarer's Map (/" + COMMAND
                + (filter == null ? "" : " " + filter)
                + "), "
                + done.size()
                + " mobs.\n\n"
                + "cards/<mob>.png    everything about one mob on one picture: start here.\n"
                + "sheet-N.png        all mobs side by side, "
                + SHEET_ROWS
                + " per sheet, with how each icon was made.\n"
                + "mobs.json          per mob: classes (with superclasses), size, renderer, model, skin, every\n"
                + "                   model part (index = the number in the parts picture) with its boxes in model\n"
                + "                   units (1/16 block, y down from 24 = the feet) and the texture square of each\n"
                + "                   box's front (-Z, where faces are); \"icon\" is the trace of MobIcons.make: the\n"
                + "                   frame {centerX, centerY, half} in blocks from the feet, the head candidates in\n"
                + "                   order, and for each one tried how many pixels it covered and why it was taken\n"
                + "                   or not.\n"
                + "skin/<mob>.png     the mob's texture.\n\n"
                + "Pictures (each also in its own folder):\n"
                + "  icon          the icon the map draws (MobIcons): the mob rendered unlit facing the viewer, the\n"
                + "                head cut out (what differs when the head part is hidden), else the whole mob.\n"
                + "  onMap         that icon on the map's dark tile at "
                + Arrays.toString(MAP_SIZES)
                + " px, enlarged.\n"
                + "  fallback      the face cut out of the skin (EntityIcons), drawn while the icon isn't made or if\n"
                + "                it can't be.\n"
                + "  parts         the mob unlit from the front, each top-level model part in its own color with its\n"
                + "                index (what differs when it is hidden).\n"
                + "  front, threeQuarter, side, top\n"
                + "                the mob drawn by its renderer, lit as in the inventory, in the icon's frame.\n";
            Files.write(new File(folder, "README.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
        }

        private void zipFolder() throws IOException {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                addToZip(out, folder, folder.getName());
            }
        }

        private void addToZip(ZipOutputStream out, File file, String name) throws IOException {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(out, child, name + "/" + child.getName());
                }
                return;
            }
            out.putNextEntry(new ZipEntry(name));
            Files.copy(file.toPath(), out);
            out.closeEntry();
        }
    }

    /** The icon on the map's dark tile at each of {@link #MAP_SIZES}, made small then enlarged to see its pixels. */
    private static BufferedImage onMap(BufferedImage icon) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        int half = SIZE / 2;
        for (int n = 0; n < MAP_SIZES.length; n++) {
            int size = MAP_SIZES[n];
            BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D s = small.createGraphics();
            s.setColor(new Color(TILE, true));
            s.fillRect(0, 0, size, size);
            s.drawImage(shrink(icon, size), 0, 0, null);
            s.dispose();
            int scale = (half - 8) / size;
            int x = n % 2 * half + (half - size * scale) / 2, y = n / 2 * half + (half - size * scale) / 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(small, x, y, size * scale, size * scale, null);
        }
        g.dispose();
        return image;
    }

    /** The picture made {@code size} pixels wide, each pixel the average of those it covers weighted by alpha. */
    private static BufferedImage shrink(BufferedImage image, int size) {
        BufferedImage small = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int side = image.getWidth();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx0 = x * side / size, sx1 = Math.max(sx0 + 1, (x + 1) * side / size);
                int sy0 = y * side / size, sy1 = Math.max(sy0 + 1, (y + 1) * side / size);
                long a = 0, r = 0, g = 0, b = 0;
                for (int j = sy0; j < sy1; j++) {
                    for (int i = sx0; i < sx1; i++) {
                        int c = image.getRGB(i, j), alpha = c >>> 24;
                        a += alpha;
                        r += (c >> 16 & 0xFF) * alpha;
                        g += (c >> 8 & 0xFF) * alpha;
                        b += (c & 0xFF) * alpha;
                    }
                }
                int n = (sx1 - sx0) * (sy1 - sy0);
                small.setRGB(
                    x,
                    y,
                    a == 0 ? 0 : (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
            }
        }
        return small;
    }

    /** Pixels top row first as a picture. */
    private static BufferedImage image(int[] pixels, int side) {
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, side, side, pixels, 0, side);
        return image;
    }

    private static void fill(BufferedImage image, int x, int y, int scale, int argb) {
        for (int dy = 0; dy < scale; dy++) {
            for (int dx = 0; dx < scale; dx++) {
                image.setRGB(x * scale + dx, y * scale + dy, argb);
            }
        }
    }

    /** Half the pixel's color, half the part's. */
    private static int mix(int argb, int rgb) {
        int r = ((argb >> 16 & 0xFF) + (rgb >> 16 & 0xFF)) / 2;
        int g = ((argb >> 8 & 0xFF) + (rgb >> 8 & 0xFF)) / 2;
        int b = ((argb & 0xFF) + (rgb & 0xFF)) / 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void checkers(Graphics2D g, int x, int y, int size) {
        checkers(g, x, y, size, size);
    }

    private static void checkers(Graphics2D g, int x, int y, int width, int height) {
        int square = Math.max(4, Math.min(width, height) / 8);
        for (int j = 0; j * square < height; j++) {
            for (int i = 0; i * square < width; i++) {
                g.setColor((i + j) % 2 == 0 ? new Color(0x55, 0x55, 0x55) : new Color(0x48, 0x48, 0x48));
                g.fillRect(
                    x + i * square,
                    y + j * square,
                    Math.min(square, width - i * square),
                    Math.min(square, height - j * square));
            }
        }
    }

    private static void write(BufferedImage image, File file) throws IOException {
        file.getParentFile()
            .mkdirs();
        ImageIO.write(image, "png", file);
    }

    /** A class and its superclasses up to {@code top}: the first names the mob's own, the others what it builds on. */
    private static List<String> chain(Class<?> kind, Class<?> top) {
        List<String> names = new ArrayList<>();
        for (Class<?> c = kind; c != null && c != Object.class; c = c.getSuperclass()) {
            names.add(c.getName());
            if (c == top) {
                break;
            }
        }
        return names;
    }

    private static Object first(Object chain) {
        return chain instanceof List && !((List<?>) chain).isEmpty() ? ((List<?>) chain).get(0) : chain;
    }

    /** " (name)" of a part of the model, or nothing. */
    private static String name(ModelBase model, Integer index) {
        if (model == null || index == null || model.boxList == null || index >= model.boxList.size()) {
            return "";
        }
        Object part = model.boxList.get(index);
        return part instanceof ModelRenderer && ((ModelRenderer) part).boxName != null
            ? " (" + ((ModelRenderer) part).boxName + ")"
            : "";
    }

    /** Something to draw into the buffer: false if there was nothing to draw. */
    private interface Drawing {

        boolean draw() throws Exception;
    }

    static boolean isMob(Class<?> kind) {
        return EntityLivingBase.class.isAssignableFrom(kind) && !EntityPlayer.class.isAssignableFrom(kind)
            && !Modifier.isAbstract(kind.getModifiers());
    }

    /** The mod that added the mob: from Forge's list, else the "mod.Name" prefix of its name, else the game. */
    @SuppressWarnings("unchecked")
    static String modOf(Class<?> kind, String name) {
        try {
            EntityRegistry.EntityRegistration registration = EntityRegistry.instance()
                .lookupModSpawn((Class<? extends Entity>) kind, false);
            if (registration != null) {
                return registration.getContainer()
                    .getModId();
            }
        } catch (Throwable ignored) {
            // Not registered through Forge.
        }
        if (name != null && name.indexOf('.') > 0) {
            return name.substring(0, name.indexOf('.'));
        }
        return kind.getName()
            .startsWith("net.minecraft.") ? "minecraft" : "unknown";
    }

    /** A mob made for its pictures only (not put in the world). */
    static Entity create(Class<?> kind) throws ReflectiveOperationException {
        Constructor<?> constructor = kind.getDeclaredConstructor(World.class);
        constructor.setAccessible(true);
        return (Entity) constructor.newInstance(Minecraft.getMinecraft().theWorld);
    }

    /** The members needed, found by their types so it works with any names. */
    static void findReflection() throws NoSuchMethodException {
        if (textureMethod != null) {
            return;
        }
        for (Method method : Render.class.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() == ResourceLocation.class && params.length == 1 && params[0] == Entity.class) {
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
        if (textureMethod == null) {
            throw new NoSuchMethodException("Render.getEntityTexture");
        }
    }

    private static ModelBase model(Field field, Render render) {
        try {
            return field == null ? null : (ModelBase) field.get(render);
        } catch (Throwable t) {
            return null;
        }
    }

    static ResourceLocation texture(Render render, Entity entity) {
        try {
            return (ResourceLocation) textureMethod.invoke(render, entity);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Living mobs by "mod/name": the named ones, then those with a renderer but no name. Not players; only those whose
     * "mod/name" contains the filter (lowercase) if there is one.
     */
    static Map<String, Class<?>> mobKinds(String filter) {
        Map<String, Class<?>> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) EntityList.classToStringMapping).entrySet()) {
            if (entry.getKey() instanceof Class && entry.getValue() instanceof String
                && isMob((Class<?>) entry.getKey())) {
                Class<?> kind = (Class<?>) entry.getKey();
                kinds.put(modOf(kind, (String) entry.getValue()) + "/" + entry.getValue(), kind);
            }
        }
        for (Object key : ((Map<?, ?>) RenderManager.instance.entityRenderMap).keySet()) {
            if (key instanceof Class && isMob((Class<?>) key) && !kinds.containsValue(key)) {
                Class<?> kind = (Class<?>) key;
                kinds.put(modOf(kind, null) + "/" + kind.getSimpleName(), kind);
            }
        }
        if (filter != null) {
            kinds.keySet()
                .removeIf(
                    key -> !key.toLowerCase(Locale.ROOT)
                        .contains(filter));
        }
        return kinds;
    }

    /** A file name made of safe letters only. */
    static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
