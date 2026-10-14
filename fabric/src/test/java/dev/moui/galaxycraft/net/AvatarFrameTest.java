package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class AvatarFrameTest {
    static final double U = 80;

    static void assertVec(Vector3d want, Vector3d got, String what) {
        assertEquals(want.x, got.x, 1e-9, what + " x");
        assertEquals(want.y, got.y, 1e-9, what + " y");
        assertEquals(want.z, got.z, 1e-9, what + " z");
    }

    @Test
    void onFlatGroundLookingSouthIsTheIdentityTimesScale() {
        // Minecraft: yaw 0 faces +Z, +X is the entity's left, up is +Y.
        Matrix4d m = AvatarFrame.matrix(new Vector3d(1, 2, 3), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1), U);
        assertVec(new Vector3d(U, 0, 0), m.transformDirection(new Vector3d(1, 0, 0)), "x axis");
        assertVec(new Vector3d(0, U, 0), m.transformDirection(new Vector3d(0, 1, 0)), "y axis");
        assertVec(new Vector3d(0, 0, U), m.transformDirection(new Vector3d(0, 0, 1)), "z axis");
        assertVec(new Vector3d(1, 2, 3), m.transformPosition(new Vector3d()), "origin");
    }

    @Test
    void lookingEastTurnsTheFrontToEast() {
        Matrix4d m = AvatarFrame.matrix(new Vector3d(), new Vector3d(0, 1, 0), new Vector3d(1, 0, 0), U);
        assertVec(new Vector3d(U, 0, 0), m.transformDirection(new Vector3d(0, 0, 1)), "front");
        assertVec(new Vector3d(0, 0, -U), m.transformDirection(new Vector3d(1, 0, 0)), "left of someone facing east is north (-Z)");
    }

    @Test
    void anySkyDirectionKeepsTheFrameOrthonormalAndUpOnUp() {
        Vector3d[] ups = {new Vector3d(1, 0, 0), new Vector3d(0, -1, 0), new Vector3d(1, 1, 1).normalize(), new Vector3d(0, 0, 1)};
        for (Vector3d up : ups) {
            Matrix4d m = AvatarFrame.matrix(new Vector3d(), up, new Vector3d(0.3, -0.2, 0.9).normalize(), 1);
            assertVec(up, m.transformDirection(new Vector3d(0, 1, 0)), "up");
            assertEquals(1.0, new Matrix4d(m).determinant3x3(), 1e-9, "right handed, unit");
        }
    }

    @Test
    void lookingStraightUpOrDownStillFindsAHeading() {
        for (Vector3d look : new Vector3d[] {new Vector3d(0, 1, 0), new Vector3d(0, -1, 0)}) {
            Matrix4d m = AvatarFrame.matrix(new Vector3d(), new Vector3d(0, 1, 0), look, 1);
            Vector3d front = m.transformDirection(new Vector3d(0, 0, 1));
            assertEquals(1.0, front.length(), 1e-9);
            assertEquals(0.0, front.dot(0, 1, 0), 1e-9, "the body stays level");
        }
    }

    @Test
    void pitchIsPositiveLookingDown() {
        Vector3d up = new Vector3d(0, 1, 0);
        assertEquals(0f, AvatarFrame.pitchDegrees(up, new Vector3d(0, 0, 1)), 1e-4f);
        assertEquals(90f, AvatarFrame.pitchDegrees(up, new Vector3d(0, -1, 0)), 1e-4f);
        assertEquals(-90f, AvatarFrame.pitchDegrees(up, new Vector3d(0, 1, 0)), 1e-4f);
        assertEquals(45f, AvatarFrame.pitchDegrees(up, new Vector3d(0, -1, 1).normalize()), 1e-3f);
    }
}
