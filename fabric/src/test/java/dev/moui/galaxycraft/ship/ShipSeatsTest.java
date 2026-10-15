package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShipSeatsTest {
    final ShipSeats s = new ShipSeats();

    @Test void passengersTakeTheCushionsAfterThePilots() {
        assertEquals(1, s.sit("s", "a", 4));
        assertEquals(2, s.sit("s", "b", 4));
        assertEquals(1, s.sit("s", "a", 4)); // already seated
        assertEquals(3, s.sit("s", "c", 4));
        assertEquals(-1, s.sit("s", "d", 4)); // full
    }

    @Test void aOneSeatCabinHasNoRoomForPassengers() {
        assertEquals(-1, s.sit("s", "a", 1));
    }

    @Test void standingFreesTheSeat() {
        s.sit("s", "a", 3);
        s.sit("s", "b", 3);
        assertEquals(1, s.stand("s", "a"));
        assertEquals(-1, s.stand("s", "a"));
        assertEquals(1, s.sit("s", "c", 3));
    }

    @Test void leavingTheGameFreesEverySeatHeld() {
        s.sit("s", "a", 3);
        s.sit("t", "a", 3);
        s.sit("t", "b", 3);
        assertEquals(2, s.standAll("a").size());
        assertEquals(-1, s.seatOf("s", "a"));
        assertEquals(2, s.seatOf("t", "b"));
    }

    @Test void aParkedShipEmptiesItsCabin() {
        s.sit("s", "a", 4);
        s.sit("s", "b", 4);
        assertEquals(2, s.clearShip("s").size());
        assertTrue(s.all().isEmpty());
    }

    @Test void aCopyFollowsTheHost() {
        s.set("s", 2, "a");
        assertEquals(2, s.seatOf("s", "a"));
        s.set("s", 3, "a"); // moved
        assertEquals(3, s.seatOf("s", "a"));
        assertEquals(1, s.all().size());
        s.set("s", 3, "");
        assertEquals(-1, s.seatOf("s", "a"));
        s.set("s", 0, "x"); // the pilot's seat is not a passenger's
        assertTrue(s.all().isEmpty());
    }
}
