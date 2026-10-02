package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    static void near(Vector3d want, Vector3d got) {
        assertTrue(want.distance(got) < 1e-4, () -> "want " + want + " got " + got);
    }

    @Test void galaxyCameraSitsFromSteveAsTheGameCameraFromMario() {
        // Tests run at 100 units per block; this frame has galaxy +y as Minecraft +y.
        var frame = new GravityFrame(new Vector3d(0, 0, 0), new Vector3d(0, 100, 0), new Vector3d(0, -1, 0));
        Vector3d steve = new Vector3d(3, 101, 4); // interpolated, a little behind Mario
        Vector3d cam = CameraMath.galaxyCamera(frame, steve, new Vector3d(50, 0, 0), new Vector3d(50, 300, 800));
        near(new Vector3d(3, 104, 12), cam);
    }

    @Test void rotationMapsForwardAndUp() {
        Vector3d fwd = new Vector3d(1, -1, 0).normalize(), up = new Vector3d(1, 1, 0.2);
        Quaternionf q = CameraMath.rotation(fwd, up);
        Vector3f f = new Vector3f(0, 0, -1).rotate(q), u = new Vector3f(0, 1, 0).rotate(q);
        near(fwd, new Vector3d(f));
        assertEquals(0, u.dot(f), 1e-5);
        assertTrue(u.y > 0.6 && u.x > 0.6, "up keeps its side: " + u);
    }

    @Test void cameraOffsetInGalaxyUnits() {
        var frame = new GravityFrame(new Vector3d(0, 0, 0), new Vector3d(0, 100, 0), new Vector3d(0, -1, 0));
        near(new Vector3d(0, 162, -400),
                CameraMath.offsetGal(frame, new Vector3d(5, 101.62, 6), new Vector3d(5, 100, 10)));
    }

    @Test void yawPitchInvertDirection() {
        for (double[] yp : new double[][] {{0, 0}, {90, 10}, {-135, -40}, {170, 80}}) {
            Vector3d d = LookMath.direction(yp[0], yp[1]);
            near(d, LookMath.direction(LookMath.yaw(d), LookMath.pitch(d)));
        }
    }
}
