package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.map.Course;
import dev.moui.galaxycraft.map.Orbit;
import dev.moui.galaxycraft.map.Trip;
import dev.moui.galaxycraft.universe.UPos;
import org.junit.jupiter.api.Test;

class TripCodecTest {
    static Trip trip() {
        UPos a = new UPos(1, -2, 3, 0.5, 100, -7.25), b = new UPos(1, -2, 4, 12, 3, 9);
        return new Trip(new Course(a, b, 123456789L, 0.75), new Orbit(b, 64, 1.25, 0.005, 123459999L));
    }

    @Test void roundTrip() throws Exception {
        assertEquals(trip(), TripCodec.decode(TripCodec.encode(trip())));
        assertEquals(TripCodec.SIZE, TripCodec.encode(trip()).length);
    }

    @Test void badBytesAreRefused() {
        assertThrows(ShipCodec.Bad.class, () -> TripCodec.decode(new byte[10]));
        byte[] raw = TripCodec.encode(trip());
        raw[40] = (byte) 0x7F;
        raw[41] = (byte) 0xF8; // a NaN in the first position's doubles
        raw[42] = raw[43] = raw[44] = raw[45] = raw[46] = raw[47] = 0;
        assertThrows(ShipCodec.Bad.class, () -> TripCodec.decode(raw));
    }

    @Test void aSpeedOfZeroIsRefused() {
        byte[] raw = TripCodec.encode(trip());
        for (int i = 0; i < 8; i++) raw[96 + 8 + i] = 0;
        assertThrows(ShipCodec.Bad.class, () -> TripCodec.decode(raw));
    }
}
