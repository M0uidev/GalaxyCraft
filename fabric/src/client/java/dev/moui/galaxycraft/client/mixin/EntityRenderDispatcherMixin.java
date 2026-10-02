package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cutscenes show Mario himself: Steve steps out of the picture until they end. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void galaxycraft$hideSteve(E entity, Frustum culler, double camX, double camY,
            double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (entity == Minecraft.getInstance().player && GalaxyCraftClient.hideSteve()) cir.setReturnValue(false);
    }
}
