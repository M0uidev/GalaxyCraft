package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LiveCodecTest {
    @Test
    void roundTrip() {
        var a = new LiveCodec.Entry(1234, 7, LiveCodec.LID_NONE, 0, new byte[] {1, 2, 3});
        var b = new LiveCodec.Entry(99, 12, LiveCodec.LID_OPENING, 200, new byte[0]);
        LiveCodec.Body body = LiveCodec.decode(LiveCodec.encode(5, true, List.of(a, b), new int[] {4, 8}));
        assertNotNull(body);
        assertEquals(5, body.planet());
        assertTrue(body.full());
        assertEquals(2, body.upserts().size());
        assertEquals(1234, body.upserts().get(0).cell());
        assertArrayEquals(new byte[] {1, 2, 3}, body.upserts().get(0).data());
        assertEquals(LiveCodec.LID_OPENING, body.upserts().get(1).lid());
        assertEquals(200, body.upserts().get(1).open());
        assertArrayEquals(new int[] {4, 8}, body.removed());
    }

    @Test
    void emptyDelta() {
        LiveCodec.Body body = LiveCodec.decode(LiveCodec.encode(0, false, List.of(), new int[0]));
        assertNotNull(body);
        assertFalse(body.full());
        assertEquals(0, body.upserts().size());
    }

    @Test
    void rejectsGarbage() {
        byte[] ok = LiveCodec.encode(1, false, List.of(new LiveCodec.Entry(1, 1, 0, 0, new byte[4])), new int[] {2});
        assertNull(LiveCodec.decode(java.util.Arrays.copyOf(ok, ok.length - 1)));
        assertNull(LiveCodec.decode(java.util.Arrays.copyOf(ok, ok.length + 1)));
        byte[] badLid = ok.clone();
        badLid[2 + 1 + 1 + 4 + 2] = 9; // the lid byte of the first entry
        assertNull(LiveCodec.decode(badLid));
    }
}
