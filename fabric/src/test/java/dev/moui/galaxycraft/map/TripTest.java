package dev.moui.galaxycraft.map;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.universe.UPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class TripTest {
    static final UPos A = UPos.of(new Vector3d(0, 0, 0)), B = UPos.of(new Vector3d(1000, 0, 0));

    @Test void aCourseGoesFromAToBAtItsSpeed() {
        Course c = new Course(A, B, 100, 10);
        assertEquals(100, c.durationTicks());
        assertEquals(0, c.position(100).minus(A).length(), 1e-9);
        assertEquals(500, c.position(150).minus(A).x, 1e-9);
        assertEquals(0, c.position(200).minus(B).length(), 1e-9);
        assertEquals(0, c.position(9999).minus(B).length(), 1e-9);
        assertEquals(0, c.position(0).minus(A).length(), 1e-9); // before the start: still at A
        assertFalse(c.arrived(199));
        assertTrue(c.arrived(200));
    }

    @Test void aZeroLengthCourseHasArrivedAtOnceAndIsFinite() {
        Course c = new Course(A, A, 50, 10);
        assertEquals(0, c.durationTicks());
        assertTrue(c.arrived(50));
        UPos p = c.position(50);
        assertTrue(Double.isFinite(p.x()));
        assertEquals(0, p.minus(A).length(), 1e-9);
    }

    @Test void theOrbitStartsWhereTheCourseEnds() {
        UPos center = UPos.of(new Vector3d(2000, 0, 0));
        double r = 300;
        UPos to = center.plus(new Vector3d(r, 0, 0));
        Course c = new Course(A, to, 0, 10);
        Orbit o = new Orbit(center, r, 0, 0.01, c.durationTicks());
        Trip t = new Trip(c, o);
        assertEquals(0, t.positionAt(c.durationTicks() - 1).minus(c.position(c.durationTicks() - 1)).length(), 1e-9);
        assertEquals(0, t.positionAt(c.durationTicks()).minus(to).length(), 1e-6);
    }

    @Test void theOrbitKeepsItsRadius() {
        UPos center = UPos.of(new Vector3d(0, 5, 0));
        Orbit o = new Orbit(center, 250, 1.1, 0.02, 0);
        for (long t = 0; t < 2000; t += 137) assertEquals(250, o.position(t).minus(center).length(), 1e-6);
    }
}
