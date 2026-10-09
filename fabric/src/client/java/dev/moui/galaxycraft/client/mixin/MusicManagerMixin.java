package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.music.MusicService;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
abstract class MusicManagerMixin {
    /** The soundtrack plays Minecraft's songs itself (with its crossfade): vanilla stays quiet. */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$quiet(CallbackInfo ci) {
        if (MusicService.shouldReplaceVanilla()) ci.cancel();
    }
}
