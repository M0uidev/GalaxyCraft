package dev.moui.galaxycraft.view;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Entering a world: the camera comes from space, high above the player's planet looking down at
 * it, and zooms in to the player's own view. Galaxy units, offsets from Mario's feet (as SMG2
 * places its camera). No Minecraft types, so it is unit tested.
 */
public final class IntroCamera {
    /** Where the camera is (from Mario's feet), where it looks and its top (both unit). */
    public record Pose(Vector3d offset, Vector3d look, Vector3d up) {}

    /** The turn from looking down to the player's view takes the last part of the zoom, from here. */
    static final double TURN_FROM = 0.55;

    private IntroCamera() {}

    /**
     * The pose t of the way in (0..1). planetUp: up where the player stands; end*: the player's
     * own camera then; far: how high above it starts.
     */
    public static Pose at(double t, Vector3d planetUp, Vector3d endOffset, Vector3d endLook, Vector3d endUp, double far) {
        t = Math.max(0, Math.min(1, t));
        Vector3d up = new Vector3d(planetUp).normalize();
        double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; // ease in and out
        Vector3d offset = new Vector3d(up).mul(far * (1 - e)).add(endOffset);
        // From above, the top of the view is where the player will face; at the end, its own.
        Vector3d top = flat(endLook, up);
        if (top.lengthSquared() < 1e-12) top = flat(endUp, up);
        if (top.lengthSquared() < 1e-12) top = flat(Math.abs(up.x) < 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 0, 1), up);
        Quaterniond start = orientation(new Vector3d(up).negate(), top.normalize());
        Quaterniond end = orientation(new Vector3d(endLook).normalize(), new Vector3d(endUp).normalize());
        double s = t <= TURN_FROM ? 0 : (t - TURN_FROM) / (1 - TURN_FROM);
        Quaterniond q = start.slerp(end, s * s * (3 - 2 * s));
        return new Pose(offset, q.transform(new Vector3d(0, 0, -1)), q.transform(new Vector3d(0, 1, 0)));
    }

    /** v with its part along up taken away. */
    private static Vector3d flat(Vector3d v, Vector3d up) {
        return new Vector3d(v).sub(new Vector3d(up).mul(v.dot(up)));
    }

    /** The rotation taking -Z to look and +Y to up. */
    private static Quaterniond orientation(Vector3d look, Vector3d up) {
        Vector3d u = new Vector3d(up).sub(new Vector3d(look).mul(up.dot(look))).normalize(); // square to look
        Vector3d right = new Vector3d(look).cross(u);
        return new Quaterniond().setFromNormalized(new org.joml.Matrix3d(right, u, new Vector3d(look).negate()));
    }
}
