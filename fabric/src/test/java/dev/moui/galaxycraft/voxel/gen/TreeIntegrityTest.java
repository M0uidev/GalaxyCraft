package dev.moui.galaxycraft.voxel.gen;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Oaks planted on generated 1.7 terrain: every one has all its five logs, standing on the ground. */
class TreeIntegrityTest {
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final int LOGS = 5;

    private static synchronized int id(String name) {
        return IDS.computeIfAbsent(name, k -> IDS.size() + 1);
    }

    /** An oak: dirt under the trunk, five logs, a 3×3 crown above and around the top log. */
    private static Vegetation.Thing oak() {
        List<int[]> at = new ArrayList<>();
        List<String> names = new ArrayList<>();
        at.add(new int[] {0, 0, 0});
        names.add("minecraft:dirt");
        for (int y = 1; y <= LOGS; y++) {
            at.add(new int[] {0, y, 0});
            names.add("minecraft:oak_log[axis=y]");
        }
        for (int y = 4; y <= 6; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++)
                    if (!(x == 0 && z == 0 && y <= LOGS)) {
                        at.add(new int[] {x, y, z});
                        names.add("minecraft:oak_leaves");
                    }
        int n = at.size();
        int[] dx = new int[n], dy = new int[n], dz = new int[n];
        for (int b = 0; b < n; b++) {
            dx[b] = at.get(b)[0];
            dy[b] = at.get(b)[1];
            dz[b] = at.get(b)[2];
        }
        return new Vegetation.Thing(0, 0, dx, dy, dz, names.toArray(String[]::new));
    }

    private static final Vegetation.Library LIBRARY = biome -> List.of(new Vegetation.Patch(List.of(oak())));

    record Tally(int trees, int gaps, int floating) {}

    /** For every column holding logs: its logs are a run of LOGS, and under the run is solid ground. */
    static Tally scan(PlanetGenerator.Cells c) {
        CubeSphere g = c.grid();
        char log = (char) id("minecraft:oak_log[axis=y]");
        int trees = 0, gaps = 0, floating = 0;
        for (int col = 0; col < g.cellCount() / g.layers; col++) {
            int base = col * g.layers, first = -1, count = 0;
            for (int k = 0; k < g.layers; k++)
                if (c.cells()[base + k] == log) {
                    if (first < 0) first = k;
                    count++;
                }
            if (count == 0) continue;
            trees++;
            boolean run = count == LOGS && c.cells()[base + first + LOGS - 1] == log;
            if (!run) gaps++;
            char under = c.cells()[base + first - 1];
            if (under == Blocks.AIR || under == (char) id("minecraft:oak_leaves") || under == (char) id("minecraft:water")) floating++;
        }
        return new Tally(trees, gaps, floating);
    }

    private static PlanetGenerator.Cells planet(int radius, long seed) {
        PlanetBlueprint bp = PlanetBlueprint.standard("t", radius).withMode(PlanetBlueprint.Mode.GENERATED)
                .withBiome(seed, PlanetBlueprint.RANDOM, PlanetBlueprint.AUTO).withUnderground(0, false, 0).withPlants(100);
        return PlanetGenerator.cells(bp, LIBRARY, TreeIntegrityTest::id);
    }

    @Test void everyOakIsWholeAndOnTheGround() {
        int trees = 0, gaps = 0, floating = 0;
        for (int radius : new int[] {32, 64})
            for (long seed = 1; seed <= 4; seed++) {
                Tally t = scan(planet(radius, seed));
                trees += t.trees();
                gaps += t.gaps();
                floating += t.floating();
            }
        System.out.println("oaks " + trees + ", with a log missing " + gaps + ", floating " + floating);
        assertTrue(trees > 50, "the planets grew trees: " + trees);
        assertEquals(0, gaps, "oaks with a log missing");
        assertEquals(0, floating, "oaks over air, leaves or water");
    }
}
