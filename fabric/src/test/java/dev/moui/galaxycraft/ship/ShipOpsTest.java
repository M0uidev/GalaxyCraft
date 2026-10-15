package dev.moui.galaxycraft.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.ShipOps.Request;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShipOpsTest {
    final AtomicInteger n = new AtomicInteger();
    final ShipStore host = ShipStore.memory();

    Request ship(double x) {
        return new Request(ShipOps.PLACE_SHIP, new double[] {x, 0, 0}, new double[] {0, 0, 0, 2}, "Space", new ShipModules(1, 2, 0, 1));
    }

    java.util.Optional<ShipRecord> apply(Request r) {
        return ShipOps.apply(host, r, () -> "id" + n.incrementAndGet(), 0.01);
    }

    @Test void aShipGetsTheHostsIdAndANormalRotation() {
        ShipRecord r = apply(ship(1)).orElseThrow();
        assertEquals("id1", r.id());
        assertEquals(1, r.rot()[3], 1e-9);
        assertEquals(1, host.all().size());
    }

    @Test void aPadIsKeptAsAPad() {
        ShipRecord r = apply(new Request(ShipOps.PLACE_PAD, new double[] {1, 0, 0}, new double[] {0, 0, 0, 1}, "Space", null)).orElseThrow();
        assertTrue(r.isPad());
    }

    @Test void crowdedOrBadRequestsAreRefused() {
        apply(ship(1));
        assertTrue(apply(ship(1.001)).isEmpty());
        assertTrue(apply(ship(Double.NaN)).isEmpty());
        assertTrue(apply(new Request(ShipOps.PLACE_SHIP, new double[] {9, 0, 0}, new double[] {0, 0, 0, 0}, "Space", new ShipModules(0, 1, 0, 1))).isEmpty());
        assertTrue(apply(new Request(ShipOps.PLACE_SHIP, new double[] {9, 0, 0}, new double[] {0, 0, 0, 1}, "Space", new ShipModules(0, 0, 0, 0))).isEmpty());
        assertTrue(apply(new Request(ShipOps.PLACE_SHIP, new double[] {9, 0, 0}, new double[] {0, 0, 0, 1}, "Space", null)).isEmpty());
        assertTrue(apply(new Request(7, new double[] {9, 0, 0}, new double[] {0, 0, 0, 1}, "Space", null)).isEmpty());
        assertEquals(1, host.all().size());
    }

    @Test void anotherStageIsNotCrowding() {
        apply(ship(1));
        assertTrue(apply(new Request(ShipOps.PLACE_SHIP, new double[] {1, 0, 0}, new double[] {0, 0, 0, 1}, "Other", new ShipModules(0, 1, 0, 1))).isPresent());
    }
}
