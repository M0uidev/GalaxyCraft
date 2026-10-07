package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * Cells as the voxel pipeline sees them: faces of n × n columns, each `layers` cells high, a cell
 * (face, i, j, k) bounded by eight corners its neighbors share. A planet's grid is a sphere
 * ({@link CubeSphere}), a station's a turned box ({@link FlatGrid}). Units are blocks, from the
 * body's center. "Up" (TOP) is always +k.
 */
public abstract class CellGrid {
    /** Cell sides. TOP is up (+k). */
    public static final int TOP = 0, BOTTOM = 1, I_MINUS = 2, I_PLUS = 3, J_MINUS = 4, J_PLUS = 5;

    public final int n;
    public final int layers;

    protected CellGrid(int n, int layers) {
        this.n = n;
        this.layers = layers;
    }

    /** Faces of columns: 6 on a sphere, 1 flat. */
    public abstract int faces();

    public final int cellCount() {
        return faces() * n * n * layers;
    }

    /** Columns of cells (cell / layers). */
    public final int columns() {
        return faces() * n * n;
    }

    public final int index(int face, int i, int j, int k) {
        return ((face * n + i) * n + j) * layers + k;
    }

    public final int face(int cell) {
        return cell / (n * n * layers);
    }

    public final int i(int cell) {
        return cell / (n * layers) % n;
    }

    public final int j(int cell) {
        return cell / layers % n;
    }

    public final int k(int cell) {
        return cell % layers;
    }

    /** Column vertex (i, j in [0, n]) of a face at height h, in layers from the bottom of layer 0. */
    public abstract Vector3d vertex(int face, int i, int j, double h);

    /** Unit "up" at column vertex (i, j): away from the center on a sphere, the station's up when flat. */
    public abstract Vector3d columnUp(int face, int i, int j);

    /** Corner (di, dj, dk ∈ {0, 1}) of a cell. */
    public Vector3d corner(int cell, int di, int dj, int dk) {
        return vertex(face(cell), i(cell) + di, j(cell) + dj, k(cell) + dk);
    }

    public Vector3d center(int cell) {
        Vector3d c = new Vector3d();
        for (int m = 0; m < 8; m++) c.add(corner(cell, m & 1, m >> 1 & 1, m >> 2));
        return c.mul(1 / 8.0);
    }

    /** The cell containing p, or -1 outside every cell. */
    public abstract int cellAt(Vector3d p);

    /**
     * Cell (i, j) of a face's grid carried on past its edges (onto the next face on a sphere);
     * -1 where there is none.
     */
    public abstract int cellBeyond(int face, int i, int j, int k);

    /** Neighbor across a side, or -1 where there is none. */
    public abstract int neighbor(int cell, int side);

    /** Distance from the center that holds the top of layer k everywhere (a sphere's radius there). */
    public abstract double radiusAt(int k);

    /** Whether p is in the solid core under the layers (a sphere's; a flat grid has none). */
    public abstract boolean inCore(Vector3d p);

    /** Whether every cell is a box mapped affinely (CellSpace.local needs no Newton). */
    public boolean affine() {
        return false;
    }

    /**
     * The four corners of a side, counter-clockwise seen from outside the cell. Sides other than
     * TOP and BOTTOM start at the bottom: (inner, inner, outer, outer), so a texture's top edge
     * goes on q[2]→q[3], up.
     */
    public Vector3d[] side(int cell, int side) {
        Vector3d[] q;
        switch (side) {
            case TOP, BOTTOM -> {
                int dk = side == TOP ? 1 : 0;
                q = new Vector3d[] {corner(cell, 0, 0, dk), corner(cell, 1, 0, dk), corner(cell, 1, 1, dk),
                        corner(cell, 0, 1, dk)};
            }
            default -> {
                boolean alongJ = side == I_MINUS || side == I_PLUS;
                int fixed = side == I_PLUS || side == J_PLUS ? 1 : 0;
                int ai = alongJ ? fixed : 0, aj = alongJ ? 0 : fixed;
                int bi = alongJ ? fixed : 1, bj = alongJ ? 1 : fixed;
                q = new Vector3d[] {corner(cell, ai, aj, 0), corner(cell, bi, bj, 0), corner(cell, bi, bj, 1),
                        corner(cell, ai, aj, 1)};
            }
        }
        Vector3d n = new Vector3d(q[1]).sub(q[0]).cross(new Vector3d(q[2]).sub(q[0]));
        Vector3d mid = new Vector3d(q[0]).add(q[1]).add(q[2]).add(q[3]).mul(0.25);
        if (n.dot(mid.sub(center(cell))) < 0) {
            Vector3d t = q[0];
            q[0] = q[1];
            q[1] = t;
            t = q[2];
            q[2] = q[3];
            q[3] = t;
        }
        return q;
    }
}
