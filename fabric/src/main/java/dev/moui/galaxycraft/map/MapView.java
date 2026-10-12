package dev.moui.galaxycraft.map;

import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;

/**
 * The map's orbit camera: looks at a focus from a distance, turned by yaw and pitch. Projects
 * world points (units, relative to the player) to the screen and picks the nearest point to the
 * mouse. No Minecraft types.
 */
public final class MapView {
    public record Screen(double x, double y, double depth) {}

    private static final double MAX_PITCH = 1.5, FOV = Math.toRadians(60), MIN_DIST = 1e-3;

    public int width = 320, height = 240;
    private final Vector3d focus = new Vector3d();
    private double yaw = 0.6, pitch = 0.5, distance = 1;

    public Vector3d focus() {
        return new Vector3d(focus);
    }

    public double distance() {
        return distance;
    }

    public void focusOn(Vector3d f, double dist) {
        focus.set(f);
        distance = Math.max(MIN_DIST, dist);
    }

    /** Drag: radians. */
    public void orbit(double dYaw, double dPitch) {
        yaw += dYaw;
        pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + dPitch));
    }

    public void zoom(double factor) {
        distance = Math.max(MIN_DIST, Math.min(1e15, distance * factor));
    }

    public Vector3d eye() {
        double cp = Math.cos(pitch);
        return new Vector3d(focus).add(distance * cp * Math.sin(yaw), distance * Math.sin(pitch), distance * cp * Math.cos(yaw));
    }

    public Optional<Screen> project(Vector3d p) {
        Vector3d eye = eye();
        Vector3d f = new Vector3d(focus).sub(eye).normalize();
        Vector3d r = new Vector3d(f).cross(0, 1, 0).normalize();
        Vector3d u = new Vector3d(r).cross(f);
        Vector3d d = new Vector3d(p).sub(eye);
        double z = d.dot(f);
        if (z <= distance * 1e-6) return Optional.empty();
        double scale = (height / 2.0) / Math.tan(FOV / 2);
        return Optional.of(new Screen(width / 2.0 + d.dot(r) / z * scale, height / 2.0 - d.dot(u) / z * scale, z));
    }

    /** The index of the point nearest the mouse within radiusPx on screen (ties: the nearer to the camera), or -1. */
    public int pick(List<Vector3d> points, double mx, double my, double radiusPx) {
        int best = -1;
        double bestD = radiusPx * radiusPx, bestZ = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            Optional<Screen> s = project(points.get(i));
            if (s.isEmpty()) continue;
            double dx = s.get().x() - mx, dy = s.get().y() - my, d = dx * dx + dy * dy;
            if (d < bestD || (d == bestD && s.get().depth() < bestZ)) {
                best = i;
                bestD = d;
                bestZ = s.get().depth();
            }
        }
        return best;
    }
}
