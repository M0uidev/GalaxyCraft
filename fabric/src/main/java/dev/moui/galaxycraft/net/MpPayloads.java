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
                (p, b) -> b.writeByteArray(p.data), b -> new State(b.readByteArray(Wire.SIZE + 8)));

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
                b -> new Peer(b.readUUID(), b.readByteArray(Wire.SIZE + 8)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A player's custom skin (PNG), both ways: a player sends theirs (the id is ignored), the server
     * forwards it with the sender's id. Minecraft caps what a client sends at 32 KB.
     */
    public record Skin(UUID id, byte[] png) implements CustomPacketPayload {
        public static final int MAX = 24 * 1024;
        public static final Type<Skin> TYPE = typeOf("mp_skin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Skin> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeUUID(p.id);
                    b.writeByteArray(p.png);
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

    /** Both sides must know the types: call from the common entrypoint. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HelloAck.TYPE, HelloAck.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Peer.TYPE, Peer.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Skin.TYPE, Skin.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Gone.TYPE, Gone.CODEC);
    }
}
