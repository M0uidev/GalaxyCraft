package dev.moui.galaxycraft.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How charged a blow is, copied from the player to Mario's stand-in in the shadow. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("attackStrengthTicker")
    int galaxycraft$attackStrengthTicker();

    @Accessor("attackStrengthTicker")
    void galaxycraft$setAttackStrengthTicker(int ticks);
}
