package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;
import org.joml.Vector3d;

/** Sending a ship to a planet and stopping it: pure, on a CourseStore. */
public final class RemoteControl {
    /** Orbit radius from the planet's center, blocks: just outside its gravity (the pulse's margin), as the pulse stops. First draft. */
    static double orbitBlocks(double planetRadiusBlocks) {
        return dev.moui.galaxycraft.voxel.PlanetSession.gravityRadius(planetRadiusBlocks) + 64;
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
     * had is replaced). The orbit's spot is searched from the side the ship comes from all
     * round the planet: the one farthest from the ships already bound for it.
     */
    public static Trip send(CourseStore store, String shipId, UPos shipNow, UPos planetCenter, double planetRadiusBlocks,
            double unitsPerBlock, double unitsPerTick, long now) {
        UPos from = positionNow(store, shipId, shipNow, now);
        store.remove(shipId);
        double radius = orbitBlocks(planetRadiusBlocks) * unitsPerBlock;
        Vector3d side = from.minus(planetCenter);
        double base = side.x == 0 && side.z == 0 ? 0 : Math.atan2(side.z, side.x);
        // Of 24 spots around the planet (the first on the side the ship comes from), the one farthest from every ship already
        // bound for it, as they will be when this one arrives.
        Trip trip = null;
        double best = -1;
        for (int k = 0; k < 24; k++) {
            Trip candidate = around(from, planetCenter, radius, base + k * Math.PI / 12, unitsPerTick, now);
            double apart = separation(store, candidate);
            if (apart > best + 1e-9) {
                best = apart;
                trip = candidate;
            }
        }
        store.put(shipId, trip);
        return trip;
    }

    private static Trip around(UPos from, UPos center, double radius, double phase, double unitsPerTick, long now) {
        UPos to = center.plus(new Vector3d(radius * Math.cos(phase), 0, radius * Math.sin(phase)));
        Course course = new Course(from, to, now, unitsPerTick);
        return new Trip(course, new Orbit(center, radius, phase, RATE, now + course.durationTicks()));
    }

    /** The smallest angle (radians) between the candidate's spot and the ships bound for its planet, at the candidate's arrival (all turn at RATE); pi if none. */
    private static double separation(CourseStore store, Trip candidate) {
        long at = candidate.orbit().epochTick();
        double min = Math.PI;
        for (Trip o : store.all().values()) {
            if (!o.orbit().center().equals(candidate.orbit().center())) continue;
            double theirs = o.orbit().phase() + o.orbit().rate() * (at - o.orbit().epochTick());
            min = Math.min(min, Math.abs(Math.IEEEremainder(candidate.orbit().phase() - theirs, 2 * Math.PI)));
        }
        return min;
    }

    /** Stops the ship where it is: the trip is forgotten and that position returned (the ship side records it). */
    public static UPos cancel(CourseStore store, String shipId, UPos recorded, long now) {
        UPos at = positionNow(store, shipId, recorded, now);
        store.remove(shipId);
        return at;
    }
}
