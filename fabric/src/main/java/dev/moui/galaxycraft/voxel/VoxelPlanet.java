package dev.moui.galaxycraft.voxel;

import java.util.BitSet;
import org.joml.Vector3d;

/**
 * The cells of one planet and which of its chunks need resending. A cell holds a block id of
 * {@link Blocks} (Minecraft's block state). Chunks are 8×8×8 cells within one face of the cube;
 * their index is the slot the game keeps them in.
 */
public final class VoxelPlanet {
    public static final int CHUNK = 8;
    public static final int MIN_RADIUS = 10, MAX_RADIUS = 256;

    public final CubeSphere grid;
    public final Blocks blocks;
    /** Blocks of solid ground under the grass top: the crust, with bedrock at its bottom. */
    public final int depth;
    private final char[] cells;
    private final int[] versions;
    private final int[] filled; // cells per chunk that are not air
    private final int[] solid;  // opaque full cubes per chunk (hiding everything around them)
    private final Fluids fluids;
    private final BitSet dirty = new BitSet();
    private final int chunksPerEdge, chunkLayers;
    private float[] spheres; // per chunk: center x y z (blocks), radius; computed on first use

    public VoxelPlanet(CubeSphere grid, int depth) {
        this(grid, depth, new char[grid.cellCount()], CubeBlocks.INSTANCE);
    }

