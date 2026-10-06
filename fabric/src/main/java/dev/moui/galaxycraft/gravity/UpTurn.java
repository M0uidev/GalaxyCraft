package dev.moui.galaxycraft.gravity;

import org.joml.Vector3d;

/**
 * A flight's turn of up toward a new pull (gliding into a planet's gravity, back from space):
 * it speeds up gently, never past MAX_PER_TICK, and brakes into its end, instead of starting and
 * stopping at full rate. One per flight; it remembers how fast it turns.
 */
public final class UpTurn {
    /** The fastest it turns, radians a tick: a half turn in about 2.5 s. */
    public static final double MAX_PER_TICK = Math.toRadians(4);
    /** How much faster (or slower) it turns each tick, radians a tick. */
    public static final double ACCEL_PER_TICK = Math.toRadians(0.3);
    private double rate;

    /** up turned one tick toward to (both unit). */
    public Vector3d step(Vector3d up, Vector3d to) {
        double angle = up.angle(to);
        if (angle < 1e-9) {
            rate = 0;
            return new Vector3d(to);
        }
        // Speeding up by ACCEL, capped by MAX, and slow enough to stop at the end braking by ACCEL.
        rate = Math.min(Math.min(rate + ACCEL_PER_TICK, MAX_PER_TICK), Math.sqrt(2 * ACCEL_PER_TICK * angle));
        if (angle <= rate) {
            rate = 0;
            return new Vector3d(to);
        }
        return GravityFrame.limitTurn(up, to, rate);
    }

    /** No turn going on (on the ground, in the void): the next one starts gently again. */
    public void reset() {
        rate = 0;
    }
}
