package dev.moui.galaxycraft.ship;

import java.util.List;
import org.joml.Vector3d;

/** Which ship a click is on: the look ray against a sphere around each ship. Pure. */
public final class ShipBoarding {
    private ShipBoarding() {}

    /** Distance along the ray to the sphere's nearest point on it, or -1 if the ray misses or it is out of reach. */
    static double along(Vector3d eye, Vector3d dir, double[] center, double reach, double radius) {
        double cx = center[0] - eye.x, cy = center[1] - eye.y, cz = center[2] - eye.z;
        double t = cx * dir.x + cy * dir.y + cz * dir.z;
        if (t < 0 || t > reach + radius) return -1;
        double px = cx - dir.x * t, py = cy - dir.y * t, pz = cz - dir.z * t;
        return px * px + py * py + pz * pz <= radius * radius ? t : -1;
    }

    public static boolean hit(Vector3d eye, Vector3d dir, double[] center, double reach, double radius) {
        return along(eye, dir, center, reach, radius) >= 0;
    }

    /** Index of the nearest ship on the ray, -1 if none. */
    public static int nearest(Vector3d eye, Vector3d dir, List<double[]> centers, double reach, double radius) {
        int best = -1;
        double bt = Double.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            double t = along(eye, dir, centers.get(i), reach, radius);
            if (t >= 0 && t < bt) {
                bt = t;
                best = i;
            }
        }
        return best;
    }
}
