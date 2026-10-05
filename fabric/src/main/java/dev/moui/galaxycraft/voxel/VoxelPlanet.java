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
    private Listener listener; // told of every change but those set quietly
    private final java.util.Map<Long, Boolean> sameLook = new java.util.HashMap<>();
    private PlanetBiomes biomes = PlanetBiomes.uniform(PlanetBiomes.PLAINS);
    private final PlanetLight light;
    /** Per biome color kind, per column: the color blended over the columns around (0: not yet). */
    private final int[][] blended = new int[PlanetBiomes.KINDS][];
    /** Columns each way a biome color is averaged over (Minecraft's biome blend, 5 x 5 by default). */
    public static final int BIOME_BLEND = Integer.getInteger("galaxycraft.biomeBlend", 2);

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
        }
        this.light = new PlanetLight(this);
        light.computeAll();
        light.onChange(this::lightChanged);
        // Flowing again where it was saved, but only what can change: a sea of still water would
        // otherwise keep the fluid ticks busy for a while after every load.
        for (int c = 0; c < cells.length; c++)
            if (cells[c] != Blocks.AIR && info(c).isFluid() && unsettled(c)) fluids.schedule(c);
        dirty.set(0, chunkCount());
    }

    /**
     * Whether a fluid cell may change on its next tick: it is flowing, or something it flows into
     * (air, a block without collision) is below or beside it, or the other fluid touches it.
     */
    private boolean unsettled(int c) {
        BlockInfo me = info(c);
        if (me.level() != 0) return true;
        for (int side = 0; side < 6; side++) {
            int nb = grid.neighbor(c, side);
            if (nb < 0) continue;
            int id = get(nb);
            BlockInfo b = id == Blocks.AIR ? null : info(nb);
            if (b != null && b.isFluid() && b.fluid() != me.fluid()) return true;
            if (side != CubeSphere.TOP && (b == null || !b.isFluid() && !b.collides())) return true;
        }
        return false;
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
        int[] surfaceDown = {blocks.id(Material.GRASS), blocks.id(Material.DIRT), blocks.id(Material.DIRT), blocks.id(Material.STONE)};
        return layered(radius, defaultAir(radius), surfaceDown, blocks);
    }

    /**
     * A planet of that radius with air blocks of room above the surface, its crust as
     * {@link #ofRadius} has it: surfaceDown[i] is the block i blocks below the surface (the top
     * block at 0), the last one down to the bedrock that seals the bottom.
     */
    public static VoxelPlanet layered(int radius, int air, int[] surfaceDown, Blocks blocks) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS)
            throw new IllegalArgumentException("radius " + radius + " not in " + MIN_RADIUS + ".." + MAX_RADIUS);
        if (surfaceDown.length == 0) throw new IllegalArgumentException("no layers");
        int depth = groundDepth(radius);
        CubeSphere grid = new CubeSphere(gridSize(radius), radius - depth, depth + air);
        char[] column = new char[grid.layers];
        for (int k = 0; k < grid.layers; k++)
            column[k] = (char) (k == 0 ? blocks.id(Material.BEDROCK) : k >= depth ? Blocks.AIR
                    : surfaceDown[Math.min(depth - 1 - k, surfaceDown.length - 1)]);
        return fill(grid, depth, column, blocks);
    }

    /** The crust a new planet of that radius gets (blocks, bedrock included). */
    public static int groundDepth(int radius) {
        // -Dgalaxycraft.crustDepth: deeper crusts, as planets saved before had (tools/gxfit.sh).
        return Math.min(radius - 2, Integer.getInteger("galaxycraft.crustDepth", crustDepth(radius)));
    }

    /** Cells along a face's edge for a planet of that radius: about a block wide at the surface. */
    public static int gridSize(int radius) {
        return (int) Math.round(Math.PI * radius / 2);
    }

    /** Room to build above a planet of that radius: a quarter of it, 8 to 32 blocks. */
    public static int defaultAir(int radius) {
        return Math.max(8, Math.min(32, radius / 4));
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
        char[] column = new char[grid.layers];
        for (int k = 0; k < grid.layers; k++) {
            Material m = k == 0 ? Material.BEDROCK : k == depth - 1 ? Material.GRASS
                    : k >= depth - 3 && k < depth ? Material.DIRT : k < depth ? Material.STONE : Material.AIR;
            column[k] = (char) blocks.id(m);
        }
        return fill(grid, depth, column, blocks);
    }

    private static VoxelPlanet fill(CubeSphere grid, int depth, char[] column, Blocks blocks) {
        char[] cells = new char[grid.cellCount()];
        for (int c = 0; c < cells.length; c += grid.layers) System.arraycopy(column, 0, cells, c, grid.layers);
        return new VoxelPlanet(grid, depth, cells, blocks);
    }

    /** A planet as saved by {@link #cells()}, its ids those of blocks. */
    public static VoxelPlanet of(CubeSphere grid, int depth, char[] cells, Blocks blocks) {
        return new VoxelPlanet(grid, depth, cells, blocks);
    }

    /** Sky and block light of every cell (Minecraft's levels, 0 to 15). */
    public PlanetLight light() {
        return light;
    }

    /** A cell's light changed: what shows its light (its chunk, and its neighbors' faces toward it) is drawn anew. */
    private void lightChanged(int cell) {
        dirty.set(chunkOf(cell));
        int i = grid.i(cell) % CHUNK, j = grid.j(cell) % CHUNK, k = grid.k(cell) % CHUNK;
        if (i == 0 || j == 0 || k == 0 || i == CHUNK - 1 || j == CHUNK - 1 || k == CHUNK - 1)
            for (int s = 0; s < 6; s++) {
                int nb = grid.neighbor(cell, s);
                if (nb >= 0) dirty.set(chunkOf(nb));
            }
    }

    public PlanetBiomes biomes() {
        return biomes;
    }

    public void setBiomes(PlanetBiomes b) {
        if (b == null) b = PlanetBiomes.uniform(PlanetBiomes.PLAINS);
        if (!b.uniform() && b.columns().length != 6 * grid.n * grid.n) throw new IllegalArgumentException("biomes do not fit the grid");
        biomes = b;
        java.util.Arrays.fill(blended, null);
    }

    /** The biome of a cell's column. */
    public String biome(int cell) {
        return biomes.at(cell / grid.layers);
    }

    /**
     * The color a quad of cell's tinted tint is drawn with: a biome color (see {@link PlanetBiomes})
     * as the biomes around that column blend it, as Minecraft blends them; any other as it is.
     */
    public int tint(int cell, int tint) {
        int kind = PlanetBiomes.kind(tint);
        if (kind == PlanetBiomes.FIXED || kind >= PlanetBiomes.KINDS) return tint & 0xFFFFFF;
        int col = cell / grid.layers;
        int[] cache = blended[kind];
        if (cache == null) blended[kind] = cache = new int[6 * grid.n * grid.n];
        int c = cache[col];
        if (c == 0) cache[col] = c = 0x1000000 | blend(col, kind, tint & 0xFFFFFF);
        return c & 0xFFFFFF;
    }

    private int blend(int col, int kind, int fallback) {
        if (biomes.uniform() && kind != PlanetBiomes.GRASS) return color(biomes.at(0), kind, col, fallback);
        int r = 0, g = 0, b = 0, n = 0;
        int start = col * grid.layers;
        for (int a = -BIOME_BLEND; a <= BIOME_BLEND; a++) {
            int row = walk(start, a < 0 ? CubeSphere.I_MINUS : CubeSphere.I_PLUS, Math.abs(a));
            for (int d = -BIOME_BLEND; d <= BIOME_BLEND && row >= 0; d++) {
                int c = walk(row, d < 0 ? CubeSphere.J_MINUS : CubeSphere.J_PLUS, Math.abs(d));
                if (c < 0) continue;
                int rgb = color(biomes.at(c / grid.layers), kind, c / grid.layers, fallback);
                r += rgb >> 16 & 0xFF;
                g += rgb >> 8 & 0xFF;
                b += rgb & 0xFF;
                n++;
            }
        }
        return n == 0 ? fallback : (r / n) << 16 | (g / n) << 8 | b / n;
    }

    private int walk(int cell, int side, int steps) {
        for (int s = 0; s < steps && cell >= 0; s++) cell = grid.neighbor(cell, side);
        return cell;
    }

    private int color(String biome, int kind, int col, int fallback) {
        int face = col / (grid.n * grid.n), i = col / grid.n % grid.n, j = col % grid.n;
        int c = blocks.biomeColor(biome, kind, face * 1024 + j, i);
        return c < 0 ? fallback : c & 0xFFFFFF;
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
        if (blocks.lightBlock(was) != blocks.lightBlock(b) || blocks.lightEmission(was) != blocks.lightEmission(b))
            light.update(cell);
        if (sameLook(was, b)) { // nothing to draw or collide anew
            if (listener != null) listener.changed(cell, b);
            return;
        }
        int chunk = chunkOf(cell);
        filled[chunk] += (b != Blocks.AIR ? 1 : 0) - (was != Blocks.AIR ? 1 : 0);
        solid[chunk] += (blocks.info(b).occludes() ? 1 : 0) - (blocks.info(was).occludes() ? 1 : 0);
        dirty.set(chunk);
        for (int s = 0; s < 6; s++) {
            int nb = grid.neighbor(cell, s);
            if (nb >= 0) dirty.set(chunkOf(nb));
        }
        fluids.touched(cell);
        if (listener != null) listener.changed(cell, b);
    }

    public interface Listener {
        void changed(int cell, int id);
    }

    /**
     * Whether a and b are states of one block that look and collide the same, so a change from
     * one to the other leaves the meshes as they are. Minecraft's own updates on the planet (a
     * leaf's distance to its log) would otherwise mesh and send its chunk again and again.
     */
    private boolean sameLook(char a, char b) {
        if (a == Blocks.AIR || b == Blocks.AIR) return false;
        long key = (long) Math.min(a, b) << 16 | Math.max(a, b);
        Boolean same = sameLook.get(key);
        if (same == null) {
            String na = blocks.name(a), nb = blocks.name(b);
            int ia = na.indexOf('['), ib = nb.indexOf('[');
            same = (ia < 0 ? na : na.substring(0, ia)).equals(ib < 0 ? nb : nb.substring(0, ib)) && blocks.info(a).sameAs(blocks.info(b));
            sameLook.put(key, same);
        }
        return same;
    }

    /** Told (cell, id) of every change to a cell except those made by {@link #setQuietly}. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Sets a cell without telling the listener (the change came from where it listens for). */
    public void setQuietly(int cell, int id) {
        var l = listener;
        listener = null;
        try {
            set(cell, id);
        } finally {
            listener = l;
        }
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

    /** Chunks along a face's edge. */
    public int chunksPerEdge() {
        return chunksPerEdge;
    }

    /** Chunks from the bottom layer to the top. */
    public int chunkLayers() {
        return chunkLayers;
    }

    /** The cube face a chunk is on. */
    public int faceOfChunk(int chunk) {
        return chunk / (chunkLayers * chunksPerEdge * chunksPerEdge);
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
