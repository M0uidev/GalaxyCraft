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
}
