package dev.moui.galaxycraft.net;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.GalaxySave;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Fabric glue: Relay's players are the server's. Payload handlers and events run on the server thread,
 * so Relay needs no locking. Nothing happens until a player joins; in plain single player the host is
 * the only peer and nothing is ever relayed.
 */
public final class MpServer {
    /** A guest has this long (server ticks, 5 s) to answer Hello. */
    static final int ACK_TICKS = 100;

    private static volatile MinecraftServer server;
    private static Relay relay;
    /** Guests that were sent a Hello: the server tick it was sent on. */
    private static final Map<UUID, Integer> waiting = new HashMap<>();
    /** Each player's latest custom skin, for those who join later; and how often they may change it. */
    private static final Map<UUID, byte[]> skins = new HashMap<>();
    private static final Map<UUID, RateLimiter> skinLimits = new HashMap<>();

    /** Who makes a planet's bytes for a guest: the host's game (it owns the planets). Called on the server thread. */
    public interface PlanetSource {
        void ask(UUID guest, int index, long guestEtag);
    }

    /** The host's game, which owns the planets: applies a guest's edits, hears when a guest lets a planet go. Called on the server thread. */
    public interface EditSink {
        void edits(UUID guest, EditCodec.Msg m);

        void dropped(UUID guest, int planet);

        /** A guest right clicked a block (already checked: a planet it holds, a sane cell and hit). Called on the server thread. */
        void use(UUID guest, MpPayloads.Use u);

        /** A guest hit or used a mob of the shadow (already checked: holds the planet, sane numbers). Called on the server thread. */
        void mobHit(UUID guest, MpPayloads.MobHit h);

        /** A guest stepped on a drop. */
        void takeDrop(UUID guest, MpPayloads.DropTake t);

        /** A guest breaks a block (already checked: a planet it holds, a cell number). */
        void breakBlock(UUID guest, MpPayloads.Break b);

        /** A guest throws what it holds onto a planet (checked: holds it, finite numbers). */
        void throwItem(UUID guest, MpPayloads.Throw t);

        /** A creative guest throws a stack it made (checked: finite numbers, a planet it holds, a sane stack; the host still checks creative). */
        void throwStack(UUID guest, MpPayloads.ThrowStack t);

        /** Where a guest is on a planet (already checked: finite numbers, a planet it holds or none). */
        void where(UUID guest, MpPayloads.Where w);

        /** The keys a guest holds while it rides (checked: a member, rate limited). */
        default void steer(UUID guest, int keys) {}
    }

    private static volatile PlanetSource planetSource;
    private static volatile EditSink editSink;
    /** Who holds which planet, and what waits to be sent them about it. */
    private static EditHub<UUID> hub = newHub();
    private static final Map<UUID, RateLimiter> editLimits = new HashMap<>();
    private static final Map<UUID, RateLimiter> editBytes = new HashMap<>();
    private static final Map<UUID, Integer> editBad = new HashMap<>();
    /** What a guest's planet edits may take of each tick's send budget; the planet chunks get the rest. */
    static final int EDIT_BUDGET = 16 * 1024;
    private static final int EDIT_BAD_KICK = 5;
    /** Guests that entered the galaxy: only they may ask for planets. */
    private static final java.util.Set<UUID> members = new java.util.HashSet<>();
    private static final Map<UUID, RateLimiter> askLimits = new HashMap<>();
    /** Planets asked for and not yet handed to the queue, per guest. */
    private static final Map<UUID, Integer> outstanding = new HashMap<>();
    /** What each guest is being sent: a little each tick. */
    private static final Map<UUID, PlanetXfer.Outbox<net.minecraft.network.protocol.common.custom.CustomPacketPayload>> outboxes = new HashMap<>();

    private MpServer() {}

