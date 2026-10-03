package dev.moui.galaxycraft.voxel;

import java.util.BitSet;

/**
 * The cells of one planet and which of its chunks need resending. Chunks are 8×8×8 cells within
 * one face of the cube; their index is the slot the game keeps them in.
 */
public final class VoxelPlanet {
    public static final int CHUNK = 8;

    public final CubeSphere grid;
    private final byte[] cells;
    private final int[] versions;
    private final BitSet dirty = new BitSet();
    private final int chunksPerEdge, chunkLayers;

    public VoxelPlanet(CubeSphere grid) {
        this.grid = grid;
        this.cells = new byte[grid.cellCount()];
        this.chunksPerEdge = (grid.n + CHUNK - 1) / CHUNK;
        this.chunkLayers = (grid.layers + CHUNK - 1) / CHUNK;
        this.versions = new int[chunkCount()];
    }

    /** The hito-1 planet: bedrock at the bottom, stone, dirt, grass at radius 16, air up to 24. */
    public static VoxelPlanet standard() {
        VoxelPlanet p = new VoxelPlanet(new CubeSphere(24, 7, 17));
        for (int c = 0; c < p.cells.length; c++) {
            int k = p.grid.k(c);
            Material m = k == 0 ? Material.BEDROCK : k <= 5 ? Material.STONE : k <= 7 ? Material.DIRT
                    : k == 8 ? Material.GRASS : Material.AIR;
            p.cells[c] = (byte) m.ordinal();
        }
        p.dirty.set(0, p.chunkCount());
        return p;
    }

    /** Radius of the grass surface, blocks. */
    public double surface() {
        return grid.radius(9);
    }

    public Material get(int cell) {
        return cell < 0 ? Material.AIR : Material.values()[cells[cell]];
    }

    /** Sets a cell and marks its chunk and its neighbors' chunks (their faces change) dirty. */
    public void set(int cell, Material m) {
        if (get(cell) == m) return;
        cells[cell] = (byte) m.ordinal();
        markDirty(chunkOf(cell));
        for (int s = 0; s < 6; s++) {
            int nb = grid.neighbor(cell, s);
            if (nb >= 0) markDirty(chunkOf(nb));
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

    public int version(int chunk) {
        return versions[chunk];
    }

    /** Chunks to send, each with its version bumped; clears the dirty set. */
    public int[] takeDirty() {
        int[] out = dirty.stream().toArray();
        for (int c : out) versions[c]++;
        dirty.clear();
        return out;
    }

    /** Everything is resent (the game lost it: new scene or reconnect). */
    public void markAllDirty() {
        dirty.set(0, chunkCount());
    }

    private void markDirty(int chunk) {
        dirty.set(chunk);
    }
}
