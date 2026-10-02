package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.GalaxyCraft;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The galaxy's ground is not made of blocks, so the server would think a player standing on a
 * planet is floating and kick them. While a galaxy is linked, report that blocks are around.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$galaxyGroundCounts(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (GalaxyCraft.FIELD.hasFrame()) cir.setReturnValue(false);
    }
}
