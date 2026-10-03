package dev.moui.galaxycraft.voxel;

import java.util.BitSet;
import org.joml.Vector3d;

/**
 * The cells of one planet and which of its chunks need resending. Chunks are 8×8×8 cells within
 * one face of the cube; their index is the slot the game keeps them in.
 */
public final class VoxelPlanet {
    public static final int CHUNK = 8;
    public static final int MIN_RADIUS = 10, MAX_RADIUS = 256;

    public final CubeSphere grid;
    /** Blocks of solid ground under the grass top: the crust, with bedrock at its bottom. */
    public final int depth;
    private final byte[] cells;
    private final int[] versions;
    private final int[] solid; // solid cells per chunk
    private final BitSet dirty = new BitSet();
    private final int chunksPerEdge, chunkLayers;
    private float[] spheres; // per chunk: center x y z (blocks), radius; computed on first use

    public VoxelPlanet(CubeSphere grid, int depth) {
        this(grid, depth, new byte[grid.cellCount()]);
    }

    private VoxelPlanet(CubeSphere grid, int depth, byte[] cells) {
        if (cells.length != grid.cellCount()) throw new IllegalArgumentException("cells do not fit the grid");
        this.grid = grid;
        this.depth = depth;
        this.cells = cells;
        this.chunksPerEdge = (grid.n + CHUNK - 1) / CHUNK;
        this.chunkLayers = (grid.layers + CHUNK - 1) / CHUNK;
        this.versions = new int[chunkCount()];
        this.solid = new int[chunkCount()];
        for (int c = 0; c < cells.length; c++) if (cells[c] != 0) solid[chunkOf(c)]++;
        dirty.set(0, chunkCount());
    }

    /** The hito-1 planet: bedrock at the bottom, stone, dirt, grass at radius 16, air up to 24. */
    public static VoxelPlanet standard() {
        return generate(new CubeSphere(24, 7, 17), 9);
    }

