package dev.moui.galaxycraft.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moui.galaxycraft.voxel.CubeSphere;
import org.junit.jupiter.api.Test;

class ShadowMapTest {
    private final CubeSphere grid = new CubeSphere(12, 4, 6);
    private final ShadowMap map = new ShadowMap(grid, "Stage");

    @Test
    void everyCellHasItsOwnPosition() {
        for (int c = 0; c < grid.cellCount(); c++) assertEquals(c, map.cell(map.x(c), map.y(c), map.z(c)));
    }

    @Test
    void insideAFaceNeighborsAreOneStepApart() {
        for (int c = 0; c < grid.cellCount(); c++)
            for (int s = 0; s < 6; s++) {
                int nb = grid.neighbor(c, s);
                if (nb < 0 || grid.face(nb) != grid.face(c)) continue;
                int[] d = ShadowMap.STEP[s];
                assertEquals(nb, map.cell(map.x(c) + d[0], map.y(c) + d[1], map.z(c) + d[2]));
            }
    }

    @Test
    void acrossAnEdgeTheHaloHoldsTheRealNeighbor() {
        int edges = 0;
        for (int c = 0; c < grid.cellCount(); c++)
            for (int s = CubeSphere.I_MINUS; s <= CubeSphere.J_PLUS; s++) {
                int nb = grid.neighbor(c, s);
                if (nb < 0 || grid.face(nb) == grid.face(c)) continue;
                int[] d = ShadowMap.STEP[s];
                assertEquals(nb, map.haloSource(map.x(c) + d[0], map.y(c), map.z(c) + d[2]));
                edges++;
            }
        assertTrue(edges > 0);
    }

    @Test
    void anEdgeCellsHalosCopyIt() {
        for (int c = 0; c < grid.cellCount(); c++) {
            int[][] halos = map.halos(c);
            assertEquals(map.onEdge(c), halos.length > 0);
            for (int[] h : halos) assertEquals(c, map.haloSource(h[0], h[1], h[2]));
        }
    }

    @Test
    void walkingOffAFaceEdgeGoesOnAroundThePlanet() {
        int wrapped = 0;
        for (int c = 0; c < grid.cellCount(); c++)
            for (int s = CubeSphere.I_MINUS; s <= CubeSphere.J_PLUS; s++) {
                int nb = grid.neighbor(c, s);
                if (nb < 0 || grid.face(nb) == grid.face(c)) continue;
                // Half a block past the cell's side, in its face's halo.
                int[] d = ShadowMap.STEP[s];
                double x = map.x(c) + 0.5 + d[0], y = map.y(c) + 0.5, z = map.z(c) + 0.5 + d[2];
                ShadowMap.Wrap w = map.wrap(x, y, z);
                assertTrue(w != null, "wraps");
                double[] before = map.frame(x, y, z), after = map.frame(w.x(), w.y(), w.z());
                for (int k = 0; k < 3; k++) assertEquals(before[k], after[k], 1e-3, "same planet point");
                assertEquals(nb, map.cell((int) Math.floor(w.x()), (int) Math.floor(w.y()), (int) Math.floor(w.z())));
                // Walking on: a step that left the face keeps leaving the edge behind on the next one.
                org.joml.Vector3d out = w.turn().transform(new org.joml.Vector3d(d[0], d[1], d[2]));
                ShadowMap.Wrap back = map.wrap(w.x() - out.x, w.y() - out.y, w.z() - out.z);
                assertTrue(back != null && grid.face(map.cell((int) Math.floor(back.x()), (int) Math.floor(back.y()),
                        (int) Math.floor(back.z()))) == grid.face(c), "turning back returns");
                wrapped++;
            }
        assertTrue(wrapped > 0);
        assertEquals(null, map.wrap(map.x(0) + 0.5, map.y(0) + 0.5, map.z(0) + 0.5), "inside a face: stays");
    }
}
