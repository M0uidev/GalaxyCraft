package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class IntroCameraTest {
    static final Vector3d UP = new Vector3d(0, 1, 0), OFFSET = new Vector3d(0, 130, 0), LOOK = new Vector3d(1, 0, 0),
            CAM_UP = new Vector3d(0, 1, 0);
    static final double FAR = 20000;

    @Test void itStartsHighAboveLookingDownWithWhereThePlayerFacesOnTop() {
        IntroCamera.Pose p = IntroCamera.at(0, UP, OFFSET, LOOK, CAM_UP, FAR);
        assertTrue(p.offset().distance(new Vector3d(0, 130 + FAR, 0)) < 1e-6);
        assertTrue(p.look().distance(new Vector3d(0, -1, 0)) < 1e-9);
        assertTrue(p.up().distance(LOOK) < 1e-9, "the top of the view is where the player will face");
    }

    @Test void itEndsAtThePlayersOwnView() {
        IntroCamera.Pose p = IntroCamera.at(1, UP, OFFSET, LOOK, CAM_UP, FAR);
        assertTrue(p.offset().distance(OFFSET) < 1e-6);
        assertTrue(p.look().distance(LOOK) < 1e-9);
        assertTrue(p.up().distance(CAM_UP) < 1e-9);
    }

    @Test void itComesDownAllTheWayWithoutGoingBack() {
        double last = Double.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            IntroCamera.Pose p = IntroCamera.at(i / 100.0, UP, OFFSET, LOOK, CAM_UP, FAR);
            double h = p.offset().dot(UP);
            assertTrue(h <= last + 1e-9, "height at " + i);
            last = h;
            assertEquals(1, p.look().length(), 1e-9);
            assertEquals(0, p.look().dot(p.up()), 1e-9, "look and up stay square");
        }
    }

    @Test void lookingStraightUpOrDownStillHasATop() {
        IntroCamera.Pose p = IntroCamera.at(0, UP, OFFSET, new Vector3d(0, -1, 0), new Vector3d(0, 0, 1), FAR);
        assertEquals(1, p.up().length(), 1e-9);
        assertEquals(0, p.look().dot(p.up()), 1e-9);
    }
}
