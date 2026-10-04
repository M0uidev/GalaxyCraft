package dev.moui.galaxycraft.voxel.gen;

import java.util.List;

/** Minecraft's overworld biomes by climate (client/McWorldgen in the game). Biomes by id. */
public interface BiomeTable {
    /** The land biome Minecraft puts at that climate. */
    String find(Climate c);

    /** Where that biome appears at the surface (all its places together), or null if there is no such land biome. */
    Climate.Span span(String biome);

    /** The land biomes: no oceans, rivers, beaches or caves (no water yet). */
    List<String> land();
}
