package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The ship messages through Minecraft's own buffers. */
class ShipPayloadsTest {
    @Test void shipDoTravels() {
        var d = new MpPayloads.ShipDo(MpPayloads.ShipDo.LEAVE, 1, "id", 1.5, -2, 3, 0, 0.6, 0, 0.8, "Space");
        assertEquals(d, MpPayloadsTest.roundTrip(MpPayloads.ShipDo.CODEC, d));
    }

    @Test void poseAndPilotTravel() {
        var p = new MpPayloads.ShipPose("id", 1, 2, 3, 0, 0, 0, 1);
        assertEquals(p, MpPayloadsTest.roundTrip(MpPayloads.ShipPose.CODEC, p));
        var w = new MpPayloads.ShipPilot("id", "4a6e0f1c-0000-0000-0000-000000000001");
        assertEquals(w, MpPayloadsTest.roundTrip(MpPayloads.ShipPilot.CODEC, w));
    }

    @Test void putAndListTravel() {
        byte[] data = ShipCodec.encode(ShipCodecTest.ship("a"));
        assertArrayEquals(data, MpPayloadsTest.roundTrip(MpPayloads.ShipPut.CODEC, new MpPayloads.ShipPut(data)).data());
        byte[] list = ShipCodec.encodeList(java.util.List.of(ShipCodecTest.ship("a")));
        assertArrayEquals(list, MpPayloadsTest.roundTrip(MpPayloads.ShipList.CODEC, new MpPayloads.ShipList(list)).data());
    }

    @Test void anOversizedNameIsNotRead() {
        var b = MpPayloadsTest.buf();
        b.writeUtf("y".repeat(200), 400);
        assertThrows(RuntimeException.class, () -> MpPayloads.ShipPilot.CODEC.decode(b));
    }
}
