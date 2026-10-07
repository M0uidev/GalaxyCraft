package dev.moui.galaxycraft.universe;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class UniverseTest {
    static final List<String> LAND = List.of("minecraft:plains", "minecraft:desert", "minecraft:forest");
    static final double U = 80;

    @Test void theSameSeedMakesTheSameUniverse() {
        var a = new Universe(7, U).around(UPos.ZERO, 3);
        var b = new Universe(7, U).around(UPos.ZERO, 3);
        var c = new Universe(8, U).around(UPos.ZERO, 3);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test void homeIsAlwaysThereAtTheCenter() {
        for (long seed = 0; seed < 50; seed++) {
            var home = new Universe(seed, U).star(Universe.Sector.HOME);
            assertTrue(home.isPresent());
            assertEquals(UPos.ZERO, home.get().center());
            assertTrue(home.get().home());
        }
    }

    @Test void aboutDensityOfTheSectorsHoldASystem() {
        var stars = new Universe(3, U).around(UPos.ZERO, 6);
        double share = stars.size() / Math.pow(13, 3);
        assertEquals(Universe.DENSITY, share, 0.05);
    }

    @Test void twoSystemsNeverReachEachOther() {
        var stars = new Universe(11, U).around(UPos.ZERO, 3);
        double apart = (2 * Universe.SYSTEM_BLOCKS + Universe.GAP_BLOCKS) * U;
        for (int i = 0; i < stars.size(); i++)
            for (int j = i + 1; j < stars.size(); j++)
                assertTrue(stars.get(i).center().minus(stars.get(j).center()).length() >= apart - 1e-6);
    }

    @Test void eachSystemIsInsideItsOwnSector() {
        for (var s : new Universe(5, U).around(UPos.ZERO, 4))
            assertEquals(s.sector(), Universe.sectorOf(s.center()));
    }

    @Test void farAwayIsAsGoodAsHere() {
        // A trillion sectors out: the sector math and the system's place stay exact.
        long far = 1_000_000_000_000L;
        Universe u = new Universe(9, U);
        Universe.Sector s = null;
        Optional<Universe.Star> star = Optional.empty();
        for (long k = 0; star.isEmpty(); k++) star = u.star(s = new Universe.Sector(far + k, -far, far));
        Vector3d off = star.get().center().minus(s.center());
        assertTrue(Math.abs(off.x) <= u.jitter() && Math.abs(off.y) <= u.jitter() && Math.abs(off.z) <= u.jitter());
        assertEquals(s, Universe.sectorOf(star.get().center()));
        var near = u.around(star.get().center(), 1);
        assertEquals(star.get(), near.getFirst(), "nearest first");
    }

    @Test void aSystemsPlanetsStayWithinItsReachAndApart() {
        Universe u = new Universe(21, U);
        int systems = 0;
        for (var star : u.around(UPos.ZERO, 2)) {
            if (star.home()) continue;
            var made = u.system(star, LAND);
            assertFalse(made.entries().isEmpty());
            assertEquals(new Vector3d(), made.entries().getFirst().center(), "the first at the center");
            for (var e : made.entries()) {
                double reach = e.center().length() + PlanetSession.gravityRadius(e.radius()) * U;
                assertTrue(reach <= Universe.SYSTEM_BLOCKS * U + 1e-6);
                assertTrue(e.radius() >= star.minRadius() && e.radius() <= star.maxRadius());
            }
            assertSame(made, u.system(star, LAND), "kept made");
            systems++;
        }
        assertTrue(systems > 20);
    }

    @Test void theSystemAtAPointIsOnlyNearItsCenter() {
        Universe u = new Universe(2, U);
        var home = u.systemAt(UPos.of(new Vector3d(100 * U, 0, 0)), 0);
        assertTrue(home.isPresent() && home.get().home());
        assertTrue(u.systemAt(UPos.of(new Vector3d((Universe.SYSTEM_BLOCKS + 10) * U, 0, 0)), 0).isEmpty());
        assertTrue(u.systemAt(UPos.of(new Vector3d((Universe.SYSTEM_BLOCKS + 10) * U, 0, 0)), 20).isPresent());
    }

    @Test void aBigHomeGalaxyKeepsItsNeighborsAway() {
        // 7000 blocks: a world's galaxy of 64 planets far apart.
        Universe u = new Universe(3, U).withHome(7000);
        for (Universe.Star s : u.around(UPos.ZERO, 2))
            if (!s.home()) assertTrue(s.center().minus(UPos.ZERO).length() / U >= 7000 + Universe.SYSTEM_BLOCKS + Universe.GAP_BLOCKS);
        assertTrue(u.systemAt(UPos.of(new Vector3d(6900 * U, 0, 0)), 0).orElseThrow().home(), "home reaches that far");
        assertFalse(new Universe(3, U).systemAt(UPos.of(new Vector3d(6900 * U, 0, 0)), 0).map(Universe.Star::home).orElse(false),
                "a home of the usual size does not");
    }

    @Test void manySectorsAreCheap() {
        Universe u = new Universe(4, U);
        u.around(UPos.ZERO, 8);
        long t = System.nanoTime();
        var stars = u.around(UPos.of(new Vector3d(1e7, 0, 0)), 8);
        double ms = (System.nanoTime() - t) / 1e6;
        assertTrue(stars.size() > 2000);
        assertTrue(ms < 250, "17^3 sectors in " + ms + " ms");
    }
}
