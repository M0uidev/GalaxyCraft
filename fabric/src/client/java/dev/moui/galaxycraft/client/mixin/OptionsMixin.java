package dev.moui.galaxycraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Arrays;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No clouds: they would hang over SMG2's sky, and the galaxy has no sky of its own to hold them.
 * No Friends or Social Interactions keys: there are no friends or other players to show, so they
 * are unbound and left out of the Controls list.
 */
@Mixin(Options.class)
abstract class OptionsMixin {
    @Shadow @Mutable public KeyMapping[] keyMappings;
    @Shadow public KeyMapping keyFriends;
    @Shadow public KeyMapping keySocialInteractions;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void galaxycraft$noSocialKeys(CallbackInfo ci) {
        keyFriends.setKey(InputConstants.UNKNOWN);
        keySocialInteractions.setKey(InputConstants.UNKNOWN);
        keyMappings = Arrays.stream(keyMappings).filter(k -> k != keyFriends && k != keySocialInteractions)
                .toArray(KeyMapping[]::new);
        KeyMapping.resetMapping();
    }

    @Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$noClouds(CallbackInfoReturnable<CloudStatus> cir) {
        cir.setReturnValue(CloudStatus.OFF);
    }
}
