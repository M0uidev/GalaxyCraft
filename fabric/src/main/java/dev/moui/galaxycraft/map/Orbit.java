package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;
import org.joml.Vector3d;

/**
 * A circle around a planet (in the universe's x-z plane): radius units, phase radians at
 * epochTick, rate radians a tick.
 */
public record Orbit(UPos center, double radius, double phase, double rate, long epochTick) {
    public UPos position(long t) {
        double a = phase + rate * (t - epochTick);
        return center.plus(new Vector3d(radius * Math.cos(a), 0, radius * Math.sin(a)));
    }
}
