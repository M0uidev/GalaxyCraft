package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarpGateTest {
    static final double U = 80;
    @TempDir Path dir;

    @Test void aNewSystemPastTheCoreIsRefusedAndAVisitedOneIsFree() {
        var u = new Universe(21, U);
        Visited v = Visited.load(dir.resolve("v.json"));
        var far = u.around(UPos.ZERO, 12).stream().filter(s -> u.tier(s) >= 3).findFirst().orElseThrow();
        assertFalse(WarpGate.allowed(u, far, v, 0));
        assertTrue(WarpGate.allowed(u, far, v, 3));
        v.mark(far.sector());
        assertTrue(WarpGate.allowed(u, far, v, 0));
    }

    @Test void homeIsAlwaysAllowed() {
        var u = new Universe(21, U);
        var home = u.star(Universe.Sector.HOME).orElseThrow();
        assertTrue(WarpGate.allowed(u, home, Visited.load(dir.resolve("v.json")), 0));
    }

    @Test void whyNamesTheTierAndTheCoreNeeded() {
        var u = new Universe(21, U);
        var far = u.around(UPos.ZERO, 12).stream().filter(s -> u.tier(s) >= 3).findFirst().orElseThrow();
        String msg = WarpGate.why(u, far, 0);
        assertTrue(msg.contains("tier " + u.tier(far)), msg);
        assertTrue(msg.contains("Warp Core"), msg);
    }
}
