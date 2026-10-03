package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * A sphere of cells: a cube projected onto it (equal-angle mapping), each face an n×n grid, and
 * radial layers one block thick. Cell (face, i, j, k) spans radii [core + k, core + k + 1]; its
 * sides lie on planes through the center, so neighbors share their corners exactly and "up" is
 * always the outer face. Units are blocks, origin at the planet's center.
 */
public final class CubeSphere {
    /** Cell sides. TOP faces away from the center. */
    public static final int TOP = 0, BOTTOM = 1, I_MINUS = 2, I_PLUS = 3, J_MINUS = 4, J_PLUS = 5;

    // Per face: normal N, then U and V with U × V = N.
    private static final double[][][] BASIS = {
            {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}}, {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
            {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}}, {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}}};

    public final int n;
    public final double core;
    public final int layers;

    public CubeSphere(int n, double core, int layers) {
        this.n = n;
        this.core = core;
        this.layers = layers;
    }

    public int cellCount() {
        return 6 * n * n * layers;
    }

    public int index(int face, int i, int j, int k) {
        return ((face * n + i) * n + j) * layers + k;
    }

    public int face(int cell) {
        return cell / (n * n * layers);
    }

    public int i(int cell) {
        return cell / (n * layers) % n;
    }

    public int j(int cell) {
        return cell / layers % n;
    }

    public int k(int cell) {
        return cell % layers;
    }

    /** Radius of the bottom of layer k. */
    public double radius(int k) {
        return core + k;
    }

    /** Unit direction of grid vertex (i, j) of a face, i and j in [0, n]. */
    public Vector3d dir(int face, int i, int j) {
        double x = Math.tan((-1 + 2.0 * i / n) * Math.PI / 4), y = Math.tan((-1 + 2.0 * j / n) * Math.PI / 4);
        double[][] b = BASIS[face];
        return new Vector3d(b[0][0] + x * b[1][0] + y * b[2][0], b[0][1] + x * b[1][1] + y * b[2][1],
                b[0][2] + x * b[1][2] + y * b[2][2]).normalize();
    }

    /** Corner (di, dj, dk ∈ {0, 1}) of a cell. */
    public Vector3d corner(int cell, int di, int dj, int dk) {
        return dir(face(cell), i(cell) + di, j(cell) + dj).mul(radius(k(cell) + dk));
    }

    public Vector3d center(int cell) {
        Vector3d c = new Vector3d();
        for (int m = 0; m < 8; m++) c.add(corner(cell, m & 1, m >> 1 & 1, m >> 2));
        return c.mul(1 / 8.0);
    }

    /** The cell containing p, or -1 outside the layers. */
    public int cellAt(Vector3d p) {
        double r = p.length();
        int k = (int) Math.floor(r - core);
        if (r == 0 || k < 0 || k >= layers) return -1;
        int face = 0;
        double best = -1;
        for (int f = 0; f < 6; f++) {
            double d = dot(BASIS[f][0], p);
            if (d > best) {
                best = d;
                face = f;
            }
        }
        double[][] b = BASIS[face];
        return index(face, grid(dot(b[1], p) / best), grid(dot(b[2], p) / best), k);
    }

    /** Neighbor across a side, or -1 past the innermost or outermost layer. */
    public int neighbor(int cell, int side) {
        int k = k(cell);
        if (side == TOP) return k + 1 < layers ? cell + 1 : -1;
        if (side == BOTTOM) return k > 0 ? cell - 1 : -1;
        Vector3d[] q = side(cell, side);
        Vector3d mid = new Vector3d(q[0]).add(q[1]).add(q[2]).add(q[3]).mul(0.25);
        Vector3d out = new Vector3d(mid).sub(center(cell)).mul(0.05).add(mid);
        // Keep the probe in this layer's shell: only the direction should change.
        out.normalize(mid.length());
        return cellAt(out);
    }

    /**
     * The four corners of a side, counter-clockwise seen from outside the cell. Sides other than
     * TOP and BOTTOM start at the inner radius: (inner, inner, outer, outer), so a texture's top
     * edge goes on q[2]→q[3], away from the center.
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

    private int grid(double tan) {
        int g = (int) Math.floor((Math.atan(tan) * 4 / Math.PI + 1) / 2 * n);
        return Math.max(0, Math.min(n - 1, g));
    }

    private static double dot(double[] a, Vector3d p) {
        return a[0] * p.x + a[1] * p.y + a[2] * p.z;
    }
}
