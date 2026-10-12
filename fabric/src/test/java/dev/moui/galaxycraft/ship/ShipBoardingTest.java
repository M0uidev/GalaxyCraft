package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ShipBoardingTest {
    @Test
    void aRayThroughTheShipHitsIt() {
        assertTrue(ShipBoarding.hit(new Vector3d(0, 0, 0), new Vector3d(0, 0, 1), new double[] {0.5, 0, 4}, 6, 1.5));
    }

    @Test
    void behindTheEyeOrBeyondReachMisses() {
        assertFalse(ShipBoarding.hit(new Vector3d(0, 0, 0), new Vector3d(0, 0, 1), new double[] {0, 0, -4}, 6, 1.5));
        assertFalse(ShipBoarding.hit(new Vector3d(0, 0, 0), new Vector3d(0, 0, 1), new double[] {0, 0, 9}, 6, 1.5));
    }

    @Test
    void aRayPassingWideMisses() {
        assertFalse(ShipBoarding.hit(new Vector3d(0, 0, 0), new Vector3d(0, 0, 1), new double[] {3, 0, 4}, 6, 1.5));
    }

    @Test
    void theNearestShipInReachIsChosen() {
        var eye = new Vector3d(0, 0, 0);
        var dir = new Vector3d(0, 0, 1);
        int best = ShipBoarding.nearest(eye, dir, java.util.List.of(new double[] {0, 0, 5}, new double[] {0, 0, 3}, new double[] {9, 0, 3}), 6, 1.5);
        assertEquals(1, best);
        assertEquals(-1, ShipBoarding.nearest(eye, dir, java.util.List.of(), 6, 1.5));
    }
}
