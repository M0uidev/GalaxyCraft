package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class WireTest {
    static PeerState sample() {
        return new PeerState(7, 1.5e9, -2.25, 3.0e6, 0f, 1f, 0f, 90f, -10f, 1f, 0f, -2f, Protocol.MODE_STEVE,
                Protocol.F_SNEAK | Protocol.F_GROUND);
    }

    @Test
    void roundTrips() {
        byte[] b = Wire.encode(sample());
        assertEquals(Wire.SIZE, b.length);
        assertEquals(sample(), Wire.decode(b));
    }

    @Test
    void shortInputIsMalformed() {
        assertThrows(Wire.Malformed.class, () -> Wire.decode(new byte[Wire.SIZE - 1]));
    }

    @Test
    void longInputIsMalformed() {
        assertThrows(Wire.Malformed.class, () -> Wire.decode(new byte[Wire.SIZE + 1]));
    }

    @Test
    void unknownModeIsMalformed() {
        byte[] b = Wire.encode(sample());
        b[Wire.SIZE - 3] = 9; // mode byte
        assertThrows(Wire.Malformed.class, () -> Wire.decode(b));
    }

    @Test
    void validRejectsNaNInfiniteAndAbsurd() {
        assertTrue(sample().valid());
        assertFalse(new PeerState(0, Double.NaN, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, Double.POSITIVE_INFINITY, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 1e30, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).valid(), "up vector of length 0");
        assertFalse(new PeerState(0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0).valid(), "up vector far from unit");
        assertFalse(new PeerState(0, 0, 0, 0, 0, 1, 0, Float.NaN, 0, 0, 0, 0, 0, 0).valid());
        assertFalse(new PeerState(0, 0, 0, 0, 0, 1, 0, 0, 0, 1e9f, 0, 0, 0, 0).valid(), "absurd velocity");
    }

    @Test
    void versionRefusalNamesBoth() {
        assertEquals(Optional.empty(), Protocol.refusal(1, 1));
        String r = Protocol.refusal(2, 1).orElseThrow();
        assertTrue(r.contains("2") && r.contains("1"), r);
    }
}
