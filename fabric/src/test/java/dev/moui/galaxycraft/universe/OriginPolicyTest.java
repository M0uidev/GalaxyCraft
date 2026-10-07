package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.PlanetLayout;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class OriginPolicyTest {
    static final double U = 80;
    static final List<PlanetLayout.Sphere> ONE = List.of(new PlanetLayout.Sphere(new Vector3d(), 100 * U));

    @Test void nearAPlanetIsNotClear() {
        assertFalse(OriginPolicy.clear(new Vector3d(150 * U, 0, 0), ONE, false, U));
        assertTrue(OriginPolicy.clear(new Vector3d(300 * U, 0, 0), ONE, false, U));
        assertFalse(OriginPolicy.clear(new Vector3d(300 * U, 0, 0), ONE, true, U), "landing");
    }

    @Test void inTheVoidTheOriginFollowsMarioPastMoveAt() {
        Origin o = new Origin();
        UPos near = UPos.of(new Vector3d(OriginPolicy.MOVE_AT - 1, 0, 0));
        UPos far = UPos.of(new Vector3d(OriginPolicy.MOVE_AT + 1, 0, 0));
        assertTrue(OriginPolicy.target(o, near, Optional.empty(), true).isEmpty());
        assertEquals(far, OriginPolicy.target(o, far, Optional.empty(), true).orElseThrow());
        assertTrue(OriginPolicy.target(o, far, Optional.empty(), false).isEmpty(), "waits for the clear");
    }

    @Test void pastMustAtItMovesAnyway() {
        Origin o = new Origin();
        UPos far = UPos.of(new Vector3d(0, -OriginPolicy.MUST_AT, 0));
        assertTrue(OriginPolicy.target(o, far, Optional.empty(), false).isPresent());
    }

    @Test void enteringASystemSnapsToItsCenterOnceThenStays() {
        Universe u = new Universe(13, U);
        Universe.Star star = u.around(UPos.of(new Vector3d(3 * Universe.SECTOR, 0, 0)), 3).stream()
                .filter(s -> !s.home()).findFirst().orElseThrow();
        // The origin left behind in the void, Mario arriving at the system's edge.
        UPos mario = star.center().plus(new Vector3d((Universe.SYSTEM_BLOCKS + 100) * U, 0, 0));
        Origin o = new Origin();
        o.moveTo(mario);
        var system = u.systemAt(mario, OriginPolicy.SYSTEM_MARGIN);
        assertEquals(Optional.of(star), system);
        UPos goal = OriginPolicy.target(o, mario, system, true).orElseThrow();
        assertEquals(star.center(), goal);
        o.moveTo(goal);
        // Anywhere in it from then on: no more moves, and every planet's floats stay fine.
        for (double b = -Universe.SYSTEM_BLOCKS; b <= Universe.SYSTEM_BLOCKS; b += 256) {
            UPos at = star.center().plus(new Vector3d(b * U, b * U / 2, 0));
            assertTrue(OriginPolicy.target(o, at, u.systemAt(at, OriginPolicy.SYSTEM_MARGIN), true).isEmpty());
            assertTrue(Math.abs(o.local(at).x) < OriginPolicy.MOVE_AT);
        }
    }
}
