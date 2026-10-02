package dev.moui.galaxycraft.geom;

import org.joml.Matrix4x3d;
import org.joml.Vector3d;

/** A triangle with its unit face normal (counter-clockwise a→b→c seen from the normal side). */
public record Tri(Vector3d a, Vector3d b, Vector3d c, Vector3d n) {
    public static Tri of(Vector3d a, Vector3d b, Vector3d c) {
        Vector3d n = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(a)).normalize();
        return new Tri(a, b, c, n);
    }

    /** This triangle transformed by an affine matrix (normal recomputed). */
    public Tri transform(Matrix4x3d m) {
        return of(m.transformPosition(new Vector3d(a)), m.transformPosition(new Vector3d(b)),
                m.transformPosition(new Vector3d(c)));
    }
}
