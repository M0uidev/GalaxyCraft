package dev.moui.galaxycraft.voxel;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Minecraft biome each column of a planet is (a column: a face's (i, j), all its layers; index
 * cell / layers). What tints grass, leaves and water as Minecraft tints them, and what mobs spawn
 * there. A planet made of layers is plains all over.
 */
public final class PlanetBiomes {
    public static final String PLAINS = "minecraft:plains";

    /**
     * A tint (0xRRGGBB) may carry in its top byte the biome color it stands for (Minecraft's
     * ColorResolver the block's tint asks for); its RGB is then the color away from any biome.
     */
    public static final int FIXED = 0, GRASS = 1, FOLIAGE = 2, DRY_FOLIAGE = 3, WATER = 4, KINDS = 5;

    private final String[] names;
    private final byte[] columns; // index into names; null: all names[0]

    private PlanetBiomes(String[] names, byte[] columns) {
        this.names = names;
        this.columns = columns;
    }

    public static PlanetBiomes uniform(String biome) {
        return new PlanetBiomes(new String[] {biome}, null);
    }

    /** One biome per column (up to 256 different ones). */
    public static PlanetBiomes of(String[] perColumn) {
        Map<String, Integer> index = new LinkedHashMap<>();
        byte[] cols = new byte[perColumn.length];
        for (int c = 0; c < perColumn.length; c++) {
            Integer i = index.get(perColumn[c]);
            if (i == null) {
                if (index.size() == 256) throw new IllegalArgumentException("more than 256 biomes");
                index.put(perColumn[c], i = index.size());
            }
            cols[c] = (byte) (int) i;
        }
        String[] names = index.keySet().toArray(new String[0]);
        return names.length == 1 ? uniform(names[0]) : new PlanetBiomes(names, cols);
    }

    /** As saved: names and, for more than one, a byte per column. */
    public static PlanetBiomes of(String[] names, byte[] columns) {
        if (names.length == 0) throw new IllegalArgumentException("no biomes");
        if (names.length == 1) return uniform(names[0]);
        for (byte b : columns) if ((b & 0xFF) >= names.length) throw new IllegalArgumentException("bad biome index");
        return new PlanetBiomes(names.clone(), columns.clone());
    }

    public String at(int column) {
        return columns == null ? names[0] : names[columns[column] & 0xFF];
    }

    public boolean uniform() {
        return columns == null;
    }

    public List<String> names() {
        return List.of(names);
    }

    /** The byte per column (null if uniform). */
    public byte[] columns() {
        return columns == null ? null : columns.clone();
    }

    /** Which biome color a tint stands for (FIXED: none). */
    public static int kind(int tint) {
        return tint >>> 24;
    }

    /** A tint that stands for a biome color, rgb away from biomes. */
    public static int tint(int kind, int rgb) {
        return kind << 24 | rgb & 0xFFFFFF;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlanetBiomes b && Arrays.equals(names, b.names) && Arrays.equals(columns, b.columns);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(names) * 31 + Arrays.hashCode(columns);
    }
}
