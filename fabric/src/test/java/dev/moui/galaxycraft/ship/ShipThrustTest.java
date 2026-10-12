package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.ShipThrust.Controls;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ShipThrustTest {
    private static final ShipStats SLOW = ShipStats.of(new ShipModules(1, 1, 1, 1));
    private static final ShipStats FAST = ShipStats.of(new ShipModules(1, 3, 3, 1));
    private static final Vector3d LOOK = new Vector3d(0, 0, 1);
    private static final Controls W = new Controls(true, false, false, false, false, false, false);

    private static Vector3d run(Controls c, Vector3d look, ShipStats s, int ticks) {
        Vector3d v = new Vector3d();
        for (int i = 0; i < ticks; i++) v = ShipThrust.step(v, look, c, s);
        return v;
    }

    @Test
    void holdingForwardReachesTheTopSpeedAlongTheLook() {
        Vector3d v = run(W, LOOK, SLOW, 300);
        assertEquals(ShipThrust.topSpeed(SLOW), v.length(), 1e-3);
        assertTrue(v.z > 0 && Math.abs(v.x) < 1e-9 && Math.abs(v.y) < 1e-9);
    }

    @Test
    void aBetterEngineIsFasterAndABetterRudderEasesQuicker() {
        assertTrue(ShipThrust.topSpeed(FAST) > ShipThrust.topSpeed(SLOW));
        assertTrue(run(W, LOOK, FAST, 3).length() / ShipThrust.topSpeed(FAST)
                > run(W, LOOK, SLOW, 3).length() / ShipThrust.topSpeed(SLOW));
    }

    @Test
    void letGoItComesToAHaltRatherThanDrifting() {
        Vector3d v = run(W, LOOK, SLOW, 100);
        for (int i = 0; i < 100; i++) v = ShipThrust.step(v, LOOK, Controls.NONE, SLOW);
        assertTrue(v.length() < 1e-3);
    }

    @Test
    void backGoesTheOtherWayAndSidesStrafe() {
        assertTrue(run(new Controls(false, true, false, false, false, false, false), LOOK, SLOW, 100).z < 0);
        Vector3d right = run(new Controls(false, false, false, true, false, false, false), LOOK, SLOW, 100);
        Vector3d left = run(new Controls(false, false, true, false, false, false, false), LOOK, SLOW, 100);
        assertTrue(right.x * left.x < 0 && Math.abs(right.z) < 1e-9, "D and A go to opposite sides, not along the look");
    }

    @Test
    void spaceClimbsAndCtrlDescendsWhateverTheLook() {
        Vector3d up = run(new Controls(false, false, false, false, true, false, false), new Vector3d(1, 0, 0), SLOW, 100);
        Vector3d down = run(new Controls(false, false, false, false, false, true, false), new Vector3d(1, 0, 0), SLOW, 100);
        assertTrue(up.y > 0 && down.y < 0 && Math.abs(up.x) < 1e-9);
    }

    @Test
    void lookingStraightUpStillSteers() {
        Vector3d v = run(W, new Vector3d(0, 1, 0), SLOW, 100);
        assertTrue(v.y > 0 && Double.isFinite(v.x));
        assertTrue(Double.isFinite(run(new Controls(false, false, false, true, false, false, false), new Vector3d(0, 1, 0), SLOW, 10).x));
    }

    @Test
    void theTurboRaisesTheTopSpeedAndAccelerationOnlyWhileHeld() {
        Controls t = new Controls(true, false, false, false, false, false, true);
        Vector3d v = run(t, LOOK, SLOW, 300);
        assertEquals(ShipThrust.turboSpeed(SLOW), v.length(), 1e-3);
        assertTrue(ShipThrust.turboSpeed(SLOW) > 1.8 * ShipThrust.topSpeed(SLOW));
        assertTrue(run(t, LOOK, SLOW, 3).length() > run(W, LOOK, SLOW, 3).length());
        // Let go of the turbo (W still held): it eases back down to the normal top speed.
        for (int i = 0; i < 300; i++) v = ShipThrust.step(v, LOOK, W, SLOW);
        assertEquals(ShipThrust.topSpeed(SLOW), v.length(), 1e-3);
    }

    @Test
    void bothDirectionsAtOnceCancel() {
        assertTrue(run(new Controls(true, true, false, false, false, false, false), LOOK, SLOW, 50).length() < 1e-9);
    }

    @Test
    void diagonalIsNoFasterThanStraight() {
        Vector3d v = run(new Controls(true, false, false, true, false, false, false), LOOK, SLOW, 300);
        assertTrue(v.length() <= ShipThrust.topSpeed(SLOW) + 1e-6);
    }
}
