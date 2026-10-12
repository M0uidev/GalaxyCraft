package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TierTest {
    @Test void ringsGrowWithDistance() {
        assertEquals(0, Tier.ring(0));
        assertEquals(0, Tier.ring(11_999));
        assertEquals(1, Tier.ring(12_000));
        assertEquals(2, Tier.ring(25_000));
        assertEquals(3, Tier.ring(50_000));
        assertEquals(4, Tier.ring(100_000));
        assertEquals(5, Tier.ring(110_000));
        assertEquals(5, Tier.ring(1e15));
    }

    @Test void homeIsAlwaysTierZero() {
        for (long seed = 0; seed < 200; seed++) assertEquals(0, Tier.of(0, seed, true));
    }

    @Test void noiseStaysWithinOneAndInRange() {
        for (long seed = 0; seed < 2000; seed++) {
            for (double d : new double[] {0, 15_000, 30_000, 60_000, 90_000, 500_000}) {
                int t = Tier.of(d, seed, false);
                assertTrue(t >= 0 && t <= Tier.MAX, "tier " + t);
                assertTrue(Math.abs(t - Tier.ring(d)) <= 1, "noise at " + d);
            }
        }
    }

    @Test void sameSeedSameTier() {
        assertEquals(Tier.of(33_000, 12345, false), Tier.of(33_000, 12345, false));
    }

    @Test void reachOfTheWarpCoreClampsAndGrows() {
        assertEquals(1, Tier.reach(0));
        assertEquals(2, Tier.reach(1));
        assertEquals(4, Tier.reach(2));
        assertEquals(5, Tier.reach(3));
        assertEquals(1, Tier.reach(-4));
        assertEquals(5, Tier.reach(99));
    }
}
