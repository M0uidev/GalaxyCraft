package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class StationTest {
    static final char STONE = (char) CubeBlocks.INSTANCE.id(Material.STONE), CORE = (char) CubeBlocks.INSTANCE.id(Material.DIRT);

    static Station station(String id) {
        return Station.create(id, "Station", new Quaterniond(), CubeBlocks.INSTANCE, STONE, CORE);
    }

    @Test void starterSlabWithTheCoreInTheMiddle() {
        Station s = station("abcd1234");
        FlatGrid g = s.grid();
        assertEquals(CORE, s.planet.get(g.cellOf(0, 0, 0)));
        assertEquals(STONE, s.planet.get(g.cellOf(4, 0, -4)));
        assertEquals(Blocks.AIR, s.planet.get(g.cellOf(5, 0, 0)));
        assertEquals(Blocks.AIR, s.planet.get(g.cellOf(0, 1, 0)));
        assertEquals(81, s.blockCount());
        assertEquals(StationShape.starter(), s.bounds);
    }

    @Test void aBlockInsideTheSlackGrowsTheBoundsOnly() {
        Station s = station("abcd1234");
        int c = s.grid().cellOf(6, 1, 0);
        s.planet.set(c, STONE);
        assertFalse(s.changed(c));
        assertEquals(6, s.bounds.x1());
        assertEquals(1, s.bounds.y1());
    }

    @Test void breakingDoesNotGrowAnything() {
        Station s = station("abcd1234");
        int c = s.grid().cellOf(4, 0, 4);
        s.planet.set(c, Blocks.AIR);
        assertFalse(s.changed(c));
        assertEquals(StationShape.starter(), s.bounds);
        assertEquals(80, s.blockCount());
    }

    @Test void regrowKeepsEveryCellAtItsStationCoordinateAndPlace() {
        Station s = Station.create("abcd1234", "Station", new Quaterniond().rotateY(1), CubeBlocks.INSTANCE, STONE, CORE);
        FlatGrid before = s.grid();
        int x = before.ox + 2; // inside the outer edge
        int c = before.cellOf(x, 0, 0);
        s.planet.set(c, STONE);
        assertTrue(s.changed(c));
        Vector3d where = before.center(c);
        s.regrow();
        FlatGrid after = s.grid();
        assertNotSame(before, after);
        int c2 = after.cellOf(x, 0, 0);
        assertEquals(STONE, s.planet.get(c2));
        assertEquals(0, where.distance(after.center(c2)), 1e-9);
        assertEquals(CORE, s.planet.get(after.cellOf(0, 0, 0)));
        assertFalse(StationShape.nearEdge(after, x, 0, 0));
        assertEquals(82, s.blockCount());
    }

    @Test void allowedFollowsTheLimits() {
        Station s = station("abcd1234");
        assertTrue(s.allowed(0, 79, 0));
        assertFalse(s.allowed(0, 80, 0));
        assertFalse(s.allowed(300, 0, 0));
    }

    @Test void newIdsAreEightHexDigits() {
        assertTrue(Station.newId(new java.util.Random(1)).matches("[0-9a-f]{8}"));
    }

    @Test void placementOnlyInOpenSpace() {
        var planet = new dev.moui.galaxycraft.gravity.GravityBody.Sphere(new Vector3d(), 100);
        assertNotNull(Station.refusal(new Vector3d(50, 0, 0), java.util.List.of(planet), 0, 8)); // in its gravity
        assertNotNull(Station.refusal(new Vector3d(110, 0, 0), java.util.List.of(planet), 0, 8)); // within 16 of it
        assertNull(Station.refusal(new Vector3d(200, 0, 0), java.util.List.of(planet), 0, 8));
        assertNotNull(Station.refusal(new Vector3d(200, 0, 0), java.util.List.of(), 8, 8)); // every slot taken
    }
}
