package dev.moui.galaxycraft.client.mixin;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No clouds: they would hang over SMG2's sky, and the galaxy has no sky of its own to hold them. */
@Mixin(Options.class)
abstract class OptionsMixin {
    @Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$noClouds(CallbackInfoReturnable<CloudStatus> cir) {
        cir.setReturnValue(CloudStatus.OFF);
    }
}
