package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A chest or furnace on the planet stays open: its block is in the shadow dimension, far from the player. */
@Mixin(Player.class)
abstract class ShadowReachMixin {
    @Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$planetBlocksInReach(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
        if (pos.getZ() >= ShadowMap.Z_BASE) cir.setReturnValue(true);
    }
}
