package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FlatGridTest {
    final Quaterniond tilt = new Quaterniond().rotateXYZ(0.3, 1.1, -0.4);
    final FlatGrid g = new FlatGrid(24, 16, -12, -4, -12, tilt);

    @Test void oneFacePlainIndexing() {
        assertEquals(1, g.faces());
        assertEquals(24 * 24 * 16, g.cellCount());
        int c = g.index(0, 3, 5, 7);
        assertEquals(3, g.i(c));
        assertEquals(5, g.j(c));
        assertEquals(7, g.k(c));
    }

    @Test void stationCoordinatesRoundTrip() {
        int c = g.cellOf(0, 0, 0);
        assertEquals(0, g.stationX(c));
        assertEquals(0, g.stationY(c));
        assertEquals(0, g.stationZ(c));
        assertEquals(-1, g.cellOf(12, 0, 0));
        assertEquals(-1, g.cellOf(0, -5, 0));
        assertEquals(0, g.center(c).length(), 1e-9); // the core's cell is centered on the origin
    }

    @Test void centersMapBackUnderRotation() {
        for (int c = 0; c < g.cellCount(); c += 5) assertEquals(c, g.cellAt(g.center(c)), "cell " + c);
        assertEquals(-1, g.cellAt(new Vector3d(g.up()).mul(40)));
    }

    @Test void neighborsArePlainAndStopAtTheBox() {
        int c = g.index(0, 0, 0, 0);
        assertEquals(-1, g.neighbor(c, CellGrid.I_MINUS));
        assertEquals(-1, g.neighbor(c, CellGrid.J_MINUS));
        assertEquals(-1, g.neighbor(c, CellGrid.BOTTOM));
        assertEquals(g.index(0, 1, 0, 0), g.neighbor(c, CellGrid.I_PLUS));
        assertEquals(g.index(0, 0, 1, 0), g.neighbor(c, CellGrid.J_PLUS));
        assertEquals(g.index(0, 0, 0, 1), g.neighbor(c, CellGrid.TOP));
        assertEquals(-1, g.neighbor(g.index(0, 23, 23, 15), CellGrid.TOP));
    }

    @Test void topIsUpAndCellsAreUnitCubes() {
        int c = g.index(0, 4, 4, 4);
        Vector3d up = g.corner(c, 0, 0, 1).sub(g.corner(c, 0, 0, 0));
        assertEquals(0, up.distance(g.up()), 1e-9);
        assertEquals(1, g.corner(c, 1, 0, 0).distance(g.corner(c, 0, 0, 0)), 1e-9);
        Vector3d[] top = g.side(c, CellGrid.TOP);
        Vector3d nrm = new Vector3d(top[1]).sub(top[0]).cross(new Vector3d(top[2]).sub(top[0])).normalize();
        assertEquals(1, nrm.dot(g.up()), 1e-9);
    }

    @Test void cellModelAxesAreMinecraftsWithoutMirroring() {
        // CellSpace: x along j, y = k, z along i, and x × y = z as in Minecraft.
        int c = g.index(0, 4, 4, 4);
        Vector3d x = g.corner(c, 0, 1, 0).sub(g.corner(c, 0, 0, 0));
        Vector3d y = g.corner(c, 0, 0, 1).sub(g.corner(c, 0, 0, 0));
        Vector3d z = g.corner(c, 1, 0, 0).sub(g.corner(c, 0, 0, 0));
        assertEquals(1, new Vector3d(x).cross(y).dot(z), 1e-9);
    }

    @Test void localIsExact() {
        int c = g.index(0, 2, 3, 4);
        Vector3d p = CellSpace.point(g, c, 0.25, 0.5, 0.75);
        Vector3d m = CellSpace.local(g, c, p);
        assertEquals(0, m.distance(new Vector3d(0.25, 0.5, 0.75)), 1e-12);
    }

    @Test void radiusHoldsTheTopCorners() {
        assertTrue(g.radiusAt(16) >= g.vertex(0, 24, 24, 16).length() - 1e-9);
        assertFalse(g.inCore(new Vector3d()));
        assertTrue(g.affine());
    }
}
