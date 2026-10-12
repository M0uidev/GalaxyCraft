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
     * degrees for each ship already bound for that planet so none shares a spot.
     */
    public static Trip send(CourseStore store, String shipId, UPos shipNow, UPos planetCenter, double planetRadiusBlocks,
            double unitsPerBlock, double unitsPerTick, long now) {
        UPos from = positionNow(store, shipId, shipNow, now);
        store.remove(shipId);
        double radius = orbitBlocks(planetRadiusBlocks) * unitsPerBlock;
        Vector3d side = from.minus(planetCenter);
        double base = side.x == 0 && side.z == 0 ? 0 : Math.atan2(side.z, side.x);
        double phase = base + store.orbitingAround(planetCenter) * Math.PI / 4;
        UPos to = planetCenter.plus(new Vector3d(radius * Math.cos(phase), 0, radius * Math.sin(phase)));
        Course course = new Course(from, to, now, unitsPerTick);
        Orbit orbit = new Orbit(planetCenter, radius, phase, RATE, now + course.durationTicks());
        Trip trip = new Trip(course, orbit);
        store.put(shipId, trip);
        return trip;
    }

    /** Stops the ship where it is: the trip is forgotten and that position returned (the ship side records it). */
    public static UPos cancel(CourseStore store, String shipId, UPos recorded, long now) {
        UPos at = positionNow(store, shipId, recorded, now);
        store.remove(shipId);
        return at;
    }
}
