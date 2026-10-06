package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutlineEdgesTest {
    static double length(double[] e) {
        return Math.abs(e[3] - e[0]) + Math.abs(e[4] - e[1]) + Math.abs(e[5] - e[2]);
    }

    static boolean has(List<double[]> edges, double... e) {
        for (double[] x : edges) {
            boolean same = true, flipped = true;
            for (int k = 0; k < 6; k++) {
                same &= Math.abs(x[k] - e[k]) < 1e-9;
                flipped &= Math.abs(x[(k + 3) % 6] - e[k]) < 1e-9;
            }
            if (same || flipped) return true;
        }
        return false;
    }

    @Test void aCubeHasTwelveEdges() {
        List<double[]> e = OutlineEdges.of(List.of(BlockInfo.FULL));
        assertEquals(12, e.size());
        assertTrue(e.stream().allMatch(x -> length(x) == 1));
    }

    @Test void aSlabIsItsBox() {
        List<double[]> e = OutlineEdges.of(List.of(new double[] {0, 0, 0, 1, 0.5, 1}));
        assertEquals(12, e.size());
        assertTrue(has(e, 0, 0.5, 0, 1, 0.5, 0));
    }

    @Test void stairsShowTheirStepAndNoLineAcrossAFace() {
        // A bottom slab and the top half at the back: an L, 6 corners on each end, 18 edges.
        List<double[]> e = OutlineEdges.of(List.of(new double[] {0, 0, 0, 1, 0.5, 1}, new double[] {0, 0.5, 0.5, 1, 1, 1}));
        assertEquals(18, e.size());
        assertTrue(has(e, 0, 0.5, 0.5, 1, 0.5, 0.5), "the step's inner corner");
        assertTrue(has(e, 0, 0, 0, 0, 0, 1), "the bottom's side in one piece, not two");
        assertFalse(has(e, 0, 0, 0.5, 1, 0, 0.5), "nothing across the bottom face");
        assertFalse(has(e, 0, 0.5, 1, 1, 0.5, 1), "nothing across the back face");
    }

    @Test void touchingBoxesMakeOneShape() {
        // Two halves side by side are the whole cube's 12 edges.
        List<double[]> e = OutlineEdges.of(List.of(new double[] {0, 0, 0, 0.5, 1, 1}, new double[] {0.5, 0, 0, 1, 1, 1}));
        assertEquals(12, e.size());
    }

    @Test void diagonalBoxesKeepTheEdgeTheyShare() {
        // Two boxes meeting at one line only: that line is an edge of both.
        List<double[]> e = OutlineEdges.of(List.of(new double[] {0, 0, 0, 1, 0.5, 0.5}, new double[] {0, 0.5, 0.5, 1, 1, 1}));
        assertTrue(has(e, 0, 0.5, 0.5, 1, 0.5, 0.5));
        // 24 edges, the shared one once, and four pairs that run on in one line joined (on each end,
        // the upright ones at z = 0.5 and the level ones at y = 0.5).
        assertEquals(19, e.size());
    }

    @Test void aFencePostWithABar() {
        // Post 6..10 across, bar 7..9 thick from the post to the north side, at 12..15 high.
        double p = 1 / 16.0;
        List<double[]> e = OutlineEdges.of(List.of(new double[] {6 * p, 0, 6 * p, 10 * p, 1, 10 * p},
                new double[] {7 * p, 12 * p, 0, 9 * p, 15 * p, 6 * p}));
        assertEquals(12 + 12, e.size(), "the post's and the bar's, the bar's end drawn where it meets the post");
    }

    @Test void nothingGivesNothing() {
        assertTrue(OutlineEdges.of(List.of()).isEmpty());
        assertTrue(OutlineEdges.of(List.of(new double[] {0, 0, 0, 1, 0, 1})).isEmpty(), "a flat box");
    }
}
