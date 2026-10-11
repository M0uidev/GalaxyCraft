package dev.moui.galaxycraft.client.mixin;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's Video settings without what changes nothing here: Dolphin draws the world, so
 * ambient occlusion, mipmaps, texture filtering, shadows, biome blend, gamma, clouds, V-Sync,
 * fullscreen and the like have no effect on what is seen. What stays shapes the game (distances,
 * particles, frame rate limit, GUI scale, the HUD's own options).
 */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin {
    @Inject(method = {"qualityOptions", "displayOptions", "preferenceOptions"}, at = @At("RETURN"), cancellable = true)
    private static void galaxycraft$onlyWhatMatters(Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        List<OptionInstance<?>> useless = List.of(
                options.ambientOcclusion(), options.biomeBlendRadius(), options.cloudRange(), options.cloudStatus(),
                options.cutoutLeaves(), options.enableVsync(), options.entityShadows(), options.exclusiveFullscreen(),
                options.fullscreen(), options.gamma(), options.improvedTransparency(), options.inactivityFpsLimit(),
                options.macFullscreenMenuVisibility(), options.maxAnisotropyBit(), options.mipmapLevels(),
                options.preferredGraphicsBackend(), options.prioritizeChunkUpdates(), options.textureFiltering(),
                options.weatherRadius(), options.chunkSectionFadeInTime());
        cir.setReturnValue(Arrays.stream(cir.getReturnValue()).filter(o -> !useless.contains(o)).toArray(OptionInstance[]::new));
    }
}
