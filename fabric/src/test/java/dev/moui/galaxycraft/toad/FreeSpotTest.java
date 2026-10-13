package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FreeSpotTest {
    /** A world: ground below y=0 (solid), plus the extra solid cells given. */
    private static FreeSpot.Space world(int... solidXYZ) {
        java.util.Set<String> extra = new java.util.HashSet<>();
        for (int i = 0; i < solidXYZ.length; i += 3) extra.add(solidXYZ[i] + "," + solidXYZ[i + 1] + "," + solidXYZ[i + 2]);
        return (x, y, z) -> y < 0 || extra.contains(x + "," + y + "," + z);
    }

    @Test void freeGroundStaysWhereItIs() {
        assertArrayEquals(new int[] {3, 0, 4}, FreeSpot.nearest(world(), 3, 0, 4, 6));
    }

    @Test void insideTheGroundComesOutOnTop() {
        assertArrayEquals(new int[] {0, 0, 0}, FreeSpot.nearest(world(), 0, -3, 0, 6));
    }

    @Test void insideAHillComesOutBesideItNotThroughIt() {
        int[] cells = new int[5 * 5 * 4 * 3];
        int n = 0;
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 4; y++) for (int z = -2; z <= 2; z++) { cells[n++] = x; cells[n++] = y; cells[n++] = z; }
        FreeSpot.Space w = world(cells);
        int[] p = FreeSpot.nearest(w, 0, 2, 0, 8);
        assertNotNull(p);
        assertFalse(FreeSpot.inside(w, p[0], p[1], p[2]));
        assertTrue(w.solid(p[0], p[1] - 1, p[2]));
    }

    @Test void aOneBlockCaveIsNotAStandingSpot() {
        int[] p = FreeSpot.nearest(world(0, 1, 0), 0, 0, 0, 3);
        assertNotNull(p);
        assertFalse(p[0] == 0 && p[2] == 0);
    }

    @Test void nothingNearGivesNull() {
        FreeSpot.Space allSolid = (x, y, z) -> true;
        assertNull(FreeSpot.nearest(allSolid, 0, 0, 0, 4));
    }

    @Test void insideReportsFeetAndHead() {
        FreeSpot.Space w = world(2, 0, 2, 5, 1, 5);
        assertTrue(FreeSpot.inside(w, 2, 0, 2));
        assertTrue(FreeSpot.inside(w, 5, 0, 5));
        assertFalse(FreeSpot.inside(w, 0, 0, 0));
    }
}
