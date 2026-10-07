package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class OriginTest {
    static final double U = 80;

    @Test void aPointNearAnotherIsExactHoweverFarBothAre() {
        // 10^15 cells: 8 * 10^17 blocks out, far past where a double counts single units.
        UPos a = UPos.of(1_000_000_000_000_000L, -7, 3, 1000.25, 5, 65535.5);
        UPos b = a.plus(new Vector3d(0.125, 70_000, -1));
        assertEquals(new Vector3d(0.125, 70_000, -1), b.minus(a));
        assertTrue(b.y() >= 0 && b.y() < UPos.CELL, "offsets stay inside their cell");
        assertEquals(a.cy() + 1, b.cy());
    }

    @Test void negativeOffsetsGoToTheCellBelow() {
        UPos p = UPos.of(new Vector3d(-1, -UPos.CELL, 0));
        assertEquals(-1, p.cx());
        assertEquals(UPos.CELL - 1, p.x());
        assertEquals(-1, p.cy());
        assertEquals(0, p.y());
    }

    @Test void movingTheOriginLandsMarioWithinHalfACell() {
        Origin o = new Origin();
        UPos mario = UPos.of(new Vector3d(1_000_000 * U, -3_000_000 * U, 42));
        Origin.Shift s = o.moveTo(mario);
        assertNotNull(s);
        assertEquals(1, s.epoch());
        Vector3d local = o.local(mario);
        assertTrue(Math.abs(local.x) <= UPos.CELL / 2 && Math.abs(local.y) <= UPos.CELL / 2 && Math.abs(local.z) <= UPos.CELL / 2);
        assertNull(o.moveTo(mario), "already there");
        assertEquals(mario, o.universe(local));
    }

    /** What the game does with a move: every float position minus the shift, in floats. */
    @Test void aMoveTowardTheOriginKeepsEveryFloatBitSoNothingJumps() {
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < 100_000; i++) {
            // Mario somewhere up to 16 cells (13,000 blocks) out, the shift whole cells toward 0.
            float x = (float) ((rnd.nextDouble() * 2 - 1) * 16 * UPos.CELL);
            long cells = Math.round(x / UPos.CELL);
            float moved = x - (float) (cells * UPos.CELL);
            assertEquals((double) x - cells * UPos.CELL, (double) moved, 0.0, "exact at " + x);
        }
    }

    /**
     * Why it is needed: a float position's last bit, in blocks, at a distance from the origin. The
     * game's view, gravity and collision subtract positions like these every frame.
     */
    static double floatStepBlocks(double blocks) {
        return Math.ulp((float) (blocks * U)) / U;
    }

    @Test void floatsShakeFarAwayAndNotNearTheOrigin() {
        assertTrue(floatStepBlocks(3_000) < 0.001, "a system: under a thousandth of a block");
        assertTrue(floatStepBlocks(100_000) >= 0.006, "100k blocks: visible on Mario's 145 units");
        assertTrue(floatStepBlocks(1_000_000) >= 0.1, "a million: a tenth of a block");
        assertTrue(floatStepBlocks(30_000_000) >= 1, "Minecraft's border: whole blocks");
        // With the origin following, Mario is never past MUST_AT (16 cells) from it: an eighth of a unit.
        assertTrue(Math.ulp((float) OriginPolicy.MUST_AT) / U < 0.002);
    }

    @Test void positionsOfAnOlderEpochAreConverted() {
        Origin o = new Origin();
        Origin.Shift a = o.moveTo(UPos.of(new Vector3d(5 * UPos.CELL, 0, 0)));
        Origin.Shift b = o.moveTo(UPos.of(new Vector3d(5 * UPos.CELL, 3 * UPos.CELL, 0)));
        assertEquals(new Vector3d(5 * UPos.CELL, 0, 0), a.units());
        assertEquals(new Vector3d(0, 3 * UPos.CELL, 0), b.units());
        // Reported at (5 cells + 10, 3 cells, 0) in epoch 0: now (10, 0, 0).
        Vector3d now = o.local(new Vector3d(5 * UPos.CELL + 10, 3 * UPos.CELL, 0), 0);
        assertEquals(new Vector3d(10, 0, 0), now);
        assertEquals(new Vector3d(1, 2, 3), o.local(new Vector3d(1, 2, 3), o.epoch()));
    }

    @Test void tooOldAnEpochIsNotGuessed() {
        Origin o = new Origin();
        for (int i = 1; i <= Origin.HISTORY + 2; i++) o.moveTo(UPos.of(new Vector3d(i * UPos.CELL, 0, 0)));
        assertNull(o.local(new Vector3d(), 0));
        assertNotNull(o.local(new Vector3d(), o.epoch() - Origin.HISTORY));
        assertNull(o.local(new Vector3d(), o.epoch() + 1), "not from the future either");
    }
}
