package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Trees shaped like Minecraft's, planted on cube-sphere planets: every block lands, and stays next to its neighbors. */
class VegetationTest {
    private static final int DEPTH = 8, AIR = 48, GROUND = 60000, SNOW = 59999;

    /** Blocks by offset; each gets its own id (b0, b1...) so the test can find where it went. */
    private static final class Shape {
        final Map<List<Integer>, Boolean> at = new LinkedHashMap<>();

        void add(int dx, int dy, int dz) {
            at.putIfAbsent(List.of(dx, dy, dz), true);
        }

        Vegetation.Thing thing() {
            List<List<Integer>> keys = new ArrayList<>(at.keySet());
            int[] dx = new int[keys.size()], dy = new int[keys.size()], dz = new int[keys.size()];
            String[] blocks = new String[keys.size()];
            for (int b = 0; b < keys.size(); b++) {
                dx[b] = keys.get(b).get(0);
                dy[b] = keys.get(b).get(1);
                dz[b] = keys.get(b).get(2);
                blocks[b] = "b" + b;
            }
            return new Vegetation.Thing(0, 0, dx, dy, dz, blocks);
        }
    }

    /** Oak: dirt, a trunk of 5, leaves 5×5 two high around it, then 3×3 two high. */
    static Vegetation.Thing oak() {
        Shape s = new Shape();
        s.add(0, 0, 0);
        for (int y = 1; y <= 5; y++) s.add(0, y, 0);
        for (int y = 3; y <= 6; y++) {
            int r = y <= 4 ? 2 : 1;
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++) s.add(x, y, z);
        }
        return s.thing();
    }

    /** Mega spruce: a 2×2 trunk 24 high under a cone of leaves up to 4 wide. */
    static Vegetation.Thing megaSpruce() {
        Shape s = new Shape();
        for (int y = 0; y <= 24; y++)
            for (int x = 0; x <= 1; x++)
                for (int z = 0; z <= 1; z++) s.add(x, y, z);
        for (int y = 6; y <= 26; y++) disk(s, y, Math.min(4, (26 - y) / 5 + 1));
        return s.thing();
    }

    /** Big jungle tree: a 2×2 trunk 28 high, a wide crown, two branches with leaves. */
    static Vegetation.Thing jungle() {
        Shape s = new Shape();
        for (int y = 0; y <= 28; y++)
            for (int x = 0; x <= 1; x++)
                for (int z = 0; z <= 1; z++) s.add(x, y, z);
        for (int y = 26; y <= 30; y++) disk(s, y, y == 30 ? 2 : 4);
        for (int x = 2; x <= 4; x++) s.add(x, 18, 0);
        for (int z = -1; z >= -3; z--) s.add(0, 20, z);
        for (int y = 18; y <= 19; y++) {
            for (int x = 2; x <= 6; x++)
                for (int z = -2; z <= 2; z++) s.add(x, y + 1, z);
            for (int x = -2; x <= 2; x++)
                for (int z = -5; z <= -1; z++) s.add(x, y + 3, z);
        }
        return s.thing();
    }

    /** Leaves round a 2×2 trunk's middle, r blocks out. */
    private static void disk(Shape s, int y, int r) {
        for (int x = -r; x <= r + 1; x++)
            for (int z = -r; z <= r + 1; z++)
                if ((x - 0.5) * (x - 0.5) + (z - 0.5) * (z - 0.5) <= (r + 0.5) * (r + 0.5)) s.add(x, y, z);
    }

    /** A planet with flat ground (or the heights given) and room for tall trees. */
    record World(CubeSphere grid, char[] cells, int[] height, float[] dirs) {
        static World flat(int radius, java.util.function.IntUnaryOperator heightOf) {
            int n = (int) Math.round(Math.PI * radius / 2);
            CubeSphere g = new CubeSphere(n, radius - DEPTH, DEPTH + AIR);
            int columns = 6 * n * n;
            int[] height = new int[columns];
            float[] dirs = new float[3 * columns];
            char[] cells = new char[g.cellCount()];
            for (int f = 0; f < 6; f++)
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++) {
                        int col = (f * n + i) * n + j;
                        Vector3d p = g.dir(f, i, j).add(g.dir(f, i + 1, j)).add(g.dir(f, i, j + 1)).add(g.dir(f, i + 1, j + 1)).normalize();
                        dirs[3 * col] = (float) p.x;
                        dirs[3 * col + 1] = (float) p.y;
                        dirs[3 * col + 2] = (float) p.z;
                        height[col] = heightOf.applyAsInt(col);
                        for (int k = 0; k <= DEPTH - 1 + height[col]; k++) cells[col * g.layers + k] = (char) GROUND;
                    }
            return new World(g, cells, height, dirs);
        }
    }

    /** Where it went: for each block, its cell, or -1 if it is not on the planet. */
    static int[] plant(World w, Vegetation.Thing t, int f, int i, int j) {
        int n = w.grid.n, col = (f * n + i) * n + j;
        Vegetation.place(w.grid, DEPTH, w.cells, w.height, f, i, j, col, t, VegetationTest::id);
        int[] where = new int[t.blocks().length];
        java.util.Arrays.fill(where, -1);
        for (int c = 0; c < w.cells.length; c++) {
            int id = w.cells[c];
            if (id >= 1 && id <= where.length) where[id - 1] = c;
        }
        return where;
    }

    private static int id(String name) {
        return name.equals("minecraft:snow") ? SNOW : Integer.parseInt(name.substring(1)) + 1;
    }

    /** Lost blocks, and pairs of blocks side by side in Minecraft that are apart on the planet. */
    record Damage(int blocks, int lost, int apart) {}

    static Damage damage(World w, Vegetation.Thing t, int[] where) {
        Map<List<Integer>, Integer> index = new java.util.HashMap<>();
        for (int b = 0; b < where.length; b++) index.put(List.of(t.dx()[b], t.dy()[b], t.dz()[b]), b);
        int lost = 0, apart = 0;
        for (int b = 0; b < where.length; b++) {
            if (where[b] < 0) {
                lost++;
                continue;
            }
            for (int[] d : new int[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}) {
                Integer o = index.get(List.of(t.dx()[b] + d[0], t.dy()[b] + d[1], t.dz()[b] + d[2]));
                if (o == null || where[o] < 0) continue;
                if (!touch(w.grid, where[b], where[o])) apart++;
            }
        }
        return new Damage(where.length, lost, apart);
    }

    private static boolean touch(CubeSphere g, int a, int b) {
        for (int side = 0; side < 6; side++) if (g.neighbor(a, side) == b) return true;
        return false;
    }

    private static Map<String, Vegetation.Thing> shapes() {
        Map<String, Vegetation.Thing> shapes = new LinkedHashMap<>();
        shapes.put("oak", oak());
        shapes.put("mega spruce", megaSpruce());
        shapes.put("jungle", jungle());
        return shapes;
    }

    /**
     * On flat ground every tree lands whole: no block lost, none apart from its neighbors, in the
     * middle of a face and across its edges. Round a corner it may not fit; then nothing is planted.
     */
    @Test void treesLandWholeOrNotAtAll() {
        for (int radius : new int[] {32, 64, 128}) {
            int n = (int) Math.round(Math.PI * radius / 2);
            int[][] spots = {{n / 2, n / 2}, {n / 4, n / 3}, {1, n / 2}, {n - 1, n / 3}, {n / 2, 0}, {2, 2}, {0, 0}, {n - 1, 1}};
            String[] names = {"mid-face", "off-middle", "edge", "far edge", "j edge", "near corner", "on corner", "corner 2"};
            for (Map.Entry<String, Vegetation.Thing> e : shapes().entrySet())
                for (int s = 0; s < spots.length; s++) {
                    World w = World.flat(radius, col -> 0);
                    char[] before = w.cells.clone();
                    int[] where = plant(w, e.getValue(), 0, spots[s][0], spots[s][1]);
                    Damage d = damage(w, e.getValue(), where);
                    boolean planted = !java.util.Arrays.equals(before, w.cells);
                    System.out.printf("r%-4d %-12s %-11s planted %-5b blocks %4d lost %4d apart %4d%n", radius, e.getKey(), names[s],
                            planted, d.blocks, d.lost, d.apart);
                    String what = e.getKey() + " r" + radius + " " + names[s];
                    if (s < 5) assertTrue(planted, what);
                    if (!planted) continue;
                    assertEquals(0, d.lost, what + " lost");
                    assertEquals(0, d.apart, what + " apart");
                }
        }
    }

    /** Minecraft's x is the shadow dimension's: along j; its z along i. Vines and cocoa face as they grew. */
    @Test void xGoesAlongJ() {
        World w = World.flat(32, col -> 0);
        Vegetation.Thing t = new Vegetation.Thing(0, 0, new int[] {0, 1, 0}, new int[] {1, 1, 1}, new int[] {0, 0, 1},
                new String[] {"b0", "b1", "b2"});
        int n = w.grid.n;
        int[] where = plant(w, t, 0, n / 2, n / 2);
        CubeSphere g = w.grid;
        assertEquals(g.j(where[0]) + 1, g.j(where[1]));
        assertEquals(g.i(where[0]), g.i(where[1]));
        assertEquals(g.i(where[0]) + 1, g.i(where[2]));
    }

    /**
     * On a slope as Minecraft's trees: a gentle one keeps them whole, a hill cuts into a crown but
     * never into a trunk, and a 2×2 trunk on uneven ground, or a tree past the sky, is not planted.
     */
    @Test void slopes() {
        int radius = 64, n = (int) Math.round(Math.PI * radius / 2), i0 = n / 2, j0 = n / 2;
        // Up one block every three columns along j, around the middle of face 0.
        World gentle = World.flat(radius, col -> col / (n * n) == 0 ? Math.max(0, (col % n - j0) / 3) : 0);
        for (Map.Entry<String, Vegetation.Thing> e : shapes().entrySet()) {
            World w = World.flat(radius, col -> col / (n * n) == 0 ? Math.max(0, (col % n - j0 + 20) / 3) : 0);
            int[] where = plant(w, e.getValue(), 0, i0, j0);
            Damage d = damage(w, e.getValue(), where);
            System.out.printf("slope r%d %-12s planted %-5b lost %4d apart %4d%n", radius, e.getKey(), where[0] >= 0, d.lost, d.apart);
        }
        Damage oak = damage(gentle, oak(), plant(gentle, oak(), 0, i0, j0 - 1));
        assertEquals(0, oak.lost, "an oak on a gentle slope");

        // A wall 4 high two columns away: the crown's lowest leaves on that side go, the trunk stays.
        World wall = World.flat(radius, col -> col / (n * n) == 0 && col % n >= j0 + 2 ? 4 : 0);
        Vegetation.Thing t = oak();
        int[] where = plant(wall, t, 0, i0, j0);
        for (int b = 0; b < where.length; b++) {
            boolean inHill = t.dx()[b] >= 2 && t.dy()[b] <= 4;
            assertEquals(inHill, where[b] < 0, "block " + t.dx()[b] + "," + t.dy()[b] + "," + t.dz()[b]);
        }

        // One column of a 2×2 trunk a block up: not planted at all.
        World step = World.flat(radius, col -> col == (i0 * n) + j0 + 1 ? 1 : 0);
        char[] before = step.cells.clone();
        plant(step, megaSpruce(), 0, i0, j0);
        assertArrayEquals(before, step.cells, "a giant tree with a trunk in the ground");
        // Two blocks down: it would hang over the drop.
        step = World.flat(radius, col -> col == (i0 * n) + j0 + 1 ? -2 : 0);
        before = step.cells.clone();
        plant(step, megaSpruce(), 0, i0, j0);
        assertArrayEquals(before, step.cells, "a giant tree over a drop");
        // One block down: planted whole, its dirt filling the step as in Minecraft.
        step = World.flat(radius, col -> col == (i0 * n) + j0 + 1 ? -1 : 0);
        Vegetation.Thing spruce = megaSpruce();
        int[] at = plant(step, spruce, 0, i0, j0);
        assertEquals(0, damage(step, spruce, at).lost, "a giant tree on a step");
        for (int b = 0; b < at.length; b++)
            if (spruce.dy()[b] == 0) assertEquals(GROUND, (int) step.cells[at[b] - 1], "dirt on the ground");
        // Ground high enough that the jungle tree's top would pass the sky: not planted.
        World high = World.flat(radius, col -> AIR - 25);
        before = high.cells.clone();
        plant(high, jungle(), 0, i0, j0);
        assertArrayEquals(before, high.cells, "a tree past the sky");
    }

    /** Builds what grew from (dx, dy, dz, block) rows, in a 16-wide chunk with 4 blocks of margin. */
    private static String[][][] grown(Object[]... rows) {
        String[][][] g = new String[24][12][24];
        for (Object[] r : rows) g[4 + (int) r[0]][(int) r[1]][4 + (int) r[2]] = (String) r[3];
        return g;
    }

    /** A 2×2 trunk is one thing; a flower beside it, a tree past the chunk and touching crowns stay apart. */
    @Test void grownSplitsIntoThings() {
        java.util.List<Object[]> rows = new ArrayList<>();
        for (int x = 3; x <= 4; x++)
            for (int z = 3; z <= 4; z++) {
                rows.add(new Object[] {x, 0, z, "dirt"});
                for (int y = 1; y <= 6; y++) rows.add(new Object[] {x, y, z, "log"});
            }
        for (int x = 1; x <= 6; x++)
            for (int z = 1; z <= 6; z++) rows.add(new Object[] {x, 7, z, "leaves"});
        rows.add(new Object[] {5, 1, 3, "poppy"});
        // A thin tree whose crown touches the big one's.
        for (int y = 1; y <= 5; y++) rows.add(new Object[] {9, y, 3, "log"});
        for (int z = 2; z <= 4; z++) rows.add(new Object[] {8, 6, z, "leaves"});
        rows.add(new Object[] {9, 6, 3, "leaves"});
        // One standing past the chunk, its crown over it.
        for (int y = 1; y <= 4; y++) rows.add(new Object[] {-2, y, 8, "log"});
        rows.add(new Object[] {-1, 4, 8, "leaves"});
        List<Vegetation.Thing> things = Vegetation.things(grown(rows.toArray(new Object[0][])), 4);
        assertEquals(3, things.size());
        Vegetation.Thing big = things.get(0), poppy = things.get(1), thin = things.get(2);
        assertEquals(List.of(3, 3), List.of(big.ax(), big.az()));
        assertEquals(4 + 24 + 36, big.blocks().length, "dirt, trunk and crown");
        assertEquals(List.of(5, 3, 1), List.of(poppy.ax(), poppy.az(), poppy.blocks().length));
        assertEquals(List.of(9, 3), List.of(thin.ax(), thin.az()));
        assertEquals(5 + 4, thin.blocks().length, "its own crown: the leaves nearest it");
        for (int b = 0; b < big.blocks().length; b++)
            if (big.dy()[b] == 0) assertEquals("dirt", big.blocks()[b]);
    }
}
