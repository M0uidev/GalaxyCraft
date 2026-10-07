package dev.moui.galaxycraft.client;

import java.net.URI;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Minecraft's title screen is GalaxyCraft's: no Multiplayer nor Realms (worlds are galaxies of
 * this computer's SMG2), "Super Minecraft Galaxy" and its author under the logo, a button to the
 * Discord server, and how Super Mario Galaxy 2 is doing behind it (Dolphin boots it into
 * GalaxyCraftSpace while this screen shows).
 */
final class TitleMenu {
    private static final String[] GONE = {"menu.multiplayer", "menu.online"};
    private static final int GOLD = 0xFFFFD34D, GREY = 0xFFA0A0A0;
    static final String AUTHOR = "@M0uiDev";
    static final URI DISCORD = URI.create("https://discord.gg/NhKmT6cVM7");
    static final URI YOUTUBE = URI.create("https://www.youtube.com/@m0uidev");

    private TitleMenu() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            trim(Screens.getWidgets(screen));
            addDiscord(screen, Screens.getWidgets(screen));
            addAuthor(screen, Screens.getWidgets(screen));
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, a) -> {
                var font = Minecraft.getInstance().font;
                g.centeredText(font, "Super Minecraft Galaxy", s.width / 2, 78, GOLD);
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

    /** "Join the Discord" right under Singleplayer, where Multiplayer was; what was below moves down. */
    private static void addDiscord(Screen screen, List<AbstractWidget> widgets) {
        AbstractWidget single = widgets.stream().filter(w -> key(w, "menu.singleplayer")).findFirst().orElse(null);
        if (single == null) return;
        int row = single.getHeight() + 4, y = single.getY() + row;
        for (AbstractWidget w : widgets)
            if (w.getY() >= y && w.getY() < y + 200) w.setY(w.getY() + row);
        widgets.add(Button.builder(Component.literal("Join the Discord"), ConfirmLinkScreen.confirmLink(screen, DISCORD, true))
                .bounds(single.getX(), y, single.getWidth(), single.getHeight()).build());
    }

    /** "by @M0uiDev" under "Super Minecraft Galaxy"; it opens the author's YouTube channel. */
    private static void addAuthor(Screen screen, List<AbstractWidget> widgets) {
        var font = Minecraft.getInstance().font;
        Component text = Component.literal("by " + AUTHOR).withColor(GREY & 0xFFFFFF);
        int w = font.width(text);
        widgets.add(new PlainTextButton(screen.width / 2 - w / 2, 89, w, 10, text,
                ConfirmLinkScreen.confirmLink(screen, YOUTUBE, true), font));
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }
}
