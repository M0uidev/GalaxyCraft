package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SeatLayoutTest {
    @Test
    void thereIsOneSeatPerSeatTheCabinHolds() {
        assertEquals(1, SeatLayout.seats(1).size());
        assertEquals(4, SeatLayout.seats(4).size());
    }

    @Test
    void theDriverSitsFurthestForwardAndSeatsDoNotOverlap() {
        var s = SeatLayout.seats(4);
        for (int i = 1; i < s.size(); i++) assertTrue(s.get(i)[2] < s.get(0)[2], "the driver is furthest forward (+z)");
        for (int i = 0; i < s.size(); i++)
            for (int j = i + 1; j < s.size(); j++)
                assertTrue(Math.hypot(s.get(i)[0] - s.get(j)[0], s.get(i)[2] - s.get(j)[2]) >= 12, "a cushion takes 12 px");
    }

    @Test
    void anEmptyOrHugeCountIsClamped() {
        assertEquals(1, SeatLayout.seats(0).size());
        assertEquals(4, SeatLayout.seats(99).size());
    }

    @Test
    void asksForCopiesSoCallersCannotMoveTheSeats() {
        SeatLayout.seats(1).get(0)[2] = 999;
        assertNotEquals(999f, SeatLayout.seats(1).get(0)[2]);
    }
}
