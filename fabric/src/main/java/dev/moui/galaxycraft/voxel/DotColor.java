package dev.moui.galaxycraft.voxel;

import java.util.Map;

/**
 * The color a far planet's dot of light has (0xRRGGBB), from its recipe without making it: a
 * generated planet's biome (Auto planets, several biomes and sea, a green-blue), a blueprint's
 * top layer's map color.
 */
public final class DotColor {
    /** Several biomes and some sea, seen from afar. */
    public static final int AUTO = 0x5E8E6A;
    private static final int PLAINS = 0x6A9A3A;
    private static final Map<String, Integer> BY_BIOME = Map.ofEntries(Map.entry("minecraft:plains", PLAINS),
            Map.entry("minecraft:forest", 0x4E7A2E), Map.entry("minecraft:birch_forest", 0x6A8E3E), Map.entry("minecraft:dark_forest", 0x3A5A22),
            Map.entry("minecraft:jungle", 0x3E8A1E), Map.entry("minecraft:swamp", 0x4C5E3A), Map.entry("minecraft:taiga", 0x4A6E4E),
            Map.entry("minecraft:old_growth_pine_taiga", 0x56603A), Map.entry("minecraft:windswept_hills", 0x7A8A7A),
            Map.entry("minecraft:desert", 0xD8C88A), Map.entry("minecraft:savanna", 0xA8A048), Map.entry("minecraft:badlands", 0xB8653A),
            Map.entry("minecraft:snowy_plains", 0xF0F4F8), Map.entry("minecraft:snowy_taiga", 0xD8E2E6),
            Map.entry("minecraft:ocean", 0x3A5AC8), Map.entry("minecraft:deep_ocean", 0x2A3E98), Map.entry("minecraft:frozen_ocean", 0xA8C0E8));

    private DotColor() {}

    /** A generated planet's: of its one biome, or null for several (Auto); an unknown biome as plains. */
    public static int generated(String biome) {
        return biome == null ? AUTO : BY_BIOME.getOrDefault(biome, PLAINS);
    }

    /** A blueprint's: its top layer's color, or AUTO for none (negative). */
    public static int blueprint(int topColor) {
        return topColor < 0 ? AUTO : topColor & 0xFFFFFF;
    }
}
