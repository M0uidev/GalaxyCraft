package dev.moui.galaxycraft.voxel;

/**
 * How a planet far away is shown, by how big it looks: a dot of light while it spans under
 * DOT_BELOW degrees, a far view once over FAR_ABOVE (between, it keeps what it had: no flicker);
 * how far another system's star has opened into its planets as you near it; and
 * when such a system is loaded (its planets known) and left. No Minecraft types.
 */
public final class FarSight {
    public enum Level { DOT, FAR }

    static final double DOT_BELOW = 0.2, FAR_ABOVE = 0.3;
    /** A system's star opens into its planets from LOAD (they are known then) to OPEN blocks from it. */
    public static final double OPEN = 14_000;
    /** Blocks from a system's star: loaded within LOAD, left past LEAVE. */
    public static final double LOAD = 20_000, LEAVE = 25_000;

    private FarSight() {}

    /** Degrees a ball of that radius spans at that distance (same units). */
    public static double angle(double radius, double distance) {
        return Math.toDegrees(2 * Math.atan(radius / Math.max(distance, 1e-9)));
    }

    public static Level level(double angle, Level had) {
        if (angle > FAR_ABOVE) return Level.FAR;
        if (angle < DOT_BELOW) return Level.DOT;
        return had;
    }

    /** 0: only the star shows; 1: only its planets' dots (and views); linear between, by blocks from its star. */
    public static double opened(double blocks) {
        return Math.clamp((LOAD - blocks) / (LOAD - OPEN), 0, 1);
    }

    /** Whether a system that far (blocks) is loaded, having been loaded or not. */
    public static boolean load(double blocks, boolean loaded) {
        return blocks < (loaded ? LEAVE : LOAD);
    }
}
