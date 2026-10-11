package dev.moui.galaxycraft.ship;

import org.joml.Vector3d;

/** How a boarded ship moves the player: velocity in Minecraft space, blocks a tick. Pure. */
public final class ShipThrust {
    /** Share of the speed kept each tick without thrust. */
    private static final double DRAG = 0.99;
    private static final double ACCEL_PER_SPEED = 0.05, TOP_PER_SPEED = 0.9, TURN_PER_TURN = 0.04;

    private ShipThrust() {}

    /** Blocks a tick the ship tops out at. */
    public static double topSpeed(ShipStats s) {
        return TOP_PER_SPEED * s.speed();
    }

    /** The velocity next tick: thrust along look while forward is held, turned toward look by the rudder, dragged. */
    public static Vector3d step(Vector3d vel, Vector3d look, boolean forward, ShipStats s) {
        Vector3d dir = new Vector3d(look).normalize();
        Vector3d v = new Vector3d(vel);
        if (forward) v.fma(ACCEL_PER_SPEED * s.speed(), dir);
        else v.mul(DRAG);
        double speed = v.length();
        if (speed < 1e-9) return new Vector3d();
        // The heading eases toward where the player looks: a better rudder, quicker.
        Vector3d heading = v.div(speed).lerp(dir, Math.min(1, TURN_PER_TURN * s.turn())).normalize();
        return heading.mul(Math.min(speed, topSpeed(s)));
    }
}
