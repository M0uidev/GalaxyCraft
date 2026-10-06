package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PlanetLayoutTest {
    static final Vector3d FEET = new Vector3d(100, 0, 50), UP = new Vector3d(0, 1, 0);

    @Test void aLonePlanetGoesAboveThePlayerAsBefore() {
        double g = PlanetSession.gravityRadius(32) * 80;
        Vector3d c = PlanetLayout.place(List.of(), g, FEET, UP, 80);
        assertEquals(new Vector3d(100, g + 24 * 80, 50), c);
    }

    @Test void moreGoAroundItWithTheirGravityApart() {
        double g = PlanetSession.gravityRadius(32) * 80;
        List<PlanetLayout.Sphere> placed = new ArrayList<>();
        for (int i = 0; i < PlanetLayout.MAX_PLANETS; i++) {
            Vector3d c = PlanetLayout.place(placed, g, FEET, UP, 80);
            assertNotNull(c, "planet " + i);
            for (PlanetLayout.Sphere o : placed)
                assertTrue(o.center().distance(c) >= 2 * g + PlanetLayout.GAP * 80 - 1e-6, "apart from the others");
            // In the plane above the player, none below his feet.
            assertEquals(g + 24 * 80, c.y, 1e-6);
            placed.add(new PlanetLayout.Sphere(c, g));
        }
    }

    @Test void theNearestPlanetAlwaysHasItsChunksOthersOnlyNearTheirGravity() {
        double g = 80 * 80;
        assertTrue(PlanetLayout.detail(false, true, 1e9, g, 80));
        assertFalse(PlanetLayout.detail(false, false, g + 100 * 80, g, 80));
        assertTrue(PlanetLayout.detail(false, false, g + 50 * 80, g, 80));
        // Slack: once in, out only past DETAIL_OUT.
        assertTrue(PlanetLayout.detail(true, false, g + 100 * 80, g, 80));
        assertFalse(PlanetLayout.detail(true, false, g + 130 * 80, g, 80));
    }

    // ---- placeAlong: where the player looks ----

    static final double U = 80, G = PlanetSession.gravityRadius(32) * U;
    static final Vector3d EYE = new Vector3d(0, 0, 0);

    @Test void inEmptySpaceJustOutOfTheNewGravityAlongTheLook() {
        Vector3d c = PlanetLayout.placeAlong(List.of(), G, EYE, new Vector3d(1, 0, 0), U);
        assertNotNull(c);
        assertEquals(0, c.y, 1e-9);
        assertEquals(0, c.z, 1e-9);
        assertTrue(c.x >= G + PlanetLayout.GAP * U - 1e-6, "the player is not in it: " + c.x);
        assertTrue(c.x <= G + PlanetLayout.GAP * U + PlanetLayout.ALONG_STEP * U + 1e-6, "but just out: " + c.x);
    }

    @Test void pastAnotherPlanetInTheWay() {
        Vector3d other = new Vector3d(3 * G, 0, 0);
        Vector3d c = PlanetLayout.placeAlong(List.of(new PlanetLayout.Sphere(other, G)), G, EYE, new Vector3d(1, 0, 0), U);
        assertNotNull(c);
        assertTrue(c.distance(other) >= 2 * G + PlanetLayout.GAP * U - 1e-6, "its gravity apart from the other's");
        assertTrue(c.x > other.x, "beyond it, along the look");
    }

    @Test void lookingDownThroughThePlanetUnderfootItGoesBelowIt() {
        // Standing on a planet (its center 40 blocks under the eye), looking straight down.
        Vector3d home = new Vector3d(0, -40 * U, 0);
        double homeG = PlanetSession.gravityRadius(40) * U;
        Vector3d c = PlanetLayout.placeAlong(List.of(new PlanetLayout.Sphere(home, homeG)), G, EYE, new Vector3d(0, -1, 0), U);
        assertNotNull(c);
        assertTrue(c.y < home.y - homeG, "under the planet: " + c.y / U);
        assertTrue(c.distance(home) >= homeG + G + PlanetLayout.GAP * U - 1e-6);
    }

    @Test void nothingIfNoRoomWithinReach() {
        // A wall of gravity filling the whole reach.
        Vector3d huge = new Vector3d(0, 0, 0);
        double wall = (PlanetLayout.ALONG_MAX + 1000) * U;
        assertNull(PlanetLayout.placeAlong(List.of(new PlanetLayout.Sphere(huge, wall)), G, EYE, new Vector3d(1, 0, 0), U));
    }

    static List<PlanetLayout.Sphere> line(int n) {
        List<PlanetLayout.Sphere> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new PlanetLayout.Sphere(new Vector3d(i * 1000 * 80, 0, 0), 100 * 80));
        return out;
    }

    @Test void theEightNearestAreComplete() {
        List<Integer> r = PlanetLayout.ranked(line(12), new Vector3d(), java.util.Set.of(), 80);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), r.subList(0, PlanetLayout.NEAR_PLANETS));
        assertEquals(List.of(8, 9), r.subList(8, 10), "the next two are made ahead");
    }

    @Test void aCompletePlanetStaysUntilAnotherIsClearlyNearer() {
        List<PlanetLayout.Sphere> s = new ArrayList<>(line(8));
        s.add(new PlanetLayout.Sphere(new Vector3d(7000 * 80 - 10 * 80, 0, 0), 100 * 80)); // 10 blocks nearer than 7
        java.util.Set<Integer> had = java.util.Set.of(0, 1, 2, 3, 4, 5, 6, 7);
        assertTrue(PlanetLayout.ranked(s, new Vector3d(), had, 80).subList(0, 8).contains(7));
        s.set(8, new PlanetLayout.Sphere(new Vector3d(7000 * 80 - 100 * 80, 0, 0), 100 * 80)); // 100 nearer: still not
        assertTrue(PlanetLayout.ranked(s, new Vector3d(), had, 80).subList(0, 8).contains(7), "flying past, no swap for 100 blocks");
        s.set(8, new PlanetLayout.Sphere(new Vector3d(7000 * 80 - 170 * 80, 0, 0), 100 * 80)); // 170 nearer
        List<Integer> r = PlanetLayout.ranked(s, new Vector3d(), had, 80).subList(0, 8);
        assertTrue(r.contains(8));
        assertFalse(r.contains(7));
    }

    @Test void farPlanetsLoseDetailWithDistanceWithSlack() {
        double r = 100 * 80;
        assertEquals(12, PlanetLayout.farPatches(r, 500 * 80, 0));   // ~23 degrees
        assertEquals(6, PlanetLayout.farPatches(r, 4000 * 80, 0));   // ~2.9 degrees
        assertEquals(3, PlanetLayout.farPatches(r, 9000 * 80, 0));   // ~1.3 degrees
        assertEquals(2, PlanetLayout.farPatches(r, 30000 * 80, 0));  // ~0.38 degrees
        assertEquals(1, PlanetLayout.farPatches(r, 80000 * 80, 0));  // ~0.14 degrees: a cube
        double six = r / Math.tan(Math.toRadians(3)); // exactly 6 degrees across
        assertEquals(12, PlanetLayout.farPatches(r, six * 1.05, 12), "a little smaller keeps 12");
        assertEquals(6, PlanetLayout.farPatches(r, six * 0.95, 6), "a little bigger keeps 6");
        assertEquals(12, PlanetLayout.farPatches(r, six * 0.7, 6));
    }
}
