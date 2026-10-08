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

    private static int count(byte[] m) {
        return ByteBuffer.wrap(m).getInt();
    }

    @Test void planetDotsJoinTheStarsAndAnOpenStarIsGone() {
        Universe u = new Universe(11, U);
        UPos at = UPos.ZERO;
        List<Universe.Star> stars = u.around(at, 2).stream().filter(s -> !s.home()).toList();
        List<StarField.Dot> dots = List.of(new StarField.Dot(UPos.of(new Vector3d(3000 * U, 0, 0)), 64, 0x6A9A5A, 1),
                new StarField.Dot(UPos.of(new Vector3d(0, 5000 * U, 0)), 32, 0xD8C88A, 1));
        byte[] closed = StarField.message(stars, s -> 0, dots, at, U);
        assertEquals(stars.size() + 2, count(closed));
        byte[] open = StarField.message(stars, s -> 1, dots, at, U);
        assertEquals(2, count(open), "fully open stars are left out: their planets show");
        ByteBuffer b = ByteBuffer.wrap(open);
        b.getInt();
        Vector3d d = new Vector3d(b.getFloat(), b.getFloat(), b.getFloat());
        assertEquals(1, d.x, 1e-6, "nearest first: the dot 3000 blocks off");
        b.getFloat();
        assertEquals(0x6A9A5A, b.getInt() >>> 8);
    }

    @Test void neverMoreThanTheGameHolds() {
        Universe u = new Universe(11, U);
        List<Universe.Star> stars = u.around(UPos.ZERO, 8);
        List<StarField.Dot> dots = new java.util.ArrayList<>();
        for (int i = 0; i < 5000; i++) dots.add(new StarField.Dot(UPos.of(new Vector3d((1000 + i) * U, i * U, 0)), 40, 0xFFFFFF, 1));
        byte[] m = StarField.message(stars, s -> 0, dots, UPos.ZERO, U);
        assertEquals(StarField.MAX, count(m));
        assertEquals(4 + StarField.MAX * StarField.STAR_BYTES, m.length);
    }

    @Test void aDotFadesWithItsSystemsOpening() {
        StarField.Dot half = new StarField.Dot(UPos.of(new Vector3d(3000 * U, 0, 0)), 64, 0xFFFFFF, 0.5);
        byte[] m = StarField.message(List.of(), s -> 0, List.of(half), UPos.ZERO, U);
        ByteBuffer b = ByteBuffer.wrap(m);
        b.getInt();
        for (int i = 0; i < 4; i++) b.getFloat();
        int alpha = b.getInt() & 0xFF;
        assertTrue(alpha > 0 && alpha < 200, "alpha " + alpha);
    }
}
