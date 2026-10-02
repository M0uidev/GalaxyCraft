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
}
