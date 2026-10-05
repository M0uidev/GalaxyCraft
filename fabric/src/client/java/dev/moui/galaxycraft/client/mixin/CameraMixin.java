package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.client.GalaxyCraftClient;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Perspectives: Minecraft's camera is reported to SMG2, which draws from it (outside the Galaxy
 * view). Third person stops short of the galaxy's collision.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
    /** Kept between the camera and a wall, in blocks. */
    private static final double WALL_MARGIN = 0.2;

    @Shadow private Entity entity;
    @Shadow private Vec3 position;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void galaxycraft$perspective(float partialTicks, CallbackInfo ci) {
        if (!GalaxyCraftClient.exportingOverlay()) return;
        Vector3d feet = new Vector3d(Mth.lerp(partialTicks, entity.xo, entity.getX()),
                Mth.lerp(partialTicks, entity.yo, entity.getY()), Mth.lerp(partialTicks, entity.zo, entity.getZ()));
        GalaxyCraftClient.onCameraAligned(new Vector3d(position.x, position.y, position.z), vec(forwards), vec(up), feet,
                partialTicks);
    }

    @ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), argsOnly = true)
    private float galaxycraft$distance(float cameraDist) {
        return GalaxyCraftClient.exportingOverlay() ? GalaxyCraftClient.thirdPersonDistance(cameraDist) : cameraDist;
    }

    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$stopAtWalls(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (!GalaxyCraftClient.exportingOverlay()) return;
        float dist = cir.getReturnValue();
        Vector3d back = vec(forwards).negate();
        double hit = GalaxyCraft.FIELD.clipDistance(new Vector3d(position.x, position.y, position.z), back, dist);
        if (hit < dist) cir.setReturnValue((float) Math.max(0, hit - WALL_MARGIN));
        if (Boolean.getBoolean("galaxycraft.camlog")) GalaxyCraftClient.camLog(new Vector3d(position.x, position.y, position.z), back, dist, hit);
    }

    private static Vector3d vec(Vector3f v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
