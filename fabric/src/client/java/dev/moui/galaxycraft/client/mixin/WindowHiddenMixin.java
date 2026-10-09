package dev.moui.galaxycraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * With the window hidden (Dolphin shows the game), Minecraft's own window is created hidden, not
 * hidden once it is up: a window that appears for an instant makes some compositors (Hyprland) take
 * Dolphin out of fullscreen. GalaxyCraftClient shows it again if Dolphin does not come up.
 */
@Mixin(Window.class)
abstract class WindowHiddenMixin {
    /** SDL_WINDOW_HIDDEN. */
    private static final long SDL_WINDOW_HIDDEN = 0x8L;

    @ModifyArg(method = "createWindow", index = 3, at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
    private long galaxycraft$createHidden(long flags) {
        return Boolean.getBoolean("galaxycraft.hidden") ? flags | SDL_WINDOW_HIDDEN : flags;
    }
}
