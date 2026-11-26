package WayFarMap.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.LayeredTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.google.gson.GsonBuilder;

import WayFarMap.WayFarMap;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * {@code /wfmobtextures [filter]}: the textures of every mob the game knows (vanilla and mods), or of those whose
 * "mod/name" contains the filter, saved as files into {@code wayfarmap/dumps/mob-textures-<date>/}, with a zip of that
 * folder next to it.
 * {@code /wfmobtexturesmissing [filter]} uses the same format for mobs without a ready-made map icon.
 * <p>
 * Per mob: the texture its renderer names, every texture bound while the mob is drawn once (layers: a sheep's wool,
 * a spider's eyes, an enderman's eyes...), and the other pictures in the same folder when it is an entity folder
 * (variants: villager professions, horse coats, cat types...). The files are saved at their resource paths
 * ({@code assets/<mod>/textures/...}) as the game has them now, resource packs included, so the folder (and the zip,
 * with its {@code pack.mcmeta}) is a resource pack to start from. Textures the game makes itself (a horse's coat
 * with its markings) are read back from the graphics card into {@code generated/}. {@code mobs.json} and
 * {@code mobs.txt} list which mob has which textures. Client side only; the mobs are made but never put in the world.
 */
public final class MobTextureDump extends CommandBase {

    public static final String COMMAND = "wfmobtextures";
    private static final String MISSING_COMMAND = "wfmobtexturesmissing";

    /** Side of the buffer the mob is drawn into, only to see which textures it binds. */
    private static final int SIZE = 64;
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    /** Textures bound while a mob is drawn for the dump; null at any other time. */
    private static Set<ResourceLocation> recording;

    private final boolean onlyMissing;

    public MobTextureDump() {
        this(false);
    }

    public MobTextureDump(boolean onlyMissing) {
        this.onlyMissing = onlyMissing;
    }

    /** Called for every texture bound (see MixinTextureManager): noted only while a mob is drawn for the dump. */
    public static void noteBound(ResourceLocation location) {
        Set<ResourceLocation> set = recording;
        if (set != null && location != null) {
            set.add(location);
        }
    }

