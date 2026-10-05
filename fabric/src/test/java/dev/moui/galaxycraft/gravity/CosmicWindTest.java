package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
        assertEquals(0, CosmicWind.push(v(0, 50, 0), v(0, 3, 0), PLANETS).length());
        assertEquals(0, CosmicWind.push(v(0, 100 + CosmicWind.FREE, 0), v(0, 3, 0), PLANETS).length());
    }

    @Test void noPlanetsNoWind() {
        assertEquals(0, CosmicWind.push(v(0, 1e6, 0), v(0, 1, 0), List.of()).length());
    }

    @Test void pullsTowardTheNearestPlanet() {
        Vector3d dv = CosmicWind.push(v(1000, 400, 0), new Vector3d(), PLANETS);
        assertTrue(dv.length() > 0);
        assertEquals(-1, dv.normalize().y, 1e-9, "toward the planet at x = 1000, straight down");
    }

    @Test void growsSmoothlyAndCaps() {
        double last = 0;
        for (double past = CosmicWind.FREE; past <= CosmicWind.FREE + 2 * CosmicWind.RAMP; past += 10) {
            double s = CosmicWind.push(v(0, 100 + past, 0), new Vector3d(), PLANETS).length();
            assertTrue(s >= last - 1e-12, "never weaker farther out: " + past);
            assertTrue(s <= CosmicWind.PULL + 1e-12);
            last = s;
        }
        assertEquals(CosmicWind.PULL, last, 1e-12);
    }

    @Test void slowsGoingOutButNeverPushesOut() {
        Vector3d far = v(0, 100 + CosmicWind.FREE + CosmicWind.RAMP, 0);
        Vector3d out = CosmicWind.push(far, v(0, 2, 0), PLANETS);
        assertEquals(-(CosmicWind.PULL + 2 * CosmicWind.DRAG), out.y, 1e-9);
        Vector3d in = CosmicWind.push(far, v(0, -2, 0), PLANETS);
        assertEquals(-CosmicWind.PULL, in.y, 1e-9, "coming back: only the pull");
        Vector3d side = CosmicWind.push(far, v(5, 0, 0), PLANETS);
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
        assertEquals(0, CosmicWind.push(v(0, 150, 0), new Vector3d(), List.of(slab)).length());
        assertTrue(CosmicWind.push(v(0, 500, 0), new Vector3d(), List.of(slab)).y < 0);
    }
}
