package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The Launch Star messages through Minecraft's own buffers. */
class StarPayloadsTest {
    @Test void starDoTravels() {
        var d = new MpPayloads.StarDo(0, 1, "id", 1.5, -2, 3, 0, 1, 0, "Space", "1,2,3", "code");
        assertEquals(d, MpPayloadsTest.roundTrip(MpPayloads.StarDo.CODEC, d));
    }

    @Test void starPutListAndGoneTravel() {
        byte[] data = StarCodec.encode(StarCodecTest.star("a"));
        assertArrayEquals(data, MpPayloadsTest.roundTrip(MpPayloads.StarPut.CODEC, new MpPayloads.StarPut(data)).data());
        byte[] list = StarCodec.encodeList(java.util.List.of(StarCodecTest.star("a")));
        assertArrayEquals(list, MpPayloadsTest.roundTrip(MpPayloads.StarList.CODEC, new MpPayloads.StarList(list)).data());
        assertEquals(new MpPayloads.StarGone("x"), MpPayloadsTest.roundTrip(MpPayloads.StarGone.CODEC, new MpPayloads.StarGone("x")));
    }

    @Test void anOversizedNameIsNotRead() {
        var b = MpPayloadsTest.buf();
        b.writeUtf("y".repeat(200), 400);
        assertThrows(RuntimeException.class, () -> MpPayloads.StarGone.CODEC.decode(b));
    }
}
