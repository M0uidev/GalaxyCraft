package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;
import org.joml.Vector3d;

/** Sending a ship to a planet and stopping it: pure, on a CourseStore. */
public final class RemoteControl {
    /** Orbit radius over the surface, blocks: 60% of the radius plus 24. First draft, tuned by playing. */
    static double orbitBlocks(double planetRadiusBlocks) {
        return planetRadiusBlocks * 1.6 + 24;
    }

    /** Radians a tick: a lap takes about 60 seconds (1200 ticks). */
    static final double RATE = 2 * Math.PI / 1200;

    private RemoteControl() {}

    /** Where the ship is at now: on its trip if it has one, else where it was recorded. */
    public static UPos positionNow(CourseStore store, String shipId, UPos recorded, long now) {
        return store.get(shipId).map(t -> t.positionAt(now)).orElse(recorded);
    }

    /**
     * A trip from where the ship is now to an orbit around the planet, kept in the store (a trip it
     * had is replaced). The orbit's phase starts on the side the ship comes from, turned by 45
     * degrees until no ship already bound for that planet is near that spot.
     */
    public static Trip send(CourseStore store, String shipId, UPos shipNow, UPos planetCenter, double planetRadiusBlocks,
            double unitsPerBlock, double unitsPerTick, long now) {
        UPos from = positionNow(store, shipId, shipNow, now);
        store.remove(shipId);
        double radius = orbitBlocks(planetRadiusBlocks) * unitsPerBlock;
        Vector3d side = from.minus(planetCenter);
        double base = side.x == 0 && side.z == 0 ? 0 : Math.atan2(side.z, side.x);
        // The first of 8 spots, starting on the side the ship comes from, that no ship bound for the planet is near.
        Trip trip = null;
        for (int k = 0; k < 8 && trip == null; k++) {
            Trip candidate = around(from, planetCenter, radius, base + k * Math.PI / 4, unitsPerTick, now);
            if (free(store, candidate)) trip = candidate;
        }
        if (trip == null) trip = around(from, planetCenter, radius, base, unitsPerTick, now);
        store.put(shipId, trip);
        return trip;
    }

    private static Trip around(UPos from, UPos center, double radius, double phase, double unitsPerTick, long now) {
        UPos to = center.plus(new Vector3d(radius * Math.cos(phase), 0, radius * Math.sin(phase)));
        Course course = new Course(from, to, now, unitsPerTick);
        return new Trip(course, new Orbit(center, radius, phase, RATE, now + course.durationTicks()));
    }

    /** No ship bound for this planet is within 22.5 degrees of the candidate's spot when it arrives (all turn at RATE). */
    private static boolean free(CourseStore store, Trip candidate) {
        long at = candidate.orbit().epochTick();
        for (Trip o : store.all().values()) {
            if (!o.orbit().center().equals(candidate.orbit().center())) continue;
            double theirs = o.orbit().phase() + o.orbit().rate() * (at - o.orbit().epochTick());
            double d = Math.abs(Math.IEEEremainder(candidate.orbit().phase() - theirs, 2 * Math.PI));
            if (d < Math.PI / 8) return false;
        }
        return true;
    }

    /** Stops the ship where it is: the trip is forgotten and that position returned (the ship side records it). */
    public static UPos cancel(CourseStore store, String shipId, UPos recorded, long now) {
        UPos at = positionNow(store, shipId, recorded, now);
        store.remove(shipId);
        return at;
    }
}
