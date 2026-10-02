package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.client.GalaxyCraftClient;
import dev.moui.galaxycraft.gravity.LookMath;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Perspectives: in the Galaxy view the camera is SMG2's (placed relative to Steve); in the others
 * it is Minecraft's, reported to SMG2. Third person stops short of the galaxy's collision.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
    /** Kept between the camera and a wall, in blocks. */
    private static final double WALL_MARGIN = 0.2;

    @Shadow @Final private static Vector3fc FORWARDS;
    @Shadow @Final private static Vector3fc UP;
    @Shadow @Final private static Vector3fc LEFT;
    @Shadow private Entity entity;
    @Shadow private Vec3 position;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private int matrixPropertiesDirty;

    @Shadow protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void galaxycraft$perspective(float partialTicks, CallbackInfo ci) {
        if (!GalaxyCraftClient.exportingOverlay()) return;
        Vector3d feet = new Vector3d(Mth.lerp(partialTicks, entity.xo, entity.getX()),
                Mth.lerp(partialTicks, entity.yo, entity.getY()), Mth.lerp(partialTicks, entity.zo, entity.getZ()));
        GalaxyCraftClient.galaxyCamera(feet).ifPresent(cam -> {
            setPosition(new Vec3(cam.pos().x, cam.pos().y, cam.pos().z));
            rotation.set(cam.rotation());
            FORWARDS.rotate(rotation, forwards);
            UP.rotate(rotation, up);
            LEFT.rotate(rotation, left);
            yRot = (float) LookMath.yaw(cam.forward());
            xRot = (float) LookMath.pitch(cam.forward());
            matrixPropertiesDirty |= 3;
        });
        GalaxyCraftClient.onCameraAligned(new Vector3d(position.x, position.y, position.z), vec(forwards), vec(up), feet,
                partialTicks);
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$galaxyFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (!GalaxyCraftClient.exportingOverlay() || entity == null) return;
        Vector3d feet = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        GalaxyCraftClient.galaxyCamera(feet).ifPresent(cam -> cir.setReturnValue(cam.fovY()));
    }

    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void galaxycraft$stopAtWalls(float cameraDist, CallbackInfoReturnable<Float> cir) {
        if (!GalaxyCraftClient.exportingOverlay()) return;
        float dist = cir.getReturnValue();
        Vector3d back = vec(forwards).negate();
        double hit = GalaxyCraft.FIELD.clipDistance(new Vector3d(position.x, position.y, position.z), back, dist);
        if (hit < dist) cir.setReturnValue((float) Math.max(0, hit - WALL_MARGIN));
    }

    private static Vector3d vec(Vector3f v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
