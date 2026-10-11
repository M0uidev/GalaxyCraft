package dev.moui.galaxycraft.ship;

import org.joml.Vector3d;

/** How a boarded ship moves the player: velocity in Minecraft space, blocks a tick. Pure. */
public final class ShipThrust {
    /** Fractions of the speed stat: the top speed along the look and the climb. */
    private static final double TOP_PER_SPEED = 0.6, CLIMB = 0.6, TURBO = 1.9;
    /** How quickly the velocity goes to the one asked for, per tick: base plus per rudder point. */
    private static final double EASE_BASE = 0.05, EASE_PER_TURN = 0.05;

    private ShipThrust() {}

    /** The keys that fly a ship. */
    public record Controls(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down, boolean turbo) {
        public static final Controls NONE = new Controls(false, false, false, false, false, false, false);
    }

    /** Blocks a tick the ship tops out at. */
    public static double topSpeed(ShipStats s) {
        return TOP_PER_SPEED * s.speed();
    }

    /** Blocks a tick it tops out at with the turbo on. */
    public static double turboSpeed(ShipStats s) {
        return TURBO * topSpeed(s);
    }

    /**
     * The velocity next tick, as a hovering craft: W and S along the look (pitch included), A and D
     * to the sides, Space up and Ctrl down, the velocity easing to what the keys ask (a better
     * rudder, quicker) and to a halt with none. No gravity, no drift to fight.
     */
    public static Vector3d step(Vector3d vel, Vector3d look, Controls c, ShipStats s) {
        Vector3d fwd = new Vector3d(look);
        if (fwd.lengthSquared() < 1e-12) fwd.set(0, 0, 1);
        fwd.normalize();
        Vector3d right = new Vector3d(fwd).cross(0, 1, 0);
        if (right.lengthSquared() < 1e-9) right.set(-1, 0, 0); // looking straight up or down
        right.normalize();
        Vector3d want = new Vector3d();
        want.fma((c.forward() ? 1 : 0) - (c.back() ? 1 : 0), fwd);
        want.fma((c.right() ? 1 : 0) - (c.left() ? 1 : 0), right);
        want.y += ((c.up() ? 1 : 0) - (c.down() ? 1 : 0)) * CLIMB;
        if (want.lengthSquared() > 1) want.normalize();
        want.mul(c.turbo() ? turboSpeed(s) : topSpeed(s));
        double k = Math.min(1, (EASE_BASE + EASE_PER_TURN * s.turn()) * (c.turbo() ? 1.4 : 1));
        return new Vector3d(vel).lerp(want, k);
    }
}
