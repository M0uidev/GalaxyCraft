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

    @Test void unitsPerBlockFromProperty() {
        assertEquals(89, GravityFrame.unitsPerBlock("89"));
        assertEquals(62.5, GravityFrame.unitsPerBlock(" 62.5 "));
        assertEquals(GravityFrame.DEFAULT_UNITS_PER_BLOCK, GravityFrame.unitsPerBlock(null));
        for (String bad : new String[] {"", "abc", "0", "-5", "NaN", "Infinity", "100000"}) {
            assertEquals(GravityFrame.DEFAULT_UNITS_PER_BLOCK, GravityFrame.unitsPerBlock(bad), bad);
        }
    }

    @Test void scaleFollowsTheProperty() {
        // build.gradle pins -Dgalaxycraft.unitsPerBlock=100 for unit tests.
        assertEquals(1.0 / 100, GravityFrame.SCALE);
        var f = start();
        assertVec(v(0, 101.78, 0), f.toMc(v(0, 820 + 178, 0)), 1e-9);
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

    @Test void dirToGalInterpolatesTheLastTurn() {
        var f = new GravityFrame(new Vector3d(), new Vector3d(0, 100, 0), new Vector3d(0, -1, 0));
        f.update(new Vector3d(-1, 0, 0), new Vector3d(0, 100, 0)); // up turns 90° to +x
        Vector3d mcUp = new Vector3d(0, 1, 0);
        assertTrue(f.dirToGal(mcUp, 0).distance(0, 1, 0) < 1e-9);
        assertTrue(f.dirToGal(mcUp, 1).distance(1, 0, 0) < 1e-9);
        Vector3d half = f.dirToGal(mcUp, 0.5);
        assertEquals(45, Math.toDegrees(half.angle(new Vector3d(0, 1, 0))), 1e-6);
        f.update(new Vector3d(-1, 0, 0), new Vector3d(0, 100, 0)); // no turn this tick
        assertTrue(f.dirToGal(mcUp, 0).distance(1, 0, 0) < 1e-9);
    }

    @Test void limitTurnCapsTheAngle() {
        Vector3d from = new Vector3d(0, 1, 0), to = new Vector3d(1, 0, 0);
        Vector3d step = GravityFrame.limitTurn(from, to, Math.toRadians(10));
        assertEquals(10, Math.toDegrees(from.angle(step)), 1e-6);
        assertEquals(80, Math.toDegrees(step.angle(to)), 1e-6);
        assertTrue(GravityFrame.limitTurn(from, new Vector3d(0.1, 1, 0).normalize(), 1).distance(new Vector3d(0.1, 1, 0).normalize()) < 1e-12);
    }

    static int stepsToTurn(Vector3d from, Vector3d to) {
        Vector3d up = new Vector3d(from);
        for (int n = 1; n < 1000; n++) {
            up = GravityFrame.limitTurn(up, to, GravityFrame.FLIGHT_TURN_PER_TICK);
            assertTrue(Double.isFinite(up.x + up.y + up.z), "no NaN at step " + n);
            assertEquals(1, up.length(), 1e-9);
            if (up.distance(to) < 1e-9) return n;
        }
        return -1;
    }

    @Test void flightTurnFlipsOverInAboutThirtyTicks() {
        int flip = stepsToTurn(v(0, 1, 0), v(0, -1, 0));
        assertTrue(flip >= 30 && flip <= 31, "flip took " + flip);
        int quarter = stepsToTurn(v(0, 1, 0), v(1, 0, 0));
        assertTrue(quarter >= 15 && quarter <= 16, "a quarter took " + quarter);
    }

    @Test void aTickWithoutATurnDrawsStill() {
        GravityFrame f = new GravityFrame(new Vector3d(), new Vector3d(0, 100, 0), new Vector3d(0, -1, 0));
        f.startTick();
        f.update(new Vector3d(0.3, -1, 0), new Vector3d(0, 100, 0)); // a turn this tick
        f.startTick(); // the next tick turns no more (the void: up stays)
        Vector3d d = new Vector3d(0, 0, 1);
        assertTrue(f.dirToGal(d, 0.3).distance(f.dirToGal(d)) < 1e-12, "no swing back to last tick's turn");
    }
}
