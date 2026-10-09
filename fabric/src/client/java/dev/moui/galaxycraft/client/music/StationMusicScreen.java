package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import dev.moui.galaxycraft.music.Want;
import dev.moui.galaxycraft.voxel.Station;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A Station Core's Music: which music plays while you are on this station. */
public final class StationMusicScreen extends Screen {
    private static final int ROWS = 8;
    private record Entry(String label, Want want) {}
    private final Station station;
    private int page;

    private StationMusicScreen(Station station) {
        super(Component.translatable("screen.galaxycraft.station.music"));
        this.station = station;
    }

    public static void open(Station station) {
        Minecraft.getInstance().gui.setScreen(new StationMusicScreen(station));
    }

    private List<Entry> entries() {
        List<Entry> e = new ArrayList<>(List.of(new Entry("Space music", Want.SPACE), new Entry("Planet music", Want.PLANET),
                new Entry("Silence", Want.SILENCE)));
        for (Track t : MusicService.tracks()) if (t.source() == Source.SMG2) e.add(new Entry(t.title(), Want.track(t.id())));
        return e;
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 260), x = (width - w) / 2, y = 34;
        List<Entry> all = entries();
        int pages = Math.max(1, (all.size() + ROWS - 1) / ROWS);
        page = Math.clamp(page, 0, pages - 1);
        Want chosen = MusicService.stationMusic().get(station.id);
        for (int i = 0; i < ROWS && page * ROWS + i < all.size(); i++) {
            Entry en = all.get(page * ROWS + i);
            addRenderableWidget(Button.builder(Component.literal((en.want().equals(chosen) ? "> " : "") + en.label()), b -> {
                MusicService.stationPick(station.id, en.want());
                onClose();
            }).bounds(x, y + i * 22, w, 20).build());
        }
        int by = y + ROWS * 22 + 6;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(x, by, 40, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + w / 2 - 50, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(x + w - 40, by, 40, 20).build());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
