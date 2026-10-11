package dev.moui.galaxycraft.client;

import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Esc opens Minecraft's own pause menu (Dolphin gives Escape to Minecraft while Mario is
 * playable), with a Super Minecraft Galaxy... button that opens {@link GalaxySettingsScreen}.
 * The row of small icons (report a bug, feedback, friends, player reporting) goes: they open
 * Mojang's web pages and online features this game has no use for, and its button takes the row.
 */
final class PauseMenu {
    /** The pause menu's rows are this far apart, pixels. */
    private static final int ROW = 24;

    private PauseMenu() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof PauseScreen) addButtons(mc, screen);
            else if (screen instanceof OptionsScreen) trimOptions(screen);
        });
    }

    /**
     * Options...: no Online button (Realms, friends, server lists) and no Telemetry (nothing is
     * sent), and Credits takes Telemetry's place.
     */
    private static void trimOptions(Screen options) {
        List<AbstractWidget> widgets = Screens.getWidgets(options);
        AbstractWidget telemetry = widgets.stream().filter(w -> key(w, "options.telemetry")).findFirst().orElse(null);
        AbstractWidget credits = widgets.stream().filter(w -> key(w, "options.credits_and_attribution")).findFirst().orElse(null);
        for (AbstractWidget w : widgets) {
            if (key(w, "options.online") || w == telemetry) {
                w.visible = false;
                w.active = false;
            }
        }
        if (telemetry != null && credits != null) credits.setPosition(telemetry.getX(), telemetry.getY());
    }

    private static void addButtons(Minecraft mc, Screen pause) {
        List<AbstractWidget> buttons = Screens.getWidgets(pause);
        if (buttons.isEmpty()) return; // F3+Esc: paused without a menu
        // The icon row: hidden, and the button takes its place.
        List<AbstractWidget> icons = buttons.stream().filter(w -> w.getWidth() == 20 && w.getHeight() == 20).toList();
        if (icons.size() >= 2) {
            for (AbstractWidget icon : icons) {
                icon.visible = false;
                icon.active = false;
            }
            int x = buttons.stream().filter(w -> w.getWidth() > 20).mapToInt(AbstractWidget::getX).min().orElse(4);
            int right = buttons.stream().filter(w -> w.getWidth() > 20).mapToInt(w -> w.getX() + w.getWidth()).max().orElse(x + 204);
            buttons.add(galaxyButton(mc, pause, x, icons.get(0).getY(), right - x));
            return;
        }
        // A row of its own where Options... is, the rest pushed down a row; with no room for the
        // row (large GUI scale), or without Options... (another version's menu), the top left corner.
        AbstractWidget options = buttons.stream().filter(w -> key(w, "menu.options")).findFirst().orElse(null);
        int x = 4, y = 4, w = 120;
        if (options != null) {
            int bottom = buttons.stream().mapToInt(b -> b.getY() + b.getHeight()).max().orElse(0);
            if (bottom + ROW <= pause.height - 4) {
                y = options.getY();
                for (AbstractWidget b : buttons)
                    if (b.getY() >= y) b.setY(b.getY() + ROW);
                x = buttons.stream().mapToInt(AbstractWidget::getX).min().orElse(options.getX());
                int right = buttons.stream().mapToInt(b -> b.getX() + b.getWidth()).max().orElse(x + w);
                w = right - x;
            }
        }
        buttons.add(galaxyButton(mc, pause, x, y, w));
    }

    private static Button galaxyButton(Minecraft mc, Screen pause, int x, int y, int w) {
        return Button.builder(Component.literal("Super Minecraft Galaxy..."), b -> mc.gui.setScreen(new GalaxySettingsScreen(pause)))
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(
                        "Movement, skin and Super Minecraft Galaxy's other settings")))
                .build();
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

}
