package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.joml.Vector3d;

/**
 * Trees, flowers and grass on a generated planet: what Minecraft decorates a chunk of each biome
 * with (client/McVegetation runs its features on a flat floor and keeps what grew), laid on the
 * planet a 16×16 patch of a face at a time. Each thing stands on the ground under its base, built
 * along that spot's up and its face's two directions, so it is upright anywhere on the sphere.
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
     * Plants patches on every face's 16×16 squares. plants: percent of Minecraft's amount (100 one
     * patch per square, 200 two). A thing is planted only where its base is its biome's top block
     * with nothing on it (or the biome's cover), and its blocks go only into air or cover.
     */
    static void plant(CubeSphere grid, int depth, char[] cells, int[] height, float[] dirs, String[] biome,
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
                            int i = i0 + t.ax(), j = j0 + t.az();
                            if (i >= n || j >= n) continue;
                            int col = (f * n + i) * n + j;
                            if (!bare[col] || !biome[col].equals(biome[mid])) continue;
                            place(grid, depth, cells, height, dirs, f, i, j, col, t, ids);
                        }
                    }
    }

    private static void place(CubeSphere grid, int depth, char[] cells, int[] height, float[] dirs, int f, int i, int j,
            int col, Thing t, ToIntFunction<String> ids) {
        Vector3d up = new Vector3d(dirs[3 * col], dirs[3 * col + 1], dirs[3 * col + 2]);
        // The face's directions here, made square to up.
        Vector3d e1 = grid.dir(f, i + 1, j).sub(grid.dir(f, i, j)), e2 = grid.dir(f, i, j + 1).sub(grid.dir(f, i, j));
        e1.sub(new Vector3d(up).mul(e1.dot(up))).normalize();
        e2.sub(new Vector3d(up).mul(e2.dot(up))).sub(new Vector3d(e1).mul(e2.dot(e1))).normalize();
        double floor = grid.radius(depth + height[col]); // the top of the ground
        Vector3d p = new Vector3d();
        for (int b = 0; b < t.blocks().length; b++) {
            p.set(up).mul(floor + t.dy()[b] - 0.5).add(e1.x * t.dx()[b], e1.y * t.dx()[b], e1.z * t.dx()[b])
                    .add(e2.x * t.dz()[b], e2.y * t.dz()[b], e2.z * t.dz()[b]);
            int cell = grid.cellAt(p);
            if (cell < 0 || grid.k(cell) == 0) continue;
            if (t.dy()[b] == 0 ? cells[cell] == Blocks.AIR : cells[cell] != Blocks.AIR && !snowCover(cells[cell], ids)) continue;
            cells[cell] = (char) ids.applyAsInt(t.blocks()[b]);
        }
    }

    private static boolean snowCover(char id, ToIntFunction<String> ids) {
        return id == ids.applyAsInt("minecraft:snow");
    }
}