    /**
     * A planet whose grass is at radius blocks: cells about a block wide at the surface, a crust
     * up to 24 blocks deep (bedrock at its bottom seals the hollow center), and room to build a
     * quarter of the radius high (8 to 32 blocks).
     */
    public static VoxelPlanet ofRadius(int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS)
            throw new IllegalArgumentException("radius " + radius + " not in " + MIN_RADIUS + ".." + MAX_RADIUS);
        int depth = Math.min(radius - 7, 24);
        int air = Math.max(8, Math.min(32, radius / 4));
        int n = (int) Math.round(Math.PI * radius / 2);
        return generate(new CubeSphere(n, radius - depth, depth + air), depth);
    }

    private static VoxelPlanet generate(CubeSphere grid, int depth) {
        byte[] cells = new byte[grid.cellCount()];
        byte[] column = new byte[grid.layers];
        for (int k = 0; k < grid.layers; k++) {
            Material m = k == 0 ? Material.BEDROCK : k == depth - 1 ? Material.GRASS
                    : k >= depth - 3 && k < depth ? Material.DIRT : k < depth ? Material.STONE : Material.AIR;
            column[k] = (byte) m.ordinal();
        }
        for (int c = 0; c < cells.length; c += grid.layers) System.arraycopy(column, 0, cells, c, grid.layers);
        return new VoxelPlanet(grid, depth, cells);
    }

    /** A planet as saved by {@link #cells()}. */
    public static VoxelPlanet of(CubeSphere grid, int depth, byte[] cells) {
        return new VoxelPlanet(grid, depth, cells);
    }

    /** The cells as stored (one Material ordinal per cell): what gets saved. */
    public byte[] cells() {
        return cells;
    }

    /** Radius of the grass surface, blocks. */
    public double surface() {
        return grid.radius(depth);
    }

    /** Radius of the ball nothing can dig into (inside the bedrock), blocks. */
    public double occluder() {
        return grid.core;
    }

    public Material get(int cell) {
        return cell < 0 ? Material.AIR : Material.values()[cells[cell]];
    }

    /** Sets a cell and marks its chunk and its neighbors' chunks (their faces change) dirty. */
    public void set(int cell, Material m) {
        Material old = get(cell);
        if (old == m) return;
        cells[cell] = (byte) m.ordinal();
        solid[chunkOf(cell)] += (m.solid() ? 1 : 0) - (old.solid() ? 1 : 0);
        dirty.set(chunkOf(cell));
        for (int s = 0; s < 6; s++) {
            int nb = grid.neighbor(cell, s);
            if (nb >= 0) dirty.set(chunkOf(nb));
        }
    }

    public int chunkCount() {
        return 6 * chunksPerEdge * chunksPerEdge * chunkLayers;
    }

    public int chunkOf(int cell) {
        return ((grid.face(cell) * chunksPerEdge + grid.i(cell) / CHUNK) * chunksPerEdge + grid.j(cell) / CHUNK)
                * chunkLayers + grid.k(cell) / CHUNK;
    }

    /** Cells of a chunk, in index order. */
    public int[] cellsOf(int chunk) {
        int ck = chunk % chunkLayers, cj = chunk / chunkLayers % chunksPerEdge;
        int ci = chunk / (chunkLayers * chunksPerEdge) % chunksPerEdge, f = chunk / (chunkLayers * chunksPerEdge * chunksPerEdge);
        int[] out = new int[CHUNK * CHUNK * CHUNK];
        int n = 0;
        for (int i = ci * CHUNK; i < Math.min(grid.n, ci * CHUNK + CHUNK); i++)
            for (int j = cj * CHUNK; j < Math.min(grid.n, cj * CHUNK + CHUNK); j++)
                for (int k = ck * CHUNK; k < Math.min(grid.layers, ck * CHUNK + CHUNK); k++)
                    out[n++] = grid.index(f, i, j, k);
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Whether a chunk can have visible sides: some solid cell, and either some air in it or a
     * neighbor chunk that is not wholly solid. Cheap; {@link PlanetMesher} gives the exact answer.
     */
    public boolean mayShow(int chunk) {
        if (solid[chunk] == 0) return false;
        int[] cs = cellsOf(chunk);
        if (solid[chunk] < cs.length) return true;
        for (int c : cs) {
            int i = grid.i(c) % CHUNK, j = grid.j(c) % CHUNK, k = grid.k(c) % CHUNK;
            boolean edge = i == 0 || j == 0 || k == 0 || i == CHUNK - 1 || j == CHUNK - 1 || k == CHUNK - 1
                    || grid.i(c) == grid.n - 1 || grid.j(c) == grid.n - 1 || grid.k(c) == grid.layers - 1;
            if (!edge) continue;
            for (int s = 0; s < 6; s++) {
                int nb = grid.neighbor(c, s);
                if (nb >= 0 && chunkOf(nb) != chunk && !get(nb).solid()) return true;
                if (nb < 0 && s == CubeSphere.TOP) return true;
            }
        }
        return false;
    }

    /** Bounding sphere of a chunk: center (blocks, from the planet's center) and radius. */
    public void sphere(int chunk, Vector3d center, double[] radius) {
        if (spheres == null) {
            spheres = new float[4 * chunkCount()];
            for (int ch = 0; ch < chunkCount(); ch++) computeSphere(ch);
        }
        center.set(spheres[4 * chunk], spheres[4 * chunk + 1], spheres[4 * chunk + 2]);
        radius[0] = spheres[4 * chunk + 3];
    }

    private void computeSphere(int chunk) {
        int[] cs = cellsOf(chunk);
        int first = cs[0], last = cs[cs.length - 1], f = grid.face(first);
        int i0 = grid.i(first), i1 = grid.i(last) + 1, j0 = grid.j(first), j1 = grid.j(last) + 1;
        double r0 = grid.radius(grid.k(first)), r1 = grid.radius(grid.k(last) + 1);
        // The corners of the chunk and the middle of its outer face (where the shell bulges).
        Vector3d[] pts = new Vector3d[10];
        int n = 0;
        for (int m = 0; m < 4; m++) {
            Vector3d d = grid.dir(f, (m & 1) == 0 ? i0 : i1, (m & 2) == 0 ? j0 : j1);
            pts[n++] = new Vector3d(d).mul(r0);
            pts[n++] = new Vector3d(d).mul(r1);
        }
        Vector3d mid = grid.dir(f, (i0 + i1) / 2, (j0 + j1) / 2);
        pts[n++] = new Vector3d(mid).mul(r0);
        pts[n++] = new Vector3d(mid).mul(r1);
        Vector3d c = new Vector3d();
        for (Vector3d p : pts) c.add(p);
        c.mul(1.0 / pts.length);
        double r = 0;
        for (Vector3d p : pts) r = Math.max(r, p.distance(c));
        r += 0.25;
        spheres[4 * chunk] = (float) c.x;
        spheres[4 * chunk + 1] = (float) c.y;
        spheres[4 * chunk + 2] = (float) c.z;
        spheres[4 * chunk + 3] = (float) r;
    }

    public int version(int chunk) {
        return versions[chunk];
    }

    /** Next version of a chunk, for a message about to be sent. */
    public int bump(int chunk) {
        return ++versions[chunk];
    }

    /** Chunks changed since last asked; clears the set. */
    public int[] takeDirty() {
        int[] out = dirty.stream().toArray();
        dirty.clear();
        return out;
    }

    /** Everything is resent (the game lost it: new scene or reconnect). */
    public void markAllDirty() {
        dirty.set(0, chunkCount());
    }
}
