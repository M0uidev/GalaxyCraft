package dev.moui.galaxycraft.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A guest's Q on a planet the host runs: the host throws the item onto the planet (the server's own throw would land in the host's coordinates). */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$guestThrow(LocalPlayer player, boolean all, CallbackInfo ci) {
        // Crouching, the drop key throws the whole stack at once (as Ctrl does).
        if (!all && player.isShiftKeyDown()) {
            ((MultiPlayerGameMode) (Object) this).dropItem(player, true);
            ci.cancel();
            return;
        }
        if (dev.moui.galaxycraft.client.PlanetClient.guestThrow(player, all)) ci.cancel();
    }
}
