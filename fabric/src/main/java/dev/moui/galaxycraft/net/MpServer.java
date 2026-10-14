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

    private static MinecraftServer server;
    private static Relay relay;
    /** Guests that were sent a Hello: the server tick it was sent on. */
    private static final Map<UUID, Integer> waiting = new HashMap<>();
    /** Each player's latest custom skin, for those who join later; and how often they may change it. */
    private static final Map<UUID, byte[]> skins = new HashMap<>();
    private static final Map<UUID, RateLimiter> skinLimits = new HashMap<>();

    private MpServer() {}

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
                GalaxyCraft.LOG.info("[GalaxyCraft mp] {} entered the galaxy", ctx.player().getName().getString());
            }
        });
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
        });
        GalaxyCraft.LOG.info("Multiplayer ready (protocol {})", Protocol.VERSION);
    }
}
