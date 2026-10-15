package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mario mode moves Steve after his old position is saved, so he is drawn and animated walking. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void galaxycraft$follow(CallbackInfo ci) {
        GalaxyCraftClient.onPlayerTick((LocalPlayer) (Object) this);
    }

    /** A guest's Q on a planet the host runs: the host throws the item onto the planet (the server's own throw would land in the host's coordinates). */
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$guestThrow(boolean all, CallbackInfoReturnable<Boolean> cir) {
        if (dev.moui.galaxycraft.client.PlanetClient.guestThrow((LocalPlayer) (Object) this, all)) cir.setReturnValue(true);
    }
}
