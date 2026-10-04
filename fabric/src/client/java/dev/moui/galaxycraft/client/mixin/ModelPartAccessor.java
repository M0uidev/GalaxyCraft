package dev.moui.galaxycraft.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A model's pieces and their cubes, for the game's copy of Minecraft's entities (EntityClient). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
    @Accessor("cubes")
    List<ModelPart.Cube> galaxycraft$cubes();

    @Accessor("children")
    Map<String, ModelPart> galaxycraft$children();
}
