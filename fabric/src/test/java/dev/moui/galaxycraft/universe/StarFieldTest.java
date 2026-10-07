package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class StarFieldTest {
    static final double U = 80;

    @Test void starsAreDirectionsFromMarioNearestFirstSkippingTheNearOnes() {
        Universe u = new Universe(11, U);
        UPos at = UPos.of(new Vector3d(1000 * U, 0, 0));
        List<Universe.Star> stars = u.around(at, 3);
        byte[] m = StarField.message(stars, at, 6000, U);
        ByteBuffer b = ByteBuffer.wrap(m);
        int n = b.getInt();
        long expected = stars.stream().filter(s -> s.center().minus(at).length() / U >= 6000).count();
        assertEquals(expected, n);
        assertEquals(4 + n * StarField.STAR_BYTES, m.length);
        for (int i = 0; i < n; i++) {
            Vector3d d = new Vector3d(b.getFloat(), b.getFloat(), b.getFloat());
            assertEquals(1, d.length(), 1e-5, "a unit direction");
            float px = b.getFloat();
            assertTrue(px >= StarField.MIN_PX && px <= StarField.MAX_PX);
            assertTrue((b.getInt() & 0xFF) >= 89, "visible");
        }
    }

    @Test void theSameSystemSeenFromAfarIsSmallerAndDimmer() {
        Universe.Star s = new Universe(1, U).around(UPos.ZERO, 2).stream().filter(x -> !x.home()).findFirst().orElseThrow();
        assertTrue(StarField.size(s, 8000) >= StarField.size(s, 60000));
        assertTrue((StarField.rgba(s, 8000) & 0xFF) >= (StarField.rgba(s, 60000) & 0xFF));
        assertEquals(StarField.rgba(s, 8000) >>> 8, StarField.rgba(s, 60000) >>> 8, "its color is its own");
    }
}
