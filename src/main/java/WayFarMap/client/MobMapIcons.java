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
    }

    static boolean hasIcon(Class<?> kind) {
        return ICONS.computeIfAbsent(kind, MobMapIcons::read)
            .isPresent();
    }

    static Integer texture(Class<?> kind) {
        Icon icon = ICONS.computeIfAbsent(kind, MobMapIcons::read)
            .orElse(null);
        return icon == null ? null : icon.texture();
    }

    private static Optional<Icon> read(Class<?> kind) {
        String key = MobIconDump.mobKey(kind)
            .toLowerCase(Locale.ROOT);
        ResourceLocation png = new ResourceLocation("wayfarmap", "textures/mob_icons/" + key + ".png");
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
        private int texture;

        Icon(BufferedImage image) {
            this.image = image;
        }

        int texture() {
            if (texture == 0) {
                texture = GlTextures.create();
                // Nearest-neighbor filtering preserves the pixels, clamp keeps the transparent edge.
                TextureUtil.uploadTextureImageAllocate(texture, image, false, true);
            }
            return texture;
        }

        void release() {
            if (texture != 0) {
                GlTextures.delete(texture);
            }
        }
    }
}
