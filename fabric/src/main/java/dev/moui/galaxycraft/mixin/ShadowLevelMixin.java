package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shadow dimension has no players: its sounds are played to the players where they are,
 * quieter the farther from Mario they were made. Items dropped there, or thrown by the player
 * on a planet, become the planet's drops; experience goes to the player.
 */
@Mixin(ServerLevel.class)
abstract class ShadowLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$dropsOnThePlanet(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (entity instanceof ItemEntity item && ShadowWorld.catchItem(self, item)) cir.setReturnValue(false);
        else if (entity instanceof ExperienceOrb orb && ShadowWorld.isShadow(self)) {
            ShadowWorld.giveExperience(self, orb.getValue());
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void galaxycraft$soundAtMario(@Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!ShadowWorld.isShadow(self)) return;
        ci.cancel();
        double range = sound.value().getRange(volume), d = ShadowWorld.distanceToMario(x, y, z);
        if (d >= range) return;
        float v = (float) (volume * (1 - d / range));
        for (ServerPlayer p : self.getServer().getPlayerList().getPlayers())
            p.connection.send(new ClientboundSoundPacket(sound, source, p.getX(), p.getEyeY(), p.getZ(), v, pitch, seed));
    }
}
