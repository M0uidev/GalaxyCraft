package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles a client would make (a mob's last poof, hearts), made in the shadow by what is
 * replayed there (ShadowWorld.entityEvent): on a server they would be lost, here they go to the
 * planet, drawn by the game.
 */
@Mixin(Level.class)
abstract class ShadowParticleMixin {
    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"))
    private void galaxycraft$particle(ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz,
            CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level && ShadowWorld.isShadow(level)) ShadowWorld.particle(particle, x, y, z, vx, vy, vz);
    }

    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"))
    private void galaxycraft$alwaysVisible(ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz,
            CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel level && ShadowWorld.isShadow(level)) ShadowWorld.particle(particle, x, y, z, vx, vy, vz);
    }
}
