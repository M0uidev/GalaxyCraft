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
        assertEquals(-27f, p.get(0).center()[0], 1e-6);
        assertEquals(-11.5f, p.get(0).center()[2], 1e-6);
    }

    @Test
    void theChestsStandAgainstTheSideWallsFacingTheMiddle() {
        ShipCargo c = new ShipCargo(4);
        for (int i = 0; i < 4; i++) c.install(i);
        List<CargoLayout.Placed> p = CargoLayout.chests(c);
        for (int i = 0; i < 4; i++) {
            float x = p.get(i).center()[0];
            assertTrue(Math.abs(x) > 20, "chest " + i + " at x " + x);
            assertEquals(x < 0 ? 90f : -90f, p.get(i).yaw());
        }
    }
}
