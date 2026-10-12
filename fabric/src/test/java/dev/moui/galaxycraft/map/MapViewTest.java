package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class MapViewTest {
    static MapView view() {
        MapView v = new MapView();
        v.width = 400;
        v.height = 300;
        v.focusOn(new Vector3d(), 100);
        return v;
    }

    @Test void theFocusIsAtTheCenter() {
        var s = view().project(new Vector3d()).orElseThrow();
        assertEquals(200, s.x(), 1e-6);
        assertEquals(150, s.y(), 1e-6);
    }

    @Test void aPointToTheCameraRightIsRightOnScreen() {
        MapView v = view();
        Vector3d f = v.focus().sub(v.eye()).normalize();
        Vector3d right = new Vector3d(f).cross(0, 1, 0).normalize();
        var s = v.project(new Vector3d(right).mul(10)).orElseThrow();
        assertTrue(s.x() > 200);
        assertEquals(150, s.y(), 1e-6);
    }

    @Test void aPointBehindTheCameraIsNotDrawn() {
        MapView v = view();
        Vector3d eye = v.eye();
        Vector3d behind = new Vector3d(eye).add(new Vector3d(eye).sub(v.focus()).normalize().mul(50));
        assertTrue(v.project(behind).isEmpty());
    }

    @Test void pickTakesTheNearestWithinTheRadius() {
        MapView v = view();
        var s = v.project(new Vector3d(10, 0, 0)).orElseThrow();
        List<Vector3d> pts = List.of(new Vector3d(-30, 0, 0), new Vector3d(10, 0, 0), new Vector3d(0, 40, 0));
        assertEquals(1, v.pick(pts, s.x() + 3, s.y() - 2, 8));
        assertEquals(-1, v.pick(pts, 5, 5, 8));
    }

    @Test void zoomIsClamped() {
        MapView v = view();
        for (int i = 0; i < 200; i++) v.zoom(0.5);
        double near = v.distance();
        for (int i = 0; i < 200; i++) v.zoom(2);
        assertTrue(near > 0);
        assertTrue(v.distance() > near);
        assertTrue(Double.isFinite(v.distance()));
    }

    @Test void pitchNeverFlipsTheCamera() {
        MapView v = view();
        v.orbit(0, 1e6);
        assertTrue(v.eye().y > 0 && v.eye().y < 100.0000001);
        var s = v.project(new Vector3d()).orElseThrow();
        assertEquals(200, s.x(), 1e-6);
        assertEquals(150, s.y(), 1e-6);
    }
}
