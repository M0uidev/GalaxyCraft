package dev.moui.galaxycraft.net;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The server's whole job in MP-1: take a player's state, check it, hand it to everyone else. */
public final class Relay {
    public interface Sink {
        void peerState(UUID to, UUID from, PeerState s);

        void peerGone(UUID to, UUID gone);

        void kick(UUID who, String reason);
    }

    private static final int MALFORMED_KICK = 5;

    private static final class Peer {
        final RateLimiter limit = new RateLimiter(20, 8);
        int malformed;
    }

    private final Sink sink;
    private final Map<UUID, Peer> peers = new LinkedHashMap<>();

    public Relay(Sink sink) {
        this.sink = sink;
    }

    public void join(UUID id) {
        if (peers.size() >= Protocol.MAX_PLAYERS && !peers.containsKey(id)) {
            sink.kick(id, "The galaxy is full (" + Protocol.MAX_PLAYERS + " players).");
            return;
        }
        peers.put(id, new Peer());
    }

    public void leave(UUID id) {
        if (peers.remove(id) == null) return;
        for (UUID other : peers.keySet()) sink.peerGone(other, id);
    }

    public void state(UUID from, byte[] raw, long nowNanos) {
        Peer p = peers.get(from);
        if (p == null) return;
        if (!p.limit.allow(nowNanos)) return;
        PeerState s;
        try {
            s = Wire.decode(raw);
        } catch (Wire.Malformed e) {
            if (++p.malformed >= MALFORMED_KICK) {
                sink.kick(from, "Too many malformed multiplayer packets.");
                leave(from);
            }
            return;
        }
        if (!s.valid()) return;
        p.malformed = 0;
        for (UUID other : peers.keySet())
            if (!other.equals(from)) sink.peerState(other, from, s);
    }
}
