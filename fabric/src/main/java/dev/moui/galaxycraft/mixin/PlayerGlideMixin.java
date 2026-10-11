package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.ship.ShipFlightState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Aboard a ship the player glides as with elytra, without wearing any: the ship carries it. */
@Mixin(Player.class)
abstract class PlayerGlideMixin {
    @Inject(method = "canGlide", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$aShipGlides(CallbackInfoReturnable<Boolean> cir) {
        if (ShipFlightState.boarded() != null) cir.setReturnValue(true);
    }
}
