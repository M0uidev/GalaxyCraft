package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FollowTest {
    static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    @Test void targetIsMarioInMinecraftSpace() {
        var f = new GravityFrame(v(0, 820, 0), v(0, 100, 0), v(0, -1, 0));
        Vector3d got = Follow.target(f, v(0, 820 + 160, 0));
        Vector3d want = v(0, 100 + 160 * GravityFrame.SCALE, 0);
        assertTrue(want.distance(got) <= 1e-9, () -> "expected " + want + " got " + got);
    }
}
