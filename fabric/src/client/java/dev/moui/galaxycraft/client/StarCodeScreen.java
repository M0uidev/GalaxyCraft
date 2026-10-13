package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.launchstar.StarRecord;
import dev.moui.galaxycraft.launchstar.StarStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A Launch Star, right-clicked: its code. Stars with the same code (on different planets) are linked. */
public final class StarCodeScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0;
    private final StarRecord star;
    private EditBox code;

    private StarCodeScreen(StarRecord star) {
        super(Component.translatable("screen.galaxycraft.star.title"));
        this.star = star;
    }

    static void open(StarRecord star) {
        Minecraft.getInstance().gui.setScreen(new StarCodeScreen(star));
    }

    @Override
    protected void init() {
        int w = 200, x = (width - w) / 2, y = height / 2 - 30;
        code = new EditBox(font, x, y + 14, w, 20, Component.translatable("screen.galaxycraft.star.code"));
        code.setMaxLength(StarStore.MAX_CODE);
        code.setValue(star.code());
        addRenderableWidget(code);
        setInitialFocus(code);
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.star.done"), b -> done())
                .bounds(x, y + 44, w, 20).build());
    }

    private void done() {
        LaunchStarClient.setCode(star.id(), code.getValue());
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 257 || event.key() == 335) { // Enter
            done();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = (width - 200) / 2, y = height / 2 - 30;
        g.centeredText(font, title.getString(), width / 2, y - 16, WHITE);
        g.text(font, Component.translatable("screen.galaxycraft.star.code").getString(), x, y, GRAY);
        g.text(font, Component.translatable("screen.galaxycraft.star.info").getString(), x, y + 70, GRAY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
