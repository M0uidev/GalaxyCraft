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
}
