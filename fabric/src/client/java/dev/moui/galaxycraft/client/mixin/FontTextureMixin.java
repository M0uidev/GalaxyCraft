package dev.moui.galaxycraft.client.mixin;

import dev.moui.galaxycraft.client.FontPages;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A glyph added to a font page: the game's copy of that page (FontPages) is old now. */
@Mixin(FontTexture.class)
abstract class FontTextureMixin {
    @Inject(method = "add", at = @At("RETURN"))
    private void galaxycraft$added(CallbackInfoReturnable<?> cir) {
        if (cir.getReturnValue() != null) FontPages.changed(((AbstractTexture) (Object) this).getTexture());
    }
}
