package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player uses shadow mobs from where Minecraft keeps them (ShadowWorld.interact): a saddled
 * pig or a horse would take them into the shadow dimension. They stay; Mario rides minecarts
 * and boats his own way.
 */
@Mixin(Entity.class)
abstract class ShadowRideMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$notIntoTheShadow(Entity vehicle, boolean force, boolean sendEvent, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer && ShadowWorld.isShadow(vehicle.level())
                && !ShadowWorld.isShadow(((Entity) (Object) this).level()))
            cir.setReturnValue(false);
    }
}
