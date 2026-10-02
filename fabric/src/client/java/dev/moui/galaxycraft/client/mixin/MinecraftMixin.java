package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
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
}
