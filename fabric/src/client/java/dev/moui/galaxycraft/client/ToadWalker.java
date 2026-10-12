package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadWander;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.HashMap;
import java.util.Map;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Feeds {@link ToadWander} the planet's ground and moves the Toad by what it answers. Each Toad
 * keeps a flat frame at its home (right, up, forward from its saved rotation); a point of the
 * walk is (x, z) in blocks along it and a height h above that plane, so the ground there is the
 * first block a ray down that column meets. The position written back is that column point.
 */
final class ToadWalker {
    /** Blocks of ground a Toad stays within of its home. */
    static final double LEASH = 10;
    private static final double RISE = 5, FALL_G = 20, EYE = 1.6, REACH = 7.6, NEAR_PLAYER = 3, BODY = 1.6;

    /** What a frame of walking says about a Toad. */
    record Frame(String anim, double yaw, boolean moved) {}

    private static final class Walk {
        ToadWander wander;
        Vector3d home, right, up, fwd;
        double x, z, h, vy;
        Vector3d upNow;
    }

    private static final Map<String, Walk> WALKS = new HashMap<>();

    private ToadWalker() {}

    static void forget(String id) {
        WALKS.remove(id);
    }

    /** Where this Toad keeps to (universe units), or null if it has not walked. */
    static double[] homeOf(String id) {
        Walk w = WALKS.get(id);
        return w == null ? null : new double[] {w.home.x, w.home.y, w.home.z};
    }

    /** The Toad turned (to look at the player): its next turn starts from that heading. */
    static void face(String id, double yaw) {
        Walk w = WALKS.get(id);
        if (w != null) w.wander.face(Math.PI / 2 - yaw);
    }

    /** The Toad's up where it stands now (the planet's, from the last ground it found), or null if it has not walked. */
    static Vector3d upOf(String id) {
        Walk w = WALKS.get(id);
        return w == null ? null : w.upNow;
    }

    /** The orientation to draw it in: its placed rotation tilted to the ground it is on. */
    static Quaterniond orientation(ToadRecord r) {
        Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
        Walk w = WALKS.get(r.id());
        if (w == null || w.upNow == null) return q;
        return new Quaterniond().rotationTo(w.up, w.upNow).mul(q);
    }

    /**
     * Advances one Toad by dt seconds on this planet and moves record.pos() in place; null if it
     * cannot walk (no ground found at all yet).
     */
    static Frame step(ToadRecord r, double dt, PlanetSession s, boolean night, boolean near, boolean talking, double faceYaw) {
        double upb = 1 / GravityFrame.SCALE; // universe units per block
        Walk w = WALKS.get(r.id());
        if (w == null) {
            w = new Walk();
            Quaterniond q = new Quaterniond(r.rot()[0], r.rot()[1], r.rot()[2], r.rot()[3]);
            double[] hm = r.home() != null ? r.home() : r.pos();
            w.home = new Vector3d(hm[0], hm[1], hm[2]);
            w.right = new Vector3d(1, 0, 0).rotate(q);
            w.up = new Vector3d(0, 1, 0).rotate(q);
            w.fwd = new Vector3d(0, 0, 1).rotate(q);
            Vector3d off = new Vector3d(r.pos()[0], r.pos()[1], r.pos()[2]).sub(w.home);
            w.x = off.dot(w.right) / upb;
            w.z = off.dot(w.fwd) / upb;
            w.h = off.dot(w.up) / upb;
            w.wander = new ToadWander(0, 0, LEASH, r.id().hashCode());
            w.wander.face(Math.PI / 2 - faceYaw);
            WALKS.put(r.id(), w);
        }
        final Walk k = w;
        ToadWander.Terrain t = new ToadWander.Terrain() {
            public double groundAt(double x, double z) {
                return ground(s, k, x, z, k.h + EYE, upb);
            }

            public boolean blocked(double x, double z, double y) {
                double g = ground(s, k, x, z, k.h + EYE, upb);
                if (g < -1e2) return true;
                for (double dy = 0.5; dy < BODY + 0.5; dy += 1) {
                    Vector3d p = point(k, x, z, g + dy, upb);
                    int cell = s.cellAt(p);
                    if (cell >= 0 && s.planet().info(cell).collides()) return true;
                }
                return false;
            }
        };
        double floor = t.groundAt(w.x, w.z);
        if (floor < -1e2) return null; // nothing under it (the planet is not meshed there yet): stays put
        ToadWander.Step st = w.wander.step(dt, w.x, w.z, w.h, t, night, near, talking);
        boolean moved = st.dx() != 0 || st.dz() != 0;
        w.x += st.dx();
        w.z += st.dz();
        double ground = t.groundAt(w.x, w.z);
        if (ground > -1e2) {
            if (ground > w.h) {
                w.h = Math.min(ground, w.h + RISE * dt);
                w.vy = 0;
            } else if (ground < w.h - 1e-6) {
                w.vy += FALL_G * dt;
                w.h = Math.max(ground, w.h - w.vy * dt);
            } else w.vy = 0;
        }
        Vector3d p = point(w, w.x, w.z, w.h, upb);
        r.pos()[0] = p.x;
        r.pos()[1] = p.y;
        r.pos()[2] = p.z;
        Vector3d[] hit = s.surfaceAlong(point(w, w.x, w.z, w.h + EYE, upb), new Vector3d(w.up).negate(), REACH + 1);
        if (hit != null) w.upNow = hit[1];
        return new Frame(st.anim(), Math.PI / 2 - st.heading(), moved);
    }

    /** Where (x, z) at height h is, galaxy units. */
    private static Vector3d point(Walk w, double x, double z, double h, double upb) {
        return new Vector3d(w.home).fma(x * upb, w.right).fma(z * upb, w.fwd).fma(h * upb, w.up);
    }

    /** The height of the ground in the column of (x, z), looking down from `from`; -1e3 if there is none. */
    private static double ground(PlanetSession s, Walk w, double x, double z, double from, double upb) {
        Vector3d[] hit = s.surfaceAlong(point(w, x, z, from, upb), new Vector3d(w.up).negate(), REACH);
        if (hit == null) return -1e3;
        return new Vector3d(hit[0]).sub(w.home).dot(w.up) / upb;
    }

    /** Whether the player stands near enough to wake a sleeping Toad (blocks). */
    static boolean near(double blocksToPlayer) {
        return blocksToPlayer < NEAR_PLAYER;
    }
}
