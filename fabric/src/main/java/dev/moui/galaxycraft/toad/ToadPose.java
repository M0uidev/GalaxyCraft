package dev.moui.galaxycraft.toad;

import org.joml.Matrix4f;

/** A skeleton's joints at a pose: pure maths, no model data. */
public final class ToadPose {
    private ToadPose() {}

    /**
     * The world matrix of every joint from its parent (-1: none) and its local transform as
     * {@code sx, sy, sz, rx, ry, rz, tx, ty, tz} (radians): translate, rotate Z then Y then X, scale,
     * as J3D composes them. Parents may come after their children; a loop is an error.
     */
    public static Matrix4f[] worlds(int[] parent, float[][] srt) {
        int n = parent.length;
        Matrix4f[] out = new Matrix4f[n];
        for (int j = 0; j < n; j++) resolve(j, parent, srt, out, 0);
        return out;
    }

    private static Matrix4f resolve(int j, int[] parent, float[][] srt, Matrix4f[] out, int depth) {
        if (out[j] != null) return out[j];
        if (depth > parent.length) throw new IllegalArgumentException("a loop in the joints' parents");
        float[] s = srt[j];
        Matrix4f local = new Matrix4f().translate(s[6], s[7], s[8]).rotateZYX(s[5], s[4], s[3]).scale(s[0], s[1], s[2]);
        out[j] = parent[j] < 0 ? local : new Matrix4f(resolve(parent[j], parent, srt, out, depth + 1)).mul(local);
        return out[j];
    }
}
