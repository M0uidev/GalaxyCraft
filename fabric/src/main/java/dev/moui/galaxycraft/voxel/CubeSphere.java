package dev.moui.galaxycraft.voxel;

import org.joml.Vector3d;

/**
 * A sphere of cells: a cube projected onto it (equal-angle mapping), each face an n×n grid, and
 * radial layers one block thick. Cell (face, i, j, k) spans radii [core + k, core + k + 1]; its
 * sides lie on planes through the center, so neighbors share their corners exactly and "up" is
 * always the outer face. Units are blocks, origin at the planet's center.
 */
public final class CubeSphere extends CellGrid {

    // Per face: normal N, then U and V with U × V = N.
    private static final double[][][] BASIS = {
            {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, {{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
            {{0, 1, 0}, {0, 0, 1}, {1, 0, 0}}, {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}},
            {{0, 0, 1}, {1, 0, 0}, {0, 1, 0}}, {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}}};

    public final double core;
    /** Unit directions of every face's (n+1)² grid vertices, x y z each. */
    private final float[] dirs;

    public CubeSphere(int n, double core, int layers) {
        super(n, layers);
        this.core = core;
        dirs = new float[6 * (n + 1) * (n + 1) * 3];
        for (int f = 0; f < 6; f++)
            for (int i = 0; i <= n; i++)
                for (int j = 0; j <= n; j++) {
                    Vector3d d = computeDir(f, i, j);
                    int o = ((f * (n + 1) + i) * (n + 1) + j) * 3;
                    dirs[o] = (float) d.x;
                    dirs[o + 1] = (float) d.y;
                    dirs[o + 2] = (float) d.z;
                }
    }

    @Override
    public int faces() {
        return 6;
    }

    /** Radius of the bottom of layer k. */
    public double radius(int k) {
        return core + k;
    }

    @Override
    public double radiusAt(int k) {
        return core + k;
    }

    @Override
    public boolean inCore(Vector3d p) {
        return p.length() < core;
    }

    @Override
    public Vector3d vertex(int face, int i, int j, double h) {
        return dir(face, i, j).mul(core + h);
    }

    @Override
    public Vector3d columnUp(int face, int i, int j) {
        return dir(face, i, j);
    }

    /** Unit direction of grid vertex (i, j) of a face, i and j in [0, n]. */
    public Vector3d dir(int face, int i, int j) {
        int o = ((face * (n + 1) + i) * (n + 1) + j) * 3;
        return new Vector3d(dirs[o], dirs[o + 1], dirs[o + 2]);
    }

    private Vector3d computeDir(int face, int i, int j) {
        // Exactly ±1 on the cube's edges (tan(π/4) is not quite 1 in doubles): the faces meeting
        // there then compute the same direction bit for bit, and their corners meet without a seam.
        double x = tanGrid(i), y = tanGrid(j);
        double[][] b = BASIS[face];
        return new Vector3d(b[0][0] + x * b[1][0] + y * b[2][0], b[0][1] + x * b[1][1] + y * b[2][1],
                b[0][2] + x * b[1][2] + y * b[2][2]).normalize();
    }

    /** tan of grid line i's angle, odd about the middle exactly (faces may run either way along an edge). */
    private double tanGrid(int i) {
        if (2 * i > n) return -tanGrid(n - i);
        if (i == 0) return -1;
        if (2 * i == n) return 0;
        return Math.tan((-1 + 2.0 * i / n) * Math.PI / 4);
    }

    /** Corner (di, dj, dk ∈ {0, 1}) of a cell. */
    @Override
    public Vector3d corner(int cell, int di, int dj, int dk) {
        return dir(face(cell), i(cell) + di, j(cell) + dj).mul(radius(k(cell) + dk));
    }

    /** The cell containing p, or -1 outside the layers. */
    @Override
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

    /**
     * Cell (i, j) of a face's grid carried on past its edges, one for one: past one edge it
     * crosses there and goes straight on along the next face's rows, which meet this face's
     * exactly. -1 outside the layers, a face or more away, or past two edges at once: round a
     * corner three faces meet, and there is no room for a fourth.
     */
    @Override
    public int cellBeyond(int face, int i, int j, int k) {
        if (k < 0 || k >= layers) return -1;
        boolean inI = i >= 0 && i < n, inJ = j >= 0 && j < n;
        if (inI && inJ) return index(face, i, j, k);
        if (!inI && !inJ) return -1;
        int side = !inI ? (i < 0 ? I_MINUS : I_PLUS) : j < 0 ? J_MINUS : J_PLUS;
        int past = !inI ? (i < 0 ? -i : i - n + 1) : j < 0 ? -j : j - n + 1;
        if (past >= n) return -1;
        int edge = index(face, Math.max(0, Math.min(n - 1, i)), Math.max(0, Math.min(n - 1, j)), k);
        int cell = neighbor(edge, side);
        // On the next face, "on" is away from the side that leads back.
        int on = -1;
        for (int s = I_MINUS; s <= J_PLUS; s++) if (neighbor(cell, s) == edge) on = s ^ 1;
        if (on < 0) return -1;
        for (int step = 1; step < past; step++) cell = neighbor(cell, on);
        return cell;
    }

    /** Neighbor across a side, or -1 past the innermost or outermost layer. */
    @Override
    public int neighbor(int cell, int side) {
        int k = k(cell);
        if (side == TOP) return k + 1 < layers ? cell + 1 : -1;
        if (side == BOTTOM) return k > 0 ? cell - 1 : -1;
        // Inside a face the grid is plain; only across a cube edge does geometry decide.
        int i = i(cell), j = j(cell);
        switch (side) {
            case I_MINUS -> { if (i > 0) return cell - n * layers; }
            case I_PLUS -> { if (i + 1 < n) return cell + n * layers; }
            case J_MINUS -> { if (j > 0) return cell - layers; }
            default -> { if (j + 1 < n) return cell + layers; }
        }
        Vector3d[] q = side(cell, side);
        Vector3d mid = new Vector3d(q[0]).add(q[1]).add(q[2]).add(q[3]).mul(0.25);
        Vector3d out = new Vector3d(mid).sub(center(cell)).mul(0.05).add(mid);
        // Keep the probe in this layer's shell: only the direction should change.
        out.normalize(mid.length());
        return cellAt(out);
    }

    private int grid(double tan) {
        int g = (int) Math.floor((Math.atan(tan) * 4 / Math.PI + 1) / 2 * n);
        return Math.max(0, Math.min(n - 1, g));
    }

    private static double dot(double[] a, Vector3d p) {
        return a[0] * p.x + a[1] * p.y + a[2] * p.z;
    }
}
