package dev.moui.galaxycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moui.galaxycraft.GalaxyCraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds the galaxy's collision to the shapes a player's movement collides with. Vanilla
 * collision, step-up and ground detection then run unchanged against them.
 */
@Mixin(Entity.class)
abstract class EntityCollideMixin {
    @WrapOperation(method = "collide", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> galaxycraft$addGalaxyShapes(Level level, Entity entity, AABB box,
            Operation<List<VoxelShape>> original) {
        List<VoxelShape> shapes = original.call(level, entity, box);
        if (!(entity instanceof Player) || !GalaxyCraft.FIELD.hasFrame()) return shapes;
        List<double[]> boxes = GalaxyCraft.FIELD.boxesFor(
                new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
        if (boxes.isEmpty()) return shapes;
        List<VoxelShape> out = new ArrayList<>(shapes.size() + boxes.size());
        out.addAll(shapes);
        for (double[] b : boxes) out.add(Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        return out;
    }
}
