package dev.moui.galaxycraft.voxel.gen;

import java.util.Arrays;
import java.util.List;

/**
 * Minecraft 1.7's biomes as its terrain knew them: how high the ground sits (root) and how much it
 * rises and falls (variation), their climate zone, and the id the biome has in today's Minecraft
 * (its blocks, colors and plants come from there). Hills variants share their biome's id.
 */
public enum LegacyBiome {
    OCEAN("minecraft:ocean", -1, 0.1, Zone.WATER),
    DEEP_OCEAN("minecraft:deep_ocean", -1.8, 0.1, Zone.WATER),
    FROZEN_OCEAN("minecraft:frozen_ocean", -1, 0.1, Zone.WATER),
    DEEP_FROZEN_OCEAN("minecraft:deep_frozen_ocean", -1.8, 0.1, Zone.WATER),
    RIVER("minecraft:river", -0.5, 0, Zone.WATER),
    FROZEN_RIVER("minecraft:frozen_river", -0.5, 0, Zone.WATER),
    BEACH("minecraft:beach", 0, 0.025, Zone.TEMPERATE),
    SNOWY_BEACH("minecraft:snowy_beach", 0, 0.025, Zone.SNOWY),
    STONY_SHORE("minecraft:stony_shore", 0.1, 0.8, Zone.COLD),
    PLAINS("minecraft:plains", 0.125, 0.05, Zone.TEMPERATE),
    DESERT_HILLS("minecraft:desert", 0.45, 0.3, Zone.WARM),
    DESERT("minecraft:desert", 0.125, 0.05, Zone.WARM, DESERT_HILLS),
    SAVANNA_PLATEAU("minecraft:savanna_plateau", 1.5, 0.025, Zone.WARM),
    SAVANNA("minecraft:savanna", 0.125, 0.05, Zone.WARM, SAVANNA_PLATEAU),
    WOODED_BADLANDS("minecraft:wooded_badlands", 1.5, 0.025, Zone.WARM),
    BADLANDS("minecraft:badlands", 0.1, 0.2, Zone.WARM, WOODED_BADLANDS),
    FOREST_HILLS("minecraft:forest", 0.45, 0.3, Zone.TEMPERATE),
    FOREST("minecraft:forest", 0.1, 0.2, Zone.TEMPERATE, FOREST_HILLS),
    BIRCH_HILLS("minecraft:birch_forest", 0.45, 0.3, Zone.TEMPERATE),
    BIRCH_FOREST("minecraft:birch_forest", 0.1, 0.2, Zone.TEMPERATE, BIRCH_HILLS),
    DARK_FOREST("minecraft:dark_forest", 0.1, 0.2, Zone.TEMPERATE),
    JUNGLE_HILLS("minecraft:jungle", 0.45, 0.3, Zone.TEMPERATE),
    JUNGLE("minecraft:jungle", 0.1, 0.2, Zone.TEMPERATE, JUNGLE_HILLS),
    SWAMP("minecraft:swamp", -0.2, 0.1, Zone.TEMPERATE),
    WINDSWEPT_FOREST("minecraft:windswept_forest", 1, 0.5, Zone.COLD),
    WINDSWEPT_HILLS("minecraft:windswept_hills", 1, 0.5, Zone.COLD, WINDSWEPT_FOREST),
    TAIGA_HILLS("minecraft:taiga", 0.45, 0.3, Zone.COLD),
    TAIGA("minecraft:taiga", 0.2, 0.2, Zone.COLD, TAIGA_HILLS),
    PINE_TAIGA_HILLS("minecraft:old_growth_pine_taiga", 0.45, 0.3, Zone.COLD),
    OLD_GROWTH_PINE_TAIGA("minecraft:old_growth_pine_taiga", 0.2, 0.2, Zone.COLD, PINE_TAIGA_HILLS),
    SNOWY_HILLS("minecraft:snowy_plains", 0.45, 0.3, Zone.SNOWY),
    SNOWY_PLAINS("minecraft:snowy_plains", 0.125, 0.05, Zone.SNOWY, SNOWY_HILLS),
    SNOWY_TAIGA_HILLS("minecraft:snowy_taiga", 0.45, 0.3, Zone.SNOWY),
    SNOWY_TAIGA("minecraft:snowy_taiga", 0.2, 0.2, Zone.SNOWY, SNOWY_TAIGA_HILLS);

    /** Climate zones, warm to snowy; WATER for seas and rivers. */
    public enum Zone { WARM, TEMPERATE, COLD, SNOWY, WATER }

    /** What 1.7 picks from per zone (a biome listed twice comes twice as often). */
    static final List<List<LegacyBiome>> PICKS = List.of(
            List.of(DESERT, DESERT, SAVANNA, SAVANNA, PLAINS, BADLANDS),
            List.of(FOREST, DARK_FOREST, WINDSWEPT_HILLS, PLAINS, BIRCH_FOREST, SWAMP, JUNGLE),
            List.of(FOREST, WINDSWEPT_HILLS, TAIGA, PLAINS, OLD_GROWTH_PINE_TAIGA),
            List.of(SNOWY_PLAINS, SNOWY_PLAINS, SNOWY_TAIGA));

    private final String id;
    private final double root, variation;
    private final Zone zone;
    private final LegacyBiome hills;

    LegacyBiome(String id, double root, double variation, Zone zone) {
        this(id, root, variation, zone, null);
    }

    LegacyBiome(String id, double root, double variation, Zone zone, LegacyBiome hills) {
        this.id = id;
        this.root = root;
        this.variation = variation;
        this.zone = zone;
        this.hills = hills;
    }

    public String id() {
        return id;
    }

    public double root() {
        return root;
    }

    public double variation() {
        return variation;
    }

    public Zone zone() {
        return zone;
    }

    /** Its hills variant (itself if it has none). */
    public LegacyBiome hills() {
        return hills == null ? this : hills;
    }

    public boolean ocean() {
        return root <= -1;
    }

    /** A beach or shore: land at the sea's edge. */
    public boolean shore() {
        return this == BEACH || this == SNOWY_BEACH || this == STONY_SHORE;
    }

    /** Its water freezes (snowy zone, or a frozen sea or river). */
    public boolean frozen() {
        return zone == Zone.SNOWY || this == FROZEN_OCEAN || this == DEEP_FROZEN_OCEAN || this == FROZEN_RIVER;
    }

    /** The 1.7 biome a one-biome planet of that id is (its main one, not a hills variant); null if none. */
    public static LegacyBiome of(String id) {
        for (LegacyBiome b : values())
            if (b.id.equals(id) && Arrays.stream(values()).noneMatch(o -> o.hills == b)) return b;
        return null;
    }

    /** The land biomes a planet can be all of: what "random" picks from. */
    public static List<String> land() {
        return PICKS.stream().flatMap(List::stream).map(LegacyBiome::id).distinct().sorted().toList();
    }

    /** Every biome a one-biome planet can be: land and seas. */
    public static List<String> all() {
        return java.util.stream.Stream.concat(land().stream(), Arrays.stream(new String[] {OCEAN.id, DEEP_OCEAN.id, FROZEN_OCEAN.id}))
                .sorted().toList();
    }
}
