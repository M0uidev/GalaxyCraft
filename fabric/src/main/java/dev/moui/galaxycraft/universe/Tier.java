package dev.moui.galaxycraft.universe;

/**
 * How far a system is from home, as a tier 0 to 5: rings by distance plus one step of noise from
 * the system's own seed. Derived, never stored. No Minecraft types.
 */
public final class Tier {
    public static final int MAX = 5;
    /** Ring r ends at EDGES[r] blocks from home (the last ring is open). First draft, tuned by playing. */
    static final double[] EDGES = {12_000, 20_000, 40_000, 70_000, 110_000};
    /** Highest tier of a not yet visited system a Warp Core of tier 0 to 3 reaches. */
    private static final int[] REACH = {1, 2, 4, 5};
    private static final long SALT = 0x7131E4A5D9C3B1F7L;

    private Tier() {}

    /** The ring of a distance (blocks from home), before noise. */
    public static int ring(double blocks) {
        for (int r = 0; r < EDGES.length; r++) if (blocks < EDGES[r]) return r;
        return MAX;
    }

    /** A system's tier: its ring, -1, 0 or +1 by its seed, kept in 0..MAX; home is always 0. */
    public static int of(double blocks, long seed, boolean home) {
        if (home) return 0;
        int noise = switch ((int) Math.floorMod(Universe.mix(seed ^ SALT), 4L)) {
            case 0 -> -1;
            case 3 -> 1;
            default -> 0;
        };
        return Math.max(0, Math.min(MAX, ring(blocks) + noise));
    }

    /** The highest tier of a not yet visited system a Warp Core of that tier reaches (clamped). */
    public static int reach(int coreTier) {
        return REACH[Math.max(0, Math.min(REACH.length - 1, coreTier))];
    }
}
