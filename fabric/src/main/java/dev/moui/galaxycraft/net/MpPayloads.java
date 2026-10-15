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

    /** Server -> guest: what changed among the live blocks near it on a planet it holds (a LiveCodec body). */
    public record Live(byte[] data) implements CustomPacketPayload {
        public static final Type<Live> TYPE = typeOf("mp_live");
        public static final StreamCodec<RegistryFriendlyByteBuf, Live> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new Live(b.readByteArray(LiveCodec.MAX_WIRE)));

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

    /** Guest -> server, ~10 Hz while on a planet: which one (-1: none), and where it stands and looks there (planet space). */
    public record Where(int planet, float fx, float fy, float fz, float lx, float ly, float lz) implements CustomPacketPayload {
        public static final Type<Where> TYPE = typeOf("mp_where");
        public static final StreamCodec<RegistryFriendlyByteBuf, Where> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet + 1);
                    b.writeFloat(p.fx);
                    b.writeFloat(p.fy);
                    b.writeFloat(p.fz);
                    b.writeFloat(p.lx);
                    b.writeFloat(p.ly);
                    b.writeFloat(p.lz);
                },
                b -> new Where(b.readVarInt() - 1, b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat()));

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

    /**
     * Server -> guest: the host's stations (a {@link StationList} body): all of them after the Hello is answered, then
     * the ones that changed (placed, packed, renamed). A station's number on the wire is its slot (see StationSlots).
     */
    public record StationUpdate(byte[] data) implements CustomPacketPayload {
        public static final Type<StationUpdate> TYPE = typeOf("mp_stations");
        public static final StreamCodec<RegistryFriendlyByteBuf, StationUpdate> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new StationUpdate(b.readByteArray(StationList.MAX_WIRE)));

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

    // ---- Launch Stars (the host's StarStore is the truth; a guest keeps a mirror) ----

    /** Server -> guest, once on entering: every star (a StarCodec list). */
    public record StarList(byte[] data) implements CustomPacketPayload {
        public static final Type<StarList> TYPE = typeOf("mp_star_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, StarList> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new StarList(b.readByteArray(StarCodec.MAX_LIST)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: a star was placed or changed its code (one StarCodec record). */
    public record StarPut(byte[] data) implements CustomPacketPayload {
        public static final Type<StarPut> TYPE = typeOf("mp_star_put");
        public static final StreamCodec<RegistryFriendlyByteBuf, StarPut> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new StarPut(b.readByteArray(1024)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: a star is gone (picked up, or a placing the host refused). */
    public record StarGone(String id) implements CustomPacketPayload {
        public static final Type<StarGone> TYPE = typeOf("mp_star_gone");
        public static final StreamCodec<RegistryFriendlyByteBuf, StarGone> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUtf(p.id, 96), b -> new StarGone(b.readUtf(96)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Guest -> server: place (0), pick up (1) or rename (2) a star; the fields as StarOps.Request. hand: the hand
     * that holds the star item (0 main, 1 off) for a placing, which the host checks and uses up.
     */
    public record StarDo(int kind, int hand, String id, double px, double py, double pz, double ux, double uy, double uz,
            String stage, String body, String code) implements CustomPacketPayload {
        public static final Type<StarDo> TYPE = typeOf("mp_star_do");
        public static final StreamCodec<RegistryFriendlyByteBuf, StarDo> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeByte(p.kind).writeByte(p.hand).writeUtf(p.id, 96);
                    b.writeDouble(p.px).writeDouble(p.py).writeDouble(p.pz);
                    b.writeDouble(p.ux).writeDouble(p.uy).writeDouble(p.uz);
                    b.writeUtf(p.stage, 96).writeUtf(p.body, 96).writeUtf(p.code, 96);
                },
                b -> new StarDo(b.readByte(), b.readByte(), b.readUtf(96), b.readDouble(), b.readDouble(), b.readDouble(),
                        b.readDouble(), b.readDouble(), b.readDouble(), b.readUtf(96), b.readUtf(96), b.readUtf(96)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- Ships (the host's ShipStore is the truth; a guest keeps a mirror) ----

    /** Server -> guest, once on entering: every ship and pad (a ShipCodec list). */
    public record ShipList(byte[] data) implements CustomPacketPayload {
        public static final Type<ShipList> TYPE = typeOf("mp_ship_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipList> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new ShipList(b.readByteArray(ShipCodec.MAX_LIST)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: a ship or pad was put down, or parked in a new place (one ShipCodec record). */
    public record ShipPut(byte[] data) implements CustomPacketPayload {
        public static final Type<ShipPut> TYPE = typeOf("mp_ship_put");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipPut> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByteArray(p.data), b -> new ShipPut(b.readByteArray(1024)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: this ship is flown by that player (a UUID text), or by nobody (empty). */
    public record ShipPilot(String id, String who) implements CustomPacketPayload {
        public static final Type<ShipPilot> TYPE = typeOf("mp_ship_pilot");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipPilot> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUtf(p.id, 96).writeUtf(p.who, 40), b -> new ShipPilot(b.readUtf(96), b.readUtf(40)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Guest -> server: put a ship (0) or a pad (1) down at pos/rot of stage, holding it in hand (0 main, 1 off; the
     * host reads the modules from that item and uses it up), board a ship (2: id), or leave it (3: id, parked at pos/rot).
     */
    public record ShipDo(int kind, int hand, String id, double px, double py, double pz, double qx, double qy, double qz, double qw,
            String stage) implements CustomPacketPayload {
        public static final int PLACE_SHIP = 0, PLACE_PAD = 1, BOARD = 2, LEAVE = 3, CARGO = 4, SIT = 5, STAND = 6;
        public static final Type<ShipDo> TYPE = typeOf("mp_ship_do");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipDo> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeByte(p.kind).writeByte(p.hand).writeUtf(p.id, 96);
                    b.writeDouble(p.px).writeDouble(p.py).writeDouble(p.pz);
                    b.writeDouble(p.qx).writeDouble(p.qy).writeDouble(p.qz).writeDouble(p.qw);
                    b.writeUtf(p.stage, 96);
                },
                b -> new ShipDo(b.readByte(), b.readByte(), b.readUtf(96), b.readDouble(), b.readDouble(), b.readDouble(),
                        b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readUtf(96)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: the bays of a ship's hold (ShipCargo.layout), so the chests are drawn; the items stay with the host. */
    public record ShipBays(String id, byte[] bays) implements CustomPacketPayload {
        public static final Type<ShipBays> TYPE = typeOf("mp_ship_bays");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipBays> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUtf(p.id, 96).writeByteArray(p.bays), b -> new ShipBays(b.readUtf(96), b.readByteArray(16)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: this passenger seat (1 and up) of a ship has this player (a UUID text), or nobody (empty). Seat 0 with nobody: refused. */
    public record ShipSeat(String id, int seat, String who) implements CustomPacketPayload {
        public static final Type<ShipSeat> TYPE = typeOf("mp_ship_seat");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipSeat> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUtf(p.id, 96).writeByte(p.seat).writeUtf(p.who, 40),
                b -> new ShipSeat(b.readUtf(96), b.readByte(), b.readUtf(40)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: this ship's trip on the map (a TripCodec), or none (empty): the host's sends and cancels. */
    public record ShipTrip(String id, byte[] data) implements CustomPacketPayload {
        public static final Type<ShipTrip> TYPE = typeOf("mp_ship_trip");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipTrip> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeUtf(p.id, 96).writeByteArray(p.data),
                b -> new ShipTrip(b.readUtf(96), b.readByteArray(TripCodec.SIZE)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Guest -> server: send a ship to the planet at (center, radius in blocks) from the map, or (cancel) stop its trip.
     */
    public record TripDo(String id, boolean cancel, long cx, long cy, long cz, double x, double y, double z, double radius)
            implements CustomPacketPayload {
        public static final Type<TripDo> TYPE = typeOf("mp_trip_do");
        public static final StreamCodec<RegistryFriendlyByteBuf, TripDo> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeUtf(p.id, 96).writeBoolean(p.cancel);
                    b.writeLong(p.cx).writeLong(p.cy).writeLong(p.cz);
                    b.writeDouble(p.x).writeDouble(p.y).writeDouble(p.z).writeDouble(p.radius);
                },
                b -> new TripDo(b.readUtf(96), b.readBoolean(), b.readLong(), b.readLong(), b.readLong(), b.readDouble(),
                        b.readDouble(), b.readDouble(), b.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Where a flown ship is, ten times a second: the pilot -> server (checked), server -> the others. */
    public record ShipPose(String id, double px, double py, double pz, double qx, double qy, double qz, double qw)
            implements CustomPacketPayload {
        public static final Type<ShipPose> TYPE = typeOf("mp_ship_pose");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShipPose> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeUtf(p.id, 96);
                    b.writeDouble(p.px).writeDouble(p.py).writeDouble(p.pz);
                    b.writeDouble(p.qx).writeDouble(p.qy).writeDouble(p.qz).writeDouble(p.qw);
                },
                b -> new ShipPose(b.readUtf(96), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(),
                        b.readDouble(), b.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Guest -> server: the movement keys held while riding a minecart or boat (bits: 1 forward, 2 back, 4 left, 8 right,
     * 16 sneak = get off). Sent when they change and five times a second while any is held; the host counts a silence
     * of half a second as released.
     */
    public record Steer(int keys) implements CustomPacketPayload {
        public static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, SNEAK = 16, ALL = 31;
        public static final Type<Steer> TYPE = typeOf("mp_steer");
        public static final StreamCodec<RegistryFriendlyByteBuf, Steer> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByte(p.keys), b -> new Steer(b.readByte() & ALL));

        public static int pack(boolean forward, boolean back, boolean left, boolean right, boolean sneak) {
            return (forward ? FORWARD : 0) | (back ? BACK : 0) | (left ? LEFT : 0) | (right ? RIGHT : 0) | (sneak ? SNEAK : 0);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: where the vehicle the guest rides seats it (planet space of the planet with this catalog index), about ten times a second. */
    public record RideSeat(int planet, float x, float y, float z) implements CustomPacketPayload {
        public static final Type<RideSeat> TYPE = typeOf("mp_ride_seat");
        public static final StreamCodec<RegistryFriendlyByteBuf, RideSeat> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.planet);
                    b.writeFloat(p.x);
                    b.writeFloat(p.y);
                    b.writeFloat(p.z);
                }, b -> new RideSeat(b.readVarInt(), b.readFloat(), b.readFloat(), b.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> guest: the guest is no longer on its vehicle (it got off, the vehicle went, the planet closed). */
    public record RideEnd() implements CustomPacketPayload {
        public static final Type<RideEnd> TYPE = typeOf("mp_ride_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, RideEnd> CODEC = CustomPacketPayload.codec(
                (p, b) -> b.writeByte(0), b -> {
                    b.readByte();
                    return new RideEnd();
                });

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
        PayloadTypeRegistry.clientboundPlay().register(Live.TYPE, Live.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MobHit.TYPE, MobHit.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Drops.TYPE, Drops.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DropTake.TYPE, DropTake.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Break.TYPE, Break.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Where.TYPE, Where.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Throw.TYPE, Throw.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StationUpdate.TYPE, StationUpdate.CODEC);
        registerStars();
        registerShips();
        registerRiding();
    }

    private static void registerStars() {
        PayloadTypeRegistry.clientboundPlay().register(StarList.TYPE, StarList.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StarPut.TYPE, StarPut.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StarGone.TYPE, StarGone.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StarDo.TYPE, StarDo.CODEC);
    }

    private static void registerShips() {
        PayloadTypeRegistry.clientboundPlay().register(ShipList.TYPE, ShipList.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipPut.TYPE, ShipPut.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipPilot.TYPE, ShipPilot.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipPose.TYPE, ShipPose.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipBays.TYPE, ShipBays.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipSeat.TYPE, ShipSeat.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ShipTrip.TYPE, ShipTrip.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TripDo.TYPE, TripDo.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShipDo.TYPE, ShipDo.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ShipPose.TYPE, ShipPose.CODEC);
    }

    private static void registerRiding() {
        PayloadTypeRegistry.serverboundPlay().register(Steer.TYPE, Steer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RideSeat.TYPE, RideSeat.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RideEnd.TYPE, RideEnd.CODEC);
    }
}
