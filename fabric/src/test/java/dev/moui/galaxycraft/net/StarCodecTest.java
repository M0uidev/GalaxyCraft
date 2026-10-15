package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.launchstar.StarRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StarCodecTest {
    static StarRecord star(String id) {
        return new StarRecord(id, new double[] {1.5, -2e9, 3}, new double[] {0, 1, 0}, "GalaxyCraftSpace", "1,2,3", "ñandú-1");
    }

    static void same(StarRecord a, StarRecord b) {
        assertEquals(a.id(), b.id());
        assertArrayEquals(a.pos(), b.pos());
        assertArrayEquals(a.up(), b.up());
        assertEquals(a.stage(), b.stage());
        assertEquals(a.body(), b.body());
        assertEquals(a.code(), b.code());
    }

    @Test void oneStarRoundTrips() throws Exception {
        same(star("a"), StarCodec.decode(StarCodec.encode(star("a"))));
    }

    @Test void aListRoundTripsAndEmptyToo() throws Exception {
        List<StarRecord> in = List.of(star("a"), star("b"));
        List<StarRecord> out = StarCodec.decodeList(StarCodec.encodeList(in));
        assertEquals(2, out.size());
        same(in.get(1), out.get(1));
        assertTrue(StarCodec.decodeList(StarCodec.encodeList(List.of())).isEmpty());
    }

    @Test void junkIsBadNotAnAllocation() {
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decode(new byte[] {(byte) 0xFF, (byte) 0xFF, 1}));
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decode(new byte[0]));
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decodeList(new byte[] {(byte) 0xFF, (byte) 0xFF}));
        byte[] good = StarCodec.encode(star("a"));
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decode(java.util.Arrays.copyOf(good, good.length - 3)));
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decode(java.util.Arrays.copyOf(good, good.length + 1)));
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decodeList(new byte[StarCodec.MAX_LIST + 1]));
    }

    @Test void nonFiniteNumbersAreBad() {
        StarRecord r = new StarRecord("a", new double[] {Double.NaN, 0, 0}, new double[] {0, 1, 0}, "s", "b", "c");
        assertThrows(StarCodec.Bad.class, () -> StarCodec.decode(StarCodec.encode(r)));
    }

    @Test void aLongListStaysUnderTheCap() throws Exception {
        List<StarRecord> many = new ArrayList<>();
        for (int i = 0; i < 256; i++) many.add(star("id-" + i));
        assertTrue(StarCodec.encodeList(many).length <= StarCodec.MAX_LIST);
        assertEquals(256, StarCodec.decodeList(StarCodec.encodeList(many)).size());
    }
}
