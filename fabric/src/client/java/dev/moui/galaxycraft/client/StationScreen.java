package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A station's core, right-clicked: its name, its size, and Pack up. */
public final class StationScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0;
    private final PlanetSession session;
    private final Station station;
    private EditBox name;

    private StationScreen(PlanetSession session, Station station) {
        super(Component.translatable("screen.galaxycraft.station.title"));
        this.session = session;
        this.station = station;
    }

    /** The menu of the station a session streams (nothing for a planet). */
    static void open(PlanetSession session) {
        StationClient.of(session).ifPresent(s -> Minecraft.getInstance().gui.setScreen(new StationScreen(session, s)));
    }

    @Override
    protected void init() {
        int w = 200, x = (width - w) / 2, y = height / 2 - 50;
        name = new EditBox(font, x, y + 14, w, 20, Component.translatable("screen.galaxycraft.station.name"));
        name.setMaxLength(32);
        name.setValue(station.name);
        addRenderableWidget(name);
        int bw = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.station.rename"), b -> rename())
                .bounds(x, y + 60, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.station.pack"), b -> {
            rename();
            onClose();
            StationClient.pack(session);
        }).bounds(x + bw + 4, y + 60, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.station.done"), b -> {
            rename();
            onClose();
        }).bounds(x + 2 * (bw + 4), y + 60, bw, 20).build());
    }

    private void rename() {
        if (!name.getValue().strip().equals(station.name)) StationClient.rename(session, name.getValue());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = (width - 200) / 2, y = height / 2 - 50;
        g.centeredText(font, title.getString(), width / 2, y - 16, WHITE);
        g.text(font, Component.translatable("screen.galaxycraft.station.name").getString(), x, y, GRAY);
        var b = station.bounds;
        g.text(font, Component.translatable("screen.galaxycraft.station.info", b.spanX(), b.spanZ(), b.spanY(), station.blockCount()).getString(),
                x, y + 42, GRAY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
