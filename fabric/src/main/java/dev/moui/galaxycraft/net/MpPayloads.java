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

    /** Server -> guest, ~5 Hz: the shadow's entities near it on a planet it holds (a MobsCodec body). */
    public record Mobs(byte[] data) implements CustomPacketPayload {
        public static final Type<Mobs> TYPE = typeOf("mp_mobs");
        public static final StreamCodec<RegistryFriendlyByteBuf, Mobs> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new Mobs(b.readByteArray(MobsCodec.MAX_WIRE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: hit (kind 0) or use (kind 1) a mob of the shadow, by the host's entity id; look: the guest's view in the planet's space. */
    public record MobHit(int planet, int entity, int kind, float lx, float ly, float lz) implements CustomPacketPayload {
        public static final Type<MobHit> TYPE = typeOf("mp_mob_hit");
        public static final StreamCodec<RegistryFriendlyByteBuf, MobHit> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet);
                    b.writeVarInt(p.entity);
                    b.writeByte(p.kind);
                    b.writeFloat(p.lx);
                    b.writeFloat(p.ly);
                    b.writeFloat(p.lz);
                },
                b -> new MobHit(b.readVarInt(), b.readVarInt(), b.readByte(), b.readFloat(), b.readFloat(), b.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One dropped item as sent: its id, place and speed in the planet's space, ticks until it can be picked up, the stack. */
    public record DropItem(int id, float x, float y, float z, float vx, float vy, float vz, int delay,
            net.minecraft.world.item.ItemStack stack) {}

    /** Most drops in one message. */
    public static final int MAX_DROPS = 200;

    /** Server -> guest, ~5 Hz: the dropped items of the planet the host runs. */
    public record Drops(int planet, java.util.List<DropItem> items) implements CustomPacketPayload {
        public static final Type<Drops> TYPE = typeOf("mp_drops");
        public static final StreamCodec<RegistryFriendlyByteBuf, Drops> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet);
                    int n = Math.min(p.items.size(), MAX_DROPS);
                    b.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        DropItem d = p.items.get(i);
                        b.writeVarInt(d.id);
                        b.writeFloat(d.x);
                        b.writeFloat(d.y);
                        b.writeFloat(d.z);
                        b.writeFloat(d.vx);
                        b.writeFloat(d.vy);
                        b.writeFloat(d.vz);
                        b.writeVarInt(d.delay);
                        net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.encode(b, d.stack);
                    }
                },
                b -> {
                    int planet = b.readVarInt(), n = b.readVarInt();
                    if (n > MAX_DROPS) throw new io.netty.handler.codec.DecoderException("too many drops: " + n);
                    java.util.List<DropItem> items = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++)
                        items.add(new DropItem(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                                b.readFloat(), b.readVarInt(), net.minecraft.world.item.ItemStack.OPTIONAL_STREAM_CODEC.decode(b)));
                    return new Drops(planet, items);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: I stepped on that drop; give it to me. */
    public record DropTake(int planet, int id) implements CustomPacketPayload {
        public static final Type<DropTake> TYPE = typeOf("mp_drop_take");
        public static final StreamCodec<RegistryFriendlyByteBuf, DropTake> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet);
                    b.writeVarInt(p.id);
                },
                b -> new DropTake(b.readVarInt(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: break that block (the host's shadow does it as this player); answered with UseDone (passed: nobody did, break it yourself). */
    public record Break(int nonce, int planet, int cell) implements CustomPacketPayload {
        public static final Type<Break> TYPE = typeOf("mp_break");
        public static final StreamCodec<RegistryFriendlyByteBuf, Break> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.nonce);
                    b.writeVarInt(p.planet);
                    b.writeVarInt(p.cell);
                },
                b -> new Break(b.readVarInt(), b.readVarInt(), b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: throw the stack in my hand (or one of it) onto the planet from here (planet space) with this speed. */
    public record Throw(int planet, boolean all, float x, float y, float z, float vx, float vy, float vz) implements CustomPacketPayload {
        public static final Type<Throw> TYPE = typeOf("mp_throw");
        public static final StreamCodec<RegistryFriendlyByteBuf, Throw> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet);
                    b.writeBoolean(p.all);
                    b.writeFloat(p.x);
                    b.writeFloat(p.y);
                    b.writeFloat(p.z);
                    b.writeFloat(p.vx);
                    b.writeFloat(p.vy);
                    b.writeFloat(p.vz);
                },
                b -> new Throw(b.readVarInt(), b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                        b.readFloat()));

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
    public record PlanetHead(int index, int state, long etag, int total, int seq) implements CustomPacketPayload {
        public static final Type<PlanetHead> TYPE = typeOf("mp_planet_head");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanetHead> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.index);
                    b.writeByte(p.state);
                    b.writeLong(p.etag);
                    b.writeVarInt(p.total);
                    b.writeVarInt(p.seq);
                },
                b -> new PlanetHead(b.readVarInt(), b.readByte(), b.readLong(), b.readVarInt(), b.readVarInt()));

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

    /** Cell changes of one catalog planet (an EditCodec body), both ways: a guest's edit; the host's, in its order. */
    public record Edits(byte[] data) implements CustomPacketPayload {
        public static final Type<Edits> TYPE = typeOf("mp_edits");
        public static final StreamCodec<RegistryFriendlyByteBuf, Edits> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new Edits(b.readByteArray(EditCodec.MAX_WIRE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: this guest no longer holds that planet; stop sending it edits. */
    public record PlanetDrop(int index) implements CustomPacketPayload {
        public static final Type<PlanetDrop> TYPE = typeOf("mp_planet_drop");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlanetDrop> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeVarInt(p.index), b -> new PlanetDrop(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: too far behind on that planet; drop it and ask for it again. */
    public record Resync(int index) implements CustomPacketPayload {
        public static final Type<Resync> TYPE = typeOf("mp_resync");
        public static final StreamCodec<RegistryFriendlyByteBuf, Resync> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeVarInt(p.index), b -> new Resync(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: a right click on a block of a planet the guest holds; the host runs it in the shadow as that guest. */
    public record Use(int nonce, int planet, int cell, int face, float hx, float hy, float hz, int hand) implements CustomPacketPayload {
        public static final Type<Use> TYPE = typeOf("mp_use");
        public static final StreamCodec<RegistryFriendlyByteBuf, Use> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.nonce);
                    b.writeVarInt(p.planet);
                    b.writeVarInt(p.cell);
                    b.writeByte(p.face);
                    b.writeFloat(p.hx);
                    b.writeFloat(p.hy);
                    b.writeFloat(p.hz);
                    b.writeByte(p.hand);
                },
                b -> new Use(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readByte(), b.readFloat(), b.readFloat(), b.readFloat(),
                        b.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: the click of that number was used by the block (passed false) or by nothing (passed: the guest places its block). */
    public record UseDone(int nonce, boolean passed) implements CustomPacketPayload {
        public static final Type<UseDone> TYPE = typeOf("mp_use_done");
        public static final StreamCodec<RegistryFriendlyByteBuf, UseDone> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.nonce);
                    b.writeBoolean(p.passed);
                },
                b -> new UseDone(b.readVarInt(), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest -> server: a block was placed from this hand outside creative: one fewer in the stack (the inventory is the server's). */
    public record UsedUp(int hand) implements CustomPacketPayload {
        public static final Type<UsedUp> TYPE = typeOf("mp_used_up");
        public static final StreamCodec<RegistryFriendlyByteBuf, UsedUp> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByte(p.hand), b -> new UsedUp(b.readByte()));

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
        PayloadTypeRegistry.serverboundPlay().register(Edits.TYPE, Edits.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Edits.TYPE, Edits.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlanetDrop.TYPE, PlanetDrop.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Resync.TYPE, Resync.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(UseDone.TYPE, UseDone.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UsedUp.TYPE, UsedUp.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Mobs.TYPE, Mobs.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MobHit.TYPE, MobHit.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Drops.TYPE, Drops.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DropTake.TYPE, DropTake.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Break.TYPE, Break.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Throw.TYPE, Throw.CODEC);
    }
}
