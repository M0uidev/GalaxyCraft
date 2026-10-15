package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

/** What a station does when someone else (a guest) edits it: limits, growth, regrow, and the bytes it travels in. */
class StationSharedTest {
    @Test void aCellPastTheLimitsIsNotAllowed() {
        Station s = StationTest.station("abcd1234");
        FlatGrid g = s.grid();
        assertTrue(s.allowedCell(g.cellOf(2, 0, 2)));
        assertFalse(s.allowedCell(-1));
        assertFalse(s.allowedCell(g.cellCount()));
    }

    @Test void settleGrowsTheBoundsAndRegrowsNearTheSide() {
        Station s = StationTest.station("abcd1234");
        FlatGrid before = s.grid();
        int key = s.gridKey();
        int c = before.cellOf(before.ox + 2, 0, 0);
        s.planet.set(c, StationTest.STONE);
        assertTrue(s.settle(new int[] {c}), "a block near the side regrows the grid");
        assertNotSame(before, s.grid());
        assertNotEquals(key, s.gridKey(), "so the old numbers are known stale");
        assertEquals(StationTest.STONE, s.planet.get(s.grid().cellOf(before.ox + 2, 0, 0)));
    }

    @Test void settleLeavesAnInnerBlockAlone() {
        Station s = StationTest.station("abcd1234");
        FlatGrid before = s.grid();
        int key = s.gridKey();
        int c = before.cellOf(0, 1, 0);
        s.planet.set(c, StationTest.STONE);
        assertFalse(s.settle(new int[] {c, -1, before.cellCount() + 4}));
        assertSame(before, s.grid());
        assertEquals(key, s.gridKey());
        assertEquals(1, s.bounds.y1());
    }

    @Test void theGridKeyFollowsTheShapeNotTheObject() {
        Station a = StationTest.station("aaaa0001"), b = StationTest.station("bbbb0002");
        assertEquals(a.gridKey(), b.gridKey());
        assertTrue(a.gridKey() > 0);
        int c = a.grid().cellOf(a.grid().ox + 2, 0, 0);
        a.planet.set(c, StationTest.STONE);
        a.settle(new int[] {c});
        assertNotEquals(a.gridKey(), b.gridKey());
    }

    @Test void theBytesAGuestIsSentGiveTheStationBack() throws Exception {
        Station s = Station.create("cafe0001", "Farm", new Quaterniond().rotateXYZ(0.1, 0.2, 0.3), CubeBlocks.INSTANCE,
                StationTest.STONE, StationTest.CORE);
        s.stage = "GalaxyCraftSpace";
        s.center = new org.joml.Vector3d(1e7, -5, 3.25);
        byte[] bytes = StationStore.encode(s, s.planet.cells(), CubeBlocks.INSTANCE);
        Station r = StationStore.decode(bytes, CubeBlocks.INSTANCE, CubeBlocks.INSTANCE::parse);
        assertEquals("cafe0001", r.id);
        assertEquals("Farm", r.name);
        assertEquals("GalaxyCraftSpace", r.stage);
        assertArrayEquals(s.planet.cells(), r.planet.cells());
        assertEquals(s.gridKey(), r.gridKey(), "the same grid, so the same cell numbers");
        assertEquals(s.bounds, r.bounds);
        assertThrows(java.io.IOException.class, () -> StationStore.decode(new byte[] {1, 2, 3}, CubeBlocks.INSTANCE, CubeBlocks.INSTANCE::parse));
    }
}
