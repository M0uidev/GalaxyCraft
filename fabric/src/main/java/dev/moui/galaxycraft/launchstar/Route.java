package dev.moui.galaxycraft.launchstar;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/**
 * A Launch Star's course: a smooth curve from one star to the other that leaves along the first
 * star's up, arrives from above onto the second, and bends around the planets in the way. Points
 * in any one unit (the caller's); no Minecraft types, so it is unit tested. The curve is a cubic
 * Hermite spline through waypoints; a waypoint is added on the surface of every obstacle the
 * curve goes into, until it clears them all (or gives up after MAX_ROUNDS).
 */
public final class Route {
    /** A planet (or any body) the course keeps out of. */
    public record Sphere(Vector3d center, double radius) {}

    private static final int MAX_ROUNDS = 24, PER_SEGMENT = 256, TABLE_STEP = 2;
    /** How far outside an obstacle a detour waypoint is put, as a share of its radius. */
    private static final double DETOUR = 1.18;

    private final List<Vector3d> pts;
    private final List<Vector3d> tans;
    /** Cumulative arc length at every sample, and the sample's (segment, t). */
    private final double[] arc;
    private final int[] seg;
    private final double[] tt;

    private Route(List<Vector3d> pts, List<Vector3d> tans) {
        this.pts = pts;
        this.tans = tans;
        int n = (pts.size() - 1) * PER_SEGMENT + 1;
        arc = new double[n];
        seg = new int[n];
        tt = new double[n];
        Vector3d prev = null;
        for (int i = 0; i < n; i++) {
            int s = Math.min(i / PER_SEGMENT, pts.size() - 2);
            double t = (i - s * PER_SEGMENT) / (double) PER_SEGMENT;
            seg[i] = s;
            tt[i] = t;
            Vector3d p = eval(s, t);
            arc[i] = i == 0 ? 0 : arc[i - 1] + p.distance(prev);
            prev = p;
        }
    }

    public double length() {
        return arc[arc.length - 1];
    }

    /** The point s along the course (clamped to 0..length). */
    public Vector3d at(double s) {
        int i = index(s);
        double span = arc[i + 1] - arc[i];
        double f = span <= 0 ? 0 : (Math.max(0, Math.min(length(), s)) - arc[i]) / span;
        return eval(seg[i], tt[i] + f / PER_SEGMENT);
    }

    /** The direction of travel at s (unit). */
    public Vector3d tangent(double s) {
        double h = Math.max(length() * 1e-4, 1e-9);
        double a = Math.max(0, s - h), b = Math.min(length(), s + h);
        Vector3d d = at(b).sub(at(a));
        return d.lengthSquared() > 0 ? d.normalize() : new Vector3d(0, 1, 0);
    }

    private int index(double s) {
        double x = Math.max(0, Math.min(length(), s));
        int lo = 0, hi = arc.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (arc[mid] <= x) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private Vector3d eval(int s, double t) {
        Vector3d p0 = pts.get(s), p1 = pts.get(s + 1), m0 = tans.get(s), m1 = tans.get(s + 1);
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        return new Vector3d(p0).mul(h00).fma(h10, m0).fma(h01, p1).fma(h11, m1);
    }

    /** The course from a (up upA) to b (up upB) around the obstacles. */
    public static Route plan(Vector3d a, Vector3d upA, Vector3d b, Vector3d upB, List<Sphere> obstacles) {
        Vector3d ua = unit(upA), ub = unit(upB);
        double chord = Math.max(a.distance(b), 1e-9);
        double handle = chord * 0.35;
        List<Vector3d> via = new ArrayList<>();
        Route best = build(a, ua, b, ub, via, handle);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int[] hit = firstPenetration(best, a, b, obstacles);
            if (hit == null) return best;
            Sphere o = obstacles.get(hit[0]);
            Vector3d p = best.at(best.arc[hit[1]]);
            Vector3d out = new Vector3d(p).sub(o.center());
            if (out.lengthSquared() < 1e-9 * Math.max(1, o.radius() * o.radius())) {
                // right through the middle: go around on the side of "up" crossed with the chord
                out = new Vector3d(ua).cross(new Vector3d(b).sub(a));
                if (out.lengthSquared() < 1e-18) out = new Vector3d(1, 0, 0).cross(new Vector3d(b).sub(a));
                if (out.lengthSquared() < 1e-18) out = new Vector3d(0, 0, 1);
            }
            Vector3d w = new Vector3d(o.center()).fma(o.radius() * DETOUR, unit(out));
            int at = 0; // waypoints stay in the order along the course
            double s = best.arc[hit[1]];
            for (int k = 0; k < via.size(); k++) if (arcOf(best, via.get(k)) < s) at = k + 1;
            via.add(at, w);
            best = build(a, ua, b, ub, via, handle);
        }
        return best;
    }

    private static double arcOf(Route r, Vector3d p) {
        double best = Double.MAX_VALUE, s = 0;
        for (int i = 0; i < r.arc.length; i += 4) {
            double d = r.eval(r.seg[i], r.tt[i]).distanceSquared(p);
            if (d < best) {
                best = d;
                s = r.arc[i];
            }
        }
        return s;
    }

    /** {obstacle, sample} of the first sample that is inside an obstacle, or null. */
    private static int[] firstPenetration(Route r, Vector3d a, Vector3d b, List<Sphere> obstacles) {
        for (int i = 0; i < r.arc.length; i += TABLE_STEP) {
            Vector3d p = r.eval(r.seg[i], r.tt[i]);
            for (int k = 0; k < obstacles.size(); k++) {
                Sphere o = obstacles.get(k);
                // the planet a star stands on: only its own surface is kept out of, not its gravity
                double rad = o.radius();
                double da = a.distance(o.center()), db = b.distance(o.center());
                if (da < rad) rad = Math.min(rad, da * 0.999);
                if (db < rad) rad = Math.min(rad, db * 0.999);
                if (p.distance(o.center()) < rad) return new int[] {k, i};
            }
        }
        return null;
    }

    private static Route build(Vector3d a, Vector3d ua, Vector3d b, Vector3d ub, List<Vector3d> via, double handle) {
        List<Vector3d> pts = new ArrayList<>();
        pts.add(a);
        pts.addAll(via);
        pts.add(b);
        List<Vector3d> tans = new ArrayList<>();
        tans.add(new Vector3d(ua).mul(handle));
        for (int i = 1; i < pts.size() - 1; i++) {
            Vector3d d = new Vector3d(pts.get(i + 1)).sub(pts.get(i - 1));
            double in = pts.get(i).distance(pts.get(i - 1)), out = pts.get(i).distance(pts.get(i + 1));
            tans.add(unit(d).mul(Math.min(in, out) * 0.9));
        }
        tans.add(new Vector3d(ub).negate().mul(handle));
        return new Route(pts, tans);
    }

    private static Vector3d unit(Vector3d v) {
        double l = v.length();
        return l > 0 ? new Vector3d(v).div(l) : new Vector3d(0, 1, 0);
    }
}
