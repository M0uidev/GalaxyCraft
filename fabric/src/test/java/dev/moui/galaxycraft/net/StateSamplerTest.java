package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StateSamplerTest {
    @Test
    void dueOnTheFirstTickThenEveryNth() {
        StateSampler s = new StateSampler(2);
        assertTrue(s.due());
        assertFalse(s.due());
        assertTrue(s.due());
        assertFalse(s.due());
    }

    @Test
    void everyTickWhenOne() {
        StateSampler s = new StateSampler(1);
        for (int i = 0; i < 5; i++) assertTrue(s.due());
    }

    @Test
    void restartMakesTheNextOneDue() {
        StateSampler s = new StateSampler(5);
        assertTrue(s.due());
        assertFalse(s.due());
        s.restart();
        assertTrue(s.due());
    }

    @Test
    void flagsPackEachBit() {
        assertEquals(Protocol.F_SNEAK | Protocol.F_GROUND, StateSampler.flags(true, false, false, false, true, false));
        assertEquals(Protocol.F_SPRINT | Protocol.F_SWIM | Protocol.F_GLIDE | Protocol.F_SHIP,
                StateSampler.flags(false, true, true, true, false, true));
        assertEquals(0, StateSampler.flags(false, false, false, false, false, false));
    }

    @Test
    void speedIsClampedToWhatPeersAccept() {
        assertEquals(0f, StateSampler.speed(0, 2), 0f);
        assertEquals(10f, StateSampler.speed(1, 2), 1e-5f, "1 unit in 2 ticks (0.1 s)");
        assertEquals(Protocol.MAX_SPEED / 2, StateSampler.speed(1e12, 2), 0f, "a warp is not a speed");
        assertEquals(-Protocol.MAX_SPEED / 2, StateSampler.speed(-1e12, 2), 0f);
        assertEquals(0f, StateSampler.speed(Double.NaN, 2), 0f);
        assertEquals(0f, StateSampler.speed(1, 0), 0f, "no time passed");
    }
}
