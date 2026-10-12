package dev.moui.galaxycraft.toad;

import java.util.Random;

/**
 * What an idle Toad does with its feet: stand, turn, walk to a spot within a leash of home, go home
 * and sleep at night. Pure: the client's ToadWalker feeds it the ground (a {@link Terrain}) and moves
 * the record by the {@link Step} it returns. Coordinates are planar blocks around the home; y is the
 * ground height.
 */
public final class ToadWander {
    /** The ground the Toad walks on, in planar blocks. */
    public interface Terrain {
        /** Ground height at this point. */
        double groundAt(double x, double z);

        /** Whether a Toad standing at height y cannot be at this point (a wall, a solid block). */
        boolean blocked(double x, double z, double y);
    }

    /** One frame's answer: how far to move, which way to face, and Kinopio's animation to play. */
    public record Step(double dx, double dz, double heading, String anim) {}

    public static final double SPEED = 1.1, TURN_RATE = 4.0, MAX_CLIMB = 1.0, MAX_DROP = 1.0;
    private static final double ARRIVED = 0.3, HOME_ARRIVED = 0.4;
    private static final int GIVE_UP_HOMING = 6;

    private enum State { IDLE, TURN, WALK, SLEEP }

    private final double homeX, homeZ, leash;
    private final Random rng;
    private State state = State.IDLE;
    private double heading, wantHeading, targetX, targetZ, timer, walked;
    private int stuck;
    private boolean homing;

    public ToadWander(double homeX, double homeZ, double leash, long seed) {
        this.homeX = homeX;
        this.homeZ = homeZ;
        this.leash = leash;
        this.rng = new Random(seed);
        this.heading = rng.nextDouble() * Math.PI * 2;
        this.targetX = homeX;
        this.targetZ = homeZ;
        this.timer = idleTime();
    }

    public double heading() {
        return heading;
    }

    public boolean sleeping() {
        return state == State.SLEEP;
    }

    public Step step(double dt, double x, double z, double y, Terrain t, boolean night, boolean playerNear, boolean talking) {
        if (talking) return new Step(0, 0, heading, "Wait");
        boolean sleepy = night && !playerNear;
        if (state == State.SLEEP) {
            if (sleepy) return new Step(0, 0, heading, "Sleep");
            state = State.IDLE;
            timer = idleTime();
            homing = false;
        }
        if (sleepy && !homing) {
            if (Math.hypot(x - homeX, z - homeZ) <= HOME_ARRIVED) return fallAsleep();
            homing = true;
            goTo(homeX, homeZ, x, z);
        } else if (!sleepy && homing) {
            homing = false;
        }
        switch (state) {
            case TURN: {
                double diff = angleDiff(wantHeading, heading), turn = TURN_RATE * dt;
                if (Math.abs(diff) <= turn) {
                    heading = wantHeading;
                    state = State.WALK;
                    walked = 0;
                } else heading += Math.signum(diff) * turn;
                return new Step(0, 0, heading, "Turn");
            }
            case WALK:
                return walk(dt, x, z, y, t);
            default:
                timer -= dt;
                if (timer <= 0) {
                    stuck = 0;
                    pickTarget(x, z);
                }
                return new Step(0, 0, heading, "Wait");
        }
    }

    private Step walk(double dt, double x, double z, double y, Terrain t) {
        walked += dt;
        double dist = Math.hypot(targetX - x, targetZ - z);
        double goal = homing ? HOME_ARRIVED : ARRIVED;
        if (dist <= goal) return arrived();
        if (walked > 8 + dist / SPEED * 2) return blockedStep(x, z); // took far too long: another way
        double len = Math.min(SPEED * dt, dist);
        double nx = x + Math.cos(heading) * len, nz = z + Math.sin(heading) * len;
        // Face the target while walking (small drift correction), then test the step.
        if (Math.hypot(nx - homeX, nz - homeZ) > leash && !homing) return blockedStep(x, z);
        double rise = t.groundAt(nx, nz) - y;
        if (rise > MAX_CLIMB + 1e-9 || rise < -MAX_DROP - 1e-9 || t.blocked(nx, nz, y)) return blockedStep(x, z);
        return new Step(nx - x, nz - z, heading, "Walk");
    }

    private Step arrived() {
        stuck = 0;
        if (homing) return fallAsleep();
        state = State.IDLE;
        timer = idleTime();
        return new Step(0, 0, heading, "Wait");
    }

    private Step fallAsleep() {
        state = State.SLEEP;
        return new Step(0, 0, heading, "Sleep");
    }

    /** The way ahead is shut: another heading (+60, then -60 degrees), then a new target. */
    private Step blockedStep(double x, double z) {
        stuck++;
        if (homing && stuck >= GIVE_UP_HOMING) return fallAsleep();
        if (stuck == 1) turnTo(heading + Math.PI / 3);
        else if (stuck == 2) turnTo(heading - 2 * Math.PI / 3);
        else if (homing) {
            turnTo(Math.atan2(homeZ - z, homeX - x) + (rng.nextDouble() - 0.5) * Math.PI);
        } else {
            stuck = 0;
            pickTarget(x, z);
        }
        return new Step(0, 0, heading, "Turn");
    }

    private void pickTarget(double x, double z) {
        double r = leash * Math.sqrt(rng.nextDouble()), a = rng.nextDouble() * Math.PI * 2;
        double tx = homeX + Math.cos(a) * r, tz = homeZ + Math.sin(a) * r;
        if (Math.hypot(tx - x, tz - z) < 1.0) { // too close to be worth a walk: go the other side of home
            tx = homeX - (x - homeX) * 0.5 + Math.cos(a);
            tz = homeZ - (z - homeZ) * 0.5 + Math.sin(a);
        }
        goTo(tx, tz, x, z);
    }

    private void goTo(double tx, double tz, double x, double z) {
        targetX = tx;
        targetZ = tz;
        stuck = homing ? stuck : 0;
        turnTo(Math.atan2(tz - z, tx - x));
    }

    private void turnTo(double h) {
        wantHeading = h;
        state = State.TURN;
    }

    private double idleTime() {
        return 2 + rng.nextDouble() * 4;
    }

    private static double angleDiff(double to, double from) {
        double d = (to - from) % (Math.PI * 2);
        if (d > Math.PI) d -= Math.PI * 2;
        if (d < -Math.PI) d += Math.PI * 2;
        return d;
    }
}
