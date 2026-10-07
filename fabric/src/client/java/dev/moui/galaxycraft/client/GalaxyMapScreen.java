package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.universe.Universe;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The galaxy map: the nearest solar systems (and home), each a button that warps there. Laid out as
 * Minecraft's own option screens: a column of buttons under the title, Done at the bottom.
 */
final class GalaxyMapScreen extends Screen {
    private static final int W = 260, ROW = 22, TOP = 36, SHOWN = 10;

    GalaxyMapScreen() {
        super(Component.literal("Galaxy Map"));
    }

    @Override
    protected void init() {
        List<Universe.Star> stars = Warp.nearby();
        int y = TOP;
        for (Universe.Star s : stars.subList(0, Math.min(SHOWN, stars.size()))) {
            addRenderableWidget(Button.builder(Component.literal(Warp.label(s)), b -> {
                onClose();
                Warp.to(minecraft, s);
            }).bounds(width / 2 - W / 2, y, W, 20).build());
            y += ROW;
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 27, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title.getString(), width / 2, 15, 0xFFFFFFFF);
        if (Warp.nearby().isEmpty()) g.centeredText(font, "No other system in reach", width / 2, TOP + 6, 0xFFA0A0A0);
    }
}
