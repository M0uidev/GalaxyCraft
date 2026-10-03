package dev.moui.galaxycraft.voxel;

/**
 * What a cell is made of, with its tiles in the 4×4 block atlas (tools/voxel_atlas.py). At most
 * 16: a cell keeps its material in the low nibble (the high one is a fluid's level).
 */
public enum Material {
    AIR(-1, -1, -1, null),
    BEDROCK(4, 4, 4, null),
    STONE(3, 3, 3, "minecraft:stone"),
    DIRT(2, 2, 2, "minecraft:dirt"),
    GRASS(0, 1, 2, "minecraft:grass_block"),
    ICE(5, 5, 5, "minecraft:ice"),
    WATER(6, 6, 6, "minecraft:water_bucket"),
    LAVA(7, 7, 7, "minecraft:lava_bucket"),
    COBBLESTONE(8, 8, 8, "minecraft:cobblestone"),
    OBSIDIAN(9, 9, 9, "minecraft:obsidian");

    public final int top, side, bottom;
    /** The item that places it, or null if none does. */
    public final String item;

    Material(int top, int side, int bottom, String item) {
        this.top = top;
        this.side = side;
        this.bottom = bottom;
        this.item = item;
    }

    /** A block: it collides, hides what is behind it and stops the crosshair. */
    public boolean solid() {
        return this != AIR && !fluid();
    }

    public boolean fluid() {
        return this == WATER || this == LAVA;
    }

    /** Whether the player can break it (bedrock seals the hollow center; fluids are scooped). */
    public boolean breakable() {
        return solid() && this != BEDROCK;
    }

    /** What is left where it is broken: ice melts into water, as in Minecraft. */
    public Material broken() {
        return this == ICE ? WATER : AIR;
    }

    public static Material ofItem(String id) {
        for (Material m : values()) if (id.equals(m.item)) return m;
        return null;
    }
}
