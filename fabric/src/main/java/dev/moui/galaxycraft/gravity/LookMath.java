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
}
