package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's window is hidden behind Dolphin's: it counts as focused while the host is linked.
 * F5 also reaches SMG2's own camera in Mario mode.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$hostFocused(CallbackInfoReturnable<Boolean> cir) {
        if (GalaxyCraftClient.exportingOverlay()) cir.setReturnValue(true);
    }

    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/CameraType;cycle()Lnet/minecraft/client/CameraType;"))
    private CameraType galaxycraft$cycle(CameraType current) {
        return GalaxyCraftClient.cycleCamera(current);
    }

    /**
     * Each frame, before its state is taken for drawing: the host's input (a click may leave the
     * world: taken after, the frame would still draw the world, with no game mode, and crash).
     */
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void galaxycraft$frameStart(boolean advanceGameTime, CallbackInfo ci) {
        dev.moui.galaxycraft.client.GalaxyCraftClient.onFrameStart();
    }
}
