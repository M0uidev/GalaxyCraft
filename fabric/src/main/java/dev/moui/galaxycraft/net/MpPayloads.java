package dev.moui.galaxycraft.net;

import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The multiplayer messages. Every size is bounded on read, so a bad peer cannot make anyone allocate much. */
public final class MpPayloads {
    /** The galaxy text in Hello: a 64 planet catalog is a few KB; this is generous. */
    public static final int MAX_GALAXY_JSON = 262_144;
    /**
     * The most a State may carry on the wire. Wire.decode rejects anything but its exact size; this is
     * only the bound for reading, wider than that so a wrong size is a counted malformed state, not a
     * decoding error that closes the connection at once.
     */
    public static final int MAX_STATE = 256;

    private MpPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("galaxycraft", name));
    }

    /** Server -> guest, once on join: what it needs to enter the host's galaxy. */
    public record Hello(int version, String worldId, String galaxyJson) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = typeOf("mp_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.version);
                    b.writeUtf(p.worldId, 64);
                    b.writeUtf(p.galaxyJson, MAX_GALAXY_JSON);
                },
                b -> new Hello(b.readVarInt(), b.readUtf(64), b.readUtf(MAX_GALAXY_JSON)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: entered the galaxy (ok) or could not. */
    public record HelloAck(int version, boolean ok) implements CustomPacketPayload {
        public static final Type<HelloAck> TYPE = typeOf("mp_hello_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloAck> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.version);
                    b.writeBoolean(p.ok);
                },
                b -> new HelloAck(b.readVarInt(), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Player -> server, ~10 Hz: Wire-encoded PeerState. */
    public record State(byte[] data) implements CustomPacketPayload {
        public static final Type<State> TYPE = typeOf("mp_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new State(b.readByteArray(MAX_STATE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> players: another player's Wire-encoded state. */
    public record Peer(UUID id, byte[] data) implements CustomPacketPayload {
        public static final Type<Peer> TYPE = typeOf("mp_peer");
        public static final StreamCodec<RegistryFriendlyByteBuf, Peer> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeUUID(p.id);
                    b.writeByteArray(p.data);
                },
                b -> new Peer(b.readUUID(), b.readByteArray(MAX_STATE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player's custom skin (PNG), both ways: a player sends theirs (the id is ignored), the server
     * forwards it with the sender's id. Minecraft caps what a client sends at 32 KB.
     */
    public record Skin(UUID id, byte[] data) implements CustomPacketPayload {
        public static final int MAX = Protocol.SKIN_MAX;
        public static final Type<Skin> TYPE = typeOf("mp_skin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Skin> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeUUID(p.id);
                    b.writeByteArray(p.data);
                },
                b -> new Skin(b.readUUID(), b.readByteArray(MAX)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> players: that player left. */
    public record Gone(UUID id) implements CustomPacketPayload {
        public static final Type<Gone> TYPE = typeOf("mp_gone");
        public static final StreamCodec<RegistryFriendlyByteBuf, Gone> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUUID(p.id), b -> new Gone(b.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: send me that catalog planet; etag is the one of the copy the guest holds (0: none). */
    public record PlanetAsk(int index, long etag) implements CustomPacketPayload {
        public static final Type<PlanetAsk> TYPE = typeOf("mp_planet_ask");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanetAsk> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.index);
                    b.writeLong(p.etag);
                },
                b -> new PlanetAsk(b.readVarInt(), b.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: the answer to a PlanetAsk. state: data follows in `total` bytes (chunks), the guest's copy is the same, or failed. */
    public record PlanetHead(int index, int state, long etag, int total) implements CustomPacketPayload {
        public static final Type<PlanetHead> TYPE = typeOf("mp_planet_head");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanetHead> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.index);
                    b.writeByte(p.state);
                    b.writeLong(p.etag);
                    b.writeVarInt(p.total);
                },
                b -> new PlanetHead(b.readVarInt(), b.readByte(), b.readLong(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: the next piece of the planet after its head. */
    public record PlanetChunk(int index, int seq, byte[] data) implements CustomPacketPayload {
        public static final Type<PlanetChunk> TYPE = typeOf("mp_planet_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanetChunk> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.index);
                    b.writeVarInt(p.seq);
                    b.writeByteArray(p.data);
                },
                b -> new PlanetChunk(b.readVarInt(), b.readVarInt(), b.readByteArray(PlanetXfer.CHUNK)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Both sides must know the types: call from the common entrypoint. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloAck.TYPE, HelloAck.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Peer.TYPE, Peer.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Gone.TYPE, Gone.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlanetAsk.TYPE, PlanetAsk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlanetHead.TYPE, PlanetHead.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlanetChunk.TYPE, PlanetChunk.CODEC);
    }
}
