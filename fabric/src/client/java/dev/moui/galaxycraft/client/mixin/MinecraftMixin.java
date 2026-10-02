package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft's window is hidden behind Dolphin's: it counts as focused while the host is linked. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$hostFocused(CallbackInfoReturnable<Boolean> cir) {
        if (GalaxyCraftClient.exportingOverlay()) cir.setReturnValue(true);
    }
}
