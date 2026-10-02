package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    static void near(Vector3d want, Vector3d got) {
        assertTrue(want.distance(got) < 1e-4, () -> "want " + want + " got " + got);
    }

    @Test void cameraOffsetInGalaxyUnits() {
        var frame = new GravityFrame(new Vector3d(0, 0, 0), new Vector3d(0, 100, 0), new Vector3d(0, -1, 0));
        near(new Vector3d(0, 162, -400),
                CameraMath.offsetGal(frame, new Vector3d(5, 101.62, 6), new Vector3d(5, 100, 10), 1));
    }

    @Test void yawPitchInvertDirection() {
        for (double[] yp : new double[][] {{0, 0}, {90, 10}, {-135, -40}, {170, 80}}) {
            Vector3d d = LookMath.direction(yp[0], yp[1]);
            near(d, LookMath.direction(LookMath.yaw(d), LookMath.pitch(d)));
        }
    }
}
