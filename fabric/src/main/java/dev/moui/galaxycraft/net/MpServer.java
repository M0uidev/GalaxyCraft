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
            box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_FAIL, 0, 0, 0), 32);
            return;
        }
        if (offer.etag() == guestEtag) {
            box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_SAME, offer.etag(), 0, offer.seq()), 32);
            hub.deliver(guest, index, offer.seq());
            return;
        }
        byte[] data = offer.data();
        box.add(new MpPayloads.PlanetHead(index, PlanetXfer.STATE_DATA, offer.etag(), data.length, offer.seq()), 32);
        for (int seq = 0; seq < PlanetXfer.chunks(data.length); seq++)
            box.add(new MpPayloads.PlanetChunk(index, seq, PlanetXfer.slice(data, seq)), Math.min(PlanetXfer.CHUNK, data.length - seq * PlanetXfer.CHUNK));
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
            outboxes.computeIfAbsent(id, k -> new PlanetXfer.Outbox<>()).add(new MpPayloads.PlanetHead(ask.index(), PlanetXfer.STATE_FAIL, 0, 0, 0), 32);
            return;
        }
        outstanding.merge(id, 1, Integer::sum);
        hub.subscribe(id, ask.index()); // edits from now on wait for the planet's bytes
        source.ask(id, ask.index(), ask.etag());
    }

    private static void pump(MinecraftServer srv) {
        for (var e : outboxes.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            ServerPlayer p = player(e.getKey());
            if (p != null) e.getValue().drain(PlanetXfer.BUDGET_PER_TICK - EDIT_BUDGET, payload -> ServerPlayNetworking.send(p, payload));
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

    private static final Map<UUID, RateLimiter> usedUpLimits = new HashMap<>();

    private static void usedUp(ServerPlayer p, MpPayloads.UsedUp u) {
        UUID id = p.getUUID();
        if (!members.contains(id) || (u.hand() != 0 && u.hand() != 1)) return;
        if (!usedUpLimits.computeIfAbsent(id, k -> new RateLimiter(20, 10)).allow(System.nanoTime())) return;
        var hand = u.hand() == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
        var stack = p.getItemInHand(hand);
        if (!p.hasInfiniteMaterials() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) stack.shrink(1);
    }

    private static void forget(UUID id) {
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
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.HelloAck.TYPE, (payload, ctx) -> {
            UUID id = ctx.player().getUUID();
            waiting.remove(id);
            var refusal = Protocol.refusal(Protocol.VERSION, payload.version());
            if (refusal.isPresent()) SINK.kick(id, refusal.get());
            else if (!payload.ok()) SINK.kick(id, "Your game could not enter the host's galaxy. See its log.");
            else {
                entered++;
                members.add(id);
                GalaxyCraft.LOG.info("[GalaxyCraft mp] {} entered the galaxy", ctx.player().getName().getString());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.PlanetAsk.TYPE, (payload, ctx) -> ask(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.Edits.TYPE, (payload, ctx) -> edits(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.PlanetDrop.TYPE, (payload, ctx) -> {
            UUID id = ctx.player().getUUID();
            if (!hub.subscribed(id, payload.index())) return;
            hub.cancel(id, payload.index());
            EditSink sink = editSink;
            if (sink != null) sink.dropped(id, payload.index());
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.Use.TYPE, (payload, ctx) -> use(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.MobHit.TYPE, (payload, ctx) -> mobHit(ctx.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.UsedUp.TYPE, (payload, ctx) -> usedUp(ctx.player(), payload));
        ServerTickEvents.END_SERVER_TICK.register(MpServer::pump);
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.State.TYPE, (payload, ctx) -> {
            if (relay != null) relay.state(ctx.player().getUUID(), payload.data(), System.nanoTime());
        });
        ServerPlayNetworking.registerGlobalReceiver(MpPayloads.Skin.TYPE, (payload, ctx) -> {
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
            editBytes.clear();
            editBad.clear();
            useLimits.clear();
            usedUpLimits.clear();
            hub = newHub();
        });
        GalaxyCraft.LOG.info("Multiplayer ready (protocol {})", Protocol.VERSION);
    }
}
