package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipStatsTest {
    @Test
    void aShipNeedsAnEngineAndACabin() {
        assertFalse(new ShipModules(1, 0, 1, 1).valid());
        assertFalse(new ShipModules(1, 1, 1, 0).valid());
        assertTrue(new ShipModules(0, 1, 0, 1).valid());
    }

    @Test
    void repairedGivesTheWeakestValidShip() {
        ShipModules r = new ShipModules(9, 0, -2, 0).repaired();
        assertEquals(new ShipModules(3, 1, 0, 1), r);
        assertTrue(r.valid());
    }

    @Test
    void highTiersAreFasterTougherAndRoomier() {
        ShipStats low = ShipStats.of(new ShipModules(1, 1, 1, 1));
        ShipStats high = ShipStats.of(new ShipModules(3, 3, 3, 3));
        assertTrue(high.speed() > low.speed());
        assertTrue(high.turn() > low.turn());
        assertTrue(high.durability() > low.durability());
        assertEquals(1, low.seats());
        assertEquals(4, high.seats());
        assertEquals(0, low.storage());
        assertEquals(27, high.storage());
    }

    @Test
    void aMissingHullOrRudderIsTheWorstNotAnError() {
        ShipStats s = ShipStats.of(new ShipModules(0, 1, 0, 1));
        assertEquals(20, s.durability());
        assertEquals(0.5, s.turn(), 1e-9);
    }
}
