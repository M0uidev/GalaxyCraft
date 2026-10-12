package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class HutsTest {
    static final int N = 48, DEPTH = 20, LAYERS = 40;
    private final Map<String, Integer> known = new HashMap<>();
    private final ToIntFunction<String> ids = name -> known.computeIfAbsent(name, k -> known.size() + 10);

    /** A planet whose ground is flat at layer DEPTH - 1 (+ bump(col) blocks), every column bare. */
    private World world(java.util.function.IntUnaryOperator bump) {
        CubeSphere g = new CubeSphere(N, 30, LAYERS);
        char[] cells = new char[g.cellCount()];
        int[] height = new int[g.columns()];
        boolean[] bare = new boolean[g.columns()];
        char stone = 5;
        for (int c = 0; c < g.columns(); c++) {
            height[c] = bump.applyAsInt(c);
            for (int k = 0; k <= DEPTH - 1 + height[c]; k++) cells[c * LAYERS + k] = stone;
            bare[c] = true;
        }
        return new World(g, cells, height, bare);
    }

    record World(CubeSphere g, char[] cells, int[] height, boolean[] bare) {}

    private static double[] dir(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / l, y / l, z / l};
    }

    @Test void aHutStandsOnFlatGround() {
        World w = world(c -> 0);
        List<Huts.Placed> out = Huts.plant(w.g, DEPTH, w.cells, w.height, w.bare,
                List.of(new Huts.Request("h", dir(0.3, 1, 0.2))), true, ids);
        assertEquals(1, out.size());
        Huts.Placed p = out.get(0);
        assertEquals("h", p.id());
        double[] d = p.dir();
        assertEquals(1.0, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9);
        // The floor replaces the top block (layer DEPTH - 1): the Toad stands on its top, layer DEPTH's bottom.
        assertEquals(30 + DEPTH, p.radius(), 1e-9);
        int roof = ids.applyAsInt("minecraft:red_mushroom_block");
        int walls = ids.applyAsInt("minecraft:mushroom_stem");
        int found = 0, wallBlocks = 0;
        for (char c : w.cells) {
            if (c == roof) found++;
            if (c == walls) wallBlocks++;
        }
        assertTrue(found >= 17, "a roof: " + found);
        assertTrue(wallBlocks >= 20, "walls: " + wallBlocks);
        double[] fc = p.facing();
        assertEquals(1.0, Math.sqrt(fc[0] * fc[0] + fc[1] * fc[1] + fc[2] * fc[2]), 1e-6);
        assertEquals(0, fc[0] * d[0] + fc[1] * d[1] + fc[2] * d[2], 1e-6, "facing is along the ground");
    }

    @Test void nothingGrowsOnAHutAndItsRing() {
        World w = world(c -> 0);
        int before = 0;
        for (boolean b : w.bare) if (b) before++;
        Huts.plant(w.g, DEPTH, w.cells, w.height, w.bare, List.of(new Huts.Request("h", dir(0, 1, 0))), true, ids);
        int after = 0;
        for (boolean b : w.bare) if (b) after++;
        assertEquals(11 * 11, before - after);
    }

    @Test void theSameRequestMakesTheSameHut() {
        World a = world(c -> 0), b = world(c -> 0);
        Huts.Request r = new Huts.Request("h", dir(1, 0.4, -0.7));
        Huts.plant(a.g, DEPTH, a.cells, a.height, a.bare, List.of(r), true, ids);
        Huts.plant(b.g, DEPTH, b.cells, b.height, b.bare, List.of(r), true, ids);
        assertArrayEquals(a.cells, b.cells);
    }

    @Test void aSlopeOfOneIsLeveledAndASteepOneIsAvoided() {
        // Ground rises one block every four columns of i: leveled under the hut.
        World gentle = world(c -> c / N % N / 4 % 3);
        assertEquals(1, Huts.plant(gentle.g, DEPTH, gentle.cells, gentle.height, gentle.bare,
                List.of(new Huts.Request("h", dir(0, 1, 0))), true, ids).size());
        // Cliffs of five blocks everywhere: nowhere to build.
        World cliffs = world(c -> (c % 2) * 5);
        assertTrue(Huts.plant(cliffs.g, DEPTH, cliffs.cells, cliffs.height, cliffs.bare,
                List.of(new Huts.Request("h", dir(0, 1, 0))), true, ids).isEmpty());
    }

    @Test void waterOrBareRockIsNotBuiltOn() {
        World w = world(c -> 0);
        java.util.Arrays.fill(w.bare, false);
        assertTrue(Huts.plant(w.g, DEPTH, w.cells, w.height, w.bare,
                List.of(new Huts.Request("h", dir(0, 1, 0))), true, ids).isEmpty());
    }

    @Test void withBuildOffNothingIsPlacedButTheGroundIsFound() {
        World w = world(c -> 0);
        char[] copy = w.cells.clone();
        List<Huts.Placed> out = Huts.plant(w.g, DEPTH, w.cells, w.height, w.bare,
                List.of(new Huts.Request("h", dir(0, 1, 0))), false, ids);
        assertEquals(1, out.size());
        assertArrayEquals(copy, w.cells);
    }

    @Test void aStepInFrontOfTheDoorIsLeveled() {
        // Ground one block higher on the door's side of the hut (rows past the hut's middle).
        World w = world(c -> c / N % N >= 28 ? 1 : 0);
        List<Huts.Placed> out = Huts.plant(w.g, DEPTH, w.cells, w.height, w.bare,
                List.of(new Huts.Request("h", dir(0, 1, 0))), true, ids);
        assertEquals(1, out.size());
        // Every column of the way out has its top at the floor's layer, nothing above it.
        double[] d = out.get(0).dir();
        int at = w.g.cellAt(new org.joml.Vector3d(d[0], d[1], d[2]).mul(30 + DEPTH - 0.5));
        int col = at / LAYERS, n = N;
        for (int a = 2; a <= 4; a++) { // the spot is the column past the middle; the way out is the three after the wall
            int c = col + a * n;
            assertEquals(0, w.height[c], "height " + a);
            for (int k = DEPTH; k < LAYERS; k++) assertEquals(0, w.cells[c * LAYERS + k], "air " + a + " " + k);
        }
    }
}
