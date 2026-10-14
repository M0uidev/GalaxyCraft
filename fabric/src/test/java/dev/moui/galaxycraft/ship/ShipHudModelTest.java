package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ShipHudModelTest {
    private static final ShipModules M = new ShipModules(1, 1, 1, 3);

    @Test
    void speedIsBlocksASecond() {
        ShipHudModel h = ShipHudModel.of(new Vector3d(0.3, 0, 0.4), false, M, 0, 4); // 0.5 a tick
        assertEquals(10.0, h.speed(), 1e-9);
        assertEquals(ShipThrust.topSpeed(ShipStats.of(M)) * 20, h.top(), 1e-9);
    }

    @Test
    void theGaugeIsFullAtTurboSpeedAndNeverPastIt() {
        double t = ShipThrust.turboSpeed(ShipStats.of(M));
        assertEquals(1.0, ShipHudModel.of(new Vector3d(0, 0, t), true, M, 0, 4).fraction(), 1e-9);
        assertEquals(1.0, ShipHudModel.of(new Vector3d(0, 0, t * 3), true, M, 0, 4).fraction(), 1e-9);
        assertEquals(0.0, ShipHudModel.of(new Vector3d(), false, M, 0, 4).fraction(), 1e-9);
    }

    @Test
    void theTopMarkIsWhereTurboBegins() {
        ShipHudModel h = ShipHudModel.of(new Vector3d(), false, M, 0, 4);
        assertEquals(1 / 1.9, h.topMark(), 1e-9);
    }

    @Test
    void boostingNeedsTheKeyAndMorethanTopSpeed() {
        double top = ShipThrust.topSpeed(ShipStats.of(M)), turbo = ShipThrust.turboSpeed(ShipStats.of(M));
        assertFalse(ShipHudModel.of(new Vector3d(0, 0, top), true, M, 0, 4).boosting()); // key down, not faster yet
        assertTrue(ShipHudModel.of(new Vector3d(0, 0, turbo), true, M, 0, 4).boosting());
        assertFalse(ShipHudModel.of(new Vector3d(0, 0, turbo), false, M, 0, 4).boosting()); // coasting after the key
    }

    @Test
    void anInvalidShipStillHasStats() {
        ShipHudModel h = ShipHudModel.of(new Vector3d(), false, new ShipModules(0, 0, 0, 0), 0, 0);
        assertTrue(h.modules().valid());
        assertTrue(h.top() > 0);
    }
}
