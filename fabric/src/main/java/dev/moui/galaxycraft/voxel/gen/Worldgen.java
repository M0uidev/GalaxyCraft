package dev.moui.galaxycraft.voxel.gen;

/** What generated planets are made with: the noises for a seed and the biome table. */
public interface Worldgen {
    TerrainNoise noise(long seed);

    BiomeTable biomes();
}
