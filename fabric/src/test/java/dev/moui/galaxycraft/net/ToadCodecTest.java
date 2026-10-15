package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.moui.galaxycraft.toad.ToadRecord;
import dev.moui.galaxycraft.toad.ToadType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToadCodecTest {
    @Test
    void roundTrip() throws Exception {
        ToadRecord a = new ToadRecord("t1", ToadType.GUIDE, new double[] {1, 2, 3}, new double[] {0, 0, 0, 1}, "stage", new double[] {4, 5, 6});
        ToadRecord b = new ToadRecord("t2", ToadType.TECHNICIAN, new double[] {7, 8, 9}, new double[] {0, 1, 0, 0}, "");
        var body = ToadCodec.decode(ToadCodec.encode(true, List.of(a, b), List.of("old")));
        assertTrue(body.full());
        assertEquals(2, body.puts().size());
        assertEquals("t1", body.puts().get(0).id());
        assertEquals(ToadType.TECHNICIAN, body.puts().get(1).type());
        assertArrayEquals(new double[] {4, 5, 6}, body.puts().get(0).home());
        assertNull(body.puts().get(1).home());
        assertEquals(List.of("old"), body.gone());
    }

    @Test
    void badInputsAreRefused() {
        assertThrows(ToadCodec.Bad.class, () -> ToadCodec.decode(new byte[] {1, 0}));
        assertThrows(ToadCodec.Bad.class, () -> ToadCodec.decode(new byte[ToadCodec.MAX_BYTES + 1]));
        ToadRecord nan = new ToadRecord("t", ToadType.GUIDE, new double[] {Double.NaN, 0, 0}, new double[] {0, 0, 0, 1}, "");
        assertThrows(ToadCodec.Bad.class, () -> ToadCodec.decode(ToadCodec.encode(false, List.of(nan), List.of())));
    }
}
