package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CargoLayoutTest {
    @Test
    void anEmptyHoldDrawsNothing() {
        assertTrue(CargoLayout.chests(new ShipCargo(4)).isEmpty());
    }

    @Test
    void eachSmallChestIsOneDrawnChestAndNoTwoShareAPlace() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        List<CargoLayout.Placed> p = CargoLayout.chests(c);
        assertEquals(4, p.size());
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++)
                assertFalse(p.get(i).center()[0] == p.get(j).center()[0] && p.get(i).center()[2] == p.get(j).center()[2]);
    }

    @Test
    void aLargeChestIsOneDoubleChestBetweenItsBays() {
        ShipCargo c = new ShipCargo(4);
        c.install(0);
        c.install(1);
        c.join(0);
        List<CargoLayout.Placed> p = CargoLayout.chests(c);
        assertEquals(1, p.size());
        assertTrue(p.get(0).large());
        assertEquals(0f, p.get(0).center()[0], 1e-6);
    }

    @Test
    void theHoldIsBehindTheSeats() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        float lastSeat = SeatLayout.seats(4).stream().map(s -> s[2]).min(Float::compare).orElse(0f);
        for (CargoLayout.Placed p : CargoLayout.chests(c)) assertTrue(p.center()[2] < lastSeat + 0.1f * 0 - 8, "chest at z " + p.center()[2]);
    }
}
