package dev.moui.galaxycraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** How a mob's renderer turns and sizes its model, for the game's copy of it (EntityClient). */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
    @Invoker("setupRotations")
    void galaxycraft$setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale);

    @Invoker("scale")
    void galaxycraft$scale(LivingEntityRenderState state, PoseStack poseStack);
}
