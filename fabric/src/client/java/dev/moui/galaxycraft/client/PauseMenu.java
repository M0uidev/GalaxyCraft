package dev.moui.galaxycraft.client;

import java.util.List;
import java.util.Set;
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
 * playable). Its feedback buttons, of no use here, become GalaxyCraft's: GalaxyCraft... opens
 * {@link GalaxySettingsScreen}, SMG2 Menu presses the + button that Escape no longer does.
 */
final class PauseMenu {
    /** Pause menu buttons whose place GalaxyCraft's take, by their text's translation key. */
    private static final Set<String> SPARE = Set.of("menu.sendFeedback", "menu.reportBugs", "menu.feedback", "menu.server_links");

    private PauseMenu() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof PauseScreen) addButtons(mc, screen);
        });
    }

    private static void addButtons(Minecraft mc, Screen pause) {
        List<AbstractWidget> buttons = Screens.getWidgets(pause);
        if (buttons.isEmpty()) return; // F3+Esc: paused without a menu
        List<AbstractWidget> spare = buttons.stream().filter(PauseMenu::spare).toList();
        // Two places: the two spare buttons', one spare button's halves, or else the top corner.
        int[][] at;
        if (spare.size() >= 2) at = new int[][] {bounds(spare.get(0)), bounds(spare.get(1))};
        else if (spare.size() == 1) {
            int[] b = bounds(spare.get(0));
            int half = (b[2] - 4) / 2;
            at = new int[][] {{b[0], b[1], half}, {b[0] + b[2] - half, b[1], half}};
        } else at = new int[][] {{4, 4, 98}, {106, 4, 98}};
        buttons.removeAll(spare);

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

    private static boolean spare(AbstractWidget w) {
        return w.getMessage().getContents() instanceof TranslatableContents t && SPARE.contains(t.getKey());
    }

    private static int[] bounds(AbstractWidget w) {
        return new int[] {w.getX(), w.getY(), w.getWidth()};
    }
}
