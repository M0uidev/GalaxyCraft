package dev.moui.galaxycraft.client;

import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/** For the Create World mixin (GalaxyTab stays package-private). */
public final class GalaxyTabs {
    private GalaxyTabs() {}

    public static Tab make(CreateWorldScreen screen) {
        return new GalaxyTab(screen);
    }
}
