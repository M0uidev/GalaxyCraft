package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import org.junit.jupiter.api.Test;

class SystemInfoTest {
    static final double U = 80;

    @Test void homeIsTierZeroAndYellow() {
        var home = new Universe(5, U).star(Universe.Sector.HOME).orElseThrow();
        assertEquals(new SystemInfo(0, StarType.YELLOW), SystemInfo.of(home, U));
    }

    @Test void ringsGrowWithDistance() {
        assertEquals(0, SystemInfo.tierAt(0));
        assertEquals(0, SystemInfo.tierAt(12_000));
        assertEquals(1, SystemInfo.tierAt(12_001));
        assertEquals(2, SystemInfo.tierAt(20_001));
        assertEquals(3, SystemInfo.tierAt(40_001));
        assertEquals(4, SystemInfo.tierAt(70_001));
        assertEquals(5, SystemInfo.tierAt(110_001));
        assertEquals(5, SystemInfo.tierAt(1e12));
    }

    @Test void aSystemsTierFollowsItsDistance() {
        var u = new Universe(9, U);
        for (var s : u.around(UPos.ZERO, 6)) {
            double blocks = s.center().minus(UPos.ZERO).length() / U;
            assertEquals(SystemInfo.tierAt(blocks), SystemInfo.of(s, U).tier());
        }
    }

    @Test void starsVaryAndAreStable() {
        var u = new Universe(9, U);
        var kinds = new java.util.HashSet<StarType>();
        for (var s : u.around(UPos.ZERO, 6)) {
            assertEquals(SystemInfo.of(s, U).star(), SystemInfo.of(s, U).star());
            kinds.add(SystemInfo.of(s, U).star());
        }
        assertTrue(kinds.size() > 1);
    }
}
