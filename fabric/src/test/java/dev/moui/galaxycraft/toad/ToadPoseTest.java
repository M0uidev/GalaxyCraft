package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ToadPoseTest {
    static float[] srt(float rz, float tx, float ty, float tz) {
        return new float[] {1, 1, 1, 0, 0, rz, tx, ty, tz};
    }

    @Test void aChildFollowsItsParent() {
        int[] parent = {-1, 0};
        Matrix4f[] w = ToadPose.worlds(parent, new float[][] {srt(0, 0, 10, 0), srt(0, 5, 0, 0)});
        Vector3f p = w[1].transformPosition(new Vector3f());
        assertEquals(5f, p.x, 1e-5);
        assertEquals(10f, p.y, 1e-5);
    }

    @Test void aParentsTurnCarriesTheChild() {
        int[] parent = {-1, 0};
        Matrix4f[] w = ToadPose.worlds(parent, new float[][] {srt((float) (Math.PI / 2), 0, 10, 0), srt(0, 5, 0, 0)});
        Vector3f p = w[1].transformPosition(new Vector3f());
        assertEquals(0f, p.x, 1e-4, "5 along x, turned a quarter about z, is 5 along y");
        assertEquals(15f, p.y, 1e-4);
    }

    @Test void aChildListedBeforeItsParentStillResolves() {
        int[] parent = {1, -1};
        Matrix4f[] w = ToadPose.worlds(parent, new float[][] {srt(0, 1, 0, 0), srt(0, 0, 7, 0)});
        Vector3f p = w[0].transformPosition(new Vector3f());
        assertEquals(1f, p.x, 1e-5);
        assertEquals(7f, p.y, 1e-5);
    }

    @Test void aLoopInTheParentsIsRejectedNotSpun() {
        assertThrows(IllegalArgumentException.class,
                () -> ToadPose.worlds(new int[] {1, 0}, new float[][] {srt(0, 0, 0, 0), srt(0, 0, 0, 0)}));
    }
}
