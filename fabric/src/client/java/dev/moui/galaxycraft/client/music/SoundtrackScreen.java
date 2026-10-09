package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The music player: the library, a mood for each song, and the transport. */
public final class SoundtrackScreen extends Screen {
    private static final int ROWS = 8, WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, GREEN = 0xFF55FF55;
    private enum Filter { ALL, SPACE, PLANET, MINECRAFT, UNSORTED }
    private Filter filter = Filter.ALL;
    private int page;

    private SoundtrackScreen() {
        super(Component.translatable("screen.galaxycraft.music.title"));
    }

    public static void open() {
        Minecraft.getInstance().gui.setScreen(new SoundtrackScreen());
    }

    private List<Track> shown() {
        return MusicService.tracks().stream().filter(t -> switch (filter) {
            case ALL -> true;
            case SPACE -> t.mood() == Mood.SPACE && t.source() == Source.SMG2;
            case PLANET -> t.mood() == Mood.PLANET && t.source() == Source.SMG2;
            case MINECRAFT -> t.source() == Source.MINECRAFT;
            case UNSORTED -> t.mood() == null;
        }).toList();
    }

    @Override
    protected void init() {
        int w = Math.min(width - 20, 360), x = (width - w) / 2, y = 34;
        List<Track> list = shown();
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < ROWS && page * ROWS + i < list.size(); i++) {
            Track t = list.get(page * ROWS + i);
            int ry = y + i * 22;
            addRenderableWidget(Button.builder(Component.literal((MusicService.playing() == t ? "> " : "") + t.title()),
                    b -> MusicService.playNow(t)).bounds(x, ry, w - 110, 20).build());
            addRenderableWidget(Button.builder(Component.literal(t.mood() == null ? "-" : t.mood() == Mood.SPACE ? "Space" : "Planet"),
                    b -> {
                        MusicService.setMood(t, t.mood() == null ? Mood.SPACE : t.mood() == Mood.SPACE ? Mood.PLANET : null);
                        rebuildWidgets();
                    }).bounds(x + w - 106, ry, 56, 20).build());
            addRenderableWidget(Button.builder(Component.literal(t.enabled() ? "ON" : "OFF"), b -> {
                MusicService.setEnabled(t, !t.enabled());
                rebuildWidgets();
            }).bounds(x + w - 46, ry, 46, 20).build());
        }
        int by = y + ROWS * 22 + 6, bw = (w - 12) / 4;
        addRenderableWidget(Button.builder(Component.literal("< Prev"), b -> MusicService.previous()).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(MusicService.paused() ? "Play" : "Pause"), b -> {
            MusicService.pause(!MusicService.paused());
            rebuildWidgets();
        }).bounds(x + bw + 4, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Next >"), b -> MusicService.next()).bounds(x + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.galaxycraft.music.auto"), b -> MusicService.auto())
                .bounds(x + 3 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Filter: " + filter.name()), b -> {
            filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
            page = 0;
            rebuildWidgets();
        }).bounds(x, by + 24, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); })
                .bounds(x + w - 90, by + 24, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; rebuildWidgets(); })
                .bounds(x + w - 20, by + 24, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x + w / 2 - 50, by + 50, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title.getString(), width / 2, 14, WHITE);
        Track now = MusicService.playing();
        String line = now == null ? Component.translatable("screen.galaxycraft.music.silent").getString()
                : now.title() + (MusicService.pinned() ? "  (picked)" : "  (automatic)");
        g.centeredText(font, line, width / 2, 24, now == null ? GRAY : GREEN);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
