package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft movement deep in a planet: Steve's box as narrow as its blocks (GalaxyCraftClient.walkWidth). */
@Mixin(Entity.class)
abstract class EntityMixin {
    @Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$narrow(Vec3 pos, CallbackInfoReturnable<AABB> cir) {
        if (!((Object) this instanceof LocalPlayer)) return;
        double w = GalaxyCraftClient.walkWidth();
        AABB box = cir.getReturnValue();
        if (w >= box.getXsize() - 1e-6) return;
        cir.setReturnValue(new AABB(pos.x - w / 2, box.minY, pos.z - w / 2, pos.x + w / 2, box.maxY, pos.z + w / 2));
    }
}
