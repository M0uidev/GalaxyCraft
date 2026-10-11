package dev.moui.galaxycraft.mixin;

import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gliding wears out one of the worn gliders, picked at random; aboard a ship none is worn and the
 * list is empty (vanilla's nextInt(0) throws). An empty slot's stack takes no damage, so it stands in.
 */
@Mixin(LivingEntity.class)
abstract class LivingGlideMixin {
    @Redirect(method = "updateFallFlying", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;getRandom(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;"))
    private Object galaxycraft$noGliderWorn(List<?> list, RandomSource random) {
        return list.isEmpty() ? EquipmentSlot.CHEST : Util.getRandom(list, random);
    }
}
