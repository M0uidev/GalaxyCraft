package dev.moui.galaxycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.moui.galaxycraft.GalaxyCraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sneaking keeps the player off the edges of the galaxy's blocks too: Minecraft looks for ground
 * under the moved box with Level.noCollision, which knows only its own world's blocks.
 */
@Mixin(Player.class)
abstract class PlayerEdgeMixin {
    @WrapOperation(method = "canFallAtLeast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean galaxycraft$galaxyGround(Level level, Entity entity, AABB box, Operation<Boolean> original) {
        if (!original.call(level, entity, box)) return false;
        return !GalaxyCraft.FIELD.hasFrame()
                || !GalaxyCraft.FIELD.blocked(new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
    }
}
