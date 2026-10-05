package dev.moui.galaxycraft.gravity;

import org.joml.Vector3d;

/**
 * Something whose gravity reaches a region of space: a planet today, a flat station later. Units
 * are the caller's (blocks or galaxy units), the same for points and distances.
 */
public interface GravityBody {
    Vector3d center();

    /** How far p is past this body's gravity (0 or less inside it). */
    double outside(Vector3d p);

    /** A planet: gravity reaches this far from its center. */
    record Sphere(Vector3d center, double reach) implements GravityBody {
        @Override
        public double outside(Vector3d p) {
            return p.distance(center) - reach;
        }
    }
}
