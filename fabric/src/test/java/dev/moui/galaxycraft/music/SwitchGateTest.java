package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SwitchGateTest {
    @Test void theFirstWantStartsAtOnce() {
        SwitchGate<String> g = new SwitchGate<>();
        assertEquals("space", g.update(0, "space", 5, 120));
        assertEquals("space", g.current());
    }

    @Test void aChangeWaitsForTheDwellTime() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 0);
        assertNull(g.update(10, "planet", 5, 0));
        assertNull(g.update(14.9, "planet", 5, 0));
        assertEquals("planet", g.update(15, "planet", 5, 0));
    }

    @Test void theCooldownHoldsASwitchAndThePendingOneIsAppliedWhenItEnds() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 120);
        assertNull(g.update(200, "planet", 5, 120));
        assertEquals("planet", g.update(205, "planet", 5, 120));
        // left the planet 55 s later: inside the cooldown, nothing changes...
        assertNull(g.update(260, "space", 5, 120));
        assertNull(g.update(324, "space", 5, 120));
        // ...and when the cooldown ends, still in space: it changes
        assertEquals("space", g.update(325, "space", 5, 120));
    }

    @Test void aFlipBackInsideTheCooldownCancelsThePendingChange() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 120);
        assertEquals("planet", g.update(200, "planet", 0, 120));
        assertNull(g.update(250, "space", 0, 120));
        assertNull(g.update(260, "planet", 0, 120));
        assertNull(g.update(400, "planet", 0, 120));
        assertEquals("planet", g.current());
    }

    @Test void zeroDwellAndZeroCooldownSwitchAtOnce() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 0);
        assertEquals("planet", g.update(0.1, "planet", 0, 0));
        assertEquals("space", g.update(0.2, "space", 0, 0));
    }

    @Test void aPlayersPickStartsTheCooldown() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 0, 60);
        g.force("planet", 100);
        assertEquals("planet", g.current());
        assertNull(g.update(130, "space", 0, 60));
        assertEquals("space", g.update(160, "space", 0, 60));
    }

    @Test void resetStartsOver() {
        SwitchGate<String> g = new SwitchGate<>();
        g.update(0, "space", 5, 120);
        g.reset();
        assertNull(g.current());
        assertEquals("planet", g.update(1, "planet", 5, 120));
    }

    @Test void aNullWantChangesNothing() {
        SwitchGate<String> g = new SwitchGate<>();
        assertNull(g.update(0, null, 5, 5));
        assertNull(g.current());
    }

    @Test void tellsHowLongAgoTheLastSwitchWas() {
        SwitchGate<String> g = new SwitchGate<>();
        assertEquals(Double.POSITIVE_INFINITY, g.secondsSinceSwitch(5), "nothing switched yet");
        g.update(0, "space", 0, 0); // the first want starts the music: not a switch
        assertEquals(Double.POSITIVE_INFINITY, g.secondsSinceSwitch(5));
        g.update(10, "planet", 0, 0);
        assertEquals(7, g.secondsSinceSwitch(17), 1e-9);
        g.force("space", 20);
        assertEquals(1, g.secondsSinceSwitch(21), 1e-9);
    }
}
