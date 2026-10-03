package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.voxel.CubeSphere;

/**
 * Where a planet's cells lie in the shadow dimension. Each cube face is a plain box of cells, so
 * it goes in as one: x along j, y the layer k, z along i (the cell's model axes, see CellSpace),
 * faces side by side along x. Around each face runs a halo one block wide holding copies of the
 * cells across its edges, so blocks at an edge see their real neighbors; the halo itself is only
 * written by GalaxyCraft. Each stage's planet has its own strip of z, far from anything a player
 * reaches.
 */
public final class ShadowMap {
    /** Blocks along x from one face's box to the next (a face has at most ~400 cells across). */
    public static final int STRIDE = 1024;
    public static final int Z_BASE = 20_000_000, SLOTS = 4096;
    /** By cell side: the step in the shadow dimension (x, y, z). */
    public static final int[][] STEP = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    public final CubeSphere grid;
    public final int z0;

    public ShadowMap(CubeSphere grid, String stage) {
        this.grid = grid;
        this.z0 = Z_BASE + Math.floorMod(stage.hashCode(), SLOTS) * STRIDE;
    }

    public int x(int cell) {
        return grid.face(cell) * STRIDE + 1 + grid.j(cell);
    }

    public int y(int cell) {
        return grid.k(cell);
    }

    public int z(int cell) {
        return z0 + 1 + grid.i(cell);
    }

    /** The cell at a position inside a face's box; -1 elsewhere (halo, gaps, above or below). */
    public int cell(int x, int y, int z) {
        int f = Math.floorDiv(x, STRIDE), j = x - f * STRIDE - 1, i = z - z0 - 1;
        if (f < 0 || f >= 6 || y < 0 || y >= grid.layers || i < 0 || i >= grid.n || j < 0 || j >= grid.n) return -1;
        return grid.index(f, i, j, y);
    }

    /** The cell a halo position copies (the one across the face's edge); -1 if it is no halo. */
    public int haloSource(int x, int y, int z) {
        int f = Math.floorDiv(x, STRIDE), j = x - f * STRIDE - 1, i = z - z0 - 1, n = grid.n;
        if (f < 0 || f >= 6 || y < 0 || y >= grid.layers) return -1;
        boolean iOut = i == -1 || i == n, jOut = j == -1 || j == n;
        if (iOut == jOut || i < -1 || i > n || j < -1 || j > n) return -1; // inside, a corner or beyond
        int side = i == -1 ? CubeSphere.I_MINUS : i == n ? CubeSphere.I_PLUS : j == -1 ? CubeSphere.J_MINUS : CubeSphere.J_PLUS;
        return grid.neighbor(grid.index(f, Math.clamp(i, 0, n - 1), Math.clamp(j, 0, n - 1), y), side);
    }

    /**
     * The halo positions that copy cell, {x, y, z} each: one per face edge it lies on whose
     * neighbor is on another face.
     */
    public int[][] halos(int cell) {
        int n = grid.n, i = grid.i(cell), j = grid.j(cell);
        int[] sides = {i == 0 ? CubeSphere.I_MINUS : -1, i == n - 1 ? CubeSphere.I_PLUS : -1,
                j == 0 ? CubeSphere.J_MINUS : -1, j == n - 1 ? CubeSphere.J_PLUS : -1};
        int[][] out = new int[4][];
        int count = 0;
        for (int side : sides) {
            if (side < 0) continue;
            int nb = grid.neighbor(cell, side);
            if (nb < 0) continue;
            for (int back = CubeSphere.I_MINUS; back <= CubeSphere.J_PLUS; back++)
                if (grid.neighbor(nb, back) == cell) {
                    out[count++] = new int[] {x(nb) + STEP[back][0], y(nb), z(nb) + STEP[back][2]};
                    break;
                }
        }
        return java.util.Arrays.copyOf(out, count);
    }

    /** Whether cell is on its face's edge (it has halo copies). */
    public boolean onEdge(int cell) {
        int n = grid.n, i = grid.i(cell), j = grid.j(cell);
        return i == 0 || j == 0 || i == n - 1 || j == n - 1;
    }

    /** Whether a position belongs to this planet's strip of the shadow dimension. */
    public boolean inStrip(int z) {
        return z >= z0 && z < z0 + STRIDE;
    }
}
