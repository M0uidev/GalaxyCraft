package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShuffleBagTest {
    private static Track t(String id) {
        return new Track(id, id, id, Source.SMG2, Mood.SPACE, List.of(), true);
    }

    @Test void usesEveryTrackOncePerRoundAndNeverRepeatsBackToBack() {
        List<Track> pool = List.of(t("a"), t("b"), t("c"));
        ShuffleBag bag = new ShuffleBag();
        Random r = new Random(7);
        Map<String, Integer> count = new HashMap<>();
        String last = null;
        for (int i = 0; i < 6; i++) {
            String id = bag.next(pool, r).id();
            assertNotEquals(last, id);
            count.merge(id, 1, Integer::sum);
            last = id;
        }
        assertEquals(Map.of("a", 2, "b", 2, "c", 2), count);
    }

    @Test void aSingleTrackPoolRepeatsItself() {
        ShuffleBag bag = new ShuffleBag();
        assertEquals("a", bag.next(List.of(t("a")), new Random(1)).id());
        assertEquals("a", bag.next(List.of(t("a")), new Random(1)).id());
    }

    @Test void anEmptyPoolGivesNull() {
        assertNull(new ShuffleBag().next(List.of(), new Random(1)));
    }
}
