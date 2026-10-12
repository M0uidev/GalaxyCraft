package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadWanderTest {
    private static final double DT = 0.05;
    private static final ToadWander.Terrain FLAT = new ToadWander.Terrain() {
        public double groundAt(double x, double z) { return 0; }
        public boolean blocked(double x, double z, double y) { return false; }
    };

    /** A Toad being driven the way ToadWalker will: position moves by the step, y snaps to the ground. */
    private static final class Rig {
        double x, z, y;
        ToadWander w;
        String anim = "";

        Rig(double homeX, double homeZ, double leash, long seed) {
            w = new ToadWander(homeX, homeZ, leash, seed);
            x = homeX;
            z = homeZ;
        }

        void run(double seconds, ToadWander.Terrain t, boolean night, boolean near, boolean talking, Runnable each) {
            for (double s = 0; s < seconds; s += DT) {
                ToadWander.Step st = w.step(DT, x, z, y, t, night, near, talking);
                x += st.dx();
                z += st.dz();
                y = t.groundAt(x, z);
                anim = st.anim();
                if (each != null) each.run();
            }
        }
    }

    @Test void neverLeavesTheLeash() {
        for (long seed = 1; seed <= 5; seed++) {
            Rig r = new Rig(10, -4, 6, seed);
            double[] max = {0};
            r.run(600, FLAT, false, false, false, () -> max[0] = Math.max(max[0], Math.hypot(r.x - 10, r.z + 4)));
            assertTrue(max[0] <= 6.5, "seed " + seed + " went " + max[0]);
            assertTrue(max[0] > 1.5, "seed " + seed + " never walked");
        }
    }

    @Test void aWallIsNotCrossedAndItKeepsMoving() {
        ToadWander.Terrain wall = new ToadWander.Terrain() {
            public double groundAt(double x, double z) { return 0; }
            public boolean blocked(double x, double z, double y) { return x >= 2; }
        };
        Rig r = new Rig(0, 0, 10, 7);
        double[] maxX = {-99};
        r.run(540, wall, false, false, false, () -> maxX[0] = Math.max(maxX[0], r.x));
        assertTrue(maxX[0] < 2, "crossed to " + maxX[0]);
        double sx = r.x, sz = r.z;
        double[] path = {0};
        double[] last = {sx, sz};
        r.run(60, wall, false, false, false, () -> {
            path[0] += Math.hypot(r.x - last[0], r.z - last[1]);
            last[0] = r.x;
            last[1] = r.z;
        });
        assertTrue(path[0] > 3, "stood still: " + path[0]);
    }

    @Test void oneBlockStepsAreClimbedAndThreeBlockDropsRefused() {
        // Ground rises 1 block per 2 blocks of x: climbable stairs.
        ToadWander.Terrain stairs = new ToadWander.Terrain() {
            public double groundAt(double x, double z) { return Math.floor(x / 2); }
            public boolean blocked(double x, double z, double y) { return false; }
        };
        Rig up = new Rig(0, 0, 12, 3);
        double[] maxX = {0};
        up.run(400, stairs, false, false, false, () -> maxX[0] = Math.max(maxX[0], up.x));
        assertTrue(maxX[0] > 4, "never climbed: " + maxX[0]);
        // A cliff: x >= 3 is 3 blocks down.
        ToadWander.Terrain cliff = new ToadWander.Terrain() {
            public double groundAt(double x, double z) { return x >= 3 ? -3 : 0; }
            public boolean blocked(double x, double z, double y) { return false; }
        };
        Rig down = new Rig(0, 0, 12, 3);
        double[] mx = {0};
        down.run(400, cliff, false, false, false, () -> mx[0] = Math.max(mx[0], down.x));
        assertTrue(mx[0] < 3, "dropped off at " + mx[0]);
    }

    @Test void atNightItGoesHomeAndSleepsAndWakesWithAPlayerNear() {
        Rig r = new Rig(0, 0, 8, 11);
        r.run(60, FLAT, false, false, false, null);
        r.run(60, FLAT, true, false, false, null);
        assertEquals("Sleep", r.anim);
        assertTrue(Math.hypot(r.x, r.z) <= 0.5, "slept " + Math.hypot(r.x, r.z) + " from home");
        assertTrue(r.w.sleeping());
        r.run(1, FLAT, true, true, false, null);
        assertNotEquals("Sleep", r.anim);
        assertFalse(r.w.sleeping());
    }

    @Test void talkingMeansNoMovement() {
        Rig r = new Rig(0, 0, 8, 5);
        r.run(20, FLAT, false, false, false, null);
        double x = r.x, z = r.z;
        r.run(10, FLAT, false, false, true, null);
        assertEquals(x, r.x);
        assertEquals(z, r.z);
        assertEquals("Wait", r.anim);
    }

    @Test void sameSeedSamePath() {
        Rig a = new Rig(3, 3, 7, 42), b = new Rig(3, 3, 7, 42);
        a.run(120, FLAT, false, false, false, null);
        b.run(120, FLAT, false, false, false, null);
        assertEquals(a.x, b.x);
        assertEquals(a.z, b.z);
    }

    @Test void walledInAtNightItSleepsWhereItStands() {
        ToadWander.Terrain shut = new ToadWander.Terrain() {
            public double groundAt(double x, double z) { return 0; }
            public boolean blocked(double x, double z, double y) { return x <= -1; } // home side shut
        };
        Rig r = new Rig(-5, 0, 8, 9);
        r.x = 2;
        r.run(120, shut, true, false, false, null);
        assertEquals("Sleep", r.anim);
    }
}