    private static EditHub<UUID> newHub() {
        return new EditHub<>((guest, planet) -> {
            ServerPlayer p = player(guest);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Resync(planet));
            EditSink sink = editSink;
            if (sink != null) sink.dropped(guest, planet);
        });
    }

    public static void setPlanetSource(PlanetSource source) {
        planetSource = source;
    }

    public static void setEditSink(EditSink sink) {
        editSink = sink;
    }

    private static volatile java.util.function.Consumer<UUID> joinListener;

    /** Called (on the server thread) when a guest has entered the galaxy: the host's game tells it what it keeps for guests (its stations). */
    public static void setJoinListener(java.util.function.Consumer<UUID> listener) {
        joinListener = listener;
    }

    /** From any thread: the host's stations (a StationList body) to one guest, or to every guest that entered the galaxy (null). */
    public static void sendStations(UUID guest, byte[] data) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            for (UUID id : guest != null ? java.util.List.of(guest) : new java.util.ArrayList<>(members)) {
                ServerPlayer p = player(id);
                if (p != null && members.contains(id)) ServerPlayNetworking.send(p, new MpPayloads.StationUpdate(data));
            }
        });
    }

    /** From any thread: the guest's copy of that planet is no good any more (a station grew a new grid): it is cut off and asks again. */
    public static void resync(UUID guest, int planet) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            hub.cancel(guest, planet);
            ServerPlayer p = player(guest);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Resync(planet));
        });
    }

    /** The host's game made a change to a planet guests may hold, from any thread: it goes to those that hold it. author: the guest whose edit it was (null: not a guest's). */
    public static void publish(EditCodec.Msg m, UUID author) {
        MinecraftServer srv = server;
        if (srv != null) srv.execute(() -> hub.publish(m, author));
    }

    /** The host's answer to one guest's edit that it could not take as it was: the cells as they are. */
    public static void correct(UUID guest, EditCodec.Msg m) {
        MinecraftServer srv = server;
        if (srv != null) srv.execute(() -> hub.publishTo(guest, m));
    }

    /**
     * The host's answer to a guest's ask, from any thread: its planet (null: it could not be made).
     * Same etag as the guest's copy: just says so; else the bytes are queued, sent a little each tick.
     */
    public static void offer(UUID guest, int index, long guestEtag, PlanetXfer.Offer offer) {
        MinecraftServer srv = server;
        if (srv != null) srv.execute(() -> deliver(guest, index, guestEtag, offer));
    }

    private static void deliver(UUID guest, int index, long guestEtag, PlanetXfer.Offer offer) {
        outstanding.computeIfPresent(guest, (k, n) -> Math.max(0, n - 1));
        ServerPlayer p = player(guest);
        if (p == null || !members.contains(guest)) return;
        var box = outboxes.computeIfAbsent(guest, k -> new PlanetXfer.Outbox<>());
        if (offer == null || offer.data().length == 0 || offer.data().length > PlanetXfer.MAX_BYTES) {
            cancelPlanet(guest, index);
            box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_FAIL, 0, 0, 0), 32, index);
            return;
        }
        if (offer.etag() == guestEtag) {
            box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_SAME, offer.etag(), 0, offer.seq()), 32, index);
            hub.deliver(guest, index, offer.seq());
            return;
        }
        byte[] data = offer.data();
        box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_DATA, offer.etag(), data.length, offer.seq()), 32, index);
        for (int seq = 0; seq < PlanetXfer.chunks(data.length); seq++)
            box.add(new MpPayloads.PlanetChunk(index, seq, PlanetXfer.slice(data, seq)), Math.min(PlanetXfer.CHUNK, data.length - seq * PlanetXfer.CHUNK), index);
        hub.deliver(guest, index, offer.seq());
        GalaxyCraft.LOG.info("[GalaxyCraft mp] planet {} ({} KB) queued for {}", index, data.length / 1024, p.getName().getString());
    }

    /** The guest will not hold that planet after all: no more edits for it, and the host's game may let it go. */
    private static void cancelPlanet(UUID guest, int index) {
        hub.cancel(guest, index);
        EditSink sink = editSink;
        if (sink != null) sink.dropped(guest, index);
    }

    private static void ask(ServerPlayer p, MpPayloads.PlanetAsk ask) {
        UUID id = p.getUUID();
        PlanetSource source = planetSource;
        if (source == null || !members.contains(id) || ask.index() < 0) return;
        if (!askLimits.computeIfAbsent(id, k -> new RateLimiter(10, 20)).allow(System.nanoTime())) return;
        if (outstanding.getOrDefault(id, 0) >= PlanetXfer.MAX_OUTSTANDING) {
            hub.cancel(id, ask.index());
            outboxes.computeIfAbsent(id, k -> new PlanetXfer.Outbox<>()).add(new MpPayloads.PlanetHead(ask.index(), PlanetXfer.STATE_FAIL, 0, 0, 0), 32, ask.index());
            return;
        }
        outstanding.merge(id, 1, Integer::sum);
        hub.subscribe(id, ask.index()); // edits from now on wait for the planet's bytes
        source.ask(id, ask.index(), ask.etag());
    }

    /** The planet each guest says it stands on: its bytes are sent first. */
    private static final Map<UUID, Integer> standingOn = new HashMap<>();

    /** How many more bytes the connection to that player takes before it is backed up (a slow link): unlimited on a local one. */
    private static long writable(ServerPlayer p) {
        io.netty.channel.Channel ch = ((dev.moui.galaxycraft.mixin.ConnectionAccess) ((dev.moui.galaxycraft.mixin.ServerCommonAccess) p.connection).galaxycraft$connection()).galaxycraft$channel();
        if (ch == null) return Long.MAX_VALUE;
        return ch.isWritable() ? ch.bytesBeforeUnwritable() : 0;
    }

    private static void pump(MinecraftServer srv) {
        for (var e : outboxes.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            ServerPlayer p = player(e.getKey());
            if (p != null) e.getValue().drain(PlanetXfer.sendBudget(PlanetXfer.BUDGET_PER_TICK - EDIT_BUDGET, writable(p)),
                    payload -> ServerPlayNetworking.send(p, payload), standingOn.getOrDefault(e.getKey(), PlanetXfer.Outbox.NO_LANE));
            else e.getValue().clear();
        }
        for (ServerPlayer p : srv.getPlayerList().getPlayers())
            if (members.contains(p.getUUID()))
                hub.drain(p.getUUID(), EDIT_BUDGET, m -> ServerPlayNetworking.send(p, new MpPayloads.Edits(EditCodec.encode(m))));
    }

    private static void edits(ServerPlayer p, MpPayloads.Edits e) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id)) return;
        if (!editLimits.computeIfAbsent(id, k -> new RateLimiter(20, 40)).allow(System.nanoTime())) return;
        var bytes = editBytes.computeIfAbsent(id, k -> new RateLimiter(64, 128));
        for (int kb = 0; kb < Math.max(1, e.data().length / 1024); kb++)
            if (!bytes.allow(System.nanoTime())) return;
        EditCodec.Msg m;
        try {
            m = EditCodec.decode(e.data());
        } catch (EditCodec.Bad bad) {
            if (editBad.merge(id, 1, Integer::sum) >= EDIT_BAD_KICK) SINK.kick(id, "Too many malformed multiplayer packets.");
            return;
        }
        editBad.remove(id);
        if (hub.subscribed(id, m.planet())) sink.edits(id, m);
    }

    private static final Map<UUID, RateLimiter> useLimits = new HashMap<>();

    private static void use(ServerPlayer p, MpPayloads.Use u) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id)) return;
        if (!useLimits.computeIfAbsent(id, k -> new RateLimiter(20, 10)).allow(System.nanoTime())) {
            ServerPlayNetworking.send(p, new MpPayloads.UseDone(u.nonce(), true)); // too fast: the guest places as before
            return;
        }
        if (!hub.subscribed(id, u.planet()) || u.cell() < 0 || u.face() < 0 || u.face() > 5 || (u.hand() != 0 && u.hand() != 1)
                || !UseCheck.valid(u.cell(), Integer.MAX_VALUE, u.face(), u.hand(), u.hx(), u.hy(), u.hz())) {
            ServerPlayNetworking.send(p, new MpPayloads.UseDone(u.nonce(), true));
            return;
        }
        sink.use(id, u);
    }

    /** The host's game answers a guest's block use, from any thread. */
    public static void useDone(UUID guest, int nonce, boolean passed) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = player(guest);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.UseDone(nonce, passed));
        });
    }

    /** The host's game sends a guest the shadow's entities near it (a MobsCodec body), from any thread. */
    public static void mobs(UUID guest, byte[] body) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Mobs(body));
        });
    }

    /** The host's game tells a guest what changed among the live blocks near it (a LiveCodec body), from any thread. */
    public static void live(UUID guest, byte[] body) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Live(body));
        });
    }

    private static final Map<UUID, RateLimiter> hitLimits = new HashMap<>();

    private static void mobHit(ServerPlayer p, MpPayloads.MobHit h) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id) || !hub.subscribed(id, h.planet()) || (h.kind() != 0 && h.kind() != 1)
                || !Float.isFinite(h.lx()) || !Float.isFinite(h.ly()) || !Float.isFinite(h.lz())
                || !hitLimits.computeIfAbsent(id, k -> new RateLimiter(20, 10)).allow(System.nanoTime()))
            return;
        sink.mobHit(id, h);
    }

    /** The host's game sends a guest the dropped items of the planet it runs, from any thread. */
    public static void drops(UUID guest, MpPayloads.Drops body) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, body);
        });
    }

    private static final Map<UUID, RateLimiter> steerLimits = new HashMap<>();

    private static void steer(ServerPlayer p, MpPayloads.Steer s) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id) || !steerLimits.computeIfAbsent(id, k -> new RateLimiter(20, 20)).allow(System.nanoTime())) return;
        sink.steer(id, s.keys());
    }

    /** The host's game says where a guest's vehicle seats it, from any thread. */
    public static void rideSeat(UUID guest, MpPayloads.RideSeat body) {
        sendTo(guest, body);
    }

    private static void sendTo(UUID guest, net.minecraft.network.protocol.common.custom.CustomPacketPayload body) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, body);
        });
    }

    /** The host's game says a guest is off its vehicle, from any thread. */
    public static void rideEnd(UUID guest) {
        sendTo(guest, new MpPayloads.RideEnd());
    }

    private static final Map<UUID, RateLimiter> takeLimits = new HashMap<>();
    private static final Map<UUID, RateLimiter> breakLimits = new HashMap<>();

    private static void takeDrop(ServerPlayer p, MpPayloads.DropTake t) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id) || !hub.subscribed(id, t.planet())
                || !takeLimits.computeIfAbsent(id, k -> new RateLimiter(60, 30)).allow(System.nanoTime()))
            return;
        sink.takeDrop(id, t);
    }

    private static final Map<UUID, RateLimiter> whereLimits = new HashMap<>();

    private static void where(ServerPlayer p, MpPayloads.Where w) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        float[] v = {w.fx(), w.fy(), w.fz(), w.lx(), w.ly(), w.lz()};
        for (float f : v) if (!Float.isFinite(f) || Math.abs(f) > 1e6f) return;
        if (sink == null || !members.contains(id) || w.planet() < -1 || w.planet() > 4096 && !StationSlots.isStation(w.planet())
                || w.planet() >= 0 && !hub.subscribed(id, w.planet())
                || !whereLimits.computeIfAbsent(id, k -> new RateLimiter(30, 30)).allow(System.nanoTime()))
            return;
        standingOn.put(id, w.planet());
        sink.where(id, w);
    }

    private static final Map<UUID, RateLimiter> throwLimits = new HashMap<>();

    private static void throwItem(ServerPlayer p, MpPayloads.Throw t) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        float[] v = {t.x(), t.y(), t.z(), t.vx(), t.vy(), t.vz()};
        for (float f : v) if (!Float.isFinite(f) || Math.abs(f) > 1e6f) return;
        if (t.container() < -1 || t.slot() < -1 || t.slot() > 255 || t.container() == -1 && t.slot() != -1) return;
        if (Math.abs(t.vx()) > 5 || Math.abs(t.vy()) > 5 || Math.abs(t.vz()) > 5) return;
        if (sink == null || !members.contains(id) || !hub.subscribed(id, t.planet())
                || !throwLimits.computeIfAbsent(id, k -> new RateLimiter(25, 25)).allow(System.nanoTime()))
            return;
        sink.throwItem(id, t);
    }

    private static void throwStack(ServerPlayer p, MpPayloads.ThrowStack t) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        float[] v = {t.x(), t.y(), t.z(), t.vx(), t.vy(), t.vz()};
        for (float f : v) if (!Float.isFinite(f) || Math.abs(f) > 1e6f) return;
        if (Math.abs(t.vx()) > 5 || Math.abs(t.vy()) > 5 || Math.abs(t.vz()) > 5) return;
        if (t.stack().isEmpty() || t.stack().getCount() > t.stack().getMaxStackSize()) return;
        if (sink == null || !members.contains(id) || !hub.subscribed(id, t.planet())
                || !throwLimits.computeIfAbsent(id, k -> new RateLimiter(25, 25)).allow(System.nanoTime()))
            return;
        sink.throwStack(id, t);
    }

    private static void breakBlock(ServerPlayer p, MpPayloads.Break b) {
        UUID id = p.getUUID();
        EditSink sink = editSink;
        if (sink == null || !members.contains(id)) return;
        if (!breakLimits.computeIfAbsent(id, k -> new RateLimiter(40, 20)).allow(System.nanoTime()) || !hub.subscribed(id, b.planet())
                || b.cell() < 0) {
            ServerPlayNetworking.send(p, new MpPayloads.UseDone(b.nonce(), true)); // break it yourself
            return;
        }
        sink.breakBlock(id, b);
    }

    private static final Map<UUID, RateLimiter> usedUpLimits = new HashMap<>();

    private static void usedUp(ServerPlayer p, MpPayloads.UsedUp u) {
        UUID id = p.getUUID();
        if (!members.contains(id) || (u.hand() != 0 && u.hand() != 1)) return;
        if (!usedUpLimits.computeIfAbsent(id, k -> new RateLimiter(20, 10)).allow(System.nanoTime())) return;
        var hand = u.hand() == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
        var stack = p.getItemInHand(hand);
        if (!p.hasInfiniteMaterials() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) stack.shrink(1);
    }

    // ---- Launch Stars: the host's game owns the StarStore; this is the wire around it ----

    /** The host's game, which owns the stars. Called on the server thread; it must hop to the client thread itself. */
    public interface StarSink {
        /** A guest asks to place, pick up or rename a star (checked: member, rate, bounded numbers; a placing already used its item up). */
        void request(UUID guest, dev.moui.galaxycraft.launchstar.StarOps.Request r);

        /** A guest entered the galaxy: it needs the whole list (answer with {@link #starList}). */
        void joined(UUID guest);
    }

    private static volatile StarSink starSink;
    private static final Map<UUID, RateLimiter> starLimits = new HashMap<>();

    public static void setStarSink(StarSink sink) {
        starSink = sink;
    }

    private static void starDo(ServerPlayer p, MpPayloads.StarDo d) {
        UUID id = p.getUUID();
        StarSink sink = starSink;
        if (sink == null || !members.contains(id)) return;
        if (!starLimits.computeIfAbsent(id, k -> new RateLimiter(5, 10)).allow(System.nanoTime())) return;
        double[] v = {d.px(), d.py(), d.pz(), d.ux(), d.uy(), d.uz()};
        for (double x : v) if (!Double.isFinite(x)) return;
        if (d.kind() < 0 || d.kind() > 2 || (d.hand() != 0 && d.hand() != 1)) return;
        if (d.kind() == dev.moui.galaxycraft.launchstar.StarOps.PLACE) { // the host checks the item and uses it up
            var hand = d.hand() == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
            var stack = p.getItemInHand(hand);
            if (dev.moui.galaxycraft.launchstar.LaunchStarBlocks.LAUNCH_STAR == null
                    || !stack.is(dev.moui.galaxycraft.launchstar.LaunchStarBlocks.LAUNCH_STAR))
                return;
            if (!p.hasInfiniteMaterials()) stack.shrink(1);
        }
        sink.request(id, new dev.moui.galaxycraft.launchstar.StarOps.Request(d.kind(), d.id(), new double[] {d.px(), d.py(), d.pz()},
                new double[] {d.ux(), d.uy(), d.uz()}, d.stage(), d.body(), d.code()));
    }

    /** One star to every guest that entered (placed or renamed), from any thread. */
    public static void starPut(dev.moui.galaxycraft.launchstar.StarRecord r) {
        MinecraftServer srv = server;
        if (srv == null) return;
        byte[] data = StarCodec.encode(r);
        srv.execute(() -> {
            for (UUID g : members) {
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, new MpPayloads.StarPut(data));
            }
        });
    }

    /** A star is gone for every guest that entered, from any thread. */
    public static void starGone(String id) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            for (UUID g : members) {
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, new MpPayloads.StarGone(id));
            }
        });
    }

    /** All the stars to one guest, from any thread. */
    public static void starList(UUID guest, java.util.List<dev.moui.galaxycraft.launchstar.StarRecord> all) {
        MinecraftServer srv = server;
        if (srv == null) return;
        byte[] data = StarCodec.encodeList(all);
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.StarList(data));
        });
    }

    /** A Launch Star item to a guest (a pickup, or a placing the host refused); none in creative. From any thread. */
    public static void starGive(UUID guest) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = player(guest);
            if (p != null && !p.hasInfiniteMaterials() && dev.moui.galaxycraft.launchstar.LaunchStarBlocks.LAUNCH_STAR != null)
                {
                var star = new net.minecraft.world.item.ItemStack(dev.moui.galaxycraft.launchstar.LaunchStarBlocks.LAUNCH_STAR);
                if (!p.getInventory().add(star)) p.spawnAtLocation((net.minecraft.server.level.ServerLevel) p.level(), star); // a full inventory drops it at the feet
            }
        });
    }


    // ---- Toads: the host's game owns the store; a guest talks and trades through these ----

    private static final Map<UUID, RateLimiter> toadLimits = new HashMap<>();

    /** The guests that entered (a copy), for the host's game to send each its Toads. */
    public static java.util.List<UUID> memberList() {
        return new java.util.ArrayList<>(members);
    }

    /** Toad changes to one guest, from any thread. */
    public static void toadSync(UUID guest, byte[] data) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.ToadSync(data));
        });
    }

    private static void toadDo(ServerPlayer p, MpPayloads.ToadDo d) {
        UUID id = p.getUUID();
        if (!members.contains(id)) return;
        if (!toadLimits.computeIfAbsent(id, k -> new RateLimiter(5, 10)).allow(System.nanoTime())) return;
        switch (d.kind()) {
            case MpPayloads.ToadDo.STAND -> dev.moui.galaxycraft.shadow.ShadowWorld.standToad(d.id(), true);
            case MpPayloads.ToadDo.FREE -> dev.moui.galaxycraft.shadow.ShadowWorld.standToad(d.id(), false);
            case MpPayloads.ToadDo.TRADE -> {
                dev.moui.galaxycraft.toad.ToadType type = null;
                for (dev.moui.galaxycraft.toad.ToadType t : dev.moui.galaxycraft.toad.ToadType.values()) if (t.id().equals(d.toadType())) type = t;
                var merchant = type == null ? null : dev.moui.galaxycraft.toad.ToadMerchant.of(type, Math.clamp(d.tier(), 0, 5));
                if (merchant == null) return;
                merchant.setTradingPlayer(p);
                merchant.openTradingScreen(p, dev.moui.galaxycraft.toad.ToadMerchant.title(type), 1);
            }
            default -> { }
        }
    }

    // ---- Ships: the host's game owns the ShipStore and who flies what; this is the wire around it ----

    /** The host's game, which owns the ships. Called on the server thread; it must hop to the client thread itself. */
    public interface ShipSink {
        /** A guest asks to put a ship or pad down (checked: member, rate, the item, bounded numbers; the item is already used up). */
        void place(UUID guest, dev.moui.galaxycraft.ship.ShipOps.Request r);

        /** A guest asks to board (kind BOARD) or leaves (kind LEAVE, parked at the pose). */
        void board(UUID guest, String shipId);

        void leave(UUID guest, String shipId, double[] pos, double[] rot);

        /** The pilot says where the ship is. */
        void pose(UUID guest, String shipId, double[] pos, double[] rot);

        /** A guest sends a ship to a planet from the map (or cancels its trip). */
        void trip(UUID guest, String shipId, boolean cancel, dev.moui.galaxycraft.universe.UPos center, double radiusBlocks);

        /** A guest wants a passenger seat in a flown ship. */
        void sit(UUID guest, String shipId);

        /** A guest gets up from its passenger seat. */
        void stand(UUID guest, String shipId);

        /** A guest wants to open a ship's hold and parts screen. */
        void cargo(UUID guest, String shipId);

        /** A guest entered the galaxy: it needs every ship and who flies them. */
        void joined(UUID guest);

        /** A guest left the game: whatever it flew is parked where it was. */
        void left(UUID guest);
    }

    private static volatile ShipSink shipSink;
    private static final Map<UUID, RateLimiter> shipLimits = new HashMap<>(), shipPoseLimits = new HashMap<>();

    public static void setShipSink(ShipSink sink) {
        shipSink = sink;
    }

    private static boolean sound(double[] v) {
        for (double x : v) if (!Double.isFinite(x) || Math.abs(x) > dev.moui.galaxycraft.ship.ShipOps.MAX_COORD) return false;
        return true;
    }

    private static void shipDo(ServerPlayer p, MpPayloads.ShipDo d) {
        UUID id = p.getUUID();
        ShipSink sink = shipSink;
        if (sink == null || !members.contains(id)) return;
        if (!shipLimits.computeIfAbsent(id, k -> new RateLimiter(5, 10)).allow(System.nanoTime())) return;
        double[] pos = {d.px(), d.py(), d.pz()}, rot = {d.qx(), d.qy(), d.qz(), d.qw()};
        if (!sound(pos) || !sound(rot) || d.id().length() > 96) return;
        switch (d.kind()) {
            case MpPayloads.ShipDo.PLACE_SHIP, MpPayloads.ShipDo.PLACE_PAD -> {
                if (d.hand() != 0 && d.hand() != 1) return;
                var stack = p.getItemInHand(d.hand() == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND
                        : net.minecraft.world.InteractionHand.OFF_HAND);
                boolean ship = d.kind() == MpPayloads.ShipDo.PLACE_SHIP;
                dev.moui.galaxycraft.ship.ShipModules mods = null;
                if (ship) {
                    mods = dev.moui.galaxycraft.ship.ToadShipItem.modules(stack).orElse(null);
                    if (mods == null) return;
                } else if (dev.moui.galaxycraft.ship.ShipBlocks.LANDING_PAD == null
                        || !stack.is(dev.moui.galaxycraft.ship.ShipBlocks.LANDING_PAD))
                    return;
                if (!p.hasInfiniteMaterials()) stack.shrink(1);
                sink.place(id, new dev.moui.galaxycraft.ship.ShipOps.Request(ship ? dev.moui.galaxycraft.ship.ShipOps.PLACE_SHIP
                        : dev.moui.galaxycraft.ship.ShipOps.PLACE_PAD, pos, rot, d.stage(), mods));
            }
            case MpPayloads.ShipDo.BOARD -> sink.board(id, d.id());
            case MpPayloads.ShipDo.LEAVE -> sink.leave(id, d.id(), pos, rot);
            case MpPayloads.ShipDo.CARGO -> sink.cargo(id, d.id());
            case MpPayloads.ShipDo.SIT -> sink.sit(id, d.id());
            case MpPayloads.ShipDo.STAND -> sink.stand(id, d.id());
            default -> {}
        }
    }

    private static final Map<UUID, RateLimiter> tripLimits = new HashMap<>();

    private static void tripDo(ServerPlayer p, MpPayloads.TripDo d) {
        UUID id = p.getUUID();
        ShipSink sink = shipSink;
        if (sink == null || !members.contains(id) || d.id().length() > 96) return;
        if (!tripLimits.computeIfAbsent(id, k -> new RateLimiter(2, 4)).allow(System.nanoTime())) return;
        double[] v = {d.x(), d.y(), d.z(), d.radius()};
        for (double x : v) if (!Double.isFinite(x) || Math.abs(x) > 1e18) return;
        if (!d.cancel() && (d.radius() <= 0 || d.radius() > 1e9)) return;
        sink.trip(id, d.id(), d.cancel(), new dev.moui.galaxycraft.universe.UPos(d.cx(), d.cy(), d.cz(), d.x(), d.y(), d.z()), d.radius());
    }

    /** A ship's trip (null: none) to every guest, or to one (to), from any thread. */
    public static void shipTrip(String shipId, dev.moui.galaxycraft.map.Trip t, UUID to) {
        MinecraftServer srv = server;
        if (srv == null) return;
        var m = new MpPayloads.ShipTrip(shipId, t == null ? new byte[0] : TripCodec.encode(t));
        srv.execute(() -> {
            for (UUID g : members) {
                if (to != null && !to.equals(g)) continue;
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, m);
            }
        });
    }

    private static void shipPose(ServerPlayer p, MpPayloads.ShipPose m) {
        UUID id = p.getUUID();
        ShipSink sink = shipSink;
        if (sink == null || !members.contains(id) || m.id().length() > 96) return;
        if (!shipPoseLimits.computeIfAbsent(id, k -> new RateLimiter(20, 40)).allow(System.nanoTime())) return;
        double[] pos = {m.px(), m.py(), m.pz()}, rot = {m.qx(), m.qy(), m.qz(), m.qw()};
        if (!sound(pos) || !sound(rot)) return;
        sink.pose(id, m.id(), pos, rot);
    }

    /** A ship or pad to every guest that entered (put down, or parked somewhere new), from any thread. */
    public static void shipPut(dev.moui.galaxycraft.ship.ShipRecord r) {
        MinecraftServer srv = server;
        if (srv == null) return;
        byte[] data = ShipCodec.encode(r);
        srv.execute(() -> {
            for (UUID g : members) {
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, new MpPayloads.ShipPut(data));
            }
        });
    }

    /** Who flies this ship now (empty: nobody), to every guest that entered, from any thread. */
    public static void shipPilot(String shipId, String who) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            for (UUID g : members) {
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, new MpPayloads.ShipPilot(shipId, who));
            }
        });
    }

    /** Who flies this ship, to one guest, from any thread. */
    public static void shipPilotTo(UUID guest, String shipId, String who) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.ShipPilot(shipId, who));
        });
    }

    /** A flown ship's pose to every guest but one (the pilot, who made it; null: all), from any thread. */
    public static void shipPoseOut(String shipId, double[] pos, double[] rot, UUID except) {
        MinecraftServer srv = server;
        if (srv == null) return;
        var m = new MpPayloads.ShipPose(shipId, pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3]);
        srv.execute(() -> {
            for (UUID g : members) {
                if (g.equals(except)) continue;
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, m);
            }
        });
    }

    /** A ship's bays to every guest (to == null) or to one, from any thread. */
    public static void shipBays(String shipId, byte[] bays, UUID to) {
        MinecraftServer srv = server;
        if (srv == null) return;
        var m = new MpPayloads.ShipBays(shipId, bays);
        srv.execute(() -> {
            for (UUID g : members) {
                if (to != null && !to.equals(g)) continue;
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, m);
            }
        });
    }

    /** A passenger seat's holder to every guest (to == null) or to one, from any thread. */
    public static void shipSeat(String shipId, int seat, String who, UUID to) {
        MinecraftServer srv = server;
        if (srv == null) return;
        var m = new MpPayloads.ShipSeat(shipId, seat, who);
        srv.execute(() -> {
            for (UUID g : members) {
                if (to != null && !to.equals(g)) continue;
                ServerPlayer p = player(g);
                if (p != null) ServerPlayNetworking.send(p, m);
            }
        });
    }

    /** All the ships to one guest, from any thread. */
    public static void shipList(UUID guest, java.util.List<dev.moui.galaxycraft.ship.ShipRecord> all) {
        MinecraftServer srv = server;
        if (srv == null) return;
        byte[] data = ShipCodec.encodeList(all);
        srv.execute(() -> {
            ServerPlayer p = members.contains(guest) ? player(guest) : null;
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.ShipList(data));
        });
    }

    /** The ship item (or pad) of a placing the host refused, back to the guest; none in creative. From any thread. */
    public static void shipGive(UUID guest, dev.moui.galaxycraft.ship.ShipOps.Request r) {
        MinecraftServer srv = server;
        if (srv == null) return;
        srv.execute(() -> {
            ServerPlayer p = player(guest);
            if (p == null || p.hasInfiniteMaterials()) return;
            if (r.kind() == dev.moui.galaxycraft.ship.ShipOps.PLACE_SHIP && r.modules() != null)
                p.getInventory().add(dev.moui.galaxycraft.ship.ToadShipItem.stack(r.modules()));
            else if (dev.moui.galaxycraft.ship.ShipBlocks.LANDING_PAD != null)
                p.getInventory().add(new net.minecraft.world.item.ItemStack(dev.moui.galaxycraft.ship.ShipBlocks.LANDING_PAD));
        });
    }

    private static final Map<UUID, InboundGuard> inbound = new HashMap<>();

    /** One guest message allowed in, over all message types: a flood is dropped, and a guest that keeps at it is kicked. */
    private static boolean admit(UUID id) {
        InboundGuard.Verdict v = inbound.computeIfAbsent(id, k -> new InboundGuard()).admit(System.nanoTime());
        if (v == InboundGuard.Verdict.KICK) SINK.kick(id, "Too many multiplayer messages.");
        return v == InboundGuard.Verdict.OK;
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void recv(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<T> type, ServerPlayNetworking.PlayPayloadHandler<T> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, ctx) -> {
            if (admit(ctx.player().getUUID())) handler.receive(payload, ctx);
        });
    }

    private static void forget(UUID id) {
        inbound.remove(id);
        standingOn.remove(id);
        ShipSink ships = shipSink;
        if (ships != null && members.contains(id)) ships.left(id);
        shipLimits.remove(id);
        shipPoseLimits.remove(id);
        tripLimits.remove(id);
        starLimits.remove(id);
        useLimits.remove(id);
        usedUpLimits.remove(id);
        members.remove(id);
        askLimits.remove(id);
        outstanding.remove(id);
        var box = outboxes.remove(id);
        if (box != null) box.clear();
        EditSink sink = editSink;
        if (sink != null) for (int planet : hub.planetsOf(id)) sink.dropped(id, planet);
        hub.forget(id);
        editLimits.remove(id);
        editBytes.remove(id);
        editBad.remove(id);
    }

    /** Guests that answered Hello well, and players this server sent away: for tests and the log. */
    private static volatile int entered, kicks;

    public static int entered() {
        return entered;
    }

    public static int kicks() {
        return kicks;
    }

    private static ServerPlayer player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    private static final Relay.Sink SINK = new Relay.Sink() {
        @Override
        public void peerState(UUID to, UUID from, PeerState s) {
            ServerPlayer p = player(to);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Peer(from, Wire.encode(s)));
        }

        @Override
        public void peerGone(UUID to, UUID gone) {
            ServerPlayer p = player(to);
            if (p != null) ServerPlayNetworking.send(p, new MpPayloads.Gone(gone));
        }

        @Override
        public void kick(UUID who, String reason) {
            ServerPlayer p = player(who);
            kicks++;
            GalaxyCraft.LOG.info("[GalaxyCraft mp] sending {} away: {}", who, reason);
            if (p != null) p.connection.disconnect(Component.literal(reason));
        }
    };

    /** A player who joins late gets the custom skins already worn. */
    private static void sendSkinsTo(ServerPlayer p) {
        for (var e : skins.entrySet())
            if (!e.getKey().equals(p.getUUID())) ServerPlayNetworking.send(p, new MpPayloads.Skin(e.getKey(), e.getValue()));
    }

    public static void init() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            server = srv;
            if (relay == null) relay = new Relay(SINK);
            ServerPlayer p = handler.getPlayer();
            // A client without the mod cannot even connect (the payload types are required), so every
            // player here can speak the protocol. The host's own client owns the galaxy: no Hello.
            // Only a world a player opened to LAN has a galaxy to give; a dedicated server has none (not supported yet).
            if (!srv.isSingleplayer()) return;
            if (p.getUUID().equals(new net.minecraft.server.players.NameAndId(srv.getSingleplayerProfile()).id())) {
                relay.join(p.getUUID());
                return;
            }
            // A client without the mod joins like any other and cannot take part: it is just not given a Hello.
            if (!ServerPlayNetworking.canSend(p, MpPayloads.Hello.TYPE)) return;
            GalaxySave galaxy = GalaxySave.of(srv.getWorldPath(LevelResource.ROOT));
            String json = galaxy.galaxyJson().orElse(null);
            if (json == null) {
                GalaxyCraft.LOG.warn("[GalaxyCraft mp] the world has no usable galaxy.json yet");
                SINK.kick(p.getUUID(), "The host's galaxy is not ready yet. Try again in a moment.");
                return;
            }
            relay.join(p.getUUID());
            waiting.put(p.getUUID(), srv.getTickCount());
            ServerPlayNetworking.send(p, new MpPayloads.Hello(Protocol.VERSION, galaxy.worldId().toString(), json));
            sendSkinsTo(p);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            UUID id = handler.getPlayer().getUUID();
            waiting.remove(id);
            skins.remove(id);
            skinLimits.remove(id);
            forget(id);
            if (relay != null) relay.leave(id);
        });
        recv(MpPayloads.HelloAck.TYPE, (payload, ctx) -> {
            UUID id = ctx.player().getUUID();
            waiting.remove(id);
            var refusal = Protocol.refusal(Protocol.VERSION, payload.version());
            if (refusal.isPresent()) SINK.kick(id, refusal.get());
            else if (!payload.ok()) SINK.kick(id, "Your game could not enter the host's galaxy. See its log.");
            else {
                entered++;
                members.add(id);
                var joined = joinListener;
                if (joined != null) joined.accept(id);
                GalaxyCraft.LOG.info("[GalaxyCraft mp] {} entered the galaxy", ctx.player().getName().getString());
                StarSink stars = starSink;
                if (stars != null) stars.joined(id);
                ShipSink ships = shipSink;
                if (ships != null) ships.joined(id);
            }
        });
        recv(MpPayloads.PlanetAsk.TYPE, (payload, ctx) -> ask(ctx.player(), payload));
        recv(MpPayloads.Edits.TYPE, (payload, ctx) -> edits(ctx.player(), payload));
        recv(MpPayloads.PlanetDrop.TYPE, (payload, ctx) -> {
            UUID id = ctx.player().getUUID();
            if (!hub.subscribed(id, payload.index())) return;
            hub.cancel(id, payload.index());
            EditSink sink = editSink;
            if (sink != null) sink.dropped(id, payload.index());
        });
        recv(MpPayloads.Use.TYPE, (payload, ctx) -> use(ctx.player(), payload));
        recv(MpPayloads.MobHit.TYPE, (payload, ctx) -> mobHit(ctx.player(), payload));
        recv(MpPayloads.DropTake.TYPE, (payload, ctx) -> takeDrop(ctx.player(), payload));
        recv(MpPayloads.Break.TYPE, (payload, ctx) -> breakBlock(ctx.player(), payload));
        recv(MpPayloads.Throw.TYPE, (payload, ctx) -> throwItem(ctx.player(), payload));
        recv(MpPayloads.ThrowStack.TYPE, (payload, ctx) -> throwStack(ctx.player(), payload));
        recv(MpPayloads.Where.TYPE, (payload, ctx) -> where(ctx.player(), payload));
        recv(MpPayloads.Steer.TYPE, (payload, ctx) -> steer(ctx.player(), payload));
        recv(MpPayloads.UsedUp.TYPE, (payload, ctx) -> usedUp(ctx.player(), payload));
        recv(MpPayloads.StarDo.TYPE, (payload, ctx) -> starDo(ctx.player(), payload));
        recv(MpPayloads.ShipDo.TYPE, (payload, ctx) -> shipDo(ctx.player(), payload));
        recv(MpPayloads.ShipPose.TYPE, (payload, ctx) -> shipPose(ctx.player(), payload));
        recv(MpPayloads.ToadDo.TYPE, (payload, ctx) -> toadDo(ctx.player(), payload));
        recv(MpPayloads.TripDo.TYPE, (payload, ctx) -> tripDo(ctx.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(MpServer::pump);
        recv(MpPayloads.State.TYPE, (payload, ctx) -> {
            if (relay != null) relay.state(ctx.player().getUUID(), payload.data(), System.nanoTime());
        });
        recv(MpPayloads.Skin.TYPE, (payload, ctx) -> {
            UUID from = ctx.player().getUUID();
            byte[] png = payload.data();
            boolean clear = SkinCodec.isClear(png);
            if (!skinLimits.computeIfAbsent(from, k -> new RateLimiter(1, 3)).allow(System.nanoTime())) return; // before the inflate
            if (!SkinCodec.acceptable(png)) return; // junk is dropped, not passed on
            if (clear) skins.remove(from);
            else skins.put(from, png);
            for (ServerPlayer other : ctx.server().getPlayerList().getPlayers())
                if (!other.getUUID().equals(from)) ServerPlayNetworking.send(other, new MpPayloads.Skin(from, png));
        });
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            if (waiting.isEmpty()) return;
            waiting.entrySet().removeIf(e -> {
                if (srv.getTickCount() - e.getValue() < ACK_TICKS) return false;
                SINK.kick(e.getKey(), "Your Super Minecraft Galaxy did not answer. Is the mod installed, the same version as the host's?");
                return true;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(srv -> {
            relay = null;
            server = null;
            waiting.clear();
            skins.clear();
            skinLimits.clear();
            members.clear();
            askLimits.clear();
            outstanding.clear();
            outboxes.clear();
            editLimits.clear();
            inbound.clear();
            editBytes.clear();
            editBad.clear();
            useLimits.clear();
            usedUpLimits.clear();
            starLimits.clear();
            shipLimits.clear();
            shipPoseLimits.clear();
            hub = newHub();
        });
        GalaxyCraft.LOG.info("Multiplayer ready (protocol {})", Protocol.VERSION);
    }
}
