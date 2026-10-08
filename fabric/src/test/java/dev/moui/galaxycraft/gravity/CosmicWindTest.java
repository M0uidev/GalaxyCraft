package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CosmicWindTest {
    static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    /** Two planets: one at the origin reaching 100 blocks, one at x = 1000 reaching 50. */
    static final List<GravityBody> PLANETS = List.of(new GravityBody.Sphere(v(0, 0, 0), 100),
            new GravityBody.Sphere(v(1000, 0, 0), 50));

    @Test void stillInsideAFieldAndJustPastIt() {
        assertEquals(0, CosmicWind.push(false, v(0, 50, 0), v(0, 3, 0), PLANETS).length());
        assertEquals(0, CosmicWind.push(false, v(0, 100 + CosmicWind.FREE, 0), v(0, 3, 0), PLANETS).length());
    }

    @Test void noPlanetsNoWind() {
        assertEquals(0, CosmicWind.push(false, v(0, 1e6, 0), v(0, 1, 0), List.of()).length());
    }

    @Test void pullsTowardTheNearestPlanet() {
        Vector3d dv = CosmicWind.push(false, v(1000, 400, 0), new Vector3d(), PLANETS);
        assertTrue(dv.length() > 0);
        assertEquals(-1, dv.normalize().y, 1e-9, "toward the planet at x = 1000, straight down");
    }

    @Test void growsSmoothlyAndCaps() {
        double last = 0;
        for (double past = CosmicWind.FREE; past <= CosmicWind.FREE + 2 * CosmicWind.RAMP; past += 10) {
            double s = CosmicWind.push(false, v(0, 100 + past, 0), new Vector3d(), PLANETS).length();
            assertTrue(s >= last - 1e-12, "never weaker farther out: " + past);
            assertTrue(s <= CosmicWind.PULL + 1e-12);
            last = s;
        }
        assertEquals(CosmicWind.PULL, last, 1e-12);
    }

    @Test void slowsGoingOutButNeverPushesOut() {
        Vector3d far = v(0, 100 + CosmicWind.FREE + CosmicWind.RAMP, 0);
        Vector3d out = CosmicWind.push(false, far, v(0, 2, 0), PLANETS);
        assertEquals(-(CosmicWind.PULL + 2 * CosmicWind.DRAG), out.y, 1e-9);
        Vector3d in = CosmicWind.push(false, far, v(0, -0.5, 0), PLANETS);
        assertEquals(-CosmicWind.PULL, in.y, 1e-9, "coming back: only the pull");
        Vector3d side = CosmicWind.push(false, far, v(5, 0, 0), PLANETS);
        assertEquals(0, side.x, 1e-9, "sideways speed is left alone");
    }

    @Test void anyBodyShapeCounts() {
        // A flat station: a box of gravity, 10 blocks around a slab at y = 0.
        GravityBody slab = new GravityBody() {
            public Vector3d center() {
                return v(0, 0, 0);
            }

            public double outside(Vector3d p) {
                return Math.abs(p.y) - 10;
            }
        };
        assertEquals(0, CosmicWind.push(false, v(0, 150, 0), new Vector3d(), List.of(slab)).length());
        assertTrue(CosmicWind.push(false, v(0, 500, 0), new Vector3d(), List.of(slab)).y < 0);
    }

    @Test void strandedDriftsBackFromAnywhereOutside() {
        // No elytra: even in the calm, a gentle pull home; never inside a field.
        Vector3d dv = CosmicWind.push(true, v(0, 150, 0), new Vector3d(), PLANETS);
        assertEquals(-CosmicWind.STRANDED_PULL, dv.y, 1e-12);
        assertEquals(0, CosmicWind.push(true, v(0, 50, 0), new Vector3d(), PLANETS).length());
        Vector3d far = CosmicWind.push(true, v(0, 100 + CosmicWind.FREE + CosmicWind.RAMP, 0), new Vector3d(), PLANETS);
        assertEquals(-CosmicWind.PULL, far.y, 1e-12, "the wind is the stronger far out");
    }

    @Test void bringsBackNoFasterThanMaxIn() {
        Vector3d far = v(0, 100 + CosmicWind.FREE + CosmicWind.RAMP, 0);
        assertEquals(0, CosmicWind.push(false, far, v(0, -CosmicWind.MAX_IN, 0), PLANETS).length(), 1e-12);
        assertEquals(-0.01, CosmicWind.push(false, far, v(0, -(CosmicWind.MAX_IN - 0.01), 0), PLANETS).y, 1e-9);
    }

    static final GravityBody.Box BOX = new GravityBody.Box(v(0, 0, 0), new Quaterniond(), v(-5, -1, -5), v(5, 25, 5));

    @Test void aBoxPullsOverItsTopAndNotBelow() {
        assertTrue(BOX.outside(v(0, 10, 0)) <= 0);
        assertEquals(3, BOX.outside(v(0, -4, 0)), 1e-9);
        assertEquals(5, BOX.outside(v(10, 10, 0)), 1e-9);
        assertEquals(Math.sqrt(9 + 25), BOX.outside(v(10, -4, 0)), 1e-9);
    }

    @Test void aTurnedBoxIsMeasuredInItsOwnAxes() {
        GravityBody.Box b = new GravityBody.Box(v(100, 0, 0), new Quaterniond().rotateZ(Math.PI / 2), v(-5, -1, -5), v(5, 25, 5));
        assertTrue(b.outside(v(90, 0, 0)) <= 0); // its up is -x now
        assertEquals(3, b.outside(v(104, 0, 0)), 1e-9);
    }

    @Test void theWindPullsTowardABox() {
        Vector3d dv = CosmicWind.push(true, v(500, 0, 0), new Vector3d(), List.of(BOX));
        assertTrue(dv.x < 0);
    }
}
