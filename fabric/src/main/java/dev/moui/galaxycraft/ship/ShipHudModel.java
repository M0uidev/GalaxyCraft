package dev.moui.galaxycraft.ship;

import org.joml.Vector3d;

/**
 * What the flight panel shows, from the ship's velocity and modules. Speeds are blocks a second (the velocity is
 * blocks a tick, 20 ticks a second). Pure: the client only draws it.
 */
public record ShipHudModel(double speed, double top, double turbo, double fraction, boolean boosting,
        ShipModules modules, double turn, int durability, int seats, int chests, int bays) {
    private static final double TICKS = 20;

    /** velocity: blocks a tick; turboKey: the turbo key is down; chests: bays holding a chest; bays: bays the cabin opens. */
    public static ShipHudModel of(Vector3d velocity, boolean turboKey, ShipModules modules, int chests, int bays) {
        ShipStats s = ShipStats.of(modules);
        double speed = velocity.length() * TICKS, top = ShipThrust.topSpeed(s) * TICKS, turbo = ShipThrust.turboSpeed(s) * TICKS;
        double fraction = turbo <= 0 ? 0 : Math.max(0, Math.min(1, speed / turbo));
        return new ShipHudModel(speed, top, turbo, fraction, turboKey && speed > top * 1.02, modules.repaired(),
                s.turn(), s.durability(), s.seats(), chests, bays);
    }

    /** Where the top-speed mark sits on the gauge (0..1): above it is the turbo's range. */
    public double topMark() {
        return turbo <= 0 ? 1 : top / turbo;
    }
}
