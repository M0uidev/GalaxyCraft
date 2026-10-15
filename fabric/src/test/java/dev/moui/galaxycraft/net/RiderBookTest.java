package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiderBookTest {
    private final UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);

    @Test
    void firstRiderSteersAndTheVehicleFillsUp() {
        RiderBook<String> book = new RiderBook<>();
        assertTrue(book.mount(a, 7));
        assertTrue(book.mount(b, 7));
        assertFalse(book.mount(c, 7));
        assertTrue(book.steers(a));
        assertFalse(book.steers(b));
        book.dismount(a);
        assertTrue(book.steers(b));
        assertEquals(-1, book.vehicleOf(a));
        assertEquals(7, book.vehicleOf(b));
    }

    @Test
    void mountingElsewhereLeavesTheOldVehicle() {
        RiderBook<String> book = new RiderBook<>();
        book.mount(a, 1);
        book.mount(a, 2);
        assertEquals(0, book.count(1));
        assertEquals(1, book.count(2));
    }

    @Test
    void keysExpire() {
        RiderBook<String> book = new RiderBook<>();
        assertEquals("none", book.keys(a, 0, "none"));
        book.steer(a, "fwd", 100);
        assertEquals("fwd", book.keys(a, 100 + RiderBook.STEER_TTL_TICKS, "none"));
        assertEquals("none", book.keys(a, 101 + RiderBook.STEER_TTL_TICKS, "none"));
    }

    @Test
    void dismountForgetsKeysAndRetainDropsTheAbsent() {
        RiderBook<String> book = new RiderBook<>();
        book.mount(a, 1);
        book.mount(b, 1);
        book.steer(a, "fwd", 5);
        book.retain(Set.of(b));
        assertEquals(-1, book.vehicleOf(a));
        assertEquals("none", book.keys(a, 5, "none"));
        assertEquals(1, book.vehicleOf(b));
    }
}
