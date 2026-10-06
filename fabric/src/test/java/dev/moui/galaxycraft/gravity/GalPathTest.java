package dev.moui.galaxycraft.gravity;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class GalPathTest {
    @Test void betweenTicksItIsPartOfTheWayFromTheLastTicksPlace() {
        GalPath p = new GalPath();
        p.tick(new Vector3d(0, 0, 0));
        p.tick(new Vector3d(80, 0, 0));
        assertEquals(new Vector3d(40, 0, 0), p.at(0.5));
        assertEquals(new Vector3d(80, 0, 0), p.at(1));
    }

    @Test void aTeleportIsNotDrawnAsAFlight() {
        GalPath p = new GalPath();
        p.tick(new Vector3d(0, 0, 0));
        p.tick(new Vector3d(80 * 50, 0, 0)); // 50 blocks in a tick
        assertEquals(new Vector3d(80 * 50, 0, 0), p.at(0.2));
    }

    @Test void nothingYetIsNothing() {
        assertNull(new GalPath().at(0.5));
        GalPath p = new GalPath();
        p.tick(new Vector3d(1, 2, 3));
        assertEquals(new Vector3d(1, 2, 3), p.at(0.5));
    }
}
