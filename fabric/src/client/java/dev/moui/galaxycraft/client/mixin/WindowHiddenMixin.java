package dev.moui.galaxycraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With the window hidden (Dolphin shows the game), Minecraft's own window is created hidden, not
 * hidden once it is up: a window that appears for an instant makes some compositors (Hyprland) take
 * Dolphin out of fullscreen. GalaxyCraftClient shows it again if Dolphin does not come up.
 */
@Mixin(Window.class)
abstract class WindowHiddenMixin {
    /** SDL_WINDOW_HIDDEN. */
    private static final long SDL_WINDOW_HIDDEN = 0x8L;
    private static final int FRAME_W = 1920, FRAME_H = 1080;

    @ModifyArg(method = "createWindow", index = 3, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
    private long galaxycraft$createHidden(long flags) {
        return Boolean.getBoolean("galaxycraft.hidden") ? flags | SDL_WINDOW_HIDDEN : flags;
    }

    /**
     * And its frame at the overlay's full size: Dolphin stretches Minecraft's frame over its own
     * window, so a small hidden window (854x480, and a hidden window cannot be made bigger on
     * Wayland) made the inventory and hotbar blocky. 1920x1080 is the biggest frame the overlay
     * carries (OverlayWriter scales a smaller Dolphin window down by averaging).
     */
    @Inject(method = "queryFramebufferSize", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$frameSize(CallbackInfoReturnable<Window.FramebufferSize> cir) {
        if (Boolean.getBoolean("galaxycraft.hidden")) cir.setReturnValue(new Window.FramebufferSize(FRAME_W, FRAME_H));
    }

    @ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int galaxycraft$resizeW(int width) {
        return Boolean.getBoolean("galaxycraft.hidden") ? FRAME_W : width;
    }

    @ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int galaxycraft$resizeH(int height) {
        return Boolean.getBoolean("galaxycraft.hidden") ? FRAME_H : height;
    }
}
