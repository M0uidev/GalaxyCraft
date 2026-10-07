package dev.moui.galaxycraft.gravity;

import org.joml.Quaterniond;
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

    /**
     * A station: gravity fills a box turned by rotation, min and max its corners in its own axes
     * from center. center() is the box's middle (the wind pulls toward it).
     */
    record Box(Vector3d origin, Quaterniond rotation, Vector3d min, Vector3d max) implements GravityBody {
        @Override
        public Vector3d center() {
            return rotation.transform(new Vector3d(min).add(max).mul(0.5)).add(origin);
        }

        @Override
        public double outside(Vector3d p) {
            Vector3d q = rotation.transformInverse(new Vector3d(p).sub(origin));
            double dx = Math.max(Math.max(min.x - q.x, q.x - max.x), 0), dy = Math.max(Math.max(min.y - q.y, q.y - max.y), 0),
                    dz = Math.max(Math.max(min.z - q.z, q.z - max.z), 0);
            double past = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (past > 0) return past;
            // Inside: how far in from the nearest face.
            return -Math.min(Math.min(Math.min(q.x - min.x, max.x - q.x), Math.min(q.y - min.y, max.y - q.y)),
                    Math.min(q.z - min.z, max.z - q.z));
        }
    }
}
