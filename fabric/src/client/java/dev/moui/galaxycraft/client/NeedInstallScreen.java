package dev.moui.galaxycraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shown when Minecraft was started from Mojang's launcher but the game is not installed (no play.json). */
final class NeedInstallScreen extends Screen {
    NeedInstallScreen() {
        super(Component.literal("Super Minecraft Galaxy"));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Quit"), b -> minecraft.stop())
                .bounds(width / 2 - 50, height / 2 + 20, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, "Open the Super Minecraft Galaxy launcher and press INSTALL", width / 2, height / 2 - 20, 0xFFFFFFFF);
        g.centeredText(font, "Then play from here again.", width / 2, height / 2 - 6, 0xFFA0A0A0);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
