package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaylistTest {
    private static Track t(String id, boolean on) {
        return new Track(id, id, id, Source.SMG2, Mood.SPACE, List.of(), on);
    }

    private final List<Track> all = List.of(t("a", true), t("b", true), t("c", true));

    private static List<String> ids(List<Track> l) {
        return l.stream().map(Track::id).toList();
    }

    @Test void nextStartsAfterTheCurrentOneAndWraps() {
        assertEquals(List.of("c", "a", "b"), ids(Playlist.candidates(all, "b", 1)));
    }

    @Test void previousGoesBackwardsAndWraps() {
        assertEquals(List.of("a", "c", "b"), ids(Playlist.candidates(all, "b", -1)));
    }

    @Test void withNothingPlayingNextIsTheFirstAndPreviousTheLast() {
        assertEquals(List.of("a", "b", "c"), ids(Playlist.candidates(all, null, 1)));
        assertEquals(List.of("c", "b", "a"), ids(Playlist.candidates(all, null, -1)));
    }

    @Test void anUnknownCurrentIdIsLikeNothingPlaying() {
        assertEquals(List.of("a", "b", "c"), ids(Playlist.candidates(all, "gone", 1)));
    }

    @Test void disabledSongsAreLeftOut() {
        List<Track> l = List.of(t("a", true), t("b", false), t("c", true));
        assertEquals(List.of("c", "a"), ids(Playlist.candidates(l, "a", 1)));
    }

    @Test void aDisabledCurrentSongStillAnchorsThePosition() {
        List<Track> l = List.of(t("a", true), t("b", false), t("c", true));
        assertEquals(List.of("c", "a"), ids(Playlist.candidates(l, "b", 1)));
    }

    @Test void emptyGivesNothing() {
        assertEquals(List.of(), Playlist.candidates(List.of(), "a", 1));
    }
}
