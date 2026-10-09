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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Esc opens Minecraft's own pause menu (Dolphin gives Escape to Minecraft while Mario is
 * playable), with a Super Minecraft Galaxy... button above Options... that opens
 * {@link GalaxySettingsScreen}.
 */
final class PauseMenu {
    /** The pause menu's rows are this far apart, pixels. */
    private static final int ROW = 24;

    private PauseMenu() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof PauseScreen) addButtons(mc, screen);
        });
    }

    private static void addButtons(Minecraft mc, Screen pause) {
        List<AbstractWidget> buttons = Screens.getWidgets(pause);
        if (buttons.isEmpty()) return; // F3+Esc: paused without a menu
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
        buttons.add(Button.builder(Component.literal("Super Minecraft Galaxy..."), b -> mc.gui.setScreen(new GalaxySettingsScreen(pause)))
                .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.literal(
                        "Movement, skin and Super Minecraft Galaxy's other settings")))
                .build());
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

}
