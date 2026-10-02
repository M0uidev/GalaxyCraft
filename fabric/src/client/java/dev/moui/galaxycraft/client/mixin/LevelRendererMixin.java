package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Galaxy 2 draws the sky; Minecraft's would cover the whole overlay. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$noSky(CallbackInfo ci) {
        if (GalaxyCraftClient.exportingOverlay()) ci.cancel();
    }
}
