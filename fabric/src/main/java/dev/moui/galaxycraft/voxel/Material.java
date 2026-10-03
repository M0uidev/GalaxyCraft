package dev.moui.galaxycraft.voxel;

/** What a cell is made of, with its tiles in the 4×4 block atlas (tools/voxel_atlas.py). */
public enum Material {
    AIR(-1, -1, -1, null),
    BEDROCK(4, 4, 4, null),
    STONE(3, 3, 3, "minecraft:stone"),
    DIRT(2, 2, 2, "minecraft:dirt"),
    GRASS(0, 1, 2, "minecraft:grass_block");

    public final int top, side, bottom;
    /** The item that places it, or null if none does. */
    public final String item;

    Material(int top, int side, int bottom, String item) {
        this.top = top;
        this.side = side;
        this.bottom = bottom;
        this.item = item;
    }

    public boolean solid() {
        return this != AIR;
    }

    /** Whether the player can break it (bedrock seals the hollow center). */
    public boolean breakable() {
        return this != AIR && this != BEDROCK;
    }

    public static Material ofItem(String id) {
        for (Material m : values()) if (id.equals(m.item)) return m;
        return null;
    }
}
