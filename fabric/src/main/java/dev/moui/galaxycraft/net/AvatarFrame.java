package dev.moui.galaxycraft.net;

import org.joml.Matrix4d;
import org.joml.Vector3d;

/**
 * Where a remote player's body stands: from its feet, its up and where it looks (all in the universe's
 * axes, which every client shares), the matrix taking Minecraft's entity space (blocks, y up, the front
 * of the body at +Z, +X its left) to the game's units.
 */
public final class AvatarFrame {
    private AvatarFrame() {}

    /** Entity blocks -> game units: scale (units per block) on every axis, origin at the feet. */
    public static Matrix4d matrix(Vector3d feet, Vector3d up, Vector3d look, double unitsPerBlock) {
        Vector3d y = new Vector3d(up).normalize();
        Vector3d z = new Vector3d(look).sub(new Vector3d(y).mul(look.dot(y))); // the look, flattened onto the ground
        if (z.lengthSquared() < 1e-12) { // straight up or down: any heading will do, but a stable one
            Vector3d any = Math.abs(y.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
            z = new Vector3d(any).sub(new Vector3d(y).mul(any.dot(y)));
        }
        z.normalize();
        Vector3d x = new Vector3d(y).cross(z); // y × z = x: +X is the left of someone facing +Z
        return new Matrix4d(x.x * unitsPerBlock, x.y * unitsPerBlock, x.z * unitsPerBlock, 0,
                y.x * unitsPerBlock, y.y * unitsPerBlock, y.z * unitsPerBlock, 0,
                z.x * unitsPerBlock, z.y * unitsPerBlock, z.z * unitsPerBlock, 0,
                feet.x, feet.y, feet.z, 1);
    }

    /** Minecraft's xRot: degrees, positive looking down. */
    public static float pitchDegrees(Vector3d up, Vector3d look) {
        double s = new Vector3d(look).normalize().dot(new Vector3d(up).normalize());
        return (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, s))));
    }
}
