package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/** The messages through Minecraft's own buffers, as they travel. */
class MpPayloadsTest {
    static RegistryFriendlyByteBuf buf() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf b = buf();
        codec.encode(b, value);
        T back = codec.decode(b);
        assertEquals(0, b.readableBytes(), "all of it was read");
        return back;
    }

    @Test
    void helloTravels() {
        var hello = new MpPayloads.Hello(1, "123e4567-e89b-12d3-a456-426614174000", "{\"version\":1}");
        assertEquals(hello, roundTrip(MpPayloads.Hello.CODEC, hello));
    }

    @Test
    void helloAckTravels() {
        assertEquals(new MpPayloads.HelloAck(1, true), roundTrip(MpPayloads.HelloAck.CODEC, new MpPayloads.HelloAck(1, true)));
    }

    @Test
    void stateAndPeerCarryTheWireBytes() {
        byte[] data = Wire.encode(WireTest.sample());
        assertArrayEquals(data, roundTrip(MpPayloads.State.CODEC, new MpPayloads.State(data)).data());
        UUID id = UUID.randomUUID();
        var back = roundTrip(MpPayloads.Peer.CODEC, new MpPayloads.Peer(id, data));
        assertEquals(id, back.id());
        assertEquals(WireTest.sample(), Wire.decode(back.data()));
    }

    @Test
    void skinAndGoneTravel() {
        UUID id = UUID.randomUUID();
        byte[] skin = SkinCodec.encode(SkinCodecTest.skin(9));
        var back = roundTrip(MpPayloads.Skin.CODEC, new MpPayloads.Skin(id, skin));
        assertEquals(id, back.id());
        assertNotNull(SkinCodec.decode(back.data()));
        assertEquals(0, roundTrip(MpPayloads.Skin.CODEC, new MpPayloads.Skin(id, new byte[0])).data().length, "the clear message");
        assertEquals(id, roundTrip(MpPayloads.Gone.CODEC, new MpPayloads.Gone(id)).id());
    }

    @Test
    void anOversizedStateOrSkinIsRefusedWhileReading() {
        RegistryFriendlyByteBuf slightly = buf(); // a wrong size reads, and Wire.decode counts it as malformed
        slightly.writeByteArray(new byte[Wire.SIZE + 9]);
        byte[] wrong = MpPayloads.State.CODEC.decode(slightly).data();
        assertThrows(Wire.Malformed.class, () -> Wire.decode(wrong));
        RegistryFriendlyByteBuf b = buf(); // far too big is refused while reading
        b.writeByteArray(new byte[MpPayloads.MAX_STATE + 1]);
        assertThrows(RuntimeException.class, () -> MpPayloads.State.CODEC.decode(b));
        RegistryFriendlyByteBuf s = buf();
        s.writeUUID(UUID.randomUUID());
        s.writeByteArray(new byte[Protocol.SKIN_MAX + 1]);
        assertThrows(RuntimeException.class, () -> MpPayloads.Skin.CODEC.decode(s));
    }

    @Test
    void anOversizedHelloTextIsRefused() {
        RegistryFriendlyByteBuf over = buf(); // a text longer than the codec's own bound
        over.writeVarInt(1);
        over.writeUtf("x", 64);
        over.writeUtf("y".repeat(MpPayloads.MAX_GALAXY_JSON + 5), MpPayloads.MAX_GALAXY_JSON + 10);
        assertThrows(RuntimeException.class, () -> MpPayloads.Hello.CODEC.decode(over));
    }

    @Test
    void planetMessagesTravel() {
        var ask = new MpPayloads.PlanetAsk(70_000, 0x1234_5678_9ABCL);
        assertEquals(ask, roundTrip(MpPayloads.PlanetAsk.CODEC, ask));
        var head = new MpPayloads.PlanetHead(3, PlanetXfer.STATE_DATA, -42L, 2_000_000, 91);
        assertEquals(head, roundTrip(MpPayloads.PlanetHead.CODEC, head));
        byte[] part = PlanetXferTest.bytes(PlanetXfer.CHUNK, 9);
        var chunk = roundTrip(MpPayloads.PlanetChunk.CODEC, new MpPayloads.PlanetChunk(3, 7, part));
        assertEquals(7, chunk.seq());
        assertArrayEquals(part, chunk.data());
    }

    @Test
    void aChunkBiggerThanAChunkIsNotRead() {
        RegistryFriendlyByteBuf b = buf();
        MpPayloads.PlanetChunk.CODEC.encode(b, new MpPayloads.PlanetChunk(0, 0, new byte[PlanetXfer.CHUNK + 1]));
        assertThrows(RuntimeException.class, () -> MpPayloads.PlanetChunk.CODEC.decode(b));
    }

    @Test
    void editMessagesTravel() {
        byte[] body = EditCodec.encode(new EditCodec.Msg(2, 5, 0, java.util.List.of(new EditCodec.Def(1, "minecraft:stone")), new int[] {4, 9}, new int[] {1, 1}));
        assertArrayEquals(body, roundTrip(MpPayloads.Edits.CODEC, new MpPayloads.Edits(body)).data());
        assertEquals(new MpPayloads.PlanetDrop(12), roundTrip(MpPayloads.PlanetDrop.CODEC, new MpPayloads.PlanetDrop(12)));
        assertEquals(new MpPayloads.Resync(12), roundTrip(MpPayloads.Resync.CODEC, new MpPayloads.Resync(12)));
    }

    @Test
    void ridingMessagesTravel() {
        int keys = MpPayloads.Steer.pack(true, false, true, false, true);
        assertEquals(new MpPayloads.Steer(keys), roundTrip(MpPayloads.Steer.CODEC, new MpPayloads.Steer(keys)));
        assertEquals(MpPayloads.Steer.FORWARD | MpPayloads.Steer.LEFT | MpPayloads.Steer.SNEAK, keys);
        var seat = new MpPayloads.RideSeat(5, 1.5f, -2f, 300f);
        assertEquals(seat, roundTrip(MpPayloads.RideSeat.CODEC, seat));
        assertEquals(new MpPayloads.RideEnd(), roundTrip(MpPayloads.RideEnd.CODEC, new MpPayloads.RideEnd()));
    }

    @Test
    void anOversizedEditsIsNotRead() {
        RegistryFriendlyByteBuf b = buf();
        b.writeByteArray(new byte[EditCodec.MAX_WIRE + 1]);
        assertThrows(RuntimeException.class, () -> MpPayloads.Edits.CODEC.decode(b));
    }
}