    private VoxelPlanet(CubeSphere grid, int depth, char[] cells, Blocks blocks) {
        if (cells.length != grid.cellCount()) throw new IllegalArgumentException("cells do not fit the grid");
        this.grid = grid;
        this.blocks = blocks;
        this.depth = depth;
        this.cells = cells;
        this.chunksPerEdge = (grid.n + CHUNK - 1) / CHUNK;
        this.chunkLayers = (grid.layers + CHUNK - 1) / CHUNK;
        this.versions = new int[chunkCount()];
        this.filled = new int[chunkCount()];
        this.solid = new int[chunkCount()];
        this.fluids = new Fluids(this);
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] == Blocks.AIR) continue;
            filled[chunkOf(c)]++;
            BlockInfo b = info(c);
            if (b.occludes()) solid[chunkOf(c)]++;
            if (b.isFluid()) fluids.schedule(c); // flowing again where it was saved
        }
        dirty.set(0, chunkCount());
    }

    /** The hito-1 planet: bedrock at the bottom, stone, dirt, grass at radius 16, air up to 24. */
    public static VoxelPlanet standard() {
        return standard(CubeBlocks.INSTANCE);
    }

    public static VoxelPlanet standard(Blocks blocks) {
        return generate(new CubeSphere(24, 7, 17), 9, blocks);
    }

    /**
     * A planet whose grass is at radius blocks: cells about a block wide at the surface, a crust a
     * quarter of the radius deep (3 to 24 blocks; bedrock at its bottom seals the hollow center),
     * and room to build a quarter of the radius high (8 to 32 blocks). Cells narrow toward the
     * center; that depth keeps every one that can be dug at least 3/4 of a block wide, where Mario
     * still fits.
     */
    public static VoxelPlanet ofRadius(int radius) {
        return ofRadius(radius, CubeBlocks.INSTANCE);
    }

    public static VoxelPlanet ofRadius(int radius, Blocks blocks) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS)
            throw new IllegalArgumentException("radius " + radius + " not in " + MIN_RADIUS + ".." + MAX_RADIUS);
        // -Dgalaxycraft.crustDepth: deeper crusts, as planets saved before had (tools/gxfit.sh).
        int depth = Math.min(radius - 2, Integer.getInteger("galaxycraft.crustDepth", crustDepth(radius)));
        int air = Math.max(8, Math.min(32, radius / 4));
        int n = (int) Math.round(Math.PI * radius / 2);
        return generate(new CubeSphere(n, radius - depth, depth + air), depth, blocks);
    }

    /** How deep a planet of that radius can be dug, bedrock included (blocks). */
    public static int crustDepth(int radius) {
        return Math.max(3, Math.min(24, Math.round(radius / 4f)));
    }

    /**
     * A planet saved when crusts went deeper (half the radius): everything below today's crust
     * turns to bedrock, holes there included. Down there cells are under 0.45 blocks wide and Mario
     * gets wedged in them, held up and shaking. Returns how many cells changed.
     */
    public int sealBelowCrust() {
        int keep = crustDepth((int) Math.round(surface()));
        int changed = 0;
        int bedrock = blocks.id(Material.BEDROCK);
        for (int c = 0; c < cells.length; c++) {
            int k = grid.k(c);
            if (k < depth - keep && get(c) != bedrock) {
                set(c, Material.BEDROCK);
                changed++;
            }
        }
        return changed;
    }

    private static VoxelPlanet generate(CubeSphere grid, int depth, Blocks blocks) {
        char[] cells = new char[grid.cellCount()];
        char[] column = new char[grid.layers];
        for (int k = 0; k < grid.layers; k++) {
            Material m = k == 0 ? Material.BEDROCK : k == depth - 1 ? Material.GRASS
                    : k >= depth - 3 && k < depth ? Material.DIRT : k < depth ? Material.STONE : Material.AIR;
            column[k] = (char) blocks.id(m);
        }
        for (int c = 0; c < cells.length; c += grid.layers) System.arraycopy(column, 0, cells, c, grid.layers);
        return new VoxelPlanet(grid, depth, cells, blocks);
    }

    /** A planet as saved by {@link #cells()}, its ids those of blocks. */
    public static VoxelPlanet of(CubeSphere grid, int depth, char[] cells, Blocks blocks) {
        return new VoxelPlanet(grid, depth, cells, blocks);
    }

    /** The cells as stored (block ids): what gets saved. */
    public char[] cells() {
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

    /** The block id of a cell (air past the layers). */
    public int get(int cell) {
        return cell < 0 ? Blocks.AIR : cells[cell];
    }

    public BlockInfo info(int cell) {
        return blocks.info(get(cell));
    }

    /** Which of the planet's own blocks the cell is (water and lava at any level), or null. */
    public Material material(int cell) {
        return blocks.material(get(cell));
    }

    /** {@link Blocks#WATER}, {@link Blocks#LAVA} or {@link Blocks#NO_FLUID}. */
    public int fluid(int cell) {
        return info(cell).fluid();
    }

    /** A fluid's level (see {@link Fluids}); 0 for anything else. */
    public int level(int cell) {
        return info(cell).level();
    }

    /** An opaque full cube: hides the faces next to it, darkens corners. */
    public boolean occludes(int cell) {
        return info(cell).occludes();
    }

    /** Collides as a whole cell (the planet's cubes, glass...). */
    public boolean fullCollision(int cell) {
        return info(cell).fullCollision();
    }

    public Fluids fluids() {
        return fluids;
    }

    public void set(int cell, Material m) {
        set(cell, m, 0);
    }

    /** One of the planet's own blocks; a fluid at this level. */
    public void set(int cell, Material m, int level) {
        set(cell, m.fluid() ? blocks.fluidState(m.fluidKind(), level) : blocks.id(m));
    }

    /**
     * Sets a cell to a block id and marks its chunk and its neighbors' chunks (their faces change)
     * dirty; the fluids around it flow on their next tick.
     */
    public void set(int cell, int id) {
        char b = (char) id;
        char was = cells[cell];
        if (was == b) return;
        cells[cell] = b;
        int chunk = chunkOf(cell);
        filled[chunk] += (b != Blocks.AIR ? 1 : 0) - (was != Blocks.AIR ? 1 : 0);
        solid[chunk] += (blocks.info(b).occludes() ? 1 : 0) - (blocks.info(was).occludes() ? 1 : 0);
        dirty.set(chunk);
        for (int s = 0; s < 6; s++) {
            int nb = grid.neighbor(cell, s);
            if (nb >= 0) dirty.set(chunkOf(nb));
        }
        fluids.touched(cell);
    }

    /**
     * After the player changed these cells: their neighbors take the shape they should have now
     * (Blocks.updateShape), and so on outward from each that changed, a bounded number of times
     * (a broken door's lower half takes its upper one along).
     */
    public void settle(int... changed) {
        java.util.ArrayDeque<Integer> due = new java.util.ArrayDeque<>();
        for (int c : changed)
            for (int s = 0; s < 6; s++) due.add(grid.neighbor(c, s));
        for (int n = 0; n < 256 && !due.isEmpty(); n++) {
            int c = due.poll();
            if (c < 0 || get(c) == Blocks.AIR) continue;
            int next = blocks.updateShape(this, c);
            if (next == get(c)) continue;
            set(c, next);
            for (int s = 0; s < 6; s++) due.add(grid.neighbor(c, s));
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
     * Whether a chunk can have visible sides: something in it, and either some cell that is not a
     * block or a neighbor chunk that is not wholly blocks. Cheap; {@link PlanetMesher} gives the exact answer.
     */
    public boolean mayShow(int chunk) {
        if (filled[chunk] == 0) return false;
        int[] cs = cellsOf(chunk);
        if (solid[chunk] < cs.length) return true;
        for (int c : cs) {
            int i = grid.i(c) % CHUNK, j = grid.j(c) % CHUNK, k = grid.k(c) % CHUNK;
            boolean edge = i == 0 || j == 0 || k == 0 || i == CHUNK - 1 || j == CHUNK - 1 || k == CHUNK - 1
                    || grid.i(c) == grid.n - 1 || grid.j(c) == grid.n - 1 || grid.k(c) == grid.layers - 1;
            if (!edge) continue;
            for (int s = 0; s < 6; s++) {
                int nb = grid.neighbor(c, s);
                if (nb >= 0 && chunkOf(nb) != chunk && !occludes(nb)) return true;
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
