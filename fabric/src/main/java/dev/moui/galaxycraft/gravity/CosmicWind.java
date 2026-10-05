package dev.moui.galaxycraft.gravity;

import java.util.List;
import org.joml.Vector3d;

/**
 * Space has no floor to fall to and no wall: far past every body's gravity, a soft wind slows a
 * player going away and pulls them back toward the nearest body, so nobody dies of it or gets
 * lost. Blocks and blocks per tick.
 */
public final class CosmicWind {
    /** Free space past the nearest body's gravity, blocks: flights between bodies feel nothing. */
    public static final double FREE = 200;
    /** Over this many blocks past FREE the pull grows to PULL (blocks/tick each tick). */
    public static final double RAMP = 100, PULL = 0.08;
    /** Share of the speed away from the body taken off each tick (at full wind). */
    public static final double DRAG = 0.05;

    private CosmicWind() {}

    /** The change of velocity this tick for a player at pos moving at vel; zero where it is calm. */
    public static Vector3d push(Vector3d pos, Vector3d vel, List<? extends GravityBody> bodies) {
        GravityBody nearest = null;
        double past = Double.MAX_VALUE;
        for (GravityBody b : bodies) {
            double o = b.outside(pos);
            if (o < past) {
                past = o;
                nearest = b;
            }
        }
        Vector3d dv = new Vector3d();
        if (nearest == null || past <= FREE) return dv;
        Vector3d in = new Vector3d(nearest.center()).sub(pos);
        if (in.lengthSquared() < 1e-12) return dv;
        in.normalize();
        double t = Math.min(1, (past - FREE) / RAMP), s = t * t * (3 - 2 * t);
        dv.fma(PULL * s, in);
        double away = -vel.dot(in);
        if (away > 0) dv.fma(away * DRAG * s, in);
        return dv;
    }
}
