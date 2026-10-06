package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** What the player wrote on a planet's sign goes to the shadow's sign (ShadowWorld.signEdited), not to the player's level. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class SignUpdateMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$shadowSign(ServerboundSignUpdatePacket packet, List<FilteredText> lines, CallbackInfo ci) {
        if (ShadowWorld.signEdited(player, packet.pos(), packet.slot(), lines)) ci.cancel();
    }
}
