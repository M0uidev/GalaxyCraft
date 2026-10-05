package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CameraDistanceTest {
    @Test void easesTowardTheTargetWithoutOvershoot() {
        double d = 4;
        double last = d;
        for (int i = 0; i < 60; i++) { // a second at 60 frames
            d = CameraDistance.approach(d, 10, 1 / 60.0);
            assertTrue(d >= last && d <= 10, "moves out, never past: " + d);
            last = d;
        }
        assertTrue(d > 8 && d < 10, "most of the way in a second: " + d);
        for (int i = 0; i < 240; i++) d = CameraDistance.approach(d, 10, 1 / 60.0);
        assertEquals(10, d, 0.01);
    }

    @Test void sameEaseWhateverTheFrameRate() {
        double a = 4, b = 4;
        for (int i = 0; i < 30; i++) a = CameraDistance.approach(a, 10, 1 / 30.0);
        for (int i = 0; i < 144; i++) b = CameraDistance.approach(b, 10, 1 / 144.0);
        assertEquals(a, b, 1e-6);
    }

    @Test void aLongHitchJumpsNoFurtherThanTheTarget() {
        assertEquals(10, CameraDistance.approach(4, 10, 30), 1e-9);
        assertEquals(4, CameraDistance.approach(4, 10, 0), 1e-9);
    }
}
