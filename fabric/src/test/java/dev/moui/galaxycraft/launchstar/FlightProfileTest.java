package dev.moui.galaxycraft.launchstar;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FlightProfileTest {
    @Test void startsAtZeroAndEndsAtTheLength() {
        for (double len : new double[] {50, 2000, 9000, 1e6}) {
            FlightProfile f = FlightProfile.of(len);
            assertEquals(0, f.arcAt(0), 1e-9);
            assertEquals(len, f.arcAt(f.ticks()), 1e-6 * len);
            assertEquals(len, f.arcAt(f.ticks() + 50), 1e-6 * len);
        }
    }

    @Test void neverGoesBackwardsAndEasesInAndOut() {
        FlightProfile f = FlightProfile.of(5000);
        double prev = 0, firstStep = f.arcAt(1), lastStep = 5000 - f.arcAt(f.ticks() - 1), cruise = f.arcAt(f.ticks() / 2 + 1) - f.arcAt(f.ticks() / 2);
        for (int t = 1; t <= f.ticks(); t++) {
            assertTrue(f.arcAt(t) >= prev);
            prev = f.arcAt(t);
        }
        assertTrue(firstStep < cruise / 2 && lastStep < cruise / 2);
    }

    @Test void tripTimeIsClamped() {
        assertEquals(FlightProfile.MIN_TICKS, FlightProfile.of(10).ticks());
        assertEquals(FlightProfile.MAX_TICKS, FlightProfile.of(1e7).ticks());
        int mid = FlightProfile.of(3200).ticks();
        assertTrue(mid > FlightProfile.MIN_TICKS && mid < FlightProfile.MAX_TICKS);
    }
}
