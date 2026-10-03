package dev.moui.galaxycraft.voxel;

/**
 * Blocks the planet itself makes and reacts with: its layers, what water and lava turn into, and
 * the fluids. Any other block comes from {@link Blocks}. The order is that of planets saved as
 * GXP1 (a cell was the ordinal), so it must not change.
 */
public enum Material {
    AIR("minecraft:air"),
    BEDROCK("minecraft:bedrock"),
    STONE("minecraft:stone"),
    DIRT("minecraft:dirt"),
    GRASS("minecraft:grass_block[snowy=false]"),
    ICE("minecraft:ice"),
    WATER("minecraft:water[level=0]"),
    LAVA("minecraft:lava[level=0]"),
    COBBLESTONE("minecraft:cobblestone"),
    OBSIDIAN("minecraft:obsidian");

    /** Minecraft's block state, as BlockStateParser reads it. */
    public final String state;

    Material(String state) {
        this.state = state;
    }

    public boolean fluid() {
        return this == WATER || this == LAVA;
    }

    /** {@link Blocks#WATER}, {@link Blocks#LAVA} or {@link Blocks#NO_FLUID}. */
    public int fluidKind() {
        return this == WATER ? Blocks.WATER : this == LAVA ? Blocks.LAVA : Blocks.NO_FLUID;
    }
}
