package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.swim.SwimHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The planet's water is the level's water where the player is (swim/SwimHook). */
@Mixin(Level.class)
abstract class PlanetWaterMixin {
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$planetWater(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        FluidState f = SwimHook.fluidAt((Level) (Object) this, pos);
        if (f != null) cir.setReturnValue(f);
    }
}
