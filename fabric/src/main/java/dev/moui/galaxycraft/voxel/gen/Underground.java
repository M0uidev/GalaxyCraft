package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.CubeSphere;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * What lies under a generated planet's ground besides its caves ({@link Caves}): ore veins at their
 * depths, Minecraft's from coal near the surface to diamonds by the bedrock, the deepslate kind in
 * the deepslate.
 */
final class Underground {
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
