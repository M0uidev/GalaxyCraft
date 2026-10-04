package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.gen.TerrainNoise.Field;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

/**
 * What lies under a generated planet's ground: caves carved where a 3D noise is high (seamless as
 * the terrain is, flattened like Minecraft's), and ore veins at their depths, Minecraft's from coal
 * near the surface to diamonds by the bedrock, the deepslate kind in the deepslate.
 */
final class Underground {
    /** Noise space per block for caves (the game's cave noise is about 32 blocks across). */
    static final double CAVE_SCALE = 1;
    /** Caves are this many times flatter than wide. */
    static final double FLAT = 1.6;
    /** The roof kept over caves where there are no entrances, and under and beside water (blocks). */
    static final int ROOF = 3;
    /** Openings to the surface need the noise this much higher than the caves under them: fewer holes. */
    static final double ENTRANCE_MARGIN = 0.12;

    /** An ore: its block, from what fraction of the ground's depth to what (0 the top, 1 the bedrock), veins per 1000 cells, blocks per vein. */
    record Ore(String name, double from, double to, double rate, int size) {
        String deepslate() {
            return name.replace("minecraft:", "minecraft:deepslate_");
        }
    }

    static final List<Ore> ORES = List.of(
            new Ore("minecraft:coal_ore", 0.05, 0.6, 1.2, 8), new Ore("minecraft:copper_ore", 0.15, 0.6, 0.8, 6),
            new Ore("minecraft:iron_ore", 0.2, 0.9, 1.0, 5), new Ore("minecraft:lapis_ore", 0.5, 1, 0.25, 4),
            new Ore("minecraft:gold_ore", 0.6, 1, 0.3, 4), new Ore("minecraft:redstone_ore", 0.7, 1, 0.5, 5),
            new Ore("minecraft:diamond_ore", 0.85, 1, 0.15, 3));
    static final String STONE = "minecraft:stone", DEEPSLATE = "minecraft:deepslate";

    private Underground() {}

    /** The noise a cave needs: caves 1 (a few) to 100 (many). */
    static double threshold(int caves) {
        return 0.55 - 0.45 * caves / 100.0;
    }

    /**
     * Carves caves into cells. height: each column's ground above the base surface; dirs: its
     * direction (x y z); keepRoof: columns that keep a ROOF over their caves whatever entrances says
     * (wet ones and those beside water). Columns are carved in parallel, a face each.
     */
    static void carve(CubeSphere grid, int depth, char[] cells, int[] height, float[] dirs, boolean[] keepRoof,
            TerrainNoise noise, int caves, boolean entrances, double radius) {
        if (caves <= 0) return;
        int n = grid.n;
        double t = threshold(caves);
        IntStream.range(0, 6).parallel().forEach(f -> {
            for (int col = f * n * n; col < (f + 1) * n * n; col++) {
                int base = col * grid.layers, top = depth - 1 + height[col];
                boolean roof = keepRoof[col] || !entrances;
                double x = dirs[3 * col], y = dirs[3 * col + 1], z = dirs[3 * col + 2];
                for (int k = 1; k <= top; k++) {
                    if (cells[base + k] == Blocks.AIR) continue;
                    boolean nearTop = k > top - ROOF;
                    if (nearTop && roof) continue;
                    double r = (radius + (grid.radius(k) + 0.5 - radius) * FLAT) * CAVE_SCALE;
                    double v = noise.value(Field.CAVES, x * r, y * r, z * r);
                    if (v > t + (nearTop ? ENTRANCE_MARGIN : 0)) cells[base + k] = (char) Blocks.AIR;
                }
                // Nothing left floating on a hole: snow cover over a carved top.
                if (top + 1 < grid.layers && cells[base + top] == Blocks.AIR) cells[base + top + 1] = (char) Blocks.AIR;
            }
        });
    }

    /** Lays ore veins in stone and deepslate: ores percent of Minecraft's amount (100 = as many). */
    static void ores(CubeSphere grid, int depth, char[] cells, int[] height, long seed, int ores, ToIntFunction<String> ids) {
        if (ores <= 0) return;
        int stone = ids.applyAsInt(STONE), deepslate = ids.applyAsInt(DEEPSLATE);
        int columns = 6 * grid.n * grid.n;
        double crust = (double) columns * (depth - 1);
        Random rnd = new Random(seed * 31 + 7);
        for (Ore ore : ORES) {
            int plain = ids.applyAsInt(ore.name()), deep = ids.applyAsInt(ore.deepslate());
            long veins = Math.round(crust / 1000 * ore.rate() * ores / 100);
            for (long v = 0; v < veins; v++) {
                int col = rnd.nextInt(columns), top = depth - 1 + height[col];
                double f = ore.from() + rnd.nextDouble() * (ore.to() - ore.from());
                int k = Math.max(1, top - (int) Math.round(f * (top - 1)));
                int cell = col * grid.layers + k;
                for (int b = 0; b < ore.size() && cell >= 0; b++) {
                    if (cells[cell] == stone) cells[cell] = (char) plain;
                    else if (cells[cell] == deepslate) cells[cell] = (char) deep;
                    cell = grid.neighbor(cell, rnd.nextInt(6));
                }
            }
        }
    }

    /** Every block this adds. */
    static List<String> blocks() {
        List<String> all = new java.util.ArrayList<>(List.of(STONE, DEEPSLATE));
        for (Ore o : ORES) all.addAll(List.of(o.name(), o.deepslate()));
        return all;
    }
}
