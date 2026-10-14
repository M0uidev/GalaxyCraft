package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UseBookTest {
    final List<String> log = new ArrayList<>();

    int add(UseBook b, String name, int now) {
        return b.add(() -> log.add(name + " passed"), () -> log.add(name + " used"), now);
    }

    @Test
    void passedRunsThePlacementAndUsedDoesNot() {
        UseBook b = new UseBook();
        int a = add(b, "a", 0), c = add(b, "c", 0);
        assertTrue(b.settle(c, true));
        assertTrue(b.settle(a, false));
        assertEquals(List.of("c passed", "a used"), log);
        assertEquals(0, b.size());
    }

    @Test
    void anAnswerSettlesOnlyOnce() {
        UseBook b = new UseBook();
        int a = add(b, "a", 0);
        assertTrue(b.settle(a, true));
        assertFalse(b.settle(a, true));
        assertFalse(b.settle(999, false));
        assertEquals(List.of("a passed"), log);
    }

    @Test
    void unansweredClicksAreForgottenNotRun() {
        UseBook b = new UseBook();
        int old = add(b, "old", 0);
        int fresh = add(b, "fresh", UseBook.TIMEOUT_TICKS - 1);
        assertEquals(1, b.expire(UseBook.TIMEOUT_TICKS));
        assertFalse(b.settle(old, true));
        assertTrue(b.settle(fresh, true));
        assertEquals(List.of("fresh passed"), log);
    }

    @Test
    void tooManyWaitingClicksAreRefused() {
        UseBook b = new UseBook();
        for (int i = 0; i < UseBook.MAX_PENDING; i++) assertTrue(add(b, "x", 0) > 0);
        assertEquals(-1, add(b, "y", 0));
    }

    @Test
    void theHostRefusesNonsense() {
        assertTrue(UseCheck.valid(5, 100, 3, 1, 0.5f, 0f, 1f));
        assertFalse(UseCheck.valid(-1, 100, 3, 0, 0, 0, 0));
        assertFalse(UseCheck.valid(100, 100, 3, 0, 0, 0, 0));
        assertFalse(UseCheck.valid(5, 100, 6, 0, 0, 0, 0));
        assertFalse(UseCheck.valid(5, 100, 3, 2, 0, 0, 0));
        assertFalse(UseCheck.valid(5, 100, 3, 0, Float.NaN, 0, 0));
        assertFalse(UseCheck.valid(5, 100, 3, 0, 0, 9f, 0));
    }
}
