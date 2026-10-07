package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CubeSphereTest {
    final CubeSphere g = new CubeSphere(24, 7, 17);

    @Test void gridApiMatchesTheOldFormulas() {
        CellGrid cg = g;
        assertEquals(6, cg.faces());
        assertEquals(6 * 24 * 24, cg.columns());
        int c = g.index(3, 5, 7, 4);
        assertEquals(0, g.dir(3, 5, 7).mul(g.radius(4)).distance(cg.vertex(3, 5, 7, 4)), 1e-9);
        assertEquals(0, g.dir(3, 5, 7).distance(cg.columnUp(3, 5, 7)), 1e-9);
        assertEquals(0, g.corner(c, 1, 0, 1).distance(cg.vertex(3, 6, 7, 5)), 1e-9);
        assertTrue(cg.inCore(new Vector3d(0, 6.5, 0)));
        assertFalse(cg.inCore(new Vector3d(0, 7.5, 0)));
        assertEquals(g.radius(9), cg.radiusAt(9));
    }

    @Test void centerOfEveryCellMapsBack() {
        for (int c = 0; c < g.cellCount(); c++) assertEquals(c, g.cellAt(g.center(c)), "cell " + c);
    }

    @Test void outsideTheLayersIsNoCell() {
        assertEquals(-1, g.cellAt(new Vector3d(0, 6.9, 0)));
        assertEquals(-1, g.cellAt(new Vector3d(0, 24.01, 0)));
        assertEquals(-1, g.cellAt(new Vector3d()));
    }

    @Test void neighborsAreSymmetricAcrossFacesToo() {
        for (int c = 0; c < g.cellCount(); c++)
            for (int s = 0; s < 6; s++) {
                int nb = g.neighbor(c, s);
                if (nb < 0) {
                    assertTrue(s == CubeSphere.TOP || s == CubeSphere.BOTTOM, "side neighbor missing " + c + "/" + s);
                    continue;
                }
                assertNotEquals(c, nb);
                boolean back = false;
                for (int t = 0; t < 6; t++) back |= g.neighbor(nb, t) == c;
                assertTrue(back, c + " -> " + nb + " not mutual");
            }
    }

    @Test void neighborsShareTheirSideCorners() {
        for (int c = 0; c < g.cellCount(); c += 7)
            for (int s = 2; s < 6; s++) {
                int nb = g.neighbor(c, s);
                Vector3d[] q = g.side(c, s);
                int shared = 0;
                for (int t = 2; t < 6; t++) {
                    if (g.neighbor(nb, t) != c) continue;
                    for (Vector3d a : q) for (Vector3d b : g.side(nb, t)) if (a.distance(b) < 1e-9) shared++;
                }
                assertEquals(4, shared, "cell " + c + " side " + s);
            }
    }

    @Test void sidesFaceOutwardAndTopIsAwayFromCenter() {
        for (int c = 0; c < g.cellCount(); c += 11)
            for (int s = 0; s < 6; s++) {
                Vector3d[] q = g.side(c, s);
                Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
                Vector3d mid = new Vector3d(q[0]).add(q[2]).mul(0.5);
                assertTrue(n.dot(new Vector3d(mid).sub(g.center(c))) > 0, c + "/" + s);
                if (s == CubeSphere.TOP) assertTrue(n.dot(mid) > 0);
                if (s >= 2) assertTrue(q[3].length() > q[0].length() + 0.5, "texture top is the outer edge");
            }
    }

    @Test void surfaceCellsAreAboutABlockWide() {
        int c = g.index(2, 12, 12, 8);
        double w = g.corner(c, 0, 0, 1).distance(g.corner(c, 1, 0, 1));
        assertTrue(w > 0.9 && w < 1.2, "width " + w);
    }
}
