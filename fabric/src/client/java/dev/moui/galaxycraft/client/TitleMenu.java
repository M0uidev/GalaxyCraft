package dev.moui.galaxycraft.client;

import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Minecraft's title screen is GalaxyCraft's: no Multiplayer nor Realms (worlds are galaxies of
 * this computer's SMG2), "GalaxyCraft" under the logo, and how Super Mario Galaxy 2 is doing
 * behind it (Dolphin boots it into GalaxyCraftSpace while this screen shows).
 */
final class TitleMenu {
    private static final String[] GONE = {"menu.multiplayer", "menu.online"};
    private static final int GOLD = 0xFFFFD34D, GREY = 0xFFA0A0A0;

    private TitleMenu() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            trim(Screens.getWidgets(screen));
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, a) -> {
                var font = Minecraft.getInstance().font;
                g.centeredText(font, "GalaxyCraft", s.width / 2, 78, GOLD);
                g.text(font, GalaxyCraftClient.smg2Status(), 2, 2, GREY);
            });
        });
    }

    /** Takes out the Multiplayer and Realms rows; what was below them moves up. */
    private static void trim(List<AbstractWidget> widgets) {
        for (String key : GONE) {
            AbstractWidget b = widgets.stream().filter(w -> key(w, key)).findFirst().orElse(null);
            if (b == null) continue;
            int y = b.getY(), row = b.getHeight() + 4;
            widgets.remove(b);
            if (widgets.stream().anyMatch(w -> w.getY() == y)) continue; // the row still has others
            for (AbstractWidget w : widgets)
                if (w.getY() > y && w.getY() < y + 200) w.setY(w.getY() - row);
        }
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }
}
