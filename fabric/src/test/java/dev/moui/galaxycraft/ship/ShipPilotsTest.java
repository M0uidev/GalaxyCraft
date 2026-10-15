package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipPilotsTest {
    final ShipPilots p = new ShipPilots();

    @Test void oneAtATime() {
        assertTrue(p.board("s", "a"));
        assertTrue(p.board("s", "a"));
        assertFalse(p.board("s", "b"));
        assertEquals("a", p.pilotOf("s"));
    }

    @Test void onlyThePilotLetsGo() {
        p.board("s", "a");
        assertFalse(p.release("s", "b"));
        assertTrue(p.release("s", "a"));
        assertTrue(p.board("s", "b"));
    }

    @Test void leavingTheGameFreesEveryShip() {
        p.board("s", "a");
        p.board("t", "a");
        p.board("u", "b");
        assertEquals(2, p.releaseAll("a").size());
        assertNull(p.pilotOf("s"));
        assertEquals("b", p.pilotOf("u"));
    }

    @Test void aCopyFollowsTheHost() {
        p.set("s", "a");
        assertEquals("a", p.pilotOf("s"));
        p.set("s", "");
        assertNull(p.pilotOf("s"));
    }
}
