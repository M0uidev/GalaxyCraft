package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.FarSight.Level;
import org.junit.jupiter.api.Test;

class FarSightTest {
    @Test void anglesInDegrees() {
        assertEquals(90, FarSight.angle(1, 1), 1e-9);
        assertEquals(0.2, FarSight.angle(64, 36670), 0.005);
    }

    @Test void aPlanetIsADotUntilItLooksBig() {
        assertEquals(Level.DOT, FarSight.level(0.1, Level.DOT));
        assertEquals(Level.DOT, FarSight.level(0.25, Level.DOT), "between: keeps its level");
        assertEquals(Level.FAR, FarSight.level(0.25, Level.FAR), "between: keeps its level");
        assertEquals(Level.FAR, FarSight.level(0.31, Level.DOT));
        assertEquals(Level.DOT, FarSight.level(0.19, Level.FAR));
    }

    @Test void aStarOpensIntoItsPlanetsAsYouNearIt() {
        assertEquals(0, FarSight.opened(30_000));
        assertEquals(0, FarSight.opened(20_000), "its planets just known");
        assertEquals(0.5, FarSight.opened(17_000), 1e-9);
        assertEquals(1, FarSight.opened(14_000));
        assertEquals(1, FarSight.opened(100));
    }

    @Test void systemsLoadNearAndLeaveFarther() {
        assertTrue(FarSight.load(19_000, false));
        assertFalse(FarSight.load(21_000, false));
        assertTrue(FarSight.load(24_000, true), "kept until 25,000");
        assertFalse(FarSight.load(26_000, true));
    }
}
