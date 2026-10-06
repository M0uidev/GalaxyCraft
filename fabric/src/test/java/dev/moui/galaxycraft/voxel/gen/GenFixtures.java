package dev.moui.galaxycraft.voxel.gen;

import dev.moui.galaxycraft.voxel.Blocks;
import dev.moui.galaxycraft.voxel.CubeBlocks;
import dev.moui.galaxycraft.voxel.Material;
import java.util.List;

/** Stand-ins for Minecraft's worldgen in tests: smooth noises, a small biome table, block ids by name. */
public final class GenFixtures {
    public static final Blocks B = CubeBlocks.INSTANCE;

    private GenFixtures() {}

    /** Smooth waves, other ones per field and seed. */
    public static TerrainNoise waves(long seed) {
        return (f, x, y, z) -> {
            double s = seed * 0.37 + f.ordinal() * 1.7;
            return 0.6 * Math.sin(x * 0.05 + s) * Math.cos(y * 0.04 - s) + 0.4 * Math.sin(z * 0.06 + 2 * s);
        };
    }

    /** Desert where it is warm, plains elsewhere; peaks are high and rough. */
    public static final BiomeTable TABLE = new BiomeTable() {
        @Override public String find(Climate c, boolean water) {
            if (water && c.continentalness() < -0.3) return c.temperature() < -0.3 ? "minecraft:frozen_ocean" : "minecraft:ocean";
            return c.temperature() > 0 ? "minecraft:desert" : "minecraft:plains";
        }

        @Override public Climate.Span span(String biome) {
            return switch (biome) {
                case "minecraft:desert", "minecraft:plains" -> new Climate.Span(new Climate(0, 0, -1, -1, -1), new Climate(0.5, 0.5, 1, 1, 1));
                case "minecraft:jagged_peaks" -> new Climate.Span(new Climate(0.5, -1, 0.5, -1, -1), new Climate(1, -0.78, 1, 1, 1));
                case "minecraft:ocean", "minecraft:frozen_ocean" -> new Climate.Span(new Climate(-1, -1, -1, -1, -1), new Climate(-0.45, 1, 1, 1, 1));
                default -> null;
            };
        }

        @Override public List<String> land() {
            return List.of("minecraft:desert", "minecraft:jagged_peaks", "minecraft:plains");
        }

        @Override public List<String> all() {
            return List.of("minecraft:desert", "minecraft:frozen_ocean", "minecraft:jagged_peaks", "minecraft:ocean", "minecraft:plains");
        }

        @Override public boolean watery(String biome) {
            return biome.endsWith("ocean");
        }
    };

    /** Sand is cobblestone here, sandstone obsidian; the rest by its kind. */
    public static int id(String name) {
        Material m = name.contains("sandstone") ? Material.OBSIDIAN : name.contains("sand") ? Material.COBBLESTONE
                : name.contains("bedrock") ? Material.BEDROCK : name.contains("dirt") ? Material.DIRT
                : name.contains("grass") ? Material.GRASS : name.contains("snow") ? Material.ICE
                : name.contains("water") ? Material.WATER : name.contains("ice") ? Material.LAVA : name.contains("gravel") ? Material.DIRT
                : Material.STONE;
        return B.id(m);
    }

}
