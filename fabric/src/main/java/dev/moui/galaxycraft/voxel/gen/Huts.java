package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.joml.Vector3d;

/**
 * Small mushroom huts on a generated planet, where its Toad outposts are (see toad.ToadPlan). Made
 * with the cells, before the plants: the ground under a hut is leveled (a step of two blocks at
 * most), what grows is kept off it, and the same request on the same planet gives the same hut.
 * A hut is 5×5 blocks of floor, walls two high with a door and two windows, a red mushroom roof.
 * No Minecraft types, so it is unit tested.
 */
public final class Huts {
    /** Where a hut wanted: an id and a unit direction from the planet's center. */
    public record Request(String id, double[] dir) {}

    /**
     * A hut made (or, with build off, the ground a lone Toad stands on): dir the unit direction of
     * the spot just inside the door, radius its height from the center in blocks, facing the unit
     * vector, tangent to the ground, from the hut out through its door.
     */
    public record Placed(String id, double[] dir, double radius, double[] facing) {}

    private static final int HALF = 2, CLEAR = 3, KEEP = 5, HEIGHT = 6, STEP = 2, TRIES = 4, SPREAD = 8;
    private static final String PLANKS = "minecraft:oak_planks", STEM = "minecraft:mushroom_stem", CAP = "minecraft:red_mushroom_block",
            GLASS = "minecraft:glass", TORCH = "minecraft:torch", DIRT = "minecraft:dirt";

    private Huts() {}

    /**
     * Puts each request's hut on the nearest ground that fits (the request's column, then a spread
     * of columns around it) and returns those that found one. height and bare are as the generator
     * has them (height: the top block's layer minus depth - 1); they are updated where a hut stands
     * so nothing is planted there. build off places no blocks: it only finds the ground.
     */
    public static List<Placed> plant(CubeSphere grid, int depth, char[] cells, int[] height, boolean[] bare,
            List<Request> requests, boolean build, ToIntFunction<String> ids) {
        List<Placed> out = new ArrayList<>();
        for (Request r : requests) {
            Vector3d p = new Vector3d(r.dir()[0], r.dir()[1], r.dir()[2]).normalize().mul(grid.core + depth);
            int at = grid.cellAt(p);
            if (at < 0) continue;
            int f = grid.face(at), i0 = grid.i(at), j0 = grid.j(at);
            Placed placed = null;
            for (int ring = 0; ring <= TRIES && placed == null; ring++) {
                for (int di = -ring; di <= ring && placed == null; di++)
                    for (int dj = -ring; dj <= ring && placed == null; dj++) {
                        if (Math.max(Math.abs(di), Math.abs(dj)) != ring) continue;
                        placed = tryAt(grid, depth, cells, height, bare, r.id(), f, i0 + di * SPREAD, j0 + dj * SPREAD, build, ids);
                    }
            }
            if (placed != null) out.add(placed);
        }
        return out;
    }

    private static Placed tryAt(CubeSphere grid, int depth, char[] cells, int[] height, boolean[] bare, String id,
            int f, int ci, int cj, boolean build, ToIntFunction<String> ids) {
        int n = grid.n, layers = grid.layers;
        // Inside one face, with the cleared ring and the kept ring.
        if (ci - KEEP < 0 || cj - KEEP < 0 || ci + KEEP >= n || cj + KEEP >= n) return null;
        int level = depth - 1 + height[col(n, f, ci, cj)];
        if (level + HEIGHT >= layers || level < 1) return null;
        for (int a = -HALF; a <= HALF; a++)
            for (int b = -HALF; b <= HALF; b++) {
                int c = col(n, f, ci + a, cj + b);
                if (!bare[c]) return null;
                int g = depth - 1 + height[c];
                if (Math.abs(g - level) > STEP) return null;
            }
        if (build) {
            // Clear the room (and a ring round it), then level and build.
            for (int a = -CLEAR; a <= CLEAR; a++)
                for (int b = -CLEAR; b <= CLEAR; b++) {
                    int base = col(n, f, ci + a, cj + b) * layers;
                    for (int k = level + 1; k <= level + HEIGHT; k++) cells[base + k] = Blocks.AIR;
                }
            char planks = (char) ids.applyAsInt(PLANKS), stem = (char) ids.applyAsInt(STEM), cap = (char) ids.applyAsInt(CAP),
                    glass = (char) ids.applyAsInt(GLASS), torch = (char) ids.applyAsInt(TORCH), dirt = (char) ids.applyAsInt(DIRT);
            for (int a = -HALF; a <= HALF; a++)
                for (int b = -HALF; b <= HALF; b++) {
                    int c = col(n, f, ci + a, cj + b), base = c * layers, g = depth - 1 + height[c];
                    for (int k = g + 1; k < level; k++) cells[base + k] = dirt;
                    cells[base + level] = planks;
                    boolean wall = Math.abs(a) == HALF || Math.abs(b) == HALF;
                    boolean door = a == HALF && b == 0, window = a == 0 && Math.abs(b) == HALF;
                    if (wall && !door) {
                        cells[base + level + 1] = stem;
                        cells[base + level + 2] = window ? glass : stem;
                    }
                    cells[base + level + 3] = a == 0 && b == 0 || (a == 1 && b == -1) ? stem : cap; // spots on the roof
                    if (Math.abs(a) <= 1 && Math.abs(b) <= 1) cells[base + level + 4] = cap;
                }
            cells[col(n, f, ci - 1, cj - 1) * layers + level + 1] = torch;
            // A level way out: three wide, three long, past the door (ground above the floor is cut
            // with what grows on it, ground below it filled), so the door is never walled by a step.
            for (int a = HALF + 1; a <= HALF + 3; a++)
                for (int b = -1; b <= 1; b++) {
                    int c = col(n, f, ci + a, cj + b), base = c * layers, g = depth - 1 + height[c];
                    for (int k = level + 1; k <= level + HEIGHT; k++) cells[base + k] = Blocks.AIR;
                    for (int k = g + 1; k <= level; k++) cells[base + k] = dirt;
                    height[c] = level - (depth - 1);
                }
        }
        // What grows is kept off the hut and its ring.
        for (int a = -KEEP; a <= KEEP; a++)
            for (int b = -KEEP; b <= KEEP; b++) {
                int c = col(n, f, ci + a, cj + b);
                bare[c] = false;
                if (build && Math.abs(a) <= HALF && Math.abs(b) <= HALF) height[c] = level - (depth - 1);
            }
        // The spot just inside the door, and the way out.
        Vector3d inside = columnDir(grid, f, ci + 1, cj), centre = columnDir(grid, f, ci, cj);
        Vector3d facing = new Vector3d(columnDir(grid, f, ci + 2, cj)).sub(centre);
        facing.fma(-facing.dot(inside), inside).normalize();
        return new Placed(id, new double[] {inside.x, inside.y, inside.z}, grid.core + level + 1, new double[] {facing.x, facing.y, facing.z});
    }

    private static int col(int n, int f, int i, int j) {
        return (f * n + i) * n + j;
    }

    /** The unit direction of a column's middle. */
    private static Vector3d columnDir(CubeSphere g, int f, int i, int j) {
        return g.dir(f, i, j).add(g.dir(f, i + 1, j)).add(g.dir(f, i, j + 1)).add(g.dir(f, i + 1, j + 1)).normalize();
    }
}
