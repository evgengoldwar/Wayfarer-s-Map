package WayFarMap.mixins.early;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import WayFarMap.client.MobTextureDump;

/**
 * Notes the textures bound while {@code /wfmobtextures} draws a mob, to know every texture it uses (layers, eyes).
 * Does nothing at any other time.
 */
@Mixin(TextureManager.class)
public abstract class MixinTextureManager {

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void wayfarmap$noteBound(ResourceLocation location, CallbackInfo ci) {
        MobTextureDump.noteBound(location);
    }
}
