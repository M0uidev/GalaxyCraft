package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StarTypeTest {
    static final double U = 80;

    @Test void homeIsYellowAndTierZero() {
        for (long seed = 0; seed < 30; seed++) {
            var u = new Universe(seed, U);
            var home = u.star(Universe.Sector.HOME).orElseThrow();
            assertEquals(0, u.tier(home));
            assertEquals(StarType.YELLOW, u.starType(home));
        }
    }

    @Test void tierZeroNeverHasRedLavaOrBlue() {
        for (long seed = 0; seed < 5000; seed++) assertEquals(StarType.YELLOW, StarType.of(0, seed, false));
    }

    @Test void blueOnlyFromTierThree() {
        for (long seed = 0; seed < 5000; seed++) for (int t = 0; t < 3; t++) assertNotEquals(StarType.BLUE, StarType.of(t, seed, false));
        Set<StarType> seen = EnumSet.noneOf(StarType.class);
        for (long seed = 0; seed < 5000; seed++) seen.add(StarType.of(4, seed, false));
        assertEquals(EnumSet.allOf(StarType.class), seen);
    }

    @Test void theUniverseGivesTheSameTierAndStarForTheSameSeed() {
        List<Universe.Star> a = new Universe(9, U).around(UPos.ZERO, 4);
        var ua = new Universe(9, U);
        var ub = new Universe(9, U);
        for (var s : a) {
            assertEquals(ua.tier(s), ub.tier(s));
            assertEquals(ua.starType(s), ub.starType(s));
        }
    }

    @Test void tierDoesNotFallFarBelowItsRingFarFromHome() {
        var u = new Universe(5, U);
        for (var s : u.around(new UPos(40, 0, 0, 0, 0, 0), 6)) {
            double blocks = s.center().minus(UPos.ZERO).length() / U;
            assertTrue(u.tier(s) >= Tier.ring(blocks) - 1);
        }
    }

    @Test void colorsMatchTheMapContract() {
        assertEquals(0xFFFFE680, StarType.YELLOW.argb);
        assertEquals(0xFFFF6A4D, StarType.RED_DWARF.argb);
        assertEquals(0xFFFF9A2E, StarType.LAVA.argb);
        assertEquals(0xFF7FB2FF, StarType.BLUE.argb);
    }
}
