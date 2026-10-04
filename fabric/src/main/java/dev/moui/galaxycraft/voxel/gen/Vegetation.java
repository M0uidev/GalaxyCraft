package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * Trees, flowers and grass on a generated planet: what Minecraft decorates a chunk of each biome
 * with (client/McVegetation runs its features on a flat floor and keeps what grew), laid on the
 * planet a 16×16 patch of a face at a time. Each thing is laid on the grid itself, its axes the
 * shadow dimension's (x along j, z along i, y up the layers), so it grows there as Minecraft grew it.
 */
public final class Vegetation {
    public static final int CHUNK = 16;

    /**
     * One thing that grew: its base in the chunk (ax, az), then its blocks above the floor (dy ≥
     * 1) and the floor it changed under them (dy 0: dirt under a trunk).
     */
    public record Thing(int ax, int az, int[] dx, int[] dy, int[] dz, String[] blocks) {}

    /** What grew on one chunk. */
    public record Patch(List<Thing> things) {}

    /** The patches a biome grows (a few, from different seeds); empty if nothing grows there. Safe from any thread. */
    public interface Library {
        List<Patch> patches(String biome);
    }

    private Vegetation() {}

    /**
     * What grew on a flat floor, split into things. grown[x][y][z] is the block there or null: y 0
     * is the floor (only where something changed it, as dirt under a trunk), and x and z run margin
     * blocks past the chunk on each side. Every block on the floor is a stem, and every other block
     * goes with its nearest stem (sides or corners), so trees whose crowns meet stay apart. Stems
     * side by side that both rise past one block (a 2×2 trunk) are one thing: a giant tree is
     * planted whole, on one spot's ground. Things whose stem stands past the chunk belong to its
     * neighbors. In a fixed order: the same seed plants the same planet.
     */
    public static List<Thing> things(String[][][] grown, int margin) {
        int w = grown.length, h = grown[0].length;
        // Stems, joined into trees: union-find over the floor's columns.
        int[] root = new int[w * w];
        for (int c = 0; c < root.length; c++) root[c] = c;
        for (int x = 0; x < w; x++)
            for (int z = 0; z < w; z++) {
                if (!tall(grown, x, z)) continue;
                for (int ox = -1; ox <= 1; ox++)
                    for (int oz = -1; oz <= 1; oz++) {
                        int nx = x + ox, nz = z + oz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= w || !tall(grown, nx, nz)) continue;
                        int a = find(root, x * w + z), b = find(root, nx * w + nz);
                        root[Math.max(a, b)] = Math.min(a, b); // the lowest column is the tree's base
                    }
            }
        // Grown out from every stem at once: each block is its nearest stem's tree.
        int[] tree = new int[w * h * w];
        Arrays.fill(tree, -1);
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        for (int x = 0; x < w; x++)
            for (int z = 0; z < w; z++)
                if (h > 1 && grown[x][1][z] != null) {
                    int q = (x * h + 1) * w + z;
                    tree[q] = find(root, x * w + z);
                    todo.add(q);
                }
        while (!todo.isEmpty()) {
            int q = todo.poll(), x = q / (h * w), y = q / w % h, z = q % w;
            for (int ox = -1; ox <= 1; ox++)
                for (int oy = -1; oy <= 1; oy++)
                    for (int oz = -1; oz <= 1; oz++) {
                        int nx = x + ox, ny = y + oy, nz = z + oz;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= w || ny < 1 || ny >= h || grown[nx][ny][nz] == null) continue;
                        int r = (nx * h + ny) * w + nz;
                        if (tree[r] >= 0) continue;
                        tree[r] = tree[q];
                        todo.add(r);
                    }
        }
        // The floor a thing changed right under its stems goes with it, or grass under a log
        // would turn to dirt later on the planet.
        for (int x = 0; x < w; x++)
            for (int z = 0; z < w; z++)
                if (h > 1 && grown[x][0][z] != null && grown[x][1][z] != null) tree[x * h * w + z] = tree[(x * h + 1) * w + z];
        Map<Integer, List<Integer>> parts = new TreeMap<>();
        for (int q = 0; q < tree.length; q++) if (tree[q] >= 0) parts.computeIfAbsent(tree[q], k -> new ArrayList<>()).add(q);
        List<Thing> things = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : parts.entrySet()) {
            int bx = e.getKey() / w, bz = e.getKey() % w;
            if (bx < margin || bx >= margin + CHUNK || bz < margin || bz >= margin + CHUNK) continue;
            List<Integer> part = e.getValue();
            int[] dx = new int[part.size()], dy = new int[part.size()], dz = new int[part.size()];
            String[] blocks = new String[part.size()];
            for (int b = 0; b < part.size(); b++) {
                int q = part.get(b), x = q / (h * w), y = q / w % h, z = q % w;
                dx[b] = x - bx;
                dy[b] = y;
                dz[b] = z - bz;
                blocks[b] = grown[x][y][z];
            }
            things.add(new Thing(bx - margin, bz - margin, dx, dy, dz, blocks));
        }
        return things;
    }

    /** A stem that rises past one block: a trunk, not a flower or a tuft. */
    private static boolean tall(String[][][] grown, int x, int z) {
        return grown[0].length > 2 && grown[x][1][z] != null && grown[x][2][z] != null;
    }

    private static int find(int[] root, int c) {
        while (root[c] != c) c = root[c] = root[root[c]];
        return c;
    }

    /**
     * Plants patches on every face's 16×16 squares. plants: percent of Minecraft's amount (100 one
     * patch per square, 200 two). A thing is planted only where its base is its biome's top block
     * with nothing on it (or the biome's cover), and its blocks go only into air or cover.
     */
    static void plant(CubeSphere grid, int depth, char[] cells, int[] height, String[] biome,
            boolean[] bare, Library library, long seed, int plants, ToIntFunction<String> ids) {
        if (plants <= 0 || library == null) return;
        int n = grid.n;
        Random rnd = new Random(seed * 131 + 17);
        for (int f = 0; f < 6; f++)
            for (int i0 = 0; i0 < n; i0 += CHUNK)
                for (int j0 = 0; j0 < n; j0 += CHUNK)
                    for (int pass = 0; pass * 100 < plants; pass++) {
                        double keep = Math.min(1, (plants - pass * 100) / 100.0);
                        int mid = (f * n + Math.min(n - 1, i0 + CHUNK / 2)) * n + Math.min(n - 1, j0 + CHUNK / 2);
                        List<Patch> patches = library.patches(biome[mid]);
                        if (patches.isEmpty()) continue;
                        Patch patch = patches.get(rnd.nextInt(patches.size()));
                        for (Thing t : patch.things()) {
                            if (rnd.nextDouble() >= keep) continue;
                            int i = i0 + t.az(), j = j0 + t.ax();
                            if (i >= n || j >= n) continue;
                            int col = (f * n + i) * n + j;
                            if (!bare[col] || !biome[col].equals(biome[mid])) continue;
                            place(grid, depth, cells, height, f, i, j, col, t, ids);
                        }
                    }
    }

    /**
     * One thing on the ground of column (f, i, j), laid on the grid itself. Inside a face that
     * keeps every block and its neighbors as Minecraft grew them (cells widen with height, so the
     * thing does too, as everything built there); past a face's edge it carries on into the next
     * face's cells. As Minecraft grows a tree only where it fits, it is planted whole or not at
     * all: not where it would reach past the sky or round a cube's corner, and only with every
     * stem in the open on the same ground as its base, or a block lower where its dirt fills the
     * step (no trunk half buried in a hill or hanging over a drop). The rest goes only into air or
     * cover, so a hill cuts into a crown as in Minecraft. Whether it was planted.
     */
    static boolean place(CubeSphere grid, int depth, char[] cells, int[] height, int f, int i, int j, int col, Thing t,
            ToIntFunction<String> ids) {
        int ground = depth - 1 + height[col]; // the top block of the ground: dy 0
        char top = cells[col * grid.layers + ground];
        int[] at = new int[t.blocks().length];
        Set<Integer> floor = new HashSet<>(); // the cells it puts floor in (dy 0)
        for (int b = 0; b < at.length; b++) {
            at[b] = grid.cellBeyond(f, i + t.dz()[b], j + t.dx()[b], ground + t.dy()[b]);
            if (at[b] < 0) return false;
            if (t.dy()[b] == 0) floor.add(at[b]);
        }
        for (int b = 0; b < at.length; b++) {
            if (t.dy()[b] != 1) continue;
            int under = at[b] - 1;
            if (!open(cells[at[b]], ids)) return false;
            if (cells[under] == top) continue;
            // A block lower, as Minecraft lets a giant trunk stand: its dirt fills the step.
            if (!floor.contains(under) || !open(cells[under], ids) || cells[under - 1] != top) return false;
        }
        for (int b = 0; b < at.length; b++) {
            int cell = at[b];
            if (t.dy()[b] != 0 && !open(cells[cell], ids)) continue;
            cells[cell] = (char) ids.applyAsInt(t.blocks()[b]);
        }
        return true;
    }

    /** Air, or snow cover: what a plant grows into. */
    private static boolean open(char id, ToIntFunction<String> ids) {
        return id == Blocks.AIR || id == ids.applyAsInt("minecraft:snow");
    }
}
