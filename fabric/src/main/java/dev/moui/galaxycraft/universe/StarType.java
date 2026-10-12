package dev.moui.galaxycraft.universe;

/**
 * The kind of a system's star: what its planets are like (later stages read it: lava raises
 * volcanic biomes and heat, red dwarf cold, blue rare ores). Derived from the seed and the tier.
 */
public enum StarType {
    YELLOW(0xFFFFE680), RED_DWARF(0xFFFF6A4D), LAVA(0xFFFF9A2E), BLUE(0xFF7FB2FF);

    private static final long SALT = 0x2F6B8C1D4E9A7305L;

    /** Cumulative weights out of 100 for YELLOW, RED_DWARF, LAVA (the rest is BLUE), by tier. First draft. */
    private static final int[][] WEIGHTS = {
        {100, 100, 100}, // 0: yellow only
        {60, 85, 100},   // 1
        {60, 85, 100},   // 2
        {40, 65, 85},    // 3: 15 blue
        {30, 55, 75},    // 4: 25 blue
        {30, 55, 75},    // 5
    };

    /** The star's color, ARGB (the 3D map and the sky read it). */
    public final int argb;

    StarType(int argb) {
        this.argb = argb;
    }

    public static StarType of(int tier, long seed, boolean home) {
        if (home) return YELLOW;
        int[] w = WEIGHTS[Math.max(0, Math.min(Tier.MAX, tier))];
        int roll = (int) Math.floorMod(Universe.mix(seed ^ SALT), 100L);
        if (roll < w[0]) return YELLOW;
        if (roll < w[1]) return RED_DWARF;
        if (roll < w[2]) return LAVA;
        return BLUE;
    }
}
