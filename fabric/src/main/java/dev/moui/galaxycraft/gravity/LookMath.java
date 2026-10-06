package dev.moui.galaxycraft.gravity;

import org.joml.Vector3d;

/** Minecraft yaw/pitch (degrees) to look vectors. */
public final class LookMath {
    private LookMath() {}

    /** Minecraft convention: yaw 0 looks to +Z, yaw 90 to -X; pitch 90 looks down. */
    public static Vector3d direction(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new Vector3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /** Yaw (degrees) of a look vector, the inverse of {@link #direction}. */
    public static double yaw(Vector3d d) {
        return Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    /** Pitch (degrees) of a look vector, the inverse of {@link #direction}. */
    public static double pitch(Vector3d d) {
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -d.y / d.length()))));
    }
}
