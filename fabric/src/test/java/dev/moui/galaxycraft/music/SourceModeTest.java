package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceModeTest {
    private static Track t(String id, Source s, Mood m, boolean on) {
        return new Track(id, id, id + ".x", s, m, List.of(), on);
    }

    private final List<Track> all = List.of(
            t("sp1", Source.SMG2, Mood.SPACE, true), t("sp2", Source.SMG2, Mood.SPACE, false),
            t("pl1", Source.SMG2, Mood.PLANET, true), t("none", Source.SMG2, null, true),
            t("mc1", Source.MINECRAFT, Mood.PLANET, true), t("mc2", Source.MINECRAFT, Mood.PLANET, true));

    private static List<String> ids(List<Track> l) {
        return l.stream().map(Track::id).sorted().toList();
    }

    @Test void bothMixesMinecraftIntoPlanets() {
        assertEquals(List.of("mc1", "mc2", "pl1"), ids(SourceMode.BOTH.pool(all, Want.PLANET)));
        assertEquals(List.of("sp1"), ids(SourceMode.BOTH.pool(all, Want.SPACE)));
    }

    @Test void smg2OnlyHasNoMinecraft() {
        assertEquals(List.of("pl1"), ids(SourceMode.SMG2.pool(all, Want.PLANET)));
    }

    @Test void minecraftOnlyPlaysEverywhere() {
        assertEquals(List.of("mc1", "mc2"), ids(SourceMode.MINECRAFT.pool(all, Want.SPACE)));
        assertEquals(List.of("mc1", "mc2"), ids(SourceMode.MINECRAFT.pool(all, Want.PLANET)));
    }

    @Test void randomTakesAnyClassifiedEnabledTrackOfEitherGame() {
        assertEquals(List.of("mc1", "mc2", "pl1", "sp1"), ids(SourceMode.RANDOM.pool(all, Want.SPACE)));
    }

    @Test void minecraftAndRandomIgnoreTheMoodWhenApplied() {
        assertEquals(Want.PLANET, SourceMode.RANDOM.apply(Want.SPACE));
        assertEquals(Want.PLANET, SourceMode.MINECRAFT.apply(Want.SPACE));
        assertEquals(Want.SPACE, SourceMode.BOTH.apply(Want.SPACE));
        assertEquals(Want.SPACE, SourceMode.SMG2.apply(Want.SPACE));
        assertEquals(Want.SILENCE, SourceMode.RANDOM.apply(Want.SILENCE));
        assertEquals(Want.track("a"), SourceMode.MINECRAFT.apply(Want.track("a")));
    }

    @Test void anEmptyPoolIsEmptyNotAnError() {
        assertEquals(List.of(), SourceMode.SMG2.pool(List.of(), Want.SPACE));
    }
}
