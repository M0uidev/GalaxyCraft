package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class GravityFrameTest {
    static Vector3d v(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    static void assertVec(Vector3d want, Vector3d got, double eps) {
        assertTrue(want.distance(got) <= eps, () -> "expected " + want + " got " + got);
    }

    static GravityFrame start() {
        return new GravityFrame(v(0, 820, 0), v(0, 100, 0), v(0, -1, 0));
    }

    @Test void startMapsSpawn() {
        var f = start();
        assertVec(v(0, 100, 0), f.toMc(v(0, 820, 0)), 1e-9);
        assertVec(v(0, 101, 0), f.toMc(v(0, 920, 0)), 1e-9);  // 100 units = 1 block
    }

    @Test void upMatchesGravity() {
        var f = start();
        var g = v(1, 0, 0);
        assertTrue(f.update(g, v(0, 100, 0)).rotated());
        assertVec(v(0, -1, 0), f.dirToMc(g), 1e-9);
    }

    @Test void playerPositionContinuous() {
        var f = start();
        var p = v(3, 100, -2);
        var galBefore = f.toGal(p);
        f.update(v(0.3, -1, 0.1).normalize(), p);
        assertVec(galBefore, f.toGal(p), 1e-9);
    }

    @Test void deltaRotatesGalaxyFixedVectors() {
        var f = start();
        var velGal = v(0.2, 0.5, -0.1);
        var velMcBefore = f.dirToMc(velGal);
        var u = f.update(v(0.4, -1, 0.3).normalize(), v(0, 100, 0));
        var rotated = u.deltaMc().transform(new Vector3d(velMcBefore));
        assertVec(f.dirToMc(velGal), rotated, 1e-9);           // velocity conserved in galaxy frame
    }

    @Test void walkAroundSphereReturnsHome() {
        double r = 800;
        var f = new GravityFrame(v(0, r, 0), v(0, 100, 0), v(0, -1, 0));
        var p = v(0, 100, 0);
        var prevGal = v(0, r, 0);
        double travelled = 0;
        int steps = 0;
        while (travelled < 2 * Math.PI && steps < 10_000) {
            p.x += 0.05;                                         // walk "forward" in MC
            var gal = f.toGal(p).normalize().mul(r);             // stand on the surface
            p = f.toMc(gal);
            var g = new Vector3d(gal).normalize().negate();
            f.update(g, p);
            assertVec(v(0, -1, 0), f.dirToMc(g), 1e-9);
            assertEquals(0, gal.z, 1e-6, "stays on one great circle");
            travelled += prevGal.angle(gal);
            prevGal = gal;
            steps++;
        }
        assertTrue(steps < 10_000);
        assertVec(v(0, r, 0), prevGal, 5);
    }

    @Test void antiparallelNoNaN() {
        var f = start();
        var u = f.update(v(0, 1, 0), v(0, 100, 0));
        assertTrue(u.rotated());
        Quaterniond q = u.deltaMc();
        assertTrue(Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w));
        assertVec(v(0, -1, 0), f.dirToMc(v(0, 1, 0)), 1e-9);
        assertVec(f.toGal(v(0, 100, 0)), v(0, 820, 0), 1e-9);
    }

    @Test void tinyAngleIgnored() {
        var f = start();
        double a = Math.toRadians(0.01);
        assertFalse(f.update(v(Math.sin(a), -Math.cos(a), 0), v(0, 100, 0)).rotated());
    }

    @Test void zeroGravityKeepsFrame() {
        var f = start();
        assertFalse(f.update(v(0, 0, 0), v(0, 100, 0)).rotated());
        assertVec(v(0, 100, 0), f.toMc(v(0, 820, 0)), 1e-9);
    }

    @Test void rebaseKeepsGalaxyPosition() {
        var f = start();
        var p = v(5, 10, 5);
        var gal = f.toGal(p);
        var np = f.rebase(p).orElseThrow();
        assertEquals(100, np.y, 1e-9);
        assertVec(gal, f.toGal(np), 1e-9);
    }

    @Test void rebaseNotNeededInRange() {
        assertTrue(start().rebase(v(0, 120, 0)).isEmpty());
    }
}
