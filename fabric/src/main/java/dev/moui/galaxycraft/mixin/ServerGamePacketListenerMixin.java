package dev.moui.galaxycraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
 * The client also places the player where SMG2 puts Mario, not where the server's collision
 * would: the server trusts that move instead of teleporting the player back with the rotation it
 * last heard of, which snapped the view back several frames whenever the mouse turned.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    @Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$galaxyGroundCounts(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (GalaxyCraft.FIELD.hasFrame()) cir.setReturnValue(false);
    }

    /**
     * A guest in a world opened to LAN is not its single player owner, so Minecraft measures its
     * speed and says "moved too quickly" and puts it back: the player is placed by SMG2 and by the
     * galaxy's own scale, which no vanilla speed limit fits. Friends are trusted (as the host's own
     * player always was): the check is off in a world someone hosts.
     */
    @Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$trustHostedPlayers(boolean elytra, CallbackInfoReturnable<Boolean> cir) {
        if (((ServerGamePacketListenerImpl) (Object) this).player.level().getServer().isSingleplayer()) cir.setReturnValue(false);
    }

    @ModifyExpressionValue(method = "handlePlayerPositionChange", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;isInPostImpulseGraceTime()Z"))
    private boolean galaxycraft$trustGalaxyMoves(boolean grace) {
        return grace || GalaxyCraft.FIELD.hasFrame();
    }
}
