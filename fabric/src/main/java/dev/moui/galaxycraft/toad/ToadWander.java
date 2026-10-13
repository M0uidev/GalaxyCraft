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

    /**
     * A hut's door: the way out of it is a gate this far in front of home (+z), and the hut itself
     * (a box around home) is walked out of and into through it, not through its walls.
     */
    public static final double GATE_Z = 3, HUT_HALF_X = 2.7, HUT_BACK_Z = -3.7, HUT_FRONT_Z = 1.7;

    private final double homeX, homeZ, leash;
    private final boolean gated;
    private final java.util.ArrayDeque<double[]> route = new java.util.ArrayDeque<>();
    private final Random rng;
    private State state = State.IDLE;
    private double heading, wantHeading, targetX, targetZ, timer, walked;
    private int stuck;
    private boolean homing;

    public ToadWander(double homeX, double homeZ, double leash, long seed) {
        this(homeX, homeZ, leash, seed, false);
    }

    /** gated: home is just inside a hut's door (z toward the door), so it goes in and out by the door. */
    public ToadWander(double homeX, double homeZ, double leash, long seed, boolean gated) {
        this.gated = gated;
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

    /** The way it faces while it stands (it turned to look at the player): its next turn starts from here. */
    public void face(double h) {
        if (state == State.IDLE) heading = h;
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

    private double lastX, lastZ, detour;
    private static final double DETOUR = 1.2;

    private Step walk(double dt, double x, double z, double y, Terrain t) {
        walked += dt;
        lastX = x;
        lastZ = z;
        // Heads for the target, except while going round something (a detour keeps its heading a while).
        if (detour > 0) detour -= dt;
        else heading = Math.atan2(targetZ - z, targetX - x);
        double dist = Math.hypot(targetX - x, targetZ - z);
        double goal = homing && route.isEmpty() ? HOME_ARRIVED : ARRIVED;
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
        if (!route.isEmpty()) { // the next point of the way round (a hut's door)
            double[] next = route.poll();
            targetX = next[0];
            targetZ = next[1];
            wantHeading = Math.atan2(targetZ - lastZ, targetX - lastX);
            state = State.TURN;
            return new Step(0, 0, heading, "Turn");
        }
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
        detour = DETOUR;
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
        if (gated) {
            // In front of the hut only, the day's walks: never behind or beside its walls.
            for (int i = 0; i < 6; i++) {
                double r = 3 + (leash - 3) * Math.sqrt(rng.nextDouble()), a = (rng.nextDouble() - 0.5) * 2.4;
                double tx = homeX + Math.sin(a) * r, tz = homeZ + Math.cos(a) * r;
                if (tz >= GATE_Z - 0.5 && Math.hypot(tx - x, tz - z) >= 1.0) {
                    goTo(tx, tz, x, z);
                    return;
                }
            }
            goTo(homeX, homeZ + GATE_Z, x, z);
            return;
        }
        double r = leash * Math.sqrt(rng.nextDouble()), a = rng.nextDouble() * Math.PI * 2;
        double tx = homeX + Math.cos(a) * r, tz = homeZ + Math.sin(a) * r;
        if (Math.hypot(tx - x, tz - z) < 1.0) { // too close to be worth a walk: go the other side of home
            tx = homeX - (x - homeX) * 0.5 + Math.cos(a);
            tz = homeZ - (z - homeZ) * 0.5 + Math.sin(a);
        }
        goTo(tx, tz, x, z);
    }

    private void goTo(double tx, double tz, double x, double z) {
        route.clear();
        double fx = tx, fz = tz;
        if (gated) {
            boolean here = inHut(x, z), there = inHut(tx, tz);
            double gx = homeX, gz = homeZ + GATE_Z;
            if (here && !there) { // out by the door
                route.add(new double[] {homeX, homeZ});
                route.add(new double[] {gx, gz});
                route.add(new double[] {tx, tz});
            } else if (!here && there) { // in by the door, from the front or round the side
                if (z < HUT_FRONT_Z + homeZ) {
                    double side = (x >= homeX ? 1 : -1) * (HUT_HALF_X + 1.3);
                    route.add(new double[] {homeX + side, z});
                    route.add(new double[] {homeX + side, gz});
                }
                route.add(new double[] {gx, gz});
                route.add(new double[] {homeX, homeZ + 0.8});
                route.add(new double[] {tx, tz});
            }
            if (!route.isEmpty()) {
                double[] first = route.poll();
                fx = first[0];
                fz = first[1];
            }
        }
        targetX = fx;
        targetZ = fz;
        stuck = homing ? stuck : 0;
        turnTo(Math.atan2(fz - z, fx - x));
    }

    /** Whether a point is in the hut's box (planar blocks, home at the door's inside). */
    private boolean inHut(double x, double z) {
        return Math.abs(x - homeX) < HUT_HALF_X && z - homeZ > HUT_BACK_Z && z - homeZ < HUT_FRONT_Z;
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
