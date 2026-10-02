package dev.moui.galaxycraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frame hooks: host input before rendering, transparent clear, overlay export after the GUI. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    private static final Vector4fc TRANSPARENT = new Vector4f(0, 0, 0, 0);

    @Shadow @Final private RenderTarget mainRenderTarget;

    @Inject(method = "render", at = @At("HEAD"))
    private void galaxycraft$frameStart(CallbackInfo ci) {
        GalaxyCraftClient.onFrameStart();
    }

    @ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"),
            index = 1)
    private Vector4fc galaxycraft$transparentClear(Vector4fc color) {
        return GalaxyCraftClient.exportingOverlay() ? TRANSPARENT : color;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void galaxycraft$frameEnd(CallbackInfo ci) {
        GalaxyCraftClient.onFrameEnd(mainRenderTarget);
    }
}
