package dev.moui.galaxycraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F2: with Dolphin showing the game, Dolphin takes the picture (Minecraft's own framebuffer is blank). */
@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
    @Inject(method = "grab(Lnet/minecraft/client/Minecraft;Z)V", at = @At("HEAD"), cancellable = true)
    private static void galaxycraft$hostShot(Minecraft mc, boolean debug, CallbackInfo ci) {
        if (dev.moui.galaxycraft.client.HostScreenshot.take(mc)) ci.cancel();
    }
}
