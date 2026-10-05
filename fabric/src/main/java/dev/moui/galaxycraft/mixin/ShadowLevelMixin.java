package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
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
    /**
     * Monsters spawn on the planets (the shadow) alone: the hidden player's own world stays free of
     * them, or they would hurt the player from where nobody sees.
     */
    @Inject(method = "isSpawningMonsters", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$monstersOnPlanetsOnly(CallbackInfoReturnable<Boolean> cir) {
        if (ShadowWorld.hidesPlayer() && !ShadowWorld.isShadow((ServerLevel) (Object) this)) cir.setReturnValue(false);
    }

    /** No phantoms, patrols, cats or traders in the shadow: they count on players Mario's proxy is not. */
    @Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$noCustomSpawners(boolean spawnEnemies, CallbackInfo ci) {
        if (ShadowWorld.isShadow((ServerLevel) (Object) this)) ci.cancel();
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$dropsOnThePlanet(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (entity instanceof ItemEntity item && ShadowWorld.catchItem(self, item)) cir.setReturnValue(false);
        else if (entity instanceof net.minecraft.world.entity.projectile.Projectile p && ShadowWorld.catchProjectile(self, p))
            cir.setReturnValue(false);
        else if (entity instanceof ExperienceOrb orb && ShadowWorld.isShadow(self)) {
            ShadowWorld.giveExperience(self, orb.getValue());
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDDDDLnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket$RandomizationType;)I",
            at = @At("HEAD"))
    private void galaxycraft$particles(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z,
            int count, double xDist, double yDist, double zDist, double xSpeed, double ySpeed, double zSpeed,
            ClientboundLevelParticlesPacket.RandomizationType type, CallbackInfoReturnable<Integer> cir) {
        if (ShadowWorld.isShadow((ServerLevel) (Object) this))
            ShadowWorld.sendParticles(particle, x, y, z, count, xDist, yDist, zDist, xSpeed, ySpeed, zSpeed);
    }

    @Inject(method = "explode", at = @At("TAIL"))
    private void galaxycraft$explosion(@Nullable Entity source, @Nullable DamageSource damageSource,
            @Nullable ExplosionDamageCalculator calculator, double x, double y, double z, float r, boolean fire,
            Level.ExplosionInteraction interaction, ParticleOptions small, ParticleOptions large,
            WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> sound, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (ShadowWorld.isShadow(self)) ShadowWorld.explosion(self, x, y, z, r, r >= 2 ? large : small, sound);
    }

    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void galaxycraft$blockBroken(@Nullable Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (type == 2001 && ShadowWorld.isShadow((ServerLevel) (Object) this)) ShadowWorld.blockBroken(pos, data);
    }

    @Inject(method = "broadcastEntityEvent", at = @At("HEAD"))
    private void galaxycraft$animateEvent(Entity entity, byte id, CallbackInfo ci) {
        if (ShadowWorld.isShadow((ServerLevel) (Object) this)) ShadowWorld.entityEvent(entity, id);
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
