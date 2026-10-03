package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change in the shadow dimension goes back to the planet; outside the planet's own
 * cells (the halo copies, the gaps between faces) only GalaxyCraft writes.
 */
@Mixin(LevelChunk.class)
abstract class ShadowChunkMixin {
    @Shadow @Final private Level level;

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$onlyPlanetCells(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (ShadowWorld.isShadow(level) && !ShadowWorld.writable(pos)) cir.setReturnValue(null);
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void galaxycraft$backToPlanet(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && ShadowWorld.isShadow(level)) ShadowWorld.changed(pos, state);
    }
}
