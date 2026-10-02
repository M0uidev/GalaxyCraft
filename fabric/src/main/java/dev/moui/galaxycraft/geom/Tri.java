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

    /** Distance along the unit ray o + t·d to this triangle (either face), or +∞ if it misses. */
    public double rayHit(Vector3d o, Vector3d d) {
        Vector3d e1 = new Vector3d(b).sub(a), e2 = new Vector3d(c).sub(a);
        Vector3d p = new Vector3d(d).cross(e2);
        double det = e1.dot(p);
        if (Math.abs(det) < 1e-12) return Double.POSITIVE_INFINITY;
        Vector3d s = new Vector3d(o).sub(a);
        double u = s.dot(p) / det;
        if (u < 0 || u > 1) return Double.POSITIVE_INFINITY;
        Vector3d q = s.cross(e1);
        double v = d.dot(q) / det;
        if (v < 0 || u + v > 1) return Double.POSITIVE_INFINITY;
        double t = e2.dot(q) / det;
        return t >= 0 ? t : Double.POSITIVE_INFINITY;
    }
}
