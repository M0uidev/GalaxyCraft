package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mario mode moves Steve after his old position is saved, so he is drawn and animated walking. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void galaxycraft$follow(CallbackInfo ci) {
        GalaxyCraftClient.onPlayerTick((LocalPlayer) (Object) this);
    }
}
