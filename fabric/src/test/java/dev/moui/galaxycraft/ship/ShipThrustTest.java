package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ShipThrustTest {
    private static final ShipStats SLOW = ShipStats.of(new ShipModules(1, 1, 1, 1));
    private static final ShipStats FAST = ShipStats.of(new ShipModules(1, 3, 3, 1));

    @Test
    void holdingForwardSpeedsUpAlongTheLookUpToTheTopSpeed() {
        Vector3d v = new Vector3d();
        Vector3d look = new Vector3d(0, 0, 1);
        for (int i = 0; i < 400; i++) v = ShipThrust.step(v, look, true, SLOW);
        assertEquals(ShipThrust.topSpeed(SLOW), v.length(), 1e-6);
        assertTrue(v.z > 0 && Math.abs(v.x) < 1e-9);
    }

    @Test
    void aBetterEngineIsFaster() {
        assertTrue(ShipThrust.topSpeed(FAST) > ShipThrust.topSpeed(SLOW));
    }

    @Test
    void withNoThrustItDriftsAndSlowsDownButNeverStopsDead() {
        Vector3d v = ShipThrust.step(new Vector3d(1, 0, 0), new Vector3d(0, 0, 1), false, SLOW);
        assertTrue(v.length() < 1 && v.length() > 0.9);
    }

    @Test
    void aBetterRudderTurnsTheVelocityTowardTheLookFaster() {
        Vector3d look = new Vector3d(0, 0, 1);
        Vector3d a = ShipThrust.step(new Vector3d(1, 0, 0), look, false, SLOW);
        Vector3d b = ShipThrust.step(new Vector3d(1, 0, 0), look, false, FAST);
        assertTrue(b.z / b.length() > a.z / a.length());
    }

    @Test
    void standingStillWithoutThrustStaysStill() {
        assertEquals(0, ShipThrust.step(new Vector3d(), new Vector3d(0, 0, 1), false, SLOW).length(), 0);
    }
}
