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
 * playable), with a row of GalaxyCraft's above Options...: GalaxyCraft... opens
 * {@link GalaxySettingsScreen}, SMG2 Menu presses the + button that Escape no longer does.
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
        // A row of their own where Options... and World Options... are, those and the rest pushed
        // down a row; without Options... (another version's menu), the top left corner.
        AbstractWidget options = buttons.stream().filter(w -> key(w, "menu.options")).findFirst().orElse(null);
        int[][] at;
        if (options != null) {
            int y = options.getY(), w = options.getWidth();
            for (AbstractWidget b : buttons)
                if (b.getY() >= y) b.setY(b.getY() + ROW);
            int right = buttons.stream().filter(b -> b.getY() == y + ROW && b != options).mapToInt(AbstractWidget::getX)
                    .max().orElse(options.getX() + w + 8);
            at = new int[][] {{options.getX(), y, w}, {right, y, w}};
        } else at = new int[][] {{4, 4, 98}, {106, 4, 98}};

        buttons.add(Button.builder(Component.literal("GalaxyCraft..."), b -> mc.gui.setScreen(new GalaxySettingsScreen(pause)))
                .bounds(at[0][0], at[0][1], at[0][2], 20).tooltip(Tooltip.create(Component.literal(
                        "Movement, skin and GalaxyCraft's other settings")))
                .build());
        Button smg2 = Button.builder(Component.literal("SMG2 Menu"), b -> {
            mc.gui.setScreen(null);
            GalaxyCraftClient.pressPlus();
        }).bounds(at[1][0], at[1][1], at[1][2], 20).tooltip(Tooltip.create(Component.literal(
                "Super Mario Galaxy 2's own pause menu (the + button)"))).build();
        smg2.active = GalaxyCraftClient.linked();
        buttons.add(smg2);
    }

    private static boolean key(AbstractWidget w, String key) {
        return w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

}
