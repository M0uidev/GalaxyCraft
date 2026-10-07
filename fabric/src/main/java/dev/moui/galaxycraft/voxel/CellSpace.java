package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * A block's model space inside a cell of the planet. Minecraft's x, y, z (0 to 1, y up) go to the
 * cell's j, k, i: y outward from the center, x along j and z along i, which keeps models from being
 * mirrored (i × j = k on the grid, x × y = z in Minecraft). A point is placed by trilinear
 * interpolation of the cell's eight corners, so neighbors' models meet where their cells do.
 *
 * Minecraft's directions as cell sides: down BOTTOM, up TOP, north (-z) I_MINUS, south I_PLUS,
 * west (-x) J_MINUS, east J_PLUS.
 */
public final class CellSpace {
    /** By Minecraft's Direction ordinal (down, up, north, south, west, east): the cell side. */
    public static final int[] SIDE_OF_DIRECTION = {CubeSphere.BOTTOM, CubeSphere.TOP, CubeSphere.I_MINUS,
            CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};
    /** By cell side: Minecraft's Direction ordinal. */
    public static final int[] DIRECTION_OF_SIDE = {1, 0, 2, 3, 4, 5};
    /** By cell side: the step in model space it faces (x, y, z). */
    public static final int[][] STEP = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private CellSpace() {}

    /** Where model point (x, y, z) of cell lies, planet blocks (values outside 0..1 extrapolate). */
    public static Vector3d point(CellGrid g, int cell, double x, double y, double z) {
        Vector3d out = new Vector3d();
        for (int m = 0; m < 8; m++) {
            int di = m & 1, dj = m >> 1 & 1, dk = m >> 2;
            double w = (di == 1 ? z : 1 - z) * (dj == 1 ? x : 1 - x) * (dk == 1 ? y : 1 - y);
            out.fma(w, g.corner(cell, di, dj, dk));
        }
        return out;
    }

    /** The model point of cell at planet point p (Newton's method on the trilinear map). */
    public static Vector3d local(CellGrid g, int cell, Vector3d p) {
        if (g instanceof FlatGrid f) // a unit cube: exact, no Newton
            return f.toStation(p).sub(f.stationX(cell) - 0.5, f.stationY(cell) - 0.5, f.stationZ(cell) - 0.5);
        Vector3d m = new Vector3d(0.5, 0.5, 0.5);
        double h = 1e-4;
        for (int it = 0; it < 8; it++) {
            Vector3d f = point(g, cell, m.x, m.y, m.z).sub(p);
            if (f.lengthSquared() < 1e-14) break;
            Vector3d dx = point(g, cell, m.x + h, m.y, m.z).sub(p).sub(f).div(h);
            Vector3d dy = point(g, cell, m.x, m.y + h, m.z).sub(p).sub(f).div(h);
            Vector3d dz = point(g, cell, m.x, m.y, m.z + h).sub(p).sub(f).div(h);
            org.joml.Matrix3d j = new org.joml.Matrix3d(dx, dy, dz);
            if (Math.abs(j.determinant()) < 1e-12) break;
            m.sub(j.invert().transform(f));
        }
        return m;
    }

    /**
     * A planet direction in cell's model axes (x, y, z), unit: the axes at the cell's center made
     * orthonormal around its outward direction.
     */
    public static Vector3d direction(CellGrid g, int cell, Vector3d dir) {
        Vector3d c = point(g, cell, 0.5, 0.5, 0.5);
        Vector3d y = new Vector3d(c).normalize();
        Vector3d x = point(g, cell, 1, 0.5, 0.5).sub(point(g, cell, 0, 0.5, 0.5));
        x.fma(-x.dot(y), y).normalize();
        Vector3d z = new Vector3d(x).cross(y);
        Vector3d d = new Vector3d(dir).normalize();
        return new Vector3d(d.dot(x), d.dot(y), d.dot(z));
    }
}
