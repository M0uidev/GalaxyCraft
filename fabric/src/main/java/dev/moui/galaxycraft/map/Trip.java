package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;

/** A ship's whole trip: the course there, then the orbit it ends in. Nothing is simulated: ask for any tick. */
public record Trip(Course course, Orbit orbit) {
    public boolean arrived(long t) {
        return course.arrived(t);
    }

    public UPos positionAt(long t) {
        return arrived(t) ? orbit.position(t) : course.position(t);
    }
}
