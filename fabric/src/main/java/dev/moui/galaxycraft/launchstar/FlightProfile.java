package dev.moui.galaxycraft.launchstar;

/**
 * How fast a Launch Star flight goes along its course: eases in over the first ticks, cruises,
 * eases out over the last, and takes a time that grows with the length but stays between a few
 * seconds and a minute. A pure function of the tick, so a pause or a slow frame cannot desync it.
 */
public final class FlightProfile {
    /** Cruise speed the time is picked for (blocks a tick: 160 blocks a second), and the time's bounds. */
    public static final double CRUISE = 8;
    public static final int MIN_TICKS = 120, MAX_TICKS = 1200, EASE_TICKS = 20;

    private final double length;
    private final int ticks;
    private final double ease, v;

    private FlightProfile(double length) {
        this.length = length;
        this.ticks = (int) Math.max(MIN_TICKS, Math.min(MAX_TICKS, Math.ceil(length / CRUISE)));
        this.ease = Math.min(EASE_TICKS, ticks / 3.0);
        this.v = length / (ticks - ease);
    }

    /** The profile for a course of that many blocks. */
    public static FlightProfile of(double lengthBlocks) {
        return new FlightProfile(Math.max(0, lengthBlocks));
    }

    public int ticks() {
        return ticks;
    }

    /** Blocks travelled after that many ticks (fractions allowed): 0 at the start, the length from the end on. */
    public double arcAt(double tick) {
        if (tick <= 0) return 0;
        if (tick >= ticks) return length;
        double a = v / (2 * ease); // acceleration over the ease, deceleration the same
        if (tick < ease) return a * tick * tick;
        double rest = ticks - tick;
        if (rest < ease) return length - a * rest * rest;
        return v * ease / 2 + v * (tick - ease);
    }
}
