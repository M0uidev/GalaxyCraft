package dev.moui.galaxycraft.client.mixin;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A mob's adult and baby models (its renderer swaps them only while Minecraft draws it). */
@Mixin(AgeableMobRenderer.class)
public interface AgeableMobRendererAccessor {
    @Accessor("adultModel")
    EntityModel<?> galaxycraft$adultModel();

    @Accessor("babyModel")
    EntityModel<?> galaxycraft$babyModel();
}
