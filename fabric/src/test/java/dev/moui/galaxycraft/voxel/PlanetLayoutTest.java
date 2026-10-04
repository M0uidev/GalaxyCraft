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
}