    @Override
    public String getCommandName() {
        return onlyMissing ? MISSING_COMMAND : COMMAND;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/" + getCommandName() + " [mod/name filter]";
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
                new ChatComponentText(EnumChatFormatting.RED + Lang.format("wayfarmap.mobtextures.noFramebuffer")));
            return;
        }
        try {
            Dump dump = new Dump(mc, args.length == 0 ? null : String.join(" ", args), onlyMissing);
            dump.run();
            ChatComponentText message = new ChatComponentText(
                EnumChatFormatting.GREEN + Lang.format(
                    onlyMissing ? "wayfarmap.mobtextures.missingDone" : "wayfarmap.mobtextures.done",
                    dump.mobs,
                    dump.written.size(),
                    dump.folder.getName(),
                    dump.zip.getName()));
            // Click: the folder with the dump and its zip.
            message.setChatStyle(
                new ChatStyle().setChatClickEvent(
                    new ClickEvent(
                        ClickEvent.Action.OPEN_FILE,
                        dump.zip.getParentFile()
                            .getAbsolutePath())));
            sender.addChatMessage(message);
        } catch (Throwable t) {
            WayFarMap.LOG.warn("Could not dump the mob textures", t);
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.RED + Lang.format("wayfarmap.mobtextures.failed", t)));
        }
    }

    /** One run of the command. */
    private static final class Dump {

        final Minecraft mc;
        final String filter;
        final boolean onlyMissing;
        final File folder, zip;
        final Resources resources = new Resources();
        /** Saved textures: resource ("mod:path") or generated name, to the file's path in the folder. */
        final Map<String, String> written = new TreeMap<>();
        final List<Map<String, Object>> infos = new ArrayList<>();
        Framebuffer framebuffer;
        int mobs;

        Dump(Minecraft mc, String filter, boolean onlyMissing) {
            this.mc = mc;
            this.filter = filter == null ? null : filter.toLowerCase(Locale.ROOT);
            this.onlyMissing = onlyMissing;
            String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            File dumps = new File(new File(mc.mcDataDir, "wayfarmap"), "dumps");
            String name = (onlyMissing ? "mob-textures-missing-" : "mob-textures-") + date;
            folder = new File(dumps, name);
            zip = new File(dumps, name + ".zip");
        }

        void run() throws Exception {
            if (!folder.mkdirs() && !folder.isDirectory()) {
                throw new IOException("Could not make " + folder);
            }
            MobIconDump.findReflection();
            Map<String, Class<?>> kinds = MobIconDump.mobKinds(filter);
            try {
                framebuffer = new Framebuffer(SIZE, SIZE, true);
                for (Map.Entry<String, Class<?>> kind : kinds.entrySet()) {
                    if (onlyMissing && MobMapIcons.hasIcon(kind.getValue())) {
                        continue;
                    }
                    mobs++;
                    infos.add(dumpMob(kind.getKey(), kind.getValue()));
                }
            } finally {
                if (framebuffer != null) {
                    framebuffer.deleteFramebuffer();
                }
                Framebuffer game = mc.getFramebuffer();
                if (game != null) {
                    game.bindFramebuffer(true);
                }
                recording = null;
                resources.close();
            }
            try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(folder, "mobs.json")),
                StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(infos, writer);
            }
            writeList();
            writeReadme();
            Files.write(
                new File(folder, "pack.mcmeta").toPath(),
                ("{\n  \"pack\": {\n    \"pack_format\": 1,\n"
                    + "    \"description\": \"Mob textures dumped by Wayfarer's Map\"\n  }\n}\n")
                        .getBytes(StandardCharsets.UTF_8));
            zipFolder();
        }

        private Map<String, Object> dumpMob(String key, Class<?> kind) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("mob", key);
            info.put("class", kind.getName());
            EntityLivingBase entity;
            try {
                entity = (EntityLivingBase) MobIconDump.create(kind);
            } catch (Throwable t) {
                info.put("error", "could not be made: " + t);
                return info;
            }
            Render render = RenderManager.instance.getEntityClassRenderObject(kind.asSubclass(Entity.class));
            if (render == null) {
                info.put("error", "no renderer");
                return info;
            }
            Set<ResourceLocation> found = new LinkedHashSet<>();
            ResourceLocation main = MobIconDump.texture(render, entity);
            if (main != null) {
                found.add(main);
                info.put("main", main.toString());
            }
            // Drawn once: every texture it binds (layers, eyes, its coat's layers).
            Set<ResourceLocation> bound = drawRecording(entity);
            List<String> drawn = new ArrayList<>();
            for (ResourceLocation location : bound) {
                if (isMobTexture(location)) {
                    found.add(location);
                    drawn.add(location.toString());
                }
            }
            info.put("drawn", drawn);
            // A texture the game layers from files (a horse's coat and markings): the files too.
            List<String> layers = new ArrayList<>();
            for (ResourceLocation location : new ArrayList<>(found)) {
                ITextureObject object = mc.getTextureManager()
                    .getTexture(location);
                if (object instanceof LayeredTexture) {
                    for (Object layer : ((LayeredTexture) object).layeredTextureNames) {
                        if (layer != null) {
                            ResourceLocation file = new ResourceLocation(layer.toString());
                            found.add(file);
                            layers.add(file.toString());
                        }
                    }
                }
            }
            info.put("layers", layers);
            // Variants: the other pictures of the folder, when it is a folder of entity textures.
            Set<ResourceLocation> variants = new TreeSet<>(
                (a, b) -> a.toString()
                    .compareTo(b.toString()));
            for (ResourceLocation location : new ArrayList<>(found)) {
                if (isEntityFolder(location.getResourcePath())) {
                    for (ResourceLocation sibling : resources.siblings(location)) {
                        if (!found.contains(sibling)) {
                            variants.add(sibling);
                        }
                    }
                }
            }
            List<String> variantNames = new ArrayList<>();
            for (ResourceLocation variant : variants) {
                found.add(variant);
                variantNames.add(variant.toString());
            }
            info.put("variants", variantNames);
            Map<String, String> files = new LinkedHashMap<>();
            for (ResourceLocation location : found) {
                String file = save(location, key);
                files.put(location.toString(), file);
            }
            info.put("files", files);
            return info;
        }

        /** The texture as a file at its resource path; read back from the graphics card if the game made it. */
        private String save(ResourceLocation location, String mobKey) {
            String id = location.toString();
            String done = written.get(id);
            if (done != null) {
                return done;
            }
            String path = "assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
            File file = new File(folder, path);
            try (InputStream in = mc.getResourceManager()
                .getResource(location)
                .getInputStream()) {
                file.getParentFile()
                    .mkdirs();
                Files.copy(in, file.toPath());
                written.put(id, path);
                return path;
            } catch (Throwable notAFile) {
                // Made by the game (a horse's coat with its markings): read it back.
            }
            String generated = "generated/" + MobIconDump.safe(mobKey) + "_" + MobIconDump.safe(id) + ".png";
            BufferedImage image = readBack(location);
            if (image == null) {
                written.put(id, "missing");
                return "missing";
            }
            try {
                File out = new File(folder, generated);
                out.getParentFile()
                    .mkdirs();
                ImageIO.write(image, "png", out);
                written.put(id, generated);
                return generated;
            } catch (IOException e) {
                written.put(id, "unwritable: " + e);
                return "unwritable";
            }
        }

        private BufferedImage readBack(ResourceLocation location) {
            try {
                TextureManager textures = mc.getTextureManager();
                textures.bindTexture(location);
                ITextureObject object = textures.getTexture(location);
                if (object == null || object == TextureUtil.missingTexture) {
                    return null;
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, object.getGlTextureId());
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width <= 0 || height <= 0 || width * height > 4096 * 4096) {
                    return null;
                }
                IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
                int[] argb = new int[width * height];
                pixels.get(argb);
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, width, height, argb, 0, width);
                return image;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * Draws the mob once into a small buffer, noting every texture bound meanwhile. GL's matrix and attribute
         * stacks are put back as they were, also when its renderer fails halfway (pushed and never popped).
         */
        private Set<ResourceLocation> drawRecording(EntityLivingBase entity) {
            Set<ResourceLocation> bound = new LinkedHashSet<>();
            RenderManager manager = RenderManager.instance;
            int previous = GL11.glGetInteger(FRAMEBUFFER_BINDING);
            int modelview = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
            int projection = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
            int attrib = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            float viewY = manager.playerViewY;
            // Bosses put up their health bar when drawn: the one there was (or none) is put back.
            String bossName = BossStatus.bossName;
            float bossHealth = BossStatus.healthScale;
            int bossTime = BossStatus.statusBarTime;
            boolean bossColor = BossStatus.hasColorModifier;
            try {
                framebuffer.bindFramebuffer(true);
                GL11.glClearColor(0f, 0f, 0f, 0f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                float half = Math.max(entity.height, entity.width) * 0.6f + 0.2f;
                GL11.glOrtho(-half, half, entity.height / 2f - half, entity.height / 2f + half, -50, 50);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                RenderHelper.enableStandardItemLighting();
                manager.playerViewY = 180f;
                recording = bound;
                manager.renderEntityWithPosYaw(entity, 0, 0, 0, 0f, 1f);
            } catch (Throwable t) {
                // What was bound before it failed is still worth having.
            } finally {
                recording = null;
                manager.playerViewY = viewY;
                BossStatus.bossName = bossName;
                BossStatus.healthScale = bossHealth;
                BossStatus.statusBarTime = bossTime;
                BossStatus.hasColorModifier = bossColor;
                RenderHelper.disableStandardItemLighting();
                Framebuffer game = mc.getFramebuffer();
                if (game != null && previous != 0 && previous == game.framebufferObject) {
                    game.bindFramebuffer(false);
                } else {
                    framebuffer.unbindFramebuffer();
                }
                // Down to the depths before: ours, and whatever a renderer that failed left pushed.
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                while (GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH) > projection) {
                    GL11.glPopMatrix();
                }
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                while (GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) > modelview) {
                    GL11.glPopMatrix();
                }
                while (GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH) > attrib) {
                    GL11.glPopAttrib();
                }
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
            }
            return bound;
        }

        /** mobs.txt: each mob with its textures, and where each was saved. */
        private void writeList() throws IOException {
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> info : infos) {
                text.append(info.get("mob"))
                    .append('\n');
                if (info.containsKey("error")) {
                    text.append("  ! ")
                        .append(info.get("error"))
                        .append('\n');
                }
                Object files = info.get("files");
                if (files instanceof Map) {
                    for (Map.Entry<?, ?> file : ((Map<?, ?>) files).entrySet()) {
                        text.append("  ")
                            .append(file.getKey())
                            .append("  ->  ")
                            .append(file.getValue())
                            .append('\n');
                    }
                }
            }
            Files.write(
                new File(folder, "mobs.txt").toPath(),
                text.toString()
                    .getBytes(StandardCharsets.UTF_8));
        }

        private void writeReadme() throws IOException {
            String text = "Mob textures dumped by Wayfarer's Map (/" + (onlyMissing ? MISSING_COMMAND : COMMAND)
                + (filter == null ? "" : " " + filter)
                + ")\n"
                + "Minecraft "
                + Loader.MC_VERSION
                + ", "
                + Loader.instance()
                    .getActiveModList()
                    .size()
                + " mods. Mobs: "
                + mobs
                + ", textures: "
                + written.size()
                + ".\n\n"
                + "assets/<mod>/textures/...  each texture at its resource path, as the game has it now (resource\n"
                + "                           packs included). With pack.mcmeta the folder is a resource pack:\n"
                + "                           change pictures here, zip the folder and use it as one.\n"
                + "generated/                 textures the game makes itself (a horse's coat with its markings),\n"
                + "                           read back from the graphics card.\n"
                + "mobs.txt                   each mob and its textures, with where each was saved.\n"
                + "mobs.json                  the same per mob: \"main\" (the texture its renderer names), \"drawn\"\n"
                + "                           (bound while it was drawn once: layers, eyes), \"layers\" (the files a\n"
                + "                           texture the game makes is layered from: a horse's coat and markings),\n"
                + "                           \"variants\" (the other pictures of the mob's own entity folder and its\n"
                + "                           folders: villager professions, horse coats and armor), \"files\".\n";
            Files.write(new File(folder, "README.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
        }

        private void zipFolder() throws IOException {
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                addToZip(out, folder, "");
            }
        }

        private void addToZip(ZipOutputStream out, File file, String name) throws IOException {
            File[] children = file.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                String entry = name.isEmpty() ? child.getName() : name + "/" + child.getName();
                if (child.isDirectory()) {
                    addToZip(out, child, entry);
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new FileInputStream(child)) {
                    byte[] buffer = new byte[16384];
                    for (int n; (n = in.read(buffer)) > 0;) {
                        out.write(buffer, 0, n);
                    }
                }
                out.closeEntry();
            }
        }
    }

    /** Textures that belong to the mob: not the item and block atlases, the shadow or the enchantment glint. */
    private static boolean isMobTexture(ResourceLocation location) {
        String path = location.getResourcePath();
        return !path.startsWith("textures/atlas/") && !path.startsWith("textures/misc/")
            && !path.startsWith("textures/blocks/")
            && !path.startsWith("textures/items/")
            && !path.startsWith("textures/font/");
    }

    /** Folders that hold the textures of many things: their other pictures are other mobs, not variants. */
    private static final Set<String> SHARED_FOLDERS = new java.util.HashSet<>(
        java.util.Arrays.asList("entity", "entities", "mob", "mobs", "model", "models", "textures", "creature"));

    /**
     * A mob's own folder of entity textures, whose other pictures are its variants ("textures/entity/horse"); not a
     * folder shared by many ("textures/entity", where the chicken lies next to the arrow and the boat).
     */
    private static boolean isEntityFolder(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String dir = path.substring(0, slash)
            .toLowerCase(Locale.ROOT);
        String last = dir.substring(dir.lastIndexOf('/') + 1);
        return (dir.contains("entit") || dir.contains("mob")) && !SHARED_FOLDERS.contains(last);
    }

    /**
     * Where resources come from (the game's jar, the mods' jars and folders, the resource packs in use), to list the
     * pictures of a folder: the game can only look up a resource by its name.
     */
    private static final class Resources {

        private List<File> roots;
        private final Map<File, ZipFile> zips = new HashMap<>();
        private final Map<String, Set<ResourceLocation>> listed = new HashMap<>();

        /** The other PNG pictures in the folder of the texture and its folders, in any of the roots. */
        Set<ResourceLocation> siblings(ResourceLocation texture) {
            String domain = texture.getResourceDomain();
            String path = texture.getResourcePath();
            int slash = path.lastIndexOf('/');
            String dir = slash < 0 ? "" : path.substring(0, slash + 1);
            String key = domain + ":" + dir;
            Set<ResourceLocation> found = listed.get(key);
            if (found != null) {
                return found;
            }
            found = new TreeSet<>(
                (a, b) -> a.toString()
                    .compareTo(b.toString()));
            String prefix = "assets/" + domain + "/" + dir;
            for (File root : roots()) {
                try {
                    if (root.isDirectory()) {
                        addPictures(new File(root, prefix), domain, dir, found);
                    } else if (root.isFile()) {
                        ZipFile zip = zips.get(root);
                        if (zip == null) {
                            zip = new ZipFile(root);
                            zips.put(root, zip);
                        }
                        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                            String name = entries.nextElement()
                                .getName();
                            if (name.startsWith(prefix) && name.toLowerCase(Locale.ROOT)
                                .endsWith(".png")) {
                                found.add(new ResourceLocation(domain, dir + name.substring(prefix.length())));
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // Not readable: its pictures are left out.
                }
            }
            listed.put(key, found);
            return found;
        }

        /** The PNG pictures in the folder and its folders (a horse's armor), as resources under {@code dir}. */
        private static void addPictures(File folder, String domain, String dir, Set<ResourceLocation> found) {
            File[] files = folder.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.isDirectory()) {
                    addPictures(file, domain, dir + file.getName() + "/", found);
                } else if (file.getName()
                    .toLowerCase(Locale.ROOT)
                    .endsWith(".png")) {
                        found.add(new ResourceLocation(domain, dir + file.getName()));
                    }
            }
        }

        private List<File> roots() {
            if (roots != null) {
                return roots;
            }
            Set<File> all = new LinkedHashSet<>();
            // The game's own: wherever its textures are read from.
            File game = sourceOf("/assets/minecraft/textures/entity/creeper/creeper.png");
            if (game != null) {
                all.add(game);
            }
            for (ModContainer mod : Loader.instance()
                .getActiveModList()) {
                File source = mod.getSource();
                if (source != null && source.exists()) {
                    all.add(source);
                }
            }
            // Resource packs in use.
            try {
                ResourcePackRepository repository = Minecraft.getMinecraft()
                    .getResourcePackRepository();
                for (Object entry : repository.getRepositoryEntries()) {
                    IResourcePack pack = ((ResourcePackRepository.Entry) entry).getResourcePack();
                    File file = packFile(pack);
                    if (file != null) {
                        all.add(file);
                    }
                }
            } catch (Throwable ignored) {
                // Without the resource packs' extra pictures.
            }
            roots = new ArrayList<>(all);
            return roots;
        }

        /** The jar or folder a resource of the class path is in. */
        private static File sourceOf(String resource) {
            try {
                URL url = MobTextureDump.class.getResource(resource);
                if (url == null) {
                    return null;
                }
                String text = URLDecoder.decode(url.toString(), "UTF-8");
                if (text.startsWith("jar:file:")) {
                    return new File(text.substring("jar:file:".length(), text.indexOf("!/")));
                }
                if (text.startsWith("file:")) {
                    String file = text.substring("file:".length());
                    return new File(file.substring(0, file.length() - resource.length()));
                }
            } catch (Exception ignored) {
                // Not found: the game's own variants are left out.
            }
            return null;
        }

        /** The file or folder of a resource pack, found by its type. */
        private static File packFile(IResourcePack pack) {
            if (!(pack instanceof AbstractResourcePack)) {
                return null;
            }
            for (Field field : AbstractResourcePack.class.getDeclaredFields()) {
                if (field.getType() == File.class) {
                    try {
                        field.setAccessible(true);
                        return (File) field.get(pack);
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                }
            }
            return null;
        }

        void close() {
            for (ZipFile zip : zips.values()) {
                try {
                    zip.close();
                } catch (IOException ignored) {}
            }
            zips.clear();
        }
    }
}
