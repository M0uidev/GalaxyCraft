package dev.moui.galaxycraft.client.mixin;

import java.util.Arrays;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Create World gets a GalaxyCraft tab after Minecraft's three (GalaxyTab). */
@Mixin(CreateWorldScreen.class)
abstract class CreateWorldScreenMixin {
    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;addTabs([Lnet/minecraft/client/gui/components/tabs/Tab;)Lnet/minecraft/client/gui/components/tabs/MenuTabBar$Builder;"))
    private Tab[] galaxycraft$tab(Tab[] tabs) {
        Tab[] out = Arrays.copyOf(tabs, tabs.length + 1);
        out[tabs.length] = dev.moui.galaxycraft.client.GalaxyTabs.make((CreateWorldScreen) (Object) this);
        return out;
    }
}
