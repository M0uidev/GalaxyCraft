package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class PlanetGeneratorTest {
    private static final Map<String, Integer> UNIQUE = new ConcurrentHashMap<>();

    /** A block id of its own for every name. */
    private static synchronized int unique(String name) {
        return UNIQUE.computeIfAbsent(name, k -> UNIQUE.size() + 1);
    }

    private static PlanetBlueprint bp(int radius, int air, long seed, String biome, int size) {
        return PlanetBlueprint.standard("g", radius).withMode(PlanetBlueprint.Mode.GENERATED).withBiome(seed, biome, size)
                .withAir(air).withUnderground(0, false, 0).withPlants(0);
    }

    private static PlanetGenerator.Cells cells(PlanetBlueprint bp) {
        return PlanetGenerator.cells(bp, LIBRARY, PlanetGeneratorTest::unique);
    }

    private static boolean sky(char id) {
        return id == Blocks.AIR || id == unique("minecraft:water") || id == unique("minecraft:snow") || id == unique("minecraft:ice");
    }

    /** Index of the highest ground cell (not air, water, snow cover or ice) in a column. */
    private static int ground(PlanetGenerator.Cells c, int base) {
        int k = c.grid().layers - 1;
        while (k > 0 && sky(c.cells()[base + k])) k--;
        return k;
    }

    @Test void noSeamsOnTheCubesEdges() {
        PlanetGenerator.Cells c = cells(bp(64, 8, 3, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO));
        CubeSphere g = c.grid();
        int worstAcross = 0;
        long across = 0, inside = 0, nAcross = 0, nInside = 0;
        for (int cell = 0; cell < g.cellCount(); cell += g.layers)
            for (int side = CubeSphere.I_MINUS; side <= CubeSphere.J_PLUS; side++) {
                int nb = g.neighbor(cell, side), d = Math.abs(ground(c, cell) - ground(c, nb - g.k(nb)));
                if (g.face(nb) != g.face(cell)) {
                    across += d;
                    nAcross++;
                    worstAcross = Math.max(worstAcross, d);
                } else {
                    inside += d;
                    nInside++;
                }
            }
        double a = (double) across / nAcross, i = (double) inside / nInside;
        assertTrue(a <= i * 1.5 + 0.2, "steps across edges " + a + ", inside faces " + i + " (worst across " + worstAcross + ")");
    }

    @Test void groundStaysBetweenBedrockAndTheSky() {
        for (int radius : new int[] {16, 64}) {
            PlanetGenerator.Cells c = cells(bp(radius, 8, 4, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO));
            CubeSphere g = c.grid();
            for (int base = 0; base < g.cellCount(); base += g.layers) {
                assertEquals(unique("minecraft:bedrock"), (int) c.cells()[base], "bedrock at the bottom");
                assertTrue(ground(c, base) >= 1, "ground over the bedrock");
                for (int k = g.layers - 3; k < g.layers; k++) assertEquals(Blocks.AIR, (int) c.cells()[base + k], "sky at the top");
            }
        }
    }

    @Test void sameSeedSamePlanet() {
        PlanetBlueprint b = bp(48, 8, 5, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO).withUnderground(50, true, 100);
        assertArrayEquals(cells(b).cells(), cells(b).cells());
        assertFalse(java.util.Arrays.equals(cells(b).cells(), cells(bp(48, 8, 6, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO)).cells()));
    }

    @Test void seasAreFullToTheirLevel() {
        PlanetGenerator.Cells c = cells(bp(96, 8, 7, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO));
        CubeSphere g = c.grid();
        int sea = c.depth() - 1, wet = 0;
        for (int base = 0; base < g.cellCount(); base += g.layers) {
            char at = c.cells()[base + sea];
            if (at != unique("minecraft:water") && at != unique("minecraft:ice")) continue;
            wet++;
            for (int k = ground(c, base) + 1; k <= sea; k++) {
                char id = c.cells()[base + k];
                assertTrue(id == unique("minecraft:water") || id == unique("minecraft:ice"), "water from the floor up, k " + k);
            }
        }
        assertTrue(wet > g.columns() / 10, wet + " wet columns of " + g.columns());
    }

    @Test void aDesertIsSand() {
        PlanetGenerator.Cells c = cells(bp(64, 8, 8, "minecraft:desert", 0));
        CubeSphere g = c.grid();
        int sand = 0, grass = 0;
        for (int base = 0; base < g.cellCount(); base += g.layers) {
            char top = c.cells()[base + ground(c, base)];
            if (top == unique("minecraft:sand")) sand++;
            if (top == unique("minecraft:grass_block")) grass++;
        }
        assertTrue(sand > g.columns() * 0.5, sand + " sand of " + g.columns()); // the rest: river beds, bare stone
        assertEquals(0, grass, "no grass on the desert");
    }

    @Test void snowyGroundIsCoveredAndItsWaterFrozen() {
        PlanetGenerator.Cells c = cells(bp(64, 8, 9, "minecraft:snowy_plains", 0));
        assertTrue(count(c, "minecraft:snow") > c.grid().columns() / 3, "snow cover");
        assertTrue(count(c, "minecraft:ice") > 0, "frozen rivers");
    }

    @Test void cavesDigTheGroundNeverTheBedrock() {
        PlanetBlueprint b = bp(64, 8, 10, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO);
        PlanetGenerator.Cells solid = cells(b), dug = cells(b.withUnderground(60, true, 0));
        int carved = 0;
        for (int i = 0; i < solid.cells().length; i++) {
            if (solid.cells()[i] == dug.cells()[i]) continue;
            assertNotEquals(0, solid.grid().k(i), "bedrock stays");
            if (dug.cells()[i] == Blocks.AIR || dug.cells()[i] == unique("minecraft:lava")) carved++;
        }
        assertTrue(carved > 1000, carved + " cells dug");
    }

    @Test void oresAtTheirDepthsDeepslateBelow() {
        PlanetBlueprint bp = bp(96, 16, 5, "minecraft:plains", 0).withUnderground(0, true, 100);
        PlanetGenerator.Cells c = cells(bp);
        CubeSphere g = c.grid();
        int deepTop = (c.depth() - 1) / 3;
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        for (int i = 0; i < c.cells().length; i++) {
            count.merge((int) c.cells()[i], 1, Integer::sum);
            int k = g.k(i);
            if (c.cells()[i] == unique("minecraft:deepslate")) assertTrue(k <= deepTop, "deepslate only deep");
            if (c.cells()[i] == unique("minecraft:diamond_ore") || c.cells()[i] == unique("minecraft:deepslate_diamond_ore"))
                assertTrue(k <= c.depth() / 3 + 2, "diamonds by the bedrock, not at " + k);
        }
        for (String ore : List.of("coal", "iron", "diamond"))
            assertTrue(count.getOrDefault(unique("minecraft:" + ore + "_ore"), 0) + count.getOrDefault(unique("minecraft:deepslate_" + ore + "_ore"), 0) > 0, ore);
        assertTrue(count.getOrDefault(unique("minecraft:deepslate_diamond_ore"), 0) > count.getOrDefault(unique("minecraft:diamond_ore"), 0));
        PlanetGenerator.Cells none = cells(bp.withUnderground(0, true, 0));
        assertFalse(java.util.Arrays.equals(none.cells(), c.cells()));
        for (char id : none.cells()) assertNotEquals(unique("minecraft:coal_ore"), (int) id);
    }

    /** A tree (dirt under a trunk of 4 logs, leaves around its top) and a flower, on every biome but the desert, rivers and beaches (as Minecraft's). */
    private static final Vegetation.Thing TREE, FLOWER = new Vegetation.Thing(7, 2, new int[] {0}, new int[] {1}, new int[] {0},
            new String[] {"minecraft:poppy"});

    static {
        List<int[]> at = new java.util.ArrayList<>();
        for (int y = 0; y <= 4; y++) at.add(new int[] {0, y, 0});
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                if (x != 0 || z != 0) at.add(new int[] {x, 4, z});
        at.add(new int[] {0, 5, 0});
        String[] names = new String[at.size()];
        for (int i = 0; i < names.length; i++) names[i] = i == 0 ? "minecraft:dirt" : i < 5 ? "minecraft:oak_log" : "minecraft:oak_leaves";
        TREE = new Vegetation.Thing(3, 3, at.stream().mapToInt(a -> a[0]).toArray(), at.stream().mapToInt(a -> a[1]).toArray(),
                at.stream().mapToInt(a -> a[2]).toArray(), names);
    }

    private static final Vegetation.Library LIBRARY = biome -> List.of("minecraft:desert", "minecraft:river", "minecraft:beach").contains(biome) ? List.of()
            : List.of(new Vegetation.Patch(List.of(TREE, FLOWER, new Vegetation.Thing(11, 12, TREE.dx(), TREE.dy(), TREE.dz(), TREE.blocks()))),
                    new Vegetation.Patch(List.of(new Vegetation.Thing(8, 7, TREE.dx(), TREE.dy(), TREE.dz(), TREE.blocks()))));

    private static int count(PlanetGenerator.Cells c, String name) {
        int n = 0;
        for (char id : c.cells()) if (id == unique(name)) n++;
        return n;
    }

    @Test void treesStandOnDirtOverGrassAndOnlyFillAir() {
        PlanetBlueprint bp = bp(64, 16, 9, "minecraft:plains", 0).withWater(true);
        PlanetGenerator.Cells bare = cells(bp), grown = cells(bp.withPlants(100));
        CubeSphere g = bare.grid();
        int logs = 0;
        for (int i = 0; i < bare.cells().length; i++) {
            if (bare.cells()[i] == grown.cells()[i]) continue;
            if (grown.cells()[i] == unique("minecraft:dirt")) {
                assertEquals(unique("minecraft:grass_block"), (int) bare.cells()[i], "the floor under a trunk was grass");
                assertEquals(unique("minecraft:oak_log"), (int) grown.cells()[i + 1], "dirt only under a trunk");
                continue;
            }
            assertTrue(bare.cells()[i] == Blocks.AIR || bare.cells()[i] == unique("minecraft:snow"), "only into air");
            if (grown.cells()[i] != unique("minecraft:oak_log")) continue;
            logs++;
            int below = i - 1;
            while (grown.cells()[below] == unique("minecraft:oak_log")) below--;
            // Minecraft turns grass under a log to dirt; grown that way, it has nothing to change later.
            assertEquals(unique("minecraft:dirt"), (int) grown.cells()[below], "a trunk stands on dirt, k " + g.k(below));
        }
        assertTrue(logs > 100, logs + " logs");
        assertTrue(count(grown, "minecraft:poppy") > 0, "flowers");
        assertTrue(count(cells(bp.withPlants(200)), "minecraft:oak_log") > logs, "more at 200%");
        assertEquals(0, count(cells(bp(64, 16, 9, "minecraft:desert", 0).withPlants(100)), "minecraft:oak_log"), "nothing grows on the desert");
    }

    @Test void blueprintsSavedBeforeAreLayered() {
        PlanetBlueprint old = PlanetBlueprint.fromJson("{\"name\":\"o\",\"radius\":20,\"air\":8,\"layers\":[{\"block\":\"x\",\"thickness\":1}]}");
        assertEquals(PlanetBlueprint.Mode.LAYERS, old.mode());
        assertEquals(PlanetBlueprint.RANDOM, old.biome());
        PlanetBlueprint g = bp(32, 8, 9, "minecraft:desert", 0);
        assertEquals(g, PlanetBlueprint.fromJson(g.toJson()));
    }
}
