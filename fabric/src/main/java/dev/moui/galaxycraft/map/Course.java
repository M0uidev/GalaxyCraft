package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;
import org.joml.Vector3d;

/** A straight trip: from, to (universe), the tick it left and its speed, units a tick (> 0). Position is a function of the tick. */
public record Course(UPos from, UPos to, long startTick, double speed) {
    public Course {
        if (!(speed > 0)) throw new IllegalArgumentException("speed must be above 0: " + speed);
    }

    public double length() {
        return to.minus(from).length();
    }

    public long durationTicks() {
        return (long) Math.ceil(length() / speed);
    }

    public boolean arrived(long t) {
        return t - startTick >= durationTicks();
    }

    public UPos position(long t) {
        long d = durationTicks();
        if (d == 0 || t - startTick >= d) return to;
        if (t <= startTick) return from;
        Vector3d step = to.minus(from).mul((double) (t - startTick) / d);
        return from.plus(step);
    }
}
