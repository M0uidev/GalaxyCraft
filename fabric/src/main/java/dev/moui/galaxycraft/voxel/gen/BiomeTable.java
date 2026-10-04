package dev.moui.galaxycraft.voxel.gen;

import java.util.List;

/** Minecraft's overworld biomes found at the surface, by climate (client/McWorldgen in the game). Biomes by id. */
public interface BiomeTable {
    /** The biome Minecraft puts at that climate; with water false, only land ones (no oceans, rivers, beaches). */
    String find(Climate c, boolean water);

    /** Where that biome appears at the surface (all its places together), or null if there is no such biome. */
    Climate.Span span(String biome);

    /** The land biomes: what "random" picks from. */
    List<String> land();

    /** Every surface biome, land and water: what the editor offers. */
    List<String> all();

    /** An ocean or a river: its planet is mostly water. */
    boolean watery(String biome);
}
