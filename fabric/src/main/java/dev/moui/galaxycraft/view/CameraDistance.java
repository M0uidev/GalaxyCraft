package dev.moui.galaxycraft.view;

/**
 * Third person's distance eases toward the one chosen for where the player is (on a planet,
 * gliding, out in space), so the camera pulls back and closes in smoothly, the same at any frame
 * rate.
 */
public final class CameraDistance {
    /** Seconds for the distance to cover about 63 % of the way. */
    public static final double EASE_SECONDS = 0.45;

    private CameraDistance() {}

    /** The distance after dt seconds, from current toward target. */
    public static double approach(double current, double target, double dt) {
        if (dt <= 0) return current;
        return target + (current - target) * Math.exp(-dt / EASE_SECONDS);
    }
}
