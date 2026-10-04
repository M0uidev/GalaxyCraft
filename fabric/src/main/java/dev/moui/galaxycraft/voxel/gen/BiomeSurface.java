package dev.moui.galaxycraft.voxel.gen;

import java.util.Map;

/**
 * The blocks each biome's ground is made of, top down: an optional cover above the top block (snow),
 * the top, a filler a few blocks deep and stone down to the bedrock. Biomes not listed are grass,
 * dirt and stone, as most of Minecraft's are.
 */
public final class BiomeSurface {
    public static final int FILLER_DEPTH = 3;

    public record Palette(String cover, String top, String filler, String stone) {}

    private static final Palette DEFAULT = new Palette(null, "minecraft:grass_block", "minecraft:dirt", "minecraft:stone");
    private static final Palette SNOWY = new Palette("minecraft:snow", "minecraft:grass_block[snowy=true]", "minecraft:dirt", "minecraft:stone");
    private static final Palette BADLANDS = new Palette(null, "minecraft:red_sand", "minecraft:terracotta", "minecraft:terracotta");
    private static final Palette PODZOL = new Palette(null, "minecraft:podzol", "minecraft:dirt", "minecraft:stone");
    private static final Map<String, Palette> BY_BIOME = Map.ofEntries(
            Map.entry("minecraft:desert", new Palette(null, "minecraft:sand", "minecraft:sandstone", "minecraft:sandstone")),
            Map.entry("minecraft:badlands", BADLANDS),
            Map.entry("minecraft:eroded_badlands", BADLANDS),
            Map.entry("minecraft:wooded_badlands", BADLANDS),
            Map.entry("minecraft:snowy_plains", SNOWY),
            Map.entry("minecraft:snowy_taiga", SNOWY),
            Map.entry("minecraft:snowy_slopes", new Palette(null, "minecraft:snow_block", "minecraft:snow_block", "minecraft:stone")),
            Map.entry("minecraft:grove", SNOWY),
            Map.entry("minecraft:ice_spikes", new Palette(null, "minecraft:snow_block", "minecraft:snow_block", "minecraft:packed_ice")),
            Map.entry("minecraft:frozen_peaks", new Palette(null, "minecraft:packed_ice", "minecraft:snow_block", "minecraft:stone")),
            Map.entry("minecraft:jagged_peaks", new Palette(null, "minecraft:snow_block", "minecraft:stone", "minecraft:stone")),
            Map.entry("minecraft:stony_peaks", new Palette(null, "minecraft:stone", "minecraft:calcite", "minecraft:stone")),
            Map.entry("minecraft:mushroom_fields", new Palette(null, "minecraft:mycelium", "minecraft:dirt", "minecraft:stone")),
            Map.entry("minecraft:old_growth_pine_taiga", PODZOL),
            Map.entry("minecraft:old_growth_spruce_taiga", PODZOL),
            Map.entry("minecraft:mangrove_swamp", new Palette(null, "minecraft:mud", "minecraft:mud", "minecraft:stone")),
            Map.entry("minecraft:windswept_gravelly_hills", new Palette(null, "minecraft:gravel", "minecraft:gravel", "minecraft:stone")));

    private BiomeSurface() {}

    /** Every block a generated planet can be made of, bedrock included. */
    public static java.util.Set<String> blocks() {
        java.util.Set<String> all = new java.util.TreeSet<>(java.util.List.of("minecraft:bedrock"));
        for (Palette p : java.util.stream.Stream.concat(java.util.stream.Stream.of(DEFAULT), BY_BIOME.values().stream()).toList()) {
            if (p.cover() != null) all.add(p.cover());
            all.addAll(java.util.List.of(p.top(), p.filler(), p.stone()));
        }
        return all;
    }

    public static Palette of(String biome) {
        return BY_BIOME.getOrDefault(biome, DEFAULT);
    }
}
