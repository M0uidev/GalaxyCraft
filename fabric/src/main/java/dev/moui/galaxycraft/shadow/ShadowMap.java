package dev.moui.galaxycraft.shadow;

import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.CubeSphere;
import org.joml.Matrix3d;
import org.joml.Vector3d;

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

    /** The overworld's surface: where a planet's ground goes in the shadow (SURFACE_Y - depth is its layer 0). */
    public static final int SURFACE_Y = 64;

    public final CubeSphere grid;
    public final int z0;
    /** The shadow's y of the planet's layer 0. */
    public final int y0;

    public ShadowMap(CubeSphere grid, String stage) {
        this(grid, stage, 0);
    }

    /**
     * y0: where layer 0 goes. A planet's ground at the overworld's height (y0 = SURFACE_Y - depth):
     * Minecraft's rules that go by height work as on its surface (no slimes of slime chunks,
     * which need y under 40; swamp slimes at night, which need 51 to 69).
     */
    public ShadowMap(CubeSphere grid, String stage, int y0) {
        this.grid = grid;
        this.z0 = Z_BASE + Math.floorMod(stage.hashCode(), SLOTS) * STRIDE;
        this.y0 = y0;
    }

    /** The shadow map of a planet: its ground at the overworld's surface height. */
    public static ShadowMap of(dev.moui.galaxycraft.voxel.VoxelPlanet p, String stage) {
        return new ShadowMap(p.grid, stage, Math.max(0, SURFACE_Y - p.depth));
    }

    public int x(int cell) {
        return grid.face(cell) * STRIDE + 1 + grid.j(cell);
    }

    public int y(int cell) {
        return y0 + grid.k(cell);
    }

    public int z(int cell) {
        return z0 + 1 + grid.i(cell);
    }

    /** The cell at a position inside a face's box; -1 elsewhere (halo, gaps, above or below). */
    public int cell(int x, int y, int z) {
        y -= y0;
        int f = Math.floorDiv(x, STRIDE), j = x - f * STRIDE - 1, i = z - z0 - 1;
        if (f < 0 || f >= 6 || y < 0 || y >= grid.layers || i < 0 || i >= grid.n || j < 0 || j >= grid.n) return -1;
        return grid.index(f, i, j, y);
    }

    /** The cell a halo position copies (the one across the face's edge); -1 if it is no halo. */
    public int haloSource(int x, int y, int z) {
        y -= y0;
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

    /**
     * The planet around a shadow position on a face's box, its halo or just past it: {planet
     * point (blocks), then the planet's step per shadow block along x, along y, along z}, from
     * the face's nearest cell (extrapolated past it). Null if the position is near no face.
     */
    public double[] frame(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), f = Math.floorDiv(bx, STRIDE), n = grid.n;
        int j = bx - f * STRIDE - 1, i = bz - z0 - 1;
        if (f < 0 || f >= 6 || j < -2 || j > n + 1 || i < -2 || i > n + 1) return null;
        int cell = grid.index(f, Math.clamp(i, 0, n - 1), Math.clamp(j, 0, n - 1), Math.clamp((int) Math.floor(y) - y0, 0, grid.layers - 1));
        double fx = x - x(cell), fy = y - y(cell), fz = z - z(cell);
        Vector3d o = CellSpace.point(grid, cell, fx, fy, fz);
        Vector3d ax = CellSpace.point(grid, cell, fx + 1, fy, fz).sub(o);
        Vector3d ay = CellSpace.point(grid, cell, fx, fy + 1, fz).sub(o);
        Vector3d az = CellSpace.point(grid, cell, fx, fy, fz + 1).sub(o);
        return new double[] {o.x, o.y, o.z, ax.x, ax.y, ax.z, ay.x, ay.y, ay.z, az.x, az.y, az.z};
    }

    /** Where something past its face's edge goes on the next face, and how its directions turn. */
    public record Wrap(double x, double y, double z, Matrix3d turn) {}

    /**
     * Something at (x, y, z) in a face's halo or past it (it walked off the face's edge): the
     * same planet point on the face it is over now, so it walks on around the planet. Null if it
     * is still on its face (or near none, or beyond the planet's layers).
     */
    public Wrap wrap(double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), f = Math.floorDiv(bx, STRIDE), n = grid.n;
        int j = bx - f * STRIDE - 1, i = bz - z0 - 1;
        if (j >= 0 && j < n && i >= 0 && i < n) return null;
        double[] a = frame(x, y, z);
        if (a == null || y < y0 || y >= y0 + grid.layers) return null;
        Vector3d p = new Vector3d(a[0], a[1], a[2]);
        int cell = grid.cellAt(p);
        if (cell < 0 || grid.face(cell) == f) return null;
        Vector3d m = CellSpace.local(grid, cell, p);
        double nx = x(cell) + m.x, ny = y(cell) + m.y, nz = z(cell) + m.z;
        double[] b = frame(nx, ny, nz);
        if (b == null) return null;
        Matrix3d from = new Matrix3d(a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11]);
        Matrix3d to = new Matrix3d(b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11]);
        return new Wrap(nx, ny, nz, to.invert().mul(from));
    }

    /** Whether a position belongs to this planet's strip of the shadow dimension. */
    public boolean inStrip(int z) {
        return z >= z0 && z < z0 + STRIDE;
    }
}
