package dev.moui.galaxycraft.voxel.gen;

/** What generated planets need from Minecraft itself: what grows on each biome. */
public interface Worldgen {
    /** What grows on each biome; null for nothing. */
    Vegetation.Library vegetation();
}
