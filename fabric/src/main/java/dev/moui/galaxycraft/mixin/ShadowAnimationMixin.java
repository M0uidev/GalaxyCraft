package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mobs in the shadow are drawn by the game from the server's own entities (no client copy of
 * them exists), so the animation Minecraft works out only on the client is worked out here too:
 * the walk cycle of the legs and the animation states some mobs set up each tick.
 */
@Mixin(LivingEntity.class)
abstract class ShadowAnimationMixin extends Entity {
    private ShadowAnimationMixin() {
        super(null, null);
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void galaxycraft$animateInTheShadow(CallbackInfo ci) {
        if (level().isClientSide() || !ShadowWorld.isShadow(level())) return;
        ((LivingEntity) (Object) this).calculateEntityAnimation(omnidirectionalAirMover());
        ShadowWorld.setupAnimationStates((LivingEntity) (Object) this);
    }
}
