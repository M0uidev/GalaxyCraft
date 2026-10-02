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

/** SMG2 draws Steve (as Mario's model): the overlay never draws the local player while linked. */
@Mixin(EntityRenderDispatcher.class)
abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void galaxycraft$noLocalPlayer(E entity, Frustum culler, double camX, double camY,
            double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (entity == Minecraft.getInstance().player && GalaxyCraftClient.exportingOverlay()) cir.setReturnValue(false);
    }
}
