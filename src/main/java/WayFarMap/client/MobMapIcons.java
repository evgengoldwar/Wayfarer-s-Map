package WayFarMap.client;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;

import WayFarMap.WayFarMap;

/** Ready-made icons for the flat map and minimap, looked up by the same mob key as in the texture dump. */
public final class MobMapIcons {

    private static final Map<Class<?>, Optional<Icon>> ICONS = new HashMap<>();

    private MobMapIcons() {}

    public static void reload() {
        for (Optional<Icon> icon : ICONS.values()) {
            icon.ifPresent(Icon::release);
        }
        ICONS.clear();
        MobIcons.reset();
    }

    static boolean hasIcon(Class<?> kind) {
        return ICONS.computeIfAbsent(kind, MobMapIcons::read)
            .isPresent();
    }

    static MobIcons.Icon icon(Class<?> kind) {
        Icon icon = ICONS.computeIfAbsent(kind, MobMapIcons::read)
            .orElse(null);
        return icon == null ? null : icon.upload();
    }

    private static Optional<Icon> read(Class<?> kind) {
        String key = MobIconDump.mobKey(kind);
        int slash = key.indexOf('/');
        // Registry IDs can contain spaces, colons and other characters that are unsafe in resource paths.
        String mod = MobIconDump.safe(key.substring(0, slash));
        String name = MobIconDump.safe(key.substring(slash + 1));
        if (".".equals(mod) || "..".equals(mod)) {
            return Optional.empty();
        }
        String path = ("textures/mob_icons/" + mod + "/" + name + ".png").toLowerCase(Locale.ROOT);
        ResourceLocation png = new ResourceLocation("wayfarmap", path);
        try (InputStream in = Minecraft.getMinecraft()
            .getResourceManager()
            .getResource(png)
            .getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("Invalid PNG");
            }
            return Optional.of(new Icon(image));
        } catch (FileNotFoundException ignored) {
            return Optional.empty();
        } catch (IOException e) {
            WayFarMap.LOG.warn("Could not read map icon " + png, e);
            return Optional.empty();
        }
    }

    /** Checking coverage for a dump reads the PNG without uploading it. */
    private static final class Icon {

        private final BufferedImage image;
        private MobIcons.Icon uploaded;

        Icon(BufferedImage image) {
            this.image = image;
        }

        MobIcons.Icon upload() {
            if (uploaded == null) {
                int texture = GlTextures.create();
                // Nearest-neighbor filtering preserves the pixels, clamp keeps the transparent edge.
                TextureUtil.uploadTextureImageAllocate(texture, image, false, true);
                int width = image.getWidth(), height = image.getHeight();
                uploaded = new MobIcons.Icon(
                    texture,
                    new MobIconOutline(width, height, image.getRGB(0, 0, width, height, null, 0, width), true));
            }
            return uploaded;
        }

        void release() {
            if (uploaded != null) {
                uploaded.release();
            }
        }
    }
}
