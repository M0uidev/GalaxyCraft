package dev.moui.galaxycraft.mixin;

import dev.moui.galaxycraft.shadow.ShadowWorld;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Mob caps in the shadow by the planet's ground near Mario, not by every chunk loaded around his
 * stand-in (mostly void): Minecraft's density, 70 monsters to 17 × 17 chunks of land.
 */
@Mixin(NaturalSpawner.class)
abstract class ShadowSpawnMixin {
    @ModifyVariable(method = "spawnForChunk", at = @At("HEAD"), argsOnly = true)
    private static List<MobCategory> galaxycraft$planetCaps(List<MobCategory> categories, ServerLevel level, LevelChunk chunk,
            NaturalSpawner.SpawnState state) {
        return ShadowWorld.isShadow(level) ? ShadowWorld.underCap(categories) : categories;
    }
}
