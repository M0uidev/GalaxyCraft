package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class LookMathTest {
    @Test void directionMatchesMinecraftConvention() {
        // yaw 0 looks to +Z, yaw 90 looks to -X, pitch 90 looks down.
        assertTrue(LookMath.direction(0, 0).distance(new Vector3d(0, 0, 1)) < 1e-9);
        assertTrue(LookMath.direction(90, 0).distance(new Vector3d(-1, 0, 0)) < 1e-9);
        assertTrue(LookMath.direction(0, 90).distance(new Vector3d(0, -1, 0)) < 1e-9);
    }

    @Test void aLookTurnedKeepsItsDirectionAndTheNearestYaw() {
        org.joml.Quaterniond q = new org.joml.Quaterniond().rotationX(Math.toRadians(20));
        double[] t = LookMath.turned(170, 10, q);
        org.joml.Vector3d want = q.transform(LookMath.direction(170, 10));
        assertTrue(LookMath.direction(t[0], t[1]).distance(want) < 1e-9);
        assertTrue(Math.abs(t[0] - 170) <= 180, "no spin through 180: " + t[0]);
        double[] wrap = LookMath.turned(179, 0, new org.joml.Quaterniond().rotationY(Math.toRadians(-3)));
        assertEquals(182, wrap[0], 1e-6, "past 180 it goes on, not back to -178");
    }
}
