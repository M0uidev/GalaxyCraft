package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.swim.SwimHook;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player's fluid check skips levels whose chunks are not there or hold no fluid: the planet's water is neither. */
@Mixin(EntityFluidInteraction.class)
abstract class PlanetWaterLoadedMixin {
    @Inject(method = "hasFluidAndLoaded", at = @At("HEAD"), cancellable = true)
    private static void galaxycraft$planetHasFluid(Level level, int x0, int y0, int z0, int x1, int y1, int z1,
            CallbackInfoReturnable<Boolean> cir) {
        if (SwimHook.on() && !ShadowWorld.isShadow(level)) cir.setReturnValue(true);
    }
}
