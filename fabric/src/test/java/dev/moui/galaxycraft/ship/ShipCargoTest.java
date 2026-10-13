package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipCargoTest {
    @Test
    void capacityGrowsWithTheCabin() {
        assertEquals(0, ShipCargo.capacity(0));
        assertEquals(1, ShipCargo.capacity(1));
        assertEquals(2, ShipCargo.capacity(2));
        assertEquals(4, ShipCargo.capacity(3));
        assertEquals(4, ShipCargo.capacity(9));
    }

    @Test
    void lockedBaysRefuseChests() {
        ShipCargo c = new ShipCargo(2);
        assertTrue(c.canInstall(1));
        assertFalse(c.canInstall(2));
        assertTrue(c.locked(3));
        assertFalse(c.canInstall(-1));
        assertFalse(c.canInstall(4));
    }

    @Test
    void fourSmallChests() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(c.canInstall(i));
            c.install(i);
        }
        assertFalse(c.canInstall(0)); // taken
        assertEquals(27, c.chest(3).size());
    }

    @Test
    void joinMakesOneLargeChestOfTwoBays() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        assertTrue(c.canJoin(0));
        c.join(0);
        assertEquals(ShipCargo.Bay.LARGE_HEAD, c.bay(0));
        assertEquals(ShipCargo.Bay.LARGE_TAIL, c.bay(1));
        assertEquals(54, c.chest(0).size());
        assertSame(c.chest(0), c.chest(1));
        assertTrue(c.canSplit(0));
        c.split(0);
        assertEquals(ShipCargo.Bay.SMALL, c.bay(0));
        assertEquals(ShipCargo.Bay.SMALL, c.bay(1));
        assertEquals(27, c.chest(1).size());
    }

    @Test
    void onlyTheHeadsJoin() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        assertFalse(c.canJoin(1)); // bays 1+2 cross the middle
        assertFalse(c.canJoin(3));
        assertTrue(c.canJoin(2));
    }

    @Test
    void twoLargeAndOneLargeWithTwoSmall() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        c.join(0);
        c.join(2);
        assertEquals(ShipCargo.Bay.LARGE_TAIL, c.bay(3));
        c.split(2);
        assertEquals(ShipCargo.Bay.SMALL, c.bay(3));
    }

    @Test
    void joinNeedsBothBaysSmallAndUnlocked() {
        ShipCargo c = new ShipCargo(1);
        c.install(0);
        assertFalse(c.canJoin(0)); // bay 1 is locked
        ShipCargo d = new ShipCargo(4);
        d.install(0);
        assertFalse(d.canJoin(0)); // bay 1 empty
    }

    @Test
    void aChestWithItemsStaysAndAnEmptyOneComesOut() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        assertTrue(c.canRemove(0));
        c.remove(0);
        assertEquals(ShipCargo.Bay.EMPTY, c.bay(0));
        assertNull(c.chest(0));
        c.install(0);
        c.chest(0).markUsedForTest(); // a chest that holds something
        assertFalse(c.canRemove(0));
        assertThrows(IllegalStateException.class, () -> c.remove(0));
        assertEquals(ShipCargo.Bay.SMALL, c.bay(0));
    }

    @Test
    void aLargeChestComesOutOnlyAfterSplit() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        c.join(0);
        assertFalse(c.canRemove(0));
        assertFalse(c.canRemove(1));
    }

    @Test
    void aFullLargeChestCannotSplit() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        c.join(0);
        c.chest(0).markUsedForTest();
        assertFalse(c.canSplit(0));
    }
}
