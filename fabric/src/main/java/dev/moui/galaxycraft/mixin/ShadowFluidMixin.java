package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava flow on the planet (voxel/Fluids, across the cube's edges), not in the shadow. */
@Mixin(FlowingFluid.class)
abstract class ShadowFluidMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$planetFlows(ServerLevel level, BlockPos pos, BlockState block, FluidState fluid, CallbackInfo ci) {
        if (ShadowWorld.isShadow(level)) ci.cancel();
    }
}
